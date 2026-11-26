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
        assertEquals(TunnelStep.STOP, planTunnel("new", { "old" }) { null }.first)
        assertEquals(TunnelStep.KEEP, planTunnel("same", { "same" }) { error("built an unchanged tunnel") }.first)
    }

    /**
     * Building the tunnel records it as running (`buildTun` sets the signature): a new tunnel is
     * still put to use, not taken for the unchanged one (the engine stayed on the old
     * descriptor and the new one leaked).
     */
    @Test
    fun aRebuiltTunnelReplacesTheOldOne() {
        var running: String? = "old"
        val (step, tun) = planTunnel("new", { running }) { running = "new"; "tun" }
        assertEquals(TunnelStep.REPLACE, step)
        assertEquals("tun", tun)
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
