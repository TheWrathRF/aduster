//! Response cache with TTL aging and optimistic (serve-stale) answers.
//!
//! Serving a stale answer immediately and refreshing it in the background avoids waiting for a
//! cold radio on mobile data, which is both the slowest and the most power-hungry path.

use crate::dns;
use lru::LruCache;
use std::num::NonZeroUsize;
use std::sync::Arc;
use std::time::Duration;

/// How long an expired entry may still be served while a refresh is in flight.
const STALE_WINDOW: Duration = Duration::from_secs(3 * 3600);
/// TTL handed out with stale answers, so clients come back soon for the fresh one.
const STALE_TTL: u32 = 10;
/// When the upstream fails, an answer up to this old beats an error (RFC 8767 serve-stale).
pub const FAILURE_STALE_MAX: Duration = Duration::from_secs(24 * 3600);
const MAX_TTL: u32 = 86_400;
/// Answers bigger than this are not cached (4,096 entries × 4 KB at most).
const MAX_CACHED: usize = 4096;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Key {
    pub name: Box<str>,
    pub qtype: u16,
    pub qclass: u16,
    pub do_bit: bool,
}

struct Entry {
    resp: Vec<u8>,
    ttl_offsets: Vec<u16>,
    /// Boot-clock milliseconds (see `clock`): TTLs keep aging while the phone sleeps.
    stored: u64,
    ttl: u32,
    /// Negative, or points at a private/special address: only valid on the network it came from.
    local: bool,
    /// Names the answer went through (CNAME chain), checked for cloaking on every hit.
    cnames: Option<Arc<[Box<str>]>>,
    /// Learned on another network: served as expired (refreshed on use) whatever its age.
    expired: bool,
}

/// The CNAME chain of a cached answer (None: it had none).
pub type Cnames = Option<Arc<[Box<str>]>>;

/// Addresses that only mean something on one network (split DNS, routers, captive portals).
pub fn is_local(ip: &std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => {
            let o = v4.octets();
            v4.is_private() || v4.is_loopback() || v4.is_link_local() || v4.is_unspecified() || (o[0] == 100 && (o[1] & 0xC0) == 64)
        }
        std::net::IpAddr::V6(v6) => {
            if let Some(v4) = embedded_v4(v6) {
                return is_local(&std::net::IpAddr::V4(v4));
            }
            let s = v6.segments()[0];
            // Unique-local, link-local, and site-local (fec0::/10: deprecated, still handed out).
            v6.is_loopback() || v6.is_unspecified() || (s & 0xFE00) == 0xFC00 || (s & 0xFFC0) == 0xFE80 || (s & 0xFFC0) == 0xFEC0
        }
    }
}

/// The IPv4 address inside an IPv4-mapped (`::ffff:a.b.c.d`), NAT64 well-known prefix
/// (`64:ff9b::a.b.c.d`) or 6to4 (`2002:aabb:ccdd::`) IPv6 address: a private one stays private
/// written that way.
pub fn embedded_v4(v6: &std::net::Ipv6Addr) -> Option<std::net::Ipv4Addr> {
    let s = v6.segments();
    let o = v6.octets();
    if let Some(m) = v6.to_ipv4_mapped() {
        return Some(m);
    }
    if s[..6] == [0x64, 0xff9b, 0, 0, 0, 0] {
        return Some(std::net::Ipv4Addr::new(o[12], o[13], o[14], o[15]));
    }
    if s[0] == 0x2002 {
        return Some(std::net::Ipv4Addr::new(o[2], o[3], o[4], o[5]));
    }
    None
}

pub enum Lookup {
    Fresh(Vec<u8>, Cnames),
    Stale(Vec<u8>, Cnames),
    Miss,
}

pub struct Cache {
    lru: LruCache<Key, Entry>,
}

impl Cache {
    pub fn new(capacity: usize) -> Cache {
        Cache { lru: LruCache::new(NonZeroUsize::new(capacity.max(16)).unwrap()) }
    }

