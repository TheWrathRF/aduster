//! Parsing of individual filter-list lines into hostname-level DNS rules.
//!
//! Supported inputs: AdGuard/ABP network rules that can be decided from a hostname alone,
//! hosts files, and plain domain lists. Anything that needs a URL, a request type or a
//! client context (`$third-party`, `$client`, `$dnsrewrite`, cosmetic rules...) is skipped:
//! applying it at DNS level would over-block.

/// How a rule matches a hostname.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Pattern {
    /// `||example.com^` and plain `example.com`: the domain and all of its subdomains.
    Suffix(String),
    /// Hosts-file entries and `|example.com^|`: this exact name only.
    Exact(String),
    /// `*.example.com` (wildcard lists): the subdomains, not the domain itself.
    Subdomains(String),
    /// Everything else, compiled to a regex over the hostname.
    Regex(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    pub pattern: Pattern,
    pub allow: bool,
    pub important: bool,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Line {
    Rule(Rule),
    /// Hosts lines can carry several names.
    Hosts(Vec<String>),
    /// A `$badfilter` rule; carries the normalized text of the rule it disables.
    BadFilter(String),
    Comment,
    Unsupported(&'static str),
}

pub fn parse_line(raw: &str) -> Line {
    let line = raw.trim();
    if line.is_empty() || line.starts_with('!') || line.starts_with('[') {
        return Line::Comment;
    }
    if line.starts_with('#') && !line.starts_with("##") {
        return Line::Comment;
    }
    if !line.is_ascii() {
        return Line::Unsupported("non-ASCII rule");
    }
    if is_cosmetic(line) {
        return Line::Unsupported("cosmetic rule");
    }
    if let Some(hosts) = parse_hosts(line) {
        return hosts;
    }

    let (allow, body) = match line.strip_prefix("@@") {
        Some(rest) => (true, rest),
        None => (false, line),
    };
    let (pattern_text, modifiers) = split_modifiers(body);

    let mut important = false;
    let mut badfilter = false;
    if let Some(mods) = modifiers {
        for m in mods.split(',') {
            match m.trim().to_ascii_lowercase().as_str() {
                "important" => important = true,
                "badfilter" => badfilter = true,
                // `$all` means every request type, which is what blocking the name does.
                // (`$popup` and `$document` are not: they block only pages opened from or at the
                // host, so at DNS level they would block the host for every app. Such rules
                // are left to Chrome's page filter.)
                "all" => {}
                "" => {}
                _ => return Line::Unsupported("modifier not applicable at DNS level"),
            }
        }
    }

    if badfilter {
        return Line::BadFilter(normalize_without_badfilter(line));
    }

    // A regex keeps its text (lowercasing would turn `\D` into `\d`); it is matched
    // case-insensitively anyway.
    let is_regex = pattern_text.len() > 2 && pattern_text.starts_with('/') && pattern_text.ends_with('/');
    let text = if is_regex { pattern_text.to_string() } else { pattern_text.to_ascii_lowercase() };
    match parse_pattern(&text) {
        Some(Pattern::Regex(re)) if is_regex && url_only(&re) => Line::Unsupported("regex needs a URL, not a hostname"),
        Some(pattern) => Line::Rule(Rule { pattern, allow, important }),
        None => Line::Unsupported("pattern is not hostname-level"),
    }
}

/// A list regex that can only match a URL (a scheme, a path, a port, a fragment), never a host
/// name: left out of the per-name regex set (11 of AdGuard DNS's 23 regexes would otherwise be
/// tried against every new name).
fn url_only(re: &str) -> bool {
    if re.contains('/') || re.contains('#') {
        return true;
    }
    // A ':' that is not regex group syntax (`(?:`, `(?i:`) stands for a port or a scheme.
    let b = re.as_bytes();
    b.iter().enumerate().any(|(i, &c)| {
        c == b':' && {
            let open = b[..i].iter().rposition(|&x| x == b'(');
            !open.is_some_and(|o| b.get(o + 1) == Some(&b'?') && b[o + 2..i].iter().all(|x| x.is_ascii_alphabetic() || *x == b'-'))
        }
    })
}

fn is_cosmetic(line: &str) -> bool {
    ["##", "#@#", "#?#", "#$#", "#%#", "#@$#", "#@%#", "$$", "$@$"].iter().any(|m| line.contains(m))
}

fn parse_hosts(line: &str) -> Option<Line> {
    let mut parts = line.split_whitespace();
    let first = parts.next()?;
    let is_sink = matches!(first, "0.0.0.0" | "127.0.0.1" | "::" | "::0" | "::1" | "0:0:0:0:0:0:0:0");
    if !is_sink {
        return None;
    }
    let mut names = Vec::new();
    for p in parts {
        if p.starts_with('#') {
            break;
        }
        let n = p.trim_end_matches('.').to_ascii_lowercase();
        if matches!(n.as_str(), "localhost" | "localhost.localdomain" | "local" | "broadcasthost" | "ip6-localhost" | "ip6-loopback" | "0.0.0.0")
            || !is_domain(&n)
        {
            continue;
        }
        names.push(n);
    }
    Some(if names.is_empty() { Line::Comment } else { Line::Hosts(names) })
}

/// Splits `pattern$mods`. Regex rules may contain `$`, so for them the modifier
/// separator must directly follow the closing slash.
fn split_modifiers(body: &str) -> (&str, Option<&str>) {
    if body.starts_with('/') {
        if let Some(end) = body.rfind('/')
            && end > 0
        {
            let rest = &body[end + 1..];
            if let Some(mods) = rest.strip_prefix('$') {
                return (&body[..=end], Some(mods));
            }
            return (body, None);
        }
        return (body, None);
    }
    match body.rfind('$') {
        Some(i) => (&body[..i], Some(&body[i + 1..])),
        None => (body, None),
    }
}

fn normalize_without_badfilter(line: &str) -> String {
    badfilter_key(line)
}

/// Normalized key used to match a rule line against collected `$badfilter` entries (and to make
/// a `$badfilter` entry's own key): modifiers sorted (without `badfilter`), and a domain pattern
/// written in its canonical form, so `x.com`, `||x.com^` and `||x.com^$b,a` vs `$a,b` all meet
/// (a textual match would miss them).
pub fn badfilter_key(line: &str) -> String {
    let lower = line.trim().to_ascii_lowercase();
    let (allow, body) = match lower.strip_prefix("@@") {
        Some(rest) => ("@@", rest),
        None => ("", lower.as_str()),
    };
    let (pat, mods) = split_modifiers(body);
    let mut kept: Vec<&str> = mods.map(|m| m.split(',').map(str::trim).filter(|m| !m.is_empty() && *m != "badfilter").collect()).unwrap_or_default();
    kept.sort_unstable();
    kept.dedup();
    let pat = match parse_pattern(pat) {
        Some(Pattern::Suffix(d)) => format!("||{d}^"),
        Some(Pattern::Exact(d)) => format!("|{d}^|"),
        Some(Pattern::Subdomains(d)) => format!("*.{d}"),
        _ => pat.to_string(),
    };
    if kept.is_empty() { format!("{allow}{pat}") } else { format!("{allow}{pat}${}", kept.join(",")) }
}

pub fn is_domain_char(b: u8) -> bool {
    b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'.' || b == b'_'
}

pub fn is_domain(s: &str) -> bool {
    !s.is_empty() && s.len() <= 253 && !s.starts_with('.') && !s.ends_with('.') && !s.contains("..") && s.bytes().all(is_domain_char)
}

fn parse_pattern(p: &str) -> Option<Pattern> {
    if p.len() > 2 && p.starts_with('/') && p.ends_with('/') {
        let inner = &p[1..p.len() - 1];
        return Some(Pattern::Regex(inner.to_string()));
    }
    // URL-level rules (paths, schemes, ports, queries) cannot be decided from a hostname.
    if p.bytes().any(|b| matches!(b, b'/' | b':' | b'?' | b'=' | b'&')) {
        return None;
    }

    // `||domain^`, `||domain^|`, `||domain|`: domain and subdomains.
    if let Some(rest) = p.strip_prefix("||") {
        let d = rest.strip_suffix("^|").or_else(|| rest.strip_suffix('^')).or_else(|| rest.strip_suffix('|'));
        if let Some(d) = d
            && is_domain(d)
        {
            return Some(Pattern::Suffix(d.to_string()));
        }
        return to_regex(p);
    }
    // `|domain^|` / `|domain^`: exact name.
    if let Some(rest) = p.strip_prefix('|') {
        let d = rest.strip_suffix("^|").or_else(|| rest.strip_suffix('^')).or_else(|| rest.strip_suffix('|'));
        if let Some(d) = d
            && is_domain(d)
        {
            return Some(Pattern::Exact(d.to_string()));
        }
        return to_regex(p);
    }
    // Plain `example.com` / `example.com^` in a domain list: domain and subdomains.
    let plain = p.strip_suffix('^').unwrap_or(p);
    if is_domain(plain) && plain.contains('.') {
        return Some(Pattern::Suffix(plain.to_string()));
    }
    // `*.example.com` / `*.example.com^`: as a regex it would be unanchored and also match
    // `a.example.community`.
    if let Some(d) = plain.strip_prefix("*.")
        && is_domain(d)
        && d.contains('.')
    {
        return Some(Pattern::Subdomains(d.to_string()));
    }
    to_regex(p)
}

/// Converts an ABP-style pattern (`*`, `^`, `|`, `||`) into a regex over the bare hostname.
fn to_regex(p: &str) -> Option<Pattern> {
    let mut out = String::with_capacity(p.len() + 16);
    let mut body = p;
    if let Some(rest) = body.strip_prefix("||") {
        out.push_str(r"(?:^|\.)");
        body = rest;
    } else if let Some(rest) = body.strip_prefix('|') {
        out.push('^');
        body = rest;
    }
    let (body, anchored_end) = match body.strip_suffix('|') {
        Some(b) => (b, true),
        None => (body, false),
    };
    if body.is_empty() || body.bytes().all(|b| b == b'*' || b == b'^' || b == b'.') {
        // Would match everything; never take such a rule at DNS level.
        return None;
    }
    let bytes = body.as_bytes();
    for (i, &b) in bytes.iter().enumerate() {
        match b {
            b'*' => out.push_str(".*"),
            b'^' => {
                if i == bytes.len() - 1 {
                    out.push('$');
                } else {
                    out.push_str(r"(?:[^a-z0-9._%-]|$)");
                }
            }
            b'.' => out.push_str(r"\."),
            b if b.is_ascii_alphanumeric() || b == b'-' || b == b'_' => out.push(b as char),
            _ => return None,
        }
    }
    if anchored_end && !out.ends_with('$') {
        out.push('$');
    }
    Some(Pattern::Regex(out))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(line: &str) -> Rule {
        match parse_line(line) {
            Line::Rule(r) => r,
            other => panic!("{line}: {other:?}"),
        }
    }

    #[test]
    fn suffix_forms() {
        assert_eq!(rule("||ads.example.com^").pattern, Pattern::Suffix("ads.example.com".into()));
        assert_eq!(rule("||Ads.Example.com^|").pattern, Pattern::Suffix("ads.example.com".into()));
        assert_eq!(rule("tracker.net").pattern, Pattern::Suffix("tracker.net".into()));
        assert_eq!(rule("*.Tracker.net^").pattern, Pattern::Subdomains("tracker.net".into()));
        assert_eq!(badfilter_key("*.tracker.net$badfilter"), badfilter_key("*.tracker.net^"));
        let r = rule("@@||cdn.taboola.com^$important");
        assert!(r.allow && r.important);
    }

    #[test]
    fn exact_and_hosts() {
        assert_eq!(rule("|load.example.com^|").pattern, Pattern::Exact("load.example.com".into()));
        assert_eq!(parse_line("0.0.0.0 a.com b.com # c"), Line::Hosts(vec!["a.com".into(), "b.com".into()]));
        assert_eq!(parse_line("127.0.0.1 localhost"), Line::Comment);
    }

    #[test]
    fn regex_forms() {
        assert_eq!(rule("||web-visor.").pattern, Pattern::Regex(r"(?:^|\.)web-visor\.".into()));
        assert_eq!(rule("|load.gtm.").pattern, Pattern::Regex(r"^load\.gtm\.".into()));
        assert_eq!(rule(".stape.net").pattern, Pattern::Regex(r"\.stape\.net".into()));
        assert_eq!(rule("||ad*.example.com^").pattern, Pattern::Regex(r"(?:^|\.)ad.*\.example\.com$".into()));
        assert_eq!(rule(r"/^88\.208\.22\.[1234]$/").pattern, Pattern::Regex(r"^88\.208\.22\.[1234]$".into()));
        let r = rule(r"/^a\.[0-9a-f]{56}\.com$/$important");
        assert!(r.important);
    }

    #[test]
    fn skipped() {
        assert!(matches!(parse_line("||example.com^$third-party"), Line::Unsupported(_)));
        assert!(matches!(parse_line("||example.com/ads/"), Line::Unsupported(_)));
        assert!(matches!(parse_line("example.com##.ad"), Line::Unsupported(_)));
        assert!(matches!(parse_line("||x^$dnsrewrite=1.2.3.4"), Line::Unsupported(_)));
        // Page-only options are not widened to a DNS block of the whole host.
        assert!(matches!(parse_line("||ads.example^$popup"), Line::Unsupported(_)));
        assert!(matches!(parse_line("||ads.example^$document"), Line::Unsupported(_)));
        assert!(matches!(parse_line("||ads.example^$all"), Line::Rule(_)));
        assert!(matches!(parse_line("*"), Line::Unsupported(_)));
        assert_eq!(parse_line("! comment"), Line::Comment);
        assert_eq!(parse_line("[Adblock Plus]"), Line::Comment);
    }

    #[test]
    fn badfilter() {
        assert_eq!(parse_line("||pl.ua^$badfilter"), Line::BadFilter("||pl.ua^".into()));
        assert_eq!(parse_line("||x.com^$important,badfilter"), Line::BadFilter("||x.com^$important".into()));
        assert_eq!(parse_line("wykop.pl$badfilter"), Line::BadFilter("||wykop.pl^".into()));
        // Modifier order and the plain form of a domain do not matter.
        assert_eq!(badfilter_key("||a.com^$important,all"), badfilter_key("||A.com^$all,important"));
        assert_eq!(badfilter_key("wykop.pl"), badfilter_key("||wykop.pl^"));
        assert_ne!(badfilter_key("@@||a.com^"), badfilter_key("||a.com^"));
    }

    /// A regex keeps its case (`\D` is not `\d`); URL-only regexes are left out.
    #[test]
    fn regex_text_and_url_only_regexes() {
        assert_eq!(rule(r"/^a\D+\.test$/").pattern, Pattern::Regex(r"^a\D+\.test$".into()));
        assert!(matches!(parse_line(r"/(https?:\/\/)213\.32\.115\..{100,}/"), Line::Unsupported(_)));
        assert!(matches!(parse_line(r"/193.200.64.24:/"), Line::Unsupported(_)));
        assert!(matches!(parse_line(r"@@/\.(gif|jpe?g)#x/"), Line::Unsupported(_)));
        // Group syntax is not a port.
        assert!(matches!(parse_line(r"/^(?:www\.)?ads\.(?i:test)$/"), Line::Rule(_)));
    }
}
