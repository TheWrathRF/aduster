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

    /** A command that ends with protection off takes the card away, unless a start queued behind it needs it. */
    @Test
    fun aStopEndsTheCardUnlessAStartWaits() {
        assertTrue(serviceEnds(running = false, startsQueued = 0))
        assertFalse(serviceEnds(running = false, startsQueued = 1))
        assertFalse(serviceEnds(running = true, startsQueued = 0))
    }

    /**
     * A start that fails is still counted among the queued starts while it stops: only other
     * starts keep the card and the service (its own count kept the card up under a stopped
     * protection when a re-post came in meanwhile).
     */
    @Test
    fun aFailedStartEndsTheServiceUnlessAnotherStartWaits() {
        assertTrue(serviceEnds(running = false, startsQueued = 1, ownStart = true))
        assertFalse(serviceEnds(running = false, startsQueued = 2, ownStart = true))
    }

    /**
     * A command that comes while a start is queued keeps the service sticky (answered from the
     * running state alone, it left a protection that was starting unrestartable), and one while
     * protection runs too.
     */
    @Test
    fun commandsDuringAStartKeepTheServiceSticky() {
        assertTrue(staysSticky(running = false, startsQueued = 1))
        assertTrue(staysSticky(running = true, startsQueued = 0))
        assertFalse(staysSticky(running = false, startsQueued = 0))
    }

    /**
     * A timed pause read back after a restart of the process is anchored to the boot clock at
     * once, as the engine anchors what is left: a wall clock moved afterwards changes neither its
     * end nor when it is over (unanchored, the app ended it early or showed it an hour late).
     */
    @Test
    fun aRestoredPauseIsAnchoredToTheBootClock() {
        val (wall, boot) = 100_000_000L to 5_000_000L
        val end = wall + 14 * 60_000
        val deadline = pauseDeadline(end, wall, boot)
        assertEquals(boot + 14 * 60_000, deadline)
        // A minute later the wall clock was set back an hour, or forward an hour.
        assertEquals(wall + 60_000 - 3_600_000 + 13 * 60_000, realignedPauseEnd(end, deadline, wall + 60_000 - 3_600_000, boot + 60_000))
        assertEquals(wall + 60_000 + 3_600_000 + 13 * 60_000, realignedPauseEnd(end, deadline, wall + 60_000 + 3_600_000, boot + 60_000))
        assertEquals(0L, pauseDeadline(0, wall, boot))
        assertEquals(0L, pauseDeadline(Long.MAX_VALUE, wall, boot))
        assertEquals(boot, pauseDeadline(wall - 1, wall, boot))
    }

    /**
     * With one reading of the clocks, a pause not over always has an end to show: two readings at
     * a start let a pause that ran out between them stay "still paused" with no end, no alarm and
     * no refiltering.
     */
    @Test
    fun aPauseNotOverAtOneReadingHasAnEndToShow() {
        val (wall, boot) = 100_000_000L to 5_000_000L
        for (left in listOf(-1L, 0L, 1L, 60_000L)) {
            val end = wall + left
            val deadline = boot + left
            val over = pauseOverAt(end, deadline, wall, boot)
            assertEquals("left $left", left <= 0, over)
            assertTrue("left $left", over || realignedPauseEnd(end, deadline, wall, boot) > 0)
        }
    }

    /**
     * A timed pause over on the boot clock shows no end: "now" as its end read as still paused
     * by a second, later look at a wall clock set back meanwhile.
     */
    @Test
    fun aPauseOverOnTheBootClockShowsNoEnd() {
        assertEquals(0L, realignedPauseEnd(pausedUntil = 9_000, deadlineBoot = 1_000, nowWall = 5_000, nowBoot = 1_000))
        assertEquals(0L, realignedPauseEnd(pausedUntil = 9_000, deadlineBoot = 1_000, nowWall = 5_000, nowBoot = 2_000))
    }
}
