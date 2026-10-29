//! Hostname resolution for the HTTPS filter, through the same upstream and cache as the tunnel
//! (so a DoH/DoT choice also applies to Chrome, and answers are shared both ways).
//!
//! Apps behind the proxy never send DNS themselves, so this path must keep its own answers
//! fresh: an expired (stale) answer is used at once and refreshed in the background, the same
//! way the tunnel does it for everyone else.

use crate::cache::{FAILURE_STALE_MAX, Key, Lookup};
use crate::health::Kind;
use crate::{Shared, dns, upstream};
use std::io;
use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

pub struct Resolved {
    pub addrs: Vec<IpAddr>,
    /// At least one family came from the cache (possibly stale); a connect failure may be worth
    /// a fresh lookup.
    pub cached: bool,
    /// Names the answer went through (CNAME chain), for cloaking checks.
    pub cnames: Vec<String>,
    /// The shortest TTL the resolver gave (as given, not what is left of it in the cache).
    /// Rebinding attacks answer with TTLs of a few seconds; split DNS rarely does.
    pub ttl: Option<u32>,
    /// Age of the oldest cached family (boot-clock ms; 0 when fetched now). An answer the
    /// CONNECT-time check fetched milliseconds ago is not worth a second lookup on a slow
    /// connect.
    pub age_ms: u64,
}

pub async fn resolve(shared: &Arc<Shared>, host: &str) -> io::Result<Vec<IpAddr>> {
    resolve_with(shared, host, false).await.map(|r| r.addrs)
}

/// `fresh` skips the cache (used after every cached address failed to connect).
pub async fn resolve_with(shared: &Arc<Shared>, host: &str, fresh: bool) -> io::Result<Resolved> {
    let bare = host.trim_start_matches('[').trim_end_matches(']');
    if let Ok(ip) = bare.parse::<IpAddr>() {
        return Ok(Resolved { addrs: vec![ip], cached: false, cnames: Vec::new(), ttl: None, age_ms: 0 });
    }
    let name = host.trim_end_matches('.').to_ascii_lowercase();
    // Only ask for families the network can use: a v4-only Wi-Fi never needs AAAA.
    let v4 = shared.has_v4.load(Ordering::Relaxed);
    let v6 = shared.has_v6.load(Ordering::Relaxed);
    let family = |qtype: u16| {
        let (s, n) = (shared.clone(), name.clone());
        let abandoned = Arc::new(AtomicBool::new(false));
        let a = abandoned.clone();
        (tokio::spawn(async move { lookup(&s, &n, qtype, fresh, &a).await }), abandoned)
    };
    let results = both_families(v4.then(|| family(dns::TYPE_A)), v6.then(|| family(dns::TYPE_AAAA))).await;
    let mut addrs = Vec::new();
    let mut cnames: Vec<String> = Vec::new();
    let mut cached = false;
    let mut ttl: Option<u32> = None;
    let mut age_ms = 0u64;
    let mut err = None;
    for r in results {
        match r {
            Ok((resp, c, t, age)) => {
                age_ms = age_ms.max(age);
                if let Some(info) = dns::inspect(&resp) {
                    // Only answers that carry addresses say anything about them.
                    if !info.addrs.is_empty()
                        && let Some(t) = t.or(info.ttl)
                    {
                        ttl = Some(ttl.map_or(t, |m| m.min(t)));
                    }
                    addrs.extend(info.addrs);
                    for n in info.cnames {
                        if !cnames.contains(&n) {
                            cnames.push(n);
                        }
                    }
                }
                cached |= c;
            }
            Err(e) => err = Some(e),
        }
    }
    addrs.retain(|ip| !ip.is_unspecified());
    if addrs.is_empty() {
        return Err(err.unwrap_or_else(|| io::Error::new(io::ErrorKind::NotFound, format!("no address for {name}"))));
    }
    Ok(Resolved { addrs, cached, cnames, ttl, age_ms })
}

