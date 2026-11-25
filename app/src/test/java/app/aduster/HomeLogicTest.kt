package app.aduster

import app.aduster.https.Browser
import app.aduster.ui.DayStat
import app.aduster.ui.LogFilterRequest
import app.aduster.ui.LogItem
import app.aduster.ui.QueryAction
import app.aduster.ui.Stats
import app.aduster.ui.common.Protection
import app.aduster.ui.historySlots
import app.aduster.ui.home.FIRST_RUN_TITLE
import app.aduster.ui.home.FIRST_RUN_VPN_NOTE
import app.aduster.ui.home.PAUSE_CHOICES
import app.aduster.ui.home.SetupFix
import app.aduster.ui.home.weekDescription
import app.aduster.ui.home.weekTrend
import app.aduster.ui.home.homeSetupRow
import app.aduster.ui.home.isFirstRun
import app.aduster.ui.home.rulesDetail
import app.aduster.ui.home.stateTitle
import app.aduster.ui.home.statusDetail
import app.aduster.ui.log.logMatches
import app.aduster.ui.parseSiteCheck
import app.aduster.ui.settings.browserFilterRows
import app.aduster.ui.settings.certificateStepsFolded
import app.aduster.ui.settings.exitLines
import app.aduster.ui.settings.installStep
import app.aduster.ui.settings.isChromiumBrowser
import app.aduster.ui.settings.licenceTitle
import app.aduster.ui.settings.timelineLines
import app.aduster.ui.settings.upstreamLabel
import app.aduster.vpn.ProcessExits
import app.aduster.vpn.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Pure logic tests for Home, Page filtering setup, Diagnostics copy and separator cleanup. */
class HomeLogicTest {
    private val midDot = '\u00B7'

    @Test
    fun historySlotsAlwaysFillThirtySlotsForZeroOneAndFortyDays() {
        // 0 days recorded (fresh install before stats exist): 30 empty tracks.
        val zero = historySlots(emptyList(), DayStat(0, 0, 0), slots = 30)
        assertEquals(30, zero.size)
        assertTrue(zero.all { it == null })

        // 1 day recorded (today only): 29 empty tracks before today's bar at the right edge.
        val one = historySlots(emptyList(), DayStat(100, 12, 4), slots = 30)
        assertEquals(30, one.size)
        assertTrue(one.take(29).all { it == null })
        assertEquals(DayStat(100, 12, 4), one.last())

        // 40 days recorded: trimmed to the newest 30 days ending at today.
        val fortyHistory = (61L..99L).map { DayStat(it, it * 10, it) }
        val forty = historySlots(fortyHistory, DayStat(100, 1000, 100), slots = 30)
        assertEquals(30, forty.size)
        assertEquals((71L..100L).toList(), forty.map { it?.day })
    }

    @Test
    fun weekChartDescriptionAndTrend() {
        val zero = historySlots(emptyList(), DayStat(0, 0, 0), slots = 7)
        assertEquals("Blocked per day: no data yet", weekDescription(zero))

        val one = historySlots(emptyList(), DayStat(100, 10, 5), slots = 7)
        assertEquals("Blocked in the last 1 day: 5, today 5", weekDescription(one))

        val five = historySlots(
            listOf(DayStat(96, 10, 10), DayStat(97, 10, 20), DayStat(98, 10, 30), DayStat(99, 10, 40)),
            DayStat(100, 10, 50),
            slots = 7,
        )
        assertEquals("Blocked in the last 5 days: 150, today 50", weekDescription(five))

        // Day one and the first week: no earlier week, no badge.
        assertNull(weekTrend(historySlots(emptyList(), DayStat(100, 10, 5), slots = 14)))
        assertNull(weekTrend(historySlots(listOf(DayStat(95, 10, 10)), DayStat(100, 10, 5), slots = 14)))
        // 100 the week before, 118 this week.
        val before = (87L..93L).map { DayStat(it, 50, if (it == 87L) 100 else 0) }
        val week = (94L..99L).map { DayStat(it, 50, if (it == 94L) 100 else 0) }
        assertEquals("+18% vs last week", weekTrend(historySlots(before + week, DayStat(100, 50, 18), slots = 14)))
        assertEquals("−50% vs last week", weekTrend(historySlots(before, DayStat(100, 50, 50), slots = 14)))
        assertEquals("Same as last week", weekTrend(historySlots(before, DayStat(100, 50, 100), slots = 14)))
        // Installed late on the last day of the earlier week: one evening is not a week to compare with
        // (it read "+69900% vs last week").
        val evening = listOf(DayStat(93, 10, 5)) + (94L..99L).map { DayStat(it, 900, 500) }
        assertNull(weekTrend(historySlots(evening, DayStat(100, 900, 500), slots = 14)))
    }

