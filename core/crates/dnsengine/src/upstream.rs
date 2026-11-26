//! Upstream resolvers: the network's own DNS over UDP (default), DNS-over-HTTPS (h2) and
//! DNS-over-TLS (pipelined). Encrypted connections are reused while queries flow and closed
//! after a short idle period so they never keep the radio awake with keepalives.

use crate::dns;
use crate::tls;
use bytes::Bytes;
use http_body_util::{BodyExt, Full};
use hyper::client::conn::http2::SendRequest;
use hyper_util::rt::{TokioExecutor, TokioIo};
use parking_lot::Mutex;
use rustls_pki_types::ServerName;
use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt, WriteHalf};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::oneshot;
use tokio_rustls::TlsConnector;
use tokio_rustls::client::TlsStream;

/// A query exchange with one server, boxed so the second server of a race can start later.
type Exchange<'a> = std::pin::Pin<Box<dyn std::future::Future<Output = io::Result<Vec<u8>>> + Send + 'a>>;

/// One server gets this long in total; the query is resent (same ID, same socket, so a late
/// answer to an earlier copy still counts) at the offsets below. A single lost datagram on
/// Wi-Fi then costs 0.7 s instead of a failed lookup.
const UDP_BUDGET: Duration = Duration::from_millis(3500);
const UDP_RESEND_MS: [u64; 2] = [700, 1500];
const TCP_TIMEOUT: Duration = Duration::from_millis(4000);
const TLS_TIMEOUT: Duration = Duration::from_millis(5000);
const IDLE_CLOSE: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
#[derive(Default)]
pub enum UpstreamConfig {
    /// The underlying network's resolvers (pushed separately on every network change).
    #[default]
    System,
    Doh {
        url: String,
        #[serde(default)]
        bootstrap: Vec<IpAddr>,
    },
    Dot {
        host: String,
        #[serde(default = "dot_port")]
        port: u16,
        #[serde(default)]
        bootstrap: Vec<IpAddr>,
    },
}

fn dot_port() -> u16 {
    853
}

pub enum Upstream {
    System,
    Doh(Arc<Doh>),
    Dot(Arc<Dot>),
}

impl Upstream {
    pub fn from_config(c: &UpstreamConfig) -> io::Result<Upstream> {
        Ok(match c {
            UpstreamConfig::System => Upstream::System,
            UpstreamConfig::Doh { url, bootstrap } => Upstream::Doh(Arc::new(Doh::new(url, bootstrap)?)),
            UpstreamConfig::Dot { host, port, bootstrap } => Upstream::Dot(Arc::new(Dot::new(host, *port, bootstrap))),
        })
    }

    /// Sends `q` (as received from the client) and returns the response with the client's ID.
    /// The network changed (`online`: there is one now). What belongs to the old one goes:
    /// an open connection is closed at once (a query waiting on it is asked again on the new
    /// network, not after its whole timeout), a dial running there is given up (see
    /// [`dial_on_this_network`]), and a failed dial there no longer refuses lookups. Connections
    /// and failed dials carry the network they belong to, so a result that lands after the
    /// change is never taken for the new network's.
    pub fn reset_connections(&self, online: bool) {
        let next = |n: &mut Net| {
            n.n += 1;
            n.online = online;
        };
        match self {
            Upstream::System => {}
            Upstream::Doh(d) => {
                d.network.send_modify(next);
                if let Ok(mut g) = d.sender.try_lock() {
                    d.drop_conn(&mut g);
                }
            }
            Upstream::Dot(d) => {
                d.network.send_modify(next);
                if let Ok(mut g) = d.conn.try_lock()
                    && let Some(c) = g.take()
                {
                    c.close();
                }
            }
        }
    }

    pub async fn exchange(&self, system: &[SocketAddr], q: &[u8]) -> io::Result<Vec<u8>> {
        match self {
            Upstream::System => system_exchange(system, q).await,
            Upstream::Doh(d) => d.exchange(q).await,
            Upstream::Dot(d) => d.exchange(q).await,
        }
    }

    /// Which resolver this is, for the diagnostics ("system", "doh dns.example", "dot …").
    pub fn describe(&self) -> String {
        match self {
            Upstream::System => "system".into(),
            Upstream::Doh(d) => format!("doh {}", d.host),
            Upstream::Dot(d) => format!("dot {}", d.host),
        }
    }

    /// Closes encrypted connections idle for a moment (the screen went off). Their
    /// own idle timer does not run while the phone sleeps, and the server closing them later
    /// would wake it.
    pub fn close_idle(&self) {
        match self {
            Upstream::System => {}
            Upstream::Doh(d) => d.close_idle(),
            Upstream::Dot(d) => d.close_idle(),
        }
    }
}

/// Idle at least this long when the screen goes off: closed.
const CLOSE_ON_SCREEN_OFF_MS: u64 = 2000;
/// After a failed dial of an encrypted resolver, queries fail at once for this long (otherwise
/// they queue behind the lock and dial again one by one, minutes in all).
const DIAL_FAILED_MS: u64 = 3000;
/// Unacknowledged data older than this kills an encrypted resolver's socket.
#[cfg(any(target_os = "linux", target_os = "android"))]
const ENCRYPTED_USER_TIMEOUT_MS: libc::c_uint = 10_000;

/// The refusal while [`dial_failed_recently`]: our own, at once, not the resolver's (kept out
/// of `upstream_latency`).
pub(crate) const DIAL_FAILED_RECENTLY: &str = "encrypted resolver unreachable a moment ago";

/// The network an encrypted resolver's connections and dials belong to: a number bumped on
/// every change ([`Upstream::reset_connections`]), and whether there is a network at all.
#[derive(Clone, Copy)]
struct Net {
    n: u64,
    online: bool,
}

fn new_net() -> tokio::sync::watch::Sender<Net> {
    tokio::sync::watch::Sender::new(Net { n: 0, online: true })
}

/// True while a dial that failed at boot ms `at` (0: none) on network `on` still counts: only
/// on that network.
fn dial_failed_recently(failed: &Mutex<(u64, u64)>, network: &tokio::sync::watch::Sender<Net>) -> bool {
    let (on, at) = *failed.lock();
    at != 0 && on == network.borrow().n && now_ms().saturating_sub(at) < DIAL_FAILED_MS
}

/// Runs `dial` on the current network: when the network changes meanwhile, the dial is given
/// up and made again on the new one, and when the network is gone, given up. A result from an
/// old network is never returned: a failure there must not refuse lookups on the new one for
/// [`DIAL_FAILED_MS`], and a connection made there is bound to it. Returns the result with the
/// network it belongs to, or None with an error when it was given up (nothing to record). A
/// network that keeps changing gets three dials.
async fn dial_on_this_network<T, F: std::future::Future<Output = io::Result<T>>>(
    network: &tokio::sync::watch::Sender<Net>,
    mut dial: impl FnMut() -> F,
) -> (Option<u64>, io::Result<T>) {
    let mut rx = network.subscribe();
    let mut last = io::Error::new(ErrorKind::NotConnected, "moved to another network while connecting");
    for _ in 0..3 {
        let net = *rx.borrow_and_update();
        if !net.online {
            return (None, Err(io::Error::new(ErrorKind::NetworkDown, "no network")));
        }
        tokio::select! {
            r = dial() => {
                if !rx.has_changed().unwrap_or(false) {
                    return (Some(net.n), r);
                }
                if let Err(e) = r {
                    last = e;
                }
            }
            Ok(()) = rx.changed() => {}
        }
    }
    (None, Err(last))
}

/// Counts a query in flight on an encrypted resolver while it lives (see `close_idle`).
struct InFlight<'a>(&'a std::sync::atomic::AtomicUsize);

impl<'a> InFlight<'a> {
    fn new(n: &'a std::sync::atomic::AtomicUsize) -> Self {
        n.fetch_add(1, Ordering::AcqRel);
        InFlight(n)
    }
}

impl Drop for InFlight<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

/// Query IDs from the system CSPRNG: together with the random source port they are what keeps
/// an off-path attacker from forging answers, so they must not be predictable.
pub fn rand_u16() -> u16 {
    use ring::rand::SecureRandom as _;
    static RNG: std::sync::OnceLock<ring::rand::SystemRandom> = std::sync::OnceLock::new();
    let mut b = [0u8; 2];
    if RNG.get_or_init(ring::rand::SystemRandom::new).fill(&mut b).is_err() {
        // Practically impossible (getrandom); fall back to the clock rather than fail a query.
        let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos()).unwrap_or(0);
        return (t ^ (t >> 16)) as u16;
    }
    u16::from_ne_bytes(b)
}

/// The network's server that answered last, as (fingerprint of the server list, index): a
/// preference learned on one network never carries over to another list.
static PREFERRED: Mutex<(u64, usize)> = Mutex::new((0, 0));

/// When the servers asked so far have not answered yet, the next one is asked too (whichever
/// answers first wins): a dead server then costs a second, not its whole budget.
const SECOND_SERVER_AFTER: Duration = Duration::from_millis(1000);

fn servers_key(servers: &[SocketAddr]) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    servers.hash(&mut h);
    h.finish()
}

/// A SERVFAIL or REFUSED answer: the server could not answer now.
fn server_failed(r: &[u8]) -> bool {
    matches!(dns::rcode_of(r), dns::RCODE_SERVFAIL | dns::RCODE_REFUSED)
}