/// One family's addresses (`v6`: AAAA), from the cache or by joining the lookup in flight (the
/// family [`resolve_with`] did not wait for is usually still being asked when the proxy's
/// connect falls back to it). Its failure is only counted, as that lookup's is. The lookup runs
/// as its own task (as [`resolve_with`]'s): the caller is dropped when its connect wins, and a
/// lookup leading the upstream query would take every app joined to it down with it.
pub async fn resolve_family(shared: &Arc<Shared>, host: &str, v6: bool) -> io::Result<Vec<IpAddr>> {
    let name = host.trim_end_matches('.').to_ascii_lowercase();
    let qtype = if v6 { dns::TYPE_AAAA } else { dns::TYPE_A };
    let s = shared.clone();
    let task = tokio::spawn(async move { lookup(&s, &name, qtype, false, &AtomicBool::new(true)).await });
    let (resp, ..) = task.await.unwrap_or_else(|e| Err(io::Error::other(e)))?;
    Ok(dns::inspect(&resp).map(|i| i.addrs).unwrap_or_default().into_iter().filter(|ip| !ip.is_unspecified()).collect())
}

type Family = io::Result<(Vec<u8>, bool, Option<u32>, u64)>;
/// A family's lookup and its "nobody waits for it" flag.
type Pending = (tokio::task::JoinHandle<Family>, Arc<AtomicBool>);

/// How long the other family is waited for once one has addresses (RFC 8305 section 3's
/// resolution delay).
const OTHER_FAMILY_WAIT: std::time::Duration = std::time::Duration::from_millis(50);

/// The A and AAAA lookups' results, A first. Once one family has addresses, the other gets
/// [`OTHER_FAMILY_WAIT`] more (on a network with IPv6, waiting for a late or lost AAAA would
/// hold every new connection 0.7-3.5 s); a lookup not waited for still finishes and fills the
/// cache, and its failure is only counted (a DNS failure nobody felt is no problem to record).
async fn both_families(a: Option<Pending>, aaaa: Option<Pending>) -> Vec<Family> {
    let joined = |r: Result<Family, tokio::task::JoinError>| r.unwrap_or_else(|e| Err(io::Error::other(e)));
    let has_addrs = |r: &Family| r.as_ref().is_ok_and(|(resp, ..)| dns::inspect(resp).is_some_and(|i| i.addrs.iter().any(|ip| !ip.is_unspecified())));
    match (a, aaaa) {
        (Some((mut a, a_flag)), Some((mut aaaa, aaaa_flag))) => {
            let (first_is_a, first, mut other, other_flag) = tokio::select! {
                r = &mut a => (true, joined(r), aaaa, aaaa_flag),
                r = &mut aaaa => (false, joined(r), a, a_flag),
            };
            let second = if has_addrs(&first) {
                let s = tokio::time::timeout(OTHER_FAMILY_WAIT, &mut other).await.ok().map(joined);
                if s.is_none() {
                    other_flag.store(true, Ordering::Relaxed);
                }
                s
            } else {
                Some(joined(other.await))
            };
            match (first_is_a, second) {
                (true, s) => std::iter::once(first).chain(s).collect(),
                (false, Some(s)) => vec![s, first],
                (false, None) => vec![first],
            }
        }
        (Some((one, _)), None) | (None, Some((one, _))) => vec![joined(one.await)],
        (None, None) => Vec::new(),
    }
}

