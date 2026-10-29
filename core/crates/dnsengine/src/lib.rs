//! DNS engine for the split tunnel: reads DNS queries from the TUN device, answers blocked names
//! locally, serves the cache, and forwards the rest upstream.

pub mod cache;
pub mod clock;
pub mod cpu;
pub mod dns;
// The loop core is used by the Android descriptor loop and by the host tests only.
#[cfg_attr(not(unix), allow(dead_code))]
mod engine;
pub mod health;
pub mod packet;
mod resolve;
pub mod runner;
pub mod stats;
pub mod tls;
pub mod upstream;

use arc_swap::ArcSwap;
use lru::LruCache;
use matcher::Filters;
use parking_lot::Mutex;
use serde::Deserialize;
use std::collections::HashSet;
use std::net::SocketAddr;
use std::num::NonZeroUsize;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

pub use dns::BlockMode;
pub use engine::run;
pub use resolve::{Resolved, resolve, resolve_family, resolve_with};
pub use stats::{Action, LogEntry};
pub use upstream::UpstreamConfig;

/// Settings pushed from the app as JSON. Every field has a default so old/new app versions
/// and partial updates never fail to parse.
#[derive(Debug, Clone, Deserialize)]
#[serde(default)]
pub struct Config {
    pub upstream: UpstreamConfig,
    pub block_mode: BlockMode,
    pub blocked_ttl: u32,
    /// In-memory query log size; 0 disables logging (and UID lookups).
    pub log_capacity: usize,
    /// Look up the app behind each query (costs one binder call per query).
    pub resolve_uids: bool,
    /// Block responses whose CNAME chain points at a blocked domain (CNAME cloaking).
    pub cname_check: bool,
    pub optimistic_cache: bool,
    pub cache_size: usize,
    /// Filtering is off until this unix-ms time (pause without any timer). For display and the
    /// log markers; the decision uses [`Config::pause_until_boot`].
    pub paused_until_ms: u64,
    /// What is left of the pause when the app sent this config (ms; `i64::MAX` = until Resume).
    /// Absent: worked out from `paused_until_ms`.
    pub pause_left_ms: Option<u64>,
    /// The pause end on the boot clock (0 = not paused), set when the config is applied: a
    /// wall-clock change (NTP, by hand) neither shortens nor lengthens a pause.
    #[serde(skip)]
    pub pause_until_boot: u64,
    pub tz_offset_s: i64,
    pub stats_path: Option<String>,
}

impl Config {
    /// Fills [`Config::pause_until_boot`] from what the app sent.
    fn with_pause_deadline(mut self) -> Config {
        let left = match self.pause_left_ms {
            Some(l) => l,
            None => match self.paused_until_ms {
                0 => 0,
                u64::MAX => u64::MAX,
                u => u.saturating_sub(now_ms()),
            },
        };
        self.pause_until_boot = match left {
            0 => 0,
            l if l >= i64::MAX as u64 => u64::MAX,
            l => clock::boot_ms().saturating_add(l),
        };
        self
    }
}

impl Default for Config {
    fn default() -> Self {
        Config {
            upstream: UpstreamConfig::System,
            block_mode: BlockMode::NullIp,
            blocked_ttl: 60,
            log_capacity: 2000,
            resolve_uids: true,
            cname_check: true,
            optimistic_cache: true,
            cache_size: 4096,
            paused_until_ms: 0,
            pause_left_ms: None,
            pause_until_boot: 0,
            tz_offset_s: 0,
            stats_path: None,
        }
    }
}

pub type UidLookup = Box<dyn Fn(u8, SocketAddr, SocketAddr) -> i32 + Send + Sync>;

#[derive(Default)]
pub struct Counters {
    pub packets: AtomicU64,
    pub dns_queries: AtomicU64,
    pub dropped: AtomicU64,
    pub rst_sent: AtomicU64,
    pub coalesced: AtomicU64,
    pub upstream_errors: AtomicU64,
    /// Answers too big for the client, sent as "truncated".
    pub truncated: AtomicU64,
    /// Queries answered SERVFAIL at once because too many names were in flight.
    pub overload: AtomicU64,
    /// Queries in flight during a move to another network, asked again there.
    pub dns_requeried: AtomicU64,
    /// The network's own names answered by its resolvers while an encrypted one is chosen.
    pub network_names: AtomicU64,
}

/// Callers waiting on one in-flight lookup: each gets the answer, or the error as text.
type Waiters = Vec<tokio::sync::oneshot::Sender<Result<Vec<u8>, String>>>;

/// State shared between the engine thread, the HTTPS filter and JNI callers.
pub struct Shared {
    pub filters: ArcSwap<Filters>,
    pub config: ArcSwap<Config>,
    pub system_servers: ArcSwap<Vec<SocketAddr>>,
    pub upstream: ArcSwap<upstream::Upstream>,
    upstream_cfg: Mutex<UpstreamConfig>,
    pub cache: Mutex<cache::Cache>,
    pub stats: Mutex<stats::Stats>,
    pub log: Mutex<stats::LogRing>,
    pub uid_lookup: OnceLock<UidLookup>,
    pub counters: Counters,
    /// Boot-clock ms of the start (uptime counts the time the phone slept).
    started: u64,
    pub efficiency_cpus: Mutex<Vec<usize>>,
    /// Boot-clock ms of the last stats write.
    last_persist: Mutex<u64>,
    /// Problems worth seeing after a stall (see `health`).
    pub health: Mutex<health::Ring>,
    /// Names being refreshed in the background for the proxy path (dedup).
    pub refreshing: Mutex<HashSet<cache::Key>>,
    /// Address families the underlying network can reach (a v4-only Wi-Fi never needs AAAA).
    pub has_v4: AtomicBool,
    pub has_v6: AtomicBool,
    /// Bumped whenever the underlying network itself changes (Wi-Fi <-> mobile, another Wi-Fi);
    /// connections from an older generation are not reused.
    pub net_generation: AtomicU64,
    /// Carries `net_generation`: long-lived connections (proxy upstreams, tunnels) watch it and
    /// close as soon as the network they were made on is gone.
    pub net_watch: tokio::sync::watch::Sender<u64>,
    /// Bumped when the rules change or filtering is paused or resumed: decisions taken at
    /// CONNECT (a DNS-blocked host answered by the proxy) are taken again.
    pub rules_watch: tokio::sync::watch::Sender<u64>,
    /// Handle of the current underlying network (0 = unknown).
    network: AtomicU64,
    /// The stretch without a network being recorded, if any (see [`Shared::offline_failure`]).
    episode: Mutex<Option<Episode>>,
    /// Set while `episode` is Some: lets the hot paths skip the lock.
    in_episode: AtomicBool,
    /// What the app calls the current network ("Wi-Fi", "Mobile data"), for the recorder, and the
    /// one the last network-change event named.
    net_label: Mutex<String>,
    net_announced: Mutex<String>,
    /// The network's own search domains (DHCP): names under them are local by definition.
    pub search_domains: ArcSwap<Vec<String>>,
    /// Names whose upstream answer had a public address, with the network generation and the
    /// boot-clock ms it came on (the rebinding guard: a name that turns local shortly after on
    /// the same network is an attack, not split DNS).
    public_seen: Mutex<LruCache<Box<str>, (u64, u64)>>,
    /// In-flight lookups of the proxy path, so parallel connections to one new name share a
    /// single upstream query.
    pub(crate) lookups: Mutex<std::collections::HashMap<cache::Key, Waiters>>,
    /// The minute each (host, action) of the proxy was last logged (see [`Shared::record`]).
    log_seen: Mutex<LruCache<(Box<str>, u8), u64>>,
    /// The current pause's "Resumed" marker is in the log (put there by the first record after
    /// a timed pause ran out, or by the config that ended it).
    resume_logged: AtomicBool,
}

