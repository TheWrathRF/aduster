package app.aduster.core

import android.content.Context
import app.aduster.settings.Settings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.TimeZone

/** Maps [Settings] to the Rust engine's config JSON (see `dnsengine::Config`). */
object EngineConfig {
    const val CACHE_SIZE = 4096

    fun statsFile(context: Context) = File(context.filesDir, "stats.json")

    /**
     * [pauseLeftMs]: what is left of the pause (0 = none, [Long.MAX_VALUE] = until Resume). The
     * engine turns it into its own boot-clock deadline, so a wall-clock change does
     * not move the end of a pause.
     */
    fun json(context: Context, s: Settings, pauseLeftMs: Long): String {
        val now = System.currentTimeMillis()
        return buildJsonObject {
            put("upstream", Upstreams.json(s.upstream, s.customUpstream))
            put("block_mode", s.blockMode.wire)
            put("blocked_ttl", s.blockedTtl)
            put("log_capacity", if (s.logEnabled) s.logSize else 0)
            put("resolve_uids", s.resolveApps)
            put("cname_check", s.cnameCheck)
            put("optimistic_cache", s.optimisticCache)
            put("cache_size", CACHE_SIZE)
            put("paused_until_ms", if (s.pausedUntil > now) s.pausedUntil else 0L)
            put("pause_left_ms", pauseLeftMs)
            put("tz_offset_s", TimeZone.getDefault().getOffset(now) / 1000)
            put("stats_path", statsFile(context).path)
        }.toString()
    }
}