    @Test
    fun statusActiveLineCoversEveryProtectionState() {
        assertEquals("Protected", stateTitle(Protection.ON))
        assertEquals("Paused", stateTitle(Protection.PAUSED))
        assertEquals("Off", stateTitle(Protection.OFF))

        assertEquals("DNS and browser pages", statusDetail(Protection.ON, httpsActive = true, pausedUntil = 0))
        assertEquals("DNS only", statusDetail(Protection.ON, httpsActive = false, pausedUntil = 0))
        assertNull(statusDetail(Protection.ON, httpsActive = false, pausedUntil = 0, unknown = true))
        assertEquals("Until you resume", statusDetail(Protection.PAUSED, httpsActive = false, pausedUntil = Long.MAX_VALUE))
        assertEquals("Not filtering", statusDetail(Protection.OFF, httpsActive = false, pausedUntil = 0))

        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val at1430 = 14 * 3_600_000L + 30 * 60_000L
            assertEquals("Until 14:30", statusDetail(Protection.PAUSED, httpsActive = true, pausedUntil = at1430))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun pauseChoicesAndDurationsMatchSpec() {
        assertEquals(
            listOf("5 min" to 5, "15 min" to 15, "1 hour" to 60, "Until you resume" to 0),
            PAUSE_CHOICES.map { it.label to it.minutes },
        )
    }

    @Test
    fun firstRunNoteShowsOnlyBeforeProtectionHasEverRun() {
        assertTrue(isFirstRun(running = false, stats = Stats.EMPTY, timeline = emptyList()))
        assertFalse(isFirstRun(running = true, stats = Stats.EMPTY.copy(running = true), timeline = emptyList()))
        assertFalse(isFirstRun(running = false, stats = Stats.EMPTY, timeline = listOf(Timeline.Entry(1_000L, Timeline.STARTED, "from the app"))))
        // A fresh install already carries today's date; only counted lookups mean it has run.
        assertTrue(isFirstRun(running = false, stats = Stats.EMPTY.copy(day = 20_000L), timeline = emptyList()))
        assertFalse(isFirstRun(running = false, stats = Stats.EMPTY.copy(day = 20_000L, queries = 3L), timeline = emptyList()))
        assertFalse(
            isFirstRun(
                running = false,
                stats = Stats.EMPTY.copy(day = 20_000L, history = listOf(app.aduster.ui.DayStat(19_999L, 40L, 2L))),
                timeline = emptyList(),
            ),
        )
        assertTrue(FIRST_RUN_VPN_NOTE.contains("VPN connection"))
        assertFalse(FIRST_RUN_VPN_NOTE.contains(midDot))
    }

    @Test
    fun homeSetupRowShowsForStrictPrivateDnsOrUnsetPageFiltering() {
        // Strict Private DNS takes priority over page filtering setup.
        val dns = homeSetupRow(privateDnsStrict = true, httpsAvailable = true, httpsOn = false, caInstalled = false)
        assertNotNull(dns)
        assertEquals("Setup", dns!!.title)
        assertEquals(SetupFix.PRIVATE_DNS, dns.fix)
        assertTrue(dns.warn)

        // Strict Private DNS also shows in builds without page filtering.
        val dnsOnlyBuild = homeSetupRow(privateDnsStrict = true, httpsAvailable = false, httpsOn = false, caInstalled = false)
        assertEquals(SetupFix.PRIVATE_DNS, dnsOnlyBuild?.fix)

        // Page filtering never set up in a build that supports it.
        val https = homeSetupRow(privateDnsStrict = false, httpsAvailable = true, httpsOn = false, caInstalled = false)
        assertNotNull(https)
        assertEquals("Setup", https!!.title)
        assertEquals(SetupFix.HTTPS, https.fix)
        assertFalse(https.warn)

        // Once the certificate is installed or page filtering is enabled, the Setup row hides.
        assertNull(homeSetupRow(privateDnsStrict = false, httpsAvailable = true, httpsOn = false, caInstalled = true))
        assertNull(homeSetupRow(privateDnsStrict = false, httpsAvailable = true, httpsOn = true, caInstalled = false))
        assertNull(homeSetupRow(privateDnsStrict = false, httpsAvailable = false, httpsOn = false, caInstalled = false))
    }

    @Test
    fun logFilterRequestTargetsDomainOrApp() {
        val domainReq = LogFilterRequest(query = "ads.example")
        val appReq = LogFilterRequest(appUid = 10123)
        val matchingDomain = LogItem(1, 0, "ads.example", 1, 10100, QueryAction.BLOCKED, null, null, 0)
        val matchingApp = LogItem(2, 0, "other.example", 1, 10123, QueryAction.BLOCKED, null, null, 0)
        val apps = mapOf(10100 to "Example app", 10123 to "Second app")

        assertTrue(logMatches(matchingDomain, 0, false, false, domainReq.appUid, domainReq.query, emptySet()) { apps[it].orEmpty() })
        assertFalse(logMatches(matchingApp, 0, false, false, domainReq.appUid, domainReq.query, emptySet()) { apps[it].orEmpty() })
        assertTrue(logMatches(matchingApp, 0, false, false, appReq.appUid, appReq.query, emptySet()) { apps[it].orEmpty() })
        assertFalse(logMatches(matchingDomain, 0, false, false, appReq.appUid, appReq.query, emptySet()) { apps[it].orEmpty() })
    }

    @Test
    fun pageFilteringCertificateFoldingAndSavedFilename() {
        assertTrue(certificateStepsFolded(installed = true, needsNew = false))
        assertFalse(certificateStepsFolded(installed = false, needsNew = false))
        assertFalse(certificateStepsFolded(installed = null, needsNew = false))
        assertFalse(certificateStepsFolded(installed = true, needsNew = true))

        assertTrue(installStep(null).contains("Aduster_CA.crt"))
        assertTrue(installStep("").contains("Aduster_CA.crt"))
        assertTrue(installStep("Aduster_CA (2).crt").contains("Aduster_CA (2).crt"))
    }

    @Test
    fun browserClassificationMarksChromiumAsFilteredAndFirefoxAsDnsOnly() {
        assertTrue(isChromiumBrowser("com.android.chrome"))
        assertTrue(isChromiumBrowser("com.chrome.beta"))
        assertTrue(isChromiumBrowser("com.brave.browser"))
        assertTrue(isChromiumBrowser("com.microsoft.emmx"))
        assertTrue(isChromiumBrowser("com.sec.android.app.sbrowser"))
        assertTrue(isChromiumBrowser("com.vivaldi.browser"))
        assertTrue(isChromiumBrowser("org.cromite.cromite"))
        assertTrue(isChromiumBrowser("app.vanadium.browser"))

        assertFalse(isChromiumBrowser("org.mozilla.firefox"))
        assertFalse(isChromiumBrowser("org.mozilla.fenix"))
        assertFalse(isChromiumBrowser("org.mozilla.focus"))
        assertFalse(isChromiumBrowser("org.torproject.torbrowser"))
        assertFalse(isChromiumBrowser("com.example.browser"))

        val installed = listOf(
            Browser("com.android.chrome", 10100, "Chrome"),
            Browser("org.mozilla.firefox", 10200, "Firefox"),
            Browser("com.brave.browser", 10300, "Brave"),
            Browser("com.example.browser", 10400, "Example browser"),
        )
        val rows = browserFilterRows(
            browsers = installed,
            filteredBrowsers = listOf("Example browser"),
            untrustedBrowsers = listOf("Brave"),
        )
        assertEquals(
            listOf(
                "Chrome" to "Page filtering",
                "Firefox" to "DNS only",
                "Brave" to "DNS only",
                "Example browser" to "Page filtering",
            ),
            rows.map { it.browser.name to it.status },
        )
        assertEquals(listOf(true, false, false, true), rows.map { it.pageFiltered })
        // Page filtering off (or no certificate yet): every browser gets DNS only right now; an
        // excluded browser gets neither.
        val off = browserFilterRows(installed, pagesOn = false, excludedApps = setOf("org.mozilla.firefox"))
        assertEquals(listOf("DNS only", "Excluded", "DNS only", "DNS only"), off.map { it.status })
        assertEquals(listOf(false, false, false, false), off.map { it.pageFiltered })
        val excluded = browserFilterRows(installed, excludedApps = setOf("com.android.chrome"))
        assertEquals("Chrome" to "Excluded", excluded.first().let { it.browser.name to it.status })
        assertFalse(excluded.first().pageFiltered)
    }

    @Test
    fun diagnosticsUpstreamLabelReplacesRawSystem() {
        assertEquals("Network default", upstreamLabel("system"))
        assertEquals("Network default", upstreamLabel(null))
        assertEquals("Network default", upstreamLabel(""))
        assertEquals("DoH, cloudflare-dns.com", upstreamLabel("doh cloudflare-dns.com"))
        assertEquals("DoT, one.one.one.one", upstreamLabel("dot one.one.one.one"))
        assertEquals("custom", upstreamLabel("custom"))
    }

    @Test
    fun formattedStringsHaveNoMiddleDotSeparator() {
        val now = 10_000_000L
        val rulesText = rulesDetail(now - 120_000L, now)
        assertEquals("Updated 2 min ago", rulesText)
        assertEquals("Not updated yet", rulesDetail(0, now))

        val timeline = timelineLines(
            listOf(
                Timeline.Entry(1_000, Timeline.STARTED, "from the app"),
                Timeline.Entry(2_000, Timeline.PAUSED, "for 5 min"),
                Timeline.Entry(3_000, Timeline.RESUMED, "by you"),
                Timeline.Entry(4_000, Timeline.STOPPED, "turned off in the app", "user_off"),
            ),
        )
        val exits = exitLines(
            listOf(
                ProcessExits.Exit("app.aduster", 2_000, "app updated", "update", "cached", 0, 0),
                ProcessExits.Exit("app.aduster:core", 1_000, "crash", "crash", "foreground service", 0, 0),
            ),
        )
        assertEquals(listOf("App: updated", "Protection: crashed"), exits.map { it.first })

        val site = parseSiteCheck(
            """{"proxy":true,"decrypted":false,"filtering_active":true,"reasons":[{"kind":"passed_through","reason":"tls_error","detail":"handshake"}]}""",
        )!!
        assertEquals("tls error, handshake", site.reasons.single().detail)

        val licence = licenceTitle("GoogleSansCode-OFL.txt")
        assertEquals("Google Sans Code, OFL", licence)

        val texts = buildList {
            add(rulesText)
            add(licence)
            add(site.reasons.single().detail!!)
            add(installStep("Aduster_CA (1).crt"))
            add(upstreamLabel("system"))
            add(upstreamLabel("doh dns.example"))
            addAll(PAUSE_CHOICES.map { it.label })
            addAll(timeline.flatMap { listOf(it.first, it.second) })
            addAll(exits.flatMap { listOf(it.first, it.second) })
            add(weekDescription(historySlots(emptyList(), DayStat(100, 10, 5), slots = 7)))
            add(FIRST_RUN_TITLE)
            add(statusDetail(Protection.ON, true, 0)!!)
            add(statusDetail(Protection.ON, false, 0)!!)
            add(statusDetail(Protection.OFF, false, 0)!!)
        }
        assertTrue(texts.none { midDot in it })
    }
}