/// One stretch without a usable network, recorded as a single event.
struct Episode {
    /// Unix ms of its start: identifies its event in the recorder.
    t: u64,
    /// Boot-clock ms of its start.
    boot: u64,
    connects: u64,
    lookups: u64,
}

const PERSIST_EVERY_SECS: u64 = 15 * 60;
/// How long a public answer for a name counts for the rebinding guard.
const PUBLIC_SEEN_MS: u64 = 10 * 60 * 1000;

pub fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

impl Shared {
    pub fn new(config: Config) -> Arc<Shared> {
        let config = config.with_pause_deadline();
        let mut stats = config.stats_path.as_deref().map(load_stats).unwrap_or_default();
        stats.roll_day(local_day(config.tz_offset_s));
        let upstream = upstream::Upstream::from_config(&config.upstream).unwrap_or(upstream::Upstream::System);
        Arc::new(Shared {
            filters: ArcSwap::from_pointee(Filters::empty()),
            system_servers: ArcSwap::from_pointee(Vec::new()),
            upstream: ArcSwap::from_pointee(upstream),
            upstream_cfg: Mutex::new(config.upstream.clone()),
            cache: Mutex::new(cache::Cache::new(config.cache_size)),
            stats: Mutex::new(stats),
            log: Mutex::new(stats::LogRing::new(config.log_capacity)),
            uid_lookup: OnceLock::new(),
            counters: Counters::default(),
            started: clock::boot_ms(),
            efficiency_cpus: Mutex::new(Vec::new()),
            last_persist: Mutex::new(clock::boot_ms()),
            health: Mutex::new(health::Ring::default()),
            refreshing: Mutex::new(HashSet::new()),
            has_v4: AtomicBool::new(true),
            has_v6: AtomicBool::new(true),
            net_generation: AtomicU64::new(0),
            net_watch: tokio::sync::watch::Sender::new(0),
            rules_watch: tokio::sync::watch::Sender::new(0),
            network: AtomicU64::new(0),
            episode: Mutex::new(None),
            in_episode: AtomicBool::new(false),
            net_label: Mutex::new(String::new()),
            net_announced: Mutex::new(String::new()),
            search_domains: ArcSwap::from_pointee(Vec::new()),
            public_seen: Mutex::new(LruCache::new(NonZeroUsize::new(512).unwrap())),
            lookups: Mutex::new(std::collections::HashMap::new()),
            log_seen: Mutex::new(LruCache::new(NonZeroUsize::new(512).unwrap())),
            resume_logged: AtomicBool::new(false),
            config: ArcSwap::from_pointee(config),
        })
    }

    pub fn set_config(&self, config: Config) {
        let config = config.with_pause_deadline();
        {
            let mut cur = self.upstream_cfg.lock();
            if *cur != config.upstream {
                match upstream::Upstream::from_config(&config.upstream) {
                    Ok(u) => {
                        self.upstream.store(Arc::new(u));
                        *cur = config.upstream.clone();
                        // Answers from the previous resolver may differ (e.g. ISP-level blocking).
                        self.cache.lock().clear();
                    }
                    Err(e) => log::warn!("invalid upstream config: {e}"),
                }
            }
        }
        self.cache.lock().resize(config.cache_size);
        self.log.lock().set_capacity(config.log_capacity);
        self.mark_pause(self.config.load().paused_until_ms, config.paused_until_ms);
        self.config.store(Arc::new(config));
    }

    /// Puts pause and resume markers into the query log (a pause is then seen as a stretch there,
    /// not guessed from a gap). `old`/`new`: pause ends in unix ms (0 = not paused).
    fn mark_pause(&self, old: u64, new: u64) {
        if old == new {
            return;
        }
        self.rules_watch.send_modify(|g| *g += 1);
        let now = now_ms();
        // Unless a record already marked it (`resume_if_ran_out`).
        if old != 0 && !self.resume_logged.swap(true, Ordering::Relaxed) {
            // A timed pause that already ran out ended at its own time.
            if old <= now {
                self.log_marker(Action::Resumed, old);
            } else if new == 0 || new <= now {
                self.log_marker(Action::Resumed, now);
            }
        }
        if new != 0 {
            self.resume_logged.store(false, Ordering::Relaxed);
        }
        if new > now && (old == 0 || old <= now) {
            self.log_marker(Action::Paused, now);
        }
    }

    /// A timed pause that ran out is marked "Resumed" (at its end) before the first record after
    /// it, so the log does not show lookups made after the pause above its end.
    fn resume_if_ran_out(&self, cfg: &Config) {
        let end = cfg.pause_until_boot;
        if end == 0 || end == u64::MAX || self.resume_logged.load(Ordering::Relaxed) {
            return;
        }
        let boot = clock::boot_ms();
        if boot >= end && !self.resume_logged.swap(true, Ordering::Relaxed) {
            self.log_marker(Action::Resumed, now_ms().saturating_sub(boot - end));
        }
    }

    fn log_marker(&self, a: Action, t: u64) {
        self.log.lock().push(LogEntry { s: 0, t, n: "".into(), q: 0, u: -1, a, r: None, l: None, us: 0, o: false, p: None, x: None });
    }

