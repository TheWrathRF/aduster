package app.aduster.filters

import android.content.Context
import app.aduster.core.NativeCore
import app.aduster.settings.Settings
import app.aduster.settings.DeviceProfile
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads lists (conditional GET) and compiles the DNS table. Blocking; call off the main thread. */
object FilterUpdater {
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val RECHECK_MS = 60 * 60 * 1000L
    /** uBO lists pull in sibling files; nesting is limited the same way uBO limits it. */
    private const val MAX_INCLUDE_DEPTH = 3
    /**
     * Bumped whenever the native page-filter compiler changes what it produces, so an app
     * update rebuilds the engine even when no list changed.
     */
    private const val WEB_FORMAT = 10

    /**
     * [dnsCompiled]/[webCompiled]: which engines were rebuilt (and need reloading). [checked]:
     * the lists this update downloaded or asked about; [unchanged] also counts those it left.
     * [failed] names the lists that failed or were held back (they shrank); [unreachable] counts
     * those that failed.
     */
    data class Result(
        val downloaded: Int,
        val unchanged: Int,
        val failed: List<String>,
        val dnsCompiled: Boolean,
        val webCompiled: Boolean,
        val checked: Int = 0,
        val unreachable: Int = 0,
    ) {
        val compiled get() = dnsCompiled || webCompiled
    }

    /** [allow]: every rule of the list allows instead of blocks (Xiaomi profile Off). */
    private data class Source(val name: String, val file: File, val allow: Boolean = false)

    internal sealed interface Fetch {
        data class Updated(val etag: String?, val lastModified: String?, val bytes: Long, val scan: FileScan) : Fetch
        /** [scan]: the stored file read once, when its facts were not known yet (an older meta.json). */
        data class NotModified(val scan: FileScan? = null) : Fetch
        /** Downloaded in full, but identical to the stored copy (kept as it is). */
        data class Same(val etag: String?, val lastModified: String?, val scan: FileScan) : Fetch
        data class Failed(val reason: String) : Fetch
        /** Downloaded, but with under half the rules of the stored copy: held back once. */
        data class Shrunk(val reason: String, val held: Held) : Fetch
    }

    /** How much an update checks: lists not on disk yet, those not checked for an hour, or all. */
    enum class Check { MISSING, STALE, ALL }

    /**
     * At most 6 downloads at once and 3 per host (uBO's filters and its 10 parts come from one
     * host, as do the 13 AdGuard lists): 25 conditional checks take ~1 s this way instead of ~6 s
     * one at a time (measured from a PC, 2026-11-21).
     */
    internal class Limits(total: Int = 6, private val perHost: Int = 3) {
        private val all = Semaphore(total)
        private val hosts = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()

        suspend fun <T> run(url: String, block: () -> T): T {
            val host = runCatching { URL(url).host }.getOrDefault("")
            return hosts.computeIfAbsent(host) { Semaphore(perHost) }.withPermit { all.withPermit { block() } }
        }
    }

    /** One list's check: what its download gave and its parts. */
    internal data class Checked(val list: FilterList, val fetch: Fetch, val inc: Includes?)

    /** A shrunk download held back: its size, its rule count and when the first was held. */
    internal data class Held(val bytes: Long = 0, val rules: Int = 0, val since: Long = 0)

    /** How long shrunk downloads are held back at most: a list really cut stays cut. */
    private const val HOLD_MAX_MS = 7 * 24 * 3600 * 1000L

    internal data class Includes(val parts: Map<String, PartMeta>, val changed: Boolean, val error: String?)

    /**
     * First run: installs the bundled snapshot and compiles, so filtering works offline right
     * away. Called by :core at every protection start: when everything is in place it takes no
     * lock at all, and otherwise waits at most 2 s for it (null = the lock stayed busy; the
     * caller starts with what is on disk and asks for a compile job).
     */
    fun ensureInstalled(context: Context, settings: Settings): Boolean? {
        val store = FilterStore(context)
        val bundled = FilterCatalog.enabledDns(settings).filter { it.bundled }
        if (store.dnsTable.exists() && bundled.all { store.raw(it.id).exists() }) return false
        return store.tryLocked(2_000) {
            var copied = false
            for (l in bundled) {
                val f = store.raw(l.id)
                if (!f.exists()) {
                    copied = copyAsset(context, "filters/${l.id}.txt", f) || copied
                }
            }
            if (copied || !store.dnsTable.exists()) compileLocked(context, store, settings, force = true) else false
        }
    }

    /**
     * True if an enabled list has never been downloaded, or was downloaded from a URL the catalog
     * no longer uses (right after an app update changed the catalog).
     */
    fun missingLists(context: Context, settings: Settings): Boolean {
        val store = FilterStore(context)
        val meta = store.readMeta()
        val lists = FilterCatalog.enabledDns(settings) + if (webWanted(context, settings)) FilterCatalog.enabledWeb(settings) + FilterCatalog.exclusionLists else emptyList()
        return lists.any { isMissing(it, meta, store) }
    }

