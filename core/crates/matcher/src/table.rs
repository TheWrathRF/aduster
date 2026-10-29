//! On-disk / in-memory table format and lookup.
//!
//! Layout (little endian):
//! ```text
//!  0  magic "ADMX"
//!  4  version u32
//!  8  n        u32   number of hashed entries
//! 12  regex_n  u32
//! 16  src_n    u32
//! 20  sub_n    u32   how many hashes are subdomain-only (`*.example.com`); 0 in older tables
//! 24  reserved [u8; 8]
//! 32  hashes   [u64; n]   sorted ascending
//!  .  metas    [u32; n]   bits 0..8 class mask, bits 16..32 source of the highest class
//!  .  regex    regex_n x { class u8, src u16, len u32, bytes }
//!  .  sources  src_n   x { len u16, bytes }
//! ```

use crate::{Class, Hit, HitKind};
use memmap2::Mmap;
use regex_automata::Input;
use regex_automata::meta::Regex;
use std::fs::File;
use std::path::Path;
use xxhash_rust::xxh3::{xxh3_64, xxh3_64_with_seed};

pub(crate) const MAGIC: &[u8; 4] = b"ADMX";
pub(crate) const VERSION: u32 = 1;
pub(crate) const HEADER: usize = 32;
pub(crate) const EXACT_SEED: u64 = 0x9E37_79B9_7F4A_7C15;
pub(crate) const SUBDOMAIN_SEED: u64 = 0xC2B2_AE3D_27D4_EB4F;

pub(crate) fn suffix_hash(s: &[u8]) -> u64 {
    xxh3_64(s)
}

pub(crate) fn exact_hash(s: &[u8]) -> u64 {
    xxh3_64_with_seed(s, EXACT_SEED)
}

pub(crate) fn subdomain_hash(s: &[u8]) -> u64 {
    xxh3_64_with_seed(s, SUBDOMAIN_SEED)
}

#[derive(Debug)]
pub enum TableError {
    Io(std::io::Error),
    Format(&'static str),
    Regex(String),
}

impl std::fmt::Display for TableError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TableError::Io(e) => write!(f, "io: {e}"),
            TableError::Format(e) => write!(f, "format: {e}"),
            TableError::Regex(e) => write!(f, "regex: {e}"),
        }
    }
}

impl std::error::Error for TableError {}

enum Data {
    Empty,
    Mapped(Mmap),
    Owned(Vec<u8>),
}

impl Data {
    fn bytes(&self) -> &[u8] {
        match self {
            Data::Empty => &[],
            Data::Mapped(m) => m,
            Data::Owned(v) => v,
        }
    }
}

pub struct Table {
    data: Data,
    n: usize,
    metas_off: usize,
    /// Subdomain-only hashes are present: only then is each parent of a name looked up a
    /// second time.
    subdomains: bool,
    /// One regex per rule class, highest class first (a class whose patterns cannot be built
    /// together has one regex per pattern, in a row). Each maps its pattern ids back to the
    /// global regex index. A name only needs the best class that matches, so the search stops
    /// at the first class with a match and never needs overlapping (all-matches) search, which
    /// is what made unseen names slow (lazy DFA cache thrash across hundreds of patterns).
    regex: Vec<(Class, Regex, Vec<u32>)>,
    regex_meta: Vec<(Class, u16)>,
    regex_patterns: Vec<String>,
    /// Patterns left out because they could not be built at all.
    skipped: usize,
    sources: Vec<String>,
}

impl Table {
    pub fn empty() -> Table {
        Table {
            data: Data::Empty,
            n: 0,
            metas_off: HEADER,
            subdomains: false,
            regex: Vec::new(),
            regex_meta: Vec::new(),
            regex_patterns: Vec::new(),
            skipped: 0,
            sources: Vec::new(),
        }
    }

    /// Memory-maps a compiled table. The file must not be modified while mapped;
    /// updates are written to a new file and swapped in atomically.
    pub fn open(path: &Path) -> Result<Table, TableError> {
        let file = File::open(path).map_err(TableError::Io)?;
        // SAFETY: the file is private to the app and replaced via rename, never written in place.
        let map = unsafe { Mmap::map(&file) }.map_err(TableError::Io)?;
        #[cfg(unix)]
        let _ = map.advise(memmap2::Advice::Random);
        Table::from_data(Data::Mapped(map))
    }

    pub fn from_bytes(bytes: Vec<u8>) -> Result<Table, TableError> {
        Table::from_data(Data::Owned(bytes))
    }

