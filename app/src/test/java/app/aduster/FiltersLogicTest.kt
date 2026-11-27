package app.aduster

import app.aduster.core.Upstreams
import app.aduster.filters.Device
import app.aduster.filters.FilterCatalog
import app.aduster.filters.FilterJobs
import app.aduster.filters.FilterList
import app.aduster.filters.FilterMeta
import app.aduster.filters.FilterStore
import app.aduster.filters.FilterUpdater
import app.aduster.filters.ListMeta
import app.aduster.filters.UpdatePhase
import app.aduster.settings.Settings
import app.aduster.ui.LogItem
import app.aduster.ui.QueryAction
import app.aduster.ui.SiteCheck
import app.aduster.ui.SiteReason
import app.aduster.ui.common.failedSummary
import app.aduster.ui.common.phaseWeights
import app.aduster.ui.common.progressFraction
import app.aduster.ui.common.progressLabel
import app.aduster.ui.filters.RULE_SYNTAX_HELP
import app.aduster.ui.filters.SiteFixAction
import app.aduster.ui.filters.activeRuleCount
import app.aduster.ui.filters.allowSiteRule
import app.aduster.ui.filters.browserLines
import app.aduster.ui.filters.failedListNames
import app.aduster.ui.filters.filtersSummaryText
import app.aduster.ui.filters.fixSiteWhy
import app.aduster.ui.filters.listDetail
import app.aduster.ui.filters.rulesEditorPlaceholder
import app.aduster.ui.filters.showPageRulesSummary
import app.aduster.ui.filters.siteFixActions
import app.aduster.ui.filters.sitesHeading
import app.aduster.ui.filters.updateButtonEnabled
import app.aduster.ui.filters.updateLine
import app.aduster.ui.filters.withNeverDecrypt
import app.aduster.ui.filters.withoutNeverDecrypt
import app.aduster.ui.log.LogLine
import app.aduster.ui.log.proxySiteOf
import app.aduster.ui.parseSiteCheck
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections

/** Pure logic tests for Filters header, phased update progress, Fix a site, copy fixes and separators. */
class FiltersLogicTest {
    private val english = Device(listOf("en"), "Google")

