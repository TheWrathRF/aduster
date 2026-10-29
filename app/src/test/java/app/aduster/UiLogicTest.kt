package app.aduster

import androidx.compose.ui.unit.dp
import app.aduster.filters.UserRules
import app.aduster.settings.Settings
import app.aduster.settings.SettingsBackup
import app.aduster.settings.ThemeMode
import app.aduster.settings.coreKey
import app.aduster.settings.sanitized
import app.aduster.ui.DayStat
import app.aduster.ui.LogItem
import app.aduster.ui.QueryAction
import app.aduster.ui.Stats
import app.aduster.ui.common.segmentShape
import app.aduster.ui.historySlots
import app.aduster.ui.parseDomainCheck
import app.aduster.ui.parseSiteCheck
import app.aduster.ui.settings.timelineLines
import app.aduster.vpn.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure logic behind the screens, no Android runtime needed. */
class UiLogicTest {
    @Test
    fun adguardFullBuildsFollowTheirLists() {
        // Each full build is on exactly when its list is, and is not a list of its own.
        val on = Settings()
        val base = app.aduster.filters.FilterCatalog.trustedCompanions.first { it.adguardTrustedOf == "adguard-cookies" }
        assertTrue(app.aduster.filters.FilterCatalog.enabledWeb(on).any { it.id == base.id })
        val off = Settings(listOverrides = mapOf("adguard-cookies" to false))
        assertFalse(app.aduster.filters.FilterCatalog.enabledWeb(off).any { it.id == base.id })
        assertFalse(app.aduster.filters.FilterCatalog.all(on).any { it.adguardTrustedOf != null })
        assertTrue(app.aduster.filters.FilterCatalog.stored(on).any { it.id == base.id })
        app.aduster.filters.FilterCatalog.trustedCompanions.forEach { assertNotNull(app.aduster.filters.FilterCatalog.byId(it.adguardTrustedOf!!)) }
    }

    @Test
    fun typeNameForFilteredIsPage() {
        // A request blocked inside a page says so; one of a browser's connections is a connection.
        val req = LogItem(1, 0, "ads.example", 0, 10, QueryAction.FILTERED, "rule", "list", 0, page = "news.example", path = "/ad.js")
        assertEquals("Page", req.typeName)
        assertEquals("Connection", req.copy(action = QueryAction.UPSTREAM).typeName)
        assertEquals("AAAA", req.copy(qtype = 28).typeName)
    }

    @Test
    fun parseAllSkipsBadEntry() {
        // One malformed entry does not empty the batch; the max sequence still moves.
        val json = """[{"s":1,"t":5,"n":"a.example","q":1,"u":3,"a":"Upstream","us":10},{"s":2,"bad":true},
            {"s":3,"t":6,"n":"b.example","q":0,"u":3,"a":"Filtered","p":"page.example","x":"/x"},{"s":4,"t":7,"n":"c","q":1,"u":1,"a":"Brand new"}]"""
        val items = LogItem.parseAll(json)
        assertEquals(listOf(1L, 3L, 4L), items.map { it.seq })
        assertEquals("page.example", items[1].page)
        assertEquals(QueryAction.UNKNOWN, items[2].action)
        assertEquals(4L, LogItem.maxSeq(json))
        assertFalse(QueryAction.FAILED.answered)
        assertTrue(QueryAction.STALE.answered)
    }

    @Test
    fun historySlotsKeepGaps() {
        // Calendar days from the first day with data; missing days in between are empty slots.
        val slots = historySlots(listOf(DayStat(95, 10, 1), DayStat(98, 20, 2)), DayStat(100, 5, 1), slots = 7)
        assertEquals(listOf(95L, null, null, 98L, null, 100L), slots.map { it?.day })
        // A new install shows today alone, not 29 empty days before it.
        assertEquals(listOf(100L), historySlots(emptyList(), DayStat(100, 5, 1)).map { it?.day })
        // Older data is cut at the chart's width.
        assertEquals(7, historySlots(listOf(DayStat(50, 1, 1)), DayStat(100, 5, 1), slots = 7).size)
    }

