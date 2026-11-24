package app.aduster.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aduster.filters.FilterJobs
import app.aduster.filters.FilterMeta
import app.aduster.filters.FilterStore
import app.aduster.filters.FilterUpdater
import app.aduster.filters.UserRules
import app.aduster.https.CaManager
import app.aduster.https.HttpsConfig
import app.aduster.settings.DeviceProfile
import app.aduster.settings.Settings
import app.aduster.settings.SettingsBackup
import app.aduster.settings.SettingsStore
import app.aduster.vpn.AdusterVpnService
import app.aduster.vpn.ProcessExits
import app.aduster.vpn.Reports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import app.aduster.settings.coreKey
import app.aduster.settings.trySave
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A row of Excluded apps. [launchable]: it has a launcher entry. [network]: it can reach the
 * network (it holds INTERNET, or another package of its UID does: exclusion works per UID).
 * [note]: the line under a row whose exclusion would change nothing.
 */
data class InstalledApp(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val uid: Int,
    val launchable: Boolean = true,
    val network: Boolean = true,
    val note: String? = null,
)

/** One-shot filter Home passes when opening the Log on a domain or app. */
data class LogFilterRequest(val query: String = "", val appUid: Int? = null)

/** Log entries fetched per binder call (forward paging). */
private const val LOG_PAGE = 1000

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val store = SettingsStore.get(ctx)
    val client = CoreClient(ctx)

    val settings: StateFlow<Settings> = store.data.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** The stored settings have been read ([settings] holds the placeholder until then). */
    val settingsRead: StateFlow<Boolean> = store.data.map { true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Polled only while something on screen collects it and the core is bound. Null = not known
     * yet: while the core is not bound nothing is emitted, so the last real value stays and Home
     * does not flash "Off / Turn on" on every open. One binder call per poll.
     */
    val stats: StateFlow<Stats?> = client.core
        .flatMapLatest { core ->
            if (core == null) {
                emptyFlow()
            } else {
                flow {
                    while (true) {
                        val s = client.call<Stats?>(null) { Stats.parse(it.stats(), running = false) }
                        if (s != null) {
                            resolveLabels(s.apps.map { it.uid })
                            emit(s)
                        }
                        delay(2000)
                    }
                }.flowOn(Dispatchers.IO)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1500), null)

    private val _filterMeta = MutableStateFlow(FilterMeta())
    val filterMeta: StateFlow<FilterMeta> = _filterMeta

    /** What a list job of this process is doing (download, compile), or null. */
    val updateProgress: StateFlow<FilterJobs.Progress?> = FilterJobs.progress

    private val _log = MutableStateFlow<List<LogItem>>(emptyList())
    val log: StateFlow<List<LogItem>> = _log
    private var logSeq = 0L
    /** The core connection and its process start the log was last read from (restart marker). */
    private var logCore: app.aduster.ICore? = null
    private var logCoreStarted = 0L
    /** One log poll at a time: two overlapping polls would read the same entries twice. */
    private val logPoll = Mutex()

    /** Installed browsers, for Page filtering's list and the Log's Browsers filter. */
    private val _browsers = MutableStateFlow<List<app.aduster.https.Browser>>(emptyList())
    val browsers: StateFlow<List<app.aduster.https.Browser>> = _browsers

    /** The browsers' UIDs, for the Log's "Browsers" filter. */
    private val _browserUids = MutableStateFlow<Set<Int>>(emptySet())
    val browserUids: StateFlow<Set<Int>> = _browserUids

    init {
        viewModelScope.launch {
            val s = store.data.first()
            withContext(Dispatchers.IO) {
                FilterJobs.schedule(ctx, s)
                val list = HttpsConfig.browsers(ctx)
                _browsers.value = list
                _browserUids.value = list.map { it.uid }.toSet()
                // A list that was never downloaded (new in the catalog, or first HTTPS enable
                // interrupted) is fetched now rather than at the next daily check. Otherwise an
                // engine built by an older version (or from other rules) is rebuilt; unchanged
                // engines are left alone (signatures).
                if (FilterUpdater.missingLists(ctx, s)) FilterJobs.runNow(ctx, force = false) else if (FilterUpdater.needsCheck(ctx)) FilterJobs.compile(ctx)
            }
        }
        // Lists and counts on screen follow the jobs: read again when one finishes.
        viewModelScope.launch {
            var busy = false
            FilterJobs.progress.collect { p ->
                if (busy && p == null) refreshFilterMeta()
                busy = p != null
            }
        }
        refreshFilterMeta()
    }

    /** The persisted settings (waits for the first read, unlike [settings]' placeholder). */
    suspend fun storedSettings(): Settings = store.data.first()

    fun refreshFilterMeta() {
        viewModelScope.launch(Dispatchers.IO) { _filterMeta.value = FilterStore(ctx).readMeta() }
    }

    // ---- protection ----

    /** Android did not give the VPN permission to the last Turn on (Home shows why, once). */
    val vpnRefused = MutableStateFlow(false)

    fun start() = AdusterVpnService.start(ctx)
    fun stop() = AdusterVpnService.stop(ctx)

    /** Pauses filtering for [minutes], or until [resume] when 0. */
    fun pause(minutes: Int = 0) = AdusterVpnService.pause(ctx, minutes)
    fun resume() = AdusterVpnService.resume(ctx)

    // ---- settings ----

    /**
     * Persists a change and tells :core to apply it. Changes to what the filter tables are built
     * from are compiled by a job (never in the always-on process, and never in this screen's
     * scope, where leaving the app would freeze or kill the work).
     */
    /**
     * [fetch]: the change may need lists that are not on disk yet; one job then fetches them and
     * compiles (a compile job beside it would only build the engines twice).
     */
    fun update(fetch: Boolean = false, transform: (Settings) -> Settings): kotlinx.coroutines.Job =
        viewModelScope.launch(Dispatchers.IO) {
            // "Before" is what the transform really saw (the StateFlow can lag or still hold
            // the placeholder at start-up).
            var before = Settings()
            val after = save { cur -> before = cur; transform(cur) } ?: return@launch
            // :core is told only when something it reads changed: the theme or the log size
            // would otherwise re-initialise its engine.
            if (before.coreKey() != after.coreKey()) AdusterVpnService.notifySettingsChanged(ctx)
            val compileChanged = before.deviceProfile != after.deviceProfile || before.blockDnsBypass != after.blockDnsBypass ||
                before.listOverrides != after.listOverrides || before.customLists != after.customLists
            FilterJobs.afterChange(ctx, after, compileChanged, fetch)
            if (before.theme != after.theme) app.aduster.widget.StatsWidget.refresh(ctx)
            if (before.autoUpdate != after.autoUpdate || before.updateIntervalHours != after.updateIntervalHours ||
                before.updateWifiOnly != after.updateWifiOnly || before.updateWhileCharging != after.updateWhileCharging
            ) {
                FilterJobs.schedule(ctx, after)
            }
        }

    /** Saves a change, or says it could not be saved (a full disk) and returns null. */
    private suspend fun save(transform: (Settings) -> Settings): Settings? =
        store.trySave(transform) ?: null.also { withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, NOT_SAVED, android.widget.Toast.LENGTH_LONG).show() } }

    fun setListEnabled(id: String, on: Boolean) {
        // A newly enabled list is fetched if needed; unchanged lists are not re-downloaded.
        update(fetch = on) { it.copy(listOverrides = it.listOverrides + (id to on)) }
    }

    /** The Device list's profile; a list used for the first time is fetched. */
    fun setDeviceProfile(p: DeviceProfile) {
        update(fetch = true) { it.copy(deviceProfile = p) }
    }

    /** Adds a list; only it is fetched (the others are not re-checked). */
    fun addCustomList(list: app.aduster.settings.CustomList) {
        update(fetch = true) { st -> st.copy(customLists = st.customLists + list) }
    }

    fun removeCustomList(id: String) = update { st -> st.copy(customLists = st.customLists.filterNot { it.id == id }) }

    fun setHttpsFiltering(on: Boolean) {
        update(fetch = on) { it.copy(httpsFiltering = on) }
    }

    fun caInstalled(): Boolean = CaManager.isInstalled(ctx)

    /**
     * Keystore, MediaStore: off the main thread, and a failure of either is a null (the screen
     * says it could not save), never a crash. The saved file's name in Downloads, or null.
     */
    suspend fun exportCa(): String? = withContext(Dispatchers.IO) {
        runCatching { if (CaManager.ensure(ctx) == null) null else CaManager.exportToDownloads(ctx)?.let { CaManager.savedName(ctx, it) } }.getOrNull()
    }

    fun caFingerprint(): String? = CaManager.fingerprint(ctx)

    fun caNeedsNew(): Boolean = CaManager.needsNew(ctx)

    /** The name the certificate was last saved under in Downloads, or null. */
    fun caSavedName(): String? = CaManager.lastSavedName(ctx)

    /** Private DNS set to a strict hostname: works even while protection is off. */
    fun privateDnsStrict(): Boolean = runCatching {
        android.provider.Settings.Global.getString(ctx.contentResolver, "private_dns_mode") == "hostname" ||
            ctx.getSystemService(android.net.ConnectivityManager::class.java)?.let { cm ->
                cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName != null
            } == true
    }.getOrDefault(false)

    /** Refreshes installed browsers (for example after returning from installing one). */
    fun refreshBrowsers() {
        viewModelScope.launch(Dispatchers.IO) {
            HttpsConfig.forgetBrowsers()
            val list = HttpsConfig.browsers(ctx)
            _browsers.value = list
            _browserUids.value = list.map { it.uid }.toSet()
        }
    }

    /** The user asked for a new certificate (the old one cannot be used); saved to Downloads. */
    suspend fun recreateCa(): String? = withContext(Dispatchers.IO) {
        runCatching { if (CaManager.recreate(ctx) == null) null else CaManager.exportToDownloads(ctx)?.let { CaManager.savedName(ctx, it) } }.getOrNull()
    }

    fun updateFiltersNow() = FilterJobs.runNow(ctx)

    // ---- user rules ----

    /**
     * Changes the user's rules. The DNS part is loaded by :core at once; when the part for
     * the browsers' page filter changed, a compile job rebuilds it.
     */
    private fun setUserRules(saved: String? = null, transform: (String) -> String): kotlinx.coroutines.Job =
        viewModelScope.launch(Dispatchers.IO) {
            var before = Settings()
            val after = save { cur -> before = cur; cur.copy(userRules = transform(cur.userRules)) } ?: return@launch
            if (saved != null) withContext(Dispatchers.Main) { android.widget.Toast.makeText(ctx, saved, android.widget.Toast.LENGTH_SHORT).show() }
            AdusterVpnService.notifySettingsChanged(ctx)
            if (after.httpsFiltering && UserRules.webPart(before.userRules) != UserRules.webPart(after.userRules)) FilterJobs.compile(ctx)
        }

    /** The rules before and after the last rule added from the Log, for its Undo. */
    @Volatile
    private var lastAdded: Pair<String, String>? = null

    fun addUserRule(rule: String) = setUserRules { before -> UserRules.withRule(before, rule).also { lastAdded = before to it } }

    /** Takes back the last rule added from the Log, unless the rules changed again since. */
    fun undoAddedRule() {
        val (before, after) = lastAdded ?: return
        lastAdded = null
        setUserRules { cur -> if (cur == after) before else cur }
    }

    /** Saves the user's rules; the browser part is recompiled into the page filter right away. */
    /** [saved]: what to say once the rules are on disk (never before: a full disk says so instead). */
    fun saveUserRules(text: String, saved: String? = null) = setUserRules(saved) { text }

    /** Allowed (true) or blocked (false) everywhere, or no rule (null), for a whole site. */
    fun setSite(host: String, allow: Boolean?) = setUserRules { UserRules.withSite(it, host, allow) }

    /** Layers and errors of the rule lines (for the Rules screen), one native call. */
    fun analyzeRules(text: String): UserRules.Analysis =
        runCatching { UserRules.analyze(text) }.getOrDefault(UserRules.Analysis(emptyMap(), emptyList()))

    /** Saves a stall report to Downloads (through the core, which holds the data). */
    fun saveReport(): String? = client.call(null) { it.saveReport() }

    /** Deletes saved reports from Downloads; how many there were. */
    suspend fun deleteReports(): Int = withContext(Dispatchers.IO) { Reports.deleteAll(ctx) }

    fun checkDomain(name: String): DomainCheck? = parseDomainCheck(client.call("{}") { it.checkDomain(name) })

    /** "Check a site", browser part; null when page filtering is not running. */
    fun checkSite(host: String): SiteCheck? = parseSiteCheck(client.call("{}") { it.explainHost(host) })

    /** "Filter again": ends a bot-check bypass. */
    suspend fun unbypass(host: String): Boolean = withContext(Dispatchers.IO) { client.call(false) { it.unbypass(host) } }

    /** Share to Aduster: [host] is not filtered for [minutes]; false when page filtering is not running. */
    suspend fun pauseSite(host: String, minutes: Int): Boolean = withContext(Dispatchers.IO) { client.call(false) { it.pauseSite(host, minutes) } }

    /** Tells :core to read the settings again (e.g. the certificate was just installed). */
    fun notifyCore() = AdusterVpnService.notifySettingsChanged(ctx)

    /** The packages behind an app's [uid] (one, or those sharing its UID), for Excluded apps. */
    fun packagesOf(uid: Int): Set<String> = runCatching { ctx.packageManager.getPackagesForUid(uid)?.toSet() }.getOrNull().orEmpty()

    /** The user's own never-decrypt entries (the core only knows the merged list). */
    fun userExcludes(host: String, s: Settings): Boolean =
        s.httpsExclusions.lines().mapNotNull { HttpsConfig.exclusionHost(it) }.any { host == it || host.endsWith(".$it") }

    fun diagnostics(): JsonObject? = runCatching { Json.parseToJsonElement(client.call("{}") { it.diagnostics() }).jsonObject }.getOrNull()

    fun processExits(): List<ProcessExits.Exit> = ProcessExits.recent(ctx)

    /** When protection started, stopped, paused and resumed (oldest first); a file, so also while it is off. */
    fun timeline(): List<app.aduster.vpn.Timeline.Entry> = app.aduster.vpn.Timeline.read(ctx)

    // ---- log ----

    private val _pendingLogFilter = MutableStateFlow<LogFilterRequest?>(null)
    val pendingLogFilter: StateFlow<LogFilterRequest?> = _pendingLogFilter

    fun openLogFilter(query: String = "", appUid: Int? = null) {
        _pendingLogFilter.value = LogFilterRequest(query = query, appUid = appUid)
    }

    fun consumeLogFilter() {
        _pendingLogFilter.value = null
    }

    fun pollLog() {
        if (!logPoll.tryLock()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cap = settings.value.logSize.coerceAtLeast(500)
                // A restarted core numbers its entries above the old ones (they start at the boot
                // clock × 1000), so paging simply continues; the restart gets a marker row, so
                // two core lifetimes are not mixed silently. It is asked once per binder
                // connection, which a restart always replaces, not on every poll.
                val core = client.core.value
                if (core != null && core !== logCore) {
                    val started = client.call(0L) { it.startedAt() }
                    if (started != 0L) {
                        if (logCoreStarted != 0L && started != logCoreStarted && _log.value.isNotEmpty()) {
                            _log.value = listOf(LogItem(-System.nanoTime(), System.currentTimeMillis(), "", 0, -1, QueryAction.RESTARTED, null, null, 0)) + _log.value
                        }
                        logCoreStarted = started
                        logCore = core
                    }
                }
                // Forward pages: everything since the last poll arrives, oldest page first,
                // up to the log size the user chose (no hole when more than a page came in).
                val fresh = ArrayList<LogItem>()
                while (true) {
                    val raw = client.call("[]") { it.logAfter(logSeq, LOG_PAGE) }
                    val page = LogItem.parseAll(raw).filter { it.seq > logSeq }
                    val top = maxOf(LogItem.maxSeq(raw), page.lastOrNull()?.seq ?: 0L)
                    if (top <= logSeq) break
                    fresh += page
                    logSeq = top
                    if (fresh.size >= cap) {
                        // Only the newest `cap` can be shown anyway: skip ahead.
                        fresh.subList(0, fresh.size - cap).clear()
                    }
                    if (page.size < LOG_PAGE) break
                    yield()
                }
                if (fresh.isEmpty()) return@launch
                resolveLabels(fresh.map { it.uid })
                _log.value = (fresh.asReversed() + _log.value).take(cap)
            } finally {
                logPoll.unlock()
            }
        }
    }

    /**
     * Off the main thread (a binder call into :core) and under the log poll's lock,
     * so an in-flight poll cannot put old entries back. True if the core was reached.
     */
    suspend fun clearLog(): Boolean = withContext(Dispatchers.IO) {
        logPoll.lock()
        try {
            val ok = client.call(false) { it.clearLog(); true }
            if (ok) _log.value = emptyList()
            ok
        } finally {
            logPoll.unlock()
        }
    }

    /** True if the core was reached (it writes the stats file: off the main thread). */
    suspend fun resetStats(): Boolean = withContext(Dispatchers.IO) { client.call(false) { it.resetStats(); true } }

    // ---- apps ----

    /**
     * App names by UID, resolved on a background thread as UIDs show up in the log and stats
     * (PackageManager calls are binder calls: never during composition).
     */
    private val _labels = MutableStateFlow<Map<Int, String>>(emptyMap())
    val labels: StateFlow<Map<Int, String>> = _labels

    /** Called on IO threads. */
    private fun resolveLabels(uids: Collection<Int>) {
        val missing = uids.filter { it >= 0 && it !in _labels.value }.distinct()
        if (missing.isEmpty()) return
        val pm = ctx.packageManager
        val found = missing.associateWith { uid ->
            systemAppName(uid) ?: pm.getPackagesForUid(uid)?.firstOrNull()?.let { pkg ->
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            } ?: "uid $uid"
        }
        // Atomic: the stats poll and the log poll resolve at the same time.
        _labels.update { it + found }
    }

    /**
     * App icons, small and cached; loaded off the main thread the first time one is shown. An
     * icon the cache drops can load again instead of staying a grey circle.
     */
    private val icons = object : LruCache<String, ImageBitmap>(160) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: ImageBitmap, newValue: ImageBitmap?) {
            if (evicted) iconLoads.remove(key)
        }
    }
    private val _iconVersion = MutableStateFlow(0)
    /** Bumped when an icon was loaded, so lists showing icons draw it. */
    val iconVersion: StateFlow<Int> = _iconVersion
    private val iconLoads = java.util.Collections.synchronizedSet(HashSet<String>())
    private val iconBump = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The icon of [pkg], or null while it loads (a later [iconVersion] change brings it). */
    fun appIcon(pkg: String): ImageBitmap? = icon(pkg) { pkg }

    /** The icon of the app behind [uid] (the first package of a shared UID); the system's lookups get Android's own. */
    fun appIconForUid(uid: Int): ImageBitmap? = when {
        systemAppName(uid) != null -> icon("pkg:android") { "android" }
        uid < Process.FIRST_APPLICATION_UID -> null
        else -> icon("uid:$uid") { ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() }
    }

    /** Cached icon under [key]; the package lookup and drawing run on IO, never in composition. */
    private fun icon(key: String, pkg: () -> String?): ImageBitmap? {
        icons.get(key)?.let { return it }
        if (iconLoads.add(key)) {
            viewModelScope.launch(Dispatchers.IO) {
                val bmp = runCatching {
                    val d = ctx.packageManager.getApplicationIcon(pkg() ?: return@launch)
                    val px = (28 * ctx.resources.displayMetrics.density).toInt()
                    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, px, px)
                    d.draw(Canvas(b))
                    b.asImageBitmap()
                }.getOrNull()
                if (bmp != null) {
                    icons.put(key, bmp)
                    // One redraw per 100 ms of loads, not one per icon.
                    if (!iconBump.getAndSet(true)) {
                        delay(100)
                        iconBump.set(false)
                        _iconVersion.value++
                    }
                } else {
                    iconLoads.remove(key)
                }
            }
        }
        return null
    }

    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val launchable = pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0L)).map { it.activityInfo.packageName }.toSet()
        val facts = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .filter { it.packageName != ctx.packageName }
            .mapNotNull { pi ->
                val ai = pi.applicationInfo ?: return@mapNotNull null
                app.aduster.ui.settings.PackageFacts(
                    pi.packageName, pm.getApplicationLabel(ai).toString(), ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, ai.uid,
                    internet = pi.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true,
                    launchable = pi.packageName in launchable,
                )
            }
        app.aduster.ui.settings.appRows(facts, keep = storedSettings().excludedApps)
    }

    // ---- backup ----

    /** Writes the settings to a user-picked document (off the main thread), marked as ours. */
    suspend fun exportSettings(uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        val json = SettingsBackup.encode(storedSettings().copy(enabled = false, pausedUntil = 0))
        runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.encodeToByteArray()) } != null }.getOrDefault(false)
    }

    /**
     * Reads a user-picked document. Only an Aduster settings file is accepted (other JSON, such
     * as a saved report, would decode to all-default settings and wipe every rule); nothing is
     * applied before the user confirms the summary.
     */
    suspend fun readImport(uri: android.net.Uri): SettingsBackup.Read = withContext(Dispatchers.IO) {
        val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty()
        val pm = ctx.packageManager
        SettingsBackup.read(text, storedSettings(), pages = HttpsConfig.available) { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }

    private val _undoImport = MutableStateFlow(false)
    /** An import was applied in this app session and can be undone (the previous settings are kept). */
    val undoImport: StateFlow<Boolean> = _undoImport

    /**
     * Applies a confirmed import: both filter engines are rebuilt from what is on disk (the
     * user's cosmetic rules and page lists too), and lists the imported settings enable but were
     * never downloaded are fetched. The settings before it are kept for "Undo import".
     */
    suspend fun applyImport(imported: Settings): Boolean = withContext(Dispatchers.IO) {
        val before = storedSettings()
        runCatching { java.io.File(ctx.filesDir, SettingsBackup.BEFORE_IMPORT).writeText(SettingsBackup.encode(before)) }
        replaceSettings(imported).also { if (it) _undoImport.value = true }
    }

    suspend fun undoImport(): Boolean = withContext(Dispatchers.IO) {
        val f = java.io.File(ctx.filesDir, SettingsBackup.BEFORE_IMPORT)
        val prev = runCatching { SettingsBackup.decode(f.readText(), storedSettings())?.settings }.getOrNull() ?: return@withContext false
        if (!replaceSettings(prev)) return@withContext false
        f.delete()
        _undoImport.value = false
        true
    }

    /** False when the settings could not be saved (a full disk): nothing else is done. */
    private suspend fun replaceSettings(next: Settings): Boolean {
        val after = store.trySave { current -> next.copy(enabled = current.enabled, pausedUntil = current.pausedUntil) } ?: return false
        AdusterVpnService.notifySettingsChanged(ctx)
        FilterJobs.schedule(ctx, after)
        FilterJobs.afterChange(ctx, after, compileChanged = true, fetch = true)
        return true
    }

    companion object {
        /** What a screen says when a change could not be written. */
        const val NOT_SAVED = "Could not save: storage is full"
    }
}
