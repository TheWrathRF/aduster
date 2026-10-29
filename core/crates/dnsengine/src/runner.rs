//! One engine run at a time, each with its own stop signal.
//!
//! With one `Notify` shared by every run, stopping a run whose thread had already ended (the
//! engine exited on its own, then the service restarted it) would store a permit nobody
//! consumes, and the *next* run would take it on its first poll and stop at once: an engine
//! that cannot be restarted, or a loop of VPN rebuilds. So a run owns its signal and its
//! "stopped on purpose" flag, and a stop is sent only to a run that is still alive.

use parking_lot::Mutex;
use std::io;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use tokio::sync::Notify;

struct Run {
    handle: JoinHandle<()>,
    stop: Arc<Notify>,
    stopping: Arc<AtomicBool>,
}

/// Holds the current run (thread + its stop signal).
pub struct EngineSlot {
    cur: Mutex<Option<Run>>,
}

impl Default for EngineSlot {
    fn default() -> Self {
        Self::new()
    }
}

impl EngineSlot {
    pub const fn new() -> EngineSlot {
        EngineSlot { cur: Mutex::new(None) }
    }

    /// Stops the current run (if any), then runs `body` on a new thread named `name`. `body`
    /// gets this run's stop signal. When `body` returns without having been asked to stop,
    /// `on_unexpected_exit` runs on that thread (the service then rebuilds the tunnel).
    pub fn start<B, E>(&self, name: &str, body: B, on_unexpected_exit: E) -> io::Result<()>
    where
        B: FnOnce(Arc<Notify>) + Send + 'static,
        E: FnOnce() + Send + 'static,
    {
        self.stop();
        let stop = Arc::new(Notify::new());
        let stopping = Arc::new(AtomicBool::new(false));
        let (s, st) = (stop.clone(), stopping.clone());
        let handle = std::thread::Builder::new().name(name.into()).spawn(move || {
            body(s);
            if !st.load(Ordering::Acquire) {
                on_unexpected_exit();
            }
        })?;
        *self.cur.lock() = Some(Run { handle, stop, stopping });
        Ok(())
    }

    /// Stops the current run and waits for its thread. A run that already ended is only
    /// joined: no signal is left behind for anyone.
    pub fn stop(&self) {
        let Some(run) = self.cur.lock().take() else { return };
        run.stopping.store(true, Ordering::Release);
        if !run.handle.is_finished() {
            // notify_one stores a permit if the loop has not polled yet: a stop sent right
            // after the start is not lost. The permit belongs to this run only.
            run.stop.notify_one();
        }
        let _ = run.handle.join();
    }

    /// True while the current run's thread is alive.
    pub fn is_running(&self) -> bool {
        self.cur.lock().as_ref().is_some_and(|r| !r.handle.is_finished())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicUsize;
    use std::time::{Duration, Instant};

    /// A body that runs until its own stop signal (like the TUN loop).
    fn until_stopped(delay_ms: u64) -> impl FnOnce(Arc<Notify>) + Send + 'static {
        move |stop| {
            let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
            rt.block_on(async {
                tokio::time::sleep(Duration::from_millis(delay_ms)).await;
                stop.notified().await;
            });
        }
    }

    fn wait_until(f: impl Fn() -> bool) -> bool {
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(3) {
            if f() {
                return true;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        false
    }

    /// After a run exits on its own, the restarted run keeps running until its own stop,
    /// and only the unexpected exit is reported.
    #[test]
    fn restart_after_unexpected_exit_stays_up() {
        let slot = EngineSlot::new();
        let exits = Arc::new(AtomicUsize::new(0));
        let e = exits.clone();
        slot.start(
            "t1",
            |_stop| {},
            move || {
                e.fetch_add(1, Ordering::SeqCst);
            },
        )
        .unwrap();
        assert!(wait_until(|| exits.load(Ordering::SeqCst) == 1 && !slot.is_running()));
        // The service restarts the engine (start stops the dead run first).
        let e = exits.clone();
        slot.start("t2", until_stopped(0), move || {
            e.fetch_add(1, Ordering::SeqCst);
        })
        .unwrap();
        std::thread::sleep(Duration::from_millis(200));
        assert!(slot.is_running(), "the new run must not take a stale stop");
        assert_eq!(exits.load(Ordering::SeqCst), 1);
        slot.stop();
        assert!(!slot.is_running());
        assert_eq!(exits.load(Ordering::SeqCst), 1, "a stop on purpose is not an unexpected exit");
    }

    /// A stop sent before the loop first waits on its signal still stops it.
    #[test]
    fn stop_before_first_poll_still_stops() {
        let slot = EngineSlot::new();
        let exits = Arc::new(AtomicUsize::new(0));
        let e = exits.clone();
        slot.start("t3", until_stopped(150), move || {
            e.fetch_add(1, Ordering::SeqCst);
        })
        .unwrap();
        let t0 = Instant::now();
        slot.stop();
        assert!(t0.elapsed() < Duration::from_secs(2));
        assert!(!slot.is_running());
        assert_eq!(exits.load(Ordering::SeqCst), 0);
        // Stopping again (nothing running) is a no-op.
        slot.stop();
    }
}
