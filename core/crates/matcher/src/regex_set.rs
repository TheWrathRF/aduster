//! Prefiltered per-class matcher for non-domain (wildcard and regex) DNS rules.
//!
//! Combining hundreds of wildcard patterns like `(?:^|\.)ad.*\.example\.com$` into one
//! multi-pattern regex causes lazy-DFA powerset blow-up on real host names: each prefix keeps its
//! `.*` branch alive for the rest of the name, so diverse multi-label names quickly overflow the
//! 2 MiB cache, trigger the lazy DFA's give-up heuristic, and drop to NFA backtracking.
//!
//! Instead, each pattern with a usable required literal is indexed into a compact per-class
//! Aho-Corasick automaton (`ContiguousNFA`, fixed heap, no mutable cache). A lookup scans the host
//! name once for overlapping required literals and verifies only the few candidate patterns that
//! matched; patterns without a usable literal stay in a small fallback regex built with
//! `WhichCaptures::None` (zero capture slots).

use crate::Class;
use aho_corasick::{AhoCorasick, AhoCorasickKind};
use regex_automata::meta::Regex;
use regex_automata::nfa::thompson::WhichCaptures;
use regex_automata::util::syntax;
use regex_automata::{Input, PatternSet};
use std::collections::HashMap;

/// Maximum pattern byte length accepted by the direct wildcard fast path; anything longer goes
/// through the regex builder so its NFA size limit still applies.
const MAX_WILDCARD_LEN: usize = 4096;
/// Maximum prefilter literal length: a slice of a required literal is still required, and host
/// names are at most 253 bytes, so 64 bytes is plenty while keeping prefilter automata small.
const MAX_PREFILTER_LITERAL: usize = 64;
/// Upper bounds for choosing a full Aho-Corasick DFA over `ContiguousNFA`: keeps the DFA to a
/// few tens of KiB even when every literal hits [`MAX_PREFILTER_LITERAL`].
const MAX_DFA_LITERALS: usize = 32;
const MAX_DFA_BYTES: usize = 512;

/// All regex/wildcard rules of one [`Class`].
pub(crate) struct ClassRegex {
    pub(crate) class: Class,
    prefilter: Option<Prefiltered>,
    fallback: Vec<FallbackGroup>,
}

struct Prefiltered {
    ac: AhoCorasick,
    /// Compressed slice bounds into `bucket_cands` per Aho-Corasick pattern id.
    bucket_offs: Vec<u32>,
    bucket_cands: Vec<u32>,
    candidates: Vec<Candidate>,
}

enum Candidate {
    Wildcard { id: u32, wild: Wildcard },
    Regex { id: u32, re: Box<Regex> },
}

