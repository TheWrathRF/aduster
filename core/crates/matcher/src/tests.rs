use crate::*;

fn filters(lists: &[(&str, &str)], user: &str) -> Filters {
    let srcs: Vec<Source> = lists.iter().map(|(n, t)| Source { name: n, text: t, user: false, allow: false }).collect();
    let (bytes, _) = compile(&srcs);
    let (ubytes, _) = compile(&[Source { name: "user", text: user, user: true, allow: false }]);
    Filters { lists: Table::from_bytes(bytes).unwrap(), user: Table::from_bytes(ubytes).unwrap() }
}

#[test]
fn suffix_matches_domain_and_subdomains_only() {
    let f = filters(&[("l", "||doubleclick.net^")], "");
    assert!(f.check("doubleclick.net").blocked());
    assert!(f.check("ad.g.doubleclick.net").blocked());
    assert!(!f.check("notdoubleclick.net").blocked());
    assert!(!f.check("doubleclick.net.example").blocked());
}

/// A list compiled as allow rules (Xiaomi profile Off) lifts other lists' blocks of its
/// domains, but not user blocks or important blocks.
#[test]
fn allow_source_leaves_its_domains_alone() {
    let block = Source { name: "pro", text: "||tracking.miui.com^\n||push.xiaomi.com^\n||ads.example^\n||hard.miui.com^$important", user: false, allow: false };
    let xiaomi = Source { name: "xiaomi", text: "! comment\n||tracking.miui.com^\n0.0.0.0 push.xiaomi.com\n||hard.miui.com^", user: false, allow: true };
    let (bytes, _) = compile(&[block, xiaomi]);
    let (ubytes, _) = compile(&[Source { name: "user", text: "||push.xiaomi.com^", user: true, allow: false }]);
    let f = Filters { lists: Table::from_bytes(bytes).unwrap(), user: Table::empty() };
    assert!(!f.check("tracking.miui.com").blocked());
    assert!(!f.check("push.xiaomi.com").blocked());
    assert!(f.check("ads.example").blocked());
    assert!(f.check("hard.miui.com").blocked());
    let f = Filters { lists: f.lists, user: Table::from_bytes(ubytes).unwrap() };
    assert!(f.check("push.xiaomi.com").blocked(), "the user's own block wins");
}

#[test]
fn hosts_entries_are_exact() {
    let f = filters(&[("h", "0.0.0.0 ads.example.com")], "");
    assert!(f.check("ads.example.com").blocked());
    assert!(!f.check("x.ads.example.com").blocked());
}

#[test]
fn precedence() {
    let f =
        filters(&[("a", "||example.com^\n@@||safe.example.com^\n||evil.safe.example.com^$important"), ("b", "@@||xiaomi.com^\n||tracking.xiaomi.com^")], "");
    assert!(f.check("example.com").blocked());
    assert!(!f.check("safe.example.com").blocked(), "allow beats block");
    assert!(f.check("evil.safe.example.com").blocked(), "important block beats allow");
    assert!(!f.check("tracking.xiaomi.com").blocked(), "allow on parent beats plain block");

    let f = filters(&[("a", "@@||googlevideo.com^$important\n||googlevideo.com^$important")], "||r1.googlevideo.com^");
    assert!(f.check("r1.googlevideo.com").blocked(), "user block beats important allow");
    let f = filters(&[("a", "||ads.com^$important")], "@@||ads.com^");
    assert!(!f.check("x.ads.com").blocked(), "user allow beats everything");
}

#[test]
fn regex_and_wildcards() {
    let f = filters(&[("a", "||web-visor.\n|load.gtm.\n.stape.net\n||ad*.cdn.com^\n/^[a-z]{3}\\.track\\.io$/")], "");
    assert!(f.check("web-visor.com").blocked());
    assert!(f.check("x.web-visor.org").blocked());
    assert!(!f.check("myweb-visor.com").blocked());
    assert!(f.check("load.gtm.example.com").blocked());
    assert!(!f.check("x.load.gtm.example.com").blocked());
    assert!(f.check("a.stape.net").blocked());
    assert!(f.check("ads1.cdn.com").blocked());
    assert!(!f.check("cdn.com").blocked());
    assert!(f.check("abc.track.io").blocked());
    assert!(!f.check("abcd.track.io").blocked());
}