pub(crate) async fn system_exchange(servers: &[SocketAddr], q: &[u8]) -> io::Result<Vec<u8>> {
    if servers.is_empty() {
        return Err(io::Error::new(ErrorKind::NotConnected, "no upstream DNS servers"));
    }
    let key = servers_key(servers);
    let start = {
        let p = PREFERRED.lock();
        if p.0 == key { p.1 % servers.len() } else { 0 }
    };
    if servers.len() == 1 {
        return udp_exchange(servers[0], q).await;
    }
    // Every server of the network, the one that answered last first, then in the network's
    // order; the next one is asked when those asked so far failed (at once) or have not answered
    // within SECOND_SERVER_AFTER. Two dead resolvers first (IPv6 ones on a network whose IPv6
    // does not work) then cost two seconds, not every lookup.
    let mut queue = (0..servers.len()).map(|i| (start + i) % servers.len());
    let ask = |i: usize| -> (usize, Exchange<'_>) { (i, Box::pin(udp_exchange(servers[i], q))) };
    let mut asked: Vec<(usize, Exchange<'_>)> = Vec::with_capacity(servers.len());
    asked.extend(queue.next().map(ask));
    let delay = tokio::time::sleep(SECOND_SERVER_AFTER);
    tokio::pin!(delay);
    // A server's SERVFAIL/REFUSED is kept while another one may still answer properly, so it
    // does not win the race at once; the first such answer is returned when all fail.
    let mut failed_answer: Option<Vec<u8>> = None;
    loop {
        let more = queue.len() != 0;
        tokio::select! {
            (i, r) = next_answer(&mut asked) => {
                let e = match r {
                    Ok(v) if !server_failed(&v) => {
                        *PREFERRED.lock() = (key, i);
                        return Ok(v);
                    }
                    Ok(v) => {
                        failed_answer.get_or_insert(v);
                        io::Error::other("server failure")
                    }
                    Err(e) => e,
                };
                // Failed fast (unreachable, refused, SERVFAIL): no need to wait for the delay.
                match queue.next() {
                    Some(j) => {
                        asked.push(ask(j));
                        delay.as_mut().reset(tokio::time::Instant::now() + SECOND_SERVER_AFTER);
                    }
                    None if asked.is_empty() => return failed_answer.map(Ok).unwrap_or(Err(e)),
                    None => {}
                }
            }
            _ = &mut delay, if more => {
                asked.extend(queue.next().map(ask));
                delay.as_mut().reset(tokio::time::Instant::now() + SECOND_SERVER_AFTER);
            }
        }
    }
}

/// The first of `asked` to finish, removed from it, with its server's index. Pending while
/// `asked` is empty.
async fn next_answer(asked: &mut Vec<(usize, Exchange<'_>)>) -> (usize, io::Result<Vec<u8>>) {
    std::future::poll_fn(|cx| {
        for k in 0..asked.len() {
            if let std::task::Poll::Ready(r) = asked[k].1.as_mut().poll(cx) {
                let (i, _) = asked.swap_remove(k);
                return std::task::Poll::Ready((i, r));
            }
        }
        std::task::Poll::Pending
    })
    .await
}

/// The handle of the network whose resolvers are in use (0: none known), set with them.
pub(crate) static BIND_HANDLE: AtomicU64 = AtomicU64::new(0);

/// Puts socket `fd` on the network whose resolvers are in use, so a query to that
/// network's resolver never leaves through another one during a handover. Nothing happens when
/// no network is known or binding fails (the network may just have gone): the socket follows
/// the default network then.
#[cfg(target_os = "android")]
pub fn bind_to_network(fd: std::os::fd::RawFd) {
    #[link(name = "android")]
    unsafe extern "C" {
        fn android_setsocknetwork(network: u64, fd: libc::c_int) -> libc::c_int;
    }
    let h = BIND_HANDLE.load(Ordering::Relaxed);
    if h != 0 && unsafe { android_setsocknetwork(h, fd) } != 0 {
        log::debug!("binding a socket to network {h}: {}", io::Error::last_os_error());
    }
}

#[cfg(all(unix, not(target_os = "android")))]
pub fn bind_to_network(_fd: std::os::fd::RawFd) {}

/// A connected TCP socket to `addr` on the resolvers' network.
async fn tcp_connect(addr: SocketAddr) -> io::Result<TcpStream> {
    let s = if addr.is_ipv4() { tokio::net::TcpSocket::new_v4()? } else { tokio::net::TcpSocket::new_v6()? };
    #[cfg(unix)]
    bind_to_network(std::os::fd::AsRawFd::as_raw_fd(&s));
    s.connect(addr).await
}

/// TCP_USER_TIMEOUT on an encrypted resolver's socket: a silently dead path fails
/// in seconds instead of minutes.
fn short_user_timeout(_s: &TcpStream) {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        use std::os::fd::AsRawFd;
        let v = ENCRYPTED_USER_TIMEOUT_MS;
        unsafe {
            libc::setsockopt(
                _s.as_raw_fd(),
                libc::IPPROTO_TCP,
                libc::TCP_USER_TIMEOUT,
                (&v as *const libc::c_uint).cast(),
                std::mem::size_of_val(&v) as libc::socklen_t,
            );
        }
    }
}

/// The longest DNS message (RFC 1035 over TCP, RFC 8484).
const MAX_MESSAGE: usize = 65_535;

/// Whether `r` is an answer (QR set) to the question of `q`, as UDP and TCP answers are checked
/// (DoH and DoT answers used to be taken as they came).
fn answers(q: &[u8], r: &[u8]) -> bool {
    r.len() >= 12 && r[2] & 0x80 != 0 && same_question(q, r)
}

fn same_question(q: &[u8], r: &[u8]) -> bool {
    // Compare the question section: the name case-insensitively (resolvers may echo another
    // case), its type and class byte for byte.
    let Some(end) = question_end(q) else { return false };
    r.len() >= end && q[12..end - 4].eq_ignore_ascii_case(&r[12..end - 4]) && q[end - 4..end] == r[end - 4..end]
}

fn question_end(b: &[u8]) -> Option<usize> {
    let mut off = 12;
    loop {
        let l = *b.get(off)? as usize;
        off += 1 + l;
        if l == 0 {
            break;
        }
        if l > 63 {
            return None;
        }
    }
    (off + 4 <= b.len()).then_some(off + 4)
}

/// Queries sent again over UDP because no answer came in time (Diagnostics `dns_resent`: shows
/// whether the first resend at 0.7 s fires on a fast LAN resolver).
pub static UDP_RESENT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

async fn udp_exchange(server: SocketAddr, q: &[u8]) -> io::Result<Vec<u8>> {
    let bind: SocketAddr = if server.is_ipv4() { "0.0.0.0:0".parse().unwrap() } else { "[::]:0".parse().unwrap() };
    let sock = UdpSocket::bind(bind).await?;
    #[cfg(unix)]
    bind_to_network(std::os::fd::AsRawFd::as_raw_fd(&sock));
    sock.connect(server).await?;
    let orig = dns::id_of(q);
    let id = rand_u16();
    let mut msg = q.to_vec();
    dns::set_id(&mut msg, id);
    sock.send(&msg).await?;
    let start = tokio::time::Instant::now();
    let deadline = start + UDP_BUDGET;
    let mut resends = UDP_RESEND_MS.iter().map(|ms| start + Duration::from_millis(*ms));
    let mut next_resend = resends.next();
    // Room for any datagram (a 4 KB buffer would cut larger answers silently).
    let mut buf = vec![0u8; 65535];
    loop {
        let wake = next_resend.map_or(deadline, |r| r.min(deadline));
        let n = match tokio::time::timeout_at(wake, sock.recv(&mut buf)).await {
            Ok(r) => r?,
            Err(_) if tokio::time::Instant::now() >= deadline => return Err(io::Error::new(ErrorKind::TimedOut, "udp timeout")),
            Err(_) => {
                sock.send(&msg).await?;
                UDP_RESENT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                next_resend = resends.next();
                continue;
            }
        };
        let r = &buf[..n];
        if n < 12 || dns::id_of(r) != id || r[2] & 0x80 == 0 || !same_question(&msg, r) {
            continue; // stray or spoofed datagram
        }
        if r[2] & 0x02 != 0 {
            return tcp_exchange(server, q).await;
        }
        buf.truncate(n);
        dns::set_id(&mut buf, orig);
        return Ok(buf);
    }
}

async fn tcp_exchange(server: SocketAddr, q: &[u8]) -> io::Result<Vec<u8>> {
    let fut = async {
        let mut s = tcp_connect(server).await?;
        s.set_nodelay(true)?;
        // A random ID and the question checked, as over UDP.
        let orig = dns::id_of(q);
        let id = rand_u16();
        let mut msg = Vec::with_capacity(q.len() + 2);
        msg.extend_from_slice(&(q.len() as u16).to_be_bytes());
        msg.extend_from_slice(q);
        dns::set_id(&mut msg[2..], id);
        s.write_all(&msg).await?;
        let len = s.read_u16().await? as usize;
        let mut r = vec![0u8; len];
        s.read_exact(&mut r).await?;
        if dns::id_of(&r) != id || !answers(&msg[2..], &r) {
            return Err(io::Error::new(ErrorKind::InvalidData, "bad tcp response"));
        }
        dns::set_id(&mut r, orig);
        Ok(r)
    };
    tokio::time::timeout(TCP_TIMEOUT, fut).await.map_err(|_| io::Error::new(ErrorKind::TimedOut, "tcp timeout"))?
}

/// Idle time is measured on the boot clock: after the phone slept, a connection that looks a
/// few seconds old to the monotonic clock may have been dead for minutes (NAT timeout).
fn now_ms() -> u64 {
    crate::clock::boot_ms()
}

/// Marks the connection for replacement when the previous query was IDLE_CLOSE or more ago
/// (the idle timer may not have fired: it does not run while the phone sleeps).
fn touch(last_used: &AtomicU64, stale: &AtomicBool) {
    let now = now_ms();
    let prev = last_used.swap(now, Ordering::Relaxed);
    if prev != 0 && now.saturating_sub(prev) >= IDLE_CLOSE.as_millis() as u64 {
        stale.store(true, Ordering::Release);
    }
}

/// The next resolver address is tried this long after the previous one, while that one is still
/// trying (strictly one after another, a lost SYN or TLS hello would hold every lookup of the
/// phone for up to 5 s per address).
const NEXT_ADDRESS_AFTER: Duration = Duration::from_millis(250);
/// The first address once more, from a new port, if nothing has connected by then.
const BACKUP_DIAL_AFTER: Duration = Duration::from_millis(1000);

/// One system lookup of a resolver's name at a time. A lookup given up (its timeout, a network
/// change) still holds its blocking thread until the system resolver returns, which can take
/// half a minute; the DNS thread has four of them, also used for app lookups and stats writes.
static NAME_LOOKUPS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(1);

async fn lookup_resolver(host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
    let permit = NAME_LOOKUPS.acquire().await.map_err(io::Error::other)?;
    let host = host.to_string();
    tokio::task::spawn_blocking(move || {
        let _held = permit;
        std::net::ToSocketAddrs::to_socket_addrs(&(host.as_str(), port)).map(|a| a.collect())
    })
    .await
    .map_err(io::Error::other)?
}

async fn tls_connect(host: &str, port: u16, bootstrap: &[IpAddr], connector: &TlsConnector) -> io::Result<TlsStream<TcpStream>> {
    // An IPv6 literal comes in brackets from a URL (`https://[2606:4700::1111]/dns-query`); the
    // lookup and the certificate check take the bare address.
    let host = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')).unwrap_or(host);
    let addrs: Vec<SocketAddr> = if bootstrap.is_empty() {
        // The system's own lookup (a custom server without bootstrap addresses) is bounded like
        // the dial: every lookup of the phone waits behind it.
        tokio::time::timeout(TLS_TIMEOUT, lookup_resolver(host, port))
            .await
            .map_err(|_| io::Error::new(ErrorKind::TimedOut, "resolver name lookup timeout"))??
    } else {
        bootstrap.iter().map(|ip| SocketAddr::new(*ip, port)).collect()
    };
    let name = ServerName::try_from(host.to_string()).map_err(|e| io::Error::new(ErrorKind::InvalidInput, e))?;
    let Some(&first) = addrs.first() else { return Err(io::Error::new(ErrorKind::NotFound, "no address")) };
    let plan = addrs.iter().enumerate().map(|(i, a)| (NEXT_ADDRESS_AFTER * i as u32, *a)).chain(std::iter::once((BACKUP_DIAL_AFTER, first)));
    // The first handshake to finish wins; dropping the set closes the others.
    let mut attempts = tokio::task::JoinSet::new();
    for (delay, a) in plan {
        let (connector, name) = (connector.clone(), name.clone());
        attempts.spawn(async move {
            tokio::time::sleep(delay).await;
            let attempt = async {
                let tcp = tcp_connect(a).await?;
                tcp.set_nodelay(true)?;
                short_user_timeout(&tcp);
                connector.connect(name, tcp).await
            };
            tokio::time::timeout(TLS_TIMEOUT, attempt).await.unwrap_or_else(|_| Err(io::Error::new(ErrorKind::TimedOut, "tls connect timeout")))
        });
    }
    let mut last = io::Error::new(ErrorKind::NotFound, "no address");
    while let Some(r) = attempts.join_next().await {
        match r {
            Ok(Ok(s)) => return Ok(s),
            Ok(Err(e)) => last = e,
            Err(e) => last = io::Error::other(e),
        }
    }
    Err(last)
}

pub struct Doh {
    uri: hyper::Uri,
    host: String,
    port: u16,
    bootstrap: Vec<IpAddr>,
    connector: TlsConnector,
    sender: tokio::sync::Mutex<Option<SendRequest<Full<Bytes>>>>,
    /// The connection's task: aborted to close it at once (screen off).
    conn_task: Mutex<Option<tokio::task::AbortHandle>>,
    last_used: AtomicU64,
    watcher: Watcher,
    stale: AtomicBool,
    /// The network and boot ms of the last failed dial (0: none), see [`DIAL_FAILED_MS`].
    dial_failed: Mutex<(u64, u64)>,
    /// The number of the connection in `sender` (changed only with `sender` locked).
    dials: AtomicU64,
    /// The current network (see [`Net`]).
    network: tokio::sync::watch::Sender<Net>,
    /// The network the connection in `sender` was made on.
    conn_net: AtomicU64,
    /// Queries waiting for an answer now.
    in_flight: std::sync::atomic::AtomicUsize,
}

/// The idle watcher of one encrypted upstream. It is a task of the runtime that opened the
/// connection; when that runtime goes away (tunnel rebuilt, proxy stopped) the task dies with
/// it, so "is one running" is asked of the task itself, not of a flag it can no longer clear.
#[derive(Default)]
struct Watcher(Mutex<Option<tokio::task::JoinHandle<()>>>);

impl Watcher {
    /// Starts `task` unless a watcher is still alive.
    fn ensure<F: std::future::Future<Output = ()> + Send + 'static>(&self, task: impl FnOnce() -> F) {
        let mut g = self.0.lock();
        if g.as_ref().is_some_and(|h| !h.is_finished()) {
            return;
        }
        *g = Some(tokio::spawn(task()));
    }
}

impl Doh {
    fn new(url: &str, bootstrap: &[IpAddr]) -> io::Result<Doh> {
        let uri: hyper::Uri = url.parse().map_err(|e| io::Error::new(ErrorKind::InvalidInput, format!("{e}")))?;
        let host = uri.host().ok_or_else(|| io::Error::new(ErrorKind::InvalidInput, "DoH URL without host"))?.to_string();
        let port = uri.port_u16().unwrap_or(443);
        Ok(Doh {
            uri,
            host,
            port,
            bootstrap: bootstrap.to_vec(),
            connector: TlsConnector::from(tls::client_config(&[b"h2"])),
            sender: tokio::sync::Mutex::new(None),
            conn_task: Mutex::new(None),
            last_used: AtomicU64::new(0),
            watcher: Watcher::default(),
            stale: AtomicBool::new(false),
            dial_failed: Mutex::new((0, 0)),
            dials: AtomicU64::new(0),
            network: new_net(),
            conn_net: AtomicU64::new(0),
            in_flight: std::sync::atomic::AtomicUsize::new(0),
        })
    }

    /// The connection to use, and its number (see [`Doh::forget`]).
    async fn sender(self: &Arc<Self>) -> io::Result<(u64, SendRequest<Full<Bytes>>)> {
        let mut g = self.sender.lock().await;
        if self.stale.swap(false, Ordering::AcqRel) || self.conn_net.load(Ordering::Acquire) != self.network.borrow().n {
            self.drop_conn(&mut g);
        }
        if let Some(s) = g.as_ref()
            && !s.is_closed()
        {
            return Ok((self.dials.load(Ordering::Relaxed), s.clone()));
        }
        if dial_failed_recently(&self.dial_failed, &self.network) {
            return Err(io::Error::new(ErrorKind::NotConnected, DIAL_FAILED_RECENTLY));
        }
        let (on, dialed) = dial_on_this_network(&self.network, || async {
            let tls = tls_connect(&self.host, self.port, &self.bootstrap, &self.connector).await?;
            let handshake = hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls));
            match tokio::time::timeout(TLS_TIMEOUT, handshake).await {
                Ok(r) => r.map_err(|e| io::Error::other(e.to_string())),
                Err(_) => Err(io::Error::new(ErrorKind::TimedOut, "doh handshake timeout")),
            }
        })
        .await;
        let (s, conn) = match (on, dialed) {
            (Some(on), Ok(v)) => {
                self.conn_net.store(on, Ordering::Release);
                v
            }
            (on, Err(e)) => {
                if let Some(on) = on {
                    *self.dial_failed.lock() = (on, now_ms().max(1));
                }
                return Err(e);
            }
            (None, Ok(_)) => return Err(io::Error::new(ErrorKind::NotConnected, "moved to another network while connecting")),
        };
        *self.dial_failed.lock() = (0, 0);
        let task = tokio::spawn(async move {
            let _ = conn.await;
        });
        *self.conn_task.lock() = Some(task.abort_handle());
        *g = Some(s.clone());
        let n = self.dials.fetch_add(1, Ordering::Relaxed) + 1;
        self.watch_idle();
        Ok((n, s))
    }

    /// Drops connection number `n` after a query failed on it, unless it was replaced meanwhile
    /// (otherwise each of a burst of queries failing on one connection also drops the new one
    /// another has just made, and they redial one after another).
    async fn forget(&self, n: u64) {
        let mut g = self.sender.lock().await;
        if self.dials.load(Ordering::Relaxed) == n {
            self.drop_conn(&mut g);
        }
    }

    /// Closes the connection in `g` (called with `sender` locked): its task is stopped too, so
    /// the socket goes at once instead of when the server gives up on it.
    fn drop_conn(&self, g: &mut Option<SendRequest<Full<Bytes>>>) {
        *g = None;
        if let Some(t) = self.conn_task.lock().take() {
            t.abort();
        }
    }

    fn close_idle(&self) {
        if now_ms().saturating_sub(self.last_used.load(Ordering::Relaxed)) < CLOSE_ON_SCREEN_OFF_MS || self.in_flight.load(Ordering::Acquire) > 0 {
            return;
        }
        if let Ok(mut g) = self.sender.try_lock() {
            self.drop_conn(&mut g);
        }
    }

    /// Drops the connection after IDLE_CLOSE without queries; one timer per active period.
    fn watch_idle(self: &Arc<Self>) {
        let me = self.clone();
        self.watcher.ensure(move || async move {
            loop {
                tokio::time::sleep(IDLE_CLOSE).await;
                if now_ms().saturating_sub(me.last_used.load(Ordering::Relaxed)) >= IDLE_CLOSE.as_millis() as u64 {
                    me.drop_conn(&mut *me.sender.lock().await);
                    return;
                }
            }
        });
    }

    async fn exchange(self: &Arc<Self>, q: &[u8]) -> io::Result<Vec<u8>> {
        touch(&self.last_used, &self.stale);
        let _in_flight = InFlight::new(&self.in_flight);
        let orig = dns::id_of(q);
        let mut msg = q.to_vec();
        dns::set_id(&mut msg, 0); // RFC 8484: ID 0 keeps HTTP caches effective
        let body = Bytes::from(msg.clone());
        let mut last = io::Error::other("doh failed");
        for _ in 0..2 {
            let (n, mut s) = self.sender().await?;
            let req = hyper::Request::post(self.uri.clone())
                .header(hyper::header::CONTENT_TYPE, "application/dns-message")
                .header(hyper::header::ACCEPT, "application/dns-message")
                .body(Full::new(body.clone()))
                .map_err(io::Error::other)?;
            let attempt = async {
                let resp = s.send_request(req).await.map_err(|e| io::Error::other(e.to_string()))?;
                if resp.status() != hyper::StatusCode::OK {
                    return Err(io::Error::other(format!("doh status {}", resp.status())));
                }
                // No DNS message is longer (the body was read whole, however large).
                let body = http_body_util::Limited::new(resp.into_body(), MAX_MESSAGE);
                let b = body.collect().await.map_err(|e| io::Error::other(e.to_string()))?.to_bytes();
                Ok::<_, io::Error>(b.to_vec())
            };
            // A network change while it waits: asked again on the new network at once (the old
            // network's connection would hold it for its whole timeout; stopping that
            // connection's task does not fail a request already sent).
            let mut moved = self.network.subscribe();
            moved.borrow_and_update();
            let answered = tokio::select! {
                r = tokio::time::timeout(TLS_TIMEOUT, attempt) => r,
                Ok(()) = moved.changed() => Ok(Err(io::Error::new(ErrorKind::ConnectionReset, "moved to another network"))),
            };
            match answered {
                // The server's own answer, just not to this question: asking again would get the
                // same, so neither a retry nor a new connection.
                Ok(Ok(r)) if !answers(&msg, &r) => return Err(io::Error::new(ErrorKind::InvalidData, "doh answer to another question")),
                Ok(Ok(mut r)) => {
                    dns::set_id(&mut r, orig);
                    return Ok(r);
                }
                Ok(Err(e)) => last = e,
                Err(_) => last = io::Error::new(ErrorKind::TimedOut, "doh timeout"),
            }
            self.forget(n).await;
        }
        Err(last)
    }
}