    /**
     * Home's numbers: the percentage is of the lookup count shown beside it, and the lookups
     * blocked plus the browser blocks shown are the status card's "blocked today".
     */
    @Test
    fun homeNumbersAgree() {
        val s = Stats.parse("""{"running":true,"queries":1047,"blocked":895,"dns_queries":1000,"dns_blocked":180}""", running = true)
        val full = app.aduster.ui.home.homeNumbers(s, pages = true)
        assertEquals(listOf("DNS lookups", "DNS lookups blocked", "Blocked in browsers"), full.map { it.label })
        assertEquals(listOf("1,000", "18%", "715"), full.map { it.value })
        assertEquals(s.blocked, s.dnsBlocked + full[2].raw)
        // A build without the page engine has nothing to count in browsers.
        val public = Stats.parse("""{"running":true,"queries":1000,"blocked":180,"dns_queries":1000,"dns_blocked":180}""", running = true)
        assertEquals(listOf("DNS lookups", "DNS lookups blocked"), app.aduster.ui.home.homeNumbers(public, pages = false).map { it.label })
    }

    /** Log: one row per name and app for its A and AAAA lookups, under a heading per minute. */
    @Test
    fun logLedger() {
        val t = 1_700_000_040_000L - 1_700_000_040_000L % 60_000
        fun e(seq: Long, at: Long, name: String, q: Int, uid: Int, a: QueryAction) = LogItem(seq, t + at, name, q, uid, a, null, null, 0)
        val newestFirst = listOf(
            e(6, 65_200, "b.test", 65, 10, QueryAction.BLOCKED),
            e(5, 65_000, "b.test", 28, 10, QueryAction.BLOCKED),
            e(4, 64_500, "b.test", 1, 10, QueryAction.BLOCKED),
            e(3, 30_000, "c.test", 1, 10, QueryAction.UPSTREAM),
            e(2, 29_000, "c.test", 28, 11, QueryAction.UPSTREAM),
            e(1, 1_000, "d.test", 0, 10, QueryAction.FILTERED),
        )
        val lines = app.aduster.ui.log.ledgerOf(newestFirst)
        assertEquals(
            listOf("minute 1", "b.test A, AAAA, HTTPS", "minute 0", "c.test A", "c.test AAAA", "d.test Page"),
            lines.map { l ->
                when (l) {
                    is app.aduster.ui.log.LogLine.Minute -> "minute ${(l.time - t) / 60_000}"
                    is app.aduster.ui.log.LogLine.Query -> "${l.e.name} ${l.types}"
                    else -> l.toString()
                }
            },
        )
        // A merged row keeps its key (its oldest entry) when the next type of the name arrives.
        val merged = lines[1] as app.aduster.ui.log.LogLine.Query
        assertEquals(4L, merged.key)
        assertEquals(lines.size, lines.map { it.key }.toSet().size)
        // Lookups seconds apart, or one blocked and one answered, stay apart.
        val apart = app.aduster.ui.log.ledgerOf(listOf(e(8, 9_000, "x.test", 28, 10, QueryAction.UPSTREAM), e(7, 1_000, "x.test", 1, 10, QueryAction.UPSTREAM)))
        assertEquals(3, apart.size)
        val mixed = app.aduster.ui.log.ledgerOf(listOf(e(8, 1_100, "x.test", 28, 10, QueryAction.BLOCKED), e(7, 1_000, "x.test", 1, 10, QueryAction.UPSTREAM)))
        assertEquals(3, mixed.size)
    }

