package app.aduster.filters

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PersistableBundle
import app.aduster.settings.Settings
import app.aduster.settings.SettingsStore
import app.aduster.vpn.AdusterVpnService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * List downloads and engine compiles, run as JobScheduler jobs in the main process.
 *
 * As a job the process counts as doing work: it is not frozen by the cached-app freezer, not
 * limited to the small cores, and not killed for "excessive CPU" as an empty background process.
 * Compiling here keeps the heavy work out of the always-on :core process as well.
 */
class FilterJobService : JobService() {
    /** A failure the job did not handle is logged, never the end of the app's process. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> android.util.Log.e("Aduster", "list job failed", e) })
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.jobId
        running[id] = scope.launch {
            var retry = false
            try {
                retry = FilterJobs.run(applicationContext, id, params.extras)
            } catch (e: java.io.IOException) {
                // A full disk or an unwritable file: tried again later, with the job's backoff.
                android.util.Log.w("Aduster", "list job $id: $e")
                retry = true
            } finally {
                running.remove(id)
                jobFinished(params, retry)
            }
        }
        return true
    }

    /**
     * The system wants the job gone (constraint lost, quota). A download or compile already in
     * native code finishes on its own; its result is kept, and the next run finds nothing to do.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        FilterJobs.done(params.jobId)
        return params.jobId != FilterJobs.ID_PERIODIC
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}

object FilterJobs {
    /** The daily (or user-chosen) update. */
    const val ID_PERIODIC = 1
    /** "Update now", list toggles and first downloads. */
    const val ID_NOW = 3
    /** Recompiles after a settings change (no network needed). */
    const val ID_COMPILE = 4

    private const val EXTRA_FORCE = "force"
    private const val EXTRA_WIFI_ONLY = "wifi_only"

    /** What a job of this process is doing right now (null = nothing), for the Filters screen. */
    data class Progress(val text: String, val done: Int = 0, val total: Int = 0)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private val active = ConcurrentHashMap<Int, Progress>()

    private fun publish(id: Int, p: Progress?) {
        if (p == null) active.remove(id) else active[id] = p
        _progress.value = active[ID_NOW] ?: active[ID_COMPILE] ?: active[ID_PERIODIC]
    }

    internal fun done(id: Int) = publish(id, null)

    private fun scheduler(ctx: Context) = ctx.getSystemService(JobScheduler::class.java)

    private fun builder(ctx: Context, id: Int) = JobInfo.Builder(id, ComponentName(ctx, FilterJobService::class.java))

    /** (Re)schedules the periodic update to match the settings. */
    fun schedule(ctx: Context, s: Settings) {
        val js = scheduler(ctx)
        if (!s.autoUpdate) {
            js.cancel(ID_PERIODIC)
            return
        }
        val hours = s.updateIntervalHours.coerceIn(6, 24 * 7).toLong()
        val interval = hours * 3_600_000L
        // No network constraint: for the VPN owner, some vendor builds (Xiaomi's) report the VPN
        // itself as the default network, whose UID ranges leave Aduster out, so JobScheduler never
        // considers a network constraint met while protection is on. The job checks the real network
        // itself and, without a usable one, tries again an hour later.
        val job = builder(ctx, ID_PERIODIC)
            .setPeriodic(interval, interval / 4)
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(s.updateWhileCharging)
            .setBackoffCriteria(3_600_000L, JobInfo.BACKOFF_POLICY_LINEAR)
            .setExtras(PersistableBundle().apply { putBoolean(EXTRA_WIFI_ONLY, s.updateWifiOnly) })
            // Survives reboots without starting the app to re-register it.
            .setPersisted(true)
            .build()
        // Re-registering an identical periodic job would restart its interval: only on change.
        val current = js.getPendingJob(ID_PERIODIC)
        if (current == null || current.intervalMillis != job.intervalMillis || current.requiredNetwork != null ||
            current.extras.getBoolean(EXTRA_WIFI_ONLY) != s.updateWifiOnly ||
            current.isRequireCharging != job.isRequireCharging || current.isRequireBatteryNotLow != job.isRequireBatteryNotLow
        ) {
            js.schedule(job)
        }
    }