/// One family: the raw answer, whether it came from the cache, (from the cache) the TTL it was
/// stored with, and its age in ms (0 when fetched now). `abandoned`: nobody waits for it any
/// more, so a failure is counted, not recorded.
async fn lookup(shared: &Arc<Shared>, name: &str, qtype: u16, fresh: bool, abandoned: &AtomicBool) -> io::Result<(Vec<u8>, bool, Option<u32>, u64)> {
    let key = Key { name: name.into(), qtype, qclass: dns::CLASS_IN, do_bit: false };
    if !fresh {
        let optimistic = shared.config.load().optimistic_cache;
        let now = crate::clock::boot_ms();
        let (cached, ttl, age) = {
            let mut c = shared.cache.lock();
            let r = c.get(&key, 0, optimistic, now);
            (r, c.ttl_of(&key), c.age_ms_of(&key, now).unwrap_or(0))
        };
        match cached {
            Lookup::Fresh(r, _) => return Ok((r, true, ttl, age)),
            Lookup::Stale(r, _) => {
                refresh_in_background(shared, key);
                return Ok((r, true, ttl, age));
            }
            Lookup::Miss => {}
        }
    }
    let t0 = Instant::now();
    match shared_exchange(shared, &key).await {
        Ok(r) => Ok((r, false, None, 0)),
        Err(e) => {
            let ms = t0.elapsed().as_millis() as u32;
            let now = crate::clock::boot_ms();
            let (old, ttl, age) = {
                let mut c = shared.cache.lock();
                (c.get_stale_any(&key, 0, FAILURE_STALE_MAX, now), c.ttl_of(&key), c.age_ms_of(&key, now).unwrap_or(0))
            };
            let failed = |k: Kind| {
                if abandoned.load(Ordering::Relaxed) {
                    shared.health.lock().count(k);
                } else {
                    shared.dns_failure(k, name, ms, &e.to_string());
                }
            };
            match old {
                Some(r) => {
                    failed(Kind::DnsStaleServed);
                    Ok((r, true, ttl, age))
                }
                None => {
                    failed(Kind::DnsFailed);
                    Err(e)
                }
            }
        }
    }
}

/// Joining the upstream query for a name, or making it (see [`coalesce`]).
pub(crate) enum Coalesce {
    /// Another one is in flight: its answer comes here.
    Wait(tokio::sync::oneshot::Receiver<Result<Vec<u8>, String>>),
    /// This one makes it; whatever happens to it (even if it is dropped), the waiters are
    /// released.
    Lead(Lead),
}

pub(crate) struct Lead {
    shared: Arc<Shared>,
    key: Key,
    result: Option<Result<Vec<u8>, String>>,
}

impl Lead {
    pub(crate) fn done(&mut self, r: &io::Result<Vec<u8>>) {
        self.result = Some(match r {
            Ok(v) => Ok(v.clone()),
            Err(e) => Err(e.to_string()),
        });
    }
}

impl Drop for Lead {
    fn drop(&mut self) {
        let waiters = self.shared.lookups.lock().remove(&self.key).unwrap_or_default();
        let r = self.result.take().unwrap_or_else(|| Err("lookup abandoned".into()));
        for w in waiters {
            let _ = w.send(r.clone());
        }
    }
}

/// One upstream query per name and type at a time, for the HTTPS filter's lookups and the
/// tunnel's queries alike: parallel lookups of the same name (a page opening several connections
/// to a new host, an app asking it at the same moment) wait for the first one.
pub(crate) fn coalesce(shared: &Arc<Shared>, key: &Key) -> Coalesce {
    let mut m = shared.lookups.lock();
    match m.get_mut(key) {
        Some(waiters) => {
            let (tx, rx) = tokio::sync::oneshot::channel();
            waiters.push(tx);
            Coalesce::Wait(rx)
        }
        None => {
            m.insert(key.clone(), Vec::new());
            Coalesce::Lead(Lead { shared: shared.clone(), key: key.clone(), result: None })
        }
    }
}

async fn shared_exchange(shared: &Arc<Shared>, key: &Key) -> io::Result<Vec<u8>> {
    match coalesce(shared, key) {
        Coalesce::Wait(rx) => match rx.await {
            // An app's query this joined got its server's SERVFAIL/REFUSED: a failure here too,
            // so an expired answer is used, as `exchange` does, instead of "no address" at once.
            Ok(Ok(r)) if matches!(dns::rcode_of(&r), dns::RCODE_SERVFAIL | dns::RCODE_REFUSED) => {
                Err(io::Error::other(format!("upstream answered rcode {}", dns::rcode_of(&r))))
            }
            Ok(Ok(r)) => Ok(r),
            Ok(Err(e)) => Err(io::Error::other(e)),
            Err(_) => Err(io::Error::other("lookup abandoned")),
        },
        Coalesce::Lead(mut lead) => {
            let r = exchange(shared, key).await;
            lead.done(&r);
            r
        }
    }
}

