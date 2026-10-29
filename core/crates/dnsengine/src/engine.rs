//! The TUN loop. Runs on one thread pinned to the efficiency cores, on a current-thread Tokio
//! runtime: it sleeps in epoll until a DNS packet arrives, answers blocked and cached names
//! inline (a few microseconds), and forwards misses as local tasks. Identical in-flight queries
//! are coalesced into one upstream request.
//!
//! The loop itself ([`serve`]) only sees packets in and packets out, so it runs (and is tested)
//! on any platform; the Android part is the file descriptor around it ([`run`]).

use crate::cache::{FAILURE_STALE_MAX, Key, Lookup};
use crate::health::Kind;
use crate::stats::Action;
use crate::{Shared, dns, packet};
use std::cell::RefCell;
use std::collections::HashMap;
use std::io;
use std::net::SocketAddr;
use std::rc::Rc;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};
use tokio::sync::Notify;

/// Largest DNS payload we put into one packet (TUN MTU is 9000).
const MAX_PAYLOAD: usize = 8900;
/// How long an answer waits for its app's UID (a binder call, run on the blocking pool so that
/// queries never queue behind it on the DNS thread) before it goes without one.
const UID_WAIT: Duration = Duration::from_millis(3);
/// An answer from upstream waits at most this long for a UID asked when its query came.
const UID_WAIT_UPSTREAM: Duration = Duration::from_millis(500);
/// At most this many distinct names asked upstream at once; beyond, SERVFAIL at once (a
/// burst of unique names would hold a socket each, from the budget every Chrome connection shares).
const MAX_INFLIGHT: usize = 512;

/// The app behind a query: known, or being looked up on the blocking pool.
enum Uid {
    Known(i32),
    Pending(tokio::task::JoinHandle<i32>),
}

impl Uid {
    async fn resolve(self, wait: Duration) -> i32 {
        match self {
            Uid::Known(u) => u,
            Uid::Pending(h) => tokio::time::timeout(wait, h).await.ok().and_then(Result::ok).unwrap_or(-1),
        }
    }
}

/// The reading side of the TUN device.
pub(crate) trait TunRead {
    /// Waits until packets arrive and hands each one to `f`. `Ok(false)`: end of file (the VPN
    /// interface is gone).
    async fn read_batch(&mut self, buf: &mut [u8], f: &mut dyn FnMut(&[u8])) -> io::Result<bool>;
}

/// The writing side of the TUN device. False: the packet was not written.
pub(crate) trait TunWrite {
    fn write(&self, bytes: &[u8]) -> bool;
}

fn micros(t0: Instant) -> u32 {
    t0.elapsed().as_micros().min(u32::MAX as u128) as u32
}

struct Pending {
    raw: Vec<u8>,
    q: dns::Query,
    client: SocketAddr,
    resolver: SocketAddr,
    uid: Uid,
    t0: Instant,
}

struct Ctx<W> {
    shared: Arc<Shared>,
    tun: W,
    inflight: RefCell<HashMap<Key, Vec<Pending>>>,
    out: RefCell<Vec<u8>>,
}

#[cfg(not(unix))]
pub fn run(_shared: Arc<Shared>, _fd: i32, _stop: Arc<Notify>) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "the TUN engine only runs on Android"))
}

#[cfg(unix)]
pub use fd::run;

#[cfg(unix)]
mod fd {
    use super::{TunRead, TunWrite, serve};
    use crate::{Shared, cpu};
    use std::io;
    use std::os::fd::{AsRawFd, RawFd};
    use std::sync::Arc;
    use tokio::io::unix::AsyncFd;
    use tokio::sync::Notify;

    /// Owns the descriptor: closes it when the loop ends.
    struct Tun(RawFd);

    impl AsRawFd for Tun {
        fn as_raw_fd(&self) -> RawFd {
            self.0
        }
    }

    impl Drop for Tun {
        fn drop(&mut self) {
            unsafe { libc::close(self.0) };
        }
    }

    struct Reader(AsyncFd<Tun>);

    impl TunRead for Reader {
        async fn read_batch(&mut self, buf: &mut [u8], f: &mut dyn FnMut(&[u8])) -> io::Result<bool> {
            let fd = self.0.as_raw_fd();
            let mut guard = self.0.readable().await?;
            loop {
                let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
                if n > 0 {
                    f(&buf[..n as usize]);
                    continue;
                }
                if n == 0 {
                    return Ok(false);
                }
                let e = io::Error::last_os_error();
                match e.kind() {
                    io::ErrorKind::WouldBlock => {
                        guard.clear_ready();
                        return Ok(true);
                    }
                    io::ErrorKind::Interrupted => continue,
                    _ => return Err(e),
                }
            }
        }
    }

    /// Writes to the descriptor the reader owns. Pending forwards never run after the loop
    /// ended (the local set is not polled again), so this never writes to a closed fd.
    struct Writer(RawFd);

    impl TunWrite for Writer {
        fn write(&self, bytes: &[u8]) -> bool {
            // EAGAIN on a TUN write is practically impossible; the client retries anyway.
            unsafe { libc::write(self.0, bytes.as_ptr().cast(), bytes.len()) >= 0 }
        }
    }

    /// Takes ownership of `fd` (a detached VpnService descriptor) and blocks until `stop` is
    /// notified or the descriptor ends.
    pub fn run(shared: Arc<Shared>, fd: RawFd, stop: Arc<Notify>) -> io::Result<()> {
        *shared.efficiency_cpus.lock() = cpu::pin_current_thread_to_efficiency_cores();
        let tun = Tun(fd);
        unsafe {
            let flags = libc::fcntl(fd, libc::F_GETFL);
            libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
        // Blocking threads run the UID lookups (binder calls); they inherit the CPU affinity. They
        // are kept 120 s, as the proxy's are: with Tokio's 10 s nearly every query burst of a
        // sleeping phone would create a thread and attach it to the JVM anew. An idle thread
        // still wakes once, when it exits.
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .max_blocking_threads(4)
            .thread_keep_alive(std::time::Duration::from_secs(120))
            .build()?;
        let local = tokio::task::LocalSet::new();
        let result = local.block_on(&rt, async {
            let reader = Reader(AsyncFd::new(tun)?);
            serve(shared.clone(), reader, Writer(fd), stop).await
        });
        shared.persist_stats();
        drop(local);
        // A blocking task stuck in a system call (a DoH bootstrap lookup, a binder call) must not
        // hold the service's Pause or Resume.
        rt.shutdown_timeout(std::time::Duration::from_millis(500));
        result
    }
}

/// The loop: answers packets until `stop` is notified or the reader ends. Must run inside a
/// `LocalSet` (forwards are local tasks).
pub(crate) async fn serve<R: TunRead, W: TunWrite + 'static>(shared: Arc<Shared>, mut rx: R, tx: W, stop: Arc<Notify>) -> io::Result<()> {
    let ctx = Rc::new(Ctx { shared, tun: tx, inflight: RefCell::new(HashMap::new()), out: RefCell::new(Vec::with_capacity(9000)) });
    let mut buf = vec![0u8; 65536];
    let mut on_packet = |p: &[u8]| handle_packet(&ctx, p);
    loop {
        tokio::select! {
            biased;
            _ = stop.notified() => return Ok(()),
            more = rx.read_batch(&mut buf, &mut on_packet) => {
                if !more? {
                    return Ok(());
                }
            }
        }
    }
}

