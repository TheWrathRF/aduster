package app.aduster.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

data class DayStat(val day: Long, val queries: Long, val blocked: Long)
data class AppCount(val uid: Int, val queries: Long, val blocked: Long)

data class Stats(
    val running: Boolean,
    val day: Long,
    val queries: Long,
    val blocked: Long,
    val history: List<DayStat>,
    val topBlocked: List<Pair<String, Long>>,
    val apps: List<AppCount>,
    val upstreamP50: Long,
    val upstreamP99: Long,
    val localP50: Long,
    /** Browser page filtering is actually running (not just switched on). */
    val httpsActive: Boolean = false,
    /** Why page filtering is not running: on, off, unavailable, no_browser, ca_missing, ca_untrusted, ca_key_unreadable, ca_mismatch, start_failed. */
    val httpsState: String = "off",
    /** Browsers whose pages have been filtered since protection started, by name. */
    val filteredBrowsers: List<String> = emptyList(),
    /** Browsers that refuse the certificate (left to DNS filtering), by name. */
    val untrustedBrowsers: List<String> = emptyList(),
    /** Private DNS in strict mode: Android sends DNS around the tunnel. */
    val privateDnsStrict: Boolean = false,
    /** "Block connections without VPN" is on for Aduster: only DNS works. */
    val lockdown: Boolean = false,
    /** Of each top name's blocks, how many were requests stopped inside browser pages. */
    val inPages: Map<String, Long> = emptyMap(),
    /** Today's DNS lookups and DNS blocks alone. */
    val dnsQueries: Long = 0,
    val dnsBlocked: Long = 0,
) {
    /** Share of DNS lookups blocked: one unit (the totals mix connections and page requests). */
    val dnsRatio: Double get() = if (dnsQueries == 0L) 0.0 else dnsBlocked.toDouble() / dnsQueries

    companion object {
        val EMPTY = Stats(false, 0, 0, 0, emptyList(), emptyList(), emptyList(), 0, 0, 0)

        /** [running]: what isRunning() said, used when the stats leave the state out. */
        fun parse(json: String, running: Boolean): Stats {
            val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return EMPTY.copy(running = running)
            fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull ?: 0L
            fun pairs(k: String) = (o[k] as? JsonArray)?.mapNotNull { e ->
                runCatching { val a = e.jsonArray; a[0].jsonPrimitive.content to a[1].jsonPrimitive.long }.getOrNull()
            } ?: emptyList()
            val lat = o["latency"] as? JsonObject
            val names = o["https_browsers"] as? JsonObject
            // UIDs the engine reports, as the browsers' names (an app no longer installed is left out).
            fun browsers(k: String) = (o[k] as? JsonArray)
                ?.mapNotNull { e -> e.jsonPrimitive.content.let { names?.get(it)?.jsonPrimitive?.content } }
                ?.sortedBy { it.lowercase() } ?: emptyList()
            return Stats(
                running = o["running"]?.jsonPrimitive?.content?.let { it == "true" } ?: running,
                day = l("day"),
                queries = l("queries"),
                blocked = l("blocked"),
                history = (o["history"] as? JsonArray)?.mapNotNull { e ->
                    runCatching {
                        val d = e.jsonObject
                        DayStat(d["day"]!!.jsonPrimitive.long, d["queries"]!!.jsonPrimitive.long, d["blocked"]!!.jsonPrimitive.long)
                    }.getOrNull()
                } ?: emptyList(),
                topBlocked = pairs("top_blocked"),
                apps = (o["apps"] as? JsonArray)?.mapNotNull { e ->
                    runCatching { val a = e.jsonArray; AppCount(a[0].jsonPrimitive.int, a[1].jsonPrimitive.long, a[2].jsonPrimitive.long) }.getOrNull()
                } ?: emptyList(),
                upstreamP50 = lat?.get("upstream_p50_us")?.jsonPrimitive?.longOrNull ?: 0,
                upstreamP99 = lat?.get("upstream_p99_us")?.jsonPrimitive?.longOrNull ?: 0,
                localP50 = lat?.get("local_p50_us")?.jsonPrimitive?.longOrNull ?: 0,
                httpsActive = o["https_active"]?.jsonPrimitive?.content == "true",
                httpsState = o["https_state"]?.jsonPrimitive?.content ?: "off",
                filteredBrowsers = browsers("https_filtered_uids"),
                untrustedBrowsers = browsers("https_untrusted_uids"),
                privateDnsStrict = o["private_dns_strict"]?.jsonPrimitive?.content == "true",
                lockdown = o["lockdown"]?.jsonPrimitive?.content == "true",
                inPages = pairs("top_blocked_page_counts").toMap(),
                dnsQueries = l("dns_queries"),
                dnsBlocked = l("dns_blocked"),
            )
        }
    }
}

/**
 * The calendar days from the first one with data to [today], at most [slots]: days without data
 * in between are empty slots, so the bars are real days even after days off. A new install or a
 * reset shows today alone, not a row of empty days. Oldest first, today last.
 */
fun historySlots(history: List<DayStat>, today: DayStat, slots: Int = 30): List<DayStat?> {
    val byDay = history.associateBy { it.day } + (today.day to today)
    val first = maxOf(byDay.keys.min(), today.day - slots + 1)
    return (first..today.day).map { byDay[it] }
}