    /**
     * A validated physical network (never the VPN), unmetered when [wifiOnly]. Aduster's own
     * sockets bypass its VPN, so this is the network the downloads really use.
     */
    fun hasNetwork(ctx: Context, wifiOnly: Boolean): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                (!wifiOnly || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }
    }

    /**
     * Jobs of this process that were asked for again while running: rescheduling a running job
     * id stops it, but its blocking download/compile would keep running detached (duplicate
     * work, clobbered progress, work outside any job). The running job runs once
     * more instead. Value: a forced re-check was asked for.
     */
    private val rerun = ConcurrentHashMap<Int, Boolean>()

    /** Starts of an "Update now" that found no network, so retries end. */
    private const val WAIT_PREFS = "filter_jobs"
    private const val GIVE_UP_AFTER_MS = 30 * 60_000L

    /**
     * The one job a settings change needs ([compileChanged]: what the engines are built from
     * changed; [fetch]: the change may need lists that are not on disk, [missing] says whether
     * it does): a fetch of the missing lists, which compiles at its end, or a compile.
     */
    internal fun forChange(compileChanged: Boolean, fetch: Boolean, missing: () -> Boolean): List<Int> = when {
        fetch && missing() -> listOf(ID_NOW)
        compileChanged || fetch -> listOf(ID_COMPILE)
        else -> emptyList()
    }

    /** Schedules what [forChange] says. */
    fun afterChange(ctx: Context, settings: Settings, compileChanged: Boolean, fetch: Boolean) {
        for (id in forChange(compileChanged, fetch) { FilterUpdater.missingLists(ctx, settings) }) {
            if (id == ID_NOW) runNow(ctx, force = false) else compile(ctx)
        }
    }

    /**
     * [force] re-checks every list now; otherwise only the lists not on disk yet are fetched (a
     * list just switched on, page filtering's lists): the others wait for the periodic update.
     */
    fun runNow(ctx: Context, force: Boolean = true) {
        if (running(ctx, ID_NOW)) {
            rerun.merge(ID_NOW, force) { a, b -> a || b }
            return
        }
        if (runningElsewhere(ctx, ID_NOW)) {
            askAgain(ctx, ID_NOW, force)
            return
        }
        ctx.getSharedPreferences(WAIT_PREFS, Context.MODE_PRIVATE).edit().remove("waiting_since").apply()
        val js = scheduler(ctx)
        // A pending forced check already covers an unforced one.
        val pending = js.getPendingJob(ID_NOW)
        val forced = force || pending?.extras?.getBoolean(EXTRA_FORCE) == true
        if (active[ID_NOW] == null) publish(ID_NOW, Progress("Starting"))
        // No network constraint (see [schedule]); without a network the job retries with backoff.
        js.schedule(
            builder(ctx, ID_NOW)
                .setExpedited(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(PersistableBundle().apply { putBoolean(EXTRA_FORCE, forced) })
                .build(),
        )
    }

    /**
     * Rebuilds whatever engine the current settings and files call for (signatures decide, so
     * an unchanged engine is not rebuilt), then tells :core which ones to load. [forceWeb]:
     * rebuild the page engine even if its inputs did not change (it failed to load).
     */
    fun compile(ctx: Context, forceWeb: Boolean = false) {
        if (running(ctx, ID_COMPILE)) {
            rerun.merge(ID_COMPILE, forceWeb) { a, b -> a || b }
            return
        }
        if (runningElsewhere(ctx, ID_COMPILE)) {
            askAgain(ctx, ID_COMPILE, forceWeb)
            return
        }
        if (active[ID_COMPILE] == null) publish(ID_COMPILE, Progress("Compiling"))
        scheduler(ctx).schedule(
            builder(ctx, ID_COMPILE).setExpedited(true)
                .setExtras(PersistableBundle().apply { putBoolean(EXTRA_FORCE, forceWeb) })
                .build(),
        )
    }

    /** Job ids whose work runs right now in this process. */
    private val executing: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** The job [id] runs right now in this process (not merely pending). */
    @Suppress("UNUSED_PARAMETER")
    private fun running(ctx: Context, id: Int): Boolean = id in executing

    /**
     * The job [id] executes in the other process right now (`:core` asks for a compile exactly
     * while the main process compiles, when it finds the list lock busy). Scheduling it
     * again would stop it, and its native compile would go on outside any job; the request is
     * left in a file instead ([askAgain]), which the running job reads before it ends.
     */
    // getPendingJobReason is Android 14+ (minSdk is 33); below it :core simply reschedules the job.
    private fun runningElsewhere(ctx: Context, id: Int): Boolean =
        android.os.Build.VERSION.SDK_INT >= 34 &&
            android.app.Application.getProcessName() != ctx.packageName &&
            scheduler(ctx).getPendingJobReason(id) == JobScheduler.PENDING_JOB_REASON_EXECUTING

    private fun againFile(ctx: Context, id: Int) = File(ctx.filesDir, "job-again-$id")

    /** A request for job [id] from another process while it runs ("1": forced). */
    private fun askAgain(ctx: Context, id: Int, force: Boolean) {
        val f = againFile(ctx, id)
        runCatching { if (force || !f.exists()) f.writeText(if (force) "1" else "0") }
    }

    /** Such a request, taken (null: none): whether it was forced. */
    private fun takeAsked(ctx: Context, id: Int): Boolean? {
        val f = againFile(ctx, id)
        if (!f.exists()) return null
        val force = runCatching { f.readText() == "1" }.getOrDefault(false)
        f.delete()
        return force
    }

    /** What was asked for while job [id] ran, in this process or the other (null: nothing). */
    private fun askedAgain(ctx: Context, id: Int): Boolean? {
        val here = rerun.remove(id)
        val there = takeAsked(ctx, id)
        return if (here == null && there == null) null else here == true || there == true
    }

    /**
     * "Update now" is tried again when every list it checked could not be downloaded (not when
     * one was held back because it shrank: the same copy again, 30 s later, would pass).
     */
    internal fun retryAfter(r: FilterUpdater.Result): Boolean = r.checked > 0 && r.unreachable == r.checked

    private fun waitingSince(ctx: Context): Long = ctx.getSharedPreferences(WAIT_PREFS, Context.MODE_PRIVATE).getLong("waiting_since", 0L)

    /**
     * Whether "Update now" goes on retrying: for 30 minutes from its first retry (the start is
     * kept until a run gets through, or gives up).
     */
    private fun keepTrying(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(WAIT_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = waitingSince(ctx).takeIf { it > 0 } ?: now.also { prefs.edit().putLong("waiting_since", it).apply() }
        if (now - since <= GIVE_UP_AFTER_MS) return true
        prefs.edit().remove("waiting_since").apply()
        return false
    }

    /** Rebuilds the engines whose inputs changed and tells :core which ones to load. */
    private fun compileNow(ctx: Context, id: Int, readSettings: suspend () -> Settings, forceWeb: Boolean) {
        publish(id, Progress("Compiling"))
        val dns = FilterUpdater.compile(ctx, readSettings)
        if (dns) AdusterVpnService.notifyFiltersChanged(ctx, dns = true, web = false)
        val web = FilterUpdater.compileWeb(ctx, readSettings, forceWeb)
        if (web) AdusterVpnService.notifyFiltersChanged(ctx, dns = false, web = true)
        FilterUpdater.noteAppVersion(ctx)
    }

    /** Runs one job; true = retry later. */
    internal suspend fun run(ctx: Context, id: Int, extras: PersistableBundle): Boolean {
        var waiting = false
        executing += id
        try {
            val readSettings: suspend () -> Settings = { SettingsStore.get(ctx).data.first() }
            var force = extras.getBoolean(EXTRA_FORCE, false)
            while (true) {
                val retry = when (id) {
                    ID_COMPILE -> {
                        compileNow(ctx, id, readSettings, force)
                        false
                    }
                    else -> {
                        val wifiOnly = id == ID_PERIODIC && extras.getBoolean(EXTRA_WIFI_ONLY, true)
                        if (!hasNetwork(ctx, wifiOnly)) {
                            if (id != ID_NOW) return true
                            // A fetch of missing lists also stands for the compile its settings
                            // change needed (a profile, a list already on disk beside it): that
                            // is done now (once, not at every retry), the download when a
                            // network comes.
                            if (!force && waitingSince(ctx) == 0L) compileNow(ctx, id, readSettings, forceWeb = false)
                            // "Update now" without a network: the screen says so while it retries,
                            // for at most 30 minutes (backoff 30 s, 1, 2, 4, 8, 16 min); then the
                            // periodic update or the next app open catches up.
                            if (!keepTrying(ctx)) return false
                            waiting = true
                            publish(id, Progress("Waiting for network"))
                            return true
                        }
                        publish(id, Progress("Starting"))
                        val check = when {
                            id == ID_PERIODIC -> FilterUpdater.Check.STALE
                            force -> FilterUpdater.Check.ALL
                            else -> FilterUpdater.Check.MISSING
                        }
                        // New DNS rules are loaded as soon as they are compiled, not after the
                        // page engine.
                        val r = FilterUpdater.update(ctx, readSettings, check, onDns = { if (it) AdusterVpnService.notifyFiltersChanged(ctx, dns = true, web = false) }) { name, done, total ->
                            publish(id, Progress(name, done, total))
                        }
                        if (r.webCompiled) AdusterVpnService.notifyFiltersChanged(ctx, dns = false, web = true)
                        FilterUpdater.noteAppVersion(ctx)
                        // Retry (with backoff) only if no list it checked came through, e.g. flaky
                        // network (lists it did not need to check do not count), and for 30
                        // minutes at most, as without a network (a dead URL is not asked forever).
                        if (id == ID_NOW && retryAfter(r)) {
                            keepTrying(ctx)
                        } else {
                            ctx.getSharedPreferences(WAIT_PREFS, Context.MODE_PRIVATE).edit().remove("waiting_since").apply()
                            false
                        }
                    }
                }
                // Asked for again while this ran (here, or from :core): once more, with what
                // was asked.
                val again = askedAgain(ctx, id) ?: return retry
                force = again
            }
        } finally {
            executing -= id
            if (!waiting) publish(id, null)
            // A request that arrived between the last check and now is not lost.
            askedAgain(ctx, id)?.let { f -> if (id == ID_COMPILE) compile(ctx, f) else if (id == ID_NOW) runNow(ctx, f) }
        }
    }
}
