package app.aduster;

// Binder surface of the :core process, used by the UI process while it is visible.
interface ICore {
    boolean isRunning();
    String stats();
    String log(long since, int max);
    /** The oldest `max` entries after `since` (the Log pages forward, so no entry is skipped). */
    String logAfter(long since, int max);
    /** When this :core process started (elapsed realtime): a change means it restarted. */
    long startedAt();
    /** How browsers' connections to `host` are handled right now and why (for "Check a site"). */
    String explainHost(String host);
    String diagnostics();
    String checkDomain(String name);
    void clearLog();
    void resetStats();
    /** Saves a stall report to Downloads; returns the file name or null. */
    String saveReport();
    /** "Filter again": ends the bot-check bypass of a host (Diagnostics "bypassed_hosts"). */
    boolean unbypass(String host);
    /** Share to Aduster: a site is not filtered for some minutes; false when page filtering is not running. */
    boolean pauseSite(String host, int minutes);
}
