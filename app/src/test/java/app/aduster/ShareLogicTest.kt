package app.aduster

import app.aduster.settings.Settings
import app.aduster.ui.SiteCheck
import app.aduster.ui.SiteReason
import app.aduster.filters.UserRules
import app.aduster.ui.share.ShareAction
import app.aduster.ui.share.nothingToChange
import app.aduster.ui.share.shareActions
import app.aduster.ui.share.sharedHost
import org.junit.Assert.assertEquals
import org.junit.Test

/** Share a page to Aduster: the site it names and what the sheet offers for it. */
class ShareLogicTest {
    @Test
    fun theSiteComesFromTheSharedAddress() {
        assertEquals("www.news.example", sharedHost("https://www.news.example/world/story?id=4#top"))
        // Browsers share "Title\nhttps://…" or the title and address on one line.
        assertEquals("shop.example", sharedHost("Big sale at Shop\nhttps://shop.example/sale"))
        assertEquals("shop.example", sharedHost("Big sale https://Shop.Example:443/ more words"))
        assertEquals("plain.example", sharedHost("http://plain.example"))
        // Not a web address: nothing to act on.
        assertEquals(null, sharedHost("just some words"))
        assertEquals(null, sharedHost("ftp://files.site.example/x"))
        assertEquals(null, sharedHost("https://localhost/x"))
        assertEquals(null, sharedHost(null))
    }

    @Test
    fun oddAddressesNameTheRealSiteOrNothing() {
        // A user name before the host is not the site (it was taken for one).
        assertEquals("real.example", sharedHost("https://ads.example:@real.example/story"))
        assertEquals("real.example", sharedHost("https://user:@real.example/"))
        assertEquals("real.example", UserRules.siteHost("https://ads.example:@real.example/story"))
        // An address literal is not a site to allow or pause.
        assertEquals(null, sharedHost("https://127.0.0.1/x"))
        assertEquals(null, sharedHost("https://192.168.1.1:8080/"))
        // A name in another script, as its DNS form.
        assertEquals("xn--mnchen-3ya.de", sharedHost("https://m\u00fcnchen.de/stadt"))
        // Punctuation around the address in a sentence.
        assertEquals("news.example", sharedHost("Read this (https://news.example)."))
        // A name longer than DNS allows (five labels of 60).
        assertEquals(null, sharedHost("https://" + List(5) { "a".repeat(60) }.joinToString(".") + ".example/"))
    }

    @Test
    fun nothingToChangeSaysOnlyWhatIsTrue() {
        // Without page filtering nothing is decrypted anyway: only "allowed" is said.
        assertEquals("Nothing to change: this site is allowed", nothingToChange(Settings(httpsFiltering = false), pagesAvailable = true))
        assertEquals("Nothing to change: this site is allowed", nothingToChange(Settings(httpsFiltering = true), pagesAvailable = false))
        assertEquals("Nothing to change: this site is allowed and never decrypted", nothingToChange(Settings(httpsFiltering = true), pagesAvailable = true))
    }

    @Test
    fun theSheetOffersWhatApplies() {
        val on = Settings(httpsFiltering = true)
        val filtered = SiteCheck(decrypted = true, filteringActive = true, reasons = emptyList())
        // A filtered page: pause it for 15 minutes first, then allow it or never decrypt it.
        assertEquals(
            listOf(ShareAction.PAUSE, ShareAction.ALLOW, ShareAction.NEVER_DECRYPT),
            shareActions("news.example", on, filtered, pagesAvailable = true, userExcluded = false),
        )
        // Already paused by the user (or bypassed for a bot check): Filter again comes first.
        val paused = SiteCheck(decrypted = false, filteringActive = true, reasons = listOf(SiteReason("paused_by_you", 600, null)))
        assertEquals(
            listOf(ShareAction.FILTER_AGAIN, ShareAction.ALLOW, ShareAction.NEVER_DECRYPT),
            shareActions("news.example", on, paused, pagesAvailable = true, userExcluded = false),
        )
        // No page filtering (off, or not in this build): only the DNS-level allow.
        assertEquals(listOf(ShareAction.ALLOW), shareActions("news.example", Settings(httpsFiltering = false), null, pagesAvailable = true, userExcluded = false))
        assertEquals(listOf(ShareAction.ALLOW), shareActions("news.example", on, null, pagesAvailable = false, userExcluded = false))
        // Protection paused: nothing is filtered now, so no 15-minute pause.
        val all = SiteCheck(decrypted = true, filteringActive = false, reasons = emptyList())
        assertEquals(listOf(ShareAction.ALLOW, ShareAction.NEVER_DECRYPT), shareActions("news.example", on, all, pagesAvailable = true, userExcluded = false))
        // Allowed by the user's rules: its pages are not filtered, so no pause either.
        val allowed = SiteCheck(decrypted = true, filteringActive = true, reasons = listOf(SiteReason("user_allowed", null, null)))
        val allowRule = Settings(httpsFiltering = true, userRules = "@@||news.example^")
        assertEquals(listOf(ShareAction.NEVER_DECRYPT), shareActions("news.example", allowRule, allowed, pagesAvailable = true, userExcluded = false))
        // Already allowed and never decrypted: nothing left to offer.
        val done = Settings(httpsFiltering = true, userRules = "@@||news.example^", httpsExclusions = "news.example")
        assertEquals(emptyList<ShareAction>(), shareActions("news.example", done, null, pagesAvailable = true, userExcluded = true))
    }
}
