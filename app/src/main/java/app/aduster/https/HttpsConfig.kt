package app.aduster.https

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.aduster.settings.Settings

/** An app that opens web links. */
data class Browser(val pkg: String, val uid: Int, val name: String)

object HttpsConfig {
    @Volatile
    private var browsers: List<Browser>? = null

    /** Whether this build has the page engine: a build from the public source has not. */
    val available: Boolean by lazy { runCatching { app.aduster.core.NativeCore.nativeProxyAvailable() }.getOrDefault(false) }

    /**
     * Why page filtering cannot run, as Home names it (see `Stats.httpsState`), or null when it
     * can. In this order, so the reason given is the first one to fix.
     */
    fun blocker(available: Boolean, enabled: Boolean, hasBrowser: Boolean, caExists: Boolean, caTrusted: Boolean): String? = when {
        !enabled -> "off"
        !available -> "unavailable"
        !hasBrowser -> "no_browser"
        !caExists -> "ca_missing"
        !caTrusted -> "ca_untrusted"
        else -> null
    }

    /**
     * The apps that open `https://` links of any site (a browsable view of a plain web address):
     * every browser, with no list of package names. Only their connections can be filtered, and
     * the page engine decrypts only a Chromium handshake among them; every other app's traffic
     * is tunnelled untouched. Custom Tabs run inside the browser's own app, so they are covered.
     * Kept until [forgetBrowsers] (an app installed, removed or changed).
     */
    fun browsers(ctx: Context): List<Browser> = browsers ?: run {
        val pm = ctx.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        pm.queryIntentActivities(view, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            .mapNotNull { r ->
                val app = r.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (app.packageName == ctx.packageName) return@mapNotNull null
                Browser(app.packageName, app.uid, r.loadLabel(pm).toString())
            }
            .distinctBy { it.uid }
            .sortedBy { it.name.lowercase() }
            .also { browsers = it }
    }

    fun forgetBrowsers() {
        browsers = null
    }

    /**
     * Hosts that skip the proxy entirely (ProxyInfo exclusion list): bulk media and app-update
     * traffic where filtering adds nothing and relaying would only cost CPU and battery. The
     * browser then fetches them itself (its DNS still goes through Aduster's filter). Video segments are
     * the big one: through the filter, every byte of a live stream is decrypted and encrypted
     * again. The list stays small and fixed (changing it re-establishes the VPN): new video CDNs
     * are handled at run time by the proxy's media bypass, not by growing this list.
     */
    val PROXY_BYPASS = listOf(
        "localhost", "127.0.0.1", "*.local", "*.lan",
        // The home network (router pages, NAS, casting): nothing to filter, no reason to relay.
        // Android's exclusion syntax takes wildcards, not CIDR (one invalid entry voids the proxy).
        "10.*", "192.168.*", "169.254.*",
        "172.16.*", "172.17.*", "172.18.*", "172.19.*", "172.20.*", "172.21.*", "172.22.*", "172.23.*",
        "172.24.*", "172.25.*", "172.26.*", "172.27.*", "172.28.*", "172.29.*", "172.30.*", "172.31.*",
        "*.googlevideo.com", "*.gvt1.com", "*.play.googleapis.com",
        "*.whatsapp.net", "*.whatsapp.com",
        "*.fbcdn.net", "*.cdninstagram.com",
        "*.nflxvideo.net", "*.scdn.co", "*.spotifycdn.com",
        "*.tiktokcdn.com", "*.tiktokv.com",
        // Live and recorded video: Kick (Amazon IVS), Twitch segments, X, Reddit, YouTube images.
        "*.live-video.net", "*.hls.ttvnw.net", "video.twimg.com", "v.redd.it",
        "*.ytimg.com", "*.ggpht.com",
    )

    /**
     * Government sites by rule, not by a list per country: the `gov`, `mil` and `int` top-level
     * domains, and the second-level labels countries use for government directly under every
     * two-letter country code (gov.uk, gouv.fr, gob.mx, go.jp, govt.nz, gub.uy, gv.at, mil.za ...),
     * plus the two irregular ones, gc.ca and admin.ch. Codes that are not countries match nothing.
     * Never-decrypt entries match a host and every name under it.
     */
    val GOVERNMENT: List<String> = buildList {
        addAll(listOf("gov", "mil", "int", "gc.ca", "admin.ch"))
        for (a in 'a'..'z') for (b in 'a'..'z') {
            for (label in listOf("gov", "gouv", "gob", "govt", "gub", "gv", "mil", "go")) add("$label.$a$b")
        }
    }

    /** Whether [host] is in [set] or under one of its names: how the page engine reads the list. */
    fun neverDecrypted(host: String, set: Set<String>): Boolean {
        var h = host.lowercase()
        while (true) {
            if (h in set) return true
            h = h.substringAfter('.', "").ifEmpty { return false }
        }
    }

    @Volatile
    private var builtin: List<String>? = null

    /** AdGuard's never-decrypt lists as last read, keyed by their files' size and mtime. */
    @Volatile
    private var downloaded: Pair<String, List<String>>? = null

    fun forgetDownloaded() {
        downloaded = null
    }

    /**
     * Built-in sensitive sites, AdGuard's maintained never-decrypt lists (banks and
     * sensitive sites worldwide, when downloaded) and the user's own list, with [GOVERNMENT].
     * Only ever adds hosts: a missing or failed download leaves the built-in floor.
     */
    fun exclusions(ctx: Context, s: Settings): List<String> {
        val builtin = builtin ?: ctx.assets.open("builtin/https-sensitive.txt").bufferedReader().use { it.readLines() }.also { builtin = it }
        return ((builtin + downloadedLines(ctx) + s.httpsExclusions.lines())
            .filter { !isComment(it) && !it.contains('$') }
            .mapNotNull { exclusionHost(it) } + GOVERNMENT)
            .distinct()
    }

    private fun downloadedLines(ctx: Context): List<String> {
        val store = app.aduster.filters.FilterStore(ctx)
        val files = app.aduster.filters.FilterCatalog.exclusionLists.map { store.raw(it.id) }.filter { it.exists() }
        val key = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        downloaded?.let { (k, v) -> if (k == key) return v }
        val lines = files.flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()) }
        downloaded = key to lines
        return lines
    }

    /** Comments: `!`, `#` and AdGuard's `//` lines. */
    private fun isComment(line: String) = line.trim().let { it.isEmpty() || it.startsWith("!") || it.startsWith("#") || it.startsWith("//") }

    private val HOST = Regex("""^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*$""")

    /**
     * The host of one never-decrypt line, also when it was pasted as a URL
     * (`https://bank.example/login` → `bank.example`) or written as `*.bank.example`; null when
     * no host name is left.
     */
    fun exclusionHost(line: String): String? {
        var s = line.trim()
        s = s.substringAfter("://", s)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        s = when {
            s.startsWith("[") -> s.removePrefix("[").substringBefore(']')
            // A bare IPv6 address (more than one colon) has no port to cut off.
            s.count { it == ':' } > 1 -> s
            else -> s.substringBefore(':')
        }
        s = s.removePrefix("*.").trimStart('.').trimEnd('.').lowercase()
        return s.takeIf { it.isNotEmpty() && (HOST.matches(it) || it.contains(':')) }
    }

    /** 1-based numbers of the user's lines that name no host (shown as errors). */
    fun invalidExclusionLines(text: String): List<Int> =
        text.lines().mapIndexedNotNull { i, l -> if (!isComment(l) && exclusionHost(l) == null) i + 1 else null }
}
