package app.aduster.vpn

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.aduster.core.NativeCore
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stall reports: a snapshot of what the core recorded (dead connections, retries, DNS failures)
 * and what is waiting right now, saved where it can be read later (Downloads, also over adb).
 * Must run in the :core process, which owns the engine.
 */
object Reports {
    /** Writes Downloads/Aduster_report_<time>.json and returns its name, or null on failure. */
    fun save(ctx: Context): String? {
        // Recent process exits (crashes, kills) ride along: the system keeps them, not the engine.
        // So does the protection timeline (when it was on, off, paused, and why).
        val exits = ProcessExits.json(ProcessExits.recent(ctx)).toString()
        val timeline = Timeline.json(Timeline.read(ctx)).toString()
        val extra = "\"process_exits\":$exits,\"timeline\":$timeline,\"build\":${build(ctx)}"
        val native = NativeCore.nativeReport().trim()
        val json = if (native.endsWith("}") && native.length > 2) native.dropLast(1) + ",$extra}" else "{$extra}"
        val name = "Aduster_report_" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".json"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(json.encodeToByteArray()) } ?: error("no stream")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            prune(ctx, name)
            name
        } catch (_: Exception) {
            // No half-written pending entry left behind.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Which APK wrote the report: its SHA-256 identifies the exact build, so a report is never
     * read against the wrong sources.
     */
    private fun build(ctx: Context): String {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val sha = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            File(ctx.applicationInfo.sourceDir).inputStream().use { s ->
                val b = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(b)
                    if (n < 0) break
                    md.update(b, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
        return "{\"version\":\"${pi?.versionName.orEmpty()}\",\"installed\":${pi?.lastUpdateTime ?: 0},\"apk_sha256\":\"$sha\"}"
    }

    /**
     * Deletes the reports this install saved (they name hosts that were visited); returns how
     * many. Android shows an app only its own files in Downloads, so those of an earlier install
     * (before an uninstall) are out of reach: [deletedText] says so.
     */
    fun deleteAll(ctx: Context): Int =
        runCatching {
            ctx.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                arrayOf(likeLiteral("Aduster_report_") + "%.json"),
            )
        }.getOrDefault(0)

    /** Of the saved reports' [names], those beyond the newest [keep] (the name holds the time). */
    internal fun beyondNewest(names: List<String>, keep: Int = KEEP, justSaved: String? = null): List<String> {
        // The report just saved always stays, also when a clock set back gave it the oldest name.
        val kept = if (justSaved != null && justSaved in names) 1 else 0
        return names.filter { REPORT.matches(it) && it != justSaved }.sortedDescending().drop((keep - kept).coerceAtLeast(0))
    }

    /** A report's name, also as the system renames a second one of the same second ("… (1).json"). */
    private val REPORT = Regex("""^Aduster_report_\d{8}-\d{6}( \(\d+\))?\.json$""")

    /** This install's reports in Downloads, name to MediaStore id. */
    private fun saved(ctx: Context): Map<String, Long> {
        val found = HashMap<String, Long>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                arrayOf(likeLiteral("Aduster_report_") + "%.json"),
                null,
            )?.use { c -> while (c.moveToNext()) found[c.getString(1)] = c.getLong(0) }
        }
        return found
    }

    /**
     * Of the [found] reports, the id of the one saved as [name]: the newest of it and the copies
     * the system renamed when a report of the same second already existed ("… (1).json").
     */
    internal fun savedId(found: Map<String, Long>, name: String): Long? {
        val stem = name.removeSuffix(".json")
        val copy = Regex("^" + Regex.escape(stem) + """( \(\d+\))?\.json$""")
        return found.filter { (n, _) -> copy.matches(n) }.values.maxOrNull()
    }

    /**
     * The share menu for the report just saved as [name] (the main process can read it: both
     * processes are one app to MediaStore); null when it is not found.
     */
    fun shareIntent(ctx: Context, name: String): android.content.Intent? {
        val id = savedId(saved(ctx), name) ?: return null
        val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Aduster report")
            // The clip carries the read grant through the chooser to the app picked.
            clipData = android.content.ClipData.newRawUri(name, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return android.content.Intent.createChooser(send, "Share report")
    }

    /** Deletes this install's reports beyond the newest [KEEP]. */
    private fun prune(ctx: Context, justSaved: String? = null) {
        val resolver = ctx.contentResolver
        val found = saved(ctx)
        for (name in beyondNewest(found.keys.toList(), justSaved = justSaved)) {
            val id = found[name] ?: continue
            runCatching { resolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }

    /** Reports kept in Downloads: older ones go when a new one is saved (they name visited hosts). */
    const val KEEP = 10

    /** [s] matched as written in a LIKE pattern with `ESCAPE '\'` (`_` and `%` are wildcards). */
    internal fun likeLiteral(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** What Delete saved reports did, in words. */
    fun deletedText(n: Int): String =
        if (n > 0) "Deleted $n report${if (n == 1) "" else "s"}" else "No reports from this install; an earlier install's stay in Downloads"
}
