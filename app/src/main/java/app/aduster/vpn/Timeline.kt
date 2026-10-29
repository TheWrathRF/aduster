package app.aduster.vpn

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * When protection started, stopped, paused and resumed, and why (the latest 50), so a gap in
 * protection can always be explained; Diagnostics and every report show it. Written by the :core
 * process only (whole file replaced at once), read by both.
 */
object Timeline {
    /**
     * [code]: a machine-readable cause, so Diagnostics never depends on the wording
     * of [reason]: stops "user_off", "revoked", "app_updated", "process_ended", "failure"; a resume
     * "off" when turning off ended a pause.
     */
    data class Entry(val time: Long, val event: String, val reason: String?, val code: String? = null)

    const val STARTED = "started"
    const val STOPPED = "stopped"
    const val PAUSED = "paused"
    const val RESUMED = "resumed"

    private const val MAX = 50
    private const val FILE = "timeline.json"

    /** [time]: when it happened (default now; earlier for a stop learned afterwards). */
    @Synchronized
    fun add(ctx: Context, event: String, reason: String? = null, time: Long = System.currentTimeMillis(), code: String? = null) {
        val list = (read(ctx) + Entry(time, event, reason, code)).sortedBy { it.time }.takeLast(MAX)
        val dir = ctx.filesDir
        runCatching {
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(json(list).toString())
            tmp.renameTo(File(dir, FILE))
        }
    }

    /** Oldest first. */
    fun read(ctx: Context): List<Entry> =
        runCatching {
            Json.parseToJsonElement(File(ctx.filesDir, FILE).readText()).jsonArray.map { e ->
                val o = e.jsonObject
                Entry(o["t"]!!.jsonPrimitive.long, o["e"]!!.jsonPrimitive.content, o["r"]?.jsonPrimitive?.content, o["c"]?.jsonPrimitive?.content)
            }
        }.getOrDefault(emptyList())

    fun json(list: List<Entry>): JsonArray = buildJsonArray {
        list.forEach { e ->
            add(buildJsonObject {
                put("t", e.time)
                put("e", e.event)
                e.reason?.let { put("r", it) }
                e.code?.let { put("c", it) }
            })
        }
    }
}
