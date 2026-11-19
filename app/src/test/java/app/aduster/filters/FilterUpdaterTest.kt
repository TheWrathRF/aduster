package app.aduster.filters

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

/** List downloads against a local HTTP server, no Android runtime needed. */
class FilterUpdaterTest {
    /**
     * A part pulled in with `!#include` that its maintainer cut below half is held back once, and
     * the second identical download is accepted rather than refused on every update.
     */
    @Test
    fun shrunkIncludePartPassesOnTheSecondDownload() {
        var part = (1..2000).joinToString("\n") { "||ad$it.test^" }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/part.txt") { ex ->
            val body = part.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-inc").toFile()
        try {
            val raw = File(tmp, "list.txt").apply { writeText("! Title: test\n!#include part.txt\n") }
            val dir = File(tmp, "list.inc")
            val url = "http://127.0.0.1:${server.address.port}/list.txt"
            val lines = { File(dir, "part.txt").readLines().size }

            val first = FilterUpdater.fetchIncludes(raw, dir, url, emptyMap())
            assertNull(first.error)
            assertEquals(2000, lines())

            part = (1..900).joinToString("\n") { "||ad$it.test^" }
            val second = FilterUpdater.fetchIncludes(raw, dir, url, first.parts)
            assertNotNull("held back once", second.error)
            assertEquals(2000, lines())

            val third = FilterUpdater.fetchIncludes(raw, dir, url, second.parts)
            assertNull(third.error)
            assertEquals(900, lines())
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    /** A local server for one part whose body and status the test sets; the list including it. */
    private class PartServer {
        var body = (1..2000).joinToString("\n") { "||ad$it.test^" }
        var status = 200
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/part.txt") { ex ->
                val b = body.toByteArray()
                ex.sendResponseHeaders(status, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            start()
        }
        val dirRoot: File = Files.createTempDirectory("aduster-inc").toFile()
        val raw = File(dirRoot, "list.txt").apply { writeText("! Title: test\n!#include part.txt\n") }
        val dir = File(dirRoot, "list.inc")
        val url = "http://127.0.0.1:${server.address.port}/list.txt"
        fun lines() = File(dir, "part.txt").readLines().size
        fun close() {
            server.stop(0)
            dirRoot.deleteRecursively()
        }
    }

    /**
     * A list really cut by its maintainer that keeps changing between updates never comes twice
     * with the same size, and must not be held back for good. A second shrunk download within
     * 10 % of the held rule count passes; any one passes after 7 days of hold-backs.
     */
    @Test
    fun aShrunkListThatKeepsChangingPasses() {
        val s = PartServer()
        try {
            val first = FilterUpdater.fetchIncludes(s.raw, s.dir, s.url, emptyMap())
            s.body = (1..900).joinToString("\n") { "||ad$it.test^" }
            val held = FilterUpdater.fetchIncludes(s.raw, s.dir, s.url, first.parts)
            assertNotNull("held back once", held.error)
            s.body = (1..905).joinToString("\n") { "||ad$it.test^" }
            val next = FilterUpdater.fetchIncludes(s.raw, s.dir, s.url, held.parts)
            assertNull(next.error)
            assertEquals(905, s.lines())

            // Far from the held count: held again, until 7 days of hold-backs have passed.
            val s2 = PartServer()
            try {
                val a = FilterUpdater.fetchIncludes(s2.raw, s2.dir, s2.url, emptyMap())
                s2.body = (1..900).joinToString("\n") { "||ad$it.test^" }
                val h1 = FilterUpdater.fetchIncludes(s2.raw, s2.dir, s2.url, a.parts)
                s2.body = (1..400).joinToString("\n") { "||ad$it.test^" }
                val h2 = FilterUpdater.fetchIncludes(s2.raw, s2.dir, s2.url, h1.parts)
                assertNotNull("not near the held count", h2.error)
                assertEquals(2000, s2.lines())
                val late = FilterUpdater.fetchIncludes(s2.raw, s2.dir, s2.url, h2.parts, now = System.currentTimeMillis() + 8 * 86_400_000L)
                assertNull(late.error)
                assertEquals(400, s2.lines())
            } finally {
                s2.close()
            }
        } finally {
            s.close()
        }
    }

    /**
     * A failed download keeps a shrunk list's hold-back (its size, rule count and start), so the
     * 7-day limit does not start over after every failure; a whole download ends it.
     */
    @Test
    fun aFailedDownloadKeepsTheHoldBack() {
        val url = "https://lists.test/a.txt"
        val held = ListMeta(rules = 5000, url = url, heldBack = 1234, heldRules = 900, heldSince = 1_000L)
        val failed = FilterUpdater.nextMeta(held, FilterUpdater.Fetch.Failed("HTTP 500"), null, url, now = 2_000L)
        assertEquals(Triple(1234L, 900, 1_000L), Triple(failed.heldBack, failed.heldRules, failed.heldSince))
        assertEquals("HTTP 500", failed.error)
        assertEquals(2_000L, failed.checkedAt)
        val whole = FilterUpdater.nextMeta(failed, FilterUpdater.Fetch.Updated("e1", null, 10), null, url, now = 3_000L)
        assertEquals(Triple(0L, 0, 0L), Triple(whole.heldBack, whole.heldRules, whole.heldSince))
        assertNull(whole.error)
    }

    /**
     * Filters' header counts failed lists among those with a row of their own: the never-decrypt
     * lists and the lists that ride along with another have none.
     */
    @Test
    fun theHeaderCountsOnlyListsWithARow() {
        val s = app.aduster.settings.Settings(httpsFiltering = true)
        val rows = FilterCatalog.all(s).map { it.id }.toSet()
        val counted = FilterUpdater.downloaded(s, web = true)
        assertEquals(emptyList<String>(), counted.filter { it !in rows })
        assertEquals(true, counted.isNotEmpty())
    }

    /**
     * Without the part's metadata (a catalog URL change, an unreadable meta.json), a failed
     * download keeps the good copy, so the list does not compile without that part.
     */
    @Test
    fun aPartIsKeptWhenItsDownloadFailsWithoutMetadata() {
        val s = PartServer()
        try {
            FilterUpdater.fetchIncludes(s.raw, s.dir, s.url, emptyMap())
            s.status = 500
            val r = FilterUpdater.fetchIncludes(s.raw, s.dir, s.url, emptyMap())
            assertNotNull(r.error)
            assertEquals(2000, s.lines())
            assertNotNull(r.parts["part.txt"])
        } finally {
            s.close()
        }
    }
}