    @Test
    fun progressCountsOnlyVisibleListsFromDefaultCatalog() {
        val s = Settings(httpsFiltering = true)
        val dns = FilterCatalog.enabledDns(s, english)
        val web = FilterCatalog.enabledWeb(s, english) + FilterCatalog.exclusionLists
        val all = dns + web
        assertEquals("default catalog set has 25 downloaded lists (17 visible + 5 companions + 3 never-decrypt)", 25, all.size)
        assertEquals(17, all.count(FilterUpdater::isVisible))
        assertEquals(17, FilterUpdater.downloaded(s, web = true, device = english).size)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        server.createContext("/") { ex ->
            val b = "||ads.example^\n".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
            ex.close()
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-vis-progress").toFile()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val localDns = dns.map { it.copy(url = "$base/${it.id}.txt") }
            val localWeb = web.map { it.copy(url = "$base/${it.id}.txt") }
            val seen = Collections.synchronizedList(mutableListOf<FilterJobs.Progress>())
            FilterUpdater.updateStore(
                store = FilterStore(tmp),
                readSettings = { s },
                check = FilterUpdater.Check.ALL,
                webWanted = { true },
                compileDns = { true },
                compileWeb = { true },
                dnsListsOf = { localDns },
                webListsOf = { localWeb },
                progress = { seen += it },
            )
            val checking = seen.filter { it.phase == UpdatePhase.CHECKING }
            assertEquals("initial 0 of 17 plus 17 completions", 18, checking.size)
            assertTrue("every checking update reports visible total 17", checking.all { it.total == 17 })
            assertEquals((0..17).toList(), checking.map { it.done })
            assertEquals("Checking lists: 6 of 17", progressLabel(UpdatePhase.CHECKING, 6, 17))
            val listNames = all.map { it.name }
            for (p in seen) {
                val label = progressLabel(p.phase, p.done, p.total)
                assertTrue("label must never name a list: $label", listNames.none { label.contains(it) })
            }
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun phasesOrderAndWeightsAdaptWithPageFilteringOnAndOff() {
        val s = Settings(httpsFiltering = true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val b = "||ads.example^\n".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
            ex.close()
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-phases").toFile()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val d = FilterCatalog.byId("adguard-dns")!!.copy(url = "$base/dns.txt")
            val w = FilterCatalog.byId("adguard-base")!!.copy(url = "$base/web.txt")

            fun run(withPages: Boolean): List<FilterJobs.Progress> {
                val events = Collections.synchronizedList(mutableListOf<FilterJobs.Progress>())
                FilterUpdater.updateStore(
                    store = FilterStore(Files.createTempDirectory(tmp.toPath(), "run").toFile()),
                    readSettings = { s.copy(httpsFiltering = withPages) },
                    check = FilterUpdater.Check.ALL,
                    webWanted = { withPages },
                    compileDns = { true },
                    compileWeb = { true },
                    dnsListsOf = { listOf(d) },
                    webListsOf = { listOf(w) },
                    progress = { events += it },
                )
                return events
            }

            fun distinctOrder(events: List<FilterJobs.Progress>): List<UpdatePhase> =
                events.map { it.phase }.fold(mutableListOf()) { acc, phase ->
                    if (acc.lastOrNull() != phase) acc += phase
                    acc
                }

            val withPages = run(withPages = true)
            // The DNS rules are applied while page lists may still download; "Applying DNS rules"
            // shows only when nothing is left to check, so it may be skipped here.
            val order = distinctOrder(withPages)
            assertTrue(
                "$order",
                order == listOf(UpdatePhase.CHECKING, UpdatePhase.APPLYING_DNS, UpdatePhase.BUILDING_PAGES) ||
                    order == listOf(UpdatePhase.CHECKING, UpdatePhase.BUILDING_PAGES),
            )
            assertTrue(withPages.all { it.withPages })

            val withoutPages = run(withPages = false)
            assertEquals(listOf(UpdatePhase.CHECKING, UpdatePhase.APPLYING_DNS), distinctOrder(withoutPages))
            assertTrue(withoutPages.none { it.phase == UpdatePhase.BUILDING_PAGES })
            assertTrue(withoutPages.none { it.withPages })
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }

        // Phase weights and fractions adapt when page filtering is off.
        val wOn = phaseWeights(withPages = true)
        val wOff = phaseWeights(withPages = false)
        assertEquals(1f, wOn.values.sum(), 1e-5f)
        assertEquals(1f, wOff.values.sum(), 1e-5f)
        assertTrue(UpdatePhase.BUILDING_PAGES in wOn)
        assertFalse(UpdatePhase.BUILDING_PAGES in wOff)

        val checkOn = progressFraction(UpdatePhase.CHECKING, 6, 17, withPages = true)!!
        val checkOff = progressFraction(UpdatePhase.CHECKING, 6, 17, withPages = false)!!
        assertTrue("checking takes a larger share when page phase is absent", checkOff > checkOn)

        val dnsOn = progressFraction(UpdatePhase.APPLYING_DNS, 17, 17, withPages = true)!!
        val dnsOff = progressFraction(UpdatePhase.APPLYING_DNS, 17, 17, withPages = false)!!
        assertTrue(dnsOn > progressFraction(UpdatePhase.CHECKING, 17, 17, withPages = true)!!)
        assertTrue(dnsOff > progressFraction(UpdatePhase.CHECKING, 17, 17, withPages = false)!!)
        assertTrue(dnsOff > dnsOn)

        // Page compile and waiting for network have no inner progress (indeterminate).
        assertNull(progressFraction(UpdatePhase.BUILDING_PAGES, 17, 17, withPages = true))
        assertNull(progressFraction(UpdatePhase.WAITING_NETWORK, 0, 0, withPages = true))
        assertNull(progressFraction(UpdatePhase.CHECKING, 0, 0, withPages = true))

        assertEquals("Applying DNS rules", progressLabel(UpdatePhase.APPLYING_DNS, 17, 17))
        assertEquals("Building page rules", progressLabel(UpdatePhase.BUILDING_PAGES, 17, 17))
        assertEquals("Waiting for network", progressLabel(UpdatePhase.WAITING_NETWORK, 0, 0))
    }

    @Test
    fun updateButtonIsDisabledWhileRunning() {
        assertTrue(updateButtonEnabled(null))
        for (phase in UpdatePhase.entries) {
            assertFalse(phase.name, updateButtonEnabled(FilterJobs.Progress(phase)))
        }
    }

    @Test
    fun failedVisibleListsAreNamedAndHiddenListsExcluded() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            if (path.contains("ok")) {
                val b = "||ads.example^\n".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            } else {
                ex.sendResponseHeaders(500, -1)
            }
            ex.close()
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-failed").toFile()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val okDns = FilterCatalog.byId("adguard-dns")!!.copy(url = "$base/ok-dns.txt")
            val badVisible = FilterCatalog.byId("adguard-base")!!.copy(url = "$base/fail-base.txt")
            val badCompanion = FilterCatalog.trustedCompanions.first { it.id == "adguard-base-trusted" }.copy(url = "$base/fail-comp.txt")
            val badExclusion = FilterCatalog.exclusionLists.first().copy(url = "$base/fail-excl.txt")
            val store = FilterStore(tmp)
            val s = Settings(httpsFiltering = true)
            val seen = Collections.synchronizedList(mutableListOf<FilterJobs.Progress>())

            FilterUpdater.updateStore(
                store = store,
                readSettings = { s },
                check = FilterUpdater.Check.ALL,
                webWanted = { true },
                compileDns = { false },
                compileWeb = { false },
                dnsListsOf = { listOf(okDns) },
                webListsOf = { listOf(badVisible, badCompanion, badExclusion) },
                progress = { seen += it },
            )

            assertEquals(listOf("AdGuard Base"), seen.last().failed)
            assertEquals(listOf("AdGuard Base"), failedListNames(store.readMeta(), s, web = true, device = english))
            assertEquals("Failed: AdGuard Base", failedSummary(listOf("AdGuard Base")))
            assertEquals("Failed: A, B", failedSummary(listOf("A", "B")))
            assertEquals("Failed (5): A, B, C and 2 more", failedSummary(listOf("A", "B", "C", "D", "E")))
            assertNull(failedSummary(emptyList()))
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    @Test
    fun homeCountsPageRulesOnlyWhenTheyAreInUse() {
        val meta = FilterMeta(compiledRules = 1200, webRules = 500)
        assertEquals(1700, activeRuleCount(meta, available = true, httpsFiltering = true, caTrusted = true))
        // Like Filters' header: no certificate, page filtering off, or a page engine that failed to build.
        assertEquals(1200, activeRuleCount(meta, available = true, httpsFiltering = true, caTrusted = false))
        assertEquals(1200, activeRuleCount(meta, available = true, httpsFiltering = false, caTrusted = true))
        assertEquals(1200, activeRuleCount(meta.copy(webError = "compile failed"), available = true, httpsFiltering = true, caTrusted = true))
    }

    @Test
    fun fixASiteNamesTheBlockingListAsTheAppDoes() {
        val s = Settings()
        assertEquals("||ads.example^ from My rules", fixSiteWhy("||ads.example^", "user", s))
        assertEquals("||bank.example^ from AdGuard HTTPS exclusions (banks)", fixSiteWhy("||bank.example^", "adguard-https-banks", s))
        assertEquals("", fixSiteWhy(null, null, s))
    }

    @Test
    fun pageRulesCountShownOnlyWithTrustedCertificate() {
        assertTrue(showPageRulesSummary(available = true, httpsFiltering = true, caTrusted = true))
        assertFalse(showPageRulesSummary(available = true, httpsFiltering = true, caTrusted = false))
        assertFalse(showPageRulesSummary(available = true, httpsFiltering = false, caTrusted = true))
        assertFalse(showPageRulesSummary(available = false, httpsFiltering = true, caTrusted = true))

        val now = 10_000_000L
        val meta = FilterMeta(compiledRules = 1200, webRules = 500, lastRun = now - 30_000)
        val withPages = filtersSummaryText(meta, listOf("adguard-dns"), pageRules = true, now = now)
        val withoutPages = filtersSummaryText(meta, listOf("adguard-dns"), pageRules = false, now = now)
        assertEquals("1,200 DNS rules, 500 page rules, updated just now", withPages)
        assertEquals("1,200 DNS rules, updated just now", withoutPages)
        assertFalse(withoutPages.contains("page rule"))

        val withError = filtersSummaryText(meta.copy(webError = "disk full"), listOf("adguard-dns"), pageRules = true, now = now)
        assertEquals("1,200 DNS rules, updated just now", withError)
    }

    @Test
    fun fixSiteActionsAndLogPassedThrough() {
        val sOn = Settings(httpsFiltering = true)
        val sOff = Settings(httpsFiltering = false)

        assertEquals("@@||shop.example^", allowSiteRule("shop.example"))
        assertEquals("bank.example\nshop.example", withNeverDecrypt("bank.example", "shop.example"))
        assertEquals("bank.example", withNeverDecrypt("bank.example", "BANK.example"))
        assertEquals("bank.example", withoutNeverDecrypt("bank.example\nshop.example", "shop.example"))

        val normal = SiteCheck(decrypted = true, filteringActive = true, reasons = emptyList())
        val botBypassed = SiteCheck(decrypted = false, filteringActive = true, reasons = listOf(SiteReason("bot_check", 300, null)))
        val passedThrough = SiteCheck(decrypted = false, filteringActive = true, reasons = listOf(SiteReason("passed_through", null, "tls error")))
        val alreadyAllowed = SiteCheck(decrypted = false, filteringActive = true, reasons = listOf(SiteReason("user_allowed", null, null)))
        val sensitive = SiteCheck(decrypted = false, filteringActive = true, reasons = listOf(SiteReason("never_decrypt", null, null)))

        assertFalse(normal.bypassed)
        assertTrue(botBypassed.bypassed)
        assertTrue(passedThrough.bypassed)
        assertFalse(sensitive.bypassed)

        assertEquals(listOf(SiteFixAction.ALLOW, SiteFixAction.NEVER_DECRYPT), siteFixActions("shop.example", sOn, normal, pagesAvailable = true, userExcluded = false))
        assertEquals(listOf(SiteFixAction.FILTER_AGAIN, SiteFixAction.ALLOW, SiteFixAction.NEVER_DECRYPT), siteFixActions("shop.example", sOn, botBypassed, pagesAvailable = true, userExcluded = false))
        assertEquals(listOf(SiteFixAction.FILTER_AGAIN, SiteFixAction.ALLOW, SiteFixAction.NEVER_DECRYPT), siteFixActions("shop.example", sOn, passedThrough, pagesAvailable = true, userExcluded = false))
        assertEquals(listOf(SiteFixAction.NEVER_DECRYPT), siteFixActions("shop.example", sOn, alreadyAllowed, pagesAvailable = true, userExcluded = false))
        assertEquals(listOf(SiteFixAction.NEVER_DECRYPT), siteFixActions("shop.example", sOn.copy(userRules = "@@||shop.example^"), normal, pagesAvailable = true, userExcluded = false))
        // A name under an allowed site is allowed already (the rule covers it), also without page filtering.
        assertEquals(emptyList<SiteFixAction>(), siteFixActions("www.shop.example", sOff.copy(userRules = "@@||shop.example^"), null, pagesAvailable = true, userExcluded = false))
        assertEquals(listOf(SiteFixAction.ALLOW), siteFixActions("shop.example", sOn, sensitive, pagesAvailable = true, userExcluded = false))
        assertEquals(listOf(SiteFixAction.ALLOW), siteFixActions("shop.example", sOn, normal, pagesAvailable = true, userExcluded = true))
        assertEquals(listOf(SiteFixAction.ALLOW), siteFixActions("shop.example", sOff, null, pagesAvailable = true, userExcluded = false))

        // Log details: proxy site is present for connection rows, page-filtered rows, and folded lookup+connection rows, not plain DNS rows.
        val dnsOnly = LogLine.Query(listOf(LogItem(1, 0, "news.test", 1, 10, QueryAction.UPSTREAM, null, null, 0)))
        val connOnly = LogLine.Query(listOf(LogItem(2, 0, "news.test", 0, 10, QueryAction.UPSTREAM, null, null, 0)))
        val folded = LogLine.Query(
            listOf(
                LogItem(4, 100, "news.test", 0, 10, QueryAction.UPSTREAM, null, null, 0),
                LogItem(3, 0, "news.test", 1, 10, QueryAction.UPSTREAM, null, null, 0),
            ),
        )
        val pageBlock = LogLine.Query(listOf(LogItem(5, 0, "ads.test", 0, 10, QueryAction.FILTERED, null, null, 0, page = "news.test")))
        assertNull(proxySiteOf(dnsOnly, setOf(10)))
        assertEquals("news.test", proxySiteOf(connOnly, setOf(10)))
        assertEquals("news.test", proxySiteOf(folded, setOf(10)))
        assertEquals("news.test", proxySiteOf(pageBlock, setOf(10)))
    }

    @Test
    fun otherAppsProxiedConnectionsStayOutOfBrowsersAndPages() {
        val browsers = setOf(10)
        val mail = LogItem(6, 0, "api.mail.test", 0, 77, QueryAction.UPSTREAM, null, null, 0)
        val chrome = LogItem(7, 0, "news.test", 0, 10, QueryAction.UPSTREAM, null, null, 0)
        val pageBlock = LogItem(8, 0, "ads.test", 0, 10, QueryAction.FILTERED, null, null, 0, page = "news.test")
        val mailLookup = LogItem(9, 0, "api.mail.test", 1, 77, QueryAction.UPSTREAM, null, null, 0)
        assertFalse("Browsers chip", app.aduster.ui.log.logMatches(mail, 0, true, false, null, "", browsers) { "" })
        assertFalse("Pages chip", app.aduster.ui.log.logMatches(mail, 0, false, true, null, "", browsers) { "" })
        assertFalse("Browsers chip, lookup", app.aduster.ui.log.logMatches(mailLookup, 0, true, false, null, "", browsers) { "" })
        for (e in listOf(chrome, pageBlock)) {
            assertTrue(app.aduster.ui.log.logMatches(e, 0, true, false, null, "", browsers) { "" })
            assertTrue(app.aduster.ui.log.logMatches(e, 0, false, true, null, "", browsers) { "" })
        }
        assertTrue("all rows", app.aduster.ui.log.logMatches(mail, 0, false, false, null, "", browsers) { "" })
        // "Never decrypt" is offered for browsers' connections only.
        assertNull(proxySiteOf(LogLine.Query(listOf(mail)), browsers))
        assertEquals("news.test", proxySiteOf(LogLine.Query(listOf(chrome)), browsers))
    }

    @Test
    fun dnsPickerNamesAreAllDistinctAndLabelDoesNotRepeatProtocol() {
        val pickerNames = Upstreams.presets.map { Upstreams.pickerLabel(it) }
        assertEquals("every DNS picker row must have a distinct name", pickerNames.size, pickerNames.toSet().size)
        assertEquals("Cloudflare (DNS-over-HTTPS)", Upstreams.pickerLabel(Upstreams.byId("cloudflare-doh")))
        assertEquals("Cloudflare (DNS-over-TLS)", Upstreams.pickerLabel(Upstreams.byId("cloudflare-dot")))
        assertEquals("Quad9 (DNS-over-HTTPS)", Upstreams.pickerLabel(Upstreams.byId("quad9-doh")))
        assertEquals("Quad9 (DNS-over-TLS)", Upstreams.pickerLabel(Upstreams.byId("quad9-dot")))
        assertEquals("AdGuard (unfiltered, DNS-over-HTTPS)", Upstreams.pickerLabel(Upstreams.byId("adguard-doh")))
        assertEquals("Network default", Upstreams.pickerLabel(Upstreams.byId("system")))
        assertEquals("Custom", Upstreams.pickerLabel(Upstreams.byId("custom")))

        // Picker detail omits the protocol already shown in the picker row name.
        assertEquals("1.1.1.1", Upstreams.pickerDetail(Upstreams.byId("cloudflare-doh")))
        assertEquals("9.9.9.9, malware filtering", Upstreams.pickerDetail(Upstreams.byId("quad9-dot")))

        // Upstreams.label prints the protocol once (not twice).
        assertEquals("Cloudflare, DNS-over-HTTPS", Upstreams.label("cloudflare-doh", ""))
        assertEquals("Cloudflare, DNS-over-TLS", Upstreams.label("cloudflare-dot", ""))
        assertEquals("Quad9, DNS-over-HTTPS", Upstreams.label("quad9-doh", ""))
        assertEquals("Network default", Upstreams.label("system", ""))
        assertEquals("https://dns.example/q", Upstreams.label("custom", "https://dns.example/q"))
        assertEquals("Network default (custom address not valid)", Upstreams.label("custom", ""))
    }

    @Test
    fun myRulesEmptyHelpIsShownOnce() {
        val placeholder = rulesEditorPlaceholder("")
        assertNotNull(placeholder)
        assertNotEquals(RULE_SYNTAX_HELP, placeholder)
        assertFalse("empty editor placeholder must not repeat the syntax help", placeholder!!.contains("||ads.example.com^"))
        assertNull(rulesEditorPlaceholder("||ads.example^"))
    }

    @Test
    fun noMiddleDotInOwnedCopy() {
        val dot = '\u00B7'
        val allLists: List<FilterList> = FilterCatalog.lists + FilterCatalog.trustedCompanions + FilterCatalog.exclusionLists
        for (l in allLists) {
            assertFalse("list name has middle dot: ${l.name}", l.name.contains(dot))
            assertFalse("list summary has middle dot: ${l.summary}", l.summary.contains(dot))
        }
        for (p in Upstreams.presets) {
            assertFalse(p.label.contains(dot))
            assertFalse(p.detail.contains(dot))
            assertFalse(Upstreams.pickerLabel(p).contains(dot))
            assertFalse(Upstreams.pickerDetail(p).contains(dot))
            assertFalse(Upstreams.label(p.id, "").contains(dot))
        }
        val now = 10_000_000L
        val badMeta = FilterMeta(lists = mapOf("a" to ListMeta(rules = 10, error = "HTTP 500")), compiledRules = 10, lastRun = now - 5_000)
        assertFalse(filtersSummaryText(badMeta, listOf("a"), pageRules = false, now = now).contains(dot))
        assertFalse(updateLine(badMeta, listOf("a"), now).contains(dot))
        assertFalse(listDetail(ListMeta(rules = 10, updatedAt = now - 5_000), now).contains(dot))
        assertFalse(listDetail(ListMeta(rules = 10, error = "HTTP 500"), now).contains(dot))
        assertFalse(sitesHeading("Allowed", 3).contains(dot))
        assertFalse(sitesHeading("Blocked", 0).contains(dot))

        val parsed = parseSiteCheck("""{"proxy":true,"decrypted":false,"filtering_active":true,"reasons":[{"kind":"passed_through","reason":"tls_error","detail":"handshake"}]}""")!!
        assertEquals("tls error, handshake", parsed.reasons.single().detail)
        assertFalse(parsed.reasons.single().detail!!.contains(dot))

        val reasons = listOf("never_decrypt", "user_allowed", "bot_check", "media", "opened_anyway", "network_block", "passed_through", "other")
            .map { SiteReason(it, 120, "detail") }
        val lines = browserLines(SiteCheck(decrypted = true, filteringActive = false, reasons = reasons), Settings(httpsFiltering = true), pagesAvailable = true, userExcluded = true, now = now)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.none { it.first.contains(dot) })
        assertTrue(browserLines(null, Settings(httpsFiltering = false), pagesAvailable = true, userExcluded = false).none { it.first.contains(dot) })
        assertTrue(browserLines(null, Settings(httpsFiltering = true), pagesAvailable = false, userExcluded = false).none { it.first.contains(dot) })
    }

    /** A bypass that ends on another day (a media host's 6 h past midnight) names the day, as Diagnostics does. */
    @Test
    fun aSiteBypassEndingTomorrowNamesTheDay() {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalDate.of(2026, 11, 27).atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val media = SiteCheck(decrypted = false, filteringActive = true, reasons = listOf(SiteReason("media", 6 * 3600L, null)))
        val lines = browserLines(media, Settings(httpsFiltering = true), pagesAvailable = true, userExcluded = false, now = now)
        assertTrue(lines.toString(), lines.any { "28 Nov 05:00" in it.first })
    }
}