struct FallbackGroup {
    re: Regex,
    ids: Vec<u32>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum StartAnchor {
    None,
    Caret,
    Dot,
}

/// Exact matcher for ABP wildcard regexes: optional `(?:^|\.)` or `^`, ASCII literal pieces
/// separated by `.*`, and optional `$`. Matched case-insensitively with `.` excluding `\n`,
/// identical to [`build_fallback_regex`].
struct Wildcard {
    start: StartAnchor,
    end: bool,
    pieces: Box<[Box<[u8]>]>,
}

impl ClassRegex {
    pub(crate) fn build(class: Class, ids: &[u32], patterns: &[String], skipped: &mut usize) -> Option<ClassRegex> {
        let mut lit_to_idx: HashMap<Vec<u8>, usize> = HashMap::new();
        let mut literals: Vec<Vec<u8>> = Vec::new();
        let mut raw_buckets: Vec<Vec<u32>> = Vec::new();
        let mut candidates: Vec<Candidate> = Vec::new();
        let mut fallback_ids: Vec<u32> = Vec::new();

        for &id in ids {
            let pat = patterns[id as usize].as_str();
            if let Some(wild) = Wildcard::parse(pat) {
                if let Some(lit) = best_literal(&wild.pieces).map(|s| s.to_vec()) {
                    let ci = candidates.len() as u32;
                    candidates.push(Candidate::Wildcard { id, wild });
                    push_bucket(&mut lit_to_idx, &mut literals, &mut raw_buckets, lit, ci);
                } else {
                    fallback_ids.push(id);
                }
                continue;
            }
            if let Some(lit) = extract_required_literal(pat) {
                if let Some(re) = build_candidate_regex(pat) {
                    let ci = candidates.len() as u32;
                    candidates.push(Candidate::Regex { id, re: Box::new(re) });
                    push_bucket(&mut lit_to_idx, &mut literals, &mut raw_buckets, lit, ci);
                } else {
                    *skipped += 1;
                }
            } else {
                fallback_ids.push(id);
            }
        }

        let prefilter = if literals.is_empty() {
            None
        } else if let Some(ac) = build_aho_corasick(&literals) {
            let mut bucket_offs = Vec::with_capacity(raw_buckets.len() + 1);
            let mut bucket_cands = Vec::with_capacity(candidates.len());
            bucket_offs.push(0);
            for b in raw_buckets {
                bucket_cands.extend_from_slice(&b);
                bucket_offs.push(bucket_cands.len() as u32);
            }
            Some(Prefiltered { ac, bucket_offs, bucket_cands, candidates })
        } else {
            // Aho-Corasick build failure should never drop rules: fall back to regex search.
            for cand in candidates {
                fallback_ids.push(cand.id());
            }
            None
        };

        let mut fallback = Vec::new();
        if !fallback_ids.is_empty() {
            let fb_pats: Vec<&str> = fallback_ids.iter().map(|&i| patterns[i as usize].as_str()).collect();
            if let Some(re) = build_fallback_regex(&fb_pats) {
                fallback.push(FallbackGroup { re, ids: fallback_ids });
            } else {
                // One pattern too large (or all of them together) must not take every other rule
                // of the table down with it: build them one by one and leave out those that fail.
                for (&id, &pat) in fallback_ids.iter().zip(&fb_pats) {
                    if let Some(re) = build_fallback_regex(&[pat]) {
                        fallback.push(FallbackGroup { re, ids: vec![id] });
                    } else {
                        *skipped += 1;
                    }
                }
            }
        }

        if prefilter.is_none() && fallback.is_empty() { None } else { Some(ClassRegex { class, prefilter, fallback }) }
    }

    pub(crate) fn find(&self, bytes: &[u8]) -> Option<u32> {
        if let Some(pre) = &self.prefilter
            && let Some(id) = pre.find(bytes)
        {
            return Some(id);
        }
        for group in &self.fallback {
            if let Some(id) = group.find(bytes) {
                return Some(id);
            }
        }
        None
    }

    /// Approximate heap bytes held by this class's prefilter, candidates, and fallback regexes.
    #[cfg(test)]
    pub(crate) fn memory_usage(&self) -> usize {
        let mut bytes = self.fallback.capacity() * std::mem::size_of::<FallbackGroup>();
        if let Some(pre) = &self.prefilter {
            bytes += pre.ac.memory_usage();
            bytes += pre.bucket_offs.capacity() * std::mem::size_of::<u32>();
            bytes += pre.bucket_cands.capacity() * std::mem::size_of::<u32>();
            bytes += pre.candidates.capacity() * std::mem::size_of::<Candidate>();
            for cand in &pre.candidates {
                bytes += match cand {
                    Candidate::Wildcard { wild, .. } => wild.memory_usage(),
                    Candidate::Regex { re, .. } => std::mem::size_of::<Regex>() + re.memory_usage() + re.create_cache().memory_usage(),
                };
            }
        }
        for group in &self.fallback {
            bytes += group.re.memory_usage() + group.re.create_cache().memory_usage();
            bytes += group.ids.capacity() * std::mem::size_of::<u32>();
        }
        bytes
    }

