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