    /// What the app knows about the next network it reports: a name for the recorder and the
    /// search domains it hands out (names under them are local, see the proxy's rebinding guard).
    /// Single-label domains are left out: they add nothing to the built-in local names and
    /// would cover a whole top-level domain.
    pub fn set_network_info(&self, label: &str, domains: Vec<String>) {
        *self.net_label.lock() = label.to_string();
        let domains: Vec<String> = domains.into_iter().map(|d| d.trim().trim_matches('.').to_ascii_lowercase()).filter(|d| d.contains('.')).collect();
        self.search_domains.store(Arc::new(domains));
    }

    /// A stable key for the current network: its kind ("Wi-Fi", "Mobile data"), its search
    /// domains and its set of resolvers. What is learned about one network (which hosts a
    /// censorship box on it cuts) is kept apart from the others, also across restarts (so not the
    /// per-boot network handle).
    pub fn network_key(&self) -> u64 {
        let mut servers: Vec<std::net::IpAddr> = self.system_servers.load().iter().map(|s| s.ip()).collect();
        servers.sort();
        let mut d = ring::digest::Context::new(&ring::digest::SHA256);
        d.update(self.net_label.lock().as_bytes());
        for domain in self.search_domains.load().iter() {
            d.update(b"|");
            d.update(domain.as_bytes());
        }
        for s in servers {
            d.update(b"|");
            d.update(s.to_string().as_bytes());
        }
        u64::from_le_bytes(d.finish().as_ref()[..8].try_into().unwrap())
    }

    /// True if `host` is one of the network's search domains or under one.
    pub fn in_search_domain(&self, host: &str) -> bool {
        self.search_domains
            .load()
            .iter()
            .any(|d| host == d || (host.len() > d.len() && host.ends_with(d.as_str()) && host.as_bytes()[host.len() - d.len() - 1] == b'.'))
    }

    /// Notes that `name` got an answer with a public address on the current network.
    pub(crate) fn note_answer(&self, name: &str, info: &dns::ResponseInfo) {
        if info.addrs.iter().any(|ip| !cache::is_local(ip) && !ip.is_unspecified() && !ip.is_multicast()) {
            let g = self.net_generation.load(Ordering::Acquire);
            self.public_seen.lock().put(name.into(), (g, clock::boot_ms()));
        }
    }

    /// True if `name` resolved to a public address on this network within the last 10 minutes.
    pub fn saw_public_recently(&self, name: &str) -> bool {
        let g = self.net_generation.load(Ordering::Acquire);
        self.public_seen.lock().peek(name).is_some_and(|&(seen_on, t)| seen_on == g && clock::since_ms(t) < PUBLIC_SEEN_MS)
    }

    /// The underlying network (`handle`, 0 if none) or its resolvers or address families changed.
    /// Returns true if anything changed.
    pub fn set_network(&self, servers: Vec<SocketAddr>, has_v4: bool, has_v6: bool, handle: u64) -> bool {
        // The resolver set, in any order: a reordering, or an address family showing up a few
        // seconds after the network, must not expire the whole cache and log a change.
        let set = |v: &[SocketAddr]| {
            let mut s = v.to_vec();
            s.sort();
            s.dedup();
            s
        };
        let servers_changed = set(&self.system_servers.load()) != set(&servers);
        let families_changed = self.has_v4.load(Ordering::Relaxed) != has_v4 || self.has_v6.load(Ordering::Relaxed) != has_v6;
        self.system_servers.store(Arc::new(servers));
        // An unknown network (no addresses reported yet) must not stop either family.
        let (v4, v6) = if has_v4 || has_v6 { (has_v4, has_v6) } else { (true, true) };
        self.has_v4.store(v4, Ordering::Relaxed);
        self.has_v6.store(v6, Ordering::Relaxed);
        // Upstream DNS sockets go out on this network.
        upstream::BIND_HANDLE.store(handle, Ordering::Relaxed);
        // Only a different network makes existing connections useless; a resolver or address
        // update on the same network (DHCP renewal, an IPv6 prefix) leaves them alone.
        let new_net = self.network.swap(handle, Ordering::AcqRel) != handle;
        if new_net {
            let g = self.net_generation.fetch_add(1, Ordering::AcqRel) + 1;
            self.net_watch.send_replace(g);
        }
        let changed = servers_changed || families_changed;
        // A different network counts even when its resolver has the old one's address (two home
        // routers both at 192.168.1.1).
        if servers_changed || (new_net && handle != 0) {
            // New network: answers that only hold on the old one (private addresses, split DNS,
            // captive portals, negative answers) go; public ones are kept but refreshed on use.
            // An encrypted resolver's public answers do not depend on the network, but the
            // network's own names it did not answer do, and so does a public answer it gave for a
            // name the new network calls its own (a campus name answered at home must not be
            // served from the cache on campus without asking the network).
            let mut c = self.cache.lock();
            c.evict_local();
            if matches!(**self.upstream.load(), upstream::Upstream::System) {
                c.mark_stale();
            } else {
                c.evict_names(|n| self.network_owns(n));
            }
            drop(c);
            // Encrypted resolver connections were bound to the old network.
            self.upstream.load().reset_connections();
        }
        if self.offline() {
            // Lost: one event for the whole stretch, whatever fails meanwhile.
            if handle == 0 && (changed || new_net) {
                self.begin_episode();
            }
        } else {
            self.end_episode();
            if servers_changed || new_net {
                let desc = self.system_servers.load().iter().map(|s| s.ip().to_string()).collect::<Vec<_>>().join(",");
                let label = self.net_label.lock().clone();
                let from = std::mem::replace(&mut *self.net_announced.lock(), label.clone());
                let name = if label.is_empty() { "network".to_string() } else { label };
                let what = if from.is_empty() || from == name { name } else { format!("{from} → {name}") };
                self.event(health::Kind::NetworkChanged, "", 0, &format!("{what} · dns={desc} v4={v4} v6={v6}"));
            }
        }
        changed
    }

    /// Asks the upstream resolver `q`. With the network's own resolvers, a query in flight when
    /// the phone moves to another network is asked again there at once: the old network's
    /// resolver will not answer any more, and the query would wait its whole 3.5 s budget.
    /// Asked again once at most; encrypted resolvers reconnect by themselves.
    ///
    /// With an encrypted resolver, the names the network calls its own (see
    /// [`Shared::network_owns`]) still go to the network's resolvers first (the encrypted
    /// resolver gives a campus name its public address, unreachable from inside); the
    /// encrypted resolver answers them only when the network's resolvers fail.
    pub async fn upstream_exchange(&self, q: &[u8]) -> std::io::Result<Vec<u8>> {
        // An answer that came, but after more than a second, is recorded: the resolver's p99 is
        // tens of ms, so such a wait is worth a trace.
        let t0 = std::time::Instant::now();
        let r = self.upstream_exchange_inner(q).await;
        let ms = t0.elapsed().as_millis();
        if r.is_ok()
            && ms > 1000
            && let Some(p) = dns::parse_query(q)
        {
            self.event(health::Kind::DnsSlow, &p.name, ms.min(u32::MAX as u128) as u32, "answer came late");
        }
        r
    }

