package app.aduster.filters

import android.os.Build
import android.os.LocaleList

/**
 * What the list defaults depend on: the phone's languages (ISO 639 codes, in the order the
 * person set them) and its maker as Android reports it.
 */
data class Device(val languages: List<String>, val manufacturer: String) {
    /** The id of the maker's own telemetry list, or null for a maker without one. */
    val brandList: String?
        get() = manufacturer.trim().lowercase().let { m -> BRAND_LISTS.entries.firstOrNull { m in it.key }?.value }

    companion object {
        /** HaGeZi's native tracker lists, by the `Build.MANUFACTURER` values of each maker's phones. */
        private val BRAND_LISTS = mapOf(
            setOf("xiaomi", "redmi", "poco") to FilterCatalog.XIAOMI_LIST,
            setOf("samsung") to "hagezi-samsung",
            setOf("huawei", "honor") to "hagezi-huawei",
            setOf("vivo") to "hagezi-vivo",
            setOf("oppo", "realme", "oneplus") to "hagezi-oppo-realme",
        )

        /** This phone; empty off-device (plain JVM tests), where the Android classes are stubs. */
        fun current(): Device = runCatching {
            val locales = LocaleList.getDefault()
            Device((0 until locales.size()).map { locales[it].language }, Build.MANUFACTURER.orEmpty())
        }.getOrDefault(Device(emptyList(), ""))
    }
}
