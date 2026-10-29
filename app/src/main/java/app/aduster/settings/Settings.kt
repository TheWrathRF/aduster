package app.aduster.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@Serializable
enum class BlockMode(val wire: String, val label: String, val detail: String) {
    @SerialName("null_ip") NULL_IP("null_ip", "Null IP", "Answers 0.0.0.0, so apps stop at once"),
    @SerialName("nxdomain") NXDOMAIN("nx_domain", "NXDOMAIN", "The name does not exist"),
    @SerialName("refused") REFUSED("refused", "Refused", "Answers that the lookup is refused"),
}

/**
 * How the Device list (the phone maker's own telemetry list) is used. Safe exists only for makers
 * with a tested exceptions file (Xiaomi); the serial names are the stored ones.
 */
@Serializable
enum class DeviceProfile(val label: String) {
    @SerialName("safe") SAFE("Safe"),
    @SerialName("aggressive") FULL("Full"),
    @SerialName("off") OFF("Off"),
    ;

    /** [allowlist]: Off turns the list into allow rules (Xiaomi), instead of leaving it unused. */
    fun detail(allowlist: Boolean): String = when (this) {
        SAFE -> "Blocks ads and telemetry; keeps push, themes and cloud sync"
        FULL -> "Blocks the whole list, push and cloud services included"
        OFF -> if (allowlist) "The maker's domains are never blocked, whatever the other lists say" else "Not used"
    }
}

@Serializable
enum class ThemeMode(val label: String) {
    @SerialName("black") BLACK("Black"),
    @SerialName("light") LIGHT("Light"),
    @SerialName("system") SYSTEM("Follow system"),
}

@Serializable
data class CustomList(
    val id: String,
    val name: String,
    val url: String,
    val web: Boolean = false,
)

/**
 * Everything the user can change. Stored as JSON in one multi-process DataStore file, read by
 * both the UI and the :core process.
 */
@Serializable
data class Settings(
    /** Desired protection state; restored after reboot/kill when Always-on VPN is not set. */
    val enabled: Boolean = false,
    val upstream: String = "system",
    val customUpstream: String = "",
    val blockMode: BlockMode = BlockMode.NULL_IP,
    val blockedTtl: Int = 60,
    val logEnabled: Boolean = true,
    val logSize: Int = 2000,
    val resolveApps: Boolean = true,
    val cnameCheck: Boolean = true,
    val optimisticCache: Boolean = true,
    val blockDnsBypass: Boolean = true,
    val pausedUntil: Long = 0,
    val excludedApps: Set<String> = emptySet(),
    /** Per-list on/off overrides of the catalog defaults, keyed by list id. */
    val listOverrides: Map<String, Boolean> = emptyMap(),
    val customLists: List<CustomList> = emptyList(),
    val userRules: String = "",
    /** Null: the maker's default (see FilterCatalog.deviceProfile). Stored under the key `xiaomiProfile`. */
    @SerialName("xiaomiProfile") val deviceProfile: DeviceProfile? = null,
    val autoUpdate: Boolean = true,
    val updateIntervalHours: Int = 24,
    val updateWifiOnly: Boolean = true,
    val updateWhileCharging: Boolean = false,
    val httpsFiltering: Boolean = false,
    /** One host per line; these sites are tunnelled without decryption. */
    val httpsExclusions: String = "",
    val theme: ThemeMode = ThemeMode.BLACK,
) {
    fun isPaused(now: Long = System.currentTimeMillis()) = pausedUntil > now
}

val SettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    coerceInputValues = true
}

/**
 * What the always-on :core process reads of the settings: a change outside it (the
 * theme, the update schedule, list choices, which the compile job applies) must not make :core
 * re-initialise its engine. Built by blanking the fields :core never reads, so a field added
 * later counts as :core's until someone decides otherwise (the safe side).
 */
fun Settings.coreKey(): Settings = copy(
    listOverrides = emptyMap(),
    customLists = emptyList(),
    deviceProfile = null,
    autoUpdate = true,
    updateIntervalHours = 24,
    updateWifiOnly = true,
    updateWhileCharging = false,
    theme = ThemeMode.BLACK,
)

/** Custom list ids are path parts of the list files: only the form the app makes. */
private val CUSTOM_ID = Regex("^custom-[0-9a-f]{8}$")

/**
 * The settings as read or imported, made safe to use: custom lists whose id the app could not
 * have made dropped, one list per id (an edited backup with a repeated id would crash the Filters
 * screen, whose rows are keyed by id), and the numbers within the app's range (the engine refuses
 * a negative count, which would stop protection from starting).
 */
fun Settings.sanitized(): Settings {
    val lists = if (customLists.all { CUSTOM_ID.matches(it.id) } && customLists.distinctBy { it.id }.size == customLists.size) customLists
    else customLists.filter { CUSTOM_ID.matches(it.id) }.distinctBy { it.id }
    val s = copy(
        customLists = lists,
        blockedTtl = blockedTtl.coerceIn(0, 86_400),
        logSize = logSize.coerceIn(500, 10_000),
        updateIntervalHours = updateIntervalHours.coerceIn(6, 24 * 7),
    )
    return if (s == this) this else s
}

private object SettingsSerializer : Serializer<Settings> {
    override val defaultValue = Settings()

    /** settings.json itself, for keeping a copy of a file that cannot be read (see [readFrom]). */
    @Volatile
    var file: File? = null

    override suspend fun readFrom(input: InputStream): Settings {
        val text = input.readBytes().decodeToString()
        return try {
            SettingsJson.decodeFromString(Settings.serializer(), text).sanitized()
        } catch (e: Exception) {
            if (e !is SerializationException && e !is IllegalArgumentException) throw e
            // One bad field must not cost every rule and list: the file is kept
            // as settings.corrupt-<time>.json and every field that still reads is kept.
            file?.let { f -> runCatching { File(f.parentFile, "settings.corrupt-${System.currentTimeMillis()}.json").writeText(text) } }
            salvage(text) ?: throw CorruptionException("settings", e)
        }
    }

    /** The fields of [text] that decode on their own, or null when it is not a JSON object. */
    private fun salvage(text: String): Settings? {
        val obj = runCatching { SettingsJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
        val kept = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in obj) {
            val trial = kotlinx.serialization.json.JsonObject(kept + (k to v))
            if (runCatching { SettingsJson.decodeFromJsonElement(Settings.serializer(), trial) }.isSuccess) kept[k] = v
        }
        return runCatching { SettingsJson.decodeFromJsonElement(Settings.serializer(), kotlinx.serialization.json.JsonObject(kept)).sanitized() }.getOrNull()
    }

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        output.write(SettingsJson.encodeToString(Settings.serializer(), t).encodeToByteArray())
    }
}

object SettingsStore {
    @Volatile
    private var instance: DataStore<Settings>? = null

    fun get(context: Context): DataStore<Settings> =
        instance ?: synchronized(this) {
            instance ?: MultiProcessDataStoreFactory.create(
                serializer = SettingsSerializer,
                // Reached only when nothing could be salvaged (not even JSON); the bad file was kept.
                corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { Settings() },
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { File(context.applicationContext.filesDir, "settings.json").also { SettingsSerializer.file = it } },
            ).also { instance = it }
        }
}
