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
     * from the defaults and carry none of a report's fields.
     */
    fun decode(text: String, current: Settings, pages: Boolean = true): Preview? {
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
        return Preview(s, summary(current, s, pages))
    }

    /** Keys of a saved report (and of other JSON the picker may offer) that settings never have. */
    private val REPORT_KEYS = setOf("engine", "proxy", "timeline", "process_exits", "problems", "build", "time_ms")

    /** [pages]: this build has the page engine; without it, its settings are not mentioned. */
    private fun summary(old: Settings, new: Settings, pages: Boolean): List<String> = buildList {
        fun rules(s: Settings) = s.userRules.lines().count { it.isNotBlank() && !it.trimStart().startsWith("!") }
        add("${rules(new)} rules of your own (now ${rules(old)})")
        add("${new.customLists.size} custom lists, ${new.listOverrides.size} list switches")
        if (pages) {
            add("Page filtering ${if (new.httpsFiltering) "on" else "off"}" + if (new.httpsFiltering != old.httpsFiltering) " (changes)" else "")
            add("${new.httpsExclusions.lines().count { it.isNotBlank() }} never-decrypted sites of your own")
        }
        add("${new.excludedApps.size} excluded apps")
    }
}