    #[cfg(test)]
    pub(crate) fn debug_summary(&self) -> String {
        let (ac_mem, n_lits, n_wild, n_re, cand_mem) = match &self.prefilter {
            Some(pre) => {
                let mut nw = 0usize;
                let mut nr = 0usize;
                let mut cm = pre.bucket_offs.capacity() * 4 + pre.bucket_cands.capacity() * 4 + pre.candidates.capacity() * std::mem::size_of::<Candidate>();
                for c in &pre.candidates {
                    match c {
                        Candidate::Wildcard { wild, .. } => {
                            nw += 1;
                            cm += wild.memory_usage();
                        }
                        Candidate::Regex { re, .. } => {
                            nr += 1;
                            cm += std::mem::size_of::<Regex>() + re.memory_usage() + re.create_cache().memory_usage();
                        }
                    }
                }
                (pre.ac.memory_usage(), pre.bucket_offs.len().saturating_sub(1), nw, nr, cm)
            }
            None => (0, 0, 0, 0, 0),
        };
        let fb_pats: usize = self.fallback.iter().map(|g| g.ids.len()).sum();
        let fb_mem: usize = self.fallback.iter().map(|g| g.re.memory_usage() + g.re.create_cache().memory_usage()).sum();
        format!(
            "{:?}: lits={n_lits} wild={n_wild} re_cand={n_re} fb={fb_pats} ac={}KiB cands={}KiB fb={}KiB total={}KiB",
            self.class,
            ac_mem / 1024,
            cand_mem / 1024,
            fb_mem / 1024,
            self.memory_usage() / 1024
        )
    }
}

impl Prefiltered {
    fn find(&self, bytes: &[u8]) -> Option<u32> {
        for m in self.ac.find_overlapping_iter(bytes) {
            let li = m.pattern().as_usize();
            let start = self.bucket_offs[li] as usize;
            let end = self.bucket_offs[li + 1] as usize;
            for &ci in &self.bucket_cands[start..end] {
                let cand = &self.candidates[ci as usize];
                if cand.matches(bytes) {
                    return Some(cand.id());
                }
            }
        }
        None
    }
}

impl Candidate {
    #[inline]
    fn id(&self) -> u32 {
        match self {
            Candidate::Wildcard { id, .. } | Candidate::Regex { id, .. } => *id,
        }
    }

    #[inline]
    fn matches(&self, bytes: &[u8]) -> bool {
        match self {
            Candidate::Wildcard { wild, .. } => wild.matches(bytes),
            Candidate::Regex { re, .. } => re.is_match(Input::new(bytes).earliest(true)),
        }
    }
}

impl FallbackGroup {
    fn find(&self, bytes: &[u8]) -> Option<u32> {
        let input = Input::new(bytes).earliest(true);
        if !self.re.is_match(input.clone()) {
            return None;
        }
        if self.ids.len() == 1 {
            return Some(self.ids[0]);
        }
        let mut patset = PatternSet::new(self.ids.len());
        self.re.which_overlapping_matches(&input, &mut patset);
        patset.iter().next().map(|p| self.ids[p.as_usize()])
    }
}

impl Wildcard {
    fn parse(pat: &str) -> Option<Wildcard> {
        if pat.len() > MAX_WILDCARD_LEN {
            return None;
        }
        let (start, rest) = if let Some(r) = pat.strip_prefix(r"(?:^|\.)") {
            (StartAnchor::Dot, r)
        } else if let Some(r) = pat.strip_prefix('^') {
            (StartAnchor::Caret, r)
        } else {
            (StartAnchor::None, pat)
        };
        let (rest, end) = match rest.strip_suffix('$') {
            Some(r) => (r, true),
            None => (rest, false),
        };
        let mut pieces = Vec::new();
        for part in rest.split(".*") {
            let mut lit = Vec::with_capacity(part.len());
            let b = part.as_bytes();
            let mut i = 0;
            while i < b.len() {
                if b[i] == b'\\' && i + 1 < b.len() && b[i + 1] == b'.' {
                    lit.push(b'.');
                    i += 2;
                } else if b[i].is_ascii_alphanumeric() || b[i] == b'-' || b[i] == b'_' {
                    lit.push(b[i].to_ascii_lowercase());
                    i += 1;
                } else {
                    return None;
                }
            }
            pieces.push(lit.into_boxed_slice());
        }
        Some(Wildcard { start, end, pieces: pieces.into_boxed_slice() })
    }