/// `*.example.com` (the wildcard-list form) means the subdomains of `example.com`, never a name
/// that only contains it.
#[test]
fn wildcard_domain_lines_are_subdomains_only() {
    let f = filters(&[("w", "*.example.com\n*.ads.test^")], "");
    assert!(f.check("a.example.com").blocked());
    assert!(f.check("x.y.example.com").blocked());
    assert!(!f.check("example.com").blocked(), "the domain itself is not listed");
    assert!(!f.check("a.example.community").blocked());
    assert!(!f.check("a.example.com.au").blocked());
    assert!(!f.check("notexample.com").blocked());
    assert!(f.check("b.ads.test").blocked());
}

/// A regex too large to build (a list added by URL, a broken list, My rules) is left out on its
/// own: the rest of its table still loads and blocks.
#[test]
fn oversized_regex_never_sinks_its_table() {
    let big = "/(?:a{1000}){1000}/";
    let text = format!("{big}\n||ads.test^\n/^x+\\.track\\.io$/");
    let (bytes, stats) = compile(&[Source { name: "l", text: &text, user: false, allow: false }]);
    assert_eq!((stats.regex, stats.unsupported), (1, 1));
    let (ubytes, _) = compile(&[Source { name: "user", text: &format!("{big}\n@@||ok.ads.test^"), user: true, allow: false }]);
    let f = Filters { lists: Table::from_bytes(bytes).unwrap(), user: Table::from_bytes(ubytes).unwrap() };
    assert!(f.check("ads.test").blocked());
    assert!(f.check("xx.track.io").blocked());
    assert!(!f.check("ok.ads.test").blocked(), "the user's allow rule survives");
    assert_eq!(validate(&text), vec![(1, "regular expression too large")]);

    // A table written before this check (the regex inside) still loads, without that regex.
    let t = table_with_regexes(&["(?:a{1000}){1000}", r"^y+\.track\.io$"]);
    assert_eq!(t.skipped(), 1);
    let f = Filters { lists: t, user: Table::empty() };
    assert!(f.check("yy.track.io").blocked());
}

/// A table in the on-disk format holding only these regexes (block class, source 0).
fn table_with_regexes(res: &[&str]) -> Table {
    let mut b = Vec::new();
    b.extend_from_slice(b"ADMX");
    b.extend_from_slice(&1u32.to_le_bytes());
    b.extend_from_slice(&0u32.to_le_bytes());
    b.extend_from_slice(&(res.len() as u32).to_le_bytes());
    b.extend_from_slice(&1u32.to_le_bytes());
    b.resize(32, 0);
    for re in res {
        b.push(Class::Block as u8);
        b.extend_from_slice(&0u16.to_le_bytes());
        b.extend_from_slice(&(re.len() as u32).to_le_bytes());
        b.extend_from_slice(re.as_bytes());
    }
    b.extend_from_slice(&1u16.to_le_bytes());
    b.push(b'l');
    Table::from_bytes(b).unwrap()
}

#[test]
fn badfilter_disables_rule_across_lists() {
    let f = filters(&[("a", "||pl.ua^\n||keep.ua^"), ("b", "||pl.ua^$badfilter")], "");
    assert!(!f.check("pl.ua").blocked());
    assert!(f.check("keep.ua").blocked());
}

