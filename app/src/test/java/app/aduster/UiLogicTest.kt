package app.aduster

import androidx.compose.ui.unit.dp
import app.aduster.filters.UserRules
import app.aduster.settings.Settings
import app.aduster.settings.SettingsBackup
import app.aduster.settings.ThemeMode
import app.aduster.settings.coreKey
import app.aduster.settings.sanitized
import app.aduster.settings.updateOrKeep
import app.aduster.settings.trySave
import app.aduster.ui.DayStat
import app.aduster.ui.LogItem
import app.aduster.ui.QueryAction
import app.aduster.ui.Stats
import app.aduster.ui.common.segmentShape
import app.aduster.ui.historySlots
import app.aduster.ui.parseDomainCheck
import app.aduster.ui.parseSiteCheck
import app.aduster.ui.settings.PackageFacts
import app.aduster.ui.settings.appRows
import app.aduster.ui.settings.shownApps
import app.aduster.ui.settings.timelineLines
import app.aduster.ui.theme.blockedTint
import app.aduster.ui.theme.pastBar
import app.aduster.ui.theme.radioRing
import app.aduster.ui.theme.switchEdge
import app.aduster.vpn.Timeline
import app.aduster.vpn.realignedPauseEnd
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

    /**
     * Excluded apps lists every app with a launcher entry that reaches the network, system or
     * not (the Google app, Chrome); background parts only behind the switch; an app with no
     * network of its own (Google's assistant app: the Google app makes its connections) only in search, with the
     * app to exclude instead.
     */
    @Test
    fun excludedAppsListsLaunchableAppsAndNamesTheCarrier() {
        val facts = listOf(
            PackageFacts("com.google.android.apps.bard", "Assistant", system = false, uid = 10150, internet = false, launchable = true),
            PackageFacts("com.google.android.googlequicksearchbox", "Google", system = true, uid = 10120, internet = true, launchable = true),
            PackageFacts("com.android.chrome", "Chrome", system = true, uid = 10130, internet = true, launchable = true),
            PackageFacts("com.google.android.safetycore", "Android System SafetyCore", system = true, uid = 10140, internet = true, launchable = false),
            // A launcher app whose network permission sits in another package of its shared UID.
            PackageFacts("com.example.front", "Front", system = false, uid = 10200, internet = false, launchable = true),
            PackageFacts("com.example.back", "Back service", system = false, uid = 10200, internet = true, launchable = false),
            PackageFacts("com.example.calc", "Calculator", system = false, uid = 10210, internet = false, launchable = true),
        )
        val rows = appRows(facts)
        fun pkgs(l: List<app.aduster.ui.InstalledApp>) = l.map { it.pkg }.toSet()
        val main = shownApps(rows, "", background = false, excluded = emptySet())
        assertEquals(setOf("com.google.android.googlequicksearchbox", "com.android.chrome", "com.example.front"), pkgs(main))
        val withBackground = shownApps(rows, "", background = true, excluded = emptySet())
        assertEquals(pkgs(main) + setOf("com.google.android.safetycore", "com.example.back"), pkgs(withBackground))
        val assistant = shownApps(rows, "assist", background = false, excluded = emptySet()).single()
        assertEquals("com.google.android.apps.bard", assistant.pkg)
        assertFalse(assistant.network)
        assertEquals("Uses Google's connection: exclude Google", assistant.note)
        assertEquals("Has no internet access of its own", shownApps(rows, "calc", background = false, excluded = emptySet()).single().note)
        assertNull(main.first { it.pkg == "com.android.chrome" }.note)
        // A background part found by search; an excluded one stays listed without the switch.
        assertEquals(listOf("com.google.android.safetycore"), shownApps(rows, "safety", background = false, excluded = emptySet()).map { it.pkg })
        assertTrue("com.example.back" in pkgs(shownApps(rows, "", background = false, excluded = setOf("com.example.back"))))
        // An app switched on keeps its place in the list (the screen puts only those excluded
        // when it opened first).
        assertEquals(main.map { it.pkg }, shownApps(rows, "", background = false, excluded = setOf("com.google.android.googlequicksearchbox")).map { it.pkg })
        // Android's own apps on a core system UID (Settings runs as UID 1000, with the system
        // server): behind the switch and in search, not in the main list.
        val system = appRows(facts + PackageFacts("com.android.settings", "Settings", system = true, uid = 1000, internet = true, launchable = true))
        assertFalse("com.android.settings" in pkgs(shownApps(system, "", background = false, excluded = emptySet())))
        assertTrue("com.android.settings" in pkgs(shownApps(system, "", background = true, excluded = emptySet())))
        assertTrue("com.android.settings" in pkgs(shownApps(system, "settings", background = false, excluded = emptySet())))
        // An excluded package that no longer has a network or a launcher entry can still be
        // found and switched back.
        val gone = PackageFacts("com.example.gone", "Gone", system = false, uid = 10300, internet = false, launchable = false)
        assertTrue("com.example.gone" in pkgs(shownApps(appRows(facts + gone, keep = setOf("com.example.gone")), "", background = false, excluded = setOf("com.example.gone"))))
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
        // All slots ending at today; missing days before or in between are empty slots.
        val slots = historySlots(listOf(DayStat(95, 10, 1), DayStat(98, 20, 2)), DayStat(100, 5, 1), slots = 7)
        assertEquals(listOf(null, 95L, null, null, 98L, null, 100L), slots.map { it?.day })
        // A new install still fills all 30 slots, with today at the end.
        val fresh = historySlots(emptyList(), DayStat(100, 5, 1))
        assertEquals(30, fresh.size)
        assertTrue(fresh.take(29).all { it == null })
        assertEquals(100L, fresh.last()?.day)
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
        assertEquals("18% of 1,000 requests, plus 715 in browser pages", app.aduster.ui.home.requestShare(s, pages = true))
        assertEquals(s.blocked, s.dnsBlocked + s.blockedInBrowsers)
        // A build without the page engine has nothing to count in browsers.
        val public = Stats.parse("""{"running":true,"queries":1000,"blocked":180,"dns_queries":1000,"dns_blocked":180}""", running = true)
        assertEquals("18% of 1,000 requests", app.aduster.ui.home.requestShare(public, pages = false))
        val one = Stats.parse("""{"running":true,"queries":1,"blocked":0,"dns_queries":1,"dns_blocked":0}""", running = true)
        assertEquals("0% of 1 request", app.aduster.ui.home.requestShare(one, pages = false))
    }

    /**
     * The Log as a ledger: one row per name, app and outcome for its A, AAAA and HTTPS lookups and
     * its repeats within a minute ("×N"), a browser's connection folded into the lookup or page
     * row it belongs to, a day divider wherever the day changes, newest activity first.
     */
    @Test
    fun logLedger() {
        val day = 86_400_000L
        val zone = java.util.TimeZone.getDefault()
        // Noon local time, so a few minutes either way stay on one day.
        val t0 = 1_700_000_000_000L.let { it - Math.floorMod(it + zone.getOffset(it), day) + day / 2 }
        fun e(seq: Long, at: Long, name: String, q: Int, uid: Int, a: QueryAction, page: String? = null) =
            LogItem(seq, t0 + at, name, q, uid, a, null, null, 0, page = page)
        fun show(lines: List<app.aduster.ui.log.LogLine>) = lines.map { l ->
            when (l) {
                is app.aduster.ui.log.LogLine.Day -> "day"
                is app.aduster.ui.log.LogLine.Query -> "${l.e.name} ${l.types} x${l.count}"
                is app.aduster.ui.log.LogLine.Marker -> "marker"
                else -> l.toString()
            }
        }
        val newestFirst = listOf(
            // The page's own connection, then a request in it blocked: one row, the page block.
            e(12, 40_300, "static.test", 0, 10, QueryAction.FILTERED, page = "news.test"),
            e(11, 40_000, "static.test", 0, 10, QueryAction.UPSTREAM),
            // A lookup and its connection a moment later: one row.
            e(10, 30_400, "news.test", 0, 10, QueryAction.UPSTREAM),
            e(9, 30_100, "news.test", 28, 10, QueryAction.UPSTREAM),
            e(8, 30_000, "news.test", 1, 10, QueryAction.UPSTREAM),
            // A name answered, then its connection refused: both kept, the refusal is news.
            e(7, 20_500, "half.test", 0, 10, QueryAction.BLOCKED),
            e(6, 20_000, "half.test", 1, 10, QueryAction.UPSTREAM),
            // The same blocked name three times within a minute: one row, ×3.
            e(5, 15_000, "ads.test", 1, 11, QueryAction.BLOCKED),
            e(4, 10_000, "ads.test", 28, 11, QueryAction.BLOCKED),
            e(3, 9_900, "ads.test", 1, 11, QueryAction.BLOCKED),
            e(2, 1_000, "ads.test", 1, 11, QueryAction.BLOCKED),
            e(1, 0, "ads.test", 1, 12, QueryAction.BLOCKED),
        )
        val lines = app.aduster.ui.log.ledgerOf(newestFirst)
        assertEquals(
            listOf(
                "day",
                "static.test Page, Connection x1",
                "news.test A, AAAA, Connection x1",
                "half.test Connection x1",
                "half.test A x1",
                "ads.test A, AAAA x3",
                // Another app's lookup of the same name is its own row.
                "ads.test A x1",
            ),
            show(lines),
        )
        assertEquals(lines.size, lines.map { it.key }.toSet().size)
        // Keys stay as newer entries arrive: a repeat moves its row to the top under the same key.
        val more = app.aduster.ui.log.ledgerOf(listOf(e(13, 50_000, "ads.test", 1, 11, QueryAction.BLOCKED)) + newestFirst)
        assertEquals("ads.test A, AAAA x4", show(more)[1])
        assertEquals(lines.map { it.key }.toSet(), more.map { it.key }.toSet())
        // A minute after a row's first lookup the name starts a new row; a pause marker ends every row.
        val later = app.aduster.ui.log.ledgerOf(listOf(e(21, 70_000, "x.test", 1, 10, QueryAction.UPSTREAM), e(20, 0, "x.test", 1, 10, QueryAction.UPSTREAM)))
        assertEquals(listOf("day", "x.test A x1", "x.test A x1"), show(later))
        val split = app.aduster.ui.log.ledgerOf(
            listOf(e(32, 2_000, "x.test", 1, 10, QueryAction.UPSTREAM), e(31, 1_500, "", 0, -1, QueryAction.RESUMED), e(30, 1_000, "x.test", 1, 10, QueryAction.UPSTREAM)),
        )
        assertEquals(listOf("day", "x.test A x1", "marker", "day", "x.test A x1"), show(split))
        assertEquals(split.size, split.map { it.key }.toSet().size)
        // The app's own "Protection restarted" marker (a negative number, between two core
        // lifetimes) stays between them, not under every older row.
        val restarted = app.aduster.ui.log.ledgerOf(
            listOf(e(5_000, 2_000, "new.test", 1, 10, QueryAction.UPSTREAM), e(-123_456, 1_500, "", 0, -1, QueryAction.RESTARTED), e(30, 1_000, "old.test", 1, 10, QueryAction.UPSTREAM)),
        )
        assertEquals(listOf("day", "new.test A x1", "marker", "day", "old.test A x1"), show(restarted))
        // An AAAA left out by IPv4-only answers is not hidden under its A row; a failed lookup is not an allowed one.
        assertEquals(3, app.aduster.ui.log.ledgerOf(listOf(e(8, 1_100, "x.test", 28, 10, QueryAction.NO_IPV6), e(7, 1_000, "x.test", 1, 10, QueryAction.UPSTREAM))).size)
        assertEquals(3, app.aduster.ui.log.ledgerOf(listOf(e(8, 1_100, "x.test", 28, 10, QueryAction.FAILED), e(7, 1_000, "x.test", 1, 10, QueryAction.UPSTREAM))).size)
        // Each day gets its divider.
        val days = app.aduster.ui.log.ledgerOf(listOf(e(41, 0, "a.test", 1, 10, QueryAction.UPSTREAM), e(40, -day, "b.test", 1, 10, QueryAction.UPSTREAM)))
        assertEquals(listOf("day", "a.test A x1", "day", "b.test A x1"), show(days))
        assertEquals(
            listOf("Today", "Yesterday", "27 Sep"),
            listOf(0L, 1L, 7L).map { app.aduster.ui.log.dayLabel(20_000L - it, today = 20_000L) },
        )
        // "N new" counts rows, not entries: one lookup of three record types is one new row.
        assertEquals(1, app.aduster.ui.log.freshRows(lines, seenSeq = 11))
        assertEquals(0, app.aduster.ui.log.freshRows(lines, seenSeq = 12))
        assertEquals(3, app.aduster.ui.log.freshRows(lines, seenSeq = 6))
    }

    /** A Log row says what happened in a word, and its second line names the app with the page or the list. */
    @Test
    fun logRowWords() {
        fun q(a: QueryAction, qtype: Int = 1, list: String? = null, page: String? = null) =
            app.aduster.ui.log.LogLine.Query(listOf(LogItem(1, 0, "ads.test", qtype, 10, a, null, list, 0, page = page)))
        assertEquals("Blocked", app.aduster.ui.log.statusWord(q(QueryAction.BLOCKED)))
        assertEquals("Blocked", app.aduster.ui.log.statusWord(q(QueryAction.BLOCKED_CNAME)))
        assertEquals("Page", app.aduster.ui.log.statusWord(q(QueryAction.FILTERED, qtype = 0, page = "news.test")))
        assertEquals("Failed", app.aduster.ui.log.statusWord(q(QueryAction.FAILED)))
        assertEquals("Cached", app.aduster.ui.log.statusWord(q(QueryAction.CACHE)))
        assertEquals("Cached", app.aduster.ui.log.statusWord(q(QueryAction.STALE)))
        assertEquals("", app.aduster.ui.log.statusWord(q(QueryAction.UPSTREAM)))
        assertEquals("Chrome on news.test", app.aduster.ui.log.secondLine(q(QueryAction.FILTERED, qtype = 0, page = "news.test"), "Chrome"))
        assertEquals("Chrome", app.aduster.ui.log.secondLine(q(QueryAction.FILTERED, qtype = 0, page = "ads.test"), "Chrome"))
        assertEquals("Chrome, HaGeZi Pro", app.aduster.ui.log.secondLine(q(QueryAction.BLOCKED, list = "HaGeZi Pro"), "Chrome"))
        assertEquals("Chrome", app.aduster.ui.log.secondLine(q(QueryAction.UPSTREAM, list = "x"), "Chrome"))
        // The engines record a list's id: the row names the list.
        val s = Settings()
        assertEquals("Chrome, HaGeZi Multi PRO", app.aduster.ui.log.secondLine(q(QueryAction.BLOCKED, list = "hagezi-pro"), "Chrome") { app.aduster.ui.log.listLabel(it, s) })
        assertEquals("My rules", app.aduster.ui.log.listLabel("user", s))
        assertEquals("unknown-list", app.aduster.ui.log.listLabel("unknown-list", s))
    }

    /** The Log's filters combine: one of All / Blocked / Allowed, and on top of it Browsers, Pages, one app and the search. */
    @Test
    fun logFiltersCombine() {
        fun e(name: String, q: Int, uid: Int, a: QueryAction) = LogItem(1, 0, name, q, uid, a, null, null, 0)
        val browsers = setOf(20)
        val apps = mapOf(10 to "Mail", 20 to "Browser")
        fun keep(x: LogItem, kind: Int = 0, inBrowsers: Boolean = false, pages: Boolean = false, only: Int? = null, query: String = "") =
            app.aduster.ui.log.logMatches(x, kind, inBrowsers, pages, only, query, browsers) { apps[it] ?: "" }
        val dnsBlocked = e("ads.test", 1, 10, QueryAction.BLOCKED)
        val pageBlocked = e("ads.test", 0, 20, QueryAction.FILTERED)
        val connection = e("news.test", 0, 20, QueryAction.UPSTREAM)
        val browserLookup = e("news.test", 1, 20, QueryAction.UPSTREAM)
        val failed = e("down.test", 1, 10, QueryAction.FAILED)
        val marker = e("", 0, -1, QueryAction.PAUSED)
        val all = listOf(dnsBlocked, pageBlocked, connection, browserLookup, failed)
        assertTrue(all.all { keep(it) })
        assertEquals(listOf(true, true, false, false, false), all.map { keep(it, kind = 1) })
        assertEquals(listOf(false, false, true, true, false), all.map { keep(it, kind = 2) })
        // Blocked in browsers; allowed in pages (the browsers' connections).
        assertEquals(listOf(false, true, false, false, false), all.map { keep(it, kind = 1, inBrowsers = true) })
        assertEquals(listOf(false, false, true, false, false), all.map { keep(it, kind = 2, pages = true) })
        assertEquals(listOf(true, false, false, false, true), all.map { keep(it, only = 10) })
        assertTrue(keep(browserLookup, query = "brow") && keep(browserLookup, query = "NEWS ") && !keep(dnsBlocked, query = "news"))
        // Markers divide the list whatever the view, but not a search or one app's rows.
        assertTrue(keep(marker, kind = 1, inBrowsers = true))
        assertFalse(keep(marker, query = "x") || keep(marker, only = 10))
    }

    /** Diagnostics: process ends grouped by what happened, in plain words, newest group first. */
    @Test
    fun processExitsGroupedInPlainWords() {
        fun x(proc: String, t: Long, reason: String, state: String = "cached") =
            app.aduster.vpn.ProcessExits.Exit(proc, t, reason, "stop app.aduster due to installPackageLI", state, 0, 0)
        val lines = app.aduster.ui.settings.exitLines(
            listOf(x("app.aduster", 4_000, "app updated"), x("app.aduster", 3_000, "app updated"), x("app.aduster:core", 2_000, "crash", "foreground service"), x("app.aduster", 1_000, "app updated")),
        )
        assertEquals(listOf("App: updated", "Protection: crashed"), lines.map { it.first })
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

    /**
     * The widget's button pauses until resumed while protected (as the tile does) and resumes
     * while paused; while off it is hidden (turning on may need Android's VPN prompt: the app
     * opens instead). The dot says the same state in colour.
     */
    @Test
    fun widgetButtonFollowsTheState() {
        val w = app.aduster.widget.StatsWidget
        assertEquals(app.aduster.widget.StatsWidget.Control.PAUSE, w.control(running = true, paused = false))
        assertEquals(app.aduster.widget.StatsWidget.Control.RESUME, w.control(running = true, paused = true))
        assertEquals(null, w.control(running = false, paused = false))
        assertEquals("Pause protection", app.aduster.widget.StatsWidget.Control.PAUSE.label)
        assertEquals("Resume protection", app.aduster.widget.StatsWidget.Control.RESUME.label)
        val p = app.aduster.ui.theme.BlackPalette
        assertEquals(p.accent, w.dot(running = true, paused = false, p))
        assertEquals(p.warn, w.dot(running = true, paused = true, p))
        assertEquals(p.muted, w.dot(running = false, paused = false, p))
    }

    /**
     * Widget redraws read `running` once and serialize state read + draw under one lock so an
     * in-flight screen-on redraw cannot overwrite a later "Off" draw.
     */
    @Test
    fun widgetRedrawReadsRunningOnceAndSerializesDraws() {
        val w = app.aduster.widget.StatsWidget
        // Reading running once avoids torn text/color when protection stops mid-update.
        var reads = 0
        var drawnRunning = false
        var drawnBig = ""
        w.renderLocked(
            readRunning = { (++reads) == 1 },
            readPausedUntil = { 0L },
            readBlocked = { 10L },
        ) { running, _, big, _ ->
            drawnRunning = running
            drawnBig = big
        }
        assertEquals("running must be read once", 1, reads)
        assertEquals(true, drawnRunning)
        assertEquals("10", drawnBig)

        // Ordering: thread 1 starts drawing while running == true and stalls inside draw; thread 2
        // flips running to false and redraws. Thread 2's "Off" draw must land last.
        val isRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val inDraw = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val applied = java.util.Collections.synchronizedList(mutableListOf<String>())
        val t1 = Thread {
            w.renderLocked(
                readRunning = { isRunning.get() },
                readPausedUntil = { 0L },
                readBlocked = { 10L },
            ) { _, _, big, _ ->
                inDraw.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                applied += big
            }
        }
        t1.start()
        assertTrue(inDraw.await(5, java.util.concurrent.TimeUnit.SECONDS))
        isRunning.set(false)
        val t2 = Thread {
            w.renderLocked(
                readRunning = { isRunning.get() },
                readPausedUntil = { 0L },
                readBlocked = { 10L },
            ) { _, _, big, _ ->
                applied += big
            }
        }
        t2.start()
        Thread.sleep(50)
        release.countDown()
        t1.join(5_000)
        t2.join(5_000)
        assertEquals(listOf("10", "Off"), applied)
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

    /**
     * A file whose marker or version has the wrong shape is not ours (and does not crash the
     * import); a file from a newer Aduster is told apart from one that is not a settings file.
     */
    @Test
    fun importOfAWrongShapeOrNewerFile() {
        for (bad in listOf("""{"format":[]}""", """{"format":{"a":1}}""", """{"format":"aduster-settings","version":[2]}""", """{"format":7,"settings":{}}""")) {
            assertNull(bad, SettingsBackup.decode(bad, Settings()))
            assertEquals(bad, SettingsBackup.Read.NotSettings, SettingsBackup.read(bad, Settings()))
        }
        val newer = """{"format":"aduster-settings","version":2,"settings":{"userRules":"||a.example^"}}"""
        assertNull(SettingsBackup.decode(newer, Settings()))
        assertEquals(SettingsBackup.Read.Newer, SettingsBackup.read(newer, Settings()))
        val ok = SettingsBackup.read(SettingsBackup.encode(Settings(userRules = "||a.example^")), Settings())
        assertTrue(ok is SettingsBackup.Read.Ok)
    }

    /** A build without the page engine says nothing about page filtering when importing. */
    @Test
    fun importSummaryFollowsTheBuild() {
        val file = SettingsBackup.encode(Settings(httpsFiltering = true, httpsExclusions = "bank.example"))
        val public = SettingsBackup.decode(file, Settings(), pages = false)!!.summary
        assertTrue(public.toString(), public.none { it.contains("page", ignoreCase = true) || it.contains("decrypt") })
        assertTrue(SettingsBackup.decode(file, Settings(), pages = true)!!.summary.any { it.contains("Page filtering: on (now off)") })
    }

    /**
     * Every stored field is one the import preview names, one an import keeps, or one :core never
     * reads (and [coreKey] blanks): a field added later cannot miss the preview.
     */
    @Test
    fun everyFieldIsPreviewedKeptOrUiOnly() {
        val d = Settings.serializer().descriptor
        val names = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        val groups = listOf(SettingsBackup.PREVIEWED_FIELDS, SettingsBackup.KEPT_FIELDS, app.aduster.settings.UI_ONLY_FIELDS)
        assertEquals(names, groups.flatten().toSet())
        assertEquals(names.size, groups.sumOf { it.size })
        val ui = Settings(
            listOverrides = mapOf("x" to false), customLists = listOf(app.aduster.settings.CustomList("custom-0123abcd", "n", "https://x")),
            deviceProfile = app.aduster.settings.DeviceProfile.FULL, autoUpdate = false, updateIntervalHours = 48, updateWifiOnly = false,
            updateWhileCharging = true, theme = ThemeMode.LIGHT,
        )
        assertEquals(Settings().coreKey(), ui.coreKey())
        // Excluded apps by name; a long list is cut.
        val apps = SettingsBackup.encode(Settings(excludedApps = setOf("p.a", "p.b", "p.c", "p.d", "p.e")))
        val line = SettingsBackup.decode(apps, Settings()) { it.removePrefix("p.").uppercase() }!!.summary.single { it.startsWith("Excluded apps") }
        assertEquals("Excluded apps: adds A, B, C and 2 more", line)
        // A change that keeps the count is named too: which apps and sites come and go.
        val now = Settings(excludedApps = setOf("p.a", "p.b", "p.c", "p.d", "p.e"), httpsExclusions = "bank.example\nshop.example")
        val swapped = SettingsBackup.encode(Settings(excludedApps = setOf("p.a", "p.b", "p.c", "p.x", "p.y"), httpsExclusions = "bank.example\nother.example"))
        val lines = SettingsBackup.decode(swapped, now) { it.removePrefix("p.").uppercase() }!!.summary
        assertTrue(lines.toString(), "Excluded apps: adds X, Y; removes D, E" in lines)
        assertTrue(lines.toString(), "Never-decrypted sites of your own: adds other.example; removes shop.example" in lines)
    }

    /**
     * A shared settings file that sends every lookup to its own server, turns Block DNS bypass off
     * and leaves a browser out says so before it is applied; comment lines
     * are not counted as rules.
     */
    @Test
    fun importNamesWhatProtectionChanges() {
        val file = SettingsBackup.encode(
            Settings(
                upstream = "custom", customUpstream = "https://dns.other.example/q", blockDnsBypass = false,
                excludedApps = setOf("com.android.chrome"), userRules = "# a\n||b.example^",
                listOverrides = mapOf("adguard-base" to false),
            ),
        )
        val lines = SettingsBackup.decode(file, Settings())!!.summary
        assertTrue(lines.toString(), "0 custom lists, 1 list switch" in lines)
        assertTrue(lines.toString(), "DNS server: https://dns.other.example/q (now Network default)" in lines)
        assertTrue(lines.toString(), "Block DNS bypass: off (now on)" in lines)
        assertTrue(lines.toString(), "1 rule of your own (now 0)" in lines)
        // What does not change is not listed as a change.
        assertTrue(lines.toString(), lines.none { it.startsWith("Reply to blocked lookups") })
    }

    /** Counts read as words do: one rule, two rules, a thousand with its separator. */
    @Test
    fun countsAgreeWithTheirNumber() {
        assertEquals("1 rule", app.aduster.ui.counted(1, "rule"))
        assertEquals("0 rules", app.aduster.ui.counted(0, "rule"))
        assertEquals("1,234 rules", app.aduster.ui.counted(1234, "rule"))
        assertEquals("1 day", app.aduster.ui.counted(1, "day"))
        assertEquals("1 entry", app.aduster.ui.counted(1, "entry", "entries"))
        assertEquals("3 entries", app.aduster.ui.counted(3, "entry", "entries"))
    }

    /**
     * A settings write that fails (a full disk) still gives the protection process the changed
     * settings to go on with, instead of an exception that ends it.
     */
    @Test
    fun aFailedSettingsWriteKeepsTheChange() = kotlinx.coroutines.runBlocking {
        class Store(val fail: Boolean) : androidx.datastore.core.DataStore<Settings> {
            var saved = Settings()
            override val data = kotlinx.coroutines.flow.flowOf(saved)
            override suspend fun updateData(transform: suspend (t: Settings) -> Settings): Settings {
                if (fail) throw java.io.IOException("No space left on device")
                return transform(saved).also { saved = it }
            }
        }
        val now = Settings(enabled = true)
        val full = Store(fail = true)
        assertEquals(now.copy(pausedUntil = 5), full.updateOrKeep(now) { it.copy(pausedUntil = 5) })
        assertEquals(Settings(), full.saved)
        val ok = Store(fail = false)
        assertEquals(5L, ok.updateOrKeep(now) { it.copy(pausedUntil = 5) }.pausedUntil)
        assertEquals(5L, ok.saved.pausedUntil)
    }

    /** A change made in the app's screens on a full disk is reported as not saved, not thrown. */
    @Test
    fun aSettingsChangeOnAFullDiskIsNotSaved() = kotlinx.coroutines.runBlocking {
        class Store(val fail: Boolean) : androidx.datastore.core.DataStore<Settings> {
            var saved = Settings()
            override val data = kotlinx.coroutines.flow.flowOf(saved)
            override suspend fun updateData(transform: suspend (t: Settings) -> Settings): Settings {
                if (fail) throw java.io.IOException("No space left on device")
                return transform(saved).also { saved = it }
            }
        }
        val full = Store(fail = true)
        assertNull(full.trySave { it.copy(ipv4Only = true) })
        assertEquals(Settings(), full.saved)
        val ok = Store(fail = false)
        assertEquals(true, ok.trySave { it.copy(ipv4Only = true) }?.ipv4Only)
        assertEquals(true, ok.saved.ipv4Only)
    }

    /**
     * A timed pause runs on the boot clock: when the wall clock jumps forward past its end, the
     * end every screen shows moves to now plus what is left, so nothing says "Protected" while
     * filtering is still paused; a pause until Resume, or one without a boot-clock end, keeps its end.
     */
    @Test
    fun aClockJumpDuringATimedPauseKeepsItShownAsPaused() {
        val boot = 1_000_000L
        // Paused at wall 100 000 for 10 min; 4 min later the wall clock jumped forward one hour.
        val end = 100_000L + 600_000
        val deadline = boot + 600_000
        val nowWall = 100_000L + 240_000 + 3_600_000
        val nowBoot = boot + 240_000
        assertEquals(nowWall + 360_000, realignedPauseEnd(end, deadline, nowWall, nowBoot))
        assertEquals(Long.MAX_VALUE, realignedPauseEnd(Long.MAX_VALUE, 0, nowWall, nowBoot))
        assertEquals(end, realignedPauseEnd(end, 0, nowWall, nowBoot))
        assertEquals(0L, realignedPauseEnd(0, 0, nowWall, nowBoot))
    }

    /**
     * Diagnostics' "Today" counts today, as Home does: browsers from the day's stats, not the
     * page engine's count since it started; the DNS bypass count, kept since protection started,
     * is not in it.
     */
    @Test
    fun diagnosticsTodayCountsOnlyToday() {
        val stats = Stats.EMPTY.copy(running = true, blocked = 50, dnsQueries = 400, dnsBlocked = 20)
        assertEquals(listOf("DNS lookups" to 400L, "DNS lookups blocked" to 20L, "Blocked in browsers" to 30L), app.aduster.ui.settings.DiagnosticsStates.today(stats, pages = true))
        assertEquals(listOf("DNS lookups" to 400L, "DNS lookups blocked" to 20L), app.aduster.ui.settings.DiagnosticsStates.today(stats, pages = false))
    }

    /**
     * Diagnostics says "Paused" during a pause, not "Running", and why page
     * filtering is not running when it is switched on, as Home says it, not "Off".
     */
    @Test
    fun diagnosticsStatesSayWhatIsTrue() {
        val on = Stats.parse("""{"running":true,"https_state":"ca_untrusted"}""", running = true)
        val d = app.aduster.ui.settings.DiagnosticsStates
        assertEquals("Paused", d.protection(on, paused = true))
        assertEquals("Running", d.protection(on, paused = false))
        assertEquals("Stopped", d.protection(on.copy(running = false), paused = true))
        assertEquals("…", d.protection(null, paused = false))
        assertEquals("Certificate not trusted", d.pages(available = true, pagesRunning = false, httpsOn = true, stats = on))
        assertEquals("Running", d.pages(available = true, pagesRunning = true, httpsOn = true, stats = on))
        assertEquals("Off", d.pages(available = true, pagesRunning = false, httpsOn = false, stats = on))
        assertEquals("Off", d.pages(available = true, pagesRunning = false, httpsOn = true, stats = on.copy(running = false)))
        assertEquals("Not in this build", d.pages(available = false, pagesRunning = false, httpsOn = true, stats = on))
    }

    /**
     * "Delete saved reports" matches its own file names only (`_` is a LIKE wildcard), and says
     * that an earlier install's reports are out of its reach.
     */
    @Test
    fun savedReportsAreMatchedByTheirName() {
        assertEquals("Aduster\\_report\\_", app.aduster.vpn.Reports.likeLiteral("Aduster_report_"))
        assertEquals("a\\%b\\\\c", app.aduster.vpn.Reports.likeLiteral("a%b\\c"))
        assertTrue(app.aduster.vpn.Reports.deletedText(0).contains("earlier install"))
        assertEquals("Deleted 1 report", app.aduster.vpn.Reports.deletedText(1))
    }

    /** The IPv4-only switch reaches the DNS engine's config, and its Log rows are named. */
    @Test
    fun ipv4OnlyReachesTheEngine() {
        fun sent(s: Settings) = kotlinx.serialization.json.Json.parseToJsonElement(app.aduster.core.EngineConfig.json("stats.json", s, 0))
            .let { it as kotlinx.serialization.json.JsonObject }["ipv4_only"].toString()
        assertEquals("true", sent(Settings(ipv4Only = true)))
        assertEquals("false", sent(Settings()))
        assertEquals(QueryAction.NO_IPV6, QueryAction.of("NoIpv6"))
        assertTrue(QueryAction.NO_IPV6.answered && !QueryAction.NO_IPV6.blocked)
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
                Timeline.Entry(1_000, Timeline.PAUSED, "until you resume"),
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
                Timeline.Entry(1_000, Timeline.PAUSED, "until you resume"),
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
            val inks = listOf("text" to p.text, "text2" to p.text2, "muted" to p.muted, "warn" to p.warn, "accent" to p.accent, "blocked" to p.blocked)
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
            // A blocked row of the Log is tinted: its texts keep their contrast on the tint.
            val tint = over(p.blockedTint(), p.bg)
            for ((name, ink) in listOf("text" to p.text, "text2" to p.text2, "muted" to p.muted, "blocked" to p.blocked)) {
                val ratio = contrast(ink, tint)
                assertTrue("$theme $name on a blocked row: ${"%.2f".format(ratio)}", ratio >= 4.5)
            }
            // Material's parts take the palette, not the wallpaper; its dialogs sit on the card colour.
            val scheme = app.aduster.ui.theme.colorScheme(p)
            assertEquals(p.accent, scheme.primary)
            assertEquals(p.surface, scheme.surfaceContainerHigh)
        }
        assertEquals("Google Sans Code, OFL", app.aduster.ui.settings.licenceTitle("GoogleSansCode-OFL.txt"))
        // Licence ids hold dashes and versions; a component with spaces keeps its own casing.
        assertEquals("Rust libraries, Apache-2.0", app.aduster.ui.settings.licenceTitle("Rust libraries-Apache-2.0.txt"))
        assertEquals("uBlock Origin scriptlets, GPL-3.0", app.aduster.ui.settings.licenceTitle("uBlock Origin scriptlets-GPL-3.0.txt"))
        assertEquals("Rust libraries, BSD-3-Clause", app.aduster.ui.settings.licenceTitle("Rust libraries-BSD-3-Clause.txt"))
        // An acronym inside a name stays whole.
        assertEquals("ABCLib, MIT", app.aduster.ui.settings.licenceTitle("ABCLib-MIT.txt"))
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
        // A modified copy that refuses to start says so on Home, with where the real one is,
        // not only in a notification that offered a retry.
        assertEquals(listOf("This copy was modified: install the official release" to "RELEASE"), rows("""{"running":false,"intact":false}""", running = false))
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

    /** A group's segments: 10 dp outside, 5 dp where two meet; a single row is round all over. */
    @Test
    fun groupSegmentCorners() {
        fun shape(top: Int, bottom: Int) = androidx.compose.foundation.shape.RoundedCornerShape(top.dp, top.dp, bottom.dp, bottom.dp)
        assertEquals(shape(10, 10), segmentShape(0, 1))
        assertEquals(shape(10, 5), segmentShape(0, 3))
        assertEquals(shape(5, 5), segmentShape(1, 3))
        assertEquals(shape(5, 10), segmentShape(2, 3))
    }

    /**
     * WCAG's 3:1 for graphics that carry meaning: a past day's bar in Home's chart against its
     * track and the card around it, a top list's share line against its track, an unchecked
     * switch's edge against the row and the page.
     */
    @Test
    fun graphicsContrastAtLeast3() {
        for (p in listOf(app.aduster.ui.theme.BlackPalette, app.aduster.ui.theme.LightPalette)) {
            val theme = if (p.dark) "black" else "light"
            val checks = listOf(
                "past bar on its track" to contrast(over(p.pastBar(), p.surfaceHigh), p.surfaceHigh),
                "past bar on the card" to contrast(over(p.pastBar(), p.surfaceHigh), p.surface),
                "today's bar on its track" to contrast(p.accent, p.surfaceHigh),
                "today's share bar on its track" to contrast(p.blocked, p.surfaceHigh),
                "switch edge on a row" to contrast(p.switchEdge, p.surface),
                "switch edge on the page" to contrast(p.switchEdge, p.bg),
                "unselected radio ring in a dialog" to contrast(p.radioRing, p.surface),
                "a blocked row's bar on its tint" to contrast(p.blocked, over(p.blockedTint(), p.bg)),
            )
            for ((what, ratio) in checks) assertTrue("$theme $what: ${"%.2f".format(ratio)}", ratio >= 3.0)
        }
    }

    /** Filters' header: an update whose downloads failed says so, never just "updated". */
    @Test
    fun filtersHeaderSaysWhenDownloadsFailed() {
        val now = 10_000_000L
        val ok = app.aduster.filters.ListMeta()
        val bad = app.aduster.filters.ListMeta(error = "HTTP 500")
        fun line(vararg lists: Pair<String, app.aduster.filters.ListMeta>, inUse: List<String> = lists.map { it.first }) =
            app.aduster.ui.filters.updateLine(app.aduster.filters.FilterMeta(lists = mapOf(*lists), lastRun = now - 30_000), inUse, now).replace('\u00A0', ' ')
        assertEquals("updated just now", line("a" to ok, "b" to ok))
        assertEquals("updated just now, 1 list failed", line("a" to bad, "b" to ok))
        assertEquals("checked just now, 2 lists failed", line("a" to bad, "b" to bad))
        // A list no longer in use (switched off, removed) does not count, though its meta is still there.
        assertEquals("updated just now", line("a" to ok, "gone" to bad, inUse = listOf("a")))
        assertEquals("not updated yet", app.aduster.ui.filters.updateLine(app.aduster.filters.FilterMeta(), listOf("a"), now))
    }

    /** Home's Rules row: when the lists were last checked, as Filters says, not when they last changed. */
    @Test
    fun homeRulesAgeIsTheLastCheck() {
        assertEquals(9L, app.aduster.ui.home.rulesAge(app.aduster.filters.FilterMeta(compiledAt = 5, lastRun = 9)))
        assertEquals(5L, app.aduster.ui.home.rulesAge(app.aduster.filters.FilterMeta(compiledAt = 5)))
    }

    /** Home says why protection is off when the user did not turn it off. */
    @Test
    fun homeSaysWhyProtectionStopped() {
        val now = 100_000_000L
        fun e(t: Long, ev: String, r: String? = null, c: String? = null) = Timeline.Entry(t, ev, r, c)
        fun note(vararg es: Timeline.Entry) = app.aduster.ui.home.stopNote(es.toList(), now)
        assertNull(note())
        assertEquals(
            "Stopped 2 h ago: turned off in system settings, or another VPN took over",
            note(e(1, Timeline.STARTED), e(now - 2 * 3_600_000, Timeline.STOPPED, "turned off in system settings, or another VPN took over", "revoked")),
        )
        assertEquals("Stopped just now: the DNS engine kept stopping", note(e(now - 1000, Timeline.STOPPED, "the DNS engine kept stopping", "failure")))
        assertEquals("Stopped 5 min ago: the filtering process ended (crash)", note(e(now - 300_000, Timeline.STOPPED, "the filtering process ended (crash)", "failure")))
        // Turned off by the user (also an entry from before codes existed), or started again: nothing to explain.
        assertNull(note(e(1, Timeline.STARTED), e(2, Timeline.STOPPED, "turned off in the app", "user_off")))
        assertNull(note(e(2, Timeline.RESUMED, "turned off", "off"), e(3, Timeline.STOPPED, "turned off in the app", "user_off")))
        assertNull(note(e(2, Timeline.STOPPED, "turned off in the app")))
        assertNull(note(e(2, Timeline.STOPPED, "the tunnel could not be set up", "failure"), e(3, Timeline.STARTED)))
    }

    /** The never-decrypt list takes hosts however they were pasted; what is no host is left out. */
    @Test
    fun exclusionHostsFromPastedLines() {
        val h = app.aduster.https.HttpsConfig::exclusionHost
        assertEquals("bank.example", h("https://Bank.example/login?x=1"))
        assertEquals("bank.example", h("*.bank.example"))
        assertNull(h("not a host"))
        assertNull(h(""))
        assertEquals("2001:db8::1", h("https://[2001:DB8::1]/admin"))
        assertEquals("2001:db8::1", h("2001:db8::1"))
        assertEquals("::ffff:192.0.2.1", h("::ffff:192.0.2.1"))
        assertNull("two colons are no IPv6 address", h("bad host::1"))
        assertNull(h("1::2::3"))
        assertNull(h("::ffff:999.0.2.1"))
        assertNull("an IPv4 part only at the end", h("1.2.3.4::"))
        assertNull(h("fe80:1.2.3.4::"))
        assertEquals("64:ff9b::192.0.2.1", h("64:ff9b::192.0.2.1"))
    }

    /**
     * The user's own never-decrypt lines: a pasted URL keeps its host whatever its query holds
     * (`$` is not an AdGuard modifier there), and a commented-out line excludes nothing, so the
     * app never shows as excluded a site the proxy decrypts.
     */
    @Test
    fun userNeverDecryptLines() {
        val text = "https://pay.example/?amt=\$50\n! https://bank.example\n# old.example\nbad host::1\nshop.example"
        assertEquals(listOf("pay.example", "shop.example"), app.aduster.https.HttpsConfig.userHosts(text))
        assertEquals(listOf(4), app.aduster.https.HttpsConfig.invalidExclusionLines(text))
    }

    /** A settings file with one bad field keeps every other field; text that is no JSON object gives nothing. */
    @Test
    fun settingsSalvageKeepsGoodFields() {
        val s = app.aduster.settings.salvageSettings("""{"userRules":"||a.example^","logSize":"many","blockDnsBypass":false}""")
        assertEquals("||a.example^", s?.userRules)
        assertEquals(false, s?.blockDnsBypass)
        assertEquals(Settings().logSize, s?.logSize)
        assertNull(app.aduster.settings.salvageSettings("[1,2]"))
        assertNull(app.aduster.settings.salvageSettings("not json"))
    }

    private fun over(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) =
        androidx.compose.ui.graphics.Color(
            fg.red * fg.alpha + bg.red * (1 - fg.alpha),
            fg.green * fg.alpha + bg.green * (1 - fg.alpha),
            fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
        )

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        fun lum(c: androidx.compose.ui.graphics.Color): Double {
            fun ch(v: Float) = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