    async fn upstream_exchange_inner(&self, q: &[u8]) -> std::io::Result<Vec<u8>> {
        let up = self.upstream.load_full();
        if matches!(*up, upstream::Upstream::System) {
            return self.system_exchange(q).await;
        }
        if !self.system_servers.load().is_empty() && dns::parse_query(q).is_some_and(|p| self.network_owns(&p.name)) {
            match self.system_exchange(q).await {
                Ok(r) if !matches!(dns::rcode_of(&r), dns::RCODE_SERVFAIL | dns::RCODE_REFUSED) => {
                    self.counters.network_names.fetch_add(1, Ordering::Relaxed);
                    return Ok(r);
                }
                r => log::debug!(
                    "network resolver failed for a local name ({}): asking the encrypted one",
                    r.err().map_or("server failure".into(), |e| e.to_string())
                ),
            }
        }
        up.exchange(&self.system_servers.load_full(), q).await
    }

    /// `q` to the network's own resolvers, asked again on the new network if the phone moves
    /// meanwhile.
    async fn system_exchange(&self, q: &[u8]) -> std::io::Result<Vec<u8>> {
        let mut moved = self.net_watch.subscribe();
        let servers = self.system_servers.load_full();
        tokio::select! {
            r = upstream::system_exchange(&servers, q) => r,
            Ok(()) = moved.changed() => {
                self.counters.dns_requeried.fetch_add(1, Ordering::Relaxed);
                upstream::system_exchange(&self.system_servers.load_full(), q).await
            }
        }
    }

    /// Names only the network's own resolvers know: under its search domains, and the reverse
    /// zones of private address ranges.
    pub fn network_owns(&self, name: &str) -> bool {
        self.in_search_domain(name) || private_reverse_zone(name)
    }

    pub fn event(&self, k: health::Kind, host: &str, ms: u32, detail: &str) {
        // The kind only: release builds keep warnings, and the host and detail name visited sites
        // and pages, which logcat must not hold (the recorder keeps them for Diagnostics).
        log::warn!("{k:?} {ms}ms");
        self.health.lock().push(k, host, ms, detail);
    }

    /// No usable underlying network: none at all (lost, or not reported yet), or, with the
    /// network's own resolvers as upstream, one that names no resolver.
    pub fn offline(&self) -> bool {
        self.network.load(Ordering::Relaxed) == 0 || (self.system_servers.load().is_empty() && matches!(**self.upstream.load(), upstream::Upstream::System))
    }

    /// Starts recording a stretch without a network (once).
    fn begin_episode(&self) {
        let mut e = self.episode.lock();
        if e.is_none() {
            log::warn!("no network: failures are counted until it is back");
            let t = self.health.lock().push(health::Kind::NetworkLost, "", 0, "no network");
            *e = Some(Episode { t, boot: clock::boot_ms(), connects: 0, lookups: 0 });
            self.in_episode.store(true, Ordering::Release);
        }
    }

    /// Ends the stretch without a network: its event gets the length and what failed in it.
    fn end_episode(&self) {
        if !self.in_episode.load(Ordering::Acquire) {
            return;
        }
        let Some(ep) = self.episode.lock().take() else { return };
        self.in_episode.store(false, Ordering::Release);
        let ms = clock::since_ms(ep.boot).min(u32::MAX as u64) as u32;
        let n = |v: u64, what: &str| format!("{v} {what}{}", if v == 1 { "" } else { "s" });
        let detail = format!("no network: {} and {} failed", n(ep.connects, "connection"), n(ep.lookups, "DNS lookup"));
        self.health.lock().update(health::Kind::NetworkLost, ep.t, ms, &detail);
    }

    /// A connection or lookup that failed because there is no network (none at all, or the
    /// system says it is unreachable). Without a network every attempt fails the same way, often
    /// thousands of times, and each would flush the recorder: they are counted, and the stretch
    /// is recorded once.
    pub fn offline_failure(&self, k: health::Kind) {
        self.begin_episode();
        self.health.lock().count(k);
        if let Some(e) = self.episode.lock().as_mut() {
            if matches!(k, health::Kind::DnsFailed | health::Kind::DnsStaleServed) {
                e.lookups += 1;
            } else {
                e.connects += 1;
            }
        }
    }

    /// A connection just worked: a stretch without a network that began with an unreachable
    /// network (before the system reported it gone) is over.
    pub fn network_ok(&self) {
        if self.in_episode.load(Ordering::Relaxed) && !self.offline() {
            self.end_episode();
        }
    }

    /// A DNS failure for a query someone waits for (see [`Shared::offline_failure`]).
    pub fn dns_failure(&self, k: health::Kind, host: &str, ms: u32, detail: &str) {
        if self.offline() {
            self.offline_failure(k);
            return;
        }
        self.event(k, host, ms, detail);
    }

    /// Blocked count for today, for the widget (no JSON).
    pub fn blocked_today(&self) -> u64 {
        let cfg = self.config.load();
        let mut s = self.stats.lock();
        s.roll_day(local_day(cfg.tz_offset_s));
        s.blocked
    }

    pub fn set_filters(&self, filters: Filters) {
        self.filters.store(Arc::new(filters));
        // Cached answers were produced under the old rules only for allowed names, so they stay
        // valid for names still allowed; newly blocked names are checked before the cache anyway.
        self.rules_watch.send_modify(|g| *g += 1);
    }

    /// What the app calls the current network ("Wi-Fi", "Mobile data").
    pub fn network_label(&self) -> String {
        self.net_label.lock().clone()
    }

    /// Not paused, or the pause ran out (boot clock: sleep counts, wall-clock changes do not).
    pub fn filtering_active(&self, cfg: &Config) -> bool {
        cfg.pause_until_boot == 0 || clock::boot_ms() >= cfg.pause_until_boot
    }

    pub fn lookup_uid(&self, proto: u8, src: SocketAddr, dst: SocketAddr) -> i32 {
        match self.uid_lookup.get() {
            Some(f) => f(proto, src, dst),
            None => -1,
        }
    }