    /** Diagnostics: process ends grouped by what happened, in plain words, newest group first. */
    @Test
    fun processExitsGroupedInPlainWords() {
        fun x(proc: String, t: Long, reason: String, state: String = "cached") =
            app.aduster.vpn.ProcessExits.Exit(proc, t, reason, "stop app.aduster due to installPackageLI", state, 0, 0)
        val lines = app.aduster.ui.settings.exitLines(
            listOf(x("app.aduster", 4_000, "app updated"), x("app.aduster", 3_000, "app updated"), x("app.aduster:core", 2_000, "crash", "foreground service"), x("app.aduster", 1_000, "app updated")),
        )
        assertEquals(listOf("App · Updated", "Protection · Crashed"), lines.map { it.first })
        assertTrue(lines[0].second, lines[0].second.startsWith("3 times, last "))
        assertEquals(listOf(false, true), lines.map { it.third })
        // The system's own text stays in the report, off the screen.
        assertTrue(lines.none { it.second.contains("installPackageLI") })
    }

    /** Allow or Block from the Log adds one line and leaves the user's blank lines and comments alone. */
    @Test
    fun addedRuleKeepsTheUsersLayout() {
        val text = "! ads\n||a.example^\n\n! mine\n||x.example^\nb.example"
        assertEquals("! ads\n||a.example^\n\n! mine\nb.example\n@@||x.example^", UserRules.withRule(text, "@@||x.example^"))
        // Added once, at the end, even when it is there already.
        assertEquals("||a.example^\n@@||x.example^", UserRules.withRule("@@||x.example^\n||a.example^\n", "@@||x.example^"))
    }

    /** The system's own lookups have one name; never "root". */
    @Test
    fun systemUidsHaveOneName() {
        assertEquals("Android system", app.aduster.ui.systemAppName(0))
        assertEquals("Android system", app.aduster.ui.systemAppName(1000))
        assertNull(app.aduster.ui.systemAppName(10_123))
    }

    /** One time format for times of day: 24-hour hours and minutes. */
    @Test
    fun oneTimeOfDayFormat() {
        val zone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            assertEquals("14:30", app.aduster.ui.hm(14 * 3_600_000L + 30 * 60_000L))
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
    }

    /** The install step names the file that was really saved ("Aduster_CA (1).crt" next to an old one). */
    @Test
    fun installStepNamesTheSavedFile() {
        assertTrue(app.aduster.ui.settings.installStep(null).contains("Aduster_CA.crt"))
        assertTrue(app.aduster.ui.settings.installStep("Aduster_CA (1).crt").contains("Aduster_CA (1).crt"))
    }

    /** The widget shows the state, and the count in sentence case. */
    @Test
    fun widgetShowsTheState() {
        val w = app.aduster.widget.StatsWidget
        assertEquals("1,234" to "Blocked today", w.lines(running = true, paused = false, blocked = 1234))
        assertEquals("Paused" to "1,234 blocked today", w.lines(running = true, paused = true, blocked = 1234))
        assertEquals("Off" to "0 blocked today", w.lines(running = false, paused = false, blocked = 0))
    }

    @Test
    fun statsCarryDnsAndPageCounts() {
        val s = Stats.parse("""{"running":true,"queries":10,"blocked":6,"dns_queries":4,"dns_blocked":1,"top_blocked":[["x.com",5]],"top_blocked_page_counts":[["x.com",3]],"https_state":"ca_untrusted"}""", running = false)
        assertTrue(s.running)
        assertEquals(0.25, s.dnsRatio, 1e-9)
        assertEquals(3L, s.inPages["x.com"])
        assertEquals("ca_untrusted", s.httpsState)
    }

    @Test
    fun ruleCountCountsCosmetic() {
        // Generic cosmetic rules are rules, hosts-file comments are not.
        val text = "! comment\n# hosts comment\n##.ad\n#@#.ok\n#?#div:has(.x)\n||ads.example^\n\n"
        assertEquals(4, UserRules.ruleCount(text))
        assertTrue(UserRules.isComment("# text"))
        assertFalse(UserRules.isComment("#\$#body { overflow: auto }"))
    }

