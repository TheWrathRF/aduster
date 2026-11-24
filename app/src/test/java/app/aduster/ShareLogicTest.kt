package app.aduster

import app.aduster.settings.Settings
import app.aduster.ui.SiteCheck
import app.aduster.ui.SiteReason
import app.aduster.ui.share.ShareAction
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
        // Already allowed and never decrypted: nothing left to offer.
        val done = Settings(httpsFiltering = true, userRules = "@@||news.example^", httpsExclusions = "news.example")
        assertEquals(emptyList<ShareAction>(), shareActions("news.example", done, null, pagesAvailable = true, userExcluded = true))
    }
}
