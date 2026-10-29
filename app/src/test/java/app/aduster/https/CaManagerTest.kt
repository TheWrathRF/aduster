package app.aduster.https

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/** The trust check's answer when the system's CA store fails for a moment. */
class CaManagerTest {
    /**
     * An error reading the store keeps the last known answer: read as "not trusted", it would
     * stop HTTPS filtering until the next check, up to 30 minutes.
     */
    @Test
    fun storeErrorKeepsTheLastKnownState() {
        val busy: () -> Boolean = { throw KeyStoreException("store busy") }
        assertTrue(CaManager.trustState { true })
        assertTrue("an error after trusted", CaManager.trustState(busy))
        assertFalse(CaManager.trustState { false })
        assertFalse("an error after not trusted", CaManager.trustState(busy))
    }

    /** The whole SHA-256, as Android's certificate details show it, so the two can be compared. */
    @Test
    fun fingerprintIsTheWholeSha256() {
        val f = CaManager.fingerprintOf("abc".encodeToByteArray())
        assertEquals(32, f.split(':').size)
        // SHA-256 of "abc" (FIPS 180-2's example), in pairs.
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(sha.uppercase().chunked(2).joinToString(":"), f)
    }
}
