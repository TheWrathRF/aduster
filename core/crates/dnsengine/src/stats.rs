//! Daily counters, top lists, per-app totals and the in-memory query log.
//!
//! Everything lives in memory; persistence piggybacks on query handling (see `Shared::record`)
//! instead of timers, so an idle phone never wakes up because of statistics.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};

const HISTORY_DAYS: usize = 30;
const TOP_LIMIT: usize = 1500;
const TOP_KEEP: usize = 1000;
const TOP_REPORT: usize = 50;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[repr(u8)]
pub enum Action {
    Upstream = 0,
    Cache = 1,
    Blocked = 2,
    BlockedCname = 3,
    Failed = 4,
    Stale = 5,
    /// A request inside a page, blocked by the Chrome filter.
    Filtered = 6,
    /// Query-log markers (never counted in the stats): filtering was paused / resumed here.
    Paused = 7,
    Resumed = 8,
}

impl Action {
    pub fn is_blocked(self) -> bool {
        matches!(self, Action::Blocked | Action::BlockedCname | Action::Filtered)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DayStat {
    pub day: i64,
    pub queries: u64,
    pub blocked: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AppStat {
    pub queries: u64,
    pub blocked: u64,
}

/// Exponential latency histogram: bucket i holds samples in [2^i, 2^(i+1)) microseconds.
#[derive(Debug, Clone, Default)]
pub struct Histogram {
    buckets: [u64; 24],
    count: u64,
}

impl Histogram {
    pub fn add(&mut self, us: u32) {
        let i = (32 - us.max(1).leading_zeros() - 1).min(23) as usize;
        self.buckets[i] += 1;
        self.count += 1;
    }

    #[cfg(test)]
    pub fn count(&self) -> u64 {
        self.count
    }

    /// Upper bound (µs) of the bucket containing the given percentile.
    pub fn percentile(&self, p: f64) -> u64 {
        if self.count == 0 {
            return 0;
        }
        let target = ((self.count as f64) * p).ceil() as u64;
        let mut acc = 0;
        for (i, b) in self.buckets.iter().enumerate() {
            acc += b;
            if acc >= target {
                return 1u64 << (i + 1);
            }
        }
        1 << 24
    }
}

#[derive(Debug, Serialize, Deserialize, Default)]
pub struct Stats {
    pub day: i64,
    pub queries: u64,
    pub blocked: u64,
    pub cached: u64,
    pub total_queries: u64,
    pub total_blocked: u64,
    pub history: VecDeque<DayStat>,
    pub top_blocked: HashMap<String, u64>,
    pub top_allowed: HashMap<String, u64>,
    /// Of the blocked counts, those of requests a page rule stopped inside Chrome's pages (the
    /// host itself is not blocked, only some of its addresses): the Home screen says so.
    #[serde(default)]
    pub top_in_pages: HashMap<String, u64>,
    /// Today's DNS lookups and DNS blocks alone: `queries`/`blocked` also count
    /// Chrome's connections and in-page blocks, so their ratio mixes units.
    #[serde(default)]
    pub dns_queries: u64,
    #[serde(default)]
    pub dns_blocked: u64,
    pub apps: HashMap<i32, AppStat>,
    #[serde(skip)]
    pub upstream_latency: Histogram,
    #[serde(skip)]
    pub local_latency: Histogram,
    #[serde(skip)]
    pub dirty: bool,
}

impl Stats {
    pub fn roll_day(&mut self, day: i64) {
        if self.day == day {
            return;
        }
        if self.day != 0 && (self.queries > 0 || self.blocked > 0) {
            self.history.push_back(DayStat { day: self.day, queries: self.queries, blocked: self.blocked });
            while self.history.len() > HISTORY_DAYS {
                self.history.pop_front();
            }
        }
        self.day = day;
        self.queries = 0;
        self.blocked = 0;
        self.cached = 0;
        self.top_blocked.clear();
        self.top_allowed.clear();
        self.top_in_pages.clear();
        self.dns_queries = 0;
        self.dns_blocked = 0;
        self.apps.clear();
        self.dirty = true;
    }

    /// `dns`: the latency of a DNS answer; None for the proxy's records (Chrome's connections and
    /// page blocks): counted, but kept out of the DNS counts and the latency histograms, which
    /// would otherwise show zeros instead of the resolver's real speed.
    pub fn add(&mut self, name: &str, uid: i32, action: Action, dns: Option<u32>) {
        self.add_from(name, uid, action, dns, false);
    }

    /// [`Stats::add`]; `waited`: the client waited for an upstream exchange (whatever it was then
    /// answered with: an expired answer after a failure, the failure, a CNAME block), so its
    /// latency is the resolver's (a stall answered from stale after 3.5 s is not a slow cache
    /// answer, and `upstream_latency` must see it).
    pub fn add_from(&mut self, name: &str, uid: i32, action: Action, dns: Option<u32>, waited: bool) {
        self.queries += 1;
        self.total_queries += 1;
        let blocked = action.is_blocked();
        if dns.is_some() {
            self.dns_queries += 1;
            self.dns_blocked += blocked as u64;
        }
        if blocked {
            self.blocked += 1;
            self.total_blocked += 1;
            let prunes = self.top_blocked.len() >= TOP_LIMIT && !self.top_blocked.contains_key(name);
            bump(&mut self.top_blocked, name);
            if prunes {
                // Pruned together: a name pruned from the top list loses its page count.
                let tb = &self.top_blocked;
                self.top_in_pages.retain(|k, _| tb.contains_key(k));
            }
            if action == Action::Filtered {
                bump(&mut self.top_in_pages, name);
            }
        } else {
            bump(&mut self.top_allowed, name);
            if matches!(action, Action::Cache | Action::Stale) {
                self.cached += 1;
            }
        }
        if let Some(latency_us) = dns {
            match action {
                _ if waited => self.upstream_latency.add(latency_us),
                Action::Upstream => self.upstream_latency.add(latency_us),
                Action::Cache | Action::Stale => self.local_latency.add(latency_us),
                a if a.is_blocked() => self.local_latency.add(latency_us),
                _ => {}
            }
        }
        if uid >= 0 {
            let a = self.apps.entry(uid).or_default();
            a.queries += 1;
            a.blocked += blocked as u64;
        }
        self.dirty = true;
    }

    pub fn report(&self) -> serde_json::Value {
        serde_json::json!({
            "day": self.day,
            "queries": self.queries,
            "blocked": self.blocked,
            "cached": self.cached,
            "total_queries": self.total_queries,
            "total_blocked": self.total_blocked,
            "history": self.history,
            "top_blocked": top(&self.top_blocked),
            // Names of `top_blocked` counted mostly from requests blocked inside pages.
            "top_blocked_in_pages": top(&self.top_blocked)
                .into_iter()
                .filter(|(n, c)| self.top_in_pages.get(*n).is_some_and(|p| p * 2 >= *c))
                .map(|(n, _)| n)
                .collect::<Vec<_>>(),
            // How many of each top name's blocks were inside pages (shown as "N · M in pages").
            "top_blocked_page_counts": top(&self.top_blocked)
                .into_iter()
                .filter_map(|(n, _)| self.top_in_pages.get(n).map(|p| (n, *p)))
                .collect::<Vec<_>>(),
            "dns_queries": self.dns_queries,
            "dns_blocked": self.dns_blocked,
            "top_allowed": top(&self.top_allowed),
            "apps": self.apps.iter().map(|(u, a)| (u, a.queries, a.blocked)).collect::<Vec<_>>(),
            "latency": {
                "upstream_p50_us": self.upstream_latency.percentile(0.5),
                "upstream_p99_us": self.upstream_latency.percentile(0.99),
                "local_p50_us": self.local_latency.percentile(0.5),
                "local_p99_us": self.local_latency.percentile(0.99),
            },
        })
    }
}

fn bump(map: &mut HashMap<String, u64>, name: &str) {
    if let Some(c) = map.get_mut(name) {
        *c += 1;
        return;
    }
    if map.len() >= TOP_LIMIT {
        let mut counts: Vec<u64> = map.values().copied().collect();
        counts.sort_unstable_by(|a, b| b.cmp(a));
        let cutoff = counts[TOP_KEEP.min(counts.len() - 1)];
        map.retain(|_, c| *c > cutoff);
    }
    map.insert(name.to_string(), 1);
}

fn top(map: &HashMap<String, u64>) -> Vec<(&str, u64)> {
    let mut v: Vec<(&str, u64)> = map.iter().map(|(k, c)| (k.as_str(), *c)).collect();
    v.sort_unstable_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(b.0)));
    v.truncate(TOP_REPORT);
    v
}

#[derive(Debug, Clone, Serialize)]
pub struct LogEntry {
    /// Sequence number, strictly increasing; the UI polls with "since".
    pub s: u64,
    /// Unix time, milliseconds.
    pub t: u64,
    pub n: Box<str>,
    pub q: u16,
    pub u: i32,
    pub a: Action,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub r: Option<Box<str>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub l: Option<Box<str>>,
    pub us: u32,
    /// Failed while there was no network (the log shows those as one stretch).
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub o: bool,
    /// A request blocked inside a page: the page's host (an exception can then be limited to
    /// that site instead of unfiltering the whole host).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub p: Option<Box<str>>,
    /// …and the request's path (shown as "Request", apart from the rule).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub x: Option<Box<str>>,
}

pub struct LogRing {
    cap: usize,
    next: u64,
    entries: VecDeque<LogEntry>,
}

impl LogRing {
    /// Sequence numbers start at the boot clock × 1000, so a restarted `:core` never repeats the
    /// numbers of the lifetime before it (the Log keys rows by them, and a repeated key crashes
    /// the list; the UI's "after" paging would also skip the new lifetime's first entries).
    /// That holds as long as a lifetime logs fewer than 1000 entries per millisecond it lived.
    pub fn new(cap: usize) -> LogRing {
        LogRing::starting_at(cap, crate::clock::boot_ms() * 1000 + 1)
    }