fn write_tun<W: TunWrite>(ctx: &Ctx<W>, bytes: &[u8]) {
    if !ctx.tun.write(bytes) {
        ctx.shared.counters.dropped.fetch_add(1, Ordering::Relaxed);
    }
}

/// Sends `payload`, the answer to `q` (`raw`). One too big for the packet, or for a client
/// without EDNS (512 bytes), goes as an empty answer with the TC bit instead, so the app does
/// not wait for its full timeout. EDNS clients take up to the packet limit (Android's resolver
/// reads big datagrams).
fn reply<W: TunWrite>(ctx: &Ctx<W>, client: SocketAddr, resolver: SocketAddr, payload: &[u8], raw: &[u8], q: &dns::Query) {
    let limit = if q.edns { MAX_PAYLOAD } else { 512 };
    let truncated;
    let payload = if payload.len() > limit {
        ctx.shared.counters.truncated.fetch_add(1, Ordering::Relaxed);
        truncated = dns::truncated_response(raw, q);
        &truncated[..]
    } else {
        payload
    };
    let mut out = ctx.out.borrow_mut();
    packet::build_udp(resolver, client, payload, &mut out);
    write_tun(ctx, &out);
}

/// Answers now, or once the app's UID is known (at most [`UID_WAIT`]), without holding up the
/// loop.
#[allow(clippy::too_many_arguments)]
fn answer<W: TunWrite + 'static>(
    ctx: &Rc<Ctx<W>>,
    u: &packet::Udp,
    q: &dns::Query,
    uid: Uid,
    resp: Vec<u8>,
    action: Action,
    why: Option<(String, String)>,
    t0: Instant,
) {
    match uid {
        Uid::Known(uid) => {
            reply(ctx, u.src, u.dst, &resp, u.payload, q);
            ctx.shared.record(&q.name, q.qtype, uid, action, why, micros(t0));
        }
        pending => {
            let (ctx, client, resolver, raw, q) = (ctx.clone(), u.src, u.dst, u.payload.to_vec(), q.clone());
            tokio::task::spawn_local(async move {
                let uid = pending.resolve(UID_WAIT).await;
                reply(&ctx, client, resolver, &resp, &raw, &q);
                ctx.shared.record(&q.name, q.qtype, uid, action, why, micros(t0));
            });
        }
    }
}

fn handle_packet<W: TunWrite + 'static>(ctx: &Rc<Ctx<W>>, p: &[u8]) {
    let c = &ctx.shared.counters;
    c.packets.fetch_add(1, Ordering::Relaxed);
    match packet::parse(p) {
        packet::Packet::Udp(u) if u.dst.port() == 53 => handle_query(ctx, u),
        packet::Packet::Udp(u) => {
            // DNS-over-QUIC / HTTP/3 to a hijacked resolver: refuse at once (port
            // unreachable) so the app falls back instead of waiting for its timeout.
            let mut out = ctx.out.borrow_mut();
            packet::build_port_unreachable(p, &u, &mut out);
            write_tun(ctx, &out);
            c.rst_sent.fetch_add(1, Ordering::Relaxed);
        }
        packet::Packet::TcpSyn(syn) => {
            // DoT/DoH/TCP-DNS to a hijacked resolver: refuse at once so the app falls back.
            let mut out = ctx.out.borrow_mut();
            packet::build_tcp_rst(&syn, &mut out);
            write_tun(ctx, &out);
            c.rst_sent.fetch_add(1, Ordering::Relaxed);
        }
        _ => {
            c.dropped.fetch_add(1, Ordering::Relaxed);
        }
    }
}

fn handle_query<W: TunWrite + 'static>(ctx: &Rc<Ctx<W>>, u: packet::Udp) {
    let t0 = Instant::now();
    let shared = &ctx.shared;
    let Some(q) = dns::parse_query(u.payload) else {
        shared.counters.dropped.fetch_add(1, Ordering::Relaxed);
        return;
    };
    shared.counters.dns_queries.fetch_add(1, Ordering::Relaxed);
    let cfg = shared.config.load();
    let logging = cfg.log_capacity > 0;
    // The app's UID is asked on the blocking pool, alongside everything else.
    let uid = if logging && cfg.resolve_uids && shared.uid_lookup.get().is_some() {
        let (s, src, dst) = (shared.clone(), u.src, u.dst);
        Uid::Pending(tokio::task::spawn_blocking(move || s.lookup_uid(17, src, dst)))
    } else {
        Uid::Known(-1)
    };

    let filters = shared.filters.load();
    let hit = filters.check(&q.name);
    if shared.filtering_active(&cfg) && hit.blocked() {
        let resp = dns::blocked_response(u.payload, &q, cfg.block_mode, cfg.blocked_ttl);
        let why = if logging { filters.describe(&q.name, &hit) } else { None };
        answer(ctx, &u, &q, uid, resp, Action::Blocked, why, t0);
        return;
    }
    // A name an allow rule matched is not CNAME-checked: the user's own allow, and the lists'
    // important allows such as YouTube's, must win over a cloaking block.
    let allowed = hit.class.is_allow();

    let key = Key { name: q.name.as_str().into(), qtype: q.qtype, qclass: q.qclass, do_bit: q.do_bit };
    let cached = shared.cache.lock().get(&key, q.id, cfg.optimistic_cache, crate::clock::boot_ms());
    // A cached answer is checked for CNAME cloaking too (against the rules of now: a rule
    // added later applies at once, and a blocked alias need not be asked upstream again).
    if let Lookup::Fresh(_, Some(cn)) | Lookup::Stale(_, Some(cn)) = &cached
        && !allowed
        && let Some(why) = cname_blocked(shared, &cfg, &cn[..])
    {
        let resp = dns::blocked_response(u.payload, &q, cfg.block_mode, cfg.blocked_ttl);
        answer(ctx, &u, &q, uid, resp, Action::BlockedCname, if logging { why } else { None }, t0);
        return;
    }
    match cached {
        Lookup::Fresh(r, _) => {
            answer(ctx, &u, &q, uid, r, Action::Cache, None, t0);
            return;
        }
        Lookup::Stale(r, _) => {
            answer(ctx, &u, &q, uid, r, Action::Stale, None, t0);
            let mut inflight = ctx.inflight.borrow_mut();
            if !inflight.contains_key(&key) {
                inflight.insert(key.clone(), Vec::new());
                tokio::task::spawn_local(forward(ctx.clone(), key, u.payload.to_vec()));
            }
            return;
        }
        Lookup::Miss => {}
    }

    let mut inflight = ctx.inflight.borrow_mut();
    if let Some(waiters) = inflight.get_mut(&key) {
        waiters.push(Pending { raw: u.payload.to_vec(), q, client: u.src, resolver: u.dst, uid, t0 });
        shared.counters.coalesced.fetch_add(1, Ordering::Relaxed);
        return;
    }
    if inflight.len() >= MAX_INFLIGHT {
        drop(inflight);
        shared.counters.overload.fetch_add(1, Ordering::Relaxed);
        let r = dns::error_response(u.payload, &q, dns::RCODE_SERVFAIL);
        answer(ctx, &u, &q, uid, r, Action::Failed, None, t0);
        return;
    }
    let raw = u.payload.to_vec();
    inflight.insert(key.clone(), vec![Pending { raw: raw.clone(), q, client: u.src, resolver: u.dst, uid, t0 }]);
    drop(inflight);
    tokio::task::spawn_local(forward(ctx.clone(), key, raw));
}