struct DotConn {
    writer: tokio::sync::Mutex<WriteHalf<TlsStream<TcpStream>>>,
    pending: Mutex<HashMap<u16, oneshot::Sender<Vec<u8>>>>,
    dead: AtomicBool,
    /// The reader task: aborted to close the connection at once (screen off, replaced).
    reader: Mutex<Option<tokio::task::AbortHandle>>,
}

impl DotConn {
    /// Marks it dead and stops its reader, which holds the socket open while it runs. Queries
    /// waiting on it fail at once (and are asked again on a new connection), not after their
    /// whole timeout.
    fn close(&self) {
        self.dead.store(true, Ordering::Release);
        if let Some(t) = self.reader.lock().take() {
            t.abort();
        }
        self.pending.lock().clear();
    }
}

/// Held by a DoT connection's reader task: however the task ends (the server closed, it was
/// aborted, or its runtime went away with a rebuilt tunnel, which drops the task without
/// running it to its end), the connection counts as dead and its waiters fail.
struct ReaderGone(Arc<DotConn>);

impl Drop for ReaderGone {
    fn drop(&mut self) {
        self.0.dead.store(true, Ordering::Release);
        self.0.pending.lock().clear();
    }
}

pub struct Dot {
    host: String,
    port: u16,
    bootstrap: Vec<IpAddr>,
    connector: TlsConnector,
    conn: tokio::sync::Mutex<Option<Arc<DotConn>>>,
    last_used: AtomicU64,
    watcher: Watcher,
    stale: AtomicBool,
    /// The network and boot ms of the last failed dial (0: none), see [`DIAL_FAILED_MS`].
    dial_failed: Mutex<(u64, u64)>,
    /// The current network (see [`Net`]).
    network: tokio::sync::watch::Sender<Net>,
    /// The network the connection in `conn` was made on.
    conn_net: AtomicU64,
    /// Queries waiting for an answer now.
    in_flight: std::sync::atomic::AtomicUsize,
}