    fn matches(&self, hay: &[u8]) -> bool {
        if self.pieces.len() == 1 {
            let p = &self.pieces[0];
            if self.end {
                if hay.len() < p.len() {
                    return false;
                }
                let pos = hay.len() - p.len();
                if !hay[pos..].eq_ignore_ascii_case(p) {
                    return false;
                }
                return match self.start {
                    StartAnchor::None => true,
                    StartAnchor::Caret => pos == 0,
                    StartAnchor::Dot => pos == 0 || hay[pos - 1] == b'.',
                };
            }
            return match self.start {
                StartAnchor::None => find_icase(hay, p).is_some(),
                StartAnchor::Caret => hay.len() >= p.len() && hay[..p.len()].eq_ignore_ascii_case(p),
                StartAnchor::Dot => find_at_dot_boundary(hay, p, true).is_some(),
            };
        }

        // Fast path: host names never contain newlines.
        if !hay.contains(&b'\n') {
            return self.matches_segment(hay, true, self.end);
        }

        // `.` does not match `\n`, and `^` / `$` only match the ends of the whole input.
        if self.start == StartAnchor::Caret {
            if self.end {
                return false;
            }
            let first_nl = hay.iter().position(|&c| c == b'\n').unwrap();
            return self.matches_segment(&hay[..first_nl], true, false);
        }
        if self.end {
            let last_nl = hay.iter().rposition(|&c| c == b'\n').unwrap();
            return self.matches_segment(&hay[last_nl + 1..], false, true);
        }
        let mut seg_start = 0usize;
        for seg in hay.split(|&c| c == b'\n') {
            if self.matches_segment(seg, seg_start == 0, false) {
                return true;
            }
            seg_start += seg.len() + 1;
        }
        false
    }

    fn matches_segment(&self, seg: &[u8], allow_caret: bool, require_end: bool) -> bool {
        let first = &self.pieces[0];
        let mut cur = match self.start {
            StartAnchor::None => match find_icase(seg, first) {
                Some(p) => p + first.len(),
                None => return false,
            },
            StartAnchor::Caret => {
                if !allow_caret || seg.len() < first.len() || !seg[..first.len()].eq_ignore_ascii_case(first) {
                    return false;
                }
                first.len()
            }
            StartAnchor::Dot => match find_at_dot_boundary(seg, first, allow_caret) {
                Some(p) => p + first.len(),
                None => return false,
            },
        };
        let mid_end = if require_end { self.pieces.len() - 1 } else { self.pieces.len() };
        for p in &self.pieces[1..mid_end] {
            let Some(rel) = find_icase(&seg[cur..], p) else {
                return false;
            };
            cur += rel + p.len();
        }
        if require_end {
            let last = &self.pieces[self.pieces.len() - 1];
            cur + last.len() <= seg.len() && seg[seg.len() - last.len()..].eq_ignore_ascii_case(last)
        } else {
            true
        }
    }