/// Every form a `$badfilter` matches by its key still meets its rule: a plain domain against
/// `||d^`, modifiers in another order, an allow rule, and a regex written with capitals (the key
/// is lowercased, the rule's regex keeps its case).
#[test]
fn badfilter_meets_its_rule_in_every_written_form() {
    let lists = [
        ("a", "plain.ua\n||mods.ua^$all,important\n@@||allow.ua^\n/^Caps\\.ua$/\n||kept.ua^$important\n  ||fast.ua^"),
        (
            "b",
            "||plain.ua^$badfilter\n||mods.ua^$important,all,badfilter\n@@||allow.ua^$badfilter\n/^Caps\\.ua$/$badfilter\n||kept.ua^$badfilter\nfast.ua$badfilter",
        ),
        ("c", "||allow.ua^"),
    ];
    let f = filters(&lists, "");
    assert!(!f.check("plain.ua").blocked());
    assert!(!f.check("mods.ua").blocked());
    // The allow rule is gone, so list c's block holds.
    assert!(f.check("allow.ua").blocked());
    assert!(!f.check("caps.ua").blocked());
    // A plain `||name^` line (read without the full parser) meets a `$badfilter` too.
    assert!(!f.check("fast.ua").blocked());
    // A `$badfilter` without `$important` does not disable the `$important` rule.
    assert!(f.check("kept.ua").blocked());
}

#[test]
fn describe_hit() {
    let f = filters(&[("hagezi", "||doubleclick.net^")], "");
    let h = f.check("ad.doubleclick.net");
    assert_eq!(f.describe("ad.doubleclick.net", &h), Some(("||doubleclick.net^".into(), "hagezi".into())));
}

#[test]
fn table_roundtrip_through_file() {
    let (bytes, stats) = compile(&[Source { name: "x", text: "||a.com^\n0.0.0.0 b.com\n/c+\\.com/", user: false, allow: false }]);
    assert_eq!((stats.hashed, stats.regex), (2, 1));
    let dir = std::env::temp_dir().join(format!("admx-test-{}", std::process::id()));
    std::fs::write(&dir, &bytes).unwrap();
    let t = Table::open(&dir).unwrap();
    let f = Filters { lists: t, user: Table::empty() };
    assert!(f.check("sub.a.com").blocked());
    assert!(f.check("b.com").blocked());
    assert!(f.check("ccc.com").blocked());
    drop(f);
    let _ = std::fs::remove_file(&dir);
}

#[test]
fn corrupt_tables_are_rejected() {
    assert!(Table::from_bytes(b"nope".to_vec()).is_err());
    let (mut bytes, _) = compile(&[Source { name: "x", text: "||a.com^", user: false, allow: false }]);
    bytes.truncate(40);
    assert!(Table::from_bytes(bytes).is_err());
}