impl Dot {
    fn new(host: &str, port: u16, bootstrap: &[IpAddr]) -> Dot {
        Dot {
            host: host.to_string(),
            port,
            bootstrap: bootstrap.to_vec(),
            connector: TlsConnector::from(tls::client_config(&[b"dot"])),
            conn: tokio::sync::Mutex::new(None),
            last_used: AtomicU64::new(0),
            watcher: Watcher::default(),
            stale: AtomicBool::new(false),
            dial_failed: Mutex::new((0, 0)),
            network: new_net(),
            conn_net: AtomicU64::new(0),
            in_flight: std::sync::atomic::AtomicUsize::new(0),
        }
    }

    fn close_idle(&self) {
        if now_ms().saturating_sub(self.last_used.load(Ordering::Relaxed)) < CLOSE_ON_SCREEN_OFF_MS || self.in_flight.load(Ordering::Acquire) > 0 {
            return;
        }
        if let Ok(mut g) = self.conn.try_lock()
            && let Some(c) = g.take()
        {
            c.close();
        }
    }

    async fn conn(self: &Arc<Self>) -> io::Result<Arc<DotConn>> {
        let mut g = self.conn.lock().await;
        let reuse = !self.stale.swap(false, Ordering::AcqRel) && self.conn_net.load(Ordering::Acquire) == self.network.borrow().n;
        if reuse
            && let Some(c) = g.as_ref()
            && !c.dead.load(Ordering::Acquire)
        {
            return Ok(c.clone());
        }
        // Replaced, from another network, or dead (a write failed, a query timed out): its
        // reader may still hold the socket.
        if let Some(c) = g.take() {
            c.close();
        }
        if dial_failed_recently(&self.dial_failed, &self.network) {
            return Err(io::Error::new(ErrorKind::NotConnected, DIAL_FAILED_RECENTLY));
        }
        let (on, dialed) = dial_on_this_network(&self.network, || tls_connect(&self.host, self.port, &self.bootstrap, &self.connector)).await;
        let tls = match (on, dialed) {
            (Some(on), Ok(t)) => {
                self.conn_net.store(on, Ordering::Release);
                t
            }
            (on, Err(e)) => {
                if let Some(on) = on {
                    *self.dial_failed.lock() = (on, now_ms().max(1));
                }
                return Err(e);
            }
            (None, Ok(_)) => return Err(io::Error::new(ErrorKind::NotConnected, "moved to another network while connecting")),
        };
        *self.dial_failed.lock() = (0, 0);
        let (mut reader, writer) = tokio::io::split(tls);
        let c = Arc::new(DotConn {
            writer: tokio::sync::Mutex::new(writer),
            pending: Mutex::new(HashMap::new()),
            dead: AtomicBool::new(false),
            reader: Mutex::new(None),
        });
        let gone = ReaderGone(c.clone());
        let task = tokio::spawn(async move {
            let rc = &gone.0;
            while let Ok(len) = reader.read_u16().await {
                let mut buf = vec![0u8; len as usize];
                if reader.read_exact(&mut buf).await.is_err() || buf.len() < 12 {
                    break;
                }
                if let Some(tx) = rc.pending.lock().remove(&dns::id_of(&buf)) {
                    let _ = tx.send(buf);
                }
            }
        });
        *c.reader.lock() = Some(task.abort_handle());
        *g = Some(c.clone());
        self.watch_idle();
        Ok(c)
    }

    fn watch_idle(self: &Arc<Self>) {
        let me = self.clone();
        self.watcher.ensure(move || async move {
            loop {
                tokio::time::sleep(IDLE_CLOSE).await;
                if me.close_if_idle().await {
                    return;
                }
            }
        });
    }

    /// Closes the connection when no query came for IDLE_CLOSE; true when it was idle.
    async fn close_if_idle(&self) -> bool {
        if now_ms().saturating_sub(self.last_used.load(Ordering::Relaxed)) < IDLE_CLOSE.as_millis() as u64 {
            return false;
        }
        // Taken out first: the resolver's lock is not held while the polite close waits (a
        // guard in the `if let` itself lives to the block's end).
        let taken = self.conn.lock().await.take();
        if let Some(c) = taken {
            // A polite close first (bounded: a stuck write holds the writer), then
            // the reader stops and waiters fail.
            let _ = tokio::time::timeout(Duration::from_secs(1), async { c.writer.lock().await.shutdown().await }).await;
            c.close();
        }
        true
    }

    async fn exchange(self: &Arc<Self>, q: &[u8]) -> io::Result<Vec<u8>> {
        touch(&self.last_used, &self.stale);
        let _in_flight = InFlight::new(&self.in_flight);
        let orig = dns::id_of(q);
        let mut last = io::Error::other("dot failed");
        for _ in 0..2 {
            let c = self.conn().await?;
            let (tx, rx) = oneshot::channel();
            let id = {
                let mut p = c.pending.lock();
                // Closed since it was handed out (its waiters were failed before this one came):
                // a new connection, not a 5 s wait on one nobody reads.
                if c.dead.load(Ordering::Acquire) {
                    last = io::Error::new(ErrorKind::ConnectionReset, "dot connection closed");
                    continue;
                }
                let mut id = rand_u16();
                while p.contains_key(&id) {
                    id = rand_u16();
                }
                p.insert(id, tx);
                id
            };
            let mut msg = Vec::with_capacity(q.len() + 2);
            msg.extend_from_slice(&(q.len() as u16).to_be_bytes());
            msg.extend_from_slice(q);
            dns::set_id(&mut msg[2..], id);
            // Bounded: a connection the server stopped reading would hold the writer, and every
            // query behind it, until TCP gives up.
            let sent = match tokio::time::timeout(TLS_TIMEOUT, async { c.writer.lock().await.write_all(&msg).await }).await {
                Ok(r) => r,
                Err(_) => Err(io::Error::new(ErrorKind::TimedOut, "dot write timeout")),
            };
            if let Err(e) = sent {
                // Its stream may hold half a message now: closed, and its other waiters fail.
                c.close();
                last = e;
                continue;
            }
            match tokio::time::timeout(TLS_TIMEOUT, rx).await {
                // As for DoH: not asked again, the connection kept.
                Ok(Ok(r)) if !answers(&msg[2..], &r) => return Err(io::Error::new(ErrorKind::InvalidData, "dot answer to another question")),
                Ok(Ok(mut r)) => {
                    dns::set_id(&mut r, orig);
                    return Ok(r);
                }
                Ok(Err(_)) => last = io::Error::new(ErrorKind::ConnectionReset, "dot connection closed"),
                Err(_) => {
                    // A connection that let a query time out is taken for dead: the retry dials
                    // a new one instead of reusing the same silent one, and the queries waiting
                    // on it go there too.
                    c.close();
                    last = io::Error::new(ErrorKind::TimedOut, "dot timeout");
                }
            }
        }
        Err(last)
    }
}

#[cfg(test)]
impl Upstream {
    /// How many connections a DoH resolver has made (0 for the others).
    pub(crate) fn dials(&self) -> u64 {
        match self {
            Upstream::Doh(d) => d.dials.load(Ordering::Relaxed),
            _ => 0,
        }
    }

    /// When the encrypted resolver's last dial failed (now_ms; 0: none).
    pub(crate) fn dial_failed_at(&self) -> u64 {
        match self {
            Upstream::System => 0,
            Upstream::Doh(d) => d.dial_failed.lock().1,
            Upstream::Dot(d) => d.dial_failed.lock().1,
        }
    }
}

/// Test helpers for other modules.
#[cfg(test)]
pub(crate) mod testing {
    use super::*;
    use std::sync::atomic::AtomicUsize;