    fn starting_at(cap: usize, first: u64) -> LogRing {
        LogRing { cap, next: first, entries: VecDeque::with_capacity(cap.min(1024)) }
    }

    pub fn set_capacity(&mut self, cap: usize) {
        self.cap = cap;
        while self.entries.len() > cap {
            self.entries.pop_front();
        }
    }

    pub fn enabled(&self) -> bool {
        self.cap > 0
    }

    pub fn push(&mut self, mut e: LogEntry) {
        if self.cap == 0 {
            return;
        }
        e.s = self.next;
        self.next += 1;
        if self.entries.len() == self.cap {
            self.entries.pop_front();
        }
        self.entries.push_back(e);
    }

    /// Newest-last entries with sequence > `since`, at most `max` (the newest ones).
    pub fn since(&self, since: u64, max: usize) -> Vec<&LogEntry> {
        let start = self.entries.partition_point(|e| e.s <= since);
        let from = start.max(self.entries.len().saturating_sub(max));
        self.entries.range(from..).collect()
    }

    /// The OLDEST `max` entries with sequence > `since`, newest last: the UI pages forward with
    /// it, so a burst of more than one page between two polls leaves no hole.
    pub fn after(&self, since: u64, max: usize) -> Vec<&LogEntry> {
        let start = self.entries.partition_point(|e| e.s <= since);
        let end = (start + max).min(self.entries.len());
        self.entries.range(start..end).collect()
    }

