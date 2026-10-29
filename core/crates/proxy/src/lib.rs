//! Stand-in for the HTTPS page engine, which is not part of the public source.
//!
//! It has the engine's public API, so the app's native layer builds unchanged, and it never
//! starts: the app runs as a DNS blocker and shows HTTPS filtering as unavailable. Release builds
//! replace this crate with the engine.

use dnsengine::Shared;
use serde::Deserialize;
use std::sync::Arc;

const UNAVAILABLE: &str = "HTTPS page filtering is not part of this build";

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct ProxyConfig {
    pub port: u16,
    pub ca_cert_pem: String,
    pub ca_key_pem: String,
    /// UIDs whose HTTPS is filtered.
    pub filter_uids: Vec<i32>,
    /// Domain suffixes never decrypted.
    pub exclusions: Vec<String>,
    pub web_engine: String,
    pub resources: String,
}

/// Whether this build has the page engine (the app hides page filtering when it has not).
pub fn available() -> bool {
    false
}

/// Whether this copy may start protection (always, without the page engine).
pub fn intact() -> bool {
    true
}

/// Generates a new CA. Returns (certificate PEM, PKCS#8 private key PEM).
pub fn generate_ca() -> Result<(String, String), String> {
    Err(UNAVAILABLE.into())
}

/// Everything that decides how a browser's connections to `host` are handled right now.
pub fn explain_host(host: &str) -> String {
    let _ = host;
    serde_json::json!({"proxy": false}).to_string()
}

/// Starts (or reconfigures) the proxy. Returns the port actually bound.
pub fn start(shared: Arc<Shared>, cfg: ProxyConfig) -> Result<u16, String> {
    let _ = (shared, cfg);
    Err(UNAVAILABLE.into())
}

pub fn stop() {}

pub fn is_running() -> bool {
    false
}

/// Whether the running proxy has a page engine.
pub fn web_loaded() -> bool {
    false
}

/// The browsers (UIDs) whose connections are tunnelled because they refuse our certificate.
pub fn untrusted_uids() -> Vec<i32> {
    Vec::new()
}

/// The browsers (UIDs) whose pages have been filtered since the proxy started.
pub fn filtered_uids() -> Vec<i32> {
    Vec::new()
}

/// Filtering was switched back on (pause ended).
pub fn refilter() {}

/// Ends the bot-check bypass of `host`. True if it was bypassed.
pub fn unbypass(host: &str) -> bool {
    let _ = host;
    false
}

/// Hot-swaps the page filtering engine (after a list update).
pub fn reload_web(dat: &str, resources: &str) -> Result<usize, String> {
    let _ = (dat, resources);
    Ok(0)
}

/// Compiles web lists into a serialized engine.
pub fn compile_web(spec_json: &str, out: &str, dns_table: &str) -> String {
    let _ = (spec_json, out, dns_table);
    serde_json::json!({"ok": false, "error": UNAVAILABLE}).to_string()
}

/// Fingerprint of what the compiled page engine `dat` depends on in the DNS table.
pub fn web_dns_digest(dat: &str, dns_table: &str) -> String {
    let _ = (dat, dns_table);
    String::new()
}

pub fn diagnostics_json() -> String {
    serde_json::json!({"running": false, "error": UNAVAILABLE}).to_string()
}

/// Requests that have been waiting for response headers for more than 5 s.
pub fn stuck_requests_json() -> serde_json::Value {
    serde_json::Value::Array(Vec::new())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn never_starts() {
        assert!(generate_ca().is_err());
        assert!(!is_running() && !web_loaded() && untrusted_uids().is_empty() && filtered_uids().is_empty());
        let compiled: serde_json::Value = serde_json::from_str(&compile_web("[]", "", "")).unwrap();
        assert_eq!(compiled["ok"], false);
        let cfg: ProxyConfig = serde_json::from_str(r#"{"port": 8080, "filter_uids": [10123]}"#).unwrap();
        assert_eq!(cfg.port, 8080);
    }
}
