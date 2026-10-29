package app.aduster.vpn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Why Aduster's processes ended recently, as the system recorded it (crash, ANR, low memory,
 * killed for excessive CPU, stopped by the user…). Otherwise such an end (say, the UI process
 * killed for excessive CPU) is visible only over adb; Diagnostics and every report show it.
 */
object ProcessExits {
    data class Exit(
        val process: String,
        val time: Long,
        val reason: String,
        val description: String?,
        /** Process state when it ended ("foreground service", "cached", …). */
        val state: String,
        val pssKb: Long,
        val rssKb: Long,
    ) {
        /** Worth a second look: not a normal end (user, update, stop, memory reclaim of a cached process). */
        val notable: Boolean
            get() = reason in setOf("crash", "native crash", "ANR", "excessive resource use", "initialization failure") ||
                (reason == "low memory" && state != "cached")
    }

    fun recent(ctx: Context, max: Int = 10): List<Exit> =
        runCatching {
            ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(ctx.packageName, 0, max).map { e ->
                Exit(e.processName, e.timestamp, reason(e.reason), e.description, state(e.importance), e.pss, e.rss)
            }
        }.getOrDefault(emptyList())

    fun json(exits: List<Exit>): JsonArray = buildJsonArray {
        exits.forEach { e ->
            add(buildJsonObject {
                put("process", e.process)
                put("t", e.time)
                put("reason", e.reason)
                e.description?.let { put("detail", it) }
                put("state", e.state)
                put("pss_kb", e.pssKb)
                put("rss_kb", e.rssKb)
            })
        }
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user request"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "unknown"
    }

    private fun state(importance: Int) = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        else -> "gone"
    }
}
