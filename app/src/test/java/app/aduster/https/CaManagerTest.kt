package app.aduster.https

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/** The certificate: the trust check, what it is remembered for, and its files. */
class CaManagerTest {
    /**
     * An error reading the store keeps the last known answer: read as "not trusted", it would
     * stop HTTPS filtering until the next check, up to 30 minutes.
     */
    @Test
    fun storeErrorKeepsTheLastKnownState() {
        val busy: () -> Boolean = { throw KeyStoreException("store busy") }
        assertTrue(CaManager.trustState("AA:01") { true })
        assertTrue("an error after trusted", CaManager.trustState("AA:01", busy))
        assertFalse(CaManager.trustState("AA:01") { false })
        assertFalse("an error after not trusted", CaManager.trustState("AA:01", busy))
    }

    /** A remembered "trusted" is not carried over to a new certificate when the store fails to answer for it. */
    @Test
    fun aStoreErrorForANewCertificateIsNotTrusted() {
        val busy: () -> Boolean = { throw KeyStoreException("store busy") }
        assertTrue(CaManager.trustState("AA:01") { true })
        assertFalse("a new certificate", CaManager.trustState("BB:02", busy))
        assertTrue("the old one again", CaManager.trustState("AA:01") { true })
    }

    /** Two processes copying the same file at once each write their own temporary file; the result is whole. */
    @Test
    fun concurrentCopiesLeaveOneWholeFile() {
        val dir = java.nio.file.Files.createTempDirectory("copy-test").toFile()
        try {
            val f = java.io.File(dir, "res.json")
            val body = ByteArray(2_000_000) { (it % 251).toByte() }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            val jobs = (1..16).map { pool.submit { app.aduster.filters.FilterStore.copyWhole({ java.io.ByteArrayInputStream(body) }, f) } }
            jobs.forEach { it.get() }
            pool.shutdown()
            assertTrue(f.readBytes().contentEquals(body))
            assertEquals(listOf("res.json"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A move that fails is an error unless the file there already holds the new bytes (a copy
     * that won the race): the old version's file left in place is not taken for the new one.
     */
    @Test
    fun aFailedMoveOverAnOldFileIsAnError() {
        val dir = java.nio.file.Files.createTempDirectory("copy-fail").toFile()
        try {
            val f = java.io.File(dir, "res.json")
            f.writeText("old")
            val refuse = { _: java.nio.file.Path, _: java.nio.file.Path -> throw java.io.IOException("refused") }
            val thrown = runCatching { app.aduster.filters.FilterStore.copyWhole({ "new".byteInputStream() }, f, refuse) }.exceptionOrNull()
            assertTrue("an old file kept silently", thrown is java.io.IOException)
            assertEquals("old", f.readText())
            f.writeText("new")
            app.aduster.filters.FilterStore.copyWhole({ "new".byteInputStream() }, f, refuse)
            assertEquals(listOf("res.json"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A remembered "trusted" holds for the certificate it was read for: a new certificate (made
     * after the old key became unreadable, not installed yet) is looked up in the store again,
     * so page filtering never starts with a certificate the browsers do not trust.
     */
    @Test
    fun trustIsRememberedPerCertificate() {
        assertNull(CaManager.rememberedTrust(null, "AA:01"))
        assertEquals(true, CaManager.rememberedTrust("AA:01" to true, "AA:01"))
        assertEquals(false, CaManager.rememberedTrust("AA:01" to false, "AA:01"))
        assertNull("another certificate", CaManager.rememberedTrust("AA:01" to true, "BB:02"))
        assertNull("no certificate", CaManager.rememberedTrust("AA:01" to true, null))
    }

    /** A certificate whose key cannot be read, or whose key is not its own, offers a new one. */
    @Test
    fun aBrokenPairOffersANewCertificate() {
        assertFalse("nothing made yet", CaManager.needsNew(certExists = false, ca = null))
        assertTrue("the key cannot be read", CaManager.needsNew(certExists = true, ca = null))
        val cut = CaManager.Ca("a certificate cut off mid-write", "a key")
        assertTrue("a pair that does not match", CaManager.needsNew(certExists = true, ca = cut))
    }

    /** A file is replaced whole or not at all: a kill mid-write leaves the old one. */
    @Test
    fun filesAreReplacedWhole() {
        val dir = java.nio.file.Files.createTempDirectory("ca-test").toFile()
        try {
            val f = java.io.File(dir, "ca.pem")
            f.writeText("old")
            CaManager.writeWhole(f, "new".encodeToByteArray())
            assertEquals("new", f.readText())
            assertEquals(listOf("ca.pem"), dir.list()!!.toList())
        } finally {
            dir.deleteRecursively()
        }
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