    pub fn resize(&mut self, capacity: usize) {
        self.lru.resize(NonZeroUsize::new(capacity.max(16)).unwrap());
    }

    pub fn clear(&mut self) {
        self.lru.clear();
    }

    pub fn len(&self) -> usize {
        self.lru.len()
    }

    pub fn is_empty(&self) -> bool {
        self.lru.is_empty()
    }

    /// Stores a successful upstream response. Returns false if it is not cacheable: an error, a
    /// truncated or TTL-0 answer, or one bigger than [`MAX_CACHED`] (the cache is
    /// bounded by entries, so each must stay small; such answers are rare and on no hot path).
    pub fn insert(&mut self, key: Key, resp: &[u8], info: &dns::ResponseInfo) -> bool {
        let cacheable = !info.truncated && matches!(info.rcode, dns::RCODE_NOERROR | dns::RCODE_NXDOMAIN) && resp.len() <= MAX_CACHED;
        let ttl = info.ttl.unwrap_or(if info.rcode == dns::RCODE_NXDOMAIN { 60 } else { 0 }).min(MAX_TTL);
        if !cacheable || ttl == 0 {
            return false;
        }
        let mut stored = resp.to_vec();
        dns::set_id(&mut stored, 0);
        // Negative and empty answers, and local addresses, hold on one network only. An answer
        // with records but no address (HTTPS/SVCB, TXT) is public, so it survives a network
        // change and can be served on an upstream failure.
        let local = info.rcode != dns::RCODE_NOERROR || info.answers == 0 || info.addrs.iter().any(is_local);
        let cnames = (!info.cnames.is_empty()).then(|| info.cnames.iter().map(|c| c.as_str().into()).collect());
        self.lru.put(key, Entry { resp: stored, ttl_offsets: info.ttl_offsets.clone(), stored: crate::clock::boot_ms(), ttl, local, cnames, expired: false });
        true
    }

    /// After a network change: drops answers that may differ on the new network and keeps the
    /// public ones, so switching Wi-Fi <-> mobile does not re-resolve everything over the radio.
    pub fn evict_local(&mut self) -> usize {
        let keys: Vec<Key> = self.lru.iter().filter(|(_, e)| e.local).map(|(k, _)| k.clone()).collect();
        for k in &keys {
            self.lru.pop(k);
        }
        keys.len()
    }

    /// Drops every answer for a name `owned` says yes to (after a network change with an
    /// encrypted resolver: the new network's own names, see `Shared::network_owns`).
    pub fn evict_names(&mut self, owned: impl Fn(&str) -> bool) -> usize {
        let keys: Vec<Key> = self.lru.iter().filter(|(k, _)| owned(&k.name)).map(|(k, _)| k.clone()).collect();
        for k in &keys {
            self.lru.pop(k);
        }
        keys.len()
    }

    /// After a network change: the public answers kept by [`Cache::evict_local`] were learned on
    /// the previous network (a campus name answered with its public address at home is reached
    /// through its private one on campus). They are marked expired: still answered at once, and
    /// refreshed in the background on their next use (one lookup per name actually used).
    pub fn mark_stale(&mut self) {
        for (_, e) in self.lru.iter_mut() {
            e.expired = true;
        }
    }

    /// The TTL an entry was stored with (not its remaining time): what its resolver meant.
    pub fn ttl_of(&self, key: &Key) -> Option<u32> {
        self.lru.peek(key).map(|e| e.ttl)
    }

    /// How long ago (boot-clock ms) the entry was stored; `now`: boot-clock milliseconds.
    pub fn age_ms_of(&self, key: &Key, now: u64) -> Option<u64> {
        self.lru.peek(key).map(|e| now.saturating_sub(e.stored))
    }