/// Compiles the real lists when ADUSTER_LISTS points at a directory of downloaded lists.
/// `cargo test -p matcher --release real_lists -- --ignored --nocapture` (ignored without it
/// instead of passing without running).
#[test]
#[ignore = "needs ADUSTER_LISTS (a directory of DNS lists)"]
fn real_lists() {
    let dir = std::env::var("ADUSTER_LISTS").expect("ADUSTER_LISTS=<dir of DNS lists>");
    let mut texts = Vec::new();
    for e in std::fs::read_dir(&dir).unwrap() {
        let p = e.unwrap().path();
        texts.push((p.file_name().unwrap().to_string_lossy().into_owned(), std::fs::read_to_string(&p).unwrap()));
    }
    let srcs: Vec<Source> = texts.iter().map(|(n, t)| Source { name: n, text: t, user: false, allow: false }).collect();
    let t0 = std::time::Instant::now();
    let (bytes, stats) = compile(&srcs);
    let compile_ms = t0.elapsed().as_millis();
    let t1 = std::time::Instant::now();
    let table = Table::from_bytes(bytes.clone()).unwrap();
    let load_ms = t1.elapsed().as_millis();
    let f = Filters { lists: table, user: Table::empty() };
    let names = ["ad.doubleclick.net", "www.google.com", "rr3---sn-4g5e6nzz.googlevideo.com", "tracking.miui.com", "a.b.c.d.example.org"];
    let t2 = std::time::Instant::now();
    let iters = 200_000;
    for i in 0..iters {
        std::hint::black_box(f.check(names[i % names.len()]));
    }
    let ns = t2.elapsed().as_nanos() / iters as u128;
    // Distinct, never-seen names (what a real day of queries looks like).
    let fresh: Vec<String> = (0..20_000).map(|i| format!("cdn{i}.img{}.example{}.com", i % 97, i % 13)).collect();
    let t3 = std::time::Instant::now();
    for n in &fresh {
        std::hint::black_box(f.check(n));
    }
    println!("fresh-name lookup={}ns", t3.elapsed().as_nanos() / fresh.len() as u128);
    // Real host names (ADUSTER_NAMES: one per line, e.g. the hosts page-list exceptions name):
    // made-up names leave the regexes nothing to work on, real ones do. Each name twice, so the
    // second pass shows the cost once the regexes' caches are warm; the verdicts as a fingerprint.
    if let Ok(path) = std::env::var("ADUSTER_NAMES") {
        let text = std::fs::read_to_string(path).unwrap();
        let real: Vec<&str> = text.lines().filter(|l| !l.is_empty()).collect();
        for pass in 1..=2 {
            use std::hash::{Hash, Hasher};
            let t = std::time::Instant::now();
            let mut verdicts = std::collections::hash_map::DefaultHasher::new();
            for n in &real {
                let h = f.check(n);
                (h.blocked(), h.class as u8).hash(&mut verdicts);
            }
            println!(
                "real-name lookup pass {pass}: {} names, {}ns each, verdicts={:016x}",
                real.len(),
                t.elapsed().as_nanos() / real.len().max(1) as u128,
                verdicts.finish()
            );
        }
    }
    for n in names {
        let h = f.check(n);
        println!("{n:40} blocked={} {:?}", h.blocked(), f.describe(n, &h));
    }
    // The table's bytes as a fingerprint: a faster compiler must give the same table.
    let fingerprint = {
        use std::hash::{Hash, Hasher};
        let mut h = std::collections::hash_map::DefaultHasher::new();
        bytes.hash(&mut h);
        h.finish()
    };
    println!("{stats:?}\nsize={} KiB compile={compile_ms}ms load={load_ms}ms lookup={ns}ns table={fingerprint:016x}", bytes.len() / 1024);
    println!("regex_mem={} KiB", f.lists.regex_memory_usage() / 1024);
    for line in f.lists.regex_debug_summary() {
        println!("  {line}");
    }
}

/// The URL exception for Google's AI Overview request is no DNS rule (it goes to
/// the browsers' page filter, `UserRules.analyze`), and must never allow www.google.* for every app.
#[test]
fn url_exception_lines_are_not_dns_rules() {
    let line = "@@://www.google.*/async/folsrch?*&async=_basejs:*,_basecomb:*&q=$xhr";
    assert_eq!(validate(line).len(), 1, "{:?}", validate(line));
    let f = filters(&[("l", "||www.google.com^")], line);
    assert!(f.check("www.google.com").blocked());
}

/// A corrupted table whose header promises more entries than the file holds is a format error,
/// never an allocation that aborts the process at every start.
#[test]
fn a_header_with_huge_counts_is_a_format_error() {
    for (regex_n, src_n) in [(u32::MAX, 0), (0, u32::MAX), (u32::MAX, u32::MAX)] {
        let mut b = vec![0u8; table::HEADER];
        b[0..4].copy_from_slice(table::MAGIC);
        b[4..8].copy_from_slice(&table::VERSION.to_le_bytes());
        b[12..16].copy_from_slice(&regex_n.to_le_bytes());
        b[16..20].copy_from_slice(&src_n.to_le_bytes());
        assert!(matches!(Table::from_bytes(b), Err(TableError::Format(_))), "{regex_n} {src_n}");
    }
}