    @Test
    fun importRejectsReportJson() {
        // A saved report (or any JSON) is not settings.
        val report = """{"time_ms":1,"engine":{},"proxy":{},"timeline":[],"build":{"version":"1"}}"""
        assertNull(SettingsBackup.decode(report, Settings()))
        assertNull(SettingsBackup.decode("{}", Settings()))
        assertNull(SettingsBackup.decode("not json", Settings()))
        val mine = Settings(userRules = "||a.example^", httpsFiltering = true)
        val back = SettingsBackup.decode(SettingsBackup.encode(mine), Settings())
        assertNotNull(back)
        assertEquals(mine, back!!.settings)
        // An old bare export with something set is still accepted.
        assertNotNull(SettingsBackup.decode("""{"userRules":"||b.example^"}""", Settings()))
        // List ids that could escape the list folder are dropped.
        val evil = """{"format":"aduster-settings","version":1,"settings":{"customLists":[{"id":"../x","name":"n","url":"https://x"},{"id":"custom-0123abcd","name":"ok","url":"https://y"}]}}"""
        assertEquals(listOf("custom-0123abcd"), SettingsBackup.decode(evil, Settings())!!.settings.customLists.map { it.id })
        // A repeated id (an edited backup) keeps its first list only (the Filters
        // screen keys its rows by id).
        val twice = """{"format":"aduster-settings","version":1,"settings":{"customLists":[{"id":"custom-0123abcd","name":"a","url":"https://x"},{"id":"custom-0123abcd","name":"b","url":"https://y"}]}}"""
        assertEquals(listOf("a"), SettingsBackup.decode(twice, Settings())!!.settings.customLists.map { it.name })
    }

    /** A build without the page engine says nothing about page filtering when importing. */
    @Test
    fun importSummaryFollowsTheBuild() {
        val file = SettingsBackup.encode(Settings(httpsFiltering = true, httpsExclusions = "bank.example"))
        val public = SettingsBackup.decode(file, Settings(), pages = false)!!.summary
        assertTrue(public.toString(), public.none { it.contains("page", ignoreCase = true) || it.contains("decrypt") })
        assertTrue(SettingsBackup.decode(file, Settings(), pages = true)!!.summary.any { it.contains("Page filtering on (changes)") })
    }

    @Test
    fun importedNumbersStayInTheirRange() {
        // The engine refuses a negative count (unsigned fields), which would stop protection
        // from starting; a huge log size would grow the log without bound.
        val bad = """{"format":"aduster-settings","version":1,"settings":{"blockedTtl":-1,"logSize":-5,"updateIntervalHours":0}}"""
        val s = SettingsBackup.decode(bad, Settings())!!.settings
        assertEquals(0, s.blockedTtl)
        assertEquals(500, s.logSize)
        assertEquals(6, s.updateIntervalHours)
        val huge = SettingsBackup.decode("""{"format":"aduster-settings","version":1,"settings":{"logSize":2000000000,"blockedTtl":999999999}}""", Settings())!!.settings
        assertEquals(10_000, huge.logSize)
        assertEquals(86_400, huge.blockedTtl)
        // Values the app offers are kept as they are.
        assertEquals(Settings(), Settings().sanitized())
    }

    @Test
    fun coreKeyIgnoresUiOnlyFields() {
        // The theme does not reach :core; the log size does.
        val s = Settings()
        assertEquals(s.coreKey(), s.copy(theme = ThemeMode.LIGHT, updateIntervalHours = 48).coreKey())
        assertFalse(s.coreKey() == s.copy(logSize = 5000).coreKey())
        assertFalse(s.coreKey() == s.copy(httpsExclusions = "bank.example").coreKey())
    }