    /// `now`: boot-clock milliseconds.
    pub fn get(&mut self, key: &Key, id: u16, optimistic: bool, now: u64) -> Lookup {
        let Some(e) = self.lru.get(key) else { return Lookup::Miss };
        let age = Duration::from_millis(now.saturating_sub(e.stored));
        let age_s = age.as_secs() as u32;
        if age_s < e.ttl && !e.expired {
            return Lookup::Fresh(render(e, id, |t| t.saturating_sub(age_s).max(1)), e.cnames.clone());
        }
        if optimistic && age < Duration::from_secs(e.ttl as u64) + STALE_WINDOW {
            return Lookup::Stale(render(e, id, |_| STALE_TTL), e.cnames.clone());
        }
        // Expired entries stay (the LRU bounds them) as a fallback for upstream failures.
        Lookup::Miss
    }

    /// Any stored positive answer younger than `max_age`, for when the upstream fails.
    /// Negative and network-local answers are not served this way.
    pub fn get_stale_any(&mut self, key: &Key, id: u16, max_age: Duration, now: u64) -> Option<Vec<u8>> {
        let e = self.lru.peek(key)?;
        if e.local || Duration::from_millis(now.saturating_sub(e.stored)) > max_age {
            return None;
        }
        Some(render(e, id, |_| STALE_TTL))
    }
}

