package app.aduster.filters

import app.aduster.settings.DeviceProfile
import app.aduster.settings.Settings

enum class Group(val title: String) {
    ADS("Ads & tracking"),
    SECURITY("Security"),
    DEVICE("Device"),
    // Shown under the "Page filtering" heading.
    WEB("Ads & privacy"),
    WEB_REGIONAL("Regional"),
    WEB_ANNOYANCES("Annoyances"),
    CUSTOM("Custom"),
}

data class FilterList(
    val id: String,
    val name: String,
    val url: String,
    /** Web lists feed the browsers' page filter; DNS lists feed the system-wide DNS filter. */
    val web: Boolean,
    val group: Group,
    val defaultOn: Boolean,
    val summary: String,
    /** Shipped inside the APK so protection works on first launch, offline. */
    val bundled: Boolean = false,
    /** uBlock Origin's own lists may use its privileged scriptlets, as in uBO. */
    val trusted: Boolean = false,
    /** Drop bare `host$image` rules (e-mail open-tracking pixels, never hit in a browser). */
    val dropImageHosts: Boolean = false,
    /** Aduster's own list: this asset, shipped with the app and never downloaded. */
    val asset: String? = null,
    /** A list of hosts never to decrypt (not a filter list: see [FilterCatalog.exclusionLists]). */
    val neverDecrypt: Boolean = false,
    /**
     * AdGuard's full build of the list with this id: only its trusted scriptlet rules are used
     * (see [FilterCatalog.trustedCompanions]); on exactly when that list is.
     */
    val adguardTrustedOf: String? = null,
    /**
     * A list that exists to unblock: its site element exceptions also exempt the elements they
     * name (and what is inside them) from every hiding rule there, not only the rule of the
     * identical text (AdGuard Base hides Google's ads under other selector texts).
     */
    val unblock: Boolean = false,
    /** A regional list: on by default when one of the phone's languages is among these. */
    val languages: Set<String> = emptySet(),
)

/**
 * One list per job, no two lists doing the same job. Browser lists use uBO syntax, the native
 * language of the page filter (AdGuard publishes uBO builds of its lists; its own `#%#` syntax
 * would be dropped). EasyList is inside AdGuard Base, so it is not listed separately.
 */
object FilterCatalog {
    private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock"
    private const val UASSETS = "https://ublockorigin.github.io/uAssets/filters"
    private const val ADGUARD = "https://filters.adtidy.org/extension/ublock/filters"
    private const val ADGUARD_FULL = "https://filters.adtidy.org/android/filters"

    const val XIAOMI_LIST = "hagezi-xiaomi"
    /** HaGeZi's encrypted-DNS list: on exactly when Settings › Block DNS bypass is. */
    const val DOH_LIST = "hagezi-doh"

    /**
     * AdGuard's maintained never-decrypt lists: banks and financial services
     * worldwide, other sites with sensitive personal data, sites that break under filtering.
     * Not filter lists: downloaded with the others while page filtering can run, merged into
     * the never-decrypt set (HttpsConfig.exclusions); the built-in list stays as the floor.
     * Their `$app=` lines (desktop apps) and AdGuard's per-platform files are not used.
     */
    val exclusionLists = listOf(
        FilterList("adguard-https-banks", "AdGuard HTTPS exclusions · banks", "https://raw.githubusercontent.com/AdguardTeam/HttpsExclusions/master/exclusions/banks.txt", true, Group.WEB, true, "Banks and financial services: never decrypted", neverDecrypt = true),
        FilterList("adguard-https-sensitive", "AdGuard HTTPS exclusions · sensitive", "https://raw.githubusercontent.com/AdguardTeam/HttpsExclusions/master/exclusions/sensitive.txt", true, Group.WEB, true, "Health, government, mail and other sensitive sites: never decrypted", neverDecrypt = true),
        FilterList("adguard-https-issues", "AdGuard HTTPS exclusions · issues", "https://raw.githubusercontent.com/AdguardTeam/HttpsExclusions/master/exclusions/issues.txt", true, Group.WEB, true, "Sites known to break when decrypted", neverDecrypt = true),
    )