    pub fn clear(&mut self) {
        self.entries.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn day_roll_moves_counters_to_history() {
        let mut s = Stats::default();
        s.roll_day(100);
        s.add("a.com", 10, Action::Blocked, Some(5));
        s.add("b.com", 10, Action::Upstream, Some(30_000));
        s.roll_day(101);
        assert_eq!((s.queries, s.blocked, s.total_queries, s.total_blocked), (0, 0, 2, 1));
        assert_eq!(s.history.back().map(|d| (d.day, d.queries, d.blocked)), Some((100, 2, 1)));
    }

    /// Hosts blocked mostly inside pages are named as such for the Home screen.
    #[test]
    fn page_blocks_are_told_apart() {
        let mut s = Stats::default();
        s.roll_day(100);
        for _ in 0..3 {
            s.add("x.com", 10, Action::Filtered, None);
            s.add("ads.test", 10, Action::Blocked, Some(5));
        }
        s.add("x.com", 10, Action::Blocked, None);
        let r = s.report();
        assert_eq!(r["top_blocked_in_pages"], serde_json::json!(["x.com"]));
        // The page count beside the total; DNS counted apart.
        assert_eq!(r["top_blocked_page_counts"], serde_json::json!([["x.com", 3]]));
        assert_eq!((r["dns_queries"].as_u64(), r["dns_blocked"].as_u64()), (Some(3), Some(3)));
        assert_eq!(r["queries"].as_u64(), Some(7));
        s.roll_day(101);
        assert!(s.top_in_pages.is_empty());
        assert_eq!(s.dns_queries, 0);
    }

    #[test]
    fn top_maps_stay_bounded() {
        let mut s = Stats::default();
        for i in 0..5000 {
            s.add(&format!("d{i}.com"), -1, Action::Blocked, Some(1));
        }
        assert!(s.top_blocked.len() <= TOP_LIMIT);
    }

    #[test]
    fn ring_since() {
        // A lifetime started later numbers above every entry of an earlier one.
        assert!(LogRing::new(3).next > 1);
        let mut r = LogRing::starting_at(3, 1);
        for i in 0..5 {
            r.push(LogEntry { s: 0, t: i, n: "x".into(), q: 1, u: 0, a: Action::Upstream, r: None, l: None, us: 0, o: false, p: None, x: None });
        }
        let all: Vec<u64> = r.since(0, 10).iter().map(|e| e.s).collect();
        assert_eq!(all, vec![3, 4, 5]);
        assert_eq!(r.since(4, 10).len(), 1);
        assert_eq!(r.since(0, 2).iter().map(|e| e.s).collect::<Vec<_>>(), vec![4, 5]);
        // Forward pages: the oldest first, no hole between pages.
        assert_eq!(r.after(0, 2).iter().map(|e| e.s).collect::<Vec<_>>(), vec![3, 4]);
        assert_eq!(r.after(4, 2).iter().map(|e| e.s).collect::<Vec<_>>(), vec![5]);
        assert!(r.after(5, 2).is_empty());
    }

    #[test]
    fn histogram_percentiles() {
        let mut h = Histogram::default();
        for _ in 0..90 {
            h.add(100);
        }
        for _ in 0..10 {
            h.add(50_000);
        }
        assert_eq!(h.percentile(0.5), 128);
        assert_eq!(h.percentile(0.99), 65_536);
    }
}
