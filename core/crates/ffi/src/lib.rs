//! JNI surface for `app.aduster.core.NativeCore`. Every entry point is panic-safe: a Rust
//! panic is logged and turned into a neutral return value instead of aborting the process.
//! The methods are registered in `JNI_OnLoad`, the library's only exported symbol.

use dnsengine::runner::EngineSlot;
use dnsengine::{Config, Shared};
use jni::objects::{GlobalRef, JClass, JStaticMethodID, JString};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, jboolean, jint, jlong, jvalue};
use jni::{JNIEnv, JavaVM};
use matcher::{Filters, Source, Table};
use parking_lot::Mutex;
use serde::Deserialize;
use std::ffi::c_void;
use std::net::{IpAddr, SocketAddr};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::{Arc, OnceLock};

static VM: OnceLock<JavaVM> = OnceLock::new();
static CLASS: OnceLock<GlobalRef> = OnceLock::new();
/// `NativeCore.ownerUid`, looked up once (it is called for every query and proxy connection).
static OWNER_UID: OnceLock<JStaticMethodID> = OnceLock::new();
static SHARED: Mutex<Option<Arc<Shared>>> = Mutex::new(None);
static ENGINE: EngineSlot = EngineSlot::new();

const CLASS_NAME: &str = "app/aduster/core/NativeCore";

/// The JNI type code of a type a native method takes or returns.
trait JniType {
    const CODE: &'static str;
}
impl JniType for JString<'_> {
    const CODE: &'static str = "Ljava/lang/String;";
}
impl JniType for jni::sys::jstring {
    const CODE: &'static str = "Ljava/lang/String;";
}
impl JniType for jboolean {
    const CODE: &'static str = "Z";
}
impl JniType for jint {
    const CODE: &'static str = "I";
}
impl JniType for jlong {
    const CODE: &'static str = "J";
}
impl JniType for () {
    const CODE: &'static str = "V";
}

/// The `NativeCore` methods, registered in `JNI_OnLoad` so the library exports nothing else.
/// Each JNI signature is built from the types listed here, and the cast to a function pointer of
/// exactly those types fails to compile if a function's own differ.
macro_rules! natives {
    ($($name:literal => $f:ident($($arg:ty),*) -> $ret:ty;)*) => {
        fn natives() -> Vec<(&'static str, String, *mut c_void)> {
            vec![$((
                $name,
                format!("({}){}", <[&str]>::concat(&[$(<$arg as JniType>::CODE),*]), <$ret as JniType>::CODE),
                ($f as extern "system" fn(JNIEnv, JClass $(, $arg)*) -> $ret) as *mut c_void,
            )),*]
        }
    };
}

natives! {
    "nativeInit" => native_init(JString) -> jboolean;
    "nativeIntact" => native_intact() -> jboolean;
    "nativeStart" => native_start(jint) -> jboolean;
    "nativeStop" => native_stop() -> ();
    "nativeIsRunning" => native_is_running() -> jboolean;
    "nativeSetNetwork" => native_set_network(JString, jboolean, jboolean, jlong, JString, JString) -> ();
    "nativeCompile" => native_compile(JString, JString) -> jni::sys::jstring;
    "nativeLoadFilters" => native_load_filters(JString, JString) -> jni::sys::jstring;
    "nativeValidateRules" => native_validate_rules(JString) -> jni::sys::jstring;
    "nativeCheckDomain" => native_check_domain(JString) -> jni::sys::jstring;
    "nativeStats" => native_stats() -> jni::sys::jstring;
    "nativeBlockedToday" => native_blocked_today() -> jlong;
    "nativeLog" => native_log(jlong, jint) -> jni::sys::jstring;
    "nativeLogAfter" => native_log_after(jlong, jint) -> jni::sys::jstring;
    "nativeExplainHost" => native_explain_host(JString) -> jni::sys::jstring;
    "nativeWebLoaded" => native_web_loaded() -> jboolean;
    "nativeDiagnostics" => native_diagnostics() -> jni::sys::jstring;
    "nativeReport" => native_report() -> jni::sys::jstring;
    "nativeClearLog" => native_clear_log() -> ();
    "nativeResetStats" => native_reset_stats() -> ();
    "nativeTrimMemory" => native_trim_memory() -> ();
    "nativeUnbypass" => native_unbypass(JString) -> jboolean;
    "nativePersist" => native_persist() -> ();
    "nativeGenerateCa" => native_generate_ca() -> jni::sys::jstring;
    "nativeProxyAvailable" => native_proxy_available() -> jboolean;
    "nativeProxyStart" => native_proxy_start(JString) -> jint;
    "nativeProxyStop" => native_proxy_stop() -> ();
    "nativeCompileWeb" => native_compile_web(JString, JString, JString) -> jni::sys::jstring;
    "nativeWebDnsDigest" => native_web_dns_digest(JString, JString) -> jni::sys::jstring;
    "nativeReloadWeb" => native_reload_web(JString, JString) -> jni::sys::jstring;
}