    @Test
    fun timelinePauseThenStop() {
        // A pause ended by turning off is not an open pause; stops decided by code.
        val lines = timelineLines(
            listOf(
                Timeline.Entry(1_000, Timeline.PAUSED, "until Resume"),
                Timeline.Entry(2_000, Timeline.STOPPED, "turned off in the app", "user_off"),
                Timeline.Entry(3_000, Timeline.STOPPED, "the filtering process ended (app updated)", "app_updated"),
                Timeline.Entry(4_000, Timeline.STOPPED, "the DNS engine kept stopping", "failure"),
            ),
        )
        // Newest first.
        assertEquals(listOf(true, false, false, false), lines.map { it.third })
        assertTrue(lines.last().first.startsWith("Paused") && !lines.last().first.contains("since"))
    }

    @Test
    fun timelinePauseOutlivesAnUpdate() {
        // An app update during a pause does not end it: no "turned off", and the
        // pause opens again after the start, so the later Resume closes it.
        val lines = timelineLines(
            listOf(
                Timeline.Entry(1_000, Timeline.PAUSED, "until Resume"),
                Timeline.Entry(2_000, Timeline.STOPPED, "the filtering process ended (app updated)", "app_updated"),
                Timeline.Entry(3_000, Timeline.STARTED, "after an app update"),
                Timeline.Entry(3_000, Timeline.PAUSED, "still paused"),
                Timeline.Entry(4_000, Timeline.RESUMED, "by you"),
            ),
        )
        // Newest first: the pause and its resume, Started, Stopped, the pause before the stop.
        assertEquals(listOf("Paused", "Started", "Stopped", "Paused"), lines.map { it.first.substringBefore(' ') })
        assertTrue(lines[0].second.contains("still paused") && lines[0].second.contains("resumed by you"))
        assertFalse(lines[3].second, lines[3].second.contains("turned off"))
    }

    @Test
    fun anEndedTimedPauseIsNotStillPausedAtStart() {
        // A start after a timed pause ran out must not write "still paused" (an amber open pause
        // in Diagnostics); only an open-ended or running pause is still paused.
        val now = 1_000_000L
        assertEquals(app.aduster.vpn.PauseAtStart.NONE, app.aduster.vpn.pauseAtStart(0L, over = true))
        assertEquals(app.aduster.vpn.PauseAtStart.ENDED, app.aduster.vpn.pauseAtStart(now - 60_000, over = true))
        assertEquals(app.aduster.vpn.PauseAtStart.STILL_PAUSED, app.aduster.vpn.pauseAtStart(now + 60_000, over = false))
        assertEquals(app.aduster.vpn.PauseAtStart.STILL_PAUSED, app.aduster.vpn.pauseAtStart(Long.MAX_VALUE, over = true))
    }

    @Test
    fun checksParse() {
        assertEquals(false, parseDomainCheck("""{"blocked":false,"rule":null,"list":null,"running":false}""")!!.running)
        val c = parseSiteCheck("""{"proxy":true,"host":"x","decrypted":false,"filtering_active":true,"reasons":[{"kind":"bot_check","remaining_s":300}]}""")!!
        assertFalse(c.decrypted)
        assertEquals(300L, c.reasons.single().remainingS)
        assertNull(parseSiteCheck("""{"proxy":false}"""))
    }

    /** The core names the browsers whose pages are filtered and those that refuse the certificate. */
    @Test
    fun browsersByName() {
        val s = Stats.parse(
            """{"https_active":true,"https_filtered_uids":[10200,10100],"https_untrusted_uids":[10300,10999],""" +
                """"https_browsers":{"10100":"Edge","10200":"Chrome","10300":"Some Browser","10400":"Firefox"}}""",
            running = true,
        )
        assertEquals(listOf("Chrome", "Edge"), s.filteredBrowsers)
        assertEquals(listOf("Some Browser"), s.untrustedBrowsers)
        assertTrue(Stats.parse("""{"https_active":true}""", running = true).filteredBrowsers.isEmpty())
    }

