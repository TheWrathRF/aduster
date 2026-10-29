package app.aduster.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.security.KeyChain
import android.service.quicksettings.TileService
import android.util.Log
import app.aduster.ICore
import app.aduster.core.EngineConfig
import app.aduster.core.NativeCore
import app.aduster.filters.FilterStore
import app.aduster.filters.FilterUpdater
import app.aduster.https.CaManager
import app.aduster.https.HttpsConfig
import app.aduster.settings.Settings
import app.aduster.settings.SettingsStore
import app.aduster.tile.ProtectionTileService
import app.aduster.ui.MainActivity
import app.aduster.widget.StatsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.TimeZone
import kotlinx.serialization.json.putJsonArray

internal enum class PauseAtStart { NONE, STILL_PAUSED, ENDED }

/**
 * What a start does with the pause saved before it ([over]: a timed pause's end has passed). A
 * pause until Resume, or a timed one still running, is "still paused" in the timeline; a timed
 * one that ran out while protection was stopped is cleared without an entry (as "still paused"
 * it would show as an open pause in Diagnostics).
 */
internal fun pauseAtStart(pausedUntil: Long, over: Boolean): PauseAtStart = when {
    pausedUntil == 0L -> PauseAtStart.NONE
    pausedUntil != Long.MAX_VALUE && over -> PauseAtStart.ENDED
    else -> PauseAtStart.STILL_PAUSED
}

/**
 * Runs in the :core process. Owns the TUN device and the Rust engine; everything that changes
 * protection state runs on one serial dispatcher, so start/stop/reconfigure never race.
 */
class AdusterVpnService : VpnService() {

