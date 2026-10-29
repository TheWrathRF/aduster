//! Flight recorder for network problems. Healthy traffic records nothing (no timers, no
//! allocation); anything that could show up as a stalled page leaves one entry here, so a
//! random stall can be diagnosed after the fact.

use serde::Serialize;
use std::collections::VecDeque;

const CAPACITY: usize = 256;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    /// An upstream request failed before any response arrived.
    UpstreamError,
    /// An HTTP/2 keep-alive ping went unanswered: the connection was dead.
    KeepAliveTimeout,
    /// A failed idempotent request was sent again on a fresh connection.
    Retried,
    /// Response headers took unusually long.
    SlowResponse,
    /// No address of a host could be connected to.
    ConnectFailed,
    /// Upstream DNS did not answer in time or failed.
    DnsFailed,
    /// DNS failed and an expired answer was served instead of an error.
    DnsStaleServed,
    /// The underlying network (and its resolvers) changed.
    NetworkChanged,
    /// A site answered with a bot check; it is left undecrypted for a while.
    ChallengeBypass,
    /// Chrome's connection was handed to the real server undecrypted (certificate rejected, or
    /// the server refused the filtered handshake). The detail says why.
    Passthrough,
    /// A public name resolved only to local addresses and Chrome's connection was refused
    /// (DNS rebinding).
    RebindBlocked,
    /// A stretch without a usable network: one event for the whole stretch (updated when it
    /// ends: `ms` is its length, the detail how many connections and lookups failed in it).
    NetworkLost,
    /// A site answered with a bot check that was let through to Chrome untouched (a first one:
    /// it may clear through the filter).
    ChallengePassed,
    /// A consent window came back after its "reject" was pressed twice in 10 minutes (its
    /// platform cannot store the answer): not pressed again for a while.
    ConsentLoop,
    /// Reported by the page engine (private): one of its self-checks found a difference; once
    /// per field per day.
    EngineMismatch,
    /// Reported by the page engine (private): the browser is 4 or more versions newer than the
    /// engine's root store table; once per process.
    RootStoreOld,
    /// A DNS answer that came, but after more than a second.
    DnsSlow,
    /// Filters loaded without some regex rules that could not be built (too large); the
    /// detail says how many.
    RulesSkipped,
}

const KINDS: usize = 18;
/// Longest host kept in an event (a DNS name is at most 253 bytes; CONNECT targets are not
/// checked that strictly).
const MAX_HOST: usize = 253;

#[derive(Debug, Clone, Serialize)]
pub struct Event {
    /// Unix time, milliseconds.
    pub t: u64,
    pub k: Kind,
    pub host: Box<str>,
    /// Elapsed milliseconds for the operation, if meaningful.
    pub ms: u32,
    #[serde(skip_serializing_if = "str::is_empty")]
    pub detail: Box<str>,
}

#[derive(Default)]
pub struct Ring {
    events: VecDeque<Event>,
    /// Totals since start, by kind (never trimmed).
    counts: [u64; KINDS],
}

impl Ring {
    /// Keeps an event; returns its time (unix ms), which identifies it for [`Ring::update`].
    pub fn push(&mut self, k: Kind, host: &str, ms: u32, detail: &str) -> u64 {
        self.counts[k as usize] += 1;
        if self.events.len() == CAPACITY {
            self.events.pop_front();
        }
        let t = crate::now_ms();
        let host: String = host.chars().take(MAX_HOST).collect();
        self.events.push_back(Event { t, k, host: host.into(), ms, detail: detail.chars().take(160).collect::<String>().into() });
        t
    }

    /// Counts a problem without keeping an event for it (a repeat of one already kept).
    pub fn count(&mut self, k: Kind) {
        self.counts[k as usize] += 1;
    }

    /// Updates the kept event of kind `k` made at unix ms `t` (if it is still in the ring).
    pub fn update(&mut self, k: Kind, t: u64, ms: u32, detail: &str) {
        if let Some(e) = self.events.iter_mut().rev().find(|e| e.k == k && e.t == t) {
            e.ms = ms;
            e.detail = detail.chars().take(160).collect::<String>().into();
        }
    }

    /// Totals by kind, under the same names the events carry.
    pub fn counts_json(&self) -> serde_json::Value {
        const NAMES: [&str; KINDS] = [
            "upstream_error",
            "keepalive_timeout",
            "retried",
            "slow_response",
            "connect_failed",
            "dns_failed",
            "dns_stale_served",
            "network_changed",
            "challenge_bypass",
            "passthrough",
            "rebind_blocked",
            "network_lost",
            "challenge_passed",
            "consent_loop",
            "engine_mismatch",
            "root_store_old",
            "dns_slow",
            "rules_skipped",
        ];
        NAMES.iter().zip(self.counts).map(|(n, c)| (n.to_string(), serde_json::Value::from(c))).collect::<serde_json::Map<_, _>>().into()
    }

    /// Newest first, at most `max`.
    pub fn recent(&self, max: usize) -> Vec<&Event> {
        self.events.iter().rev().take(max).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_and_counted() {
        let mut r = Ring::default();
        for i in 0..300 {
            r.push(Kind::DnsFailed, &format!("h{i}"), 1, "");
        }
        r.push(Kind::Retried, "x", 2, "detail");
        assert_eq!(r.events.len(), CAPACITY);
        assert_eq!(r.counts_json()["dns_failed"], 300);
        assert_eq!(r.recent(1)[0].host.as_ref(), "x");
        let t = r.push(Kind::NetworkLost, "", 0, "no network");
        r.update(Kind::NetworkLost, t, 5000, "no network: 3 connections failed");
        assert_eq!((r.recent(1)[0].ms, r.recent(1)[0].detail.as_ref()), (5000, "no network: 3 connections failed"));
        r.push(Kind::ChallengeBypass, &"a".repeat(5000), 0, "");
        assert_eq!(r.recent(1)[0].host.len(), MAX_HOST);
        // Count names are the event names.
        let counts = r.counts_json();
        assert!(counts.get("challenge_bypass").is_some() && counts.get("challenge_passed").is_some());
        assert_eq!(serde_json::to_value(Kind::ChallengeBypass).unwrap(), "challenge_bypass");
        r.push(Kind::RulesSkipped, "", 0, "1 regex rules could not be built and are not applied");
        assert_eq!(r.counts_json()["rules_skipped"], 1);
        assert_eq!(serde_json::to_value(Kind::RulesSkipped).unwrap(), "rules_skipped");
    }
}