/// The rule that blocks a name in a CNAME chain (CNAME cloaking: a first-party name that
/// aliases a blocked tracker), when that check is on. Some(None): blocked, no description.
fn cname_blocked<S: AsRef<str>>(shared: &Shared, cfg: &crate::Config, cnames: &[S]) -> Option<Option<(String, String)>> {
    if cnames.is_empty() || !cfg.cname_check || !shared.filtering_active(cfg) {
        return None;
    }
    let filters = shared.filters.load();
    cnames.iter().find_map(|c| {
        let c = c.as_ref();
        let hit = filters.check(c);
        hit.blocked().then(|| filters.describe(c, &hit).map(|(r, l)| (format!("{r} via CNAME {c}"), l)))
    })
}

async fn forward<W: TunWrite>(ctx: Rc<Ctx<W>>, key: Key, raw: Vec<u8>) {
    let shared = ctx.shared.clone();
    let t_fwd = Instant::now();
    // One upstream query per name for both paths: a lookup the HTTPS filter has in flight for
    // this name is joined, and one made here is joined by it.
    let result = match crate::resolve::coalesce(&shared, &key) {
        crate::resolve::Coalesce::Wait(rx) => match rx.await {
            Ok(Ok(r)) => Ok(r),
            Ok(Err(e)) => Err(std::io::Error::other(e)),
            Err(_) => Err(std::io::Error::other("lookup abandoned")),
        },
        crate::resolve::Coalesce::Lead(mut lead) => {
            let r = shared.upstream_exchange(&raw).await;
            lead.done(&r);
            r
        }
    };
    let waiters = ctx.inflight.borrow_mut().remove(&key).unwrap_or_default();
    let cfg = shared.config.load();

    // An upstream that could not answer (an error, or its SERVFAIL/REFUSED) is covered by an
    // expired answer where there is one: the app keeps working while the resolver recovers.
    // Without one, a SERVFAIL from upstream is passed on as it is.
    let failed = match &result {
        Err(e) => Some(e.to_string()),
        Ok(r) if matches!(dns::rcode_of(r), dns::RCODE_SERVFAIL | dns::RCODE_REFUSED) => Some(format!("upstream answered rcode {}", dns::rcode_of(r))),
        Ok(_) => None,
    };
    if let Some(why) = failed {
        // A background refresh of an answer the client already got (stale): nobody waits,
        // and the old answer stays in the cache. Not a failure anyone saw.
        if waiters.is_empty() {
            log::debug!("refresh of {} failed: {why}", key.name);
            return;
        }
        shared.counters.upstream_errors.fetch_add(1, Ordering::Relaxed);
        let now = crate::clock::boot_ms();
        let mut stale = false;
        let done = answer_all(&ctx, waiters, |w| {
            let old = shared.cache.lock().get_stale_any(&key, w.q.id, FAILURE_STALE_MAX, now);
            match (old, &result) {
                (Some(r), _) => {
                    stale = true;
                    (r, Action::Stale, None)
                }
                (None, Ok(server_said)) => {
                    let mut r = server_said.clone();
                    dns::set_id(&mut r, w.q.id);
                    (r, Action::Failed, None)
                }
                (None, Err(_)) => (dns::error_response(&w.raw, &w.q, dns::RCODE_SERVFAIL), Action::Failed, None),
            }
        });
        let kind = if stale { Kind::DnsStaleServed } else { Kind::DnsFailed };
        shared.dns_failure(kind, &key.name, t_fwd.elapsed().as_millis() as u32, &why);
        // Refused at once by us (the encrypted resolver's dial failed moments ago): no wait for
        // any resolver, like a failure without a network.
        let refused_here = why.contains(crate::upstream::DIAL_FAILED_RECENTLY);
        record_all(&shared, done, !shared.offline() && !refused_here).await;
        return;
    }
    let Ok(resp) = result else { return };
    let info = dns::inspect(&resp);
    // Cached before the CNAME check: an answer aliasing a blocked tracker is then answered
    // (blocked) from the cache next time instead of being asked upstream again; every cache
    // hit is checked against the current rules.
    if let Some(info) = &info {
        // As the HTTPS filter's own lookups do: it may join this one (the rebinding guard).
        shared.note_answer(&key.name, info);
        shared.cache.lock().insert(key.clone(), &resp, info);
    }

    // CNAME cloaking: a first-party name that aliases to a blocked tracker (not checked for a
    // name an allow rule matched).
    let allowed = shared.filters.load().check(&key.name).class.is_allow();
    if let Some(info) = &info
        && !allowed
        && let Some(why) = cname_blocked(&shared, &cfg, &info.cnames[..])
    {
        let done = answer_all(&ctx, waiters, |w| (dns::blocked_response(&w.raw, &w.q, cfg.block_mode, cfg.blocked_ttl), Action::BlockedCname, why.clone()));
        record_all(&shared, done, true).await;
        return;
    }
    let done = answer_all(&ctx, waiters, |w| {
        let mut r = resp.clone();
        dns::set_id(&mut r, w.q.id);
        (r, Action::Upstream, None)
    });
    record_all(&shared, done, true).await;
}

/// What an answered waiter still has to record once its app is known.
struct Answered {
    uid: Uid,
    name: String,
    qtype: u16,
    action: Action,
    why: Option<(String, String)>,
    micros: u32,
}

