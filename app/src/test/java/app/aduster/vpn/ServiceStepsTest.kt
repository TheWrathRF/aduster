package app.aduster.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStepsTest {
    /**
     * A settings change whose new tunnel cannot be made stops protection: the old tunnel still
     * names the proxy that was just stopped or restarted, so every page would fail under
     * "Protected". An unchanged tunnel stays; a new one replaces it.
     */
    @Test
    fun aTunnelThatCannotBeRebuiltStopsProtection() {
        assertEquals(TunnelStep.STOP, tunnelStep(changed = true, built = false))
        assertEquals(TunnelStep.REPLACE, tunnelStep(changed = true, built = true))
        assertEquals(TunnelStep.KEEP, tunnelStep(changed = false, built = false))
    }

    /** Only the engine's own "ok" counts as a loaded DNS table; an error or a broken answer asks for a rebuild. */
    @Test
    fun aDnsTableLoadIsReadFromItsAnswer() {
        assertTrue(dnsTableLoaded("""{"ok":true,"list_rules":12,"user_rules":0,"skipped":0}"""))
        assertFalse(dnsTableLoaded("""{"ok":false,"error":"unsupported version"}"""))
        assertFalse(dnsTableLoaded("""{"ok":false,"error":"panic"}"""))
        assertFalse(dnsTableLoaded("not json"))
        assertFalse(dnsTableLoaded("{}"))
    }
}
