use crate::Class;
use crate::rule::{Line, Pattern, badfilter_key, parse_line};
use crate::table::{HEADER, MAGIC, VERSION, build_regex, exact_hash, subdomain_hash, suffix_hash};
use std::collections::HashSet;

/// One input list. `user` marks the user's own rules, which outrank every list.
pub struct Source<'a> {
    pub name: &'a str,
    pub text: &'a str,
    pub user: bool,
    /// Every rule of this source allows instead of blocks (a list turned into "leave these
    /// domains alone", e.g. the Xiaomi profile Off).
    pub allow: bool,
}

#[derive(Debug, Default, Clone)]
pub struct CompileStats {
    pub hashed: usize,
    pub regex: usize,
    pub unsupported: usize,
    pub badfiltered: usize,
    /// Distinct entries in the compiled table (duplicates across lists merged).
    pub unique: usize,
    /// Accepted rules per source, in input order.
    pub per_source: Vec<usize>,
}

struct Entry {
    hash: u64,
    class: Class,
    src: u16,
}

/// Compiles sources into the binary table format read by [`crate::Table`].
pub fn compile(sources: &[Source]) -> (Vec<u8>, CompileStats) {
    let mut stats = CompileStats { per_source: vec![0; sources.len()], ..Default::default() };

    // Pass 1: `$badfilter` rules disable identical rules from any list.
    let mut bad: HashSet<String> = HashSet::new();
    for s in sources {
        for line in s.text.lines() {
            if line.contains("badfilter")
                && let Line::BadFilter(key) = parse_line(line)
            {
                bad.insert(key);
            }
        }
    }

    let mut entries: Vec<Entry> = Vec::new();
    let mut regexes: Vec<(String, Class, u16)> = Vec::new();
    let mut subdomains = 0u32;

    for (si, s) in sources.iter().enumerate() {
        let src = si as u16;
        let mut add = |hash: u64, class: Class, stats: &mut CompileStats| {
            entries.push(Entry { hash, class, src });
            stats.hashed += 1;
            stats.per_source[si] += 1;
        };
        for line in s.text.lines() {
            let parsed = parse_line(line);
            if !bad.is_empty() && matches!(parsed, Line::Rule(_)) && bad.contains(&badfilter_key(line)) {
                stats.badfiltered += 1;
                continue;
            }
            match parsed {
                Line::Rule(r) => {
                    let class = Class::of(r.allow || s.allow, r.important, s.user);
                    match r.pattern {
                        Pattern::Suffix(d) => add(suffix_hash(d.as_bytes()), class, &mut stats),
                        Pattern::Exact(d) => add(exact_hash(d.as_bytes()), class, &mut stats),
                        Pattern::Subdomains(d) => {
                            add(subdomain_hash(d.as_bytes()), class, &mut stats);
                            subdomains += 1;
                        }
                        Pattern::Regex(re) => {
                            // Built alone here, so one that parses but is too large to build is
                            // counted as unsupported instead of failing the whole table at load.
                            if build_regex(&[re.as_str()]).is_ok() {
                                regexes.push((re, class, src));
                                stats.regex += 1;
                                stats.per_source[si] += 1;
                            } else {
                                stats.unsupported += 1;
                            }
                        }
                    }
                }
                Line::Hosts(names) => {
                    let class = Class::of(s.allow, false, s.user);
                    for n in names {
                        add(exact_hash(n.as_bytes()), class, &mut stats);
                    }
                }
                Line::Unsupported(_) => stats.unsupported += 1,
                Line::BadFilter(_) | Line::Comment => {}
            }
        }
    }

    // Merge duplicates: one slot per hash with a mask of all classes present and the
    // source of the highest one (used for attribution in the log).
    entries.sort_unstable_by_key(|e| e.hash);
    let mut hashes: Vec<u64> = Vec::with_capacity(entries.len());
    let mut metas: Vec<u32> = Vec::with_capacity(entries.len());
    let mut best: Vec<Class> = Vec::with_capacity(entries.len());
    for e in &entries {
        let bit = 1u32 << (e.class as u32);
        if hashes.last() == Some(&e.hash) {
            let i = metas.len() - 1;
            if e.class > best[i] {
                best[i] = e.class;
                metas[i] = (metas[i] & 0xFF) | bit | ((e.src as u32) << 16);
            } else {
                metas[i] |= bit;
            }
        } else {
            hashes.push(e.hash);
            metas.push(bit | ((e.src as u32) << 16));
            best.push(e.class);
        }
    }
    regexes.sort();
    regexes.dedup_by(|a, b| a.0 == b.0 && a.1 == b.1);
    stats.unique = hashes.len() + regexes.len();

    let mut out = Vec::with_capacity(HEADER + hashes.len() * 12 + regexes.len() * 64);
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&(hashes.len() as u32).to_le_bytes());
    out.extend_from_slice(&(regexes.len() as u32).to_le_bytes());
    out.extend_from_slice(&(sources.len() as u32).to_le_bytes());
    out.extend_from_slice(&subdomains.to_le_bytes());
    out.resize(HEADER, 0);
    for h in &hashes {
        out.extend_from_slice(&h.to_le_bytes());
    }
    for m in &metas {
        out.extend_from_slice(&m.to_le_bytes());
    }
    for (re, class, src) in &regexes {
        out.push(*class as u8);
        out.extend_from_slice(&src.to_le_bytes());
        out.extend_from_slice(&(re.len() as u32).to_le_bytes());
        out.extend_from_slice(re.as_bytes());
    }
    for s in sources {
        let name = &s.name.as_bytes()[..s.name.len().min(u16::MAX as usize)];
        out.extend_from_slice(&(name.len() as u16).to_le_bytes());
        out.extend_from_slice(name);
    }
    (out, stats)
}

/// Checks user-entered rules; returns (1-based line number, reason) for each rejected line.
pub fn validate(text: &str) -> Vec<(usize, &'static str)> {
    let syntax = regex_automata::util::syntax::Config::new().unicode(false).utf8(false);
    let mut errors = Vec::new();
    for (i, line) in text.lines().enumerate() {
        match parse_line(line) {
            Line::Unsupported(why) => errors.push((i + 1, why)),
            // Reported, as it would have no effect: My rules are compiled apart from the lists.
            Line::BadFilter(_) => errors.push((i + 1, "$badfilter only works inside lists: use @@||host^ to allow a host")),
            Line::Rule(r) => {
                if let Pattern::Regex(re) = r.pattern {
                    if regex_automata::util::syntax::parse_with(&re, &syntax).is_err() {
                        errors.push((i + 1, "invalid regular expression"));
                    } else if build_regex(&[re.as_str()]).is_err() {
                        errors.push((i + 1, "regular expression too large"));
                    }
                }
            }
            _ => {}
        }
    }
    errors
}