    /// Records one answered query in stats and the log, and persists stats when due.
    #[allow(clippy::too_many_arguments)]
    pub fn record(&self, name: &str, qtype: u16, uid: i32, action: Action, rule: Option<(String, String)>, latency_us: u32) {
        self.record_with(name, qtype, uid, action, rule, latency_us, None, None, false);
    }

    /// [`Shared::record`] of an answer that waited for an upstream exchange (see
    /// [`stats::Stats::add_from`]).
    pub(crate) fn record_waited(&self, name: &str, qtype: u16, uid: i32, action: Action, rule: Option<(String, String)>, latency_us: u32) {
        self.record_with(name, qtype, uid, action, rule, latency_us, None, None, true);
    }

    /// A request blocked inside a Chrome page: to `host`, from a page of `page`, at `path`.
    pub fn record_request(&self, host: &str, uid: i32, rule: (String, String), page: &str, path: &str) {
        self.record_with(host, 0, uid, Action::Filtered, Some(rule), 0, Some(page), Some(path), false);
    }

    #[allow(clippy::too_many_arguments)]
    fn record_with(
        &self,
        name: &str,
        qtype: u16,
        uid: i32,
        action: Action,
        rule: Option<(String, String)>,
        latency_us: u32,
        page: Option<&str>,
        path: Option<&str>,
        waited: bool,
    ) {
        let cfg = self.config.load();
        self.resume_if_ran_out(&cfg);
        // The proxy's records (Chrome's connections and in-page blocks) carry no DNS type.
        let proxy_record = qtype == 0;
        let persist = {
            let mut s = self.stats.lock();
            s.roll_day(local_day(cfg.tz_offset_s));
            s.add_from(name, uid, action, (!proxy_record).then_some(latency_us), waited);
            self.persist_due()
        };
        // The proxy's records are logged once per host, kind and minute (otherwise Chrome alone
        // pushes every other app's lines out of the log within minutes); the counts above stay
        // exact.
        if proxy_record {
            let minute = now_ms() / 60_000;
            let mut seen = self.log_seen.lock();
            let k = (Box::<str>::from(name), action as u8);
            if seen.get(&k) == Some(&minute) {
                drop(seen);
                if persist {
                    self.persist_stats_soon();
                }
                return;
            }
            seen.put(k, minute);
        }
        {
            let mut log = self.log.lock();
            if log.enabled() {
                let (r, l) = match rule {
                    Some((r, l)) => (Some(r.into_boxed_str()), Some(l.into_boxed_str())),
                    None => (None, None),
                };
                // Failures while there is no network: the log shows them as one stretch.
                let o = action == Action::Failed && self.offline();
                log.push(LogEntry {
                    s: 0,
                    t: now_ms(),
                    n: name.into(),
                    q: qtype,
                    u: uid,
                    a: action,
                    r,
                    l,
                    us: latency_us,
                    o,
                    p: page.map(Into::into),
                    x: path.map(Into::into),
                });
            }
        }
        if persist {
            self.persist_stats_soon();
        }
    }

    /// Writes stats without holding up the caller: inside a runtime (the proxy's workers, the
    /// DNS thread) the file write goes to its blocking pool.
    fn persist_stats_soon(&self) {
        let Some((path, bytes, seq)) = self.stats_snapshot() else { return };
        match tokio::runtime::Handle::try_current() {
            Ok(h) => {
                h.spawn_blocking(move || write_atomic(&path, &bytes, seq));
            }
            Err(_) => write_atomic(&path, &bytes, seq),
        }
    }

    fn persist_due(&self) -> bool {
        let mut last = self.last_persist.lock();
        let now = clock::boot_ms();
        if now.saturating_sub(*last) < PERSIST_EVERY_SECS * 1000 {
            return false;
        }
        *last = now;
        true
    }

    /// Writes stats atomically (temp file + rename).
    pub fn persist_stats(&self) {
        if let Some((path, bytes, seq)) = self.stats_snapshot() {
            write_atomic(&path, &bytes, seq);
        }
    }

    /// The screen went off (an event, no timer): unsaved stats are written, and encrypted
    /// resolver connections idle a moment are closed before the phone sleeps.
    pub fn screen_off(&self) {
        self.persist_stats();
        self.upstream.load().close_idle();
    }

    /// The stats file's path and contents, if there is anything new to write, with the
    /// snapshot's number (see [`write_atomic`]).
    fn stats_snapshot(&self) -> Option<(String, Vec<u8>, u64)> {
        static SEQ: AtomicU64 = AtomicU64::new(1);
        let cfg = self.config.load();
        let path = cfg.stats_path.clone()?;
        let mut s = self.stats.lock();
        if !s.dirty {
            return None;
        }
        s.dirty = false;
        let seq = SEQ.fetch_add(1, Ordering::Relaxed);
        serde_json::to_vec(&*s).ok().map(|b| (path, b, seq))
    }

    pub fn stats_json(&self) -> String {
        let cfg = self.config.load();
        let mut s = self.stats.lock();
        s.roll_day(local_day(cfg.tz_offset_s));
        s.report().to_string()
    }

    pub fn log_json(&self, since: u64, max: usize) -> String {
        let log = self.log.lock();
        serde_json::to_string(&log.since(since, max)).unwrap_or_else(|_| "[]".into())
    }

    /// The oldest `max` entries after `since` (forward paging).
    pub fn log_after_json(&self, since: u64, max: usize) -> String {
        let log = self.log.lock();
        serde_json::to_string(&log.after(since, max)).unwrap_or_else(|_| "[]".into())
    }

    /// Recorded problems, newest first.
    pub fn health_json(&self, max: usize) -> serde_json::Value {
        let h = self.health.lock();
        serde_json::json!({"counts": h.counts_json(), "events": h.recent(max)})
    }

    pub fn clear_log(&self) {
        self.log.lock().clear();
    }

    pub fn reset_stats(&self) {
        let cfg = self.config.load();
        {
            let mut s = self.stats.lock();
            *s = stats::Stats::default();
            s.roll_day(local_day(cfg.tz_offset_s));
            s.dirty = true;
        }
        self.persist_stats();
    }

    /// [`Shared::diagnostics_json`] for an exported report, which people post or send: the
    /// network's search domains (a school's or company's own domain) only counted.
    pub fn report_json(&self) -> serde_json::Value {
        let mut v: serde_json::Value = serde_json::from_str(&self.diagnostics_json()).unwrap_or_default();
        v["search_domains"] = self.search_domains.load().len().into();
        v
    }