/// Many wildcard rules searched against diverse real-looking host names must stay fast and steady
/// on both passes (no lazy-DFA cache thrashing or fallback cliff) while deciding every name by the
/// right pattern and class, including case-insensitivity, `||` / `|` anchors, and unprefilterable
/// regexes.
#[test]
fn many_wildcards_stay_fast_and_accurate() {
    let mut rules = String::new();
    // Spread `.*` prefixes across the alphabet (like real filter lists) so multi-label host
    // names activate distinct combinations of `.*` branches and stress multi-pattern DFAs.
    let rule_prefixes = [
        "", "a", "ad", "ads", "an", "ap", "b", "be", "c", "cd", "cl", "co", "d", "da", "e", "ev", "f", "g", "i", "im", "l", "lo", "m", "me", "p", "pi", "r",
        "s", "sd", "st", "t", "ta", "te", "tr", "v", "w",
    ];
    for i in 0..400 {
        let p = rule_prefixes[i % rule_prefixes.len()];
        rules.push_str(&format!("||{p}*.host{i:03}.example.com^\n"));
    }
    rules.push_str("||ad*.host999.example.com^\n");
    for i in 0..100 {
        let p = rule_prefixes[(i * 3) % rule_prefixes.len()];
        rules.push_str(&format!("||{p}*.cdn{i:03}.site.net^\n"));
    }
    rules.push_str("||a*.cdn999.site.net^\n");
    for i in 0..50 {
        rules.push_str(&format!("||trk-{i:03}.\n"));
    }
    for i in 0..50 {
        rules.push_str(&format!("|load.gtm{i:03}.\n"));
    }
    // Overlapping literals in the same class: `foo.overlap` occurs inside `y1.foo.overlap.net`,
    // where the first rule must fail and the second must still be checked and match.
    rules.push_str("||x*foo.overlap^\n");
    rules.push_str("||y*foo.overlap.net^\n");
    // Duplicate required literal shared by two rules.
    rules.push_str("||alpha*.shared-lit.test^\n");
    rules.push_str("||beta*.shared-lit.test^\n");
    // Precedence across classes on the same wildcard shapes.
    rules.push_str("||ad*.host942.example.com^\n");
    rules.push_str("@@||ad*.host942.example.com^\n");
    rules.push_str("||ad*.host942.example.com^$important\n");
    rules.push_str("@@||a*.cdn999.site.net^\n");
    // Regexes with no usable literal (must still match via fallback).
    rules.push_str("/^[a-z]{3}\\.[0-9]{4}\\.io$/\n");
    rules.push_str("/^(a|c)\\.[0-9a-f]{16}\\.com$/\n");
    rules.push_str("/foo-alt|bar-alt/\n");
    // Raw regex with a usable required literal.
    rules.push_str("/^198\\.51\\.100\\.(1[0-9]|2[0-5])$/\n");

    let user = "@@||ad*.host999.example.com^\n||u*.only-user.test^\n";
    let f = filters(&[("list", &rules)], user);

    // Accuracy: right pattern, right class, describe() id, anchors, case-insensitivity.
    let h = f.check("ad7.host999.example.com");
    assert_eq!(h.class, Class::UserAllow);
    assert!(!h.blocked());
    let h = f.check("ad7.host002.example.com");
    assert_eq!(h.class, Class::Block);
    assert_eq!(f.describe("ad7.host002.example.com", &h), Some((r"/(?:^|\.)ad.*\.host002\.example\.com$/".into(), "list".into())));

    let h = f.check("sub.ad7.host002.example.com");
    assert_eq!(h.class, Class::Block);
    assert!(!f.check("bad7.host002.example.com").blocked(), "|| requires start or dot boundary");
    assert!(!f.check("ad7.host002.example.com.other").blocked(), "^ requires end of name");

    // Case-insensitivity across wildcard, anchored and raw regex rules.
    assert_eq!(f.check("AD7.HOST002.EXAMPLE.COM").class, Class::Block);
    assert_eq!(f.check("SUB.TRK-009.ORG").class, Class::Block);
    assert_eq!(f.check("LOAD.GTM005.SITE.COM").class, Class::Block);
    assert_eq!(f.check("ABC.1234.IO").class, Class::Block);

    // `||` vs `|` anchor semantics.
    assert!(f.check("trk-009.org").blocked());
    assert!(f.check("a.trk-009.org").blocked());
    assert!(!f.check("xtrk-009.org").blocked());
    assert!(f.check("load.gtm005.site.com").blocked());
    assert!(!f.check("sub.load.gtm005.site.com").blocked(), "| anchors at start only");

    // Overlapping and duplicate literals in the same class.
    assert!(f.check("y1.foo.overlap.net").blocked());
    assert!(!f.check("y1.foo.overlap").blocked());
    let h = f.check("beta9.shared-lit.test");
    assert_eq!(h.class, Class::Block);
    assert_eq!(f.describe("beta9.shared-lit.test", &h), Some((r"/(?:^|\.)beta.*\.shared-lit\.test$/".into(), "list".into())));

    // Highest class wins: ImportantBlock > Allow > Block, UserAllow > everything.
    let h = f.check("ad1.host942.example.com");
    assert_eq!(h.class, Class::ImportantBlock);
    assert!(h.blocked());
    let h = f.check("a9.cdn999.site.net");
    assert_eq!(h.class, Class::Allow);
    assert!(!h.blocked());
    assert_eq!(f.check("u5.only-user.test").class, Class::UserBlock);

    // Unprefilterable and raw regex rules.
    let h = f.check("abc.1234.io");
    assert_eq!(h.class, Class::Block);
    assert_eq!(f.describe("abc.1234.io", &h), Some((r"/^[a-z]{3}\.[0-9]{4}\.io$/".into(), "list".into())));
    assert!(!f.check("abcd.1234.io").blocked());
    assert!(f.check("a.0123456789abcdef.com").blocked());
    assert!(!f.check("b.0123456789abcdef.com").blocked());
    assert!(f.check("x.foo-alt.net").blocked());
    assert!(f.check("x.bar-alt.net").blocked());
    assert!(f.check("198.51.100.15").blocked());
    assert!(!f.check("198.51.100.99").blocked());

    // Diverse real-looking host names across two passes: neither pass may thrash or degrade.
    let mut state = 0x9e37_79b9_7f4a_7c15u64;
    let mut next_u64 = || {
        state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        state
    };
    let tlds = ["com", "net", "org", "io", "de", "co.uk", "jp", "ru", "fr", "dev"];
    let mut names: Vec<String> = Vec::with_capacity(4000);
    for _ in 0..4000 {
        let mut s = String::with_capacity(32);
        for label in 0..3 {
            if label > 0 {
                s.push('.');
            }
            let len = 4 + (next_u64() as usize % 6);
            for _ in 0..len {
                s.push((b'a' + (next_u64() % 26) as u8) as char);
            }
        }
        s.push('.');
        s.push_str(tlds[next_u64() as usize % tlds.len()]);
        names.push(s);
    }
    names.sort();

    let mut pass_ns = [0u128; 2];
    for (pass, slot) in pass_ns.iter_mut().enumerate() {
        let t = std::time::Instant::now();
        let mut blocked = 0usize;
        for name in &names {
            if f.check(name).blocked() {
                blocked += 1;
            }
        }
        *slot = t.elapsed().as_nanos() / names.len() as u128;
        assert_eq!(blocked, 0, "pass {}: none of the synthetic non-matching names should match", pass + 1);
    }
    // Today's combined lazy DFA thrashes and gives up on pass 2 (>100_000ns/name in release);
    // a prefiltered matcher stays well under 10_000ns/name on both passes and keeps heap small.
    assert!(pass_ns[0] < 10_000 && pass_ns[1] < 10_000, "wildcards lookup too slow: pass1={}ns/name pass2={}ns/name", pass_ns[0], pass_ns[1]);
    assert!(f.lists.regex_memory_usage() < 512 * 1024, "regex heap too large: {} KiB", f.lists.regex_memory_usage() / 1024);
}

