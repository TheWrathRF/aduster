//! Hostname matcher for DNS-level blocking.
//!
//! Filter lists are compiled once (on update, in the background) into a flat binary table:
//! a sorted array of 64-bit xxh3 hashes of every rule domain plus a parallel metadata array.
//! At runtime the file is memory-mapped, so loading costs no parsing and the pages are clean,
//! shareable and reclaimable by the kernel. A lookup hashes each suffix of the queried name
//! (usually 2-5) and binary-searches for it; the few non-domain rules go through one
//! multi-pattern regex.

mod compile;
pub mod rule;
mod table;

pub use compile::{CompileStats, Source, compile, validate};
pub use table::{Table, TableError};

/// Rule classes in increasing precedence. The highest class matching a name decides:
/// user rules beat everything, `$important` beats plain, allow beats block at the same level.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u8)]
pub enum Class {
    None = 0,
    Block = 1,
    Allow = 2,
    ImportantBlock = 3,
    ImportantAllow = 4,
    UserBlock = 5,
    UserAllow = 6,
}

impl Class {
    pub fn from_u8(v: u8) -> Class {
        match v {
            1 => Class::Block,
            2 => Class::Allow,
            3 => Class::ImportantBlock,
            4 => Class::ImportantAllow,
            5 => Class::UserBlock,
            6 => Class::UserAllow,
            _ => Class::None,
        }
    }

    pub fn of(allow: bool, important: bool, user: bool) -> Class {
        match (user, important, allow) {
            (true, _, true) => Class::UserAllow,
            (true, _, false) => Class::UserBlock,
            (false, true, true) => Class::ImportantAllow,
            (false, true, false) => Class::ImportantBlock,
            (false, false, true) => Class::Allow,
            (false, false, false) => Class::Block,
        }
    }

    pub fn is_block(self) -> bool {
        matches!(self, Class::Block | Class::ImportantBlock | Class::UserBlock)
    }

    /// An allow rule matched the name itself: its CNAME chain is not checked either (as AdGuard
    /// Home does: an allowlisted name is not CNAME-filtered).
    pub fn is_allow(self) -> bool {
        matches!(self, Class::Allow | Class::ImportantAllow | Class::UserAllow)
    }
}

/// What matched, for the query log ("why was this blocked?").
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HitKind {
    None,
    /// Suffix rule; the value is the byte offset in the queried name where the rule domain starts.
    Suffix(usize),
    /// Subdomain-only rule (`*.example.com`); the offset where the rule domain starts.
    Subdomain(usize),
    Exact,
    Regex(u32),
}

#[derive(Debug, Clone, Copy)]
pub struct Hit {
    pub class: Class,
    /// Source index inside the table that produced the hit.
    pub src: u16,
    /// 0 = compiled lists, 1 = user rules.
    pub table: u8,
    pub kind: HitKind,
}

impl Hit {
    pub const NONE: Hit = Hit { class: Class::None, src: 0, table: 0, kind: HitKind::None };

    pub fn blocked(&self) -> bool {
        self.class.is_block()
    }
}

/// Compiled filter lists plus the (small, instantly recompiled) user rules.
pub struct Filters {
    pub lists: Table,
    pub user: Table,
}

impl Filters {
    pub fn empty() -> Filters {
        Filters { lists: Table::empty(), user: Table::empty() }
    }

    /// `name` must be lowercase ASCII without a trailing dot.
    pub fn check(&self, name: &str) -> Hit {
        let mut hit = Hit::NONE;
        self.user.check(name, 1, &mut hit);
        // User rules outrank every list class, so a user hit is final.
        if hit.class < Class::UserBlock {
            self.lists.check(name, 0, &mut hit);
        }
        hit
    }

    /// Human-readable rule and source for a hit, e.g. ("||doubleclick.net^", "hagezi-pro").
    pub fn describe(&self, name: &str, hit: &Hit) -> Option<(String, String)> {
        let table = if hit.table == 1 { &self.user } else { &self.lists };
        let rule = match hit.kind {
            HitKind::None => return None,
            HitKind::Suffix(off) => format!("||{}^", name.get(off..)?),
            HitKind::Subdomain(off) => format!("*.{}", name.get(off..)?),
            HitKind::Exact => format!("|{}^|", name),
            HitKind::Regex(i) => format!("/{}/", table.regex_pattern(i)?),
        };
        let rule = if matches!(hit.class, Class::Allow | Class::ImportantAllow | Class::UserAllow) { format!("@@{rule}") } else { rule };
        Some((rule, table.source_name(hit.src).unwrap_or("?").to_string()))
    }
}

#[cfg(test)]
mod tests;