enum class QueryAction(val blocked: Boolean, val label: String) {
    UPSTREAM(false, "Resolved"),
    CACHE(false, "Cached"),
    BLOCKED(true, "Blocked"),
    BLOCKED_CNAME(true, "Blocked (CNAME)"),
    FAILED(false, "Failed"),
    STALE(false, "Cached (refreshing)"),
    FILTERED(true, "Blocked in a page"),
    /** Log markers, not queries: filtering was paused / resumed here. */
    PAUSED(false, "Paused"),
    RESUMED(false, "Resumed"),
    /** An action this app version does not know (a newer core): shown by name, never as a cache hit. */
    UNKNOWN(false, "Other"),
    /** Marker the Log inserts itself: protection's process restarted, older entries are from before. */
    RESTARTED(false, "Protection restarted"),
    ;

    val marker: Boolean get() = this == PAUSED || this == RESUMED || this == RESTARTED

    /** Answered (from the resolver or the cache): the "Allowed" tab (failures are not allowed answers). */
    val answered: Boolean get() = this == UPSTREAM || this == CACHE || this == STALE

    companion object {
        fun of(s: String) = when (s) {
            "Upstream" -> UPSTREAM
            "Cache" -> CACHE
            "Stale" -> STALE
            "Blocked" -> BLOCKED
            "BlockedCname" -> BLOCKED_CNAME
            "Failed" -> FAILED
            "Filtered" -> FILTERED
            "Paused" -> PAUSED
            "Resumed" -> RESUMED
            else -> UNKNOWN
        }
    }
}

data class LogItem(
    val seq: Long,
    val time: Long,
    val name: String,
    val qtype: Int,
    val uid: Int,
    val action: QueryAction,
    val rule: String?,
    val list: String?,
    val micros: Long,
    /** Failed while there was no network (shown as one stretch). */
    val offline: Boolean = false,
    /** A request blocked inside a page: the page's host and the request's path. */
    val page: String? = null,
    val path: String? = null,
) {
    val typeName: String
        get() = when (qtype) {
            1 -> "A"
            28 -> "AAAA"
            65 -> "HTTPS"
            64 -> "SVCB"
            // The proxy's records (not a DNS type; "HTTPS" would read like record type 65): a
            // request blocked inside a page, or one of a browser's connections.
            0 -> if (action == QueryAction.FILTERED) "Page" else "Connection"
            5 -> "CNAME"
            12 -> "PTR"
            16 -> "TXT"
            33 -> "SRV"
            15 -> "MX"
            else -> "TYPE$qtype"
        }

    companion object {
        /**
         * Entry by entry: one malformed entry must not empty the whole batch, or the Log stops
         * updating because the sequence never moves past it.
         */
        fun parseAll(json: String): List<LogItem> {
            val arr = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
            return arr.mapNotNull { e ->
                runCatching {
                    val o = e.jsonObject
                    LogItem(
                        seq = o["s"]!!.jsonPrimitive.long,
                        time = o["t"]!!.jsonPrimitive.long,
                        name = o["n"]!!.jsonPrimitive.content,
                        qtype = o["q"]?.jsonPrimitive?.int ?: 0,
                        uid = o["u"]?.jsonPrimitive?.int ?: -1,
                        action = QueryAction.of(o["a"]?.jsonPrimitive?.content.orEmpty()),
                        rule = o["r"]?.jsonPrimitive?.content,
                        list = o["l"]?.jsonPrimitive?.content,
                        micros = o["us"]?.jsonPrimitive?.longOrNull ?: 0,
                        offline = o["o"]?.jsonPrimitive?.content == "true",
                        page = o["p"]?.jsonPrimitive?.content,
                        path = o["x"]?.jsonPrimitive?.content,
                    )
                }.getOrNull()
            }
        }

        /** The highest sequence number in a raw batch, also when some entries did not parse. */
        fun maxSeq(json: String): Long =
            runCatching { Json.parseToJsonElement(json).jsonArray.maxOf { it.jsonObject["s"]!!.jsonPrimitive.long } }.getOrDefault(0L)
    }
}

/** [running]: whether protection runs (off = only the user's rules and the lists on disk were checked). */
data class DomainCheck(val blocked: Boolean, val rule: String?, val list: String?, val running: Boolean = true)

fun parseDomainCheck(json: String): DomainCheck? =
    runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        DomainCheck(
            o["blocked"]!!.jsonPrimitive.content == "true",
            o["rule"]?.jsonPrimitive?.takeIf { it.isString }?.content,
            o["list"]?.jsonPrimitive?.takeIf { it.isString }?.content,
            o["running"]?.jsonPrimitive?.content != "false",
        )
    }.getOrNull()

/** One reason a site is handled the way it is in browsers, as the screen says it. */
data class SiteReason(val kind: String, val remainingS: Long?, val detail: String?)

/** "Check a site", browser part: null = the proxy is not running. */
data class SiteCheck(val decrypted: Boolean, val filteringActive: Boolean, val reasons: List<SiteReason>)

fun parseSiteCheck(json: String): SiteCheck? =
    runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        if (o["proxy"]?.jsonPrimitive?.content != "true") return@runCatching null
        SiteCheck(
            o["decrypted"]?.jsonPrimitive?.content == "true",
            o["filtering_active"]?.jsonPrimitive?.content != "false",
            (o["reasons"] as? JsonArray).orEmpty().mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val detail = listOfNotNull(r["reason"]?.jsonPrimitive?.content?.replace('_', ' '), r["detail"]?.jsonPrimitive?.content).joinToString(" · ").ifEmpty { null }
                SiteReason(r["kind"]?.jsonPrimitive?.content.orEmpty(), r["remaining_s"]?.jsonPrimitive?.longOrNull, detail)
            },
        )
    }.getOrNull()