/// Edge cases for wildcard matching, literal extraction soundness, newline handling, and Send+Sync.
#[test]
fn wildcard_and_regex_edge_cases() {
    fn assert_send_sync<T: Send + Sync>() {}
    assert_send_sync::<Table>();
    assert_send_sync::<Filters>();

    let rules = [
        // Leading and trailing wildcards, multiple wildcards.
        "||*suffix-only.test^",
        "||prefix-only.test*^",
        "||p1*mid*end.multi.test^",
        // Unanchored plain wildcard with suffix anchor and without.
        ".inner-lit.net",
        ".end-lit.net^",
        // Inline flag (?x) and optional quantifier right after a literal must never cause false negatives.
        "/(?x) s p a c e d \\. t e s t /",
        "/^fooo?\\.opt\\.test$/",
        "/^optx{0,2}\\.quant\\.test$/",
        "/^reqx{1,2}\\.quant\\.test$/",
        "/(?:a|b)\\.group-lit\\.test$/",
        // Non-wildcard regex with character class containing `)` inside parens.
        "/^(?:[)])\\.paren\\.test$/",
    ]
    .join("\n");
    let f = filters(&[("e", &rules)], "");

    assert!(f.check("anything.suffix-only.test").blocked());
    assert!(f.check("xsuffix-only.test").blocked());
    assert!(!f.check("suffix-only.test.more").blocked());

    assert!(f.check("prefix-only.test").blocked());
    assert!(f.check("a.prefix-only.test.anything").blocked());
    assert!(!f.check("xprefix-only.test").blocked());

    assert!(f.check("p1-aa-mid-bb-end.multi.test").blocked());
    assert!(f.check("x.p1midend.multi.test").blocked());
    assert!(!f.check("p1-end-mid.multi.test").blocked());
    assert!(!f.check("xp1midend.multi.test").blocked());

    assert!(f.check("a.inner-lit.net.extra").blocked());
    assert!(!f.check("inner-lit.net").blocked());
    assert!(f.check("a.end-lit.net").blocked());
    assert!(!f.check("a.end-lit.net.extra").blocked());

    assert!(f.check("spaced.test").blocked());
    assert!(f.check("foo.opt.test").blocked(), "optional 'o' must not be required by prefilter");
    assert!(f.check("fooo.opt.test").blocked());
    assert!(f.check("opt.quant.test").blocked(), "x{{0,2}} must not require 'x'");
    assert!(f.check("optxx.quant.test").blocked());
    assert!(!f.check("req.quant.test").blocked());
    assert!(f.check("reqx.quant.test").blocked());
    assert!(f.check("a.group-lit.test").blocked());
    assert!(f.check(").paren.test").blocked());

    // `.` in `.*` must not match `\n`, matching regex-automata's default dot_matches_new_line(false).
    assert!(!f.check("p1\nmid-end.multi.test").blocked());
    assert!(!f.check("a\np1midend.multi.test").blocked(), "^ does not match after newline");
    assert!(f.check("a\n.p1midend.multi.test").blocked());
    assert!(!f.check("").blocked());
    assert!(!f.check(".").blocked());

    // An oversized raw regex with an extractable literal is skipped cleanly without sinking peers.
    let t = table_with_regexes(&[r"example\.test(?:a{1000}){1000}", r"^ok\.example\.test$"]);
    assert_eq!(t.skipped(), 1);
    let f = Filters { lists: t, user: Table::empty() };
    assert!(f.check("ok.example.test").blocked());
}