    /// A DoH resolver on this machine answering every query with A `addr` (TTL 300; None: no
    /// records), trusted by the returned upstream alone, and the number of queries it got.
    pub(crate) async fn doh_answering(addr: Option<[u8; 4]>) -> (Upstream, Arc<AtomicUsize>) {
        let ck = rcgen::generate_simple_self_signed(vec!["dns.test".into()]).unwrap();
        let cert = ck.cert.der().clone();
        let key = rustls_pki_types::PrivateKeyDer::try_from(ck.signing_key.serialize_der()).unwrap();
        let mut server = rustls::ServerConfig::builder_with_provider(tls::provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key)
            .unwrap();
        server.alpn_protocols = vec![b"h2".to_vec()];
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(server));
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        let hits = Arc::new(AtomicUsize::new(0));
        let h = hits.clone();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let (a, h) = (acceptor.clone(), h.clone());
                tokio::spawn(async move {
                    let Ok(t) = a.accept(s).await else { return };
                    let svc = hyper::service::service_fn(move |req: hyper::Request<hyper::body::Incoming>| {
                        let h = h.clone();
                        async move {
                            let q = req.into_body().collect().await?.to_bytes().to_vec();
                            h.fetch_add(1, Ordering::SeqCst);
                            let p = dns::parse_query(&q).unwrap();
                            let r = match addr {
                                Some(a) => {
                                    let mut r = dns::blocked_response(&q, &p, dns::BlockMode::NullIp, 300);
                                    let l = r.len();
                                    let at = if p.edns { l - 11 - 4 } else { l - 4 };
                                    r[at..at + 4].copy_from_slice(&a);
                                    r
                                }
                                None => dns::error_response(&q, &p, dns::RCODE_NOERROR),
                            };
                            Ok::<_, hyper::Error>(
                                hyper::Response::builder().header("content-type", "application/dns-message").body(Full::new(Bytes::from(r))).unwrap(),
                            )
                        }
                    });
                    let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(t), svc).await;
                });
            }
        });
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert).unwrap();
        let mut client = rustls::ClientConfig::builder_with_provider(tls::provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();
        client.alpn_protocols = vec![b"h2".to_vec()];
        let mut doh = Doh::new(&format!("https://dns.test:{port}/dns-query"), &["127.0.0.1".parse().unwrap()]).unwrap();
        doh.connector = TlsConnector::from(Arc::new(client));
        (Upstream::Doh(Arc::new(doh)), hits)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// What the 64 KB zeroed receive buffer costs per upstream query, next to a whole UDP
    /// exchange with a local resolver, on the cores the DNS thread runs on. Run on an Android
    /// device (`cargo ndk … test -p dnsengine --release --no-run`, push the binary, then
    /// `<binary> --ignored receive_buffer_cost --nocapture`).
    #[tokio::test(flavor = "current_thread")]
    #[ignore]
    async fn receive_buffer_cost() {
        let cpus = crate::cpu::pin_current_thread_to_efficiency_cores();
        const N: u32 = 20_000;
        let t = std::time::Instant::now();
        for _ in 0..N {
            std::hint::black_box(vec![0u8; 65535]);
        }
        let zeroed = t.elapsed().as_secs_f64() * 1e6 / N as f64;
        let t = std::time::Instant::now();
        for _ in 0..N {
            std::hint::black_box(Vec::<u8>::with_capacity(65535));
        }
        let reserved = t.elapsed().as_secs_f64() * 1e6 / N as f64;
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let mut r = buf[..n].to_vec();
                r[2] |= 0x80;
                server.send_to(&r, from).await.unwrap();
            }
        });
        let q = dns::build_query(0x4242, "example.com", dns::TYPE_A);
        const M: u32 = 2_000;
        let t = std::time::Instant::now();
        for _ in 0..M {
            udp_exchange(addr, &q).await.unwrap();
        }
        let exchange = t.elapsed().as_secs_f64() * 1e6 / M as f64;
        println!(
            "receive buffer cost on cpus {cpus:?}: zeroed 64 KB buffer {zeroed:.2} us, reserved only {reserved:.2} us, whole local exchange {exchange:.1} us"
        );
    }

    /// A resolver that ignores the first copy of every query: the resend must get through.
    #[tokio::test]
    async fn resends_lost_query() {
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            let mut seen = 0;
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                seen += 1;
                if seen == 1 {
                    continue;
                }
                let mut r = buf[..n].to_vec();
                r[2] |= 0x80;
                server.send_to(&r, from).await.unwrap();
            }
        });
        let q = dns::build_query(0x4242, "example.com", dns::TYPE_A);
        let t0 = std::time::Instant::now();
        let resent = UDP_RESENT.load(std::sync::atomic::Ordering::Relaxed);
        let r = udp_exchange(addr, &q).await.unwrap();
        assert_eq!(dns::id_of(&r), 0x4242);
        let ms = t0.elapsed().as_millis();
        assert!((600..1400).contains(&ms), "answered after the first resend, took {ms} ms");
        // Counted (other tests running at the same time may add theirs).
        assert!(UDP_RESENT.load(std::sync::atomic::Ordering::Relaxed) > resent);
    }

    /// A datagram bigger than 4 KB arrives whole.
    #[tokio::test]
    async fn big_answers_arrive_whole() {
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            let (n, from) = server.recv_from(&mut buf).await.unwrap();
            let mut r = buf[..n].to_vec();
            r[2] |= 0x80;
            r.resize(5000, 7);
            server.send_to(&r, from).await.unwrap();
        });
        let q = dns::build_query(0x5151, "big.test", dns::TYPE_A);
        assert_eq!(udp_exchange(addr, &q).await.unwrap().len(), 5000);
    }

    /// One server's SERVFAIL does not win over the other server's answer.
    #[tokio::test]
    async fn servfail_does_not_win_the_race() {
        let bad = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let good = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let (ba, ga) = (bad.local_addr().unwrap(), good.local_addr().unwrap());
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = bad.recv_from(&mut buf).await.unwrap();
                let q = dns::parse_query(&buf[..n]).unwrap();
                bad.send_to(&dns::error_response(&buf[..n], &q, dns::RCODE_SERVFAIL), from).await.unwrap();
            }
        });
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = good.recv_from(&mut buf).await.unwrap();
                tokio::time::sleep(Duration::from_millis(50)).await;
                let mut r = buf[..n].to_vec();
                r[2] |= 0x80;
                good.send_to(&r, from).await.unwrap();
            }
        });
        let q = dns::build_query(0x5252, "x.test", dns::TYPE_A);
        let r = system_exchange(&[ba, ga], &q).await.unwrap();
        assert_eq!(dns::rcode_of(&r), dns::RCODE_NOERROR);
    }

    /// A local resolver that answers SERVFAIL (`Some(rcode)`), answers properly (`None`), or
    /// never answers (`Some(0)`).
    async fn resolver(answer: Option<u8>) -> SocketAddr {
        let s = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let a = s.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = s.recv_from(&mut buf).await.unwrap();
                let r = match answer {
                    Some(0) => continue,
                    Some(rcode) => dns::error_response(&buf[..n], &dns::parse_query(&buf[..n]).unwrap(), rcode),
                    None => {
                        let mut r = buf[..n].to_vec();
                        r[2] |= 0x80;
                        r
                    }
                };
                s.send_to(&r, from).await.unwrap();
            }
        });
        a
    }

    /// The network's third resolver answers when the first two fail, at once (no waiting for
    /// the stagger), and is asked first afterwards.
    #[tokio::test]
    async fn third_server_is_asked_when_two_fail() {
        let servers = [resolver(Some(dns::RCODE_SERVFAIL)).await, resolver(Some(dns::RCODE_REFUSED)).await, resolver(None).await];
        let q = dns::build_query(0x5353, "third.test", dns::TYPE_A);
        let t0 = std::time::Instant::now();
        let r = system_exchange(&servers, &q).await.unwrap();
        assert_eq!(dns::rcode_of(&r), dns::RCODE_NOERROR, "the third resolver's answer");
        assert!(t0.elapsed() < SECOND_SERVER_AFTER, "took {:?}", t0.elapsed());
        assert_eq!(*PREFERRED.lock(), (servers_key(&servers), 2));
    }

    /// Two resolvers that never answer (an IPv6 pair on a network without IPv6) before a working
    /// one: the third is asked a second after the second, well inside one server's budget.
    #[tokio::test]
    async fn third_server_answers_when_two_are_silent() {
        let servers = [resolver(Some(0)).await, resolver(Some(0)).await, resolver(None).await];
        let q = dns::build_query(0x5454, "silent.test", dns::TYPE_A);
        let t0 = std::time::Instant::now();
        let r = system_exchange(&servers, &q).await.unwrap();
        assert_eq!(dns::rcode_of(&r), dns::RCODE_NOERROR);
        let took = t0.elapsed();
        assert!(took >= SECOND_SERVER_AFTER * 2 && took < SECOND_SERVER_AFTER * 3, "took {took:?}");
        // Asked first from now on: the next lookup costs no stagger.
        let t1 = std::time::Instant::now();
        system_exchange(&servers, &dns::build_query(0x5455, "silent.test", dns::TYPE_A)).await.unwrap();
        assert!(t1.elapsed() < SECOND_SERVER_AFTER, "took {:?}", t1.elapsed());
    }

    /// When every resolver fails, a server's own failure answer is returned (not a timeout).
    #[tokio::test]
    async fn every_server_failing_returns_a_failure_answer() {
        let servers = [resolver(Some(dns::RCODE_SERVFAIL)).await, resolver(Some(dns::RCODE_SERVFAIL)).await, resolver(Some(dns::RCODE_REFUSED)).await];
        let q = dns::build_query(0x5656, "fail.test", dns::TYPE_A);
        let r = system_exchange(&servers, &q).await.unwrap();
        assert!(server_failed(&r));
    }

    /// An unreachable encrypted resolver fails all waiting queries within about one dial
    /// time, not one dial each.
    #[tokio::test]
    async fn unreachable_doh_fails_fast() {
        let doh = Arc::new(Doh::new("https://dns.test/dns-query", &["192.0.2.1".parse().unwrap()]).unwrap());
        let q = dns::build_query(1, "a.test", dns::TYPE_A);
        let t0 = std::time::Instant::now();
        let mut tasks = Vec::new();
        for _ in 0..10 {
            let (d, q) = (doh.clone(), q.clone());
            tasks.push(tokio::spawn(async move { d.exchange(&q).await }));
        }
        for t in tasks {
            assert!(t.await.unwrap().is_err());
        }
        assert!(t0.elapsed() < Duration::from_secs(8), "took {:?}", t0.elapsed());
    }

    /// A dial that failed on the old network does not refuse lookups on the new one: after a
    /// network change the next lookup dials again at once.
    #[tokio::test]
    async fn a_network_change_forgets_a_failed_dial() {
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let local: [IpAddr; 1] = ["127.0.0.1".parse().unwrap()];
        let doh = Upstream::Doh(Arc::new(Doh::new(&format!("https://dns.test:{port}/dns-query"), &local).unwrap()));
        let dot = Upstream::Dot(Arc::new(Dot::new("dns.test", port, &local)));
        let q = dns::build_query(7, "a.test", dns::TYPE_A);
        for u in [doh, dot] {
            let first = u.exchange(&[], &q).await.unwrap_err();
            assert_ne!(first.to_string(), DIAL_FAILED_RECENTLY);
            assert_eq!(u.exchange(&[], &q).await.unwrap_err().to_string(), DIAL_FAILED_RECENTLY, "{}", u.describe());
            u.reset_connections(true);
            let after = u.exchange(&[], &q).await.unwrap_err();
            assert_ne!(after.to_string(), DIAL_FAILED_RECENTLY, "{}: refused without a dial after the reset", u.describe());
        }
    }

    /// A first resolver address that swallows the SYN costs a quarter second, not the
    /// whole 5 s attempt: the next address is dialled meanwhile and the first handshake wins.
    #[tokio::test]
    async fn dead_first_address_costs_a_quarter_second() {
        let ck = rcgen::generate_simple_self_signed(vec!["dns.test".into()]).unwrap();
        let cert = ck.cert.der().clone();
        let key = rustls_pki_types::PrivateKeyDer::try_from(ck.signing_key.serialize_der()).unwrap();
        let server = rustls::ServerConfig::builder_with_provider(tls::provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key)
            .unwrap();
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(server));
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let a = acceptor.clone();
                tokio::spawn(async move {
                    if let Ok(mut t) = a.accept(s).await {
                        let _ = t.read_u8().await;
                    }
                });
            }
        });
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert).unwrap();
        let client = rustls::ClientConfig::builder_with_provider(tls::provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let connector = TlsConnector::from(Arc::new(client));
        let t0 = std::time::Instant::now();
        let bootstrap: [IpAddr; 2] = ["192.0.2.1".parse().unwrap(), "127.0.0.1".parse().unwrap()];
        tls_connect("dns.test", port, &bootstrap, &connector).await.unwrap();
        assert!(t0.elapsed() < Duration::from_secs(1), "took {:?}", t0.elapsed());
    }

    /// Ten queries in flight on a DoH connection that dies all go to one new connection (no
    /// failure drops the connection another has just made).
    #[tokio::test]
    async fn a_dead_doh_connection_is_replaced_once() {
        let ck = rcgen::generate_simple_self_signed(vec!["dns.test".into()]).unwrap();
        let cert = ck.cert.der().clone();
        let key = rustls_pki_types::PrivateKeyDer::try_from(ck.signing_key.serialize_der()).unwrap();
        let mut server = rustls::ServerConfig::builder_with_provider(tls::provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key)
            .unwrap();
        server.alpn_protocols = vec![b"h2".to_vec()];
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(server));
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        let conns = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let c2 = conns.clone();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let (a, first) = (acceptor.clone(), c2.fetch_add(1, Ordering::SeqCst) == 0);
                tokio::spawn(async move {
                    let Ok(t) = a.accept(s).await else { return };
                    if first {
                        // Takes the queries, then the connection dies.
                        tokio::time::sleep(Duration::from_millis(300)).await;
                        return;
                    }
                    let svc = hyper::service::service_fn(|req: hyper::Request<hyper::body::Incoming>| async move {
                        let mut r = req.into_body().collect().await?.to_bytes().to_vec();
                        r[2] |= 0x80;
                        Ok::<_, hyper::Error>(
                            hyper::Response::builder().header("content-type", "application/dns-message").body(Full::new(Bytes::from(r))).unwrap(),
                        )
                    });
                    let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(t), svc).await;
                });
            }
        });
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert).unwrap();
        let mut client = rustls::ClientConfig::builder_with_provider(tls::provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();
        client.alpn_protocols = vec![b"h2".to_vec()];
        let mut doh = Doh::new(&format!("https://dns.test:{port}/dns-query"), &["127.0.0.1".parse().unwrap()]).unwrap();
        doh.connector = TlsConnector::from(Arc::new(client));
        let doh = Arc::new(doh);
        let mut tasks = Vec::new();
        for i in 0..10 {
            let d = doh.clone();
            tasks.push(tokio::spawn(async move { d.exchange(&dns::build_query(i, "a.test", dns::TYPE_A)).await }));
        }
        for t in tasks {
            t.await.unwrap().expect("answered on the new connection");
        }
        assert_eq!(conns.load(Ordering::SeqCst), 2);
    }

    /// A TLS server on `bind` with a self-signed certificate for `name` (a DNS name or an IP
    /// address), and a connector that trusts only that certificate.
    async fn tls_pair(bind: &str, name: &str, alpn: &[u8]) -> (tokio::net::TcpListener, tokio_rustls::TlsAcceptor, TlsConnector) {
        let ck = rcgen::generate_simple_self_signed(vec![name.to_string()]).unwrap();
        let cert = ck.cert.der().clone();
        let key = rustls_pki_types::PrivateKeyDer::try_from(ck.signing_key.serialize_der()).unwrap();
        let mut server = rustls::ServerConfig::builder_with_provider(tls::provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key)
            .unwrap();
        server.alpn_protocols = vec![alpn.to_vec()];
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert).unwrap();
        let mut client = rustls::ClientConfig::builder_with_provider(tls::provider())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();
        client.alpn_protocols = vec![alpn.to_vec()];
        let l = tokio::net::TcpListener::bind(bind).await.unwrap();
        (l, tokio_rustls::TlsAcceptor::from(Arc::new(server)), TlsConnector::from(Arc::new(client)))
    }

    /// A custom DoH address written with an IPv6 literal (`https://[::1]:port/dns-query`, no
    /// bootstrap addresses) is dialled at that address and its certificate checked against it.
    #[tokio::test]
    async fn doh_to_an_ipv6_literal() {
        let (l, acceptor, connector) = tls_pair("[::1]:0", "::1", b"h2").await;
        let port = l.local_addr().unwrap().port();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let a = acceptor.clone();
                tokio::spawn(async move {
                    let Ok(t) = a.accept(s).await else { return };
                    let svc = hyper::service::service_fn(|req: hyper::Request<hyper::body::Incoming>| async move {
                        let mut r = req.into_body().collect().await?.to_bytes().to_vec();
                        r[2] |= 0x80;
                        Ok::<_, hyper::Error>(
                            hyper::Response::builder().header("content-type", "application/dns-message").body(Full::new(Bytes::from(r))).unwrap(),
                        )
                    });
                    let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(t), svc).await;
                });
            }
        });
        let mut doh = Doh::new(&format!("https://[::1]:{port}/dns-query"), &[]).unwrap();
        doh.connector = connector;
        let r = Arc::new(doh).exchange(&dns::build_query(0x6006, "v6.test", dns::TYPE_AAAA)).await.expect("answered over the IPv6 literal");
        assert_eq!(dns::id_of(&r), 0x6006);
    }

    /// DoT to an IPv6 literal, given bare (`::1`, as the app sends it) or in brackets.
    #[tokio::test]
    async fn dot_to_an_ipv6_literal() {
        let (l, acceptor, connector) = tls_pair("[::1]:0", "::1", b"dot").await;
        let port = l.local_addr().unwrap().port();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let a = acceptor.clone();
                tokio::spawn(async move {
                    let Ok(mut t) = a.accept(s).await else { return };
                    while let Ok(n) = t.read_u16().await {
                        let mut m = vec![0u8; n as usize];
                        if t.read_exact(&mut m).await.is_err() {
                            return;
                        }
                        m[2] |= 0x80;
                        let mut out = n.to_be_bytes().to_vec();
                        out.extend_from_slice(&m);
                        let _ = t.write_all(&out).await;
                    }
                });
            }
        });
        for host in ["::1", "[::1]"] {
            let mut dot = Dot::new(host, port, &[]);
            dot.connector = connector.clone();
            let r = Arc::new(dot).exchange(&dns::build_query(0x6007, "v6.test", dns::TYPE_AAAA)).await.unwrap_or_else(|e| panic!("{host}: {e}"));
            assert_eq!(dns::id_of(&r), 0x6007);
        }
    }

    /// An encrypted resolver's answer is used only when it is an answer (QR set) to the question
    /// asked and fits a DNS message (64 KB): as plain DNS over UDP and TCP already required. A
    /// broken resolver's answer to another name was cached under the asked one, and a DoH body
    /// was read whole, however large.
    #[tokio::test]
    async fn encrypted_answers_must_answer_the_question() {
        use std::sync::atomic::{AtomicU8, AtomicUsize};
        // 0: another name, 1: no QR bit, 2: 70,000 bytes, 3: a proper answer, 4: a format error
        // without the question (12 bytes).
        let mode = Arc::new(AtomicU8::new(0));
        let asked = Arc::new(AtomicUsize::new(0));
        let (l, acceptor, connector) = tls_pair("127.0.0.1:0", "127.0.0.1", b"h2").await;
        let port = l.local_addr().unwrap().port();
        let (m, n) = (mode.clone(), asked.clone());
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let (a, m, n) = (acceptor.clone(), m.clone(), n.clone());
                tokio::spawn(async move {
                    let Ok(t) = a.accept(s).await else { return };
                    let svc = hyper::service::service_fn(move |req: hyper::Request<hyper::body::Incoming>| {
                        let (m, n) = (m.clone(), n.clone());
                        async move {
                            let mut r = req.into_body().collect().await?.to_bytes().to_vec();
                            n.fetch_add(1, Ordering::SeqCst);
                            match m.load(Ordering::SeqCst) {
                                0 => {
                                    r[2] |= 0x80;
                                    r[13] ^= 0x01;
                                }
                                1 => {}
                                2 => {
                                    r[2] |= 0x80;
                                    r.resize(70_000, 0);
                                }
                                4 => {
                                    r.truncate(12);
                                    r[2] |= 0x80;
                                    r[3] = (r[3] & 0xf0) | 1;
                                    r[4..12].fill(0);
                                }
                                _ => r[2] |= 0x80,
                            }
                            Ok::<_, hyper::Error>(
                                hyper::Response::builder().header("content-type", "application/dns-message").body(Full::new(Bytes::from(r))).unwrap(),
                            )
                        }
                    });
                    let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(t), svc).await;
                });
            }
        });
        let mut doh = Doh::new(&format!("https://127.0.0.1:{port}/dns-query"), &[]).unwrap();
        doh.connector = connector;
        let doh = Arc::new(doh);
        // A wrong answer is the server's answer, not a broken connection: asked once, the
        // connection kept (asking again would get the same answer at the cost of a new dial).
        for (m, what) in [(0, "an answer to another name"), (1, "a message without QR"), (4, "a format error without the question")] {
            mode.store(m, Ordering::SeqCst);
            let before = asked.load(Ordering::SeqCst);
            assert!(doh.exchange(&dns::build_query(0x5151, "q.test", dns::TYPE_A)).await.is_err(), "DoH took {what}");
            assert_eq!(asked.load(Ordering::SeqCst) - before, 1, "DoH asked again after {what}");
        }
        assert_eq!(doh.dials.load(Ordering::Relaxed), 1, "DoH dialled again after a wrong answer");
        mode.store(2, Ordering::SeqCst);
        assert!(doh.exchange(&dns::build_query(0x5151, "q.test", dns::TYPE_A)).await.is_err(), "DoH took a 70,000-byte body");
        mode.store(3, Ordering::SeqCst);
        doh.exchange(&dns::build_query(0x5151, "q.test", dns::TYPE_A)).await.expect("a proper answer");

        // DoT: the server answers another name, then properly; one query each, one connection.
        let (l, acceptor, connector) = tls_pair("127.0.0.1:0", "127.0.0.1", b"dot").await;
        let port = l.local_addr().unwrap().port();
        let wrong = Arc::new(AtomicU8::new(1));
        let (dot_asked, conns) = (Arc::new(AtomicUsize::new(0)), Arc::new(AtomicUsize::new(0)));
        let (w, n, c) = (wrong.clone(), dot_asked.clone(), conns.clone());
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let (a, w, n) = (acceptor.clone(), w.clone(), n.clone());
                c.fetch_add(1, Ordering::SeqCst);
                tokio::spawn(async move {
                    let Ok(mut t) = a.accept(s).await else { return };
                    while let Ok(len) = t.read_u16().await {
                        let mut m = vec![0u8; len as usize];
                        if t.read_exact(&mut m).await.is_err() {
                            return;
                        }
                        n.fetch_add(1, Ordering::SeqCst);
                        m[2] |= 0x80;
                        if w.load(Ordering::SeqCst) == 1 {
                            m[13] ^= 0x01;
                        }
                        let mut out = len.to_be_bytes().to_vec();
                        out.extend_from_slice(&m);
                        let _ = t.write_all(&out).await;
                    }
                });
            }
        });
        let mut dot = Dot::new("127.0.0.1", port, &[]);
        dot.connector = connector;
        let dot = Arc::new(dot);
        assert!(dot.exchange(&dns::build_query(0x5152, "q.test", dns::TYPE_A)).await.is_err(), "DoT took an answer to another name");
        assert_eq!(dot_asked.load(Ordering::SeqCst), 1, "DoT asked again after an answer to another name");
        wrong.store(0, Ordering::SeqCst);
        dot.exchange(&dns::build_query(0x5152, "q.test", dns::TYPE_A)).await.expect("DoT answers on after a wrong answer");
        assert_eq!(conns.load(Ordering::SeqCst), 1, "DoT dialled again after a wrong answer");
    }

    /// A DoT connection replaced by a new one (network change, a silent connection) is closed,
    /// not left open by its reader until the server gives up on it.
    #[tokio::test]
    async fn replaced_dot_connection_is_closed() {
        let (l, acceptor, connector) = tls_pair("127.0.0.1:0", "127.0.0.1", b"dot").await;
        let port = l.local_addr().unwrap().port();
        let closed = Arc::new(tokio::sync::Notify::new());
        let c2 = closed.clone();
        tokio::spawn(async move {
            let mut first = true;
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                let (a, closed, is_first) = (acceptor.clone(), c2.clone(), std::mem::replace(&mut first, false));
                tokio::spawn(async move {
                    let Ok(mut t) = a.accept(s).await else { return };
                    while let Ok(n) = t.read_u16().await {
                        let mut m = vec![0u8; n as usize];
                        if t.read_exact(&mut m).await.is_err() {
                            break;
                        }
                        m[2] |= 0x80;
                        let mut out = n.to_be_bytes().to_vec();
                        out.extend_from_slice(&m);
                        let _ = t.write_all(&out).await;
                    }
                    if is_first {
                        closed.notify_one();
                    }
                });
            }
        });
        let mut dot = Dot::new("127.0.0.1", port, &[]);
        dot.connector = connector;
        let dot = Arc::new(dot);
        dot.exchange(&dns::build_query(0x7001, "a.test", dns::TYPE_A)).await.unwrap();
        dot.stale.store(true, Ordering::Release);
        dot.exchange(&dns::build_query(0x7002, "b.test", dns::TYPE_A)).await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), closed.notified()).await.expect("the first connection was closed");
    }

    /// A network whose resolver has an IPv6 address (an IPv6-only network): plain DNS goes over
    /// an IPv6 socket, and over TCP when the answer is truncated.
    #[tokio::test]
    async fn system_resolver_over_ipv6() {
        let server = UdpSocket::bind("[::1]:0").await.unwrap();
        let addr = server.local_addr().unwrap();
        let tcp = tokio::net::TcpListener::bind(addr).await.unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let mut r = buf[..n].to_vec();
                r[2] |= 0x80 | 0x02; // truncated: ask again over TCP
                server.send_to(&r, from).await.unwrap();
            }
        });
        tokio::spawn(async move {
            let Ok((mut s, _)) = tcp.accept().await else { return };
            let n = s.read_u16().await.unwrap();
            let mut m = vec![0u8; n as usize];
            s.read_exact(&mut m).await.unwrap();
            m[2] |= 0x80;
            m.resize(1500, 0);
            let mut out = (m.len() as u16).to_be_bytes().to_vec();
            out.extend_from_slice(&m);
            s.write_all(&out).await.unwrap();
        });
        let q = dns::build_query(0x6008, "big6.test", dns::TYPE_AAAA);
        let r = system_exchange(&[addr], &q).await.unwrap();
        assert_eq!((dns::id_of(&r), r.len()), (0x6008, 1500));
    }

    /// A first server that never answers costs about a second: the second one is asked in
    /// parallel then, and preferred from then on for this server list.
    #[tokio::test]
    async fn second_server_raced() {
        let dead = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let live = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let (da, la) = (dead.local_addr().unwrap(), live.local_addr().unwrap());
        tokio::spawn(async move {
            let _keep = dead;
            std::future::pending::<()>().await;
        });
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = live.recv_from(&mut buf).await.unwrap();
                let mut r = buf[..n].to_vec();
                r[2] |= 0x80;
                live.send_to(&r, from).await.unwrap();
            }
        });
        let q = dns::build_query(0x4243, "example.com", dns::TYPE_A);
        let t0 = std::time::Instant::now();
        system_exchange(&[da, la], &q).await.unwrap();
        let ms = t0.elapsed().as_millis();
        assert!((900..1600).contains(&ms), "took {ms} ms");
        let t1 = std::time::Instant::now();
        system_exchange(&[da, la], &q).await.unwrap();
        assert!(t1.elapsed().as_millis() < 300, "the live server is asked first now");
    }

    /// The question's name compares case-insensitively (0x20 mixing), its type and class byte
    /// for byte: HTTPS (65) is not type 97, whose low byte is 'a' to an ASCII comparison.
    #[test]
    fn same_question_compares_the_type_exactly() {
        let q65 = dns::build_query(0x1234, "a.test", 65);
        let mut r97 = dns::build_query(0x1234, "a.test", 97);
        r97[2] |= 0x80;
        assert!(!same_question(&q65, &r97), "treated types 65 and 97 as equal");
        let mut upper = dns::build_query(0x1234, "A.TEST", 65);
        upper[2] |= 0x80;
        assert!(same_question(&q65, &upper), "the name is case-insensitive");
    }

    /// A TCP resolver that echoes the query back (QR clear) gave no answer.
    #[tokio::test]
    async fn tcp_exchange_rejects_a_reply_without_the_qr_bit() {
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = l.local_addr().unwrap();
        tokio::spawn(async move {
            let (mut s, _) = l.accept().await.unwrap();
            let n = s.read_u16().await.unwrap();
            let mut m = vec![0u8; n as usize];
            s.read_exact(&mut m).await.unwrap();
            let mut out = n.to_be_bytes().to_vec();
            out.extend_from_slice(&m);
            let _ = s.write_all(&out).await;
        });
        let q = dns::build_query(0x1234, "a.test", dns::TYPE_A);
        assert!(tcp_exchange(addr, &q).await.is_err(), "accepted a TCP reply without the QR bit");
    }
    /// A DoT server that echoes every query back as its answer after `delay`, counting the
    /// connections it accepted; a `silent` one reads queries and never answers.
    async fn dot_server(delay: Duration, silent: bool) -> (u16, TlsConnector, Arc<std::sync::atomic::AtomicUsize>) {
        let (l, acceptor, connector) = tls_pair("127.0.0.1:0", "127.0.0.1", b"dot").await;
        let port = l.local_addr().unwrap().port();
        let conns = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let c = conns.clone();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                c.fetch_add(1, Ordering::SeqCst);
                let a = acceptor.clone();
                tokio::spawn(async move {
                    let Ok(t) = a.accept(s).await else { return };
                    let (mut r, w) = tokio::io::split(t);
                    let w = Arc::new(tokio::sync::Mutex::new(w));
                    while let Ok(n) = r.read_u16().await {
                        let mut m = vec![0u8; n as usize];
                        if r.read_exact(&mut m).await.is_err() {
                            return;
                        }
                        if silent {
                            continue;
                        }
                        let w = w.clone();
                        tokio::spawn(async move {
                            tokio::time::sleep(delay).await;
                            m[2] |= 0x80;
                            let mut out = n.to_be_bytes().to_vec();
                            out.extend_from_slice(&m);
                            let _ = w.lock().await.write_all(&out).await;
                        });
                    }
                });
            }
        });
        (port, connector, conns)
    }

    /// A DoH server answering every query (an echo with QR set) after `delay`, and the
    /// connections it accepted.
    async fn doh_server(delay: Duration) -> (u16, TlsConnector, Arc<std::sync::atomic::AtomicUsize>) {
        let (l, acceptor, connector) = tls_pair("127.0.0.1:0", "127.0.0.1", b"h2").await;
        let port = l.local_addr().unwrap().port();
        let conns = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let c = conns.clone();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                c.fetch_add(1, Ordering::SeqCst);
                let a = acceptor.clone();
                tokio::spawn(async move {
                    let Ok(t) = a.accept(s).await else { return };
                    let svc = hyper::service::service_fn(move |req: hyper::Request<hyper::body::Incoming>| async move {
                        let mut r = req.into_body().collect().await?.to_bytes().to_vec();
                        tokio::time::sleep(delay).await;
                        r[2] |= 0x80;
                        Ok::<_, hyper::Error>(
                            hyper::Response::builder().header("content-type", "application/dns-message").body(Full::new(Bytes::from(r))).unwrap(),
                        )
                    });
                    let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(t), svc).await;
                });
            }
        });
        (port, connector, conns)
    }

    /// A dial still running on the old network when the phone moves is given up: its failure
    /// does not refuse lookups on the new network for 3 s, and nobody waits for it. The test
    /// server holds every connection made before `works` is set (the old network's), and
    /// answers the rest.
    #[tokio::test]
    async fn a_dial_on_the_old_network_does_not_hold_the_new_one() {
        for dot in [false, true] {
            let alpn: &[u8] = if dot { b"dot" } else { b"h2" };
            let (l, acceptor, connector) = tls_pair("127.0.0.1:0", "127.0.0.1", alpn).await;
            let port = l.local_addr().unwrap().port();
            let works = Arc::new(AtomicBool::new(false));
            let w = works.clone();
            tokio::spawn(async move {
                loop {
                    let Ok((s, _)) = l.accept().await else { return };
                    if !w.load(Ordering::SeqCst) {
                        tokio::spawn(async move {
                            tokio::time::sleep(Duration::from_secs(20)).await;
                            drop(s);
                        });
                        continue;
                    }
                    let a = acceptor.clone();
                    tokio::spawn(async move {
                        let Ok(mut t) = a.accept(s).await else { return };
                        if dot {
                            while let Ok(n) = t.read_u16().await {
                                let mut m = vec![0u8; n as usize];
                                if t.read_exact(&mut m).await.is_err() {
                                    return;
                                }
                                m[2] |= 0x80;
                                let mut out = n.to_be_bytes().to_vec();
                                out.extend_from_slice(&m);
                                let _ = t.write_all(&out).await;
                            }
                            return;
                        }
                        let svc = hyper::service::service_fn(|req: hyper::Request<hyper::body::Incoming>| async move {
                            let mut r = req.into_body().collect().await?.to_bytes().to_vec();
                            r[2] |= 0x80;
                            Ok::<_, hyper::Error>(
                                hyper::Response::builder().header("content-type", "application/dns-message").body(Full::new(Bytes::from(r))).unwrap(),
                            )
                        });
                        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(t), svc).await;
                    });
                }
            });
            let up = if dot {
                let mut d = Dot::new("127.0.0.1", port, &[]);
                d.connector = connector;
                Arc::new(Upstream::Dot(Arc::new(d)))
            } else {
                let mut d = Doh::new(&format!("https://127.0.0.1:{port}/dns-query"), &[]).unwrap();
                d.connector = connector;
                Arc::new(Upstream::Doh(Arc::new(d)))
            };
            let t0 = std::time::Instant::now();
            let u = up.clone();
            let first = tokio::spawn(async move { u.exchange(&[], &dns::build_query(0x7101, "a.test", dns::TYPE_A)).await });
            // The phone moves; the new network carries traffic a moment later.
            tokio::time::sleep(Duration::from_millis(200)).await;
            up.reset_connections(true);
            tokio::time::sleep(Duration::from_millis(900)).await;
            works.store(true, Ordering::SeqCst);
            let r = up.exchange(&[], &dns::build_query(0x7102, "b.test", dns::TYPE_A)).await;
            let took = t0.elapsed();
            assert!(r.is_ok(), "{}: the new network's lookup failed after {took:?}: {:?}", up.describe(), r.err());
            assert!(took < Duration::from_millis(2500), "{}: took {took:?}", up.describe());
            assert!(first.await.unwrap().is_ok(), "{}: the lookup asked during the move", up.describe());
        }
    }

    /// Closing a DoT connection fails the queries waiting on it at once (they waited their
    /// whole 5 s before).
    #[tokio::test]
    async fn closing_a_dot_connection_fails_its_waiters_at_once() {
        let (port, connector, _) = dot_server(Duration::ZERO, true).await;
        let mut d = Dot::new("127.0.0.1", port, &[]);
        d.connector = connector;
        let d = Arc::new(d);
        let c = d.conn().await.unwrap();
        let (tx, rx) = oneshot::channel();
        c.pending.lock().insert(1, tx);
        c.close();
        assert!(tokio::time::timeout(Duration::from_millis(500), rx).await.expect("the waiter is still waiting").is_err());
    }

    /// A DoT connection whose reader died with its runtime (the tunnel was rebuilt) counts as
    /// dead: the next query dials a new one instead of writing to it.
    #[test]
    fn a_dot_reader_gone_with_its_runtime_marks_the_connection_dead() {
        let main = tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build().unwrap();
        let (port, connector, _) = main.block_on(dot_server(Duration::ZERO, false));
        let mut d = Dot::new("127.0.0.1", port, &[]);
        d.connector = connector;
        let d = Arc::new(d);
        let old = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        old.block_on(d.exchange(&dns::build_query(0x7201, "a.test", dns::TYPE_A))).unwrap();
        drop(old);
        let c = main.block_on(async { d.conn.lock().await.clone() }).expect("a cached connection");
        assert!(c.dead.load(Ordering::Acquire), "the connection outlived its reader");
    }

    /// The screen going off closes connections idle a moment, not one with a query in flight
    /// (idle was judged by the query's start: a 2 s answer was cut off and asked again).
    #[tokio::test]
    async fn screen_off_keeps_a_connection_with_a_query_in_flight() {
        let slow = Duration::from_millis(2600);
        let (port, connector, conns) = dot_server(slow, false).await;
        let mut d = Dot::new("127.0.0.1", port, &[]);
        d.connector = connector;
        let dot = Arc::new(Upstream::Dot(Arc::new(d)));
        let (port, connector, doh_conns) = doh_server(slow).await;
        let mut d = Doh::new(&format!("https://127.0.0.1:{port}/dns-query"), &[]).unwrap();
        d.connector = connector;
        let doh = Arc::new(Upstream::Doh(Arc::new(d)));
        for (up, conns) in [(dot, conns), (doh, doh_conns)] {
            let t0 = std::time::Instant::now();
            let u = up.clone();
            let q = tokio::spawn(async move { u.exchange(&[], &dns::build_query(0x7301, "slow.test", dns::TYPE_A)).await });
            tokio::time::sleep(Duration::from_millis(2200)).await;
            up.close_idle();
            assert!(q.await.unwrap().is_ok(), "{}", up.describe());
            assert!(t0.elapsed() < Duration::from_millis(4000), "{}: took {:?}", up.describe(), t0.elapsed());
            assert_eq!(conns.load(Ordering::SeqCst), 1, "{}: the connection was closed under the query", up.describe());
        }
    }
    /// A TLS server for DoH or DoT that holds every connection made while `works` is false (an
    /// old network's), and answers those made later; the connections it accepted.
    async fn held_until_works(dot: bool, works: Arc<AtomicBool>) -> (u16, TlsConnector, Arc<std::sync::atomic::AtomicUsize>) {
        let alpn: &[u8] = if dot { b"dot" } else { b"h2" };
        let (l, acceptor, connector) = tls_pair("127.0.0.1:0", "127.0.0.1", alpn).await;
        let port = l.local_addr().unwrap().port();
        let conns = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let c = conns.clone();
        tokio::spawn(async move {
            loop {
                let Ok((s, _)) = l.accept().await else { return };
                c.fetch_add(1, Ordering::SeqCst);
                if !works.load(Ordering::SeqCst) {
                    tokio::spawn(async move {
                        tokio::time::sleep(Duration::from_secs(20)).await;
                        drop(s);
                    });
                    continue;
                }
                let a = acceptor.clone();
                tokio::spawn(async move {
                    let Ok(mut t) = a.accept(s).await else { return };
                    if dot {
                        while let Ok(n) = t.read_u16().await {
                            let mut m = vec![0u8; n as usize];
                            if t.read_exact(&mut m).await.is_err() {
                                return;
                            }
                            m[2] |= 0x80;
                            let mut out = n.to_be_bytes().to_vec();
                            out.extend_from_slice(&m);
                            let _ = t.write_all(&out).await;
                        }
                        return;
                    }
                    let svc = hyper::service::service_fn(|req: hyper::Request<hyper::body::Incoming>| async move {
                        let mut r = req.into_body().collect().await?.to_bytes().to_vec();
                        r[2] |= 0x80;
                        Ok::<_, hyper::Error>(
                            hyper::Response::builder().header("content-type", "application/dns-message").body(Full::new(Bytes::from(r))).unwrap(),
                        )
                    });
                    let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(t), svc).await;
                });
            }
        });
        (port, connector, conns)
    }

    fn upstream_to(dot: bool, port: u16, connector: TlsConnector) -> Arc<Upstream> {
        if dot {
            let mut d = Dot::new("127.0.0.1", port, &[]);
            d.connector = connector;
            Arc::new(Upstream::Dot(Arc::new(d)))
        } else {
            let mut d = Doh::new(&format!("https://127.0.0.1:{port}/dns-query"), &[]).unwrap();
            d.connector = connector;
            Arc::new(Upstream::Doh(Arc::new(d)))
        }
    }

    /// One handover can change the network's facts three times or more (lost, the new network,
    /// its IPv6 resolver a moment later): a dial given up each time records no failed dial, so
    /// the working network's lookups are not refused for 3 s.
    #[tokio::test]
    async fn a_dial_given_up_by_many_changes_refuses_nothing() {
        for dot in [false, true] {
            let works = Arc::new(AtomicBool::new(false));
            let (port, connector, _) = held_until_works(dot, works.clone()).await;
            let up = upstream_to(dot, port, connector);
            let u = up.clone();
            let first = tokio::spawn(async move { u.exchange(&[], &dns::build_query(0x7401, "a.test", dns::TYPE_A)).await });
            for _ in 0..3 {
                tokio::time::sleep(Duration::from_millis(60)).await;
                up.reset_connections(true);
            }
            let _ = first.await.unwrap();
            works.store(true, Ordering::SeqCst);
            let r = up.exchange(&[], &dns::build_query(0x7402, "b.test", dns::TYPE_A)).await;
            assert!(r.is_ok(), "{}: refused on the working network: {:?}", up.describe(), r.err());
        }
    }

    /// The network is lost while a dial runs: the lookup fails at once (no dial is made without
    /// a network), it does not dial again and wait.
    #[tokio::test]
    async fn losing_the_network_during_a_dial_fails_the_lookup_at_once() {
        for dot in [false, true] {
            let (port, connector, conns) = held_until_works(dot, Arc::new(AtomicBool::new(false))).await;
            let up = upstream_to(dot, port, connector);
            let u = up.clone();
            let t0 = std::time::Instant::now();
            let q = tokio::spawn(async move { u.exchange(&[], &dns::build_query(0x7501, "a.test", dns::TYPE_A)).await });
            tokio::time::sleep(Duration::from_millis(100)).await;
            up.reset_connections(false);
            assert!(q.await.unwrap().is_err(), "{}", up.describe());
            assert!(t0.elapsed() < Duration::from_millis(600), "{}: took {:?}", up.describe(), t0.elapsed());
            assert_eq!(conns.load(Ordering::SeqCst), 1, "{}: dialled again without a network", up.describe());
            assert_eq!(up.dial_failed_at(), 0, "{}", up.describe());
        }
    }

    /// A query waiting on an open connection when the network changes is asked again on the new
    /// network at once (it waited its whole 5 s on the old network's connection).
    #[tokio::test]
    async fn a_network_change_moves_a_waiting_query_to_the_new_network() {
        for dot in [false, true] {
            // The first connection answers the first query only; then it goes silent (the old
            // network gone), and the next connection answers.
            let (l, acceptor, connector) = tls_pair("127.0.0.1:0", "127.0.0.1", if dot { b"dot" } else { b"h2" }).await;
            let port = l.local_addr().unwrap().port();
            tokio::spawn(async move {
                let mut n = 0;
                loop {
                    let Ok((s, _)) = l.accept().await else { return };
                    n += 1;
                    let (a, first) = (acceptor.clone(), n == 1);
                    tokio::spawn(async move {
                        let Ok(mut t) = a.accept(s).await else { return };
                        let mut answered = 0;
                        if dot {
                            while let Ok(len) = t.read_u16().await {
                                let mut m = vec![0u8; len as usize];
                                if t.read_exact(&mut m).await.is_err() {
                                    return;
                                }
                                answered += 1;
                                if first && answered > 1 {
                                    continue;
                                }
                                m[2] |= 0x80;
                                let mut out = len.to_be_bytes().to_vec();
                                out.extend_from_slice(&m);
                                let _ = t.write_all(&out).await;
                            }
                            return;
                        }
                        let count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
                        let svc = hyper::service::service_fn(move |req: hyper::Request<hyper::body::Incoming>| {
                            let count = count.clone();
                            async move {
                                let mut r = req.into_body().collect().await?.to_bytes().to_vec();
                                if first && count.fetch_add(1, Ordering::SeqCst) >= 1 {
                                    tokio::time::sleep(Duration::from_secs(20)).await;
                                }
                                r[2] |= 0x80;
                                Ok::<_, hyper::Error>(
                                    hyper::Response::builder().header("content-type", "application/dns-message").body(Full::new(Bytes::from(r))).unwrap(),
                                )
                            }
                        });
                        let _ = hyper::server::conn::http2::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(t), svc).await;
                    });
                }
            });
            let up = upstream_to(dot, port, connector);
            up.exchange(&[], &dns::build_query(0x7601, "a.test", dns::TYPE_A)).await.unwrap();
            let u = up.clone();
            let t0 = std::time::Instant::now();
            let q = tokio::spawn(async move { u.exchange(&[], &dns::build_query(0x7602, "b.test", dns::TYPE_A)).await });
            tokio::time::sleep(Duration::from_millis(200)).await;
            up.reset_connections(true);
            assert!(q.await.unwrap().is_ok(), "{}", up.describe());
            assert!(t0.elapsed() < Duration::from_millis(1500), "{}: took {:?}", up.describe(), t0.elapsed());
        }
    }

    /// Closing an idle DoT connection does not hold the resolver while its polite close waits
    /// (a stuck writer held every new query up to a second).
    #[tokio::test]
    async fn closing_an_idle_dot_connection_does_not_hold_new_queries() {
        let (port, connector, _) = dot_server(Duration::ZERO, false).await;
        let mut d = Dot::new("127.0.0.1", port, &[]);
        d.connector = connector;
        let d = Arc::new(d);
        d.exchange(&dns::build_query(0x7701, "a.test", dns::TYPE_A)).await.unwrap();
        let c = d.conn.lock().await.clone().unwrap();
        // A write that does not finish holds the writer.
        let held = tokio::spawn(async move {
            let _w = c.writer.lock().await;
            tokio::time::sleep(Duration::from_secs(5)).await;
        });
        tokio::time::sleep(Duration::from_millis(50)).await;
        d.last_used.store(1, Ordering::Relaxed);
        let closer = d.clone();
        tokio::spawn(async move { closer.close_if_idle().await });
        tokio::time::sleep(Duration::from_millis(50)).await;
        let t0 = std::time::Instant::now();
        let _ = d.conn().await;
        assert!(t0.elapsed() < Duration::from_millis(500), "a new query waited {:?}", t0.elapsed());
        held.abort();
    }
}