    pub fn diagnostics_json(&self) -> String {
        let c = &self.counters;
        let f = self.filters.load();
        let (up50, up99) = {
            let s = self.stats.lock();
            (s.upstream_latency.percentile(0.5), s.upstream_latency.percentile(0.99))
        };
        serde_json::json!({
            "uptime_s": clock::since_ms(self.started) / 1000,
            "network": *self.net_label.lock(),
            "search_domains": **self.search_domains.load(),
            "packets": c.packets.load(Ordering::Relaxed),
            "dns_queries": c.dns_queries.load(Ordering::Relaxed),
            "dropped": c.dropped.load(Ordering::Relaxed),
            "rst_sent": c.rst_sent.load(Ordering::Relaxed),
            "coalesced": c.coalesced.load(Ordering::Relaxed),
            "upstream_errors": c.upstream_errors.load(Ordering::Relaxed),
            "truncated": c.truncated.load(Ordering::Relaxed),
            "overload": c.overload.load(Ordering::Relaxed),
            "dns_requeried": c.dns_requeried.load(Ordering::Relaxed),
            "dns_resent": upstream::UDP_RESENT.load(Ordering::Relaxed),
            "network_names": c.network_names.load(Ordering::Relaxed),
            // Which resolver answers and how fast.
            "upstream": self.upstream.load().describe(),
            "upstream_latency": {"p50_us": up50, "p99_us": up99},
            "cache_entries": self.cache.lock().len(),
            "list_rules": f.lists.len(),
            "user_rules": f.user.len(),
            "system_servers": self.system_servers.load().iter().map(|s| s.to_string()).collect::<Vec<_>>(),
            "efficiency_cpus": *self.efficiency_cpus.lock(),
            "has_v4": self.has_v4.load(Ordering::Relaxed),
            "has_v6": self.has_v6.load(Ordering::Relaxed),
            "problems": self.health.lock().counts_json(),
        })
        .to_string()
    }
}

/// A name in the reverse zone of a private, link-local or carrier-grade NAT range
/// (`5.1.168.192.in-addr.arpa`, `….d.f.ip6.arpa`): only the network's resolvers can answer it.
fn private_reverse_zone(name: &str) -> bool {
    if let Some(rest) = name.strip_suffix(".in-addr.arpa") {
        let o: Vec<u8> = rest.rsplit('.').map_while(|l| l.parse().ok()).collect();
        return match o.as_slice() {
            [10, ..] | [192, 168, ..] | [169, 254, ..] => true,
            [172, b, ..] => (16..=31).contains(b),
            [100, b, ..] => (64..=127).contains(b),
            _ => false,
        };
    }
    if let Some(rest) = name.strip_suffix(".ip6.arpa") {
        let n: Vec<&str> = rest.rsplit('.').collect();
        return matches!(n.as_slice(), ["f", "c" | "d", ..] | ["f", "e", "8" | "9" | "a" | "b", ..]);
    }
    false
}

/// The stats in `path`. A file that does not parse is kept as `.bad` rather than silently
/// overwritten later, which would lose 30 days of history with no trace.
fn load_stats(path: &str) -> stats::Stats {
    let Ok(b) = std::fs::read(path) else { return stats::Stats::default() };
    match serde_json::from_slice::<stats::Stats>(&b) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("stats file unreadable ({e}): kept as .bad, starting over");
            let _ = std::fs::rename(path, format!("{path}.bad"));
            stats::Stats::default()
        }
    }
}

/// Temp file + rename: a reader never sees half a file. One writer at a time, each with its own
/// temp name (with a shared one, the DNS thread and a proxy worker could interleave in it and
/// leave a corrupt file).
/// `seq`: the snapshot's number; an older snapshot arriving late is not written over a newer one.
fn write_atomic(path: &str, bytes: &[u8], seq: u64) {
    static WRITING: Mutex<u64> = Mutex::new(0);
    static N: AtomicU64 = AtomicU64::new(0);
    let mut last = WRITING.lock();
    if seq != 0 && seq <= *last {
        return;
    }
    *last = seq;
    let tmp = format!("{path}.{}.tmp", N.fetch_add(1, Ordering::Relaxed));
    if std::fs::write(&tmp, bytes).is_ok() && std::fs::rename(&tmp, path).is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
}

/// Hands freed heap pages back to the kernel. Swapping in a new filter table or page engine
/// frees tens of MB at once, which the allocator would otherwise keep resident in the
/// always-on process.
pub fn release_free_memory() {
    #[cfg(target_os = "android")]
    {
        unsafe extern "C" {
            // bionic <malloc.h>; not exposed by the libc crate for Android.
            fn mallopt(param: libc::c_int, value: libc::c_int) -> libc::c_int;
        }
        // M_PURGE_ALL (Android 12+), falling back to M_PURGE.
        unsafe {
            if mallopt(-104, 0) == 0 {
                mallopt(-101, 0);
            }
        }
    }
}