    /**
     * Not on disk, or downloaded from a URL the catalog no longer uses. Aduster's own lists come
     * with the app (copied out when the page engine is compiled); a bundled list's first-run copy
     * counts as there.
     */
    private fun isMissing(l: FilterList, meta: FilterMeta, store: FilterStore): Boolean =
        l.asset == null && (!store.raw(l.id).exists() || (!l.bundled && meta.lists[l.id]?.url.let { it != null && it != l.url }))

    /** Whether list [l] is downloaded by an update that checks [check]. */
    internal fun due(l: FilterList, meta: FilterMeta, store: FilterStore, check: Check, now: Long): Boolean {
        if (l.asset != null) return false
        val missing = isMissing(l, meta, store)
        val prev = meta.lists[l.id]?.takeIf { it.url == l.url }
        return when (check) {
            Check.MISSING -> missing
            Check.STALE -> missing || prev == null || now - prev.checkedAt >= RECHECK_MS
            Check.ALL -> true
        }
    }

    /**
     * Checks the lists [check] calls for and rebuilds what changed. The DNS lists come first: as
     * soon as they are in, the DNS table is compiled and [onDns] told (`:core` loads it at once)
     * while the page lists are still downloading; the page engine follows. [progress] gets the
     * last list checked, how many are done and how many there are.
     */
    fun update(context: Context, settings: Settings, check: Check, onDns: (Boolean) -> Unit = {}, progress: (String, Int, Int) -> Unit = { _, _, _ -> }): Result =
        // One update at a time (the periodic one and "Update now" may overlap): each reads what
        // meta.json knows of the files before it downloads and records it after, and two at once
        // would record each other's files with the wrong facts or remove each other's parts. The
        // second waits; it then finds the lists just checked.
        updating.withLock { updateNow(context, settings, check, onDns, progress) }

    private val updating = java.util.concurrent.locks.ReentrantLock()

    private fun updateNow(context: Context, settings: Settings, check: Check, onDns: (Boolean) -> Unit, progress: (String, Int, Int) -> Unit): Result {
        val store = FilterStore(context)
        store.locked { cleanup(store, settings) }
        val web = webWanted(context, settings)
        val dnsLists = FilterCatalog.enabledDns(settings)
        val webLists = if (web) FilterCatalog.enabledWeb(settings) + FilterCatalog.exclusionLists else emptyList()
        val now = System.currentTimeMillis()
        val t0 = android.os.SystemClock.elapsedRealtime()
        val meta = store.readMeta()
        val dueDns = dnsLists.filter { due(it, meta, store, check, now) }
        val dueWeb = webLists.filter { due(it, meta, store, check, now) }
        val total = dueDns.size + dueWeb.size
        var done = 0
        var lastDone = t0
        val shown = Any()
        val limits = Limits()
        var dnsCompiled = false
        var webCompiled = false
        var dnsMs = 0L
        var dnsReady = 0L
        var webMs = 0L
        val checked = runBlocking(Dispatchers.IO) {
            fun start(lists: List<FilterList>) = lists.map { l ->
                async {
                    checkOne(store, l, meta.lists[l.id], limits, now).also {
                        synchronized(shown) {
                            done++
                            lastDone = android.os.SystemClock.elapsedRealtime()
                            progress(l.name, done, total)
                        }
                    }
                }
            }
            val dnsJobs = start(dueDns)
            val webJobs = start(dueWeb)
            val dnsChecked = dnsJobs.awaitAll()
            // Signatures (size + mtime of every input) decide what needs rebuilding: an updated
            // DNS list does not recompile the page engine unless it changes what the engine
            // relies on, and vice versa.
            store.locked {
                record(store, dnsChecked, now)
                val t = android.os.SystemClock.elapsedRealtime()
                dnsCompiled = compileLocked(context, store, settings, force = false)
                dnsMs = android.os.SystemClock.elapsedRealtime() - t
            }
            dnsReady = android.os.SystemClock.elapsedRealtime() - t0
            onDns(dnsCompiled)
            val webChecked = webJobs.awaitAll()
            synchronized(shown) { if (web) progress("Compiling", total, total) }
            // The page engine goes second: it leaves hosts the DNS table already blocks to it.
            store.locked {
                record(store, webChecked, now)
                val t = android.os.SystemClock.elapsedRealtime()
                webCompiled = web && compileWebLocked(context, store, settings, force = false)
                webMs = android.os.SystemClock.elapsedRealtime() - t
                store.writeMeta(store.readMeta().copy(lastRun = now))
            }
            dnsChecked + webChecked
        }
        val failed = checked.filter { it.fetch is Fetch.Failed || it.fetch is Fetch.Shrunk }.map { it.list.name }
        val changedIds = checked.filter { it.fetch !is Fetch.Failed && it.fetch !is Fetch.Shrunk && (it.fetch is Fetch.Updated || it.inc?.changed == true) }.map { it.list.id }
        // A changed never-decrypt list reaches the proxy through a settings reload (:core reads
        // the files when it builds the proxy config).
        if (FilterCatalog.exclusionLists.any { it.id in changedIds }) {
            app.aduster.vpn.AdusterVpnService.notifySettingsChanged(context)
        }
        val all = dnsLists.size + webLists.size
        android.util.Log.i(
            "Aduster",
            "update: $all lists, ${changedIds.size} changed $changedIds, ${failed.size} failed in ${lastDone - t0} ms ($total checked); " +
                "DNS ${if (dnsCompiled) "compiled" else "unchanged"} $dnsMs ms, ready at $dnsReady ms, web ${if (webCompiled) "compiled" else "unchanged"} $webMs ms",
        )
        return Result(changedIds.size, all - changedIds.size - failed.size, failed, dnsCompiled, webCompiled, checked = total, unreachable = checked.count { it.fetch is Fetch.Failed })
    }

