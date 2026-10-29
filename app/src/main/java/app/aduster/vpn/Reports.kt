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

    /** Deletes the reports saved earlier (they name hosts that were visited); returns how many. */
    fun deleteAll(ctx: Context): Int =
        runCatching {
            ctx.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("Aduster_report_%.json"),
            )
        }.getOrDefault(0)
}