/// Replies to every app waiting on one upstream answer at once; their apps are looked up
/// afterwards, for the log only (a reply after each app's lookup in turn, up to 0.5 s each,
/// would leave the fifth app of a burst up to 2 s late). The lookups
/// started when the queries arrived, so they are almost always done by now.
fn answer_all<W: TunWrite>(ctx: &Ctx<W>, waiters: Vec<Pending>, mut f: impl FnMut(&Pending) -> (Vec<u8>, Action, Option<(String, String)>)) -> Vec<Answered> {
    waiters
        .into_iter()
        .map(|w| {
            let (r, action, why) = f(&w);
            reply(ctx, w.client, w.resolver, &r, &w.raw, &w.q);
            Answered { micros: micros(w.t0), uid: w.uid, name: w.q.name, qtype: w.q.qtype, action, why }
        })
        .collect()
}

/// Records answered waiters, all within one shared [`UID_WAIT_UPSTREAM`] (not one each).
/// `waited`: their wait was the resolver's (see [`stats::Stats::add_from`]); not for a failure
/// without a network, which fails at once and says nothing about any resolver.
async fn record_all(shared: &Shared, done: Vec<Answered>, waited: bool) {
    let deadline = tokio::time::Instant::now() + UID_WAIT_UPSTREAM;
    for a in done {
        let uid = a.uid.resolve(deadline.saturating_duration_since(tokio::time::Instant::now())).await;
        if waited {
            shared.record_waited(&a.name, a.qtype, uid, a.action, a.why, a.micros);
        } else {
            shared.record(&a.name, a.qtype, uid, a.action, a.why, a.micros);
        }
    }
}

#[cfg(test)]
mod tests {
    //! The TUN loop off the device: packets go in through a channel, answers come out
    //! into a list, upstream is a local UDP server.
    use super::*;
    use crate::Config;
    use matcher::{Filters, Source, Table};
    use std::net::IpAddr;
    use std::sync::atomic::AtomicUsize;
    use std::time::Duration;
    use tokio::net::UdpSocket;
    use tokio::sync::mpsc;

    struct ChanReader(mpsc::UnboundedReceiver<Vec<u8>>);

    impl TunRead for ChanReader {
        async fn read_batch(&mut self, _buf: &mut [u8], f: &mut dyn FnMut(&[u8])) -> io::Result<bool> {
            let Some(p) = self.0.recv().await else { return Ok(false) };
            f(&p);
            while let Ok(p) = self.0.try_recv() {
                f(&p);
            }
            Ok(true)
        }
    }

    #[derive(Clone, Default)]
    struct VecWriter(Rc<RefCell<Vec<Vec<u8>>>>);

    impl TunWrite for VecWriter {
        fn write(&self, bytes: &[u8]) -> bool {
            self.0.borrow_mut().push(bytes.to_vec());
            true
        }
    }

    const CLIENT: &str = "10.111.222.2:40000";
    const RESOLVER: &str = "10.111.222.1:53";

    fn query_packet(id: u16, name: &str, port: u16) -> Vec<u8> {
        let mut out = Vec::new();
        let to = SocketAddr::new(RESOLVER.parse::<SocketAddr>().unwrap().ip(), port);
        packet::build_udp(CLIENT.parse().unwrap(), to, &dns::build_query(id, name, dns::TYPE_A), &mut out);
        out
    }

    /// The DNS payload of an answer the loop wrote (None: not a UDP packet to the client).
    fn answer(p: &[u8]) -> Option<Vec<u8>> {
        match packet::parse(p) {
            packet::Packet::Udp(u) if u.dst == CLIENT.parse().unwrap() && u.src == RESOLVER.parse().unwrap() => Some(u.payload.to_vec()),
            _ => None,
        }
    }

