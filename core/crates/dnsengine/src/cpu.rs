//! Keeps the always-on DNS thread on the efficiency cluster.
//!
//! On a big.LITTLE phone the little cores report a capacity of about a fifth of the big ones.
//! DNS handling is a few microseconds per query; waking a big core for it wastes power.
//! The topology is read at runtime from `cpu_capacity` rather than hard-coded.

pub fn efficiency_cpus() -> Vec<usize> {
    let mut caps = Vec::new();
    for cpu in 0..32 {
        let path = format!("/sys/devices/system/cpu/cpu{cpu}/cpu_capacity");
        match std::fs::read_to_string(&path) {
            Ok(s) => {
                if let Ok(c) = s.trim().parse::<u32>() {
                    caps.push((cpu, c));
                }
            }
            Err(_) if cpu > 0 && caps.is_empty() => break,
            Err(_) => {}
        }
    }
    let Some(min) = caps.iter().map(|c| c.1).min() else { return Vec::new() };
    let max = caps.iter().map(|c| c.1).max().unwrap_or(min);
    if min == max {
        return Vec::new();
    }
    caps.into_iter().filter(|c| c.1 == min).map(|c| c.0).collect()
}

/// Pins the calling thread; returns the CPUs used (empty if not applicable).
pub fn pin_current_thread_to_efficiency_cores() -> Vec<usize> {
    let cpus = efficiency_cpus();
    if cpus.is_empty() {
        return cpus;
    }
    #[cfg(any(target_os = "android", target_os = "linux"))]
    unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut set);
        for &c in &cpus {
            libc::CPU_SET(c, &mut set);
        }
        if libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set) != 0 {
            log::warn!("sched_setaffinity failed: {}", std::io::Error::last_os_error());
            return Vec::new();
        }
    }
    cpus
}