    /** Downloads list [l] and its parts ([prev]: what its last check stored). */
    internal suspend fun checkOne(store: FilterStore, l: FilterList, prev: ListMeta?, limits: Limits, now: Long): Checked {
        val raw = store.raw(l.id)
        // Validators only count for the URL they came from; what is known of the stored file
        // holds whatever URL it came from.
        val same = prev?.takeIf { it.url == l.url }
        val r = limits.run(l.url) { fetch(l.url, raw, same?.etag, same?.lastModified, prev?.scan, same?.let { Held(it.heldBack, it.heldRules, it.heldSince) } ?: Held(), now) }
        val inc = if (r !is Fetch.Failed && r !is Fetch.Shrunk && raw.exists()) {
            val names = when (r) {
                is Fetch.Updated -> r.scan.includes
                is Fetch.Same -> r.scan.includes
                is Fetch.NotModified -> (r.scan ?: scanOf(raw, prev?.scan)).includes
                else -> emptyList()
            }
            fetchParts(names, store.includes(l.id), l.url, same?.parts.orEmpty(), limits, now)
        } else {
            null
        }
        return Checked(l, r, inc)
    }

    /** Writes what [checked] found into meta.json: one read and one write (under the lock). */
    internal fun record(store: FilterStore, checked: List<Checked>, now: Long) {
        if (checked.isEmpty()) return
        val meta = store.readMeta()
        val lists = meta.lists.toMutableMap()
        for (c in checked) lists[c.list.id] = nextMeta(lists[c.list.id] ?: ListMeta(), c.fetch, c.inc, c.list.url, now)
        store.writeMeta(meta.copy(lists = lists))
    }

    /** The lists [check] calls for, downloaded as [update] does (no compile): for tests. */
    internal fun checkAll(store: FilterStore, lists: List<FilterList>, check: Check, now: Long = System.currentTimeMillis()): List<Checked> {
        val meta = store.readMeta()
        val limits = Limits()
        val checked = runBlocking(Dispatchers.IO) { lists.filter { due(it, meta, store, check, now) }.map { l -> async { checkOne(store, l, meta.lists[l.id], limits, now) } }.awaitAll() }
        store.locked { record(store, checked, now) }
        return checked
    }

    /** How many of the lists [ids] have a failed download (their rows say why). */
    internal fun failedOf(meta: FilterMeta, ids: List<String>): Int = ids.count { meta.lists[it]?.error != null }

    /**
     * The lists in use that an update downloads ([web]: page filtering runs) and that have a row
     * on Filters, which says why one failed: not the app's own lists (they come with it), the
     * never-decrypt lists or the lists that ride along with another.
     */
    fun downloaded(settings: Settings, web: Boolean): List<String> {
        val rows = FilterCatalog.all(settings).map { it.id }.toSet()
        return (FilterCatalog.enabledDns(settings) + if (web) FilterCatalog.enabledWeb(settings) else emptyList())
            .filter { it.asset == null && it.id in rows }.map { it.id }
    }