    #[cfg(test)]
    fn memory_usage(&self) -> usize {
        self.pieces.len() * std::mem::size_of::<Box<[u8]>>() + self.pieces.iter().map(|p| p.len()).sum::<usize>()
    }
}

#[inline]
fn find_icase(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() {
        return Some(0);
    }
    if needle.len() > hay.len() {
        return None;
    }
    let first = needle[0];
    for i in 0..=(hay.len() - needle.len()) {
        if hay[i].to_ascii_lowercase() == first && hay[i..i + needle.len()].eq_ignore_ascii_case(needle) {
            return Some(i);
        }
    }
    None
}

#[inline]
fn find_at_dot_boundary(hay: &[u8], needle: &[u8], allow_start: bool) -> Option<usize> {
    if allow_start && hay.len() >= needle.len() && hay[..needle.len()].eq_ignore_ascii_case(needle) {
        return Some(0);
    }
    let mut pos = 0;
    while let Some(dot) = hay[pos..].iter().position(|&c| c == b'.') {
        pos += dot + 1;
        if pos + needle.len() <= hay.len() && hay[pos..pos + needle.len()].eq_ignore_ascii_case(needle) {
            return Some(pos);
        }
    }
    None
}

/// Rejects short literals and bare single-label TLDs like `.com` / `.net` / `.info` that would
/// trigger candidate checks on a large fraction of all host names.
fn is_usable_literal(lit: &[u8]) -> bool {
    lit.len() >= 4 && !(lit.len() <= 5 && lit[0] == b'.' && !lit[1..].contains(&b'.'))
}

fn best_literal(pieces: &[Box<[u8]>]) -> Option<&[u8]> {
    pieces.iter().map(|p| p.as_ref()).filter(|p| is_usable_literal(p)).max_by_key(|p| p.len()).map(|p| &p[..p.len().min(MAX_PREFILTER_LITERAL)])
}

/// Conservatively extracts one lowercase ASCII substring that must appear in every match of `pat`.
/// Returns `None` whenever the syntax could make the literal optional or change its meaning
/// (top-level alternation, inline flags, unknown escapes, nested character classes).
fn extract_required_literal(pat: &str) -> Option<Vec<u8>> {
    let b = pat.as_bytes();
    let mut best: Vec<u8> = Vec::new();
    let mut cur: Vec<u8> = Vec::new();
    let mut depth = 0usize;
    let mut i = 0usize;

    let flush = |cur: &mut Vec<u8>, best: &mut Vec<u8>| {
        if is_usable_literal(cur) && cur.len() > best.len() {
            best.clear();
            best.extend_from_slice(cur);
        }
        cur.clear();
    };

    while i < b.len() {
        match b[i] {
            b'[' => {
                flush(&mut cur, &mut best);
                i += 1;
                if i < b.len() && b[i] == b'^' {
                    i += 1;
                }
                if i < b.len() && b[i] == b']' {
                    i += 1;
                }
                let mut closed = false;
                while i < b.len() {
                    match b[i] {
                        b'\\' => {
                            if i + 1 >= b.len() {
                                return None;
                            }
                            i += 2;
                        }
                        b'[' => return None,
                        b']' => {
                            i += 1;
                            closed = true;
                            break;
                        }
                        _ => i += 1,
                    }
                }
                if !closed {
                    return None;
                }
            }
            b'(' => {
                flush(&mut cur, &mut best);
                // Any `(?` other than a plain non-capturing group `(?:` may set flags like `(?x)`
                // that alter how subsequent literals are parsed.
                if b.get(i + 1) == Some(&b'?') {
                    if b.get(i + 2) != Some(&b':') {
                        return None;
                    }
                    i += 3;
                } else {
                    i += 1;
                }
                depth += 1;
            }
            b')' => {
                if depth == 0 {
                    return None;
                }
                flush(&mut cur, &mut best);
                depth -= 1;
                i += 1;
            }
            b'|' => {
                if depth == 0 {
                    return None;
                }
                i += 1;
            }
            b'\\' => {
                let &next = b.get(i + 1)?;
                if depth > 0 {
                    i += 2;
                    continue;
                }
                match next {
                    b'.' | b'-' | b'_' => cur.push(next),
                    b'd' | b'D' | b'w' | b'W' | b's' | b'S' | b'b' | b'B' | b'A' | b'z' | b't' | b'n' | b'r' => {
                        flush(&mut cur, &mut best);
                    }
                    _ => return None,
                }
                i += 2;
            }
            _ if depth > 0 => {
                i += 1;
            }
            b'*' | b'?' => {
                // Quantifying the preceding literal atom with min 0 makes it optional.
                cur.pop();
                flush(&mut cur, &mut best);
                i += 1;
            }
            b'+' | b'.' | b'^' | b'$' => {
                flush(&mut cur, &mut best);
                i += 1;
            }
            b'{' => {
                let start = i + 1;
                let end = b[start..].iter().position(|&c| c == b'}')? + start;
                let inner = &b[start..end];
                if inner.is_empty() || !inner.iter().all(|&c| c.is_ascii_digit() || c == b',') {
                    return None;
                }
                if inner[0] == b'0' || inner[0] == b',' {
                    cur.pop();
                }
                flush(&mut cur, &mut best);
                i = end + 1;
            }
            c if c.is_ascii_alphanumeric() || c == b'-' || c == b'_' => {
                cur.push(c.to_ascii_lowercase());
                i += 1;
            }
            _ => return None,
        }
    }
    if depth != 0 {
        return None;
    }
    flush(&mut cur, &mut best);
    best.truncate(MAX_PREFILTER_LITERAL);
    (!best.is_empty()).then_some(best)
}

fn push_bucket(lit_to_idx: &mut HashMap<Vec<u8>, usize>, literals: &mut Vec<Vec<u8>>, buckets: &mut Vec<Vec<u32>>, lit: Vec<u8>, ci: u32) {
    let li = match lit_to_idx.get(&lit) {
        Some(&idx) => idx,
        None => {
            let idx = literals.len();
            literals.push(lit.clone());
            buckets.push(Vec::new());
            lit_to_idx.insert(lit, idx);
            idx
        }
    };
    buckets[li].push(ci);
}

fn build_aho_corasick(literals: &[Vec<u8>]) -> Option<AhoCorasick> {
    // Small classes (e.g. Allow / ImportantBlock with a handful of short rules) fit in a few KiB
    // as a DFA and skip failure-link walks; larger classes or longer totals use ContiguousNFA
    // (~170 KiB for ~600 literals vs ~3 MiB for a full DFA).
    let total_bytes: usize = literals.iter().map(Vec::len).sum();
    if literals.len() <= MAX_DFA_LITERALS
        && total_bytes <= MAX_DFA_BYTES
        && let Ok(ac) = AhoCorasick::builder().ascii_case_insensitive(true).kind(Some(AhoCorasickKind::DFA)).build(literals)
    {
        return Some(ac);
    }
    AhoCorasick::builder()
        .ascii_case_insensitive(true)
        .kind(Some(AhoCorasickKind::ContiguousNFA))
        .build(literals)
        .or_else(|_| AhoCorasick::builder().ascii_case_insensitive(true).kind(Some(AhoCorasickKind::NoncontiguousNFA)).build(literals))
        .ok()
}

/// Lightweight single-pattern verifier for a non-wildcard regex whose required literal already
/// matched: no DFA, no reverse NFA, and no capture slots, keeping heap tiny while reusing its
/// internal cache pool across lookups.
fn build_candidate_regex(pattern: &str) -> Option<Regex> {
    Regex::builder()
        .syntax(syntax::Config::new().unicode(false).utf8(false).case_insensitive(true))
        .configure(Regex::config().utf8_empty(false).which_captures(WhichCaptures::None).hybrid(false).dfa(false).onepass(false).auto_prefilter(false))
        .build(pattern)
        .ok()
}

/// Multi-pattern fallback for rules without a usable literal: zero capture slots so an NFA
/// fallback can never allocate a huge slot table, with the default lazy-DFA cache intact.
fn build_fallback_regex(patterns: &[&str]) -> Option<Regex> {
    Regex::builder()
        .syntax(syntax::Config::new().unicode(false).utf8(false).case_insensitive(true))
        .configure(Regex::config().utf8_empty(false).which_captures(WhichCaptures::None))
        .build_many(patterns)
        .ok()
}