    val lists = listOf(
        // System-wide DNS
        FilterList("adguard-dns", "AdGuard DNS filter", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", false, Group.ADS, true, "The hostname part of AdGuard's ad, tracking and mobile lists", bundled = true),
        FilterList("hagezi-pro", "HaGeZi Multi PRO", "$HAGEZI/pro.txt", false, Group.ADS, true, "Ads, tracking, telemetry, phishing and malware with rare breakage", bundled = true),
        FilterList("hagezi-tif", "HaGeZi Threat Intelligence", "$HAGEZI/tif.medium.txt", false, Group.SECURITY, false, "Malware, phishing, scam and cryptojacking domains"),
        // Encrypted DNS servers only (not HaGeZi's VPN/proxy part, which would break VPN apps);
        // the built-in dns-bypass.txt stays as the offline floor.
        FilterList(DOH_LIST, "HaGeZi Encrypted DNS bypass", "$HAGEZI/doh.txt", false, Group.SECURITY, true, "DNS-over-HTTPS/TLS servers apps could use to go around filtering"),

        // The phone maker's own telemetry: only the list of this phone's maker is used, in the
        // way the Device profile says (see [deviceProfile]). Not in the first-run snapshot.
        FilterList(XIAOMI_LIST, "HaGeZi Xiaomi tracker", "$HAGEZI/native.xiaomi.txt", false, Group.DEVICE, false, "Xiaomi ads and telemetry"),
        FilterList("hagezi-samsung", "HaGeZi Samsung tracker", "$HAGEZI/native.samsung.txt", false, Group.DEVICE, false, "Samsung ads and telemetry"),
        FilterList("hagezi-huawei", "HaGeZi Huawei tracker", "$HAGEZI/native.huawei.txt", false, Group.DEVICE, false, "Huawei ads and telemetry"),
        FilterList("hagezi-vivo", "HaGeZi vivo tracker", "$HAGEZI/native.vivo.txt", false, Group.DEVICE, false, "vivo ads and telemetry"),
        FilterList("hagezi-oppo-realme", "HaGeZi OPPO/realme tracker", "$HAGEZI/native.oppo-realme.txt", false, Group.DEVICE, false, "OPPO and realme ads and telemetry"),

        // Page filtering in browsers: ads and privacy
        FilterList("adguard-base", "AdGuard Base", "$ADGUARD/2.txt", true, Group.WEB, true, "EasyList plus AdGuard's own ad rules"),
        FilterList("adguard-mobile", "AdGuard Mobile Ads", "$ADGUARD/11.txt", true, Group.WEB, true, "Mobile ad networks and in-page mobile ads"),
        FilterList("adguard-tracking", "AdGuard Tracking Protection", "$ADGUARD/3.txt", true, Group.WEB, true, "Trackers, analytics and fingerprinting", dropImageHosts = true),
        FilterList("ubo-filters", "uBlock filters", "$UASSETS/filters.txt", true, Group.WEB, true, "uBlock Origin's site fixes and anti-adblock defuses", trusted = true),
        FilterList("ubo-quickfixes", "uBlock · Quick fixes", "$UASSETS/quick-fixes.txt", true, Group.WEB, true, "Fast fixes for fresh breakage", trusted = true),
        FilterList("ubo-unbreak", "uBlock · Unbreak", "$UASSETS/unbreak.txt", true, Group.WEB, true, "Exceptions that keep sites working", trusted = true),
        FilterList("ubo-badware", "uBlock · Badware risks", "$UASSETS/badware.txt", true, Group.WEB, true, "Scam and fake-download sites", trusted = true),
        // Regional: on by default for the phone's languages (AdGuard's filter ids).
        FilterList("adguard-chinese", "AdGuard Chinese", "$ADGUARD/224.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on Chinese sites", languages = setOf("zh")),
        FilterList("adguard-dutch", "AdGuard Dutch", "$ADGUARD/8.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on Dutch sites", languages = setOf("nl")),
        FilterList("adguard-french", "AdGuard French", "$ADGUARD/16.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on French sites", languages = setOf("fr")),
        FilterList("adguard-german", "AdGuard German", "$ADGUARD/6.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on German sites", languages = setOf("de")),
        FilterList("adguard-japanese", "AdGuard Japanese", "$ADGUARD/7.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on Japanese sites", languages = setOf("ja")),
        FilterList("adguard-russian", "AdGuard Russian", "$ADGUARD/1.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on Russian-language sites", languages = setOf("ru")),
        FilterList("adguard-spanish-portuguese", "AdGuard Spanish/Portuguese", "$ADGUARD/9.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on Spanish and Portuguese sites", languages = setOf("es", "pt")),
        FilterList("adguard-turkish", "AdGuard Turkish", "$ADGUARD/13.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on Turkish sites", languages = setOf("tr")),
        FilterList("adguard-ukrainian", "AdGuard Ukrainian", "$ADGUARD/23.txt", true, Group.WEB_REGIONAL, false, "Ads and trackers on Ukrainian sites", languages = setOf("uk")),

        // Page filtering in browsers: annoyances (AdGuard's split annoyance lists, no overlap)
        FilterList("adguard-cookies", "AdGuard Cookie Notices", "$ADGUARD/18.txt", true, Group.WEB_ANNOYANCES, true, "Cookie consent banners"),
        // uBO's "Cookie notices" pair: the AdGuard list misses many sites (EasyList Cookie adds
        // ~18,500 domains, uBO's ~3,300 with scriptlets that press "reject" on consent platforms,
        // e.g. the Guardian's).
        FilterList("ubo-cookies", "uBlock · Cookie Notices", "$UASSETS/annoyances-cookies.txt", true, Group.WEB_ANNOYANCES, true, "Consent platforms answered with \"reject\"", trusted = true),
        FilterList("easylist-cookies", "EasyList Cookie", "https://ublockorigin.github.io/uAssets/thirdparties/easylist-cookies.txt", true, Group.WEB_ANNOYANCES, true, "Cookie banners on sites the other lists miss"),
        FilterList("adguard-popups", "AdGuard Popups", "$ADGUARD/19.txt", true, Group.WEB_ANNOYANCES, true, "Newsletter, subscription and other pop-ups"),
        FilterList("adguard-appbanners", "AdGuard Mobile App Banners", "$ADGUARD/20.txt", true, Group.WEB_ANNOYANCES, true, "\"Open in app\" banners"),
        FilterList("adguard-annoyances", "AdGuard Other Annoyances", "$ADGUARD/21.txt", true, Group.WEB_ANNOYANCES, true, "Self-promotion and copy restrictions"),
        FilterList("adguard-widgets", "AdGuard Widgets", "$ADGUARD/22.txt", true, Group.WEB_ANNOYANCES, true, "Chat and support widgets"),
        FilterList("adguard-urltracking", "AdGuard URL Tracking", "$ADGUARD/17.txt", true, Group.WEB_ANNOYANCES, false, "Strips tracking parameters from page links"),
        // An allowlist: it lets search engines' own ads and sites' self-promotion through.
        FilterList("adguard-searchads", "AdGuard Search ads & self-promotion", "$ADGUARD/10.txt", true, Group.WEB_ANNOYANCES, false, "Shows ads in Google/Bing/DuckDuckGo results and sites' self-promotion", unblock = true),
    )

    /**
     * AdGuard's uBO builds leave out every "trusted" scriptlet rule (uBO trusts only its own
     * lists); AdGuard's full builds carry them in AdGuard syntax: 2,212 over our lists on
     * 2026-10-05, 1,399 of them in the cookie list, mostly pressing "reject" on consent platforms
     * (one reason a page can look cleaner under AdGuard). Each full build below is downloaded with
     * its list and contributes only those rules, converted to uBO syntax by the page compiler.
     * Only lists with 30 or more such rules (Mobile, Tracking, Russian and Widgets have 3-15: not
     * worth a second download; Tracking's full build is 11 MB). Not shown as lists of their own.
     */
    val trustedCompanions = listOf(
        FilterList("adguard-base-trusted", "AdGuard Base · trusted rules", "$ADGUARD_FULL/2.txt", true, Group.WEB, true, "Scriptlets AdGuard's uBO build leaves out", adguardTrustedOf = "adguard-base"),
        FilterList("adguard-turkish-trusted", "AdGuard Turkish · trusted rules", "$ADGUARD_FULL/13.txt", true, Group.WEB_REGIONAL, true, "Scriptlets AdGuard's uBO build leaves out", adguardTrustedOf = "adguard-turkish"),
        FilterList("adguard-cookies-trusted", "AdGuard Cookie Notices · trusted rules", "$ADGUARD_FULL/18.txt", true, Group.WEB_ANNOYANCES, true, "Consent windows answered with \"reject\"", adguardTrustedOf = "adguard-cookies"),
        FilterList("adguard-popups-trusted", "AdGuard Popups · trusted rules", "$ADGUARD_FULL/19.txt", true, Group.WEB_ANNOYANCES, true, "Scriptlets AdGuard's uBO build leaves out", adguardTrustedOf = "adguard-popups"),
        FilterList("adguard-appbanners-trusted", "AdGuard Mobile App Banners · trusted rules", "$ADGUARD_FULL/20.txt", true, Group.WEB_ANNOYANCES, true, "Scriptlets AdGuard's uBO build leaves out", adguardTrustedOf = "adguard-appbanners"),
        FilterList("adguard-annoyances-trusted", "AdGuard Other Annoyances · trusted rules", "$ADGUARD_FULL/21.txt", true, Group.WEB_ANNOYANCES, true, "Scriptlets AdGuard's uBO build leaves out", adguardTrustedOf = "adguard-annoyances"),
    )

    fun byId(id: String) = lists.firstOrNull { it.id == id }

    fun custom(s: Settings): List<FilterList> =
        s.customLists.map { FilterList(it.id, it.name, it.url, it.web, Group.CUSTOM, true, it.url) }

    /**
     * How this phone's Device list is used: the stored choice, else Safe where a tested
     * exceptions file exists (Xiaomi) and Off elsewhere. Safe chosen on a Xiaomi phone and
     * restored on another maker's means Off there.
     */
    fun deviceProfile(s: Settings, d: Device): DeviceProfile {
        val safe = d.brandList == XIAOMI_LIST
        return when (val p = s.deviceProfile) {
            null -> if (safe) DeviceProfile.SAFE else DeviceProfile.OFF
            DeviceProfile.SAFE -> if (safe) p else DeviceProfile.OFF
            else -> p
        }
    }

    /** Off turns the Xiaomi list into allow rules: the general lists block nearly all its domains too. */
    fun deviceListAllows(s: Settings, d: Device) = d.brandList == XIAOMI_LIST && deviceProfile(s, d) == DeviceProfile.OFF

    /** A list's default: regional lists follow the phone's languages. */
    private fun defaultOn(list: FilterList, d: Device) =
        if (list.languages.isNotEmpty()) d.languages.any { it in list.languages } else list.defaultOn

    fun isEnabled(list: FilterList, s: Settings, d: Device = Device.current()): Boolean = when {
        list.group == Group.DEVICE -> list.id == d.brandList && (deviceProfile(s, d) != DeviceProfile.OFF || deviceListAllows(s, d))
        list.id == DOH_LIST -> s.blockDnsBypass
        // A full build follows its list.
        list.adguardTrustedOf != null -> byId(list.adguardTrustedOf)?.let { isEnabled(it, s, d) } ?: false
        else -> s.listOverrides[list.id] ?: defaultOn(list, d)
    }

    /** Lists whose on/off follows a setting, not a switch of their own. */
    fun followsSetting(list: FilterList) = list.id == DOH_LIST

    fun enabledDns(s: Settings, d: Device = Device.current()): List<FilterList> =
        (lists + custom(s)).filter { !it.web && isEnabled(it, s, d) }

    fun enabledWeb(s: Settings, d: Device = Device.current()): List<FilterList> =
        (lists + trustedCompanions + custom(s)).filter { it.web && isEnabled(it, s, d) }

    /** The lists the person sees and switches. */
    fun all(s: Settings): List<FilterList> = lists + custom(s)

    /** Every list file the app may keep (cleanup removes the others). */
    fun stored(s: Settings): List<FilterList> = lists + trustedCompanions + exclusionLists + custom(s)

    fun byIdAny(id: String) = byId(id) ?: exclusionLists.firstOrNull { it.id == id } ?: trustedCompanions.firstOrNull { it.id == id }
}
