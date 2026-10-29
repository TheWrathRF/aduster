//! Shared TLS client setup for encrypted DNS upstreams (the page engine, private, follows the
//! same rules).
//!
//! Trust comes from the device's own system store (the Conscrypt APEX on Android 14+, which
//! Google updates via Mainline), falling back to the bundled Mozilla roots elsewhere. User-added
//! CAs (including Aduster's own filtering CA) are deliberately not trusted for upstream traffic,
//! and system roots the user turned off in Android's settings are not either.

use rustls::crypto::CryptoProvider;
use rustls::{ClientConfig, RootCertStore};
use rustls_pki_types::CertificateDer;
use rustls_pki_types::pem::PemObject;
use std::sync::{Arc, OnceLock};

const SYSTEM_CA_DIRS: &[&str] = &["/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"];
/// Where Android keeps the system roots the user disabled (same file names), per Android user:
/// a work profile or a second user has its own, not user 0's.
fn removed_dir() -> &'static std::path::Path {
    static D: OnceLock<std::path::PathBuf> = OnceLock::new();
    D.get_or_init(|| {
        #[cfg(unix)]
        let user = unsafe { libc::getuid() } / 100_000;
        #[cfg(not(unix))]
        let user = 0;
        std::path::PathBuf::from(format!("/data/misc/user/{user}/cacerts-removed"))
    })
}

/// True if the user disabled the system root stored as `file_name` (if Aduster may not read
/// the directory, nothing counts as disabled).
pub fn user_disabled(file_name: &std::ffi::OsStr) -> bool {
    removed_dir().join(file_name).exists()
}

pub fn provider() -> Arc<CryptoProvider> {
    static P: OnceLock<Arc<CryptoProvider>> = OnceLock::new();
    P.get_or_init(|| {
        let p = Arc::new(rustls::crypto::ring::default_provider());
        let _ = CryptoProvider::install_default((*p).clone());
        p
    })
    .clone()
}

pub fn roots() -> Arc<RootCertStore> {
    static R: OnceLock<Arc<RootCertStore>> = OnceLock::new();
    R.get_or_init(|| {
        let mut store = RootCertStore::empty();
        for dir in SYSTEM_CA_DIRS {
            let Ok(entries) = std::fs::read_dir(dir) else { continue };
            for e in entries.flatten() {
                if user_disabled(&e.file_name()) {
                    continue;
                }
                if let Ok(certs) = CertificateDer::pem_file_iter(e.path()) {
                    for c in certs.flatten() {
                        let _ = store.add(c);
                    }
                }
            }
            if !store.is_empty() {
                log::info!("loaded {} system roots from {dir}", store.len());
                break;
            }
        }
        if store.is_empty() {
            store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        }
        Arc::new(store)
    })
    .clone()
}

pub fn client_config(alpn: &[&[u8]]) -> Arc<ClientConfig> {
    let mut c = ClientConfig::builder_with_provider(provider())
        .with_safe_default_protocol_versions()
        .expect("ring supports the default protocol versions")
        .with_root_certificates(roots())
        .with_no_client_auth();
    c.alpn_protocols = alpn.iter().map(|p| p.to_vec()).collect();
    Arc::new(c)
}
