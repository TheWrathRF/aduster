package app.aduster.filters

import app.aduster.filters.UserRules.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The helpers that read and rewrite the user's own rules. */
class UserRulesTest {
    /** The DNS matcher's verdict for a test: the reason it gives for each rejected line. */
    private fun matcher(reasons: Map<String, String>): (String) -> String = { text ->
        text.lines().withIndex().filter { it.value.trim() in reasons }
            .joinToString(",", "[", "]") { (i, l) -> "[${i + 1},\"${reasons.getValue(l.trim()).replace("\"", "\\\"")}\"]" }
    }

    /**
     * A line neither layer can use is shown with the DNS matcher's reason, not handed to the page
     * filter, which would drop it without a word. Lines the page filter takes still go there.
     */
    @Test
    fun malformedLinesAreShownNotSentToPages() {
        val reasons = linkedMapOf(
            "||bad host^" to "pattern is not hostname-level",
            "/[unclosed/" to "invalid regular expression",
            "||x.test^\$badfilter" to "\$badfilter only works inside lists: use @@||host^ to allow a host",
            "||ex ample.test/x" to "pattern is not hostname-level",
            "||bad,host.test/x" to "pattern is not hostname-level",
            "||x.test/ads/" to "pattern is not hostname-level",
            "||x.test^\$script" to "modifier not applicable at DNS level",
            "/banner\\d+/" to "regex needs a URL, not a hostname",
            "|https://x.test/ad.js" to "pattern is not hostname-level",
            "@@||x.test/ok/" to "pattern is not hostname-level",
            "plainword" to "pattern is not hostname-level",
        )
        val lines = listOf("! mine", "||ok.test^", "##.ad") + reasons.keys
        val a = UserRules.analyze(lines.joinToString("\n"), matcher(reasons))
        fun layer(line: String) = a.layers[lines.indexOf(line) + 1]
        assertNull(layer("! mine"))
        assertEquals(Layer.DNS, layer("||ok.test^"))
        assertEquals(Layer.CHROME, layer("##.ad"))
        for (l in listOf("||x.test/ads/", "||x.test^\$script", "/banner\\d+/", "|https://x.test/ad.js", "@@||x.test/ok/")) {
            assertEquals(l, Layer.CHROME, layer(l))
        }
        val invalid = listOf("||bad host^", "/[unclosed/", "||x.test^\$badfilter", "||ex ample.test/x", "||bad,host.test/x", "plainword")
        for (l in invalid) assertEquals(l, Layer.INVALID, layer(l))
        assertEquals(invalid.map { lines.indexOf(it) + 1 to reasons.getValue(it) }, a.errors)
        // Only the page filter's lines reach it.
        assertEquals("##.ad\n||x.test/ads/\n||x.test^\$script\n/banner\\d+/\n|https://x.test/ad.js\n@@||x.test/ok/", UserRules.webLines(lines.joinToString("\n"), a))
    }

    /** The Sites screen: the host a person typed or pasted, and the whole-site rules it lists and sets. */
    @Test
    fun siteHelpers() {
        assertEquals("www.site.example", UserRules.siteHost(" https://WWW.Site.example:443/x?y#z "))
        assertEquals("site.example", UserRules.siteHost("*.site.example."))
        assertNull(UserRules.siteHost("localhost"))
        assertNull(UserRules.siteHost("a..example"))
        assertNull(UserRules.siteHost("bad host.example"))
        val text = "! mine\n||a.example^\n@@||b.example^\n||a.example/path\n@@||B.example^\n"
        assertEquals(UserRules.Sites(allowed = listOf("b.example"), blocked = listOf("a.example")), UserRules.sites(text))
        // Setting a site replaces its rule; other lines, comments and blank lines stay.
        assertEquals("! mine\n\n||c.example^\n@@||a.example^", UserRules.withSite("! mine\n||a.example^\n\n||c.example^\n", "a.example", true))
        assertEquals("||c.example^\n||a.example^", UserRules.withSite("@@||a.example^\n||c.example^", "a.example", false))
        assertEquals("||c.example^", UserRules.withSite("@@||a.example^\n||c.example^", "a.example", null))
    }
}