/// Registers the native methods and looks up what the engine calls back. A method that cannot be
/// registered fails the library load (`UnsatisfiedLinkError`) instead of failing later on first use.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _: *mut c_void) -> jint {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_tag("Aduster").with_max_level(log::LevelFilter::Info));
    let Ok(mut env) = vm.get_env() else { return jni::sys::JNI_ERR };
    let Ok(cls) = env.find_class(CLASS_NAME) else { return jni::sys::JNI_ERR };
    let methods: Vec<jni::NativeMethod> =
        natives().into_iter().map(|(name, sig, fn_ptr)| jni::NativeMethod { name: name.into(), sig: sig.into(), fn_ptr }).collect();
    if let Err(e) = env.register_native_methods(&cls, &methods) {
        log::error!("registering native methods failed: {e}");
        return jni::sys::JNI_ERR;
    }
    if let Ok(id) = env.get_static_method_id(&cls, "ownerUid", "(IZJJIJJI)I") {
        let _ = OWNER_UID.set(id);
    }
    if let Ok(g) = env.new_global_ref(cls) {
        let _ = CLASS.set(g);
    }
    let _ = VM.set(vm);
    JNI_VERSION_1_6
}

/// The registered methods match `NativeCore.kt`'s `external fun` declarations: every one, by
/// name and by the signature Kotlin's types give.
#[cfg(test)]
#[test]
fn natives_match_kotlin() {
    let kt = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../../app/src/main/java/app/aduster/core/NativeCore.kt")).unwrap();
    let code = |t: &str| match t.trim() {
        "String" => "Ljava/lang/String;",
        "Boolean" => "Z",
        "Int" => "I",
        "Long" => "J",
        "" => "V",
        other => panic!("no JNI code for Kotlin type {other}"),
    };
    let mut declared: Vec<(String, String)> = kt
        .split("external fun ")
        .skip(1)
        .map(|d| {
            let (name, rest) = d.split_once('(').unwrap();
            let (params, rest) = rest.split_once(')').unwrap();
            let ret = rest.lines().next().unwrap().trim().strip_prefix(':').unwrap_or("");
            let args: String = params.split(',').filter(|p| !p.trim().is_empty()).map(|p| code(p.split_once(':').unwrap().1)).collect();
            (name.to_string(), format!("({args}){}", code(ret)))
        })
        .collect();
    let mut registered: Vec<(String, String)> = natives().into_iter().map(|(n, s, _)| (n.to_string(), s)).collect();
    declared.sort();
    registered.sort();
    assert_eq!(declared.len(), 30);
    assert_eq!(registered, declared);
}

fn guard<T>(what: &str, default: T, f: impl FnOnce() -> T) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(e) => {
            let msg = e.downcast_ref::<&str>().map(|s| s.to_string()).or_else(|| e.downcast_ref::<String>().cloned()).unwrap_or_default();
            log::error!("panic in {what}: {msg}");
            default
        }
    }
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jni::sys::jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn shared() -> Option<Arc<Shared>> {
    SHARED.lock().clone()
}

/// A resolver address from the network ("192.168.1.1", "2001:db8::1", or a link-local one with
/// its interface's scope id, "fe80::1%12"), on port 53.
fn resolver_addr(s: &str) -> Option<SocketAddr> {
    match s.split_once('%') {
        Some((ip, scope)) => Some(SocketAddr::V6(std::net::SocketAddrV6::new(ip.parse().ok()?, 53, 0, scope.parse().ok()?))),
        None => Some(SocketAddr::new(s.parse::<IpAddr>().ok()?, 53)),
    }
}

