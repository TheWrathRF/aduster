//! Time that keeps running while the phone sleeps.
//!
//! `std::time::Instant` and Tokio's timers use CLOCK_MONOTONIC, which stops during suspend. Every
//! decision about how long something has been idle (NAT mappings, DNS TTLs, resolver
//! connections) must count the time the screen was off too, or a connection that died during
//! ten minutes of sleep looks a few seconds old after wake-up. CLOCK_BOOTTIME includes suspend.

/// Milliseconds since boot, suspend included.
pub fn boot_ms() -> u64 {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
        // Cannot fail for CLOCK_BOOTTIME with a valid pointer.
        unsafe { libc::clock_gettime(libc::CLOCK_BOOTTIME, &mut ts) };
        ts.tv_sec as u64 * 1000 + ts.tv_nsec as u64 / 1_000_000
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        // Host test builds: a monotonic clock is all there is (and nothing suspends).
        static EPOCH: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();
        EPOCH.get_or_init(std::time::Instant::now).elapsed().as_millis() as u64 + 1
    }
}

/// Milliseconds of boot time since `since` (0 if the clock reads earlier).
pub fn since_ms(since: u64) -> u64 {
    boot_ms().saturating_sub(since)
}