    companion object {
        private const val TAG = "Aduster"
        const val ACTION_START = "app.aduster.action.START"
        const val ACTION_STOP = "app.aduster.action.STOP"
        const val ACTION_PAUSE = "app.aduster.action.PAUSE"
        const val ACTION_RESUME = "app.aduster.action.RESUME"
        const val ACTION_RENOTIFY = "app.aduster.action.RENOTIFY"
        const val ACTION_BIND = "app.aduster.action.BIND"
        const val ACTION_SETTINGS_CHANGED = "app.aduster.action.SETTINGS_CHANGED"
        const val ACTION_FILTERS_CHANGED = "app.aduster.action.FILTERS_CHANGED"
        /** PAUSE: minutes, or 0 / absent for a pause that lasts until Resume. */
        const val EXTRA_MINUTES = "minutes"
        /** START: who turned protection on (for the timeline). */
        const val EXTRA_FROM = "from"
        /** RESUME: sent by the end-of-pause alarm, not by the user. */
        private const val EXTRA_AUTO = "auto"
        /** FILTERS_CHANGED: which engines were rebuilt (both when absent). */
        const val EXTRA_DNS = "dns"
        const val EXTRA_WEB = "web"

        /** Valid inside the :core process only. */
        @Volatile
        var running = false
            private set

        /**
         * Current pause end (unix ms; 0 = not paused, [Long.MAX_VALUE] = until Resume), for the
         * tile in the same process.
         */
        @Volatile
        var pausedUntil = 0L
            private set

        fun isPaused() = running && pausedUntil > System.currentTimeMillis()

        /** How often a screen-on may double-check that the certificate is still trusted. */
        private const val CERT_RECHECK_MS = 30 * 60 * 1000L
        /**
         * A card first posted longer ago than this is replaced by a new one, never updated:
         * Xiaomi's SystemUI folds an update of a card created over 6 h ago.
         */
        private const val CARD_RENEW_MS = 5 * 60 * 60 * 1000L

        /** At most this many tunnel rebuilds after DNS engine exits within the window. */
        private const val ENGINE_REBUILDS_MAX = 3
        private const val ENGINE_REBUILD_WINDOW_MS = 60_000L

        /** [from]: who asked, as the timeline shows it ("from the app", "at boot", …). */
        fun start(ctx: Context, from: String = "from the app") {
            ctx.startForegroundService(Intent(ctx, AdusterVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_FROM, from))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AdusterVpnService::class.java).setAction(ACTION_STOP))
        }

        /** Pauses filtering for [minutes], or until [resume] when 0. */
        fun pause(ctx: Context, minutes: Int = 0) {
            ctx.startService(Intent(ctx, AdusterVpnService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_MINUTES, minutes))
        }

        fun resume(ctx: Context) {
            ctx.startService(Intent(ctx, AdusterVpnService::class.java).setAction(ACTION_RESUME))
        }

        fun notifySettingsChanged(ctx: Context) {
            ctx.sendBroadcast(Intent(ACTION_SETTINGS_CHANGED).setPackage(ctx.packageName))
        }

        /** Tells :core which compiled engines changed, so only those are loaded again. */
        fun notifyFiltersChanged(ctx: Context, dns: Boolean, web: Boolean) {
            if (!dns && !web) return
            ctx.sendBroadcast(Intent(ACTION_FILTERS_CHANGED).setPackage(ctx.packageName).putExtra(EXTRA_DNS, dns).putExtra(EXTRA_WEB, web))
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val store by lazy { SettingsStore.get(this) }

    private var settings = Settings()
    /** Settings read and the engine configured (stats, Home's counts): cheap, done at creation. */
    private var engineReady = false
    /** Filter tables installed and loaded: only when protection starts or a check needs them. */
    private var initialized = false
    /** The newest start id: a stop ends the service only if no command arrived after it. */
    @Volatile
    private var latestStartId = 0
    /**
     * Boot-clock end of a timed pause set in this process: a wall-clock change
     * neither shortens nor lengthens it. 0 = none known (after a restart the wall time decides).
     */
    private var pauseDeadlineBoot = 0L
    /** Why page filtering is not running (Home's chip): on, off, unavailable, no_browser, ca_untrusted, ca_missing, ca_key_unreadable, ca_mismatch, start_failed. */
    @Volatile
    private var httpsState = "off"
    private var tunSignature: String? = null
    private var monitor: NetworkMonitor? = null
    /** Port of the browsers' HTTPS filter, 0 when it is off. */
    private var proxyPort = 0
    /** What the running proxy was started with; unchanged config means no native call. */
    private var proxySignature: String? = null
    /** Whether our CA is trusted; null = not checked yet. Invalidated by trust-store changes. */
    private var caTrusted: Boolean? = null
    private var caCheckedAt = 0L
    /** "Block connections without VPN" is on for us (see [Notifications.showLockdown]). */
    @Volatile
    private var lockdown = false
    /** UTC offset (ms) the engine's "today" was last configured with. */
    @Volatile
    private var tzOffset = Int.MIN_VALUE
    /** Boot-clock times of the recent tunnel rebuilds after a DNS engine exit (serial dispatcher only). */
    private val engineRebuilds = ArrayDeque<Long>()
    /** The card's current id ([Notifications.ID_PROTECTION] or its alternate) and when it was first posted (boot clock, 0 = none). */
    private var cardId = Notifications.ID_PROTECTION
    private var cardBornAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SETTINGS_CHANGED -> scope.launch { reloadSettings() }
                ACTION_FILTERS_CHANGED -> {
                    val dns = intent.getBooleanExtra(EXTRA_DNS, true)
                    val web = intent.getBooleanExtra(EXTRA_WEB, true)
                    scope.launch {
                        if (initialized) loadFilters(dns, web)
                        // A rebuilt page engine may be the first one (certificate just trusted).
                        if (web && running) reconfigure()
                    }
                }
                // A user CA was added, removed or disabled: re-check ours (browsers would otherwise
                // show certificate errors everywhere) and start or stop page filtering to match.
                KeyChain.ACTION_TRUST_STORE_CHANGED -> scope.launch {
                    caTrusted = null
                    if (running) reconfigure()
                    // Browser lists are built only once the certificate is trusted:
                    // the first trust fetches and compiles them (a job in the main process).
                    if (settings.httpsFiltering && CaManager.isInstalled(this@AdusterVpnService)) {
                        if (FilterUpdater.missingLists(this@AdusterVpnService, settings)) app.aduster.filters.FilterJobs.runNow(this@AdusterVpnService, force = false)
                        else app.aduster.filters.FilterJobs.compile(this@AdusterVpnService)
                    }
                }
                // Browsing leaves freed buffers resident; give them back while the phone idles.
                Intent.ACTION_SCREEN_OFF -> NativeCore.nativeTrimMemory()
                Intent.ACTION_TIMEZONE_CHANGED -> scope.launch { if (engineReady) initEngine() }
                // The widget refreshes only when someone can actually see it. The trust-store
                // broadcast is the main signal; a rare, cheap alias check here is the safety net.
                Intent.ACTION_SCREEN_ON -> {
                    // A timed pause that ran out while the screen was off: the engine already
                    // filters again (it compares times), the card and the timeline catch up now
                    // instead of when the inexact alarm runs. No new wakeup.
                    if (running && pausedUntil != 0L && pausedUntil != Long.MAX_VALUE && pauseOver()) scope.launch { resume(auto = true) }
                    // "Today" (stats, widget) is the local day: a new UTC offset (summer time
                    // starts or ends without a time-zone broadcast) reaches the engine first.
                    if (engineReady && TimeZone.getDefault().getOffset(System.currentTimeMillis()) != tzOffset) scope.launch { initEngine() }
                    StatsWidget.update(context)
                    // The card can vanish while the protection runs on: Xiaomi's Android build
                    // removes an app's notifications when it cleans its other (UI) process in the
                    // background, and folds any card older than 6 h out of sight. One look when someone can see
                    // the screen puts it back or renews it (no timer, no wakeup).
                    if (running) ensureProtectionNotification()
                    if (settings.httpsFiltering && running && SystemClock.elapsedRealtime() - caCheckedAt > CERT_RECHECK_MS) {
                        scope.launch {
                            caCheckedAt = SystemClock.elapsedRealtime()
                            if (CaManager.stillInstalled(this@AdusterVpnService) != caTrusted) {
                                caTrusted = null
                                reconfigure()
                            }
                        }
                    }
                }
            }
        }
    }

    private val binder = object : ICore.Stub() {
        override fun isRunning() = running
        // Whether page filtering really runs (setting on and certificate trusted), and the system
        // settings that undo filtering, ride along (Home's status strip).
        override fun stats(): String {
            var s = withField(NativeCore.nativeStats(), "https_active", "${proxyPort > 0}")
            s = withField(s, "https_state", "\"$httpsState\"")
            // The browsers' names, for the UIDs the engine reports as filtered or refusing.
            if (proxyPort > 0) s = withField(s, "https_browsers", browserNames())
            // One binder call for Home and Diagnostics: the running state rides along.
            s = withField(s, "running", "$running")
            return withField(withField(s, "private_dns_strict", "${monitor?.privateDnsStrict ?: false}"), "lockdown", "$lockdown")
        }
        override fun log(since: Long, max: Int): String = NativeCore.nativeLog(since, max)
        override fun logAfter(since: Long, max: Int): String = NativeCore.nativeLogAfter(since, max)
        override fun startedAt(): Long = android.os.Process.getStartElapsedRealtime()
        // Splice in the Kotlin-side facts the engine cannot see.
        override fun diagnostics(): String =
            withField(withField(NativeCore.nativeDiagnostics(), "private_dns_strict", "${monitor?.privateDnsStrict ?: false}"), "lockdown", "$lockdown")
        // While protection is off the lists are loaded for the check (and not on every app
        // open); the answer says whether the engine runs.
        override fun checkDomain(name: String): String = runBlocking(serial) {
            initialize()
            withField(NativeCore.nativeCheckDomain(name), "running", "$running")
        }
        override fun explainHost(host: String): String = runBlocking(serial) {
            initialize()
            withField(withField(NativeCore.nativeExplainHost(host), "running", "$running"), "https_state", "\"$httpsState\"")
        }
        override fun clearLog() = NativeCore.nativeClearLog()
        override fun resetStats() = NativeCore.nativeResetStats()
        override fun saveReport(): String? = Reports.save(this@AdusterVpnService)
        override fun unbypass(host: String): Boolean = NativeCore.nativeUnbypass(host)
    }

    /** Adds one raw JSON field to an object string (the engine returns "{}" before it starts). */
    private fun withField(json: String, key: String, raw: String): String {
        val body = json.trim().removePrefix("{").removeSuffix("}").trim()
        return if (body.isEmpty()) "{\"$key\":$raw}" else "{$body,\"$key\":$raw}"
    }

    /** The browsers as a JSON object of UID to name. */
    private fun browserNames(): String = buildJsonObject {
        HttpsConfig.browsers(this@AdusterVpnService).forEach { put(it.uid.toString(), it.name) }
    }.toString()

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        NativeCore.connectivity = getSystemService(ConnectivityManager::class.java)
        val filter = IntentFilter().apply {
            addAction(ACTION_SETTINGS_CHANGED)
            addAction(ACTION_FILTERS_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(KeyChain.ACTION_TRUST_STORE_CHANGED)
        }
        // Not exported: only our own and protected system broadcasts get in.
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        // A browser installed, enabled, updated or removed: the proxy's UID set follows at once,
        // without a tunnel rebuild (the port does not change).
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, RECEIVER_NOT_EXPORTED)
        // The DNS thread exited on its own (see NativeCore.onEngineExit).
        NativeCore.engineExitListener = {
            // The DNS thread died (TUN error): without it no app can resolve names. Rebuild.
            scope.launch {
                if (!running) return@launch
                // Revoked (the user, or another VPN took the slot): the engine ended because its
                // tunnel did. onRevoke stops protection; a rebuild here would race it.
                if (prepare(this@AdusterVpnService) != null) return@launch
                // A rebuild re-establishes the tunnel (AOSP keeps the same VPN network unless the
                // allowed/excluded apps change, but every app's DNS is interrupted): an engine
                // that keeps failing must not loop. The window slides, so a
                // rare exit after a minute of running is rebuilt again.
                val now = SystemClock.elapsedRealtime()
                while (engineRebuilds.isNotEmpty() && now - engineRebuilds.first() > ENGINE_REBUILD_WINDOW_MS) engineRebuilds.removeFirst()
                if (engineRebuilds.size >= ENGINE_REBUILDS_MAX) {
                    Log.w(TAG, "DNS engine exited ${engineRebuilds.size + 1} times within a minute; stopping")
                    stopProtection(userInitiated = false, reason = "the DNS engine kept stopping")
                    return@launch
                }
                engineRebuilds.addLast(now)
                Log.w(TAG, "DNS engine exited; rebuilding the tunnel")
                tunSignature = null
                reconfigure()
                if (!running) return@launch
                if (!NativeCore.nativeIsRunning()) {
                    stopProtection(userInitiated = false, reason = "the DNS engine stopped and could not be restarted")
                } else {
                    Timeline.add(this@AdusterVpnService, Timeline.STARTED, "again, after the DNS engine stopped")
                }
            }
        }
        // Only the cheap part here: a UI bind while protection is off must not load the lists.
        scope.launch { prepareEngine() }
    }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == null) return
            scope.launch {
                val before = HttpsConfig.browsers(this@AdusterVpnService).map { it.uid }
                HttpsConfig.forgetBrowsers()
                val after = HttpsConfig.browsers(this@AdusterVpnService).map { it.uid }
                if (running && after != before) reconfigure()
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        NativeCore.nativeTrimMemory()
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    if (running) {
                        stopProtection(userInitiated = true, reason = "turned off in the app", startId = startId)
                    } else {
                        // Nothing runs: no "stopped" entry, no core start-up just to stop; only
                        // the wish to stay off is kept.
                        if (settings.enabled || settings.pausedUntil != 0L) settings = store.updateData { it.copy(enabled = false, pausedUntil = 0) }
                        withContext(Dispatchers.Main) { stopSelf(startId) }
                    }
                }
                return START_NOT_STICKY
            }
            // The card was swiped away (Android 14+ lets users dismiss even a foreground service's
            // card): it comes straight back while protection runs; the service is the only way to
            // pause or turn it off from outside the app, so the card must stay.
            ACTION_RENOTIFY -> {
                if (running) {
                    showProtectionNotification()
                    return START_STICKY
                }
                stopSelf(startId)
                return START_NOT_STICKY
            }
            // Notification actions and the end-of-pause alarm act on running protection. If it is
            // not running (stopped meanwhile, or the process was gone), the service started just
            // for them ends again: as a sticky service it would be restarted later and switch
            // protection on by itself.
            ACTION_PAUSE, ACTION_RESUME -> {
                val wasRunning = running
                scope.launch {
                    if (intent.action == ACTION_PAUSE) {
                        if (running) pause(intent.getIntExtra(EXTRA_MINUTES, 0))
                    } else {
                        resume(auto = intent.getBooleanExtra(EXTRA_AUTO, false))
                    }
                    if (!running) withContext(Dispatchers.Main) { stopSelf(startId) }
                }
                return if (wasRunning) START_STICKY else START_NOT_STICKY
            }
            else -> {
                // ACTION_START, Always-on VPN (SERVICE_INTERFACE) or a sticky restart (null intent).
                try {
                    postCard(foreground = true)
                } catch (e: RuntimeException) {
                    // Android 14+ refuses a systemExempted service once the VPN consent is gone
                    // (another VPN app took the slot while :core was dead, and Android restarted
                    // it): ask for consent again instead of crashing in a loop.
                    Log.w(TAG, "foreground start refused: $e")
                    Notifications.showRevoked(this)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val from = when (intent?.action) {
                    null -> "restarted by Android"
                    SERVICE_INTERFACE -> "by Always-on VPN"
                    else -> intent.getStringExtra(EXTRA_FROM) ?: "from the app"
                }
                scope.launch { startProtection(from) }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // The user switched us off in system settings, or another VPN took the slot.
        val id = latestStartId
        scope.launch {
            stopProtection(userInitiated = true, reason = "turned off in system settings, or another VPN took over", startId = id)
            Notifications.showRevoked(this@AdusterVpnService)
        }
    }

    override fun onDestroy() {
        NativeCore.engineExitListener = null
        unregisterReceiver(receiver)
        unregisterReceiver(packages)
        monitor?.stop()
        if (running) NativeCore.nativeStop()
        running = false
        NativeCore.nativePersist()
        scope.cancel()
        refreshSurfaces()
        super.onDestroy()
    }

    /** Settings and engine configuration only: what Home and the widget read while protection is off. */
    private suspend fun prepareEngine() {
        if (engineReady) return
        settings = store.data.first()
        pausedUntil = settings.pausedUntil
        initEngine()
        engineReady = true
    }

    /**
     * The filter tables: installed on the very first run, then loaded. Never waits long for the
     * list lock (a compile in the main process holds it for seconds): without it, protection
     * starts with the tables already on disk and a compile job is asked for.
     */
    private suspend fun initialize() {
        if (initialized) return
        prepareEngine()
        if (FilterUpdater.ensureInstalled(this, settings) == null) {
            Log.w(TAG, "filter lock busy; starting with the tables on disk")
            app.aduster.filters.FilterJobs.compile(this)
        }
        loadFilters(dns = true, web = true)
        initialized = true
    }

    /**
     * Loads the compiled engines that changed: the DNS table (with the user's rules) and/or the
     * page engine. Reloading the page engine for a DNS-only update would cost a 14 MB rebuild
     * in memory for nothing.
     */
    private fun loadFilters(dns: Boolean, web: Boolean) {
        val store = FilterStore(this)
        if (dns) {
            val r = NativeCore.nativeLoadFilters(store.dnsTable.path, settings.userRules)
            Log.i(TAG, "filters loaded: $r")
        }
        if (web && proxyPort > 0 && store.webTable.exists()) {
            Log.i(TAG, "web engine: " + NativeCore.nativeReloadWeb(store.webTable.path, store.resourcePaths(this)))
            checkWebEngine()
        }
    }

    /**
     * A `web.dat` the proxy could not load (format drift, a truncated file) would leave browsers
     * without page filtering, silently: a rebuild is asked for, at most once per app
     * version (a compiler that keeps producing an unloadable file must not loop).
     */
    private fun checkWebEngine() {
        if (proxyPort <= 0 || !FilterStore(this).webTable.exists() || NativeCore.nativeWebLoaded()) return
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val prefs = getSharedPreferences("core_state", MODE_PRIVATE)
        if (prefs.getLong("web_rebuild_for", -1) == version) return
        prefs.edit().putLong("web_rebuild_for", version).apply()
        Log.w(TAG, "page engine not loaded; asking for a rebuild")
        app.aduster.filters.FilterJobs.compile(this, forceWeb = true)
    }

    /** Warns (once per state change) when lockdown mode would cut everything but DNS. */
    private fun checkLockdown() {
        val now = running && isLockdownEnabled
        if (now == lockdown) return
        lockdown = now
        if (now) Notifications.showLockdown(this) else Notifications.cancelLockdown(this)
    }

    /**
     * Starts/updates the browsers' page filter when enabled and the certificate is trusted; returns
     * its port or 0. Cheap when nothing changed: the trust check is cached (invalidated by
     * trust-store broadcasts), the CA key is decrypted once per process, and an unchanged config
     * never reaches the native side.
     */
    private fun startProxy(s: Settings): Int {
        val enabled = s.httpsFiltering && HttpsConfig.available
        val uids = if (enabled) HttpsConfig.browsers(this).map { it.uid } else emptyList()
        val trusted = enabled && (caTrusted ?: CaManager.isInstalled(this).also {
            caTrusted = it
            caCheckedAt = SystemClock.elapsedRealtime()
        })
        // Every failure goes through the same stop: a proxy left running with an old config
        // after the port went to 0 would be a stale listener.
        fun off(state: String): Int {
            httpsState = state
            if (proxyPort > 0 || proxySignature != null) NativeCore.nativeProxyStop()
            proxySignature = null
            return 0
        }
        HttpsConfig.blocker(HttpsConfig.available, s.httpsFiltering, uids.isNotEmpty(), enabled && CaManager.exists(this), trusted)?.let { return off(it) }
        val cert = CaManager.certPem(this) ?: return off("ca_missing")
        val store = FilterStore(this)
        val exclusions = HttpsConfig.exclusions(this, s)
        val web = if (store.webTable.exists()) store.webTable.path else ""
        val resources = store.resourcePaths(this)
        // Thousands of never-decrypt hosts (AdGuard's lists): their hash stands for them.
        val signature = "$uids|${exclusions.size}:${exclusions.hashCode()}|$web|$resources|${cert.hashCode()}"
        if (proxyPort > 0 && signature == proxySignature) return proxyPort
        // The private key is unwrapped only for this hand-off and not kept (the core keeps it
        // only inside its certificate authority). Certificate and key are read together and
        // must belong together: leaves signed by another key would fail in the browser on every
        // site; then the browser goes direct instead.
        val ca = CaManager.load(this) ?: return off("ca_key_unreadable")
        if (!CaManager.keyMatches(ca)) return off("ca_mismatch")
        val cfg = buildJsonObject {
            put("port", TunConfig.PROXY_PORT)
            put("ca_cert_pem", ca.certPem)
            put("ca_key_pem", ca.keyPem)
            putJsonArray("filter_uids") { uids.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("exclusions") { exclusions.forEach { add(JsonPrimitive(it)) } }
            put("web_engine", web)
            put("resources", resources)
        }
        val port = NativeCore.nativeProxyStart(cfg.toString())
        if (port <= 0) return off("start_failed")
        proxySignature = signature
        httpsState = "on"
        proxyPort = port
        checkWebEngine()
        return port
    }

    /** A timed pause has run out (by the boot clock when this process set it). */
    private fun pauseOver(): Boolean =
        if (pauseDeadlineBoot > 0) SystemClock.elapsedRealtime() >= pauseDeadlineBoot else System.currentTimeMillis() >= pausedUntil

    /** (Re)configures the engine from [settings]; remembers the UTC offset "today" was based on. */
    private fun initEngine() {
        tzOffset = TimeZone.getDefault().getOffset(System.currentTimeMillis())
        // The engine gets what is left of a timed pause; it keeps its own boot-clock deadline.
        val left = when {
            settings.pausedUntil == 0L -> 0L
            settings.pausedUntil == Long.MAX_VALUE -> Long.MAX_VALUE
            pauseDeadlineBoot > 0 -> (pauseDeadlineBoot - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            else -> (settings.pausedUntil - System.currentTimeMillis()).coerceAtLeast(0)
        }
        NativeCore.nativeInit(EngineConfig.json(this, settings, left))
    }

    /** Posts the card for the current state (protected or paused); called only on state changes. */
    private fun showProtectionNotification() = postCard(foreground = false)

    /**
     * Posts the card. Xiaomi's SystemUI folds the card out of sight (its status icon too) when an
     * update arrives for a card first posted over 6 h ago (`mHistoricalFoldingTimeout`; the system
     * still lists it as active). An update keeps the first post's time, so even a periodic re-post
     * folds an old card (its `FoldLogger` logs "logFoldLater" right after the update). A card
     * that is gone or older than [CARD_RENEW_MS] is therefore posted as a
     * new notification under the other id: `startForeground` with a new id makes it the
     * service's card and Android removes the old one. A younger card is updated in place.
     * [foreground]: the call must be `startForeground` (a start command).
     */
    @Synchronized
    private fun postCard(foreground: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val card = Notifications.protection(this, pausedUntil)
        val now = SystemClock.elapsedRealtime()
        val shown = nm.activeNotifications.any { it.id == cardId }
        val fresh = !shown || cardBornAt == 0L || now - cardBornAt >= CARD_RENEW_MS
        val old = cardId
        if (shown && fresh) cardId = if (old == Notifications.ID_PROTECTION) Notifications.ID_PROTECTION_ALT else Notifications.ID_PROTECTION
        if (fresh || foreground) {
            // The type exists from Android 14; before it the manifest's type applies.
            if (Build.VERSION.SDK_INT >= 34) startForeground(cardId, card, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) else startForeground(cardId, card)
        } else {
            nm.notify(cardId, card)
        }
        // Android removes the old card when the id changes; this only makes sure.
        if (cardId != old) nm.cancel(old)
        if (fresh) cardBornAt = now
    }

    /**
     * The card on screen-on: posted again if it is gone (swiped away, or removed by the system),
     * renewed as a new notification if it is old (see [postCard]). Runs only on screen-on (no
     * timer, no wakeup); a young card is left alone.
     */
    private fun ensureProtectionNotification() {
        val shown = getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == cardId }
        if (!shown || SystemClock.elapsedRealtime() - cardBornAt >= CARD_RENEW_MS) postCard(foreground = false)
    }

    /** [from]: who started it, for the timeline. */
    private suspend fun startProtection(from: String) {
        initialize()
        if (running) {
            reconfigure()
            return
        }
        if (prepare(this) != null) {
            // VPN consent is missing (never given, or revoked): the UI has to ask again.
            stopProtection(userInitiated = false, reason = "the VPN permission is missing", notify = false)
            Notifications.showRevoked(this)
            return
        }
        if (!NativeCore.nativeIntact()) {
            stopProtection(userInitiated = false, reason = "this copy of Aduster was modified; install it from its official release")
            return
        }
        proxyPort = startProxy(settings)
        val tun = buildTun(settings)
        if (tun == null || !startEngine(tun)) {
            stopProtection(userInitiated = false, reason = "the tunnel could not be set up")
            return
        }
        monitor = NetworkMonitor(this).also { it.start() }
        running = true
        noteUnrecordedStop()
        Timeline.add(this, Timeline.STARTED, from)
        // A pause that outlived a stop (an app update, a process end) opens again in the
        // timeline, so its Resume has a pause to close; one that ran out meanwhile is simply
        // over.
        when (pauseAtStart(pausedUntil, pauseOver())) {
            PauseAtStart.STILL_PAUSED -> Timeline.add(this, Timeline.PAUSED, "still paused")
            PauseAtStart.ENDED -> {
                pauseDeadlineBoot = 0
                settings = store.updateData { it.copy(pausedUntil = 0) }
                pausedUntil = 0
            }
            PauseAtStart.NONE -> {}
        }
        Notifications.cancelStopped(this)
        checkLockdown()
        // The notification was posted before settings were loaded; say what is actually on,
        // paused included (a restart during a pause keeps it). Alarms do not survive a reboot:
        // a timed pause gets its end-of-pause alarm again.
        showProtectionNotification()
        scheduleResume(pausedUntil)
        if (!settings.enabled) settings = store.updateData { it.copy(enabled = true) }
        refreshSurfaces()
    }

    /**
     * Protection was on when the timeline was last written, but no stop was recorded: this
     * process ended while protecting (an app update, a crash, the system). Android keeps how
     * and when; that goes into the timeline, so a gap in protection is never unexplained.
     */
    private fun noteUnrecordedStop() {
        val last = Timeline.read(this).lastOrNull() ?: return
        if (last.event == Timeline.STOPPED) return
        val exit = ProcessExits.recent(this, 5).firstOrNull { it.process.endsWith(":core") && it.time > last.time } ?: return
        val code = if (exit.reason == "app updated") "app_updated" else if (exit.notable) "failure" else "process_ended"
        Timeline.add(this, Timeline.STOPPED, "the filtering process ended (${exit.reason})", exit.time, code)
    }

    /**
     * [reason] goes to the timeline; a stop nobody asked for is also announced ([notify]):
     * otherwise protection could be off for hours without anyone noticing.
     */
    private suspend fun stopProtection(userInitiated: Boolean, reason: String, notify: Boolean = true, startId: Int = latestStartId) {
        // A pause ended by turning off is closed in the timeline first. Only turning off ends a
        // pause: after any other stop it goes on when protection starts again.
        if (running && pausedUntil != 0L && userInitiated) Timeline.add(this, Timeline.RESUMED, "turned off", code = "off")
        Timeline.add(this, Timeline.STOPPED, reason, code = stopCode(reason, userInitiated))
        if (!userInitiated && notify) Notifications.showStopped(this, reason)
        monitor?.stop()
        monitor = null
        if (running) NativeCore.nativeStop()
        NativeCore.nativeProxyStop()
        proxyPort = 0
        proxySignature = null
        running = false
        tunSignature = null
        checkLockdown()
        if (userInitiated && (settings.enabled || settings.pausedUntil != 0L)) {
            settings = store.updateData { it.copy(enabled = false, pausedUntil = 0) }
            pausedUntil = 0
            pauseDeadlineBoot = 0
            initEngine()
        }
        scheduleResume(0)
        refreshSurfaces()
        withContext(Dispatchers.Main) {
            // Only if no command came after the one that stopped: a "Turn on" right after
            // "Turn off" is queued behind this stop and must find the service alive.
            if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /** The timeline's machine code for a stop (Diagnostics tests codes, not texts). */
    private fun stopCode(reason: String, userInitiated: Boolean): String = when {
        reason == "turned off in the app" -> "user_off"
        reason.startsWith("turned off in system settings") -> "revoked"
        userInitiated -> "user_off"
        else -> "failure"
    }

    /** Pauses for [minutes], or until Resume when 0. */
    private suspend fun pause(minutes: Int) {
        Timeline.add(this, Timeline.PAUSED, if (minutes > 0) "for $minutes min" else "until Resume")
        pauseDeadlineBoot = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0
        setPause(if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else Long.MAX_VALUE)
    }

    /** [auto]: the end-of-pause alarm or a screen-on after it ran out, not the user. */
    private suspend fun resume(auto: Boolean) {
        // A late alarm for a pause that was already resumed, or an early one (the wall clock
        // was moved forward), changes nothing.
        if (auto && (pausedUntil == 0L || !pauseOver())) {
            if (pausedUntil != 0L && pauseDeadlineBoot > 0) scheduleResume(System.currentTimeMillis() + (pauseDeadlineBoot - SystemClock.elapsedRealtime()))
            return
        }
        if (pausedUntil != 0L) Timeline.add(this, Timeline.RESUMED, if (auto) "the pause ran out" else "by you")
        pauseDeadlineBoot = 0
        setPause(0)
    }

    private suspend fun setPause(until: Long) {
        settings = store.updateData { it.copy(pausedUntil = until) }
        pausedUntil = until
        initEngine()
        scheduleResume(until)
        if (running) showProtectionNotification()
        refreshSurfaces()
    }

    /**
     * The pause itself needs no timer (the engine keeps a boot-clock deadline), but the card and
     * the timeline must say when it ended, and browser connections opened during it are closed
     * (the engine refilters when a pause ends). For a timed pause one alarm at its end does
     * that (a pause until Resume has none); it is not a wake-up alarm: if the phone sleeps then,
     * it runs at the next wake, and a screen-on catches up even earlier.
     * The window keeps it within a minute of the end instead of the inexact batching of `set`.
     */
    private fun scheduleResume(until: Long) {
        val am = getSystemService(android.app.AlarmManager::class.java)
        val pi = PendingIntent.getService(
            this, 6, Intent(this, AdusterVpnService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_AUTO, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (until > System.currentTimeMillis() && until != Long.MAX_VALUE) am.setWindow(android.app.AlarmManager.RTC, until, 60_000L, pi) else am.cancel(pi)
    }

    private suspend fun reloadSettings() {
        val old = settings
        settings = store.data.first()
        if (settings.pausedUntil != pausedUntil) pauseDeadlineBoot = 0
        pausedUntil = settings.pausedUntil
        if (!engineReady) return
        initEngine()
        // The user may just have installed the certificate: a cached "not trusted" is checked
        // again on every settings change while it is false (cheap).
        if (caTrusted == false && settings.httpsFiltering) caTrusted = null
        // List changes are compiled by a job in the main process, which then sends
        // FILTERS_CHANGED; compiling here (hundreds of thousands of lines) would hold this
        // process's memory and its command queue (Pause/Resume) for seconds. The user's rules
        // are small and loaded with the table.
        if (initialized && old.userRules != settings.userRules) loadFilters(dns = true, web = false)
        if (running) reconfigure()
    }

    /** Rebuilds the tunnel only if something it was built from changed (routes, app exclusions, proxy). */
    private suspend fun reconfigure() {
        checkLockdown()
        proxyPort = startProxy(settings)
        if (signature(settings) == tunSignature) return
        val tun = buildTun(settings) ?: return
        // nativeStart stops (and joins) the old engine first, then starts the new one on the
        // new descriptor: DNS pauses for that moment only. If the new one does not start, no
        // engine runs: protection stops instead of showing Protected with no DNS.
        if (!startEngine(tun)) stopProtection(userInitiated = false, reason = "the DNS engine could not be restarted")
    }

    private fun signature(s: Settings) = "${s.blockDnsBypass}|${s.excludedApps.sorted()}|$proxyPort"

    private fun buildTun(s: Settings): ParcelFileDescriptor? {
        val b = Builder()
            .setSession("Aduster")
            .setMtu(TunConfig.MTU)
            .setBlocking(false)
            // Inherit meteredness from the real network instead of marking everything metered.
            .setMetered(false)
            .addAddress(TunConfig.ADDRESS_V4, 32)
            .addAddress(TunConfig.ADDRESS_V6, 128)
            .addDnsServer(TunConfig.DNS_V4)
            .addDnsServer(TunConfig.DNS_V6)
            .addRoute(TunConfig.DNS_V4, 32)
            .addRoute(TunConfig.DNS_V6, 128)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 13, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        if (s.blockDnsBypass) {
            TunConfig.PUBLIC_DNS_V4.forEach { b.addRoute(it, 32) }
            TunConfig.PUBLIC_DNS_V6.forEach { b.addRoute(it, 128) }
        }
        if (proxyPort > 0) {
            b.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort, HttpsConfig.PROXY_BYPASS))
        }
        // Our own sockets (upstream DNS, list downloads) must use the real network.
        b.addDisallowedApplication(packageName)
        for (pkg in s.excludedApps) {
            try {
                b.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return try {
            b.establish().also { if (it != null) tunSignature = signature(s) }
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
    }

    private fun startEngine(tun: ParcelFileDescriptor): Boolean {
        val fd = tun.detachFd()
        if (NativeCore.nativeStart(fd)) return true
        ParcelFileDescriptor.adoptFd(fd).close()
        return false
    }

    private fun refreshSurfaces() {
        TileService.requestListeningState(this, ComponentName(this, ProtectionTileService::class.java))
        StatsWidget.update(this)
    }
}