    fn from_data(data: Data) -> Result<Table, TableError> {
        let b = data.bytes();
        if b.len() < HEADER || &b[0..4] != MAGIC {
            return Err(TableError::Format("bad magic"));
        }
        if rd_u32(b, 4) != VERSION {
            return Err(TableError::Format("unsupported version"));
        }
        let n = rd_u32(b, 8) as usize;
        let regex_n = rd_u32(b, 12) as usize;
        let src_n = rd_u32(b, 16) as usize;
        let subdomains = rd_u32(b, 20) > 0;
        let metas_off = HEADER + n * 8;
        let mut off = metas_off + n * 4;
        if off > b.len() {
            return Err(TableError::Format("truncated hash section"));
        }

        let mut regex_meta = Vec::with_capacity(regex_n);
        let mut regex_patterns = Vec::with_capacity(regex_n);
        for _ in 0..regex_n {
            if off + 7 > b.len() {
                return Err(TableError::Format("truncated regex section"));
            }
            let class = Class::from_u8(b[off]);
            let src = u16::from_le_bytes([b[off + 1], b[off + 2]]);
            let len = rd_u32(b, off + 3) as usize;
            off += 7;
            let s = b.get(off..off + len).ok_or(TableError::Format("truncated regex"))?;
            regex_patterns.push(String::from_utf8_lossy(s).into_owned());
            regex_meta.push((class, src));
            off += len;
        }
        let mut sources = Vec::with_capacity(src_n);
        for _ in 0..src_n {
            if off + 2 > b.len() {
                return Err(TableError::Format("truncated sources"));
            }
            let len = u16::from_le_bytes([b[off], b[off + 1]]) as usize;
            off += 2;
            let s = b.get(off..off + len).ok_or(TableError::Format("truncated source"))?;
            sources.push(String::from_utf8_lossy(s).into_owned());
            off += len;
        }

        let mut classes: Vec<Class> = regex_meta.iter().map(|m| m.0).collect();
        classes.sort_unstable_by(|a, b| b.cmp(a));
        classes.dedup();
        let mut regex = Vec::with_capacity(classes.len());
        let mut skipped = 0;
        for class in classes {
            let ids: Vec<u32> = (0..regex_meta.len() as u32).filter(|&i| regex_meta[i as usize].0 == class).collect();
            let pats: Vec<&str> = ids.iter().map(|&i| regex_patterns[i as usize].as_str()).collect();
            match build_regex(&pats) {
                Ok(re) => regex.push((class, re, ids)),
                // One pattern too large (or all of them together) must not take every other
                // rule of the table down with it: build them one by one and leave out the
                // ones that fail.
                Err(_) => {
                    for (&id, pat) in ids.iter().zip(&pats) {
                        match build_regex(&[pat]) {
                            Ok(re) => regex.push((class, re, vec![id])),
                            Err(_) => skipped += 1,
                        }
                    }
                }
            }
        }

        Ok(Table { data, n, metas_off, subdomains, regex, regex_meta, regex_patterns, skipped, sources })
    }

    pub fn len(&self) -> usize {
        self.n + self.regex_meta.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Regex rules of the table that could not be built and are not applied.
    pub fn skipped(&self) -> usize {
        self.skipped
    }

    pub fn source_name(&self, i: u16) -> Option<&str> {
        self.sources.get(i as usize).map(String::as_str)
    }

    pub fn regex_pattern(&self, i: u32) -> Option<&str> {
        self.regex_patterns.get(i as usize).map(String::as_str)
    }

    #[inline]
    fn hash_at(&self, b: &[u8], i: usize) -> u64 {
        let o = HEADER + i * 8;
        u64::from_le_bytes(b[o..o + 8].try_into().unwrap())
    }

    #[inline]
    fn find(&self, h: u64) -> Option<u32> {
        let b = self.data.bytes();
        let (mut lo, mut hi) = (0usize, self.n);
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            let v = self.hash_at(b, mid);
            if v < h {
                lo = mid + 1;
            } else if v > h {
                hi = mid;
            } else {
                return Some(rd_u32(b, self.metas_off + mid * 4));
            }
        }
        None
    }

    /// Raises `best` if any rule in this table outranks it.
    pub fn check(&self, name: &str, table_id: u8, best: &mut Hit) {
        let bytes = name.as_bytes();
        if self.n > 0 {
            if let Some(meta) = self.find(exact_hash(bytes)) {
                consider(meta, table_id, HitKind::Exact, best);
            }
            let mut start = 0usize;
            loop {
                if let Some(meta) = self.find(suffix_hash(&bytes[start..])) {
                    consider(meta, table_id, HitKind::Suffix(start), best);
                }
                if self.subdomains
                    && start > 0
                    && let Some(meta) = self.find(subdomain_hash(&bytes[start..]))
                {
                    consider(meta, table_id, HitKind::Subdomain(start), best);
                }
                match bytes[start..].iter().position(|&c| c == b'.') {
                    Some(p) => start += p + 1,
                    None => break,
                }
            }
        }
        for (class, re, ids) in &self.regex {
            if *class <= best.class {
                break;
            }
            if let Some(m) = re.search(&Input::new(bytes).earliest(true)) {
                let id = ids[m.pattern().as_usize()];
                *best = Hit { class: *class, src: self.regex_meta[id as usize].1, table: table_id, kind: HitKind::Regex(id) };
                break;
            }
        }
    }
}

#[inline]
fn consider(meta: u32, table_id: u8, kind: HitKind, best: &mut Hit) {
    let mask = (meta & 0xFF) as u8;
    if mask == 0 {
        return;
    }
    let class = Class::from_u8(7 - mask.leading_zeros() as u8);
    if class > best.class {
        *best = Hit { class, src: (meta >> 16) as u16, table: table_id, kind };
    }
}

#[inline]
fn rd_u32(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes(b[o..o + 4].try_into().unwrap())
}

pub(crate) fn build_regex(patterns: &[&str]) -> Result<Regex, TableError> {
    Regex::builder()
        .syntax(regex_automata::util::syntax::Config::new().unicode(false).utf8(false).case_insensitive(true))
        .configure(Regex::config().utf8_empty(false))
        .build_many(patterns)
        .map_err(|e| TableError::Regex(e.to_string()))
}