fn render(e: &Entry, id: u16, ttl: impl Fn(u32) -> u32) -> Vec<u8> {
    let mut out = e.resp.clone();
    dns::set_id(&mut out, id);
    for &o in &e.ttl_offsets {
        let o = o as usize;
        if o + 4 <= out.len() {
            let t = u32::from_be_bytes(out[o..o + 4].try_into().unwrap());
            out[o..o + 4].copy_from_slice(&ttl(t).to_be_bytes());
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dns::*;

    /// example.com A 93.184.216.34 (a public address, so it is not network-local).
    fn response(ttl: u32) -> Vec<u8> {
        let raw = build_query(7, "example.com", TYPE_A);
        let q = parse_query(&raw).unwrap();
        let mut r = blocked_response(&raw, &q, BlockMode::NullIp, ttl);
        let at = r.len() - 11 - 4;
        r[at..at + 4].copy_from_slice(&[93, 184, 216, 34]);
        r
    }

    fn key() -> Key {
        Key { name: "example.com".into(), qtype: TYPE_A, qclass: CLASS_IN, do_bit: false }
    }

    #[test]
    fn ages_ttl_and_serves_stale() {
        let mut c = Cache::new(16);
        let r = response(100);
        assert!(c.insert(key(), &r, &inspect(&r).unwrap()));
        let t0 = crate::clock::boot_ms();
        let at = |d: Duration| t0 + d.as_millis() as u64;

        let Lookup::Fresh(a, cn) = c.get(&key(), 99, true, at(Duration::from_secs(40))) else { panic!() };
        assert_eq!(id_of(&a), 99);
        assert_eq!(inspect(&a).unwrap().ttl, Some(60));
        assert!(cn.is_none());

        let Lookup::Stale(s, _) = c.get(&key(), 5, true, at(Duration::from_secs(200))) else { panic!() };
        assert_eq!(inspect(&s).unwrap().ttl, Some(STALE_TTL));

        assert!(matches!(c.get(&key(), 5, false, at(Duration::from_secs(200))), Lookup::Miss));
        // Still there as a failure fallback, until it is a day old.
        let s = c.get_stale_any(&key(), 9, FAILURE_STALE_MAX, at(Duration::from_secs(200))).unwrap();
        assert_eq!((id_of(&s), inspect(&s).unwrap().ttl), (9, Some(STALE_TTL)));
        assert!(c.get_stale_any(&key(), 9, FAILURE_STALE_MAX, at(FAILURE_STALE_MAX + Duration::from_secs(1))).is_none());
    }

    #[test]
    fn network_change_keeps_public_answers() {
        let mut c = Cache::new(16);
        let raw = build_query(7, "router.lan", TYPE_A);
        let q = parse_query(&raw).unwrap();
        let nx = blocked_response(&raw, &q, BlockMode::NxDomain, 100);
        let k2 = Key { name: "router.lan".into(), ..key() };
        assert!(c.insert(k2.clone(), &nx, &inspect(&nx).unwrap()));
        assert!(is_local(&"0.0.0.0".parse().unwrap()) && !is_local(&"93.184.216.34".parse().unwrap()));
        assert!(is_local(&"100.64.1.1".parse().unwrap()) && is_local(&"fd00::1".parse().unwrap()));
        assert!(!is_local(&"2606:4700::1".parse().unwrap()) && is_local(&"192.168.1.1".parse().unwrap()));
        // Site-local, and a private IPv4 address written as IPv6 (mapped, NAT64, 6to4).
        for local in ["fec0::1", "::ffff:192.168.1.1", "64:ff9b::10.0.0.1", "2002:c0a8:0101::1"] {
            assert!(is_local(&local.parse().unwrap()), "{local}");
        }
        for public in ["::ffff:93.184.216.34", "64:ff9b::5db8:d822", "2002:5db8:d822::1"] {
            assert!(!is_local(&public.parse().unwrap()), "{public}");
        }
        assert_eq!(c.evict_local(), 1);
        assert!(matches!(c.get(&k2, 1, true, crate::clock::boot_ms()), Lookup::Miss));
    }

    /// Public answers kept across a network change are served stale (refreshed on use), and
    /// keep the TTL their resolver gave.
    #[test]
    fn network_change_marks_public_answers_stale() {
        let mut c = Cache::new(16);
        let r = response(300);
        assert!(c.insert(key(), &r, &inspect(&r).unwrap()));
        let now = crate::clock::boot_ms();
        assert!(matches!(c.get(&key(), 1, true, now), Lookup::Fresh(..)));
        c.mark_stale();
        assert!(matches!(c.get(&key(), 1, true, now), Lookup::Stale(..)));
        assert!(matches!(c.get(&key(), 1, false, now), Lookup::Miss), "without optimistic answers: asked again");
        assert_eq!(c.ttl_of(&key()), Some(300));
    }

    /// Hits carry the CNAME chain, so cloaking is checked on cached answers too.
    #[test]
    fn keeps_cname_chain() {
        let mut c = Cache::new(16);
        let r = response(100);
        let mut info = inspect(&r).unwrap();
        info.cnames = vec!["shop.tracker-cdn.test".into()];
        assert!(c.insert(key(), &r, &info));
        let Lookup::Fresh(_, Some(cn)) = c.get(&key(), 1, true, crate::clock::boot_ms()) else { panic!() };
        assert_eq!(&*cn[0], "shop.tracker-cdn.test");
    }

    #[test]
    fn skips_uncacheable() {
        let mut c = Cache::new(16);
        let r = response(0);
        assert!(!c.insert(key(), &r, &inspect(&r).unwrap()));
        // A 10 KB answer is not kept.
        let mut big = response(100);
        let info = inspect(&big).unwrap();
        big.resize(10 * 1024, 0);
        assert!(!c.insert(key(), &big, &info));
    }

    /// An answer with records but no address (HTTPS/SVCB) is public: kept across a
    /// network change and served when the upstream fails; an empty (NODATA) one is not.
    #[test]
    fn records_without_addresses_are_public() {
        let mut c = Cache::new(16);
        let r = response(100);
        let mut info = inspect(&r).unwrap();
        info.addrs.clear();
        assert!(c.insert(key(), &r, &info));
        assert_eq!(c.evict_local(), 0);
        let mut empty = info;
        empty.answers = 0;
        let k2 = Key { name: "nodata.test".into(), ..key() };
        assert!(c.insert(k2, &r, &empty));
        assert_eq!(c.evict_local(), 1);
    }
}
