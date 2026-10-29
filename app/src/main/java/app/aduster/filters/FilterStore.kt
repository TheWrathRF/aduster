package app.aduster.filters

import android.content.Context
import app.aduster.settings.SettingsJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile

/**
 * Validators of one downloaded file, for conditional requests, and the size of a download held
 * back because it shrank (as [ListMeta.heldBack], so a shrunk part is not refused on every update).
 */
@Serializable
data class PartMeta(val etag: String? = null, val lastModified: String? = null, val heldBack: Long = 0, val heldRules: Int = 0, val heldSince: Long = 0)

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
    /** Install time of the app version whose job last checked the engines. */
    val appVersion: Long = 0,
)

/** On-disk layout under files/filters. Shared by the UI/worker and :core processes. */
class FilterStore(context: Context) {
    val dir = File(context.filesDir, "filters").apply { mkdirs() }
    val rawDir = File(dir, "raw").apply { mkdirs() }
    val builtinDir = File(dir, "builtin").apply { mkdirs() }
    val dnsTable = File(dir, "dns.admx")
    /** Serialized page-filtering engine for the browsers' page filter. */
    val webTable = File(dir, "web.dat")
    /** The page engine's side table (exception hosts, HTML filters, regex cosmetics). */
    val webExtra = File(dir, "web.extra")
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

    fun writeMeta(meta: FilterMeta) {
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(SettingsJson.encodeToString(FilterMeta.serializer(), meta))
        tmp.renameTo(metaFile)
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
        val processLock = java.util.concurrent.locks.ReentrantLock()
    }
}