    /** A list's metadata after one download ([r]) of it and its parts ([inc]) from [url]. */
    internal fun nextMeta(old: ListMeta, r: Fetch, inc: Includes?, url: String, now: Long): ListMeta {
        val changed = r is Fetch.Updated || inc?.changed == true
        val m = when (r) {
            is Fetch.Updated -> old.copy(etag = r.etag, lastModified = r.lastModified, bytes = r.bytes, scan = r.scan)
            is Fetch.Same -> old.copy(etag = r.etag, lastModified = r.lastModified, scan = r.scan)
            is Fetch.NotModified -> if (r.scan != null) old.copy(scan = r.scan) else old
            is Fetch.Failed -> old
            is Fetch.Shrunk -> old
        }
        return m.copy(
            url = if (r is Fetch.Failed || r is Fetch.Shrunk) m.url else url,
            parts = inc?.parts ?: m.parts,
            checkedAt = now,
            updatedAt = if (changed) now else m.updatedAt,
            error = (r as? Fetch.Failed)?.reason ?: (r as? Fetch.Shrunk)?.reason ?: inc?.error,
            // A failed download says nothing about the list: a hold-back goes on as it was (its
            // 7 days do not start over), as a part's does.
            heldBack = (r as? Fetch.Shrunk)?.held?.bytes ?: if (r is Fetch.Failed) old.heldBack else 0,
            heldRules = (r as? Fetch.Shrunk)?.held?.rules ?: if (r is Fetch.Failed) old.heldRules else 0,
            heldSince = (r as? Fetch.Shrunk)?.held?.since ?: if (r is Fetch.Failed) old.heldSince else 0,
        )
    }

    /**
     * Browser lists are fetched and compiled only when page filtering can run: switched on and
     * the certificate trusted. The first trust triggers a compile (the UI's
     * certificate check and :core's trust-store receiver send SETTINGS_CHANGED / a compile).
     */
    fun webWanted(context: Context, settings: Settings): Boolean =
        settings.httpsFiltering && app.aduster.https.CaManager.isInstalled(context)

    /** The app version whose job last checked the engines (see [needsCheck]). */
    fun noteAppVersion(context: Context) {
        val store = FilterStore(context)
        val v = appVersion(context)
        store.locked { store.readMeta().let { if (it.appVersion != v) store.writeMeta(it.copy(appVersion = v)) } }
    }

    /**
     * Whether an app start must schedule a compile job: only after an app update (a new
     * compiler, new bundled lists) or when no table exists; everything else that changes the
     * engines schedules its own job, so an ordinary app open starts none.
     */
    fun needsCheck(context: Context): Boolean {
        val store = FilterStore(context)
        return !store.dnsTable.exists() || store.readMeta().appVersion != appVersion(context)
    }