    /**
     * A build without the page engine (the public source) reports page filtering as not part of
     * the build, never as a failed start; the other reasons keep their order.
     */
    @Test
    fun pageFilteringStateWithoutTheEngine() {
        val blocker = app.aduster.https.HttpsConfig::blocker
        assertEquals("off", blocker(false, false, true, true, true))
        assertEquals("unavailable", blocker(false, true, true, true, true))
        assertEquals("unavailable", blocker(false, true, false, false, false))
        assertEquals("no_browser", blocker(true, true, false, false, false))
        assertEquals("ca_missing", blocker(true, true, true, false, false))
        assertEquals("ca_untrusted", blocker(true, true, true, true, false))
        assertNull(blocker(true, true, true, true, true))
        assertEquals("No page filtering in this build", app.aduster.ui.home.httpsProblem("unavailable"))
        assertEquals("Certificate not installed", app.aduster.ui.home.httpsProblem("ca_missing"))
    }

    /** WCAG AA for body text: every text colour of both themes on every surface text sits on. */
    @Test
    fun textContrastAtLeast4point5() {
        for (p in listOf(app.aduster.ui.theme.BlackPalette, app.aduster.ui.theme.LightPalette)) {
            val theme = if (p.dark) "black" else "light"
            val inks = listOf("text" to p.text, "text2" to p.text2, "muted" to p.muted, "warn" to p.warn, "accent" to p.accent)
            val surfaces = listOf("bg" to p.bg, "surface" to p.surface, "surfaceHigh" to p.surfaceHigh, "tone" to p.tone)
            for ((name, ink) in inks) {
                for ((under, bg) in surfaces) {
                    val ratio = contrast(ink, bg)
                    assertTrue("$theme $name on $under: ${"%.2f".format(ratio)}", ratio >= 4.5)
                }
            }
            // Filled buttons: the accent, and amber for Resume and destructive dialog actions.
            for ((name, fill) in listOf("accent" to p.accent, "warn" to p.warn)) {
                val ratio = contrast(p.onAccent, fill)
                assertTrue("$theme onAccent on $name: ${"%.2f".format(ratio)}", ratio >= 4.5)
            }
            // Material's parts take the palette, not the wallpaper; its dialogs sit on the card colour.
            val scheme = app.aduster.ui.theme.colorScheme(p)
            assertEquals(p.accent, scheme.primary)
            assertEquals(p.surface, scheme.surfaceContainerHigh)
        }
        assertEquals("Google Sans Code · OFL", app.aduster.ui.settings.licenceTitle("GoogleSansCode-OFL.txt"))
        // Licence ids hold dashes and versions; a component with spaces keeps its own casing.
        assertEquals("Rust libraries · Apache-2.0", app.aduster.ui.settings.licenceTitle("Rust libraries-Apache-2.0.txt"))
        assertEquals("uBlock Origin scriptlets · GPL-3.0", app.aduster.ui.settings.licenceTitle("uBlock Origin scriptlets-GPL-3.0.txt"))
        assertEquals("Rust libraries · BSD-3-Clause", app.aduster.ui.settings.licenceTitle("Rust libraries-BSD-3-Clause.txt"))
        // An acronym inside a name stays whole.
        assertEquals("ABCLib · MIT", app.aduster.ui.settings.licenceTitle("ABCLib-MIT.txt"))
    }