#[cfg(test)]
#[test]
fn resolver_addresses() {
    assert_eq!(resolver_addr("192.168.1.1"), Some("192.168.1.1:53".parse().unwrap()));
    assert_eq!(resolver_addr("2001:db8::1"), Some("[2001:db8::1]:53".parse().unwrap()));
    match resolver_addr("fe80::1%12") {
        Some(SocketAddr::V6(a)) => assert!(a.ip().segments()[0] == 0xfe80 && a.scope_id() == 12 && a.port() == 53),
        other => panic!("{other:?}"),
    }
    assert_eq!(resolver_addr("fe80::1%wlan0"), None);
    assert_eq!(resolver_addr("nonsense"), None);
}

/// Splits an address into (is_v6, hi, lo) so it crosses JNI without allocating.
fn ip_parts(ip: IpAddr) -> (bool, i64, i64) {
    match ip {
        IpAddr::V4(v4) => (false, 0, u32::from(v4) as i64),
        IpAddr::V6(v6) => {
            let b = u128::from(v6);
            (true, (b >> 64) as i64, b as i64)
        }
    }
}

/// Calls `NativeCore.ownerUid(...)` (ConnectivityManager.getConnectionOwnerUid) from the engine thread.
fn owner_uid(proto: u8, src: SocketAddr, dst: SocketAddr) -> i32 {
    let (Some(vm), Some(class), Some(&mid)) = (VM.get(), CLASS.get(), OWNER_UID.get()) else { return -1 };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else { return -1 };
    let (v6, sh, sl) = ip_parts(src.ip());
    let (_, dh, dl) = ip_parts(dst.ip());
    let cls = unsafe { JClass::from_raw(class.as_obj().as_raw()) };
    let args = [
        jvalue { i: proto as jint },
        jvalue { z: v6 as jboolean },
        jvalue { j: sh },
        jvalue { j: sl },
        jvalue { i: src.port() as jint },
        jvalue { j: dh },
        jvalue { j: dl },
        jvalue { i: dst.port() as jint },
    ];
    let r = unsafe { env.call_static_method_unchecked(&cls, mid, ReturnType::Primitive(Primitive::Int), &args) };
    match r.and_then(|v| v.i()) {
        Ok(uid) => uid,
        Err(_) => {
            let _ = env.exception_clear();
            -1
        }
    }
}

/// Tells the service that the DNS engine exited on its own (TUN error), so it can rebuild the
/// tunnel instead of leaving the phone without DNS.
fn notify_engine_exit() {
    let (Some(vm), Some(class)) = (VM.get(), CLASS.get()) else { return };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else { return };
    let cls = unsafe { JClass::from_raw(class.as_obj().as_raw()) };
    if env.call_static_method(&cls, "onEngineExit", "()V", &[]).is_err() {
        let _ = env.exception_clear();
    }
}

/// Creates (or reconfigures) the shared engine state. Safe to call repeatedly.
extern "system" fn native_init(mut env: JNIEnv, _: JClass, config: JString) -> jboolean {
    let json = jstr(&mut env, &config);
    guard("init", JNI_FALSE, || {
        let cfg: Config = match serde_json::from_str(&json) {
            Ok(c) => c,
            Err(e) => {
                log::error!("bad config: {e}");
                return JNI_FALSE;
            }
        };
        let mut g = SHARED.lock();
        match g.as_ref() {
            Some(s) => {
                // A pause that ends: Chrome connections opened during it are closed. Decided by
                // the config, not the clock: the end-of-pause alarm runs after the pause ran
                // out, when a clock check already says "active".
                let pause_ends = s.config.load().paused_until_ms != 0 && cfg.paused_until_ms == 0;
                s.set_config(cfg);
                if pause_ends {
                    proxy::refilter();
                }
            }
            None => {
                let s = Shared::new(cfg);
                let _ = s.uid_lookup.set(Box::new(owner_uid));
                *g = Some(s);
            }
        }
        JNI_TRUE
    })
}

/// Whether this copy may start protection; `native_start` refuses when it may not.
extern "system" fn native_intact(_env: JNIEnv, _: JClass) -> jboolean {
    guard("intact", JNI_FALSE, || if proxy::intact() { JNI_TRUE } else { JNI_FALSE })
}

