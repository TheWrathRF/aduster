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
    for n in names {
        let h = f.check(n);
        println!("{n:40} blocked={} {:?}", h.blocked(), f.describe(n, &h));
    }
    println!("{stats:?}\nsize={} KiB compile={compile_ms}ms load={load_ms}ms lookup={ns}ns", bytes.len() / 1024);
}

/// The URL exception for Google's AI Overview request is no DNS rule (it goes to
/// Chrome's page filter, `UserRules.analyze`), and must never allow www.google.* for every app.
#[test]
fn url_exception_lines_are_not_dns_rules() {
    let line = "@@://www.google.*/async/folsrch?*&async=_basejs:*,_basecomb:*&q=$xhr";
    assert_eq!(validate(line).len(), 1, "{:?}", validate(line));
    let f = filters(&[("l", "||www.google.com^")], line);
    assert!(f.check("www.google.com").blocked());
}