    private fun appVersion(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    /** Recompiles only if the set of sources or their contents changed (or [force]). */
    fun compile(context: Context, settings: Settings, force: Boolean = false): Boolean {
        val store = FilterStore(context)
        // A compile job also clears what a cut-short write left (an update does it in cleanup).
        return store.locked { store.removeLeftovers(); compileLocked(context, store, settings, force) }
    }

    fun compileWeb(context: Context, settings: Settings, force: Boolean = false): Boolean {
        val store = FilterStore(context)
        return store.locked { store.removeLeftovers(); compileWebLocked(context, store, settings, force) }
    }

    /**
     * Removes files and metadata of lists that are no longer in the catalog or custom lists, and
     * downloads left half-done by a crash. A download in progress (another update running at the
     * same time: the periodic one and "Update now") has its own temporary file and is left alone.
     */
    private fun cleanup(store: FilterStore, settings: Settings) {
        store.removeLeftovers()
        val known = FilterCatalog.stored(settings).map { it.id }.toSet()
        val now = System.currentTimeMillis()
        store.rawDir.listFiles()?.forEach { f ->
            if (f.name.contains(".part")) {
                if (now - f.lastModified() > RECHECK_MS) f.deleteRecursively()
                return@forEach
            }
            val id = f.name.removeSuffix(".txt").removeSuffix(".inc")
            if (id !in known) f.deleteRecursively()
        }
        val meta = store.readMeta()
        val kept = meta.lists.filterKeys { it in known }
        if (kept.size != meta.lists.size) store.writeMeta(meta.copy(lists = kept))
    }

    /**
     * Downloads the files the list stored in [raw] (from [listUrl]) pulls in with `!#include`
     * (same host, relative, nested up to 3) into [dir]. A part that shrank is held back once like
     * a whole list.
     */
    internal fun fetchIncludes(raw: File, dir: File, listUrl: String, prev: Map<String, PartMeta>, now: Long = System.currentTimeMillis()): Includes =
        runBlocking(Dispatchers.IO) { fetchParts(scanFile(raw).includes, dir, listUrl, prev, Limits(), now) }

    /** [fetchIncludes] for the parts [names] a list names; each level's parts download at once. */
    internal suspend fun fetchParts(names: List<String>, dir: File, listUrl: String, prev: Map<String, PartMeta>, limits: Limits, now: Long): Includes = coroutineScope {
        val base = URL(listUrl)
        val parts = linkedMapOf<String, PartMeta>()
        var changed = false
        var error: String? = null
        var level = names
        var depth = 0
        while (level.isNotEmpty() && depth < MAX_INCLUDE_DEPTH) {
            val todo = level.distinct().filter { it !in parts }.mapNotNull { name -> URL(base, name).takeIf { it.host == base.host }?.let { name to it } }
            if (todo.isNotEmpty()) dir.mkdirs()
            val results = todo.map { (name, url) ->
                async {
                    val file = File(dir, name)
                    // A stored part without metadata (a catalog URL change, an unreadable meta.json)
                    // is still the good copy: a failed download keeps it.
                    val p = prev[name]?.takeIf { file.exists() } ?: if (file.exists()) PartMeta() else null
                    val r = limits.run(url.toString()) { fetch(url.toString(), file, p?.etag, p?.lastModified, p?.scan, p?.let { Held(it.heldBack, it.heldRules, it.heldSince) } ?: Held(), now) }
                    Triple(name, p, r)
                }
            }.awaitAll()
            val next = mutableListOf<String>()
            for ((name, p, r) in results) {
                when (r) {
                    is Fetch.Updated -> {
                        changed = true
                        parts[name] = PartMeta(r.etag, r.lastModified, scan = r.scan)
                    }
                    is Fetch.NotModified -> parts[name] = (p ?: PartMeta()).let { if (r.scan != null) it.copy(scan = r.scan) else it }
                    is Fetch.Same -> parts[name] = PartMeta(r.etag, r.lastModified, scan = r.scan)
                    is Fetch.Shrunk -> {
                        error = "$name: ${r.reason}"
                        if (p != null) parts[name] = p.copy(heldBack = r.held.bytes, heldRules = r.held.rules, heldSince = r.held.since)
                    }
                    is Fetch.Failed -> {
                        error = "$name: ${r.reason}"
                        if (p != null) parts[name] = p
                    }
                }
                val file = File(dir, name)
                if (file.exists()) next += scanOf(file, parts[name]?.scan).includes
            }
            level = next
            depth++
        }
        // Files the list no longer includes (a download of a concurrent update is left alone).
        dir.listFiles()?.forEach {
            val part = it.name.contains(".part")
            if ((!part && it.name !in parts) || (part && now - it.lastModified() > RECHECK_MS)) it.delete()
        }
        if (parts.keys != prev.keys) changed = true
        Includes(parts, changed, error)
    }

    /** [stored] if it still describes [f] (same size), else [f] read once. */
    private fun scanOf(f: File, stored: FileScan?): FileScan = stored?.takeIf { it.describes(f) } ?: scanFile(f)

    /** Reads a stored file once for its [FileScan] (a file stored before these facts were kept). */
    internal fun scanFile(f: File): FileScan {
        val s = ListScanner()
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                s.feed(buf, n)
            }
        }
        return s.finish().copy(mtime = f.lastModified())
    }

    /** Builds the browsers' page-filtering engine from the enabled web lists. */
    private fun compileWebLocked(context: Context, store: FilterStore, settings: Settings, force: Boolean): Boolean {
        // Aduster's own lists: the app's copy (rewritten only when it differs, so its size and
        // mtime, part of the signature, change only with an app update that changed it).
        FilterCatalog.enabledWeb(settings).forEach { l ->
            val asset = l.asset ?: return@forEach
            syncAsset(context, asset, store.raw(l.id))
            val m = store.readMeta()
            if (m.lists[l.id] == null) store.writeMeta(m.copy(lists = m.lists + (l.id to ListMeta(url = l.url))))
        }
        val lists = FilterCatalog.enabledWeb(settings).filter { store.raw(it.id).exists() }
        return compileWebFiles(store, lists, UserRules.webPart(settings.userRules), force)
    }

    /** The page compiler, as [compileWebFiles] calls it (a stand-in in tests). */
    internal interface WebNative {
        fun compile(spec: String, out: String, dns: String): String
        fun digest(dat: String, dns: String): String
    }

    private object NativeWeb : WebNative {
        override fun compile(spec: String, out: String, dns: String): String = NativeCore.nativeCompileWeb(spec, out, dns)
        override fun digest(dat: String, dns: String): String = NativeCore.nativeWebDnsDigest(dat, dns)
    }

    /**
     * Builds the page engine from the stored [lists] and the user's page rules [userWeb], unless
     * nothing it is built from changed (or [force]). Under the store's lock.
     */
    internal fun compileWebFiles(store: FilterStore, lists: List<FilterList>, userWeb: String, force: Boolean, native: WebNative = NativeWeb): Boolean {
        val meta = store.readMeta()
        // The user's own element-hiding and URL rules join the page filter as one more list.
        val userFile = File(store.dir, "user-web.txt")
        if (userWeb.isEmpty()) userFile.delete() else if (!userFile.exists() || userFile.readText() != userWeb) userFile.writeText(userWeb)
        // Every browser list off and no browser rules of the user: the old engine must go too, or
        // lists switched off would keep filtering. :core then runs the proxy without
        // a page engine.
        if (lists.isEmpty() && userWeb.isEmpty()) {
            if (!store.webTable.exists() && !store.webExtra.exists()) return false
            store.webTable.delete()
            store.webExtra.delete()
            store.writeMeta(meta.copy(webSignature = "", webRules = 0, webDigestKey = "", webDigest = ""))
            return true
        }
        fun sig(f: File) = "${f.length()}:${f.lastModified()}"
        val base = lists.joinToString("|") { l ->
            val inc = store.includes(l.id).listFiles()?.sortedBy { it.name }?.joinToString(",") { "${it.name}:${sig(it)}" }.orEmpty()
            "${l.id}:${sig(store.raw(l.id))}[$inc]"
        } + "|user=" + userWeb.hashCode() + "|format=$WEB_FORMAT"
        // Of the DNS table, the engine only depends on which hosts named by page-list exceptions
        // it blocks (native side): other DNS list updates leave the engine, and with it every
        // cached page's revalidation marker, alone. Working that out takes ~2 s of CPU on a test
        // phone, so it is done only when it decides something: the page lists are unchanged
        // and the DNS table (or the engine's side table) is not the one it was worked out for.
        // A compile hands back the digest of what it built.
        // The engine file too: one replaced without its side table (a compile cut short between
        // its renames) is asked about again, and the engine says the pair does not match.
        fun digestKey() = "${sig(store.webTable)}|${sig(store.webExtra)}|${sig(store.dnsTable)}"
        if (!force && store.webTable.exists() && meta.webSignature.substringBeforeLast("|dns=", "") == base) {
            val key = digestKey()
            val digest = if (key == meta.webDigestKey && meta.webDigest.isNotEmpty()) meta.webDigest else native.digest(store.webTable.path, if (store.dnsTable.exists()) store.dnsTable.path else "")
            if (meta.webSignature == "$base|dns=$digest") {
                if (meta.webDigestKey != key) store.writeMeta(meta.copy(webDigestKey = key, webDigest = digest))
                return false
            }
        }
        val spec = buildJsonArray {
            lists.forEach { l ->
                add(buildJsonObject {
                    put("name", l.id)
                    put("path", store.raw(l.id).path)
                    put("trusted", l.trusted)
                    put("drop_image_hosts", l.dropImageHosts)
                    // Only its trusted scriptlets, in uBO syntax.
                    if (l.adguardTrustedOf != null) put("adguard_trusted", true)
                    // Its element exceptions exempt elements.
                    if (l.unblock) put("unblock", true)
                    val inc = store.includes(l.id)
                    if (inc.isDirectory) put("includes", inc.path)
                })
            }
            if (userWeb.isNotEmpty()) add(buildJsonObject { put("name", "user"); put("path", userFile.path) })
        }
        val dns = if (store.dnsTable.exists()) store.dnsTable.path else ""
        val out = Json.parseToJsonElement(native.compile(spec.toString(), store.webTable.path, dns)).jsonObject
        val ok = out["ok"]?.jsonPrimitive?.content == "true"
        if (!ok) {
            store.writeMeta(meta.copy(webError = out["error"]?.jsonPrimitive?.content ?: "compile failed"))
            return false
        }
        val updated = meta.lists.toMutableMap()
        (out["lists"] as? JsonArray)?.forEach { e ->
            val o = e.jsonObject
            val id = o["name"]?.jsonPrimitive?.content ?: return@forEach
            val m = updated[id] ?: return@forEach
            updated[id] = m.copy(rules = o["rules"]?.jsonPrimitive?.int ?: m.rules, dnsCovered = o["dns"]?.jsonPrimitive?.int ?: 0)
        }
        // The digest of what the compile built against (an engine without it: worked out here).
        val newDigest = out["dns_digest"]?.jsonPrimitive?.content ?: native.digest(store.webTable.path, if (store.dnsTable.exists()) store.dnsTable.path else "")
        store.writeMeta(
            meta.copy(
                lists = updated, webCompiledAt = System.currentTimeMillis(), webRules = out["rules"]?.jsonPrimitive?.int ?: 0,
                webSignature = "$base|dns=$newDigest", webError = null, webDigestKey = digestKey(), webDigest = newDigest,
            ),
        )
        return true
    }

    private fun compileLocked(context: Context, store: FilterStore, settings: Settings, force: Boolean): Boolean {
        val sources = mutableListOf<Source>()
        fun builtin(asset: String, name: String) {
            val f = File(store.builtinDir, asset)
            syncAsset(context, "builtin/$asset", f)
            sources += Source(name, f)
        }
        builtin("core.txt", "Aduster built-in")
        if (settings.blockDnsBypass) builtin("dns-bypass.txt", "Aduster DNS bypass guard")
        val device = Device.current()
        if (device.brandList == FilterCatalog.XIAOMI_LIST && FilterCatalog.deviceProfile(settings, device) == DeviceProfile.SAFE) {
            builtin("xiaomi-safe.txt", "Aduster Xiaomi safe profile")
        }
        val lists = FilterCatalog.enabledDns(settings, device).filter { store.raw(it.id).exists() }
        // Profile Off on Xiaomi: its list lifts every other list's block of its domains (HaGeZi
        // PRO and AdGuard DNS block nearly all of them, push and Find Device included).
        val allow = FilterCatalog.deviceListAllows(settings, device)
        lists.forEach { sources += Source(it.id, store.raw(it.id), allow = allow && it.id == FilterCatalog.XIAOMI_LIST) }

        val signature = sources.joinToString("|") { "${it.name}:${it.file.length()}:${it.file.lastModified()}${if (it.allow) ":allow" else ""}" }
        val meta = store.readMeta()
        if (!force && store.dnsTable.exists() && meta.compiledSignature == signature) return false

        val spec = buildJsonArray {
            sources.forEach { s ->
                add(buildJsonObject {
                    put("name", s.name)
                    put("path", s.file.path)
                    if (s.allow) put("allow", true)
                })
            }
        }
        val out = Json.parseToJsonElement(NativeCore.nativeCompile(spec.toString(), store.dnsTable.path)).jsonObject
        if (out["ok"]?.jsonPrimitive?.content != "true") return false

        val perSource = (out["per_source"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: emptyList()
        val updatedLists = meta.lists.toMutableMap()
        sources.forEachIndexed { i, s ->
            val count = perSource.getOrNull(i) ?: return@forEachIndexed
            if (FilterCatalog.byId(s.name) != null || settings.customLists.any { it.id == s.name }) {
                updatedLists[s.name] = (updatedLists[s.name] ?: ListMeta()).copy(rules = count)
            }
        }
        val total = out["unique"]?.jsonPrimitive?.int ?: 0
        store.writeMeta(meta.copy(lists = updatedLists, compiledAt = System.currentTimeMillis(), compiledRules = total, compiledSignature = signature))
        return true
    }

    /** Rewrites [to] only when the asset differs, so its mtime (part of the signature) stays stable. */
    private fun syncAsset(context: Context, asset: String, to: File) {
        val bytes = try {
            context.assets.open(asset).use { it.readBytes() }
        } catch (_: IOException) {
            return
        }
        if (to.exists() && to.readBytes().contentEquals(bytes)) return
        val tmp = File(to.path + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(to)
    }

    private fun copyAsset(context: Context, asset: String, to: File): Boolean =
        try {
            context.assets.open(asset).use { input ->
                val tmp = File(to.path + ".part")
                tmp.outputStream().use { input.copyTo(it) }
                tmp.renameTo(to)
            }
        } catch (_: IOException) {
            false
        }

    /**
     * A download with under half the rule lines of the stored copy (when that has over 1,000)
     * is held back once: a list published truncated must not silently weaken protection. A list
     * really cut by its maintainer passes on the next download that confirms the last one held
     * ([held]): the same size, or a rule count within 10 % of it (a list that changes between
     * updates rarely comes twice with the same size), and any shrunk download once hold-backs
     * have lasted 7 days.
     */
    private fun fetch(url: String, target: File, etag: String?, lastModified: String?, stored: FileScan?, held: Held, now: Long): Fetch {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Fetch.Failed(e.message ?: "bad url")
        }
        // Unique per download: two updates running at once never write or delete each other's file.
        val tmp = File(target.path + ".part-" + android.os.Process.myPid() + "-" + downloads.incrementAndGet() + "-" + System.nanoTime())
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Aduster/1.0 (Android)")
            if (target.exists()) {
                etag?.let { conn.setRequestProperty("If-None-Match", it) }
                lastModified?.let { conn.setRequestProperty("If-Modified-Since", it) }
            }
            when (val code = conn.responseCode) {
                // A stored file whose facts are not known yet (meta.json of an older version) is
                // read this once.
                HttpURLConnection.HTTP_NOT_MODIFIED -> Fetch.NotModified(if (target.exists() && stored?.describes(target) != true) scanFile(target) else null)
                HttpURLConnection.HTTP_OK -> {
                    // One pass: written to the temporary file while its hash, rule lines and
                    // includes are taken.
                    val scanner = ListScanner()
                    conn.inputStream.use { input ->
                        tmp.outputStream().buffered().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                if (scanner.size + n > MAX_BYTES) throw IOException("list larger than 64 MiB")
                                scanner.feed(buf, n)
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    val scan = scanner.finish()
                    if (!scanner.looksLikeFilterList()) {
                        tmp.delete()
                        return Fetch.Failed("not a filter list")
                    }
                    val old = if (target.exists()) lazy { scanOf(target, stored) } else null
                    // Same measure on both copies (rule lines of this file, includes apart).
                    val before = if (old != null && scan.size != held.bytes) old.value.lines else 0
                    if (before > 1000) {
                        val n = scan.lines
                        val confirmed = (held.rules > 0 && Math.abs(n - held.rules) * 10 <= held.rules) ||
                            (held.since > 0 && now - held.since >= HOLD_MAX_MS)
                        if (n * 2 < before && !confirmed) {
                            tmp.delete()
                            val since = if (held.since > 0) held.since else now
                            return Fetch.Shrunk("the list shrank from ${"%,d".format(java.util.Locale.US, before)} to ${"%,d".format(java.util.Locale.US, n)} rules; kept the previous copy", Held(scan.size, n, since))
                        }
                    }
                    // Some servers answer every request in full (no validators, or they change
                    // them): the same bytes again must not touch the stored file, whose size and
                    // mtime decide whether the engines are rebuilt.
                    if (old != null && target.length() == scan.size && old.value.sha256 == scan.sha256) {
                        tmp.delete()
                        return Fetch.Same(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"), scan.copy(mtime = target.lastModified()))
                    }
                    // rename(2) on Android; also replaces the stored copy on a PC's JVM (the tests).
                    java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    Fetch.Updated(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"), scan.size, scan.copy(mtime = target.lastModified()))
                }
                else -> Fetch.Failed("HTTP $code")
            }
        } catch (e: Exception) {
            tmp.delete()
            Fetch.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    private val downloads = java.util.concurrent.atomic.AtomicLong()
}

/**
 * Reads a list file as it streams by, once: its SHA-256, its rule lines (not blank, not a
 * comment or a header: the shrink guard's measure), the names it pulls in with `!#include` and
 * its first 4 KB (HTML error pages, captive-portal answers and binary junk are refused).
 */
internal class ListScanner {
    private val digest = java.security.MessageDigest.getInstance("SHA-256")
    private val head = ByteArray(4096)
    private var headLen = 0
    var size = 0L
        private set
    private var lines = 0
    private val includes = mutableListOf<String>()

    // The line being read: what its first non-blank characters say it is.
    private var state = START
    private val bang = java.io.ByteArrayOutputStream(256)

    fun feed(buf: ByteArray, n: Int) {
        digest.update(buf, 0, n)
        if (headLen < head.size) {
            val k = minOf(n, head.size - headLen)
            System.arraycopy(buf, 0, head, headLen, k)
            headLen += k
        }
        size += n
        for (i in 0 until n) step(buf[i])
    }

    private fun step(b: Byte) {
        // A line ends at LF, CR or CRLF, as the line reader split it.
        if (b == NL || b == CR) {
            endLine()
            return
        }
        state = when (state) {
            START -> when {
                b == SPACE || b == TAB || b == CR || b == VT || b == FF -> START
                b == BANG -> { bang.reset(); bang.write(b.toInt()); BANG_LINE }
                b == BRACKET -> OTHER
                b == HASH -> HASH1
                else -> { lines++; OTHER }
            }
            // `##`, `#@#` and `#?#` start element rules; any other `#` line is a comment.
            HASH1 -> when (b) {
                HASH -> { lines++; OTHER }
                AT, QUESTION -> HASH2
                else -> OTHER
            }
            HASH2 -> { if (b == HASH) lines++; OTHER }
            // A `!` line too long for any include name (4 KB) is a comment, never a cut-off name.
            BANG_LINE -> if (bang.size() < 4096) { bang.write(b.toInt()); BANG_LINE } else OTHER
            else -> OTHER
        }
    }

    private fun endLine() {
        if (state == BANG_LINE) {
            INCLUDE.matchEntire(bang.toByteArray().decodeToString().trim())?.let { includes += it.groupValues[1] }
        }
        state = START
    }

    fun finish(): FileScan {
        endLine()
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return FileScan(size, hex, lines, includes.toList())
    }

    fun looksLikeFilterList(): Boolean {
        if (size == 0L) return false
        val h = head.copyOf(headLen)
        if (h.any { it == 0.toByte() }) return false
        val text = h.decodeToString().trimStart().lowercase()
        return !(text.startsWith("<!doctype html") || text.startsWith("<html"))
    }

    private companion object {
        const val START = 0
        const val OTHER = 1
        const val HASH1 = 2
        const val HASH2 = 3
        const val BANG_LINE = 4
        const val NL: Byte = 10
        const val CR: Byte = 13
        const val SPACE: Byte = 32
        const val TAB: Byte = 9
        const val VT: Byte = 11
        const val FF: Byte = 12
        const val BANG: Byte = 33
        const val HASH: Byte = 35
        const val AT: Byte = 64
        const val QUESTION: Byte = 63
        const val BRACKET: Byte = 91
        val INCLUDE = Regex("""^!#include\s+([A-Za-z0-9][A-Za-z0-9._-]*)\s*$""")
    }
}