/// Starts the TUN loop on its own thread. Takes ownership of `fd`.
extern "system" fn native_start(_env: JNIEnv, _: JClass, fd: jint) -> jboolean {
    guard("start", JNI_FALSE, || {
        if !proxy::intact() {
            return JNI_FALSE;
        }
        let Some(s) = shared() else { return JNI_FALSE };
        // Each run has its own stop signal: stopping a run that already died leaves no signal
        // behind that would end the next run at once.
        let body = move |stop| {
            // A panic must end up here too: otherwise the service never hears that the phone
            // just lost its DNS and keeps a tunnel nobody answers.
            match catch_unwind(AssertUnwindSafe(|| dnsengine::run(s.clone(), fd, stop))) {
                Ok(Err(e)) => log::error!("dns engine stopped: {e}"),
                Ok(Ok(())) => {}
                Err(p) => {
                    let msg = p.downcast_ref::<&str>().map(|s| s.to_string()).or_else(|| p.downcast_ref::<String>().cloned()).unwrap_or_default();
                    log::error!("dns engine panicked: {msg}");
                }
            }
        };
        let on_exit = || {
            log::warn!("dns engine exited unexpectedly");
            notify_engine_exit();
        };
        match ENGINE.start("aduster-dns", body, on_exit) {
            Ok(()) => JNI_TRUE,
            Err(_) => JNI_FALSE,
        }
    })
}

extern "system" fn native_stop(_env: JNIEnv, _: JClass) {
    guard("stop", (), || {
        ENGINE.stop();
        proxy::stop();
        if let Some(s) = shared() {
            s.persist_stats();
        }
    })
}

extern "system" fn native_is_running(_env: JNIEnv, _: JClass) -> jboolean {
    guard("running", JNI_FALSE, || if ENGINE.is_running() { JNI_TRUE } else { JNI_FALSE })
}

/// The underlying (non-VPN) network: comma-separated resolver IPs, which address families it
/// can reach, its handle, its search domains (comma-separated) and a name for the recorder
/// ("Wi-Fi", "Mobile data"). A different network also closes upstream connections made on the
/// previous one.
#[allow(clippy::too_many_arguments)]
extern "system" fn native_set_network(
    mut env: JNIEnv,
    _: JClass,
    servers: JString,
    has_v4: jboolean,
    has_v6: jboolean,
    handle: jlong,
    domains: JString,
    label: JString,
) {
    let (list, domains, label) = (jstr(&mut env, &servers), jstr(&mut env, &domains), jstr(&mut env, &label));
    guard("network", (), || {
        let Some(s) = shared() else { return };
        let addrs = list.split(',').filter_map(|x| resolver_addr(x.trim())).collect();
        s.set_network_info(&label, domains.split(',').filter(|d| !d.trim().is_empty()).map(str::to_string).collect());
        s.set_network(addrs, has_v4 != JNI_FALSE, has_v6 != JNI_FALSE, handle as u64);
    })
}

#[derive(Deserialize)]
struct SourceSpec {
    name: String,
    path: String,
    /// Compile every rule of this list as an allow rule (Xiaomi profile Off).
    #[serde(default)]
    allow: bool,
}