/// One upstream query; the answer goes into the shared cache.
async fn exchange(shared: &Arc<Shared>, key: &Key) -> io::Result<Vec<u8>> {
    let q = dns::build_query(upstream::rand_u16(), &key.name, key.qtype);
    let r = shared.upstream_exchange(&q).await?;
    // The server could not answer now: a failure, so an expired answer is used instead (passed
    // on, a SERVFAIL would read as "no address" though a recent answer is at hand).
    if matches!(dns::rcode_of(&r), dns::RCODE_SERVFAIL | dns::RCODE_REFUSED) {
        return Err(io::Error::other(format!("upstream answered rcode {}", dns::rcode_of(&r))));
    }
    if let Some(info) = dns::inspect(&r) {
        shared.note_answer(&key.name, &info);
        shared.cache.lock().insert(key.clone(), &r, &info);
    }
    Ok(r)
}

fn refresh_in_background(shared: &Arc<Shared>, key: Key) {
    if !shared.refreshing.lock().insert(key.clone()) {
        return;
    }
    // The name leaves the set whatever happens to the task: left there by a runtime stopping
    // mid-refresh, the name would never be refreshed in the background again.
    struct Refreshing(Arc<Shared>, Key);
    impl Drop for Refreshing {
        fn drop(&mut self) {
            self.0.refreshing.lock().remove(&self.1);
        }
    }
    let guard = Refreshing(shared.clone(), key);
    tokio::spawn(async move {
        if let Err(e) = shared_exchange(&guard.0, &guard.1).await {
            log::debug!("refresh of {} failed: {e}", guard.1.name);
        }
        drop(guard);
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Config;
    use tokio::net::UdpSocket;

    /// Stale answers are served immediately and refreshed exactly once in the background; a
    /// failing upstream falls back to the old answer.
    #[tokio::test]
    async fn stale_refresh_and_failure_fallback() {
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let addr = server.local_addr().unwrap();
        let hits = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let h = hits.clone();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                h.fetch_add(1, Ordering::SeqCst);
                let q = dns::parse_query(&buf[..n]).unwrap();
                // A record 1.2.3.4 with TTL 1.
                let mut r = dns::blocked_response(&buf[..n], &q, dns::BlockMode::NullIp, 1);
                let l = r.len();
                let at = if q.edns { l - 11 - 4 } else { l - 4 };
                r[at..at + 4].copy_from_slice(&[1, 2, 3, 4]);
                server.send_to(&r, from).await.unwrap();
            }
        });
        let shared = Shared::new(Config::default());
        shared.set_network(vec![addr], true, false, 1);
        let a = resolve(&shared, "example.com").await.unwrap();
        assert_eq!(a, vec!["1.2.3.4".parse::<IpAddr>().unwrap()]);
        assert_eq!(hits.load(Ordering::SeqCst), 1, "v4-only network: no AAAA query");
        // Asked again at once (the proxy's connect after its CONNECT-time check): from the
        // cache and young, so no "stale address" lookup follows.
        let again = resolve_with(&shared, "example.com", false).await.unwrap();
        assert!(again.cached && again.age_ms < 1000);
        assert_eq!(hits.load(Ordering::SeqCst), 1);

        tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
        for i in 0..3 {
            let r = resolve_with(&shared, "example.com", false).await.unwrap();
            // The first stale hit is old; the background refresh may already have replaced it
            // for the next ones.
            assert!(r.cached && (i > 0 || r.age_ms >= 1000));
        }
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
        assert_eq!(hits.load(Ordering::SeqCst), 2, "one background refresh for three stale hits");

        // Upstream gone: the stored answer is still used.
        shared.set_network(vec!["127.0.0.1:9".parse().unwrap()], true, false, 1);
        let r = resolve_with(&shared, "example.com", true).await.unwrap();
        assert!(r.cached && r.addrs == a);
        assert!(shared.health_json(10)["counts"]["dns_stale_served"].as_u64().unwrap() >= 1);
    }

    /// On an IPv6-only network without NAT64 (no IPv4 at all), the proxy's lookups ask AAAA
    /// alone, from the network's IPv6 resolver; address literals, of either family, are taken as
    /// they are with no lookup (CONNECT `[2001:db8::1]:443`, or an IPv4 literal that the
    /// platform's 464XLAT carries when the network has a NAT64 prefix).
    #[tokio::test]
    async fn ipv6_only_network_asks_aaaa_alone() {
        let server = UdpSocket::bind("[::1]:0").await.unwrap();
        let addr = server.local_addr().unwrap();
        let asked = Arc::new(parking_lot::Mutex::new(Vec::new()));
        let a2 = asked.clone();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let q = dns::parse_query(&buf[..n]).unwrap();
                a2.lock().push(q.qtype);
                // AAAA 64:ff9b::102:304 (a DNS64 address); an A query gets 0.0.0.0.
                let mut r = dns::blocked_response(&buf[..n], &q, dns::BlockMode::NullIp, 300);
                if q.qtype == dns::TYPE_AAAA {
                    let l = r.len();
                    let at = if q.edns { l - 11 - 16 } else { l - 16 };
                    r[at..at + 16].copy_from_slice(&[0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4]);
                }
                server.send_to(&r, from).await.unwrap();
            }
        });
        let shared = Shared::new(Config::default());
        shared.set_network(vec![addr], false, true, 1);
        assert_eq!(resolve(&shared, "v6only.test").await.unwrap(), vec!["64:ff9b::102:304".parse::<IpAddr>().unwrap()]);
        assert_eq!(*asked.lock(), [dns::TYPE_AAAA], "no A query without IPv4");
        for (literal, ip) in [("[2001:db8::1]", "2001:db8::1"), ("2001:db8::1", "2001:db8::1"), ("192.0.2.1", "192.0.2.1")] {
            let r = resolve_with(&shared, literal, false).await.unwrap();
            assert_eq!(r.addrs, vec![ip.parse::<IpAddr>().unwrap()], "{literal}");
        }
        assert_eq!(asked.lock().len(), 1, "literals are never looked up");
    }

    /// On a network with both families, an AAAA answer 2 s late does not hold the
    /// lookup once A has addresses; the late answer still reaches the cache.
    #[tokio::test]
    async fn a_late_family_does_not_hold_the_lookup() {
        let server = std::sync::Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let q = dns::parse_query(&buf[..n]).unwrap();
                let mut r = dns::blocked_response(&buf[..n], &q, dns::BlockMode::NullIp, 300);
                let late = q.qtype == dns::TYPE_AAAA;
                if !late {
                    let l = r.len();
                    let at = if q.edns { l - 11 - 4 } else { l - 4 };
                    r[at..at + 4].copy_from_slice(&[1, 2, 3, 4]);
                }
                let s = server.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(std::time::Duration::from_millis(if late { 2000 } else { 0 })).await;
                    s.send_to(&r, from).await.unwrap();
                });
            }
        });
        let shared = Shared::new(Config::default());
        shared.set_network(vec![addr], true, true, 1);
        let t0 = Instant::now();
        let r = resolve_with(&shared, "dual.test", false).await.unwrap();
        assert!(t0.elapsed() < std::time::Duration::from_millis(300), "{:?}", t0.elapsed());
        assert_eq!(r.addrs, vec!["1.2.3.4".parse::<IpAddr>().unwrap()]);
        tokio::time::sleep(std::time::Duration::from_millis(2300)).await;
        let k = Key { name: "dual.test".into(), qtype: dns::TYPE_AAAA, qclass: dns::CLASS_IN, do_bit: false };
        assert!(shared.cache.lock().ttl_of(&k).is_some(), "the late AAAA was cached");
    }

    /// The caller of [`resolve_family`] goes away (its connect won) while its lookup
    /// leads the upstream query; another lookup joined to it still gets the answer, and the
    /// cache holds it.
    #[tokio::test]
    async fn a_dropped_family_lookup_still_answers_its_waiters() {
        let server = std::sync::Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let q = dns::parse_query(&buf[..n]).unwrap();
                let r = dns::blocked_response(&buf[..n], &q, dns::BlockMode::NullIp, 300);
                let s = server.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(std::time::Duration::from_millis(300)).await;
                    s.send_to(&r, from).await.unwrap();
                });
            }
        });
        let shared = Shared::new(Config::default());
        shared.set_network(vec![addr], true, true, 1);
        let first = tokio::time::timeout(std::time::Duration::from_millis(100), resolve_family(&shared, "late6.test", true));
        let s2 = shared.clone();
        let waiter = async move {
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
            resolve_family(&s2, "late6.test", true).await
        };
        let (dropped, joined) = tokio::join!(first, waiter);
        assert!(dropped.is_err(), "the first caller gave up");
        assert!(joined.is_ok(), "{joined:?}");
        let k = Key { name: "late6.test".into(), qtype: dns::TYPE_AAAA, qclass: dns::CLASS_IN, do_bit: false };
        assert!(shared.cache.lock().ttl_of(&k).is_some(), "the answer was cached");
    }

    /// An answer that came, but after more than a second, leaves an event.
    #[tokio::test]
    async fn a_late_answer_is_recorded() {
        let server = std::sync::Arc::new(UdpSocket::bind("127.0.0.1:0").await.unwrap());
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let q = dns::parse_query(&buf[..n]).unwrap();
                let r = dns::blocked_response(&buf[..n], &q, dns::BlockMode::NullIp, 300);
                let s = server.clone();
                // Every query for this name is answered 1.15 s late (the resend at 0.7 s too).
                let late = q.name == "slow.test";
                tokio::spawn(async move {
                    tokio::time::sleep(std::time::Duration::from_millis(if late { 1150 } else { 0 })).await;
                    s.send_to(&r, from).await.unwrap();
                });
            }
        });
        let shared = Shared::new(Config::default());
        shared.set_network(vec![addr], true, false, 1);
        let _ = resolve_with(&shared, "fast.test", false).await;
        let _ = resolve_with(&shared, "slow.test", false).await;
        let h = shared.health_json(10);
        assert_eq!(h["counts"]["dns_slow"].as_u64(), Some(1), "{}", h["counts"]);
        assert!(h["events"].as_array().unwrap().iter().any(|e| e["host"] == "slow.test" && e["k"] == "dns_slow"), "{}", h["events"]);
    }

    /// The family nobody waited for (a resolver that never answers AAAA) is counted
    /// when it fails, not recorded as a 3.5 s DNS failure that pushes real events out.
    #[tokio::test]
    async fn an_unwaited_family_failure_is_only_counted() {
        let server = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let addr = server.local_addr().unwrap();
        tokio::spawn(async move {
            let mut buf = [0u8; 512];
            loop {
                let (n, from) = server.recv_from(&mut buf).await.unwrap();
                let q = dns::parse_query(&buf[..n]).unwrap();
                if q.qtype == dns::TYPE_AAAA {
                    continue;
                }
                let mut r = dns::blocked_response(&buf[..n], &q, dns::BlockMode::NullIp, 300);
                let l = r.len();
                let at = if q.edns { l - 11 - 4 } else { l - 4 };
                r[at..at + 4].copy_from_slice(&[1, 2, 3, 4]);
                server.send_to(&r, from).await.unwrap();
            }
        });
        let shared = Shared::new(Config::default());
        shared.set_network(vec![addr], true, true, 1);
        let t0 = Instant::now();
        assert_eq!(resolve(&shared, "noaaaa.test").await.unwrap(), vec!["1.2.3.4".parse::<IpAddr>().unwrap()]);
        assert!(t0.elapsed() < std::time::Duration::from_millis(300), "{:?}", t0.elapsed());
        tokio::time::sleep(std::time::Duration::from_millis(3900)).await;
        let h = shared.health_json(50);
        let events: Vec<String> = h["events"].as_array().unwrap().iter().map(|e| format!("{} {} {}", e["k"], e["host"], e["ms"])).collect();
        assert!(events.iter().all(|e| !e.contains("noaaaa.test")), "{events:?}");
        assert!(h["counts"]["dns_failed"].as_u64().unwrap_or(0) >= 1, "counted: {}", h["counts"]);
    }
}
