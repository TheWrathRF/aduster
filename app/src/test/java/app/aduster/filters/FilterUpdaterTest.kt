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
        val whole = FilterUpdater.nextMeta(failed, FilterUpdater.Fetch.Updated("e1", null, 10, FileScan(10, "", 1)), null, url, now = 3_000L)
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
     * Lists are checked several at a time, at most 3 per host; a list that answers 304 is not
     * read again (its rule count and includes come from what its download stored), nor are its
     * parts. 12 lists at 300 ms each: 3.6 s one at a time, ~1.2 s three at a time.
     */
    @Test
    fun listsAreCheckedInParallelAndA304ReadsNothing() {
        val active = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(16)
        server.createContext("/") { ex ->
            val n = active.incrementAndGet()
            most.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(300)
                val name = ex.requestURI.path.removePrefix("/")
                val body = (if (name == "l0.txt") "! Title: zero\n!#include part.txt\n" else "") + (1..50).joinToString("\n") { "||$name-$it.test^" }
                val tag = "\"${body.hashCode()}\""
                ex.responseHeaders.add("ETag", tag)
                if (ex.requestHeaders.getFirst("If-None-Match") == tag) {
                    ex.sendResponseHeaders(304, -1)
                } else {
                    val b = body.toByteArray()
                    ex.sendResponseHeaders(200, b.size.toLong())
                    ex.responseBody.use { it.write(b) }
                }
            } finally {
                active.decrementAndGet()
                ex.close()
            }
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-par").toFile()
        try {
            val store = FilterStore(tmp)
            val base = "http://127.0.0.1:${server.address.port}"
            val lists = (0 until 12).map { FilterList("l$it", "List $it", "$base/l$it.txt", false, Group.ADS, true, "") }
            val first = FilterUpdater.checkAll(store, lists, FilterUpdater.Check.ALL)
            assertEquals(12, first.count { it.fetch is FilterUpdater.Fetch.Updated })
            assertEquals(listOf("part.txt"), store.readMeta().lists["l0"]?.scan?.includes)
            assertEquals(50, store.readMeta().lists["l1"]?.scan?.lines)
            assertEquals(true, java.io.File(store.includes("l0"), "part.txt").exists())
            most.set(0)
            val t = System.nanoTime()
            val second = FilterUpdater.checkAll(store, lists, FilterUpdater.Check.ALL)
            val ms = (System.nanoTime() - t) / 1_000_000
            // Nothing read from disk: no list needed its stored file scanned.
            assertEquals(12, second.count { it.fetch == FilterUpdater.Fetch.NotModified(null) })
            assertEquals(false, second.single { it.list.id == "l0" }.inc?.changed)
            assertEquals(true, most.get() in 2..3)
            assertEquals("12 checks of 300 ms took $ms ms", true, ms < 2_400)
            // Checked an hour ago or less: an ordinary update leaves them; a list switched on
            // fetches only what is missing.
            assertEquals(0, FilterUpdater.checkAll(store, lists, FilterUpdater.Check.STALE).size)
            store.raw("l5").delete()
            assertEquals(listOf("l5"), FilterUpdater.checkAll(store, lists, FilterUpdater.Check.MISSING).map { it.list.id })
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    /** A page compiler stand-in: writes the engine's pair and says which DNS digest it built against. */
    private class FakeWeb(var dnsDigest: String) : FilterUpdater.WebNative {
        var digests = 0
        var compiles = 0
        override fun compile(spec: String, out: String, dns: String): String {
            compiles++
            java.io.File(out).writeText("engine $compiles")
            java.io.File(out.removeSuffix(".dat") + ".extra").writeText("extra $compiles")
            return """{"ok":true,"rules":5,"lists":[],"dns_digest":"$dnsDigest"}"""
        }
        override fun digest(dat: String, dns: String): String {
            digests++
            return dnsDigest
        }
    }

    /**
     * The page engine's DNS digest (~2 s of CPU on a phone) is worked out only when the page
     * lists did not change but the DNS table did; a compile hands it back itself.
     */
    @Test
    fun thePageCompileWorksOutTheDnsDigestOnlyWhenItDecides() {
        val tmp = Files.createTempDirectory("aduster-web").toFile()
        try {
            val store = FilterStore(tmp)
            val l = FilterList("w", "Web", "https://lists.test/w.txt", true, Group.WEB, true, "")
            store.raw("w").writeText("##.ad\n")
            store.dnsTable.writeText("dns 1")
            val native = FakeWeb("d1")
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals("first compile", 0, native.digests)
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals("nothing changed", 0, native.digests)
            // A DNS update that leaves the digest alone: one digest, no compile.
            store.dnsTable.writeText("dns 22")
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals(1, native.digests)
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals("its digest is kept", 1, native.digests)
            // One that changes it: the compile's own digest is kept, not worked out again.
            native.dnsDigest = "d2"
            store.dnsTable.writeText("dns 333")
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals(2, native.digests)
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            // A page list changed: compiled without a digest of its own.
            store.raw("w").writeText("##.ad\n##.banner\n")
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals(2, native.digests)
            assertEquals(3, native.compiles)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** The one-pass scan counts rule lines and finds includes as reading the file line by line did. */
    @Test
    fun theScanCountsAsTheLineReaderDid() {
        val text = "! Title: x\r\n[Adblock Plus 2.0]\n\n   \n# a comment\n#\n##.ad\n#@#.ad\n#?#.x:has(y)\n#\$#body{}\n#@\n" +
            "  ||a.test^\n!#include  sub.txt \n!#include ../bad.txt\n!#include other-part_1.txt\nexample.com##.banner\n﻿bom-line\nlast-no-newline"
        fun old(t: String) = t.lines().count { raw ->
            val l = raw.trim()
            l.isNotEmpty() && !l.startsWith("!") && !l.startsWith("[") && !(l.startsWith("#") && !l.startsWith("##") && !l.startsWith("#@#") && !l.startsWith("#?#"))
        }
        val s = ListScanner()
        val bytes = text.toByteArray()
        // Fed in uneven pieces, as a download arrives.
        var i = 0
        while (i < bytes.size) {
            val n = minOf(7, bytes.size - i)
            s.feed(bytes.copyOfRange(i, i + n), n)
            i += n
        }
        val scan = s.finish()
        assertEquals(old(text), scan.lines)
        assertEquals(listOf("sub.txt", "other-part_1.txt"), scan.includes)
        assertEquals(bytes.size.toLong(), scan.size)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(sha, scan.sha256)
    }

    /**
     * A list stored by an older version (no facts in meta.json) is read once on its first 304,
     * and its facts kept, so the next check reads nothing.
     */
    @Test
    fun aListFromAnOlderVersionIsReadOnce() {
        val s = PartServer()
        val tmp = Files.createTempDirectory("aduster-old").toFile()
        try {
            s.server.createContext("/list.txt") { ex ->
                ex.responseHeaders.add("ETag", "\"v1\"")
                if (ex.requestHeaders.getFirst("If-None-Match") == "\"v1\"") ex.sendResponseHeaders(304, -1) else ex.sendResponseHeaders(500, -1)
                ex.close()
            }
            val store = FilterStore(tmp)
            val l = FilterList("old", "Old", s.url, false, Group.ADS, true, "")
            store.raw("old").writeText("||a.test^\n||b.test^\n")
            store.writeMeta(FilterMeta(lists = mapOf("old" to ListMeta(etag = "\"v1\"", url = s.url))))
            val first = FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL).single().fetch
            assertEquals(2, (first as FilterUpdater.Fetch.NotModified).scan?.lines)
            assertEquals(2, store.readMeta().lists["old"]?.scan?.lines)
            assertEquals(FilterUpdater.Fetch.NotModified(null), FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL).single().fetch)
        } finally {
            s.close()
            tmp.deleteRecursively()
        }
    }

    /**
     * One settings change, one job: a list switched on that is not on disk yet is fetched by a
     * job that compiles at its end (no second compile job beside it); one already on disk, one
     * switched off or a changed profile only compiles; nothing to build, no job.
     */
    @Test
    fun aSettingsChangeSchedulesOneJob() {
        assertEquals(listOf(FilterJobs.ID_NOW), FilterJobs.forChange(compileChanged = true, fetch = true) { true })
        assertEquals(listOf(FilterJobs.ID_COMPILE), FilterJobs.forChange(compileChanged = true, fetch = true) { false })
        assertEquals(listOf(FilterJobs.ID_COMPILE), FilterJobs.forChange(compileChanged = true, fetch = false) { error("not asked") })
        // Page filtering switched on with its lists on disk: the page engine is built.
        assertEquals(listOf(FilterJobs.ID_COMPILE), FilterJobs.forChange(compileChanged = false, fetch = true) { false })
        assertEquals(emptyList<Int>(), FilterJobs.forChange(compileChanged = false, fetch = false) { error("not asked") })
    }

    /**
     * A list switched on whose download fails is tried again with the job's backoff: the lists
     * the job had no need to check do not count as having come through. A failure beside a list
     * that came through, or a full update that failed nowhere, is not retried.
     */
    @Test
    fun aFailedFetchOfAMissingListIsTriedAgain() {
        val oneMissing = FilterUpdater.Result(0, 25, listOf("HaGeZi Threat Intelligence"), false, false, checked = 1)
        assertEquals(true, FilterJobs.retryAfter(oneMissing))
        assertEquals(false, FilterJobs.retryAfter(oneMissing.copy(downloaded = 1, checked = 2)))
        assertEquals(false, FilterJobs.retryAfter(FilterUpdater.Result(3, 23, emptyList(), true, true, checked = 26)))
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