/// Compiles downloaded lists into a table file. Writes to `out` atomically.
/// Returns JSON: {"ok":true,"hashed":..,"regex":..,"unsupported":..,"per_source":[..]} or {"ok":false,"error":".."}.
extern "system" fn native_compile(mut env: JNIEnv, _: JClass, sources: JString, out: JString) -> jni::sys::jstring {
    let (sources, out) = (jstr(&mut env, &sources), jstr(&mut env, &out));
    let result = guard("compile", r#"{"ok":false,"error":"panic"}"#.to_string(), || compile(&sources, &out));
    to_jstring(&mut env, &result)
}

fn compile(sources: &str, out: &str) -> String {
    let specs: Vec<SourceSpec> = match serde_json::from_str(sources) {
        Ok(s) => s,
        Err(e) => return serde_json::json!({"ok": false, "error": e.to_string()}).to_string(),
    };
    // A list that cannot be read fails the compile, so the table compiled before (with that
    // list) stays in use instead of one without it.
    let mut texts: Vec<(String, String, bool)> = Vec::with_capacity(specs.len());
    for s in specs {
        match std::fs::read(&s.path) {
            Ok(bytes) => texts.push((s.name, String::from_utf8_lossy(&bytes).into_owned(), s.allow)),
            Err(e) => return serde_json::json!({"ok": false, "error": format!("{}: {e}", s.name)}).to_string(),
        }
    }
    let srcs: Vec<Source> = texts.iter().map(|(n, t, a)| Source { name: n, text: t, user: false, allow: *a }).collect();
    let (bytes, stats) = matcher::compile(&srcs);
    let tmp = format!("{out}.tmp");
    if let Err(e) = std::fs::write(&tmp, &bytes).and_then(|_| std::fs::rename(&tmp, out)) {
        return serde_json::json!({"ok": false, "error": e.to_string()}).to_string();
    }
    serde_json::json!({
        "ok": true,
        "hashed": stats.hashed,
        "regex": stats.regex,
        "unsupported": stats.unsupported,
        "badfiltered": stats.badfiltered,
        "unique": stats.unique,
        "per_source": stats.per_source,
        "bytes": bytes.len(),
    })
    .to_string()
}

/// A list file that cannot be read fails the compile and leaves the table already there
/// untouched (it compiled as an empty list before, dropping that list's rules).
#[cfg(test)]
#[test]
fn unreadable_list_fails_the_compile() {
    let dir = std::env::temp_dir().join(format!("aduster-compile-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let (good, out) = (dir.join("good.txt"), dir.join("dns.admx"));
    std::fs::write(&good, "||ads.test^\n").unwrap();
    std::fs::write(&out, b"previous").unwrap();
    let spec = |second: &str| serde_json::json!([{"name": "a", "path": good}, {"name": "b", "path": dir.join(second)}]).to_string();
    let r: serde_json::Value = serde_json::from_str(&compile(&spec("missing.txt"), out.to_str().unwrap())).unwrap();
    assert_eq!(r["ok"], false, "{r}");
    assert!(r["error"].as_str().unwrap().starts_with("b: "), "{r}");
    assert_eq!(std::fs::read(&out).unwrap(), b"previous");
    let r: serde_json::Value = serde_json::from_str(&compile(&spec("good.txt"), out.to_str().unwrap())).unwrap();
    assert_eq!(r["ok"], true, "{r}");
    let _ = std::fs::remove_dir_all(&dir);
}

/// Loads a compiled list table (memory-mapped) plus the user's rules and swaps them in.
extern "system" fn native_load_filters(mut env: JNIEnv, _: JClass, table_path: JString, user_rules: JString) -> jni::sys::jstring {
    let (path, rules) = (jstr(&mut env, &table_path), jstr(&mut env, &user_rules));
    let result = guard("load", r#"{"ok":false,"error":"panic"}"#.to_string(), || {
        let Some(s) = shared() else { return r#"{"ok":false,"error":"not initialized"}"#.to_string() };
        let lists = if path.is_empty() || !Path::new(&path).exists() {
            Table::empty()
        } else {
            match Table::open(Path::new(&path)) {
                Ok(t) => t,
                Err(e) => return serde_json::json!({"ok": false, "error": e.to_string()}).to_string(),
            }
        };
        let (ubytes, _) = matcher::compile(&[Source { name: "user", text: &rules, user: true, allow: false }]);
        let user = Table::from_bytes(ubytes).unwrap_or_else(|_| Table::empty());
        let (ln, un) = (lists.len(), user.len());
        let skipped = lists.skipped() + user.skipped();
        if skipped > 0 {
            s.event(dnsengine::health::Kind::RulesSkipped, "", 0, &format!("{skipped} regex rules could not be built and are not applied"));
        }
        s.set_filters(Filters { lists, user });
        dnsengine::release_free_memory();
        serde_json::json!({"ok": true, "list_rules": ln, "user_rules": un, "skipped": skipped}).to_string()
    });
    to_jstring(&mut env, &result)
}

/// Returns [[line, reason], ...] for rejected user rule lines.
extern "system" fn native_validate_rules(mut env: JNIEnv, _: JClass, text: JString) -> jni::sys::jstring {
    let t = jstr(&mut env, &text);
    let r = guard("validate", "[]".to_string(), || serde_json::to_string(&matcher::validate(&t)).unwrap_or_else(|_| "[]".into()));
    to_jstring(&mut env, &r)
}

/// {"blocked":bool,"rule":"..","list":".."} for the Rules screen's "test a domain".
extern "system" fn native_check_domain(mut env: JNIEnv, _: JClass, name: JString) -> jni::sys::jstring {
    let n = jstr(&mut env, &name).trim().trim_end_matches('.').to_ascii_lowercase();
    let r = guard("check", "{}".to_string(), || {
        let Some(s) = shared() else { return "{}".into() };
        let f = s.filters.load();
        let hit = f.check(&n);
        let (rule, list) = f.describe(&n, &hit).unzip();
        serde_json::json!({"blocked": hit.blocked(), "rule": rule, "list": list}).to_string()
    });
    to_jstring(&mut env, &r)
}

extern "system" fn native_stats(mut env: JNIEnv, _: JClass) -> jni::sys::jstring {
    let r = guard("stats", "{}".to_string(), || {
        let Some(s) = shared() else { return "{}".into() };
        let (filtered, untrusted) = (proxy::filtered_uids(), proxy::untrusted_uids());
        if filtered.is_empty() && untrusted.is_empty() {
            return s.stats_json();
        }
        // The browsers whose pages are filtered, and those that refuse the certificate (Home
        // names them).
        let mut v: serde_json::Value = serde_json::from_str(&s.stats_json()).unwrap_or_default();
        v["https_filtered_uids"] = serde_json::json!(filtered);
        v["https_untrusted_uids"] = serde_json::json!(untrusted);
        v.to_string()
    });
    to_jstring(&mut env, &r)
}

/// Today's blocked count for the widget: one number, no JSON.
extern "system" fn native_blocked_today(_env: JNIEnv, _: JClass) -> jlong {
    guard("blocked today", -1, || shared().map(|s| s.blocked_today() as jlong).unwrap_or(-1))
}

extern "system" fn native_log(mut env: JNIEnv, _: JClass, since: jlong, max: jint) -> jni::sys::jstring {
    let r = guard("log", "[]".to_string(), || shared().map(|s| s.log_json(since.max(0) as u64, max.max(0) as usize)).unwrap_or_else(|| "[]".into()));
    to_jstring(&mut env, &r)
}

/// The oldest `max` log entries after `since` (the UI pages forward with it).
extern "system" fn native_log_after(mut env: JNIEnv, _: JClass, since: jlong, max: jint) -> jni::sys::jstring {
    let r =
        guard("log after", "[]".to_string(), || shared().map(|s| s.log_after_json(since.max(0) as u64, max.max(0) as usize)).unwrap_or_else(|| "[]".into()));
    to_jstring(&mut env, &r)
}

/// How Chrome's connections to `host` are handled right now and why ("Check a site").
extern "system" fn native_explain_host(mut env: JNIEnv, _: JClass, host: JString) -> jni::sys::jstring {
    let h = jstr(&mut env, &host);
    let r = guard("explain", "{}".to_string(), || proxy::explain_host(&h));
    to_jstring(&mut env, &r)
}

/// Whether the running proxy has a page engine.
extern "system" fn native_web_loaded(_env: JNIEnv, _: JClass) -> jboolean {
    guard("web loaded", JNI_FALSE, || if proxy::web_loaded() { JNI_TRUE } else { JNI_FALSE })
}

extern "system" fn native_diagnostics(mut env: JNIEnv, _: JClass) -> jni::sys::jstring {
    let r = guard("diag", "{}".to_string(), || {
        let Some(s) = shared() else { return "{}".into() };
        let mut v: serde_json::Value = serde_json::from_str(&s.diagnostics_json()).unwrap_or_default();
        v["proxy"] = serde_json::from_str(&proxy::diagnostics_json()).unwrap_or_default();
        v["recent_problems"] = s.health_json(30)["events"].take();
        v.to_string()
    });
    to_jstring(&mut env, &r)
}

/// Snapshot for a stall report: everything recorded, what is waiting right now, and the state
/// of the resolver and proxy.
extern "system" fn native_report(mut env: JNIEnv, _: JClass) -> jni::sys::jstring {
    let r = guard("report", "{}".to_string(), || {
        let Some(s) = shared() else { return "{}".into() };
        serde_json::json!({
            "time_ms": dnsengine::now_ms(),
            "engine": s.report_json(),
            "proxy": serde_json::from_str::<serde_json::Value>(&proxy::diagnostics_json()).unwrap_or_default(),
            "waiting_over_5s": proxy::stuck_requests_json(),
            "problems": s.health_json(256),
        })
        .to_string()
    });
    to_jstring(&mut env, &r)
}

extern "system" fn native_clear_log(_env: JNIEnv, _: JClass) {
    guard("clear", (), || {
        if let Some(s) = shared() {
            s.clear_log();
        }
    })
}

extern "system" fn native_reset_stats(_env: JNIEnv, _: JClass) {
    guard("reset", (), || {
        if let Some(s) = shared() {
            s.reset_stats();
        }
    })
}

/// Returns freed heap memory to the kernel (screen off, memory pressure); at the same moment
/// unsaved stats are written and idle encrypted resolver connections closed.
extern "system" fn native_trim_memory(_env: JNIEnv, _: JClass) {
    guard("trim", (), || {
        if let Some(s) = shared() {
            s.screen_off();
        }
        dnsengine::release_free_memory();
    })
}

/// "Filter again" (Diagnostics): ends the bot-check bypass of `host`. True if it was bypassed.
extern "system" fn native_unbypass(mut env: JNIEnv, _: JClass, host: JString) -> jboolean {
    let h = jstr(&mut env, &host);
    guard("unbypass", JNI_FALSE, || if proxy::unbypass(&h) { JNI_TRUE } else { JNI_FALSE })
}

extern "system" fn native_persist(_env: JNIEnv, _: JClass) {
    guard("persist", (), || {
        if let Some(s) = shared() {
            s.persist_stats();
        }
    })
}

/// New filtering CA: {"cert": PEM, "key": PKCS#8 PEM}. The app encrypts the key at rest.
extern "system" fn native_generate_ca(mut env: JNIEnv, _: JClass) -> jni::sys::jstring {
    let r = guard("ca", "{}".to_string(), || match proxy::generate_ca() {
        Ok((cert, key)) => serde_json::json!({"cert": cert, "key": key}).to_string(),
        Err(e) => serde_json::json!({"error": e}).to_string(),
    });
    to_jstring(&mut env, &r)
}

/// Whether this build has the page engine.
extern "system" fn native_proxy_available(_env: JNIEnv, _: JClass) -> jboolean {
    guard("proxy available", JNI_FALSE, || if proxy::available() { JNI_TRUE } else { JNI_FALSE })
}

/// Starts the HTTPS filter proxy (or updates its settings). Returns the bound port, or -1.
extern "system" fn native_proxy_start(mut env: JNIEnv, _: JClass, config: JString) -> jint {
    let json = jstr(&mut env, &config);
    guard("proxy start", -1, || {
        let Some(s) = shared() else { return -1 };
        let cfg: proxy::ProxyConfig = match serde_json::from_str(&json) {
            Ok(c) => c,
            Err(e) => {
                log::error!("bad proxy config: {e}");
                return -1;
            }
        };
        match proxy::start(s, cfg) {
            Ok(port) => port as jint,
            Err(e) => {
                log::error!("proxy start failed: {e}");
                -1
            }
        }
    })
}

extern "system" fn native_proxy_stop(_env: JNIEnv, _: JClass) {
    guard("proxy stop", (), proxy::stop)
}

extern "system" fn native_compile_web(mut env: JNIEnv, _: JClass, sources: JString, out: JString, dns_table: JString) -> jni::sys::jstring {
    let (sources, out, dns) = (jstr(&mut env, &sources), jstr(&mut env, &out), jstr(&mut env, &dns_table));
    let r = guard("compile web", r#"{"ok":false,"error":"panic"}"#.to_string(), || proxy::compile_web(&sources, &out, &dns));
    to_jstring(&mut env, &r)
}

/// What the page engine depends on in the DNS table, as a short string (see `proxy::web_dns_digest`).
extern "system" fn native_web_dns_digest(mut env: JNIEnv, _: JClass, dat: JString, dns_table: JString) -> jni::sys::jstring {
    let (dat, dns) = (jstr(&mut env, &dat), jstr(&mut env, &dns_table));
    let r = guard("web dns digest", "error".to_string(), || proxy::web_dns_digest(&dat, &dns));
    to_jstring(&mut env, &r)
}

extern "system" fn native_reload_web(mut env: JNIEnv, _: JClass, dat: JString, resources: JString) -> jni::sys::jstring {
    let (dat, res) = (jstr(&mut env, &dat), jstr(&mut env, &resources));
    let r = guard("reload web", r#"{"ok":false}"#.to_string(), || match proxy::reload_web(&dat, &res) {
        Ok(n) => serde_json::json!({"ok": true, "rules": n}).to_string(),
        Err(e) => serde_json::json!({"ok": false, "error": e}).to_string(),
    });
    to_jstring(&mut env, &r)
}
