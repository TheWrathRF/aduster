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
    /// Forgets open encrypted connections (after a network change); the next query re-dials.
    pub fn reset_connections(&self) {
        match self {
            Upstream::System => {}
            Upstream::Doh(d) => d.stale.store(true, Ordering::Release),
            Upstream::Dot(d) => d.stale.store(true, Ordering::Release),
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

/// True while a dial that failed at `failed_at` (boot ms, 0: none) still counts.
fn dial_failed_recently(failed_at: &AtomicU64) -> bool {
    let at = failed_at.load(Ordering::Relaxed);
    at != 0 && now_ms().saturating_sub(at) < DIAL_FAILED_MS
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

/// When the first server has not answered yet, the second one is asked too (whichever answers
/// first wins): a dead first server then costs a second, not the first one's whole budget.
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
    let (a, b) = (start, (start + 1) % servers.len());
    let first = udp_exchange(servers[a], q);
    tokio::pin!(first);
    let mut second: Option<Exchange<'_>> = None;
    let delay = tokio::time::sleep(SECOND_SERVER_AFTER);
    tokio::pin!(delay);
    let (mut first_done, mut second_done) = (false, false);
    // A server's SERVFAIL/REFUSED is kept while the other one may still answer properly, so it
    // does not win the race at once.
    let mut failed_answer: Option<Vec<u8>> = None;
    loop {
        tokio::select! {
            r = &mut first, if !first_done => match r {
                Ok(v) if !server_failed(&v) => {
                    *PREFERRED.lock() = (key, a);
                    return Ok(v);
                }
                other => {
                    first_done = true;
                    let e = match other {
                        Ok(v) => {
                            failed_answer = Some(v);
                            io::Error::other("server failure")
                        }
                        Err(e) => e,
                    };
                    if second_done {
                        return failed_answer.map(Ok).unwrap_or(Err(e));
                    }
                    // Failed fast (unreachable, refused, SERVFAIL): no need to wait for the delay.
                    if second.is_none() {
                        second = Some(Box::pin(udp_exchange(servers[b], q)));
                    }
                }
            },
            _ = &mut delay, if second.is_none() && !first_done => {
                second = Some(Box::pin(udp_exchange(servers[b], q)));
            }
            r = async { second.as_mut().unwrap().await }, if second.is_some() && !second_done => match r {
                Ok(v) if !server_failed(&v) => {
                    *PREFERRED.lock() = (key, b);
                    return Ok(v);
                }
                other => {
                    second_done = true;
                    let e = match other {
                        Ok(v) => {
                            failed_answer.get_or_insert(v);
                            io::Error::other("server failure")
                        }
                        Err(e) => e,
                    };
                    if first_done {
                        return failed_answer.map(Ok).unwrap_or(Err(e));
                    }
                }
            },
        }
    }
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

fn same_question(q: &[u8], r: &[u8]) -> bool {
    // Compare the question section (name, type, class), case-insensitively.
    let Some(end) = question_end(q) else { return false };
    r.len() >= end && q[12..end].eq_ignore_ascii_case(&r[12..end])
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
        if len < 12 || dns::id_of(&r) != id || !same_question(&msg[2..], &r) {
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

async fn tls_connect(host: &str, port: u16, bootstrap: &[IpAddr], connector: &TlsConnector) -> io::Result<TlsStream<TcpStream>> {
    // An IPv6 literal comes in brackets from a URL (`https://[2606:4700::1111]/dns-query`); the
    // lookup and the certificate check take the bare address.
    let host = host.strip_prefix('[').and_then(|h| h.strip_suffix(']')).unwrap_or(host);
    let addrs: Vec<SocketAddr> = if bootstrap.is_empty() {
        tokio::net::lookup_host((host, port)).await?.collect()
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
    /// Boot ms of the last failed dial (0: none), see [`DIAL_FAILED_MS`].
    dial_failed: AtomicU64,
    /// The number of the connection in `sender` (changed only with `sender` locked).
    dials: AtomicU64,
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
            dial_failed: AtomicU64::new(0),
            dials: AtomicU64::new(0),
        })
    }

    /// The connection to use, and its number (see [`Doh::forget`]).
    async fn sender(self: &Arc<Self>) -> io::Result<(u64, SendRequest<Full<Bytes>>)> {
        let mut g = self.sender.lock().await;
        if self.stale.swap(false, Ordering::AcqRel) {
            *g = None;
        }
        if let Some(s) = g.as_ref()
            && !s.is_closed()
        {
            return Ok((self.dials.load(Ordering::Relaxed), s.clone()));
        }
        if dial_failed_recently(&self.dial_failed) {
            return Err(io::Error::new(ErrorKind::NotConnected, DIAL_FAILED_RECENTLY));
        }
        let dialed = async {
            let tls = tls_connect(&self.host, self.port, &self.bootstrap, &self.connector).await?;
            hyper::client::conn::http2::handshake(TokioExecutor::new(), TokioIo::new(tls)).await.map_err(|e| io::Error::other(e.to_string()))
        }
        .await;
        let (s, conn) = match dialed {
            Ok(v) => v,
            Err(e) => {
                self.dial_failed.store(now_ms().max(1), Ordering::Relaxed);
                return Err(e);
            }
        };
        self.dial_failed.store(0, Ordering::Relaxed);
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
            *g = None;
        }
    }

    fn close_idle(&self) {
        if now_ms().saturating_sub(self.last_used.load(Ordering::Relaxed)) < CLOSE_ON_SCREEN_OFF_MS {
            return;
        }
        if let Ok(mut g) = self.sender.try_lock() {
            *g = None;
            if let Some(t) = self.conn_task.lock().take() {
                t.abort();
            }
        }
    }

    /// Drops the connection after IDLE_CLOSE without queries; one timer per active period.
    fn watch_idle(self: &Arc<Self>) {
        let me = self.clone();
        self.watcher.ensure(move || async move {
            loop {
                tokio::time::sleep(IDLE_CLOSE).await;
                if now_ms().saturating_sub(me.last_used.load(Ordering::Relaxed)) >= IDLE_CLOSE.as_millis() as u64 {
                    *me.sender.lock().await = None;
                    return;
                }
            }
        });
    }

    async fn exchange(self: &Arc<Self>, q: &[u8]) -> io::Result<Vec<u8>> {
        touch(&self.last_used, &self.stale);
        let orig = dns::id_of(q);
        let mut msg = q.to_vec();
        dns::set_id(&mut msg, 0); // RFC 8484: ID 0 keeps HTTP caches effective
        let body = Bytes::from(msg);
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
                let b = resp.into_body().collect().await.map_err(|e| io::Error::other(e.to_string()))?.to_bytes();
                Ok::<_, io::Error>(b.to_vec())
            };
            match tokio::time::timeout(TLS_TIMEOUT, attempt).await {
                Ok(Ok(mut r)) if r.len() >= 12 => {
                    dns::set_id(&mut r, orig);
                    return Ok(r);
                }
                Ok(Ok(_)) => last = io::Error::new(ErrorKind::InvalidData, "short doh response"),
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
    /// Marks it dead and stops its reader, which holds the socket open while it runs.
    fn close(&self) {
        self.dead.store(true, Ordering::Release);
        if let Some(t) = self.reader.lock().take() {
            t.abort();
        }
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
    /// Boot ms of the last failed dial (0: none), see [`DIAL_FAILED_MS`].
    dial_failed: AtomicU64,
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
            dial_failed: AtomicU64::new(0),
        }
    }

    fn close_idle(&self) {
        if now_ms().saturating_sub(self.last_used.load(Ordering::Relaxed)) < CLOSE_ON_SCREEN_OFF_MS {
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
        if self.stale.swap(false, Ordering::AcqRel)
            && let Some(c) = g.take()
        {
            c.close();
        }
        if let Some(c) = g.as_ref() {
            if !c.dead.load(Ordering::Acquire) {
                return Ok(c.clone());
            }
            // Dead (a write failed, a query timed out): its reader may still hold the socket.
            c.close();
        }
        if dial_failed_recently(&self.dial_failed) {
            return Err(io::Error::new(ErrorKind::NotConnected, DIAL_FAILED_RECENTLY));
        }
        let tls = match tls_connect(&self.host, self.port, &self.bootstrap, &self.connector).await {
            Ok(t) => t,
            Err(e) => {
                self.dial_failed.store(now_ms().max(1), Ordering::Relaxed);
                return Err(e);
            }
        };
        self.dial_failed.store(0, Ordering::Relaxed);
        let (mut reader, writer) = tokio::io::split(tls);
        let c = Arc::new(DotConn {
            writer: tokio::sync::Mutex::new(writer),
            pending: Mutex::new(HashMap::new()),
            dead: AtomicBool::new(false),
            reader: Mutex::new(None),
        });
        let rc = c.clone();
        let task = tokio::spawn(async move {
            while let Ok(len) = reader.read_u16().await {
                let mut buf = vec![0u8; len as usize];
                if reader.read_exact(&mut buf).await.is_err() || buf.len() < 12 {
                    break;
                }
                if let Some(tx) = rc.pending.lock().remove(&dns::id_of(&buf)) {
                    let _ = tx.send(buf);
                }
            }
            rc.dead.store(true, Ordering::Release);
            rc.pending.lock().clear();
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
                if now_ms().saturating_sub(me.last_used.load(Ordering::Relaxed)) >= IDLE_CLOSE.as_millis() as u64 {
                    if let Some(c) = me.conn.lock().await.take() {
                        let _ = c.writer.lock().await.shutdown().await;
                    }
                    return;
                }
            }
        });
    }

    async fn exchange(self: &Arc<Self>, q: &[u8]) -> io::Result<Vec<u8>> {
        touch(&self.last_used, &self.stale);
        let orig = dns::id_of(q);
        let mut last = io::Error::other("dot failed");
        for _ in 0..2 {
            let c = self.conn().await?;
            let (tx, rx) = oneshot::channel();
            let id = {
                let mut p = c.pending.lock();
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
            let sent = c.writer.lock().await.write_all(&msg).await;
            if let Err(e) = sent {
                c.dead.store(true, Ordering::Release);
                c.pending.lock().remove(&id);
                last = e;
                continue;
            }
            match tokio::time::timeout(TLS_TIMEOUT, rx).await {
                Ok(Ok(mut r)) => {
                    dns::set_id(&mut r, orig);
                    return Ok(r);
                }
                Ok(Err(_)) => last = io::Error::new(ErrorKind::ConnectionReset, "dot connection closed"),
                Err(_) => {
                    c.pending.lock().remove(&id);
                    // A connection that let a query time out is taken for dead: the retry dials
                    // a new one instead of reusing the same silent one.
                    c.dead.store(true, Ordering::Release);
                    last = io::Error::new(ErrorKind::TimedOut, "dot timeout");
                }
            }
        }
        Err(last)
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
}