    /// A resolver answering A 1.2.3.4 (TTL 300) after `delay_ms`, counting the queries it got.
    async fn fake_resolver(delay_ms: u64) -> (SocketAddr, Arc<AtomicUsize>) {
        let server = Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        let addr = server.local_addr().unwrap();
        let hits = Arc::new(AtomicUsize::new(0));
        let h = hits.clone();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                h.fetch_add(1, Ordering::SeqCst);
                let q = dns::parse_query(&buf[..n]).unwrap();
                let mut r = dns::blocked_response(&buf[..n], &q, dns::BlockMode::NullIp, 300);
                let l = r.len();
                let at = if q.edns { l - 11 - 4 } else { l - 4 };
                r[at..at + 4].copy_from_slice(&[1, 2, 3, 4]);
                let s = server.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(delay_ms)).await;
                    s.send_to(&r, from).await.unwrap();
                });
            }
        });
        (addr, hits)
    }

    fn shared_with(upstream: SocketAddr) -> Arc<Shared> {
        let shared = Shared::new(Config { resolve_uids: false, ..Config::default() });
        let (bytes, _) = matcher::compile(&[Source { name: "test", text: "||ads.test^\n", user: false, allow: false }]);
        shared.set_filters(Filters { lists: Table::from_bytes(bytes).unwrap(), user: Table::empty() });
        shared.set_network(vec![upstream], true, false, 1);
        shared
    }

    async fn wait_for(out: &VecWriter, n: usize) {
        for _ in 0..300 {
            if out.0.borrow().len() >= n {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("expected {n} packets, got {}", out.0.borrow().len());
    }

    /// Blocked names are answered locally, misses go upstream once even when asked twice at
    /// the same time, the answer is cached, other UDP is refused, and the loop ends when the
    /// device does.
    #[tokio::test]
    async fn tun_loop_blocks_forwards_coalesces_and_caches() {
        let (up, hits) = fake_resolver(150).await;
        let shared = shared_with(up);
        let (tx, rx) = mpsc::unbounded_channel();
        let out = VecWriter::default();
        let local = tokio::task::LocalSet::new();
        let stop = Arc::new(Notify::new());
        let loop_ = local.spawn_local(serve(shared.clone(), ChanReader(rx), out.clone(), stop));
        local
            .run_until(async {
                tx.send(query_packet(1, "ads.test", 53)).unwrap();
                tx.send(query_packet(2, "www.ads.test", 53)).unwrap();
                wait_for(&out, 2).await;
                for (i, p) in out.0.borrow().iter().enumerate() {
                    let r = answer(p).expect("an answer");
                    assert_eq!(dns::id_of(&r), i as u16 + 1);
                    assert_eq!(dns::inspect(&r).unwrap().addrs, vec!["0.0.0.0".parse::<IpAddr>().unwrap()]);
                }
                assert_eq!(hits.load(Ordering::SeqCst), 0, "blocked names never go upstream");

                tx.send(query_packet(3, "example.test", 53)).unwrap();
                tx.send(query_packet(4, "example.test", 53)).unwrap();
                wait_for(&out, 4).await;
                let ids: Vec<u16> = out.0.borrow()[2..].iter().map(|p| dns::id_of(&answer(p).unwrap())).collect();
                assert_eq!(ids, [3, 4], "each waiter gets its own id");
                assert_eq!(hits.load(Ordering::SeqCst), 1, "two identical queries in flight: one upstream query");
                assert_eq!(shared.counters.coalesced.load(Ordering::Relaxed), 1);

                tx.send(query_packet(5, "example.test", 53)).unwrap();
                wait_for(&out, 5).await;
                let r = answer(&out.0.borrow()[4]).unwrap();
                assert_eq!(dns::inspect(&r).unwrap().addrs, vec!["1.2.3.4".parse::<IpAddr>().unwrap()]);
                assert_eq!(hits.load(Ordering::SeqCst), 1, "served from the cache");

                // DNS over QUIC to the hijacked resolver: an ICMP error, not a UDP answer.
                tx.send(query_packet(6, "example.test", 853)).unwrap();
                wait_for(&out, 6).await;
                assert!(answer(&out.0.borrow()[5]).is_none());
                assert_eq!(shared.counters.rst_sent.load(Ordering::Relaxed), 1);
                drop(tx);
            })
            .await;
        tokio::time::timeout(Duration::from_secs(2), local.run_until(loop_)).await.expect("loop ends with the device").unwrap().unwrap();
    }

    /// Runs the loop with `send` feeding it packets; returns once `n` answers came out.
    async fn run_loop(shared: Arc<Shared>, packets: Vec<Vec<u8>>, n: usize) -> Vec<Vec<u8>> {
        let (tx, rx) = mpsc::unbounded_channel();
        let out = VecWriter::default();
        let local = tokio::task::LocalSet::new();
        let stop = Arc::new(Notify::new());
        local.spawn_local(serve(shared, ChanReader(rx), out.clone(), stop.clone()));
        local
            .run_until(async {
                for p in packets {
                    tx.send(p).unwrap();
                    tokio::time::sleep(Duration::from_millis(5)).await;
                }
                wait_for(&out, n).await;
            })
            .await;
        stop.notify_one();
        out.0.borrow().iter().filter_map(|p| answer(p)).collect()
    }

    /// Apps waiting on one upstream answer all get it when it arrives, even while their app
    /// lookups queue up behind each other (a busy system server); the log still names them.
    #[tokio::test]
    async fn coalesced_answers_do_not_wait_for_app_lookups() {
        let (up, _) = fake_resolver(50).await;
        let shared = Shared::new(Config { resolve_uids: true, ..Config::default() });
        shared.set_network(vec![up], true, false, 1);
        let busy = Arc::new(parking_lot::Mutex::new(()));
        let _ = shared.uid_lookup.set(Box::new(move |_, _, _| {
            let _g = busy.lock();
            std::thread::sleep(Duration::from_millis(300));
            10_123
        }));
        let t0 = Instant::now();
        let got = run_loop(shared.clone(), (1..=3).map(|i| query_packet(i, "same.test", 53)).collect(), 3).await;
        let took = t0.elapsed();
        assert_eq!(got.len(), 3);
        assert!(took < Duration::from_millis(280), "answers waited for the app lookups: {took:?}");
    }

    /// A query in flight when the phone moves to another network is asked there at once, not
    /// after the old resolver's whole budget (3.5 s).
    #[tokio::test]
    async fn query_in_flight_moves_with_the_network() {
        let dead = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let (live, hits) = fake_resolver(0).await;
        let shared = shared_with(dead.local_addr().unwrap());
        let (tx, rx) = mpsc::unbounded_channel();
        let out = VecWriter::default();
        let local = tokio::task::LocalSet::new();
        local.spawn_local(serve(shared.clone(), ChanReader(rx), out.clone(), Arc::new(Notify::new())));
        local
            .run_until(async {
                let t0 = Instant::now();
                tx.send(query_packet(7, "moving.test", 53)).unwrap();
                tokio::time::sleep(Duration::from_millis(100)).await;
                shared.set_network(vec![live], true, false, 2);
                wait_for(&out, 1).await;
                let r = answer(&out.0.borrow()[0]).unwrap();
                assert_eq!(dns::inspect(&r).unwrap().addrs, vec!["1.2.3.4".parse::<IpAddr>().unwrap()]);
                assert!(t0.elapsed() < Duration::from_millis(600), "{:?}", t0.elapsed());
                assert_eq!(hits.load(Ordering::SeqCst), 1);
                assert_eq!(shared.counters.dns_requeried.load(Ordering::Relaxed), 1);
            })
            .await;
        drop(dead);
    }

    /// With an encrypted resolver chosen (here one that cannot be reached), names under the
    /// network's search domain and private reverse names still get the network's answer at
    /// once, through the TUN loop and the proxy's path; other names still go to the encrypted one.
    #[tokio::test]
    async fn network_names_go_to_the_network_resolver() {
        let (up, hits) = fake_resolver(0).await;
        let doh = crate::UpstreamConfig::Doh { url: "https://dns.test/dns-query".into(), bootstrap: vec!["192.0.2.1".parse().unwrap()] };
        let shared = Shared::new(Config { resolve_uids: false, upstream: doh, ..Config::default() });
        shared.set_network_info("Wi-Fi · campus.test", vec!["campus.test".into()]);
        shared.set_network(vec![up], true, false, 1);
        let t0 = Instant::now();
        let got = run_loop(shared.clone(), vec![query_packet(1, "lms.campus.test", 53), query_packet(2, "5.1.168.192.in-addr.arpa", 53)], 2).await;
        assert_eq!(got.len(), 2);
        assert_eq!(dns::inspect(&got[0]).unwrap().addrs, vec!["1.2.3.4".parse::<IpAddr>().unwrap()]);
        assert!(t0.elapsed() < Duration::from_secs(1), "{:?}", t0.elapsed());
        assert_eq!(hits.load(Ordering::SeqCst), 2);
        let r = crate::resolve_with(&shared, "moodle.campus.test", false).await.unwrap();
        assert_eq!(r.addrs, vec!["1.2.3.4".parse::<IpAddr>().unwrap()]);
        assert_eq!(hits.load(Ordering::SeqCst), 3);
        // Another name: the encrypted resolver (unreachable here), never the network's.
        let s = shared.clone();
        let other = tokio::spawn(async move { crate::resolve_with(&s, "www.example.test", false).await });
        tokio::time::sleep(Duration::from_millis(300)).await;
        assert_eq!(hits.load(Ordering::SeqCst), 3);
        other.abort();
        assert_eq!(shared.counters.network_names.load(Ordering::Relaxed), 3);
        let d: serde_json::Value = serde_json::from_str(&shared.diagnostics_json()).unwrap();
        assert_eq!(d["upstream"], "doh dns.test");
        assert!(d["upstream_latency"]["p99_us"].as_u64().unwrap() > 0, "{d}");
    }

    /// With an encrypted resolver, a campus name answered publicly at home and still cached is
    /// asked of the campus network's resolver once the phone joins that network.
    #[tokio::test]
    async fn network_names_cached_elsewhere_are_asked_again() {
        let (up, hits) = fake_resolver(0).await;
        let doh = crate::UpstreamConfig::Doh { url: "https://dns.test/dns-query".into(), bootstrap: vec!["192.0.2.1".parse().unwrap()] };
        let shared = Shared::new(Config { resolve_uids: false, upstream: doh, ..Config::default() });
        shared.set_network_info("Wi-Fi · home", vec![]);
        shared.set_network(vec![up], true, false, 1);
        cache_answer(&shared, "lms.campus.test", 300);
        shared.set_network_info("Wi-Fi · campus.test", vec!["campus.test".into()]);
        shared.set_network(vec![up], true, false, 2);
        let got = crate::resolve_with(&shared, "lms.campus.test", false).await.unwrap();
        assert_eq!(got.addrs, vec!["1.2.3.4".parse::<IpAddr>().unwrap()]);
        assert_eq!(hits.load(Ordering::SeqCst), 1);
    }

    /// A resolver that answers every query with SERVFAIL after `delay_ms`.
    async fn failing_resolver(delay_ms: u64) -> SocketAddr {
        let server = Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let q = dns::parse_query(&buf[..n]).unwrap();
                let r = dns::error_response(&buf[..n], &q, dns::RCODE_SERVFAIL);
                let s = server.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(delay_ms)).await;
                    s.send_to(&r, from).await.unwrap();
                });
            }
        });
        addr
    }

    /// A cached public A answer `9.9.9.9` for `name` with `ttl`.
    fn cache_answer(shared: &Shared, name: &str, ttl: u32) {
        let raw = dns::build_query(1, name, dns::TYPE_A);
        let q = dns::parse_query(&raw).unwrap();
        let mut r = dns::blocked_response(&raw, &q, dns::BlockMode::NullIp, ttl);
        let l = r.len();
        r[l - 15..l - 11].copy_from_slice(&[9, 9, 9, 9]);
        let info = dns::inspect(&r).unwrap();
        let key = Key { name: name.into(), qtype: dns::TYPE_A, qclass: dns::CLASS_IN, do_bit: false };
        assert!(shared.cache.lock().insert(key, &r, &info));
    }

    /// The proxy's lookup joins an app's query in flight that gets SERVFAIL: it is
    /// answered with the expired address, as the app is (it got "no address" at once).
    #[tokio::test]
    async fn joined_lookup_gets_the_stale_answer_on_servfail() {
        let up = failing_resolver(200).await;
        let shared = Shared::new(Config { resolve_uids: false, optimistic_cache: false, ..Config::default() });
        shared.set_network(vec![up], true, false, 1);
        cache_answer(&shared, "old.test", 1);
        tokio::time::sleep(Duration::from_millis(1100)).await;
        let (tx, rx) = mpsc::unbounded_channel();
        let out = VecWriter::default();
        let local = tokio::task::LocalSet::new();
        local.spawn_local(serve(shared.clone(), ChanReader(rx), out.clone(), Arc::new(Notify::new())));
        local
            .run_until(async {
                tx.send(query_packet(1, "old.test", 53)).unwrap();
                tokio::time::sleep(Duration::from_millis(20)).await;
                let r = crate::resolve_with(&shared, "old.test", false).await.expect("the stale answer");
                assert_eq!(r.addrs, vec!["9.9.9.9".parse::<IpAddr>().unwrap()]);
                wait_for(&out, 1).await;
                let app = answer(&out.0.borrow()[0]).unwrap();
                assert_eq!(dns::inspect(&app).unwrap().addrs, vec!["9.9.9.9".parse::<IpAddr>().unwrap()], "the app's too");
            })
            .await;
    }

    /// An app's query answered from an expired entry after its resolver failed (300 ms)
    /// counts as the resolver's latency, not as a slow cache answer.
    #[tokio::test]
    async fn a_failed_upstream_wait_counts_as_upstream_latency() {
        let up = failing_resolver(300).await;
        let shared = Shared::new(Config { resolve_uids: false, optimistic_cache: false, ..Config::default() });
        shared.set_network(vec![up], true, false, 1);
        cache_answer(&shared, "stall.test", 1);
        tokio::time::sleep(Duration::from_millis(1100)).await;
        let got = run_loop(shared.clone(), vec![query_packet(1, "stall.test", 53)], 1).await;
        assert_eq!(dns::inspect(&got[0]).unwrap().addrs, vec!["9.9.9.9".parse::<IpAddr>().unwrap()]);
        tokio::time::sleep(Duration::from_millis(50)).await;
        {
            let s = shared.stats.lock();
            assert!(s.local_latency.percentile(0.99) < 300_000, "{}", s.local_latency.percentile(0.99));
            assert!(s.upstream_latency.percentile(0.99) >= 250_000, "{}", s.upstream_latency.percentile(0.99));
        }
        // Without a network the lookup fails at once: that says nothing about the resolver, and
        // stays out of its histogram (one such sample would pull its median down to ~1 ms).
        shared.set_network(vec![], true, false, 0);
        let got = run_loop(shared.clone(), vec![query_packet(2, "stall.test", 53)], 1).await;
        assert_eq!(dns::inspect(&got[0]).unwrap().addrs, vec!["9.9.9.9".parse::<IpAddr>().unwrap()]);
        tokio::time::sleep(Duration::from_millis(50)).await;
        let s = shared.stats.lock();
        assert!(s.upstream_latency.percentile(0.5) >= 250_000, "{}", s.upstream_latency.percentile(0.5));
    }

    /// After a failed dial of the encrypted resolver, queries fail at once for 3 s ("unreachable
    /// a moment ago"); those instant refusals of our own say nothing about the resolver and stay
    /// out of its latency (counted, they pull its p50 down to ~16 µs while DNS fails).
    #[tokio::test]
    async fn instant_refusals_are_not_resolver_latency() {
        let closed = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let doh = crate::UpstreamConfig::Doh { url: format!("https://dns.test:{closed}/dns-query"), bootstrap: vec!["127.0.0.1".parse().unwrap()] };
        let shared = Shared::new(Config { resolve_uids: false, upstream: doh, ..Config::default() });
        shared.set_network(vec!["127.0.0.1:9".parse().unwrap()], true, false, 1);
        run_loop(shared.clone(), vec![query_packet(1, "a.test", 53)], 1).await;
        let dial = shared.stats.lock().upstream_latency.count();
        run_loop(shared.clone(), (2..6).map(|i| query_packet(i, &format!("n{i}.test"), 53)).collect(), 4).await;
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert_eq!(shared.stats.lock().upstream_latency.count(), dial, "the refused queries were counted");
    }

    /// A name an allow rule matches is not blocked for its CNAME chain (user allow and the
    /// lists' important allows); without the allow it is.
    #[tokio::test]
    async fn allowed_names_skip_the_cname_check() {
        let (up, _) = fake_resolver(0).await;
        let shared = Shared::new(Config { resolve_uids: false, ..Config::default() });
        let (lists, _) = matcher::compile(&[Source { name: "l", text: "||tracker.test^\n@@||vip.test^$important\n", user: false, allow: false }]);
        let (user, _) = matcher::compile(&[Source { name: "u", text: "@@||shop.test^\n", user: true, allow: false }]);
        shared.set_filters(Filters { lists: Table::from_bytes(lists).unwrap(), user: Table::from_bytes(user).unwrap() });
        shared.set_network(vec![up], true, false, 1);
        for name in ["shop.test", "vip.test", "plain.test"] {
            let raw = dns::build_query(1, name, dns::TYPE_A);
            let q = dns::parse_query(&raw).unwrap();
            let mut r = dns::blocked_response(&raw, &q, dns::BlockMode::NullIp, 300);
            let l = r.len();
            r[l - 15..l - 11].copy_from_slice(&[1, 2, 3, 4]);
            let mut info = dns::inspect(&r).unwrap();
            info.cnames = vec!["x.tracker.test".into()];
            let key = Key { name: name.into(), qtype: dns::TYPE_A, qclass: dns::CLASS_IN, do_bit: false };
            assert!(shared.cache.lock().insert(key, &r, &info));
        }
        let got = run_loop(shared, vec![query_packet(1, "shop.test", 53), query_packet(2, "vip.test", 53), query_packet(3, "plain.test", 53)], 3).await;
        let addr = |r: &Vec<u8>| dns::inspect(r).unwrap().addrs[0].to_string();
        assert_eq!(addr(&got[0]), "1.2.3.4", "user allow");
        assert_eq!(addr(&got[1]), "1.2.3.4", "important allow");
        assert_eq!(addr(&got[2]), "0.0.0.0", "no allow: CNAME cloaking blocked");
    }

    /// An upstream SERVFAIL is covered by an expired answer when there is one.
    #[tokio::test]
    async fn servfail_is_covered_by_a_stale_answer() {
        let server = Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            let mut n_q = 0;
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                n_q += 1;
                let q = dns::parse_query(&buf[..n]).unwrap();
                let r = if n_q == 1 {
                    let mut r = dns::blocked_response(&buf[..n], &q, dns::BlockMode::NullIp, 1);
                    let l = r.len();
                    r[l - 15..l - 11].copy_from_slice(&[5, 6, 7, 8]);
                    r
                } else {
                    dns::error_response(&buf[..n], &q, dns::RCODE_SERVFAIL)
                };
                server.send_to(&r, from).await.unwrap();
            }
        });
        let shared = shared_with(addr);
        let mut cfg = (**shared.config.load()).clone();
        cfg.optimistic_cache = false;
        shared.set_config(cfg);
        let got = run_loop(shared.clone(), vec![query_packet(1, "flaky.test", 53)], 1).await;
        assert_eq!(dns::inspect(&got[0]).unwrap().addrs[0].to_string(), "5.6.7.8");
        tokio::time::sleep(Duration::from_millis(1100)).await;
        let got = run_loop(shared.clone(), vec![query_packet(2, "flaky.test", 53)], 1).await;
        let info = dns::inspect(&got[0]).unwrap();
        assert_eq!((info.rcode, info.addrs[0].to_string()), (dns::RCODE_NOERROR, "5.6.7.8".to_string()), "the old answer, not SERVFAIL");
        assert_eq!(shared.health_json(10)["counts"]["dns_stale_served"], 1);
    }

    /// An answer too big for a client without EDNS goes as "truncated" at once.
    #[tokio::test]
    async fn too_big_answers_are_truncated() {
        let (up, _) = fake_resolver(0).await;
        let shared = shared_with(up);
        let raw = dns::build_query(9, "big.test", dns::TYPE_A);
        let q = dns::parse_query(&raw).unwrap();
        let mut r = dns::blocked_response(&raw, &q, dns::BlockMode::NullIp, 300);
        let l = r.len();
        r[l - 15..l - 11].copy_from_slice(&[9, 9, 9, 9]);
        let info = dns::inspect(&r).unwrap();
        r.resize(1500, 0);
        shared.cache.lock().insert(Key { name: "big.test".into(), qtype: dns::TYPE_A, qclass: dns::CLASS_IN, do_bit: false }, &r, &info);
        // The same question without its OPT record (no EDNS: 512 bytes at most).
        let mut plain = raw.clone();
        plain.truncate(plain.len() - 11);
        plain[11] = 0;
        let mut pkt = Vec::new();
        packet::build_udp(CLIENT.parse().unwrap(), RESOLVER.parse().unwrap(), &plain, &mut pkt);
        let got = run_loop(shared.clone(), vec![pkt, query_packet(10, "big.test", 53)], 2).await;
        assert!(got[0][2] & 0x02 != 0 && got[0].len() < 512, "TC for the 512-byte client");
        assert_eq!(got[1].len(), 1500, "an EDNS client takes it");
        assert_eq!(shared.counters.truncated.load(Ordering::Relaxed), 1);
    }

    /// `name` encoded as DNS labels.
    fn labels(name: &str) -> Vec<u8> {
        let mut out = Vec::new();
        for l in name.split('.') {
            out.push(l.len() as u8);
            out.extend_from_slice(l.as_bytes());
        }
        out.push(0);
        out
    }

    /// A resolver on an IPv6-only network with DNS64: `dns64.test` is a CNAME to a blocked
    /// tracker with a synthesized AAAA (64:ff9b::/96), `big6.test` has 50 AAAA records (about
    /// 1,450 bytes, more than IPv6's 1,280-byte minimum MTU).
    async fn dns64_resolver() -> SocketAddr {
        let server = UdpSocket::bind("[::1]:0").await.unwrap();
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let q = dns::parse_query(&buf[..n]).unwrap();
                // The question without its OPT record, then the answers.
                let mut r = buf[..n - if q.edns { 11 } else { 0 }].to_vec();
                r[2] = 0x81;
                r[3] = 0x80;
                r[10..12].copy_from_slice(&[0, 0]);
                let synth = |v4: [u8; 4]| -> [u8; 16] {
                    let mut a = [0u8; 16];
                    a[..4].copy_from_slice(&[0, 0x64, 0xff, 0x9b]);
                    a[12..].copy_from_slice(&v4);
                    a
                };
                let rr = |r: &mut Vec<u8>, name: &[u8], rtype: u16, data: &[u8]| {
                    r.extend_from_slice(name);
                    r.extend_from_slice(&rtype.to_be_bytes());
                    r.extend_from_slice(&[0, 1, 0, 0, 1, 44]);
                    r.extend_from_slice(&(data.len() as u16).to_be_bytes());
                    r.extend_from_slice(data);
                };
                let count = match q.name.as_str() {
                    "dns64.test" => {
                        let target = labels("pixel.ads.test");
                        rr(&mut r, &[0xC0, 12], 5, &target);
                        rr(&mut r, &target, dns::TYPE_AAAA, &synth([1, 2, 3, 4]));
                        2u16
                    }
                    _ => {
                        for i in 0..50u8 {
                            rr(&mut r, &[0xC0, 12], dns::TYPE_AAAA, &synth([203, 0, 113, i]));
                        }
                        50
                    }
                };
                r[6..8].copy_from_slice(&count.to_be_bytes());
                server.send_to(&r, from).await.unwrap();
            }
        });
        addr
    }

    /// Apps on IPv6 ask the tunnel's IPv6 resolver address: a blocked AAAA is answered `::`,
    /// a DNS64-synthesized AAAA whose CNAME chain holds a blocked name stays blocked, an answer
    /// over 1,280 bytes reaches an EDNS client whole, and a query to a routed public resolver's
    /// IPv6 address ("Block DNS bypass") is answered from that address.
    #[tokio::test]
    async fn ipv6_clients_and_dns64_answers() {
        let resolver = dns64_resolver().await;
        let shared = shared_with(resolver);
        shared.set_network(vec![resolver], false, true, 1);
        let client: SocketAddr = "[fd00:ad::2]:40000".parse().unwrap();
        let tun: SocketAddr = "[fd00:ad::53]:53".parse().unwrap();
        let hijacked: SocketAddr = "[2001:4860:4860::8888]:53".parse().unwrap();
        let q6 = |id: u16, name: &str, to: SocketAddr| {
            let mut out = Vec::new();
            packet::build_udp(client, to, &dns::build_query(id, name, dns::TYPE_AAAA), &mut out);
            out
        };
        let (tx, rx) = mpsc::unbounded_channel();
        let out = VecWriter::default();
        let local = tokio::task::LocalSet::new();
        let stop = Arc::new(Notify::new());
        local.spawn_local(serve(shared.clone(), ChanReader(rx), out.clone(), stop.clone()));
        local
            .run_until(async {
                for (i, p) in [q6(1, "ads.test", tun), q6(2, "dns64.test", tun), q6(3, "big6.test", tun), q6(4, "big6.test", hijacked)].into_iter().enumerate()
                {
                    tx.send(p).unwrap();
                    wait_for(&out, i + 1).await;
                }
            })
            .await;
        stop.notify_one();
        let got: Vec<(SocketAddr, SocketAddr, usize, Vec<u8>)> = out
            .0
            .borrow()
            .iter()
            .map(|p| match packet::parse(p) {
                packet::Packet::Udp(u) => (u.src, u.dst, p.len(), u.payload.to_vec()),
                other => panic!("{other:?}"),
            })
            .collect();
        let unspecified: std::net::IpAddr = "::".parse().unwrap();
        for (i, (from, to, _, r)) in got.iter().enumerate() {
            assert_eq!((dns::id_of(r), *to), (i as u16 + 1, client));
            assert_eq!(*from, if i == 3 { hijacked } else { tun }, "answer {} comes from the address asked", i + 1);
        }
        assert_eq!(dns::inspect(&got[0].3).unwrap().addrs, vec![unspecified], "blocked AAAA");
        assert_eq!(dns::inspect(&got[1].3).unwrap().addrs, vec![unspecified], "DNS64 answer aliasing a blocked tracker");
        for (_, _, len, r) in &got[2..] {
            let info = dns::inspect(r).unwrap();
            assert!(!info.truncated && info.addrs.len() == 50 && *len > 1280, "{len} bytes, {} addresses", info.addrs.len());
        }
    }

    /// A query from an app and the HTTPS filter's lookup of the same name at the same
    /// moment make one upstream query.
    #[tokio::test]
    async fn tunnel_and_proxy_share_one_query() {
        let (up, hits) = fake_resolver(200).await;
        let shared = shared_with(up);
        let s2 = shared.clone();
        let proxy = tokio::spawn(async move { crate::resolve_with(&s2, "both.test", false).await.map(|r| r.addrs) });
        tokio::time::sleep(Duration::from_millis(20)).await;
        let got = run_loop(shared.clone(), vec![query_packet(1, "both.test", 53)], 1).await;
        assert_eq!(dns::inspect(&got[0]).unwrap().addrs[0].to_string(), "1.2.3.4");
        assert_eq!(proxy.await.unwrap().unwrap()[0].to_string(), "1.2.3.4");
        assert_eq!(hits.load(Ordering::SeqCst), 1, "one upstream query for both paths");
    }

    /// An unreachable upstream with nothing cached: SERVFAIL, recorded once. The loop stops on
    /// its own signal while idle.
    #[tokio::test]
    async fn tun_loop_failure_and_stop() {
        let dead = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = dead.local_addr().unwrap();
        drop(dead);
        let shared = shared_with(addr);
        let (tx, rx) = mpsc::unbounded_channel();
        let out = VecWriter::default();
        let local = tokio::task::LocalSet::new();
        let stop = Arc::new(Notify::new());
        let loop_ = local.spawn_local(serve(shared.clone(), ChanReader(rx), out.clone(), stop.clone()));
        local
            .run_until(async {
                tx.send(query_packet(9, "gone.test", 53)).unwrap();
                for _ in 0..500 {
                    if !out.0.borrow().is_empty() {
                        break;
                    }
                    tokio::time::sleep(Duration::from_millis(20)).await;
                }
                let r = answer(&out.0.borrow()[0]).expect("an answer");
                assert_eq!(dns::inspect(&r).unwrap().rcode, dns::RCODE_SERVFAIL);
                assert_eq!(shared.counters.upstream_errors.load(Ordering::Relaxed), 1);
            })
            .await;
        stop.notify_one();
        tokio::time::timeout(Duration::from_secs(2), local.run_until(loop_)).await.expect("stops on its signal").unwrap().unwrap();
        drop(tx);
    }
}