    /**
     * Home's status card lists only what needs fixing, each with where it is fixed. Off, paused, page filtering turned off and a build without
     * the page engine are no problems (the card's state says the first two).
     */
    @Test
    fun homeWarningRows() {
        fun rows(json: String, running: Boolean = true, httpsOn: Boolean = true) =
            app.aduster.ui.home.homeWarnings(Stats.parse(json, running), httpsOn).map { it.text to it.fix.name }
        assertEquals(emptyList<Pair<String, String>>(), rows("""{"https_active":true}"""))
        assertEquals(listOf("Certificate not trusted" to "HTTPS"), rows("""{"https_state":"ca_untrusted"}"""))
        assertEquals(listOf("Page filtering failed" to "DIAGNOSTICS"), rows("""{"https_state":"start_failed"}"""))
        assertEquals(emptyList<Pair<String, String>>(), rows("""{"https_state":"unavailable"}"""))
        assertEquals(emptyList<Pair<String, String>>(), rows("""{"https_state":"ca_missing"}""", httpsOn = false))
        // Stopped: page filtering is not expected to run, so its state is no problem.
        assertEquals(emptyList<Pair<String, String>>(), rows("""{"running":false,"https_state":"ca_missing"}""", running = false))
        assertEquals(
            listOf("Some Browser: certificate not trusted" to "HTTPS"),
            rows("""{"https_active":true,"https_untrusted_uids":[10300],"https_browsers":{"10300":"Some Browser"}}"""),
        )
        assertEquals(
            listOf("Private DNS bypasses protection" to "DIAGNOSTICS", "Block connections without VPN is on" to "VPN_SETTINGS"),
            rows("""{"running":false,"private_dns_strict":true,"lockdown":true}""", running = false),
        )
    }

    /**
     * Each Filters chip shows its own lists: every catalog list sits under exactly one chip (the
     * Device list only for the phone's own maker), custom lists of both kinds under "Custom", and a
     * build without the page engine shows no page lists.
     */
    @Test
    fun filterChipLists() {
        val catalog = app.aduster.filters.FilterCatalog
        val s = Settings(customLists = listOf(app.aduster.settings.CustomList("custom-0123abcd", "Mine", "https://x", web = true), app.aduster.settings.CustomList("custom-4567abcd", "Dns", "https://y", web = false)))
        val all = catalog.all(s)
        val device = catalog.XIAOMI_LIST
        fun ids(tab: app.aduster.ui.filters.FilterTab, pages: Boolean = true) =
            app.aduster.ui.filters.tabGroups(tab, all, device, pages).flatMap { (_, lists) -> lists.map { it.id } }
        val dns = ids(app.aduster.ui.filters.FilterTab.DNS)
        val pages = ids(app.aduster.ui.filters.FilterTab.PAGES)
        val yours = ids(app.aduster.ui.filters.FilterTab.YOURS)
        assertTrue(dns.contains(device) && all.none { it.group == app.aduster.filters.Group.DEVICE && it.id != device && it.id in dns })
        assertTrue(all.filter { it.web && it.group != app.aduster.filters.Group.CUSTOM }.all { it.id in pages })
        assertEquals(listOf("custom-0123abcd", "custom-4567abcd"), yours)
        val shown = dns + pages + yours
        assertEquals(shown.size, shown.toSet().size)
        assertTrue(all.filter { it.group != app.aduster.filters.Group.DEVICE }.all { it.id in shown })
        assertTrue(ids(app.aduster.ui.filters.FilterTab.PAGES, pages = false).isEmpty())
        assertEquals(dns, ids(app.aduster.ui.filters.FilterTab.DNS, pages = false))
        // Without a maker list, no Device group at all.
        assertTrue(app.aduster.ui.filters.tabGroups(app.aduster.ui.filters.FilterTab.DNS, all, null, true).none { it.first == app.aduster.filters.Group.DEVICE })
    }

    /** A group's segments: 18 dp outside, 8 dp where two meet; a single row is round all over. */
    @Test
    fun groupSegmentCorners() {
        fun shape(top: Int, bottom: Int) = androidx.compose.foundation.shape.RoundedCornerShape(top.dp, top.dp, bottom.dp, bottom.dp)
        assertEquals(shape(18, 18), segmentShape(0, 1))
        assertEquals(shape(18, 8), segmentShape(0, 3))
        assertEquals(shape(8, 8), segmentShape(1, 3))
        assertEquals(shape(8, 18), segmentShape(2, 3))
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        fun lum(c: androidx.compose.ui.graphics.Color): Double {
            fun ch(v: Float) = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
