package app.aduster.settings

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The settings export file: `{"format": "aduster-settings", "version": 1, "settings": {...}}`.
 * The marker is what an import checks first: without it, any JSON object would decode to
 * all-default settings, so picking a saved report by mistake would wipe every rule and list.
 */
object SettingsBackup {
    const val FORMAT = "aduster-settings"
    private const val VERSION = 1
    /** The settings as they were before the last import, for "Undo import" (files dir). */
    const val BEFORE_IMPORT = "settings.before-import.json"

    /** An import that was read but not applied: what it holds, and in words what changes. */
    data class Preview(val settings: Settings, val summary: List<String>)

    fun encode(s: Settings): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("settings", SettingsJson.encodeToJsonElement(Settings.serializer(), s))
    }.toString()

    /**
     * [text] as settings, or null when it is not an Aduster settings file. Files exported before
     * the marker existed (a bare settings object) are accepted only if they set something away
     * from the defaults and carry none of a report's fields. [appName]: an excluded app's name
     * from its package.
     */
    fun decode(text: String, current: Settings, pages: Boolean = true, appName: (String) -> String = { it }): Preview? {
        val o = runCatching { SettingsJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val inner: JsonObject = when {
            o["format"]?.jsonPrimitive?.content == FORMAT -> {
                if ((o["version"]?.jsonPrimitive?.intOrNull ?: 0) > VERSION) return null
                o["settings"] as? JsonObject ?: return null
            }
            o.keys.any { it in REPORT_KEYS } -> return null
            else -> o
        }
        val s = runCatching { SettingsJson.decodeFromJsonElement(Settings.serializer(), inner) }.getOrNull()?.sanitized() ?: return null
        if (o["format"] == null && s.copy(enabled = false, pausedUntil = 0) == Settings()) return null
        return Preview(s, summary(current, s, pages, appName))
    }

    /** Keys of a saved report (and of other JSON the picker may offer) that settings never have. */
    private val REPORT_KEYS = setOf("engine", "proxy", "timeline", "process_exits", "problems", "build", "time_ms")

    /**
     * A setting the protection process reads, as the import preview names it: [keys] are its
     * stored fields, [change] what an import does to it in words (null: nothing), from the old
     * and new settings and an app's name; [pages]: only in a build with the page engine.
     */
    private class Field(val name: String, vararg val keys: String, val pages: Boolean = false, val change: (Settings, Settings, (String) -> String) -> String?)

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /** A single value: "new (now old)" when it differs. */
    private fun value(read: (Settings) -> String) = { old: Settings, new: Settings, _: (String) -> String ->
        val (before, after) = read(old) to read(new)
        if (before == after) null else "$after (now $before)"
    }

    /**
     * A set: what comes and what goes, by the values themselves (a change that keeps the count,
     * or the first names, is named too).
     */
    private fun members(old: Set<String>, new: Set<String>, show: (String) -> String): String? {
        if (old == new) return null
        fun list(xs: Set<String>): String {
            val names = xs.map(show).sortedBy { it.lowercase() }
            return if (names.size <= 4) names.joinToString(", ") else names.take(3).joinToString(", ") + " and ${names.size - 3} more"
        }
        return listOfNotNull((new - old).takeIf { it.isNotEmpty() }?.let { "adds ${list(it)}" }, (old - new).takeIf { it.isNotEmpty() }?.let { "removes ${list(it)}" })
            .joinToString("; ")
    }

    private fun hosts(s: Settings) = s.httpsExclusions.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Every field :core reads but the two an import never changes ([KEPT_FIELDS]), most consequential first. */
    private val FIELDS = listOf(
        Field("DNS server", "upstream", "customUpstream", change = value { app.aduster.core.Upstreams.label(it.upstream, it.customUpstream) }),
        Field("Block DNS bypass", "blockDnsBypass", change = value { onOff(it.blockDnsBypass) }),
        Field("Excluded apps", "excludedApps") { old, new, name -> members(old.excludedApps, new.excludedApps, name) },
        Field("Page filtering", "httpsFiltering", pages = true, change = value { onOff(it.httpsFiltering) }),
        Field("Never-decrypted sites of your own", "httpsExclusions", pages = true) { old, new, _ -> members(hosts(old), hosts(new)) { it } },
        Field("IPv4-only answers", "ipv4Only", change = value { onOff(it.ipv4Only) }),
        Field("Reply to blocked lookups", "blockMode", change = value { it.blockMode.label }),
        Field("Lifetime of blocked answers", "blockedTtl", change = value { "${it.blockedTtl} s" }),
        Field("Block disguised trackers", "cnameCheck", change = value { onOff(it.cnameCheck) }),
        Field("Optimistic cache", "optimisticCache", change = value { onOff(it.optimisticCache) }),
        Field("Keep a log", "logEnabled", change = value { onOff(it.logEnabled) }),
        Field("Log size", "logSize", change = value { "${it.logSize} entries" }),
        Field("Show app names", "resolveApps", change = value { onOff(it.resolveApps) }),
    )

    /** Fields an import leaves as they are (protection on or off, a pause): see `MainViewModel.replaceSettings`. */
    val KEPT_FIELDS = setOf("enabled", "pausedUntil")

    /** The stored fields the preview names: with [KEPT_FIELDS] and [UI_ONLY_FIELDS], every field there is. */
    val PREVIEWED_FIELDS: Set<String> = FIELDS.flatMap { it.keys.toList() }.toSet() + "userRules"

    /**
     * [pages]: this build has the page engine; without it, its settings are not mentioned.
     * The rules and lists are always counted; every other protection setting is named when the
     * import changes it, apps by [appName].
     */
    private fun summary(old: Settings, new: Settings, pages: Boolean, appName: (String) -> String): List<String> = buildList {
        add("${app.aduster.filters.UserRules.ruleCount(new.userRules)} rules of your own (now ${app.aduster.filters.UserRules.ruleCount(old.userRules)})")
        add("${new.customLists.size} custom lists, ${new.listOverrides.size} list switches")
        for (f in FIELDS) {
            if (f.pages && !pages) continue
            f.change(old, new, appName)?.let { add("${f.name}: $it") }
        }
    }
}
