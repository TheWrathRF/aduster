package app.aduster.filters

import android.content.Context
import app.aduster.settings.SettingsJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile

/**
 * What an update needs to know about a stored list file, taken while it was downloaded, so a
 * check that finds it unchanged reads nothing: SHA-256, rule lines (the shrink guard's measure)
 * and the names it pulls in with `!#include`. They hold only while the file still has its [size]
 * and [mtime] (another update, or one cut short before it saved this, may have replaced it).
 */
@Serializable
data class FileScan(val size: Long, val sha256: String, val lines: Int, val includes: List<String> = emptyList(), val mtime: Long = 0) {
    fun describes(f: java.io.File): Boolean = size == f.length() && mtime == f.lastModified()
}

/**
 * Validators of one downloaded file, for conditional requests, and the size of a download held
 * back because it shrank (as [ListMeta.heldBack], so a shrunk part is not refused on every update).
 */
@Serializable
data class PartMeta(
    val etag: String? = null,
    val lastModified: String? = null,
    val heldBack: Long = 0,
    val heldRules: Int = 0,
    val heldSince: Long = 0,
    val scan: FileScan? = null,
)

@Serializable
data class ListMeta(
    val etag: String? = null,
    val lastModified: String? = null,
    /** When the content last changed (0 = bundled snapshot). */
    val updatedAt: Long = 0,
    val checkedAt: Long = 0,
    val rules: Int = 0,
    val bytes: Long = 0,
    val error: String? = null,
    /** URL the stored copy came from; a catalog URL change invalidates the validators. */
    val url: String? = null,
    /** Files pulled in with `!#include` (uBO lists), by name. */
    val parts: Map<String, PartMeta> = emptyMap(),
    /** Web lists: hostname rules left to the DNS layer, which already blocks those hosts. */
    val dnsCovered: Int = 0,
    /** Size of a download held back because it shrank; the same size again is accepted. */
    val heldBack: Long = 0,
    /** Its rule count (within 10 % again is accepted) and when holding back began (7 days at most). */
    val heldRules: Int = 0,
    val heldSince: Long = 0,
    /** The stored file, as read while it was downloaded (see [FileScan]). */
    val scan: FileScan? = null,
)

@Serializable
data class FilterMeta(
    val lists: Map<String, ListMeta> = emptyMap(),
    val compiledAt: Long = 0,
    val compiledRules: Int = 0,
    val compiledSignature: String = "",
    val lastRun: Long = 0,
    val webCompiledAt: Long = 0,
    val webRules: Int = 0,
    val webSignature: String = "",
    val webError: String? = null,
    /** Size and mtime of the page engine's side table and the DNS table when [webDigest] was worked out. */
    val webDigestKey: String = "",
    /** Which exception hosts of the page lists the DNS table blocks (native digest). */
    val webDigest: String = "",
    /** Rule count of the user's own page engine (included in [webRules]). */
    val webUserRules: Int = 0,
    /** Signature of the user's own page engine (text hash, format, and DNS size:mtime when needed). */
    val webUserSignature: String = "",
    /** True when the user's page rules name an exception host (so a DNS table change matters). */
    val webUserDependsDns: Boolean = false,
    /** Size and mtime of the user's page engine and its side table when compiled. */
    val webUserFiles: String = "",
    /** Install time of the app version whose job last checked the engines. */
    val appVersion: Long = 0,
)

/** On-disk layout under files/filters. Shared by the UI/worker and :core processes. */
class FilterStore(val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "filters"))

    init {
        dir.mkdirs()
    }

    val rawDir = File(dir, "raw").apply { mkdirs() }
    val builtinDir = File(dir, "builtin").apply { mkdirs() }
    val dnsTable = File(dir, "dns.admx")
    /** Serialized page-filtering engine for the browsers' page filter. */
    val webTable = File(dir, "web.dat")
    /** The page engine's second file, which the DNS digest reads. */
    val webExtra = File(dir, "web.extra")
    val webCount = File(dir, "web.count")
    /** Separate pair for the user's own page rules, loaded alongside [webTable]. */
    val webUserTable = File(dir, "web-user.dat")
    val webUserExtra = File(dir, "web-user.extra")
    val webUserCount = File(dir, "web-user.count")
    /** uBlock Origin's scriptlets and redirect files, bundled in the APK. */
    val uboResources = File(builtinDir, "ubo-resources.json")
    /** Redirect files AdGuard's lists name and uBO lacks, bundled in the APK. */
    val extraResources = File(builtinDir, "extra-resources.json")
    private val metaFile = File(dir, "meta.json")
    private val lockFile = File(dir, ".lock")

    fun raw(id: String) = File(rawDir, "$id.txt")

    /**
     * Resource files for the page engine, '|'-separated: uBlock Origin's, then our additions
     * (both copied out of the APK after an install or update; the first file naming a resource
     * wins).
     */
    fun resourcePaths(context: Context): String {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        for (f in listOf(uboResources, extraResources)) {
            if (!f.exists() || f.lastModified() < installed) {
                val tmp = File(f.path + ".part")
                context.assets.open("builtin/${f.name}").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(f)
            }
        }
        return listOf(uboResources, extraResources).filter { it.exists() }.joinToString("|") { it.path }
    }

    /** Directory for the `!#include` files of list [id]. */
    fun includes(id: String) = File(rawDir, "$id.inc")

    fun readMeta(): FilterMeta =
        try {
            if (metaFile.exists()) SettingsJson.decodeFromString(FilterMeta.serializer(), metaFile.readText()) else FilterMeta()
        } catch (_: Exception) {
            FilterMeta()
        }

    /** Replaces meta.json whole; a failed write or rename throws (the job tries again later). */
    fun writeMeta(meta: FilterMeta) {
        val tmp = File(dir, META_TMP)
        tmp.writeText(SettingsJson.encodeToString(FilterMeta.serializer(), meta))
        java.nio.file.Files.move(tmp.toPath(), metaFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Temporary files a write cut short leaves (the process killed, a full disk): the page
     * engine's pair (~16 MB), the DNS table's and meta.json's. Removed under the lock, where no
     * compile or meta write is under way.
     */
    fun removeLeftovers() {
        for (name in listOf(
            META_TMP, "dns.admx.tmp",
            "web.tmp", "web.extra.tmp", "web.count.tmp",
            "web-user.tmp", "web-user.extra.tmp", "web-user.count.tmp",
        )) File(dir, name).delete()
    }

    /**
     * Exclusive section across threads (monitor) and processes (file lock): downloads, compiles
     * and meta writes never interleave.
     */
    fun <T> locked(block: () -> T): T {
        processLock.lock()
        try {
            return RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use { block() }
            }
        } finally {
            processLock.unlock()
        }
    }

    /**
     * [locked], but gives up after [timeoutMs] (null): the always-on process must not wait for
     * a compile the main process is running, or for a main process the freezer stopped while it
     * held the lock.
     */
    fun <T> tryLocked(timeoutMs: Long, block: () -> T): T? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        if (!processLock.tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
        try {
            RandomAccessFile(lockFile, "rw").use { raf ->
                while (System.nanoTime() < deadline) {
                    raf.channel.tryLock()?.let { lock -> return lock.use { block() } }
                    Thread.sleep(50)
                }
                return raf.channel.tryLock()?.use { block() }
            }
        } finally {
            processLock.unlock()
        }
    }

    private companion object {
        const val META_TMP = "meta.json.tmp"
        val processLock = java.util.concurrent.locks.ReentrantLock()
    }
}