pub fn local_day(tz_offset_s: i64) -> i64 {
    ((now_ms() / 1000) as i64 + tz_offset_s).div_euclid(86_400)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Release builds keep warnings: the recorder's log line names the kind, never the host or
    /// the detail (visited sites and pages).
    #[test]
    fn event_log_line_names_no_site() {
        struct Capture(std::sync::Mutex<Vec<String>>);
        impl log::Log for Capture {
            fn enabled(&self, _: &log::Metadata) -> bool {
                true
            }
            fn log(&self, r: &log::Record) {
                self.0.lock().unwrap().push(r.args().to_string());
            }
            fn flush(&self) {}
        }
        static CAPTURE: Capture = Capture(std::sync::Mutex::new(Vec::new()));
        log::set_logger(&CAPTURE).unwrap();
        log::set_max_level(log::LevelFilter::Warn);
        Shared::new(Config::default()).event(health::Kind::Passthrough, "visited.example", 3, "page /private-path");
        let lines = CAPTURE.0.lock().unwrap();
        assert!(lines.iter().any(|l| l.starts_with("Passthrough")), "{lines:?}");
        assert!(lines.iter().all(|l| !l.contains("visited.example") && !l.contains("private-path")), "{lines:?}");
    }

    /// An exported report names no search domain: not in its counters, not in the recorder's
    /// network event (the app's label is the network's kind only).
    #[test]
    fn report_names_no_search_domain() {
        let s = Shared::new(Config::default());
        s.set_network_info("Wi-Fi", vec!["campus.example.edu".into()]);
        s.set_network(vec!["192.0.2.53:53".parse().unwrap()], true, false, 7);
        let report = serde_json::json!({"engine": s.report_json(), "problems": s.health_json(256)}).to_string();
        assert!(!report.contains("campus.example.edu"), "{report}");
        assert_eq!(s.report_json()["search_domains"], 1);
        assert!(s.in_search_domain("lms.campus.example.edu"));
    }

    /// Without a network, DNS and connect failures are counted but kept as one event for the
    /// whole stretch, so they cannot push real problems out; once a network is back they are
    /// recorded again, and the stretch's event says how long it was and what failed.
    #[test]
    fn offline_failures_do_not_flood_the_recorder() {
        let s = Shared::new(Config::default());
        s.event(health::Kind::Passthrough, "bank.test", 1, "kept");
        for i in 0..1000 {
            s.dns_failure(health::Kind::DnsFailed, &format!("h{i}.test"), 1, "no upstream DNS servers");
        }
        for _ in 0..255 {
            s.offline_failure(health::Kind::ConnectFailed);
        }
        let h = s.health_json(300);
        assert_eq!(h["counts"]["dns_failed"], 1000);
        assert_eq!(h["counts"]["connect_failed"], 255);
        assert_eq!(h["events"].as_array().unwrap().len(), 2, "one offline stretch + the passthrough");
        assert_eq!(h["events"][0]["k"], "network_lost");
        s.set_network_info("Wi-Fi · lan", vec!["lan".into(), "campus.example.edu".into()]);
        s.set_network(vec!["192.168.1.1:53".parse().unwrap()], true, false, 7);
        let h = s.health_json(300);
        let lost = h["events"].as_array().unwrap().iter().find(|e| e["k"] == "network_lost").unwrap().clone();
        assert_eq!(lost["detail"], "no network: 255 connections and 1000 DNS lookups failed");
        assert!(h["events"][0]["detail"].as_str().unwrap().starts_with("Wi-Fi · lan · dns=192.168.1.1"), "{}", h["events"][0]);
        s.dns_failure(health::Kind::DnsFailed, "x.test", 1, "timeout");
        assert_eq!(s.health_json(300)["events"][0]["host"], "x.test");
        // Search domains: multi-label ones only, matched on label boundaries.
        assert!(s.in_search_domain("lms.campus.example.edu") && s.in_search_domain("campus.example.edu"));
        assert!(!s.in_search_domain("router.lan") && !s.in_search_domain("xcampus.example.edu"));
        // A second network: the event names both.
        s.set_network_info("Wi-Fi · eduroam", Vec::new());
        s.set_network(vec!["10.0.0.53:53".parse().unwrap()], true, false, 8);
        assert!(s.health_json(1)["events"][0]["detail"].as_str().unwrap().starts_with("Wi-Fi · lan → Wi-Fi · eduroam"));
    }

    /// The reverse zones only the network can answer.
    #[test]
    fn private_reverse_zones() {
        for n in [
            "5.1.168.192.in-addr.arpa",
            "1.0.0.10.in-addr.arpa",
            "10.in-addr.arpa",
            "9.9.20.172.in-addr.arpa",
            "1.1.254.169.in-addr.arpa",
            "1.0.64.100.in-addr.arpa",
            "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.d.f.ip6.arpa",
            "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.e.f.ip6.arpa",
        ] {
            assert!(private_reverse_zone(n), "{n}");
        }
        for n in [
            "8.8.8.8.in-addr.arpa",
            "1.0.15.172.in-addr.arpa",
            "1.0.32.172.in-addr.arpa",
            "1.168.192.in-addr.arpa.example",
            "192.in-addr.arpa",
            "8.8.4.4.in-addr.arpa",
            "1.0.0.2.ip6.arpa",
            "1.0.c.e.f.ip6.arpa",
            "example.com",
        ] {
            assert!(!private_reverse_zone(n), "{n}");
        }
    }

    /// Pause and resume leave markers in the query log; a timed pause that ran out ends at its
    /// own time.
    #[test]
    fn pause_markers_in_the_log() {
        let s = Shared::new(Config::default());
        let now = now_ms();
        let cfg = |until: u64| Config { paused_until_ms: until, ..Config::default() };
        s.set_config(cfg(u64::MAX));
        s.set_config(cfg(0));
        s.set_config(cfg(now - 5000));
        s.set_config(cfg(0));
        let log: Vec<serde_json::Value> = serde_json::from_str(&s.log_json(0, 10)).unwrap();
        let kinds: Vec<&str> = log.iter().map(|e| e["a"].as_str().unwrap()).collect();
        assert_eq!(kinds, ["Paused", "Resumed", "Resumed"]);
        assert_eq!(log[2]["t"].as_u64(), Some(now - 5000));
    }

    /// A timed pause that runs out is marked "Resumed" when the next lookup is recorded, so the
    /// marker comes before that lookup in the log, not when the app later sends a config.
    #[test]
    fn expired_pause_resumes_before_the_next_lookup() {
        let s = Shared::new(Config::default());
        s.set_config(Config { paused_until_ms: now_ms() + 60_000, pause_left_ms: Some(20), ..Config::default() });
        std::thread::sleep(std::time::Duration::from_millis(40));
        s.record("a.test", 1, 10, Action::Upstream, None, 100);
        s.set_config(Config::default());
        let log: Vec<serde_json::Value> = serde_json::from_str(&s.log_json(0, 10)).unwrap();
        let kinds: Vec<&str> = log.iter().map(|e| e["a"].as_str().unwrap()).collect();
        assert_eq!(kinds, ["Paused", "Resumed", "Upstream"]);
        assert!(log[1]["t"].as_u64() <= log[2]["t"].as_u64());
    }

    /// DNS answers are told from the proxy's records by their query type, not by a latency
    /// above zero: a lookup answered in under a microsecond is still a DNS answer.
    #[test]
    fn instant_dns_answers_count_as_dns() {
        let s = Shared::new(Config::default());
        s.record("a.test", 1, 10, Action::Cache, None, 0);
        s.record("a.test", 1, 10, Action::Cache, None, 0);
        s.record("ads.test", 0, 10, Action::Filtered, None, 0);
        let st = s.stats.lock();
        assert_eq!((st.queries, st.dns_queries), (3, 2));
        drop(st);
        let log: Vec<serde_json::Value> = serde_json::from_str(&s.log_json(0, 10)).unwrap();
        assert_eq!(log.len(), 3, "DNS answers are each logged");
    }

    /// The pause end follows the boot clock: a wall time that moved past the end
    /// does not end it early, what is left of it decides.
    #[test]
    fn pause_follows_the_boot_clock() {
        let s = Shared::new(Config::default());
        let now = now_ms();
        let cfg = |until: u64, left: Option<u64>| Config { paused_until_ms: until, pause_left_ms: left, ..Config::default() };
        s.set_config(cfg(now - 1000, Some(60_000)));
        assert!(!s.filtering_active(&s.config.load()), "wall time says over, 60 s are left");
        s.set_config(cfg(now + 60_000, Some(0)));
        assert!(s.filtering_active(&s.config.load()), "nothing left: filtering");
        s.set_config(cfg(i64::MAX as u64, Some(i64::MAX as u64)));
        assert!(!s.filtering_active(&s.config.load()), "until Resume");
        s.set_config(cfg(now + 60_000, None));
        assert!(!s.filtering_active(&s.config.load()), "old apps: from the wall time");
        s.set_config(cfg(0, None));
        assert!(s.filtering_active(&s.config.load()));
    }

    /// Public answers count for the rebinding guard on the network they came from only.
    #[test]
    fn public_answers_are_per_network() {
        let s = Shared::new(Config::default());
        s.set_network(vec!["192.168.1.1:53".parse().unwrap()], true, false, 1);
        let info = dns::ResponseInfo { addrs: vec!["93.184.216.34".parse().unwrap()], ..Default::default() };
        s.note_answer("lms.campus.test", &info);
        let private = dns::ResponseInfo { addrs: vec!["10.1.2.3".parse().unwrap()], ..Default::default() };
        s.note_answer("nas.campus.test", &private);
        assert!(s.saw_public_recently("lms.campus.test") && !s.saw_public_recently("nas.campus.test"));
        s.set_network(vec!["10.0.0.53:53".parse().unwrap()], true, false, 2);
        assert!(!s.saw_public_recently("lms.campus.test"), "learned on another network");
    }

    /// A new network whose resolver has the old one's address (two routers at 192.168.1.1)
    /// still drops the old network's local answers.
    #[test]
    fn same_resolver_address_on_a_new_network() {
        use crate::dns::*;
        let s = Shared::new(Config::default());
        let dns1: Vec<SocketAddr> = vec!["192.168.1.1:53".parse().unwrap()];
        s.set_network(dns1.clone(), true, false, 1);
        let raw = build_query(7, "router.lan", TYPE_A);
        let q = parse_query(&raw).unwrap();
        let nx = blocked_response(&raw, &q, BlockMode::NxDomain, 100);
        let k = cache::Key { name: "router.lan".into(), qtype: TYPE_A, qclass: CLASS_IN, do_bit: false };
        assert!(s.cache.lock().insert(k, &nx, &inspect(&nx).unwrap()));
        s.set_network(dns1.clone(), true, false, 1);
        assert_eq!(s.cache.lock().len(), 1, "same network: kept");
        s.set_network(dns1.clone(), true, false, 0);
        assert_eq!(s.cache.lock().len(), 1, "network lost: nothing to replace it yet");
        s.set_network(dns1, true, false, 2);
        assert_eq!(s.cache.lock().len(), 0, "another network behind the same resolver address");
    }

    /// The same resolvers in another order, or an address family showing up, keep the
    /// cache and log no network change; another resolver does.
    #[test]
    fn resolver_order_and_families_keep_the_cache() {
        use crate::dns::*;
        let s = Shared::new(Config::default());
        let a: SocketAddr = "192.168.1.1:53".parse().unwrap();
        let b: SocketAddr = "192.168.1.2:53".parse().unwrap();
        s.set_network(vec![a, b], true, false, 1);
        let raw = build_query(7, "router.lan", TYPE_A);
        let q = parse_query(&raw).unwrap();
        let nx = blocked_response(&raw, &q, BlockMode::NxDomain, 100);
        let k = cache::Key { name: "router.lan".into(), qtype: TYPE_A, qclass: CLASS_IN, do_bit: false };
        assert!(s.cache.lock().insert(k, &nx, &inspect(&nx).unwrap()));
        let changes = |s: &Shared| s.health_json(50)["counts"]["network_changed"].as_u64().unwrap();
        let before = changes(&s);
        s.set_network(vec![b, a], true, false, 1);
        s.set_network(vec![b, a], true, true, 1);
        assert_eq!((s.cache.lock().len(), changes(&s)), (1, before), "reorder and IPv6 arriving");
        s.set_network(vec!["10.0.0.1:53".parse().unwrap()], true, true, 1);
        assert_eq!((s.cache.lock().len(), changes(&s)), (0, before + 1), "another resolver");
    }

    /// Concurrent writers never leave a broken stats file; an unreadable one is kept as
    /// `.bad`.
    #[test]
    fn stats_writes_never_corrupt() {
        let dir = std::env::temp_dir().join(format!("aduster-stats-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("stats.json").to_string_lossy().to_string();
        let s = Shared::new(Config { stats_path: Some(path.clone()), ..Config::default() });
        std::thread::scope(|sc| {
            for t in 0..8 {
                let s = &s;
                sc.spawn(move || {
                    for i in 0..50 {
                        s.record(&format!("h{t}-{i}.test"), 1, -1, Action::Upstream, None, 10);
                        s.persist_stats();
                    }
                });
            }
        });
        let b = std::fs::read(&path).unwrap();
        assert!(serde_json::from_slice::<stats::Stats>(&b).is_ok());
        std::fs::write(&path, b"{broken").unwrap();
        let _ = Shared::new(Config { stats_path: Some(path.clone()), ..Config::default() });
        assert!(std::path::Path::new(&format!("{path}.bad")).exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The proxy's records are logged once per host, kind and minute; counted every time.
    #[test]
    fn proxy_records_are_logged_once_a_minute() {
        let s = Shared::new(Config::default());
        for _ in 0..20 {
            s.record("ads.test", 0, 10, Action::Filtered, None, 0);
        }
        s.record("ads.test", 0, 10, Action::Upstream, None, 0);
        let log: Vec<serde_json::Value> = serde_json::from_str(&s.log_json(0, 100)).unwrap();
        assert_eq!(log.len(), 2);
        assert_eq!(s.stats.lock().queries, 21);
    }

    /// The proxy's records (no DNS latency of their own) stay out of the latency histograms.
    #[test]
    fn proxy_records_are_not_timed() {
        let mut st = stats::Stats::default();
        st.add("a.test", -1, Action::Upstream, None);
        st.add("b.test", -1, Action::Blocked, None);
        st.add("c.test", -1, Action::Upstream, Some(5000));
        let r = st.report();
        assert_eq!(r["queries"], 3);
        assert!(r["latency"]["upstream_p50_us"].as_u64().unwrap() >= 4096, "{}", r["latency"]);
        assert_eq!(r["latency"]["local_p50_us"], 0);
    }
}
