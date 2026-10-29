package app.aduster.filters

import android.content.Context
import app.aduster.core.NativeCore
import app.aduster.https.HttpsConfig
import app.aduster.settings.Settings
import app.aduster.settings.DeviceProfile
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
    private const val WEB_FORMAT = 8
    private val INCLUDE = Regex("""^!#include\s+([A-Za-z0-9][A-Za-z0-9._-]*)\s*$""")

    /** [dnsCompiled]/[webCompiled]: which engines were rebuilt (and need reloading). */
    data class Result(val downloaded: Int, val unchanged: Int, val failed: List<String>, val dnsCompiled: Boolean, val webCompiled: Boolean) {
        val compiled get() = dnsCompiled || webCompiled
    }

    /** [allow]: every rule of the list allows instead of blocks (Xiaomi profile Off). */
    private data class Source(val name: String, val file: File, val allow: Boolean = false)

    private sealed interface Fetch {
        data class Updated(val etag: String?, val lastModified: String?, val bytes: Long) : Fetch
        data object NotModified : Fetch
        /** Downloaded in full, but identical to the stored copy (kept as it is). */
        data class Same(val etag: String?, val lastModified: String?) : Fetch
        data class Failed(val reason: String) : Fetch
        /** Downloaded, but with under half the rules of the stored copy: held back once. */
        data class Shrunk(val reason: String, val held: Held) : Fetch
    }

    /** A shrunk download held back: its size, its rule count and when the first was held. */
    private data class Held(val bytes: Long = 0, val rules: Int = 0, val since: Long = 0)

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
        // Aduster's own lists (and lists that follow a setting and are off) come with the app (copied out when the page engine is compiled).
        return lists.filter { it.asset == null }.any { l ->
            !store.raw(l.id).exists() || (!l.bundled && meta.lists[l.id]?.url.let { it != null && it != l.url })
        }
    }

    /** [progress] gets the list being fetched, how many lists are done and how many there are. */
    fun update(context: Context, settings: Settings, force: Boolean, progress: (String, Int, Int) -> Unit = { _, _, _ -> }): Result {
        val store = FilterStore(context)
        store.locked { cleanup(store, settings) }
        var downloaded = 0
        var unchanged = 0
        val failed = mutableListOf<String>()
        val changedIds = mutableListOf<String>()
        val web = webWanted(context, settings)
        val lists = FilterCatalog.enabledDns(settings) + if (web) FilterCatalog.enabledWeb(settings) + FilterCatalog.exclusionLists else emptyList()
        val now = System.currentTimeMillis()
        val now0 = android.os.SystemClock.elapsedRealtime()

        for ((i, l) in lists.withIndex()) {
            if (l.asset != null) {
                // Shipped with the app: nothing to download.
                unchanged++
                continue
            }
            // Validators only count for the URL they came from.
            val prev = store.readMeta().lists[l.id]?.takeIf { it.url == l.url }
            if (!force && prev != null && now - prev.checkedAt < RECHECK_MS && store.raw(l.id).exists()) {
                unchanged++
                continue
            }
            progress(l.name, i, lists.size)
            val r = fetch(l.url, store.raw(l.id), prev?.etag, prev?.lastModified, prev?.let { Held(it.heldBack, it.heldRules, it.heldSince) } ?: Held(), now)
            val inc = if (r !is Fetch.Failed && r !is Fetch.Shrunk && store.raw(l.id).exists()) fetchIncludes(store.raw(l.id), store.includes(l.id), l.url, prev?.parts.orEmpty()) else null
            store.locked {
                val meta = store.readMeta()
                val old = meta.lists[l.id] ?: ListMeta()
                val changed = r is Fetch.Updated || inc?.changed == true
                val next = when (r) {
                    is Fetch.Updated -> old.copy(etag = r.etag, lastModified = r.lastModified, bytes = r.bytes)
                    is Fetch.Same -> old.copy(etag = r.etag, lastModified = r.lastModified)
                    Fetch.NotModified -> old
                    is Fetch.Failed -> old
                    is Fetch.Shrunk -> old
                }.let { m ->
                    m.copy(
                        url = if (r is Fetch.Failed || r is Fetch.Shrunk) m.url else l.url,
                        parts = inc?.parts ?: m.parts,
                        checkedAt = now,
                        updatedAt = if (changed) now else m.updatedAt,
                        error = (r as? Fetch.Failed)?.reason ?: (r as? Fetch.Shrunk)?.reason ?: inc?.error,
                        heldBack = (r as? Fetch.Shrunk)?.held?.bytes ?: 0,
                        heldRules = (r as? Fetch.Shrunk)?.held?.rules ?: 0,
                        heldSince = (r as? Fetch.Shrunk)?.held?.since ?: 0,
                    )
                }
                when {
                    r is Fetch.Failed || r is Fetch.Shrunk -> failed += l.name
                    changed -> {
                        downloaded++
                        changedIds += l.id
                    }
                    else -> unchanged++
                }
                store.writeMeta(meta.copy(lists = meta.lists + (l.id to next)))
            }
        }
        progress("Compiling", lists.size, lists.size)
        // Signatures (size + mtime of every input) decide what needs rebuilding: an updated DNS
        // list does not recompile the page engine unless it changes what the engine relies on,
        // and vice versa. The DNS table goes first, since the page engine leaves hosts it
        // already blocks to it.
        var dnsCompiled = false
        var webCompiled = false
        val t1 = android.os.SystemClock.elapsedRealtime()
        var t2 = t1
        store.locked {
            dnsCompiled = compileLocked(context, store, settings, force = false)
            t2 = android.os.SystemClock.elapsedRealtime()
            webCompiled = web && compileWebLocked(context, store, settings, force = false)
            store.writeMeta(store.readMeta().copy(lastRun = now))
        }
        val t3 = android.os.SystemClock.elapsedRealtime()
        // A changed never-decrypt list reaches the proxy through a settings reload (:core reads
        // the files when it builds the proxy config).
        if (FilterCatalog.exclusionLists.any { it.id in changedIds }) {
            HttpsConfig.forgetDownloaded()
            app.aduster.vpn.AdusterVpnService.notifySettingsChanged(context)
        }
        android.util.Log.i(
            "Aduster",
            "update: ${lists.size} lists, $downloaded changed $changedIds, ${failed.size} failed in ${t1 - now0} ms; " +
                "DNS ${if (dnsCompiled) "compiled" else "unchanged"} ${t2 - t1} ms, web ${if (webCompiled) "compiled" else "unchanged"} ${t3 - t2} ms",
        )
        return Result(downloaded, unchanged, failed, dnsCompiled, webCompiled)
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
        return store.locked { compileLocked(context, store, settings, force) }
    }

    fun compileWeb(context: Context, settings: Settings, force: Boolean = false): Boolean {
        val store = FilterStore(context)
        return store.locked { compileWebLocked(context, store, settings, force) }
    }

    /**
     * Removes files and metadata of lists that are no longer in the catalog or custom lists, and
     * downloads left half-done by a crash. A download in progress (another update running at the
     * same time: the periodic one and "Update now") has its own temporary file and is left alone.
     */
    private fun cleanup(store: FilterStore, settings: Settings) {
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
    internal fun fetchIncludes(raw: File, dir: File, listUrl: String, prev: Map<String, PartMeta>, now: Long = System.currentTimeMillis()): Includes {
        val base = URL(listUrl)
        val parts = linkedMapOf<String, PartMeta>()
        var changed = false
        var error: String? = null
        var level = includesOf(raw)
        var depth = 0
        while (level.isNotEmpty() && depth < MAX_INCLUDE_DEPTH) {
            val next = mutableListOf<String>()
            for (name in level) {
                if (name in parts) continue
                val url = URL(base, name)
                if (url.host != base.host) continue
                dir.mkdirs()
                val file = File(dir, name)
                // A stored part without metadata (a catalog URL change, an unreadable meta.json)
                // is still the good copy: a failed download keeps it.
                val p = prev[name]?.takeIf { file.exists() } ?: if (file.exists()) PartMeta() else null
                when (val r = fetch(url.toString(), file, p?.etag, p?.lastModified, p?.let { Held(it.heldBack, it.heldRules, it.heldSince) } ?: Held(), now)) {
                    is Fetch.Updated -> {
                        changed = true
                        parts[name] = PartMeta(r.etag, r.lastModified)
                    }
                    Fetch.NotModified -> parts[name] = p ?: PartMeta()
                    is Fetch.Same -> parts[name] = PartMeta(r.etag, r.lastModified)
                    is Fetch.Shrunk -> {
                        error = "$name: ${r.reason}"
                        if (p != null) parts[name] = p.copy(heldBack = r.held.bytes, heldRules = r.held.rules, heldSince = r.held.since)
                    }
                    is Fetch.Failed -> {
                        error = "$name: ${r.reason}"
                        if (p != null) parts[name] = p
                    }
                }
                if (file.exists()) next += includesOf(file)
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
        return Includes(parts, changed, error)
    }

    private fun includesOf(f: File): List<String> =
        f.useLines { lines -> lines.mapNotNull { INCLUDE.matchEntire(it.trim())?.groupValues?.get(1) }.toList() }

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
        val meta = store.readMeta()
        // The user's own element-hiding and URL rules join the page filter as one more list.
        val userWeb = UserRules.webPart(settings.userRules)
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
        // cached page's revalidation marker, alone. Working that out reads and parses the
        // engine's side table (~2 s of CPU on a test phone), so it is redone only when that file
        // or the DNS table changed.
        fun digestKey() = "${sig(store.webExtra)}|${sig(store.dnsTable)}"
        fun dnsDigest(): String {
            val key = digestKey()
            if (key == meta.webDigestKey && meta.webDigest.isNotEmpty()) return meta.webDigest
            return NativeCore.nativeWebDnsDigest(store.webTable.path, if (store.dnsTable.exists()) store.dnsTable.path else "")
        }
        val digest = dnsDigest()
        val signature = "$base|dns=$digest"
        if (!force && store.webTable.exists() && meta.webSignature == signature) {
            if (meta.webDigestKey != digestKey()) store.writeMeta(meta.copy(webDigestKey = digestKey(), webDigest = digest))
            return false
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
        val out = Json.parseToJsonElement(NativeCore.nativeCompileWeb(spec.toString(), store.webTable.path, dns)).jsonObject
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
        // The new engine's side table decides the digest now (computed once, then cached).
        val newDigest = NativeCore.nativeWebDnsDigest(store.webTable.path, if (store.dnsTable.exists()) store.dnsTable.path else "")
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
    private fun fetch(url: String, target: File, etag: String?, lastModified: String?, held: Held, now: Long): Fetch {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return Fetch.Failed(e.message ?: "bad url")
        }
        // Unique per download: two updates running at once never write or delete each other's file.
        val tmp = File(target.path + ".part-" + android.os.Process.myPid() + "-" + System.nanoTime())
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
                HttpURLConnection.HTTP_NOT_MODIFIED -> Fetch.NotModified
                HttpURLConnection.HTTP_OK -> {
                    var total = 0L
                    conn.inputStream.use { input ->
                        tmp.outputStream().buffered().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw IOException("list larger than 64 MiB")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    if (!looksLikeFilterList(tmp)) {
                        tmp.delete()
                        return Fetch.Failed("not a filter list")
                    }
                    // Same measure on both copies (rule lines of this file, includes apart).
                    val before = if (target.exists() && tmp.length() != held.bytes) ruleLines(target) else 0
                    if (before > 1000) {
                        val n = ruleLines(tmp)
                        val confirmed = (held.rules > 0 && Math.abs(n - held.rules) * 10 <= held.rules) ||
                            (held.since > 0 && now - held.since >= HOLD_MAX_MS)
                        if (n * 2 < before && !confirmed) {
                            val size = tmp.length()
                            tmp.delete()
                            val since = if (held.since > 0) held.since else now
                            return Fetch.Shrunk("the list shrank from ${"%,d".format(java.util.Locale.US, before)} to ${"%,d".format(java.util.Locale.US, n)} rules; kept the previous copy", Held(size, n, since))
                        }
                    }
                    // Some servers answer every request in full (no validators, or they change
                    // them): the same bytes again must not touch the stored file, whose size and
                    // mtime decide whether the engines are rebuilt.
                    if (sameContent(tmp, target)) {
                        tmp.delete()
                        return Fetch.Same(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
                    }
                    // rename(2) on Android; also replaces the stored copy on a PC's JVM (the tests).
                    java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    Fetch.Updated(conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"), total)
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

    private fun sameContent(a: File, b: File): Boolean {
        if (!b.exists() || a.length() != b.length()) return false
        a.inputStream().buffered(64 * 1024).use { x ->
            b.inputStream().buffered(64 * 1024).use { y ->
                val bx = ByteArray(64 * 1024)
                val by = ByteArray(64 * 1024)
                while (true) {
                    val n = x.readNBytes(bx, 0, bx.size)
                    val m = y.readNBytes(by, 0, by.size)
                    if (n != m || !bx.copyOf(n).contentEquals(by.copyOf(m))) return false
                    if (n == 0) return true
                }
            }
        }
    }

    /** Lines that are rules (not blank, not a comment or a header), counted cheaply. */
    private fun ruleLines(f: File): Int = f.useLines { lines ->
        lines.count { raw ->
            val l = raw.trim()
            l.isNotEmpty() && !l.startsWith("!") && !l.startsWith("[") && !(l.startsWith("#") && !l.startsWith("##") && !l.startsWith("#@#") && !l.startsWith("#?#"))
        }
    }

    /** Rejects HTML error pages, captive-portal responses and binary junk. */
    private fun looksLikeFilterList(f: File): Boolean {
        if (f.length() == 0L) return false
        val head = f.inputStream().use { s -> ByteArray(4096).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
        if (head.any { it == 0.toByte() }) return false
        val text = head.decodeToString().trimStart().lowercase()
        return !(text.startsWith("<!doctype html") || text.startsWith("<html"))
    }
}
