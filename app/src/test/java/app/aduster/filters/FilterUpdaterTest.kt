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
                // Counted as done before the answer leaves: the client may start its next request
                // as soon as it has the answer, before this thread would reach a later decrement
                // (a slow CI runner failed this check that way).
                active.decrementAndGet()
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
            assertEquals("12 checks of 300 ms took $ms ms", true, ms < 3_000)
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
    private class FakeWeb(var dnsDigest: String, var userCandidates: Int = 0) : FilterUpdater.WebNative {
        var digests = 0
        var compiles = 0
        val compiledOuts = mutableListOf<String>()
        val compiledSpecs = mutableListOf<String>()
        var failLists: String? = null
        var failUser: String? = null
        override fun compile(spec: String, out: String, dns: String): String {
            compiles++
            compiledOuts += out
            compiledSpecs += spec
            val isUser = out.endsWith("web-user.dat")
            val err = if (isUser) failUser else failLists
            if (err != null) return """{"ok":false,"error":"$err"}"""
            val rules = if (isUser) 2 else 5
            val candidates = if (isUser) userCandidates else 1
            java.io.File(out).writeText("engine $compiles")
            java.io.File(out.removeSuffix(".dat") + ".extra").writeText("extra $compiles")
            java.io.File(out.removeSuffix(".dat") + ".count").writeText("""{"rules":$rules}""")
            return """{"ok":true,"rules":$rules,"lists":[],"dns_digest":"$dnsDigest","exception_candidates":$candidates}"""
        }
        override fun digest(dat: String, dns: String): String {
            digests++
            return dnsDigest
        }
    }

    /**
     * User page rules compile into their own pair without rebuilding the lists' engine; list
     * changes rebuild only the lists' engine; removing user rules deletes the user pair and
     * signals a reload; DNS changes rebuild the user pair only when it has exception candidates.
     */
    @Test
    fun userRulesCompileIndependentlyOfLists() {
        val tmp = Files.createTempDirectory("aduster-user-web").toFile()
        try {
            val store = FilterStore(tmp)
            val l = FilterList("w", "Web", "https://lists.test/w.txt", true, Group.WEB, true, "")
            store.raw("w").writeText("##.ad\n")
            store.dnsTable.writeText("dns 1")
            val native = FakeWeb("d1", userCandidates = 0)
            val listCompiles = { native.compiledOuts.count { it.endsWith("web.dat") } }
            val userCompiles = { native.compiledOuts.count { it.endsWith("web-user.dat") } }
            val userDat = File(tmp, "web-user.dat")
            val userExtra = File(tmp, "web-user.extra")
            val userCount = File(tmp, "web-user.count")

            // Initial compile with lists only: only web.dat is built.
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals(1, listCompiles())
            assertEquals(0, userCompiles())
            assertEquals(false, userDat.exists())
            assertEquals(5, store.readMeta().webRules)

            // User adds a page rule: only the user pair compiles, lists are untouched.
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "news.test#@#.ad", false, native))
            assertEquals("lists must not recompile for a user rule", 1, listCompiles())
            assertEquals(1, userCompiles())
            assertEquals(true, userDat.exists() && userExtra.exists())
            assertEquals(true, native.compiledSpecs.last().contains("\"user\":true"))
            assertEquals(7, store.readMeta().webRules)

            // Nothing changed: neither compiles.
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "news.test#@#.ad", false, native))
            assertEquals(1, listCompiles())
            assertEquals(1, userCompiles())

            // DNS table changes while user rules have no host exceptions: user pair is not rebuilt.
            store.dnsTable.writeText("dns 22")
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "news.test#@#.ad", false, native))
            assertEquals(1, listCompiles())
            assertEquals(1, userCompiles())

            // User adds a host exception (depends on DNS): compiles user pair only; next DNS change
            // rebuilds the user pair too.
            native.userCandidates = 1
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "@@||ads.example^\$domain=news.test", false, native))
            assertEquals(1, listCompiles())
            assertEquals(2, userCompiles())
            store.dnsTable.writeText("dns 333")
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "@@||ads.example^\$domain=news.test", false, native))
            assertEquals(1, listCompiles())
            assertEquals(3, userCompiles())

            // List changes: only the lists' engine compiles, user pair is untouched.
            store.raw("w").writeText("##.ad\n##.banner\n")
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "@@||ads.example^\$domain=news.test", false, native))
            assertEquals(2, listCompiles())
            assertEquals(3, userCompiles())
            assertEquals(7, store.readMeta().webRules)

            // User rules removed: user pair deleted, lists untouched, returns true to reload :core.
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals(2, listCompiles())
            assertEquals(3, userCompiles())
            assertEquals(false, userDat.exists() || userExtra.exists() || userCount.exists())
            assertEquals(5, store.readMeta().webRules)
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))

            // Lists empty, user rules present: empty lists engine + user pair; removing user rules
            // deletes everything.
            assertEquals(true, FilterUpdater.compileWebFiles(store, emptyList(), "site.test##.mine", false, native))
            assertEquals(3, listCompiles())
            assertEquals(4, userCompiles())
            assertEquals(true, store.webTable.exists() && userDat.exists())
            assertEquals(true, FilterUpdater.compileWebFiles(store, emptyList(), "", false, native))
            assertEquals(false, store.webTable.exists() || store.webExtra.exists() || userDat.exists() || userExtra.exists())
            assertEquals(0, store.readMeta().webRules)

            // Leftover user temp files are cleaned up.
            val tmps = listOf("web-user.tmp", "web-user.extra.tmp", "web-user.count.tmp").map { File(tmp, it).apply { writeText("x") } }
            store.removeLeftovers()
            assertEquals(true, tmps.none { it.exists() })
        } finally {
            tmp.deleteRecursively()
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
            // The engine file replaced on its own (a compile cut short between its renames): the
            // digest is asked again, and the engine says the pair does not match.
            store.webTable.writeText("the engine of another compile")
            native.dnsDigest = "mismatch"
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals(3, native.digests)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * What a download stored about a list holds only for that file: one replaced by a file of the
     * same size (a second update, or one cut short before it saved what it found) is read again,
     * so its own includes are fetched.
     */
    @Test
    fun aStoredFileReplacedWithTheSameSizeIsReadAgain() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val body = "! Title: l\n!#include pa.txt\n||x.test^\n"
        server.createContext("/") { ex ->
            val name = ex.requestURI.path.removePrefix("/")
            val text = if (name == "l.txt") body else "||$name.test^\n"
            val tag = "\"${text.hashCode()}\""
            ex.responseHeaders.add("ETag", tag)
            if (ex.requestHeaders.getFirst("If-None-Match") == tag) {
                ex.sendResponseHeaders(304, -1)
            } else {
                val b = text.toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            ex.close()
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-same-size").toFile()
        try {
            val store = FilterStore(tmp)
            val l = FilterList("l", "L", "http://127.0.0.1:${server.address.port}/l.txt", false, Group.ADS, true, "")
            FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL)
            val raw = store.raw("l")
            val stamp = raw.lastModified()
            raw.writeText(body.replace("pa.txt", "pb.txt"))
            raw.setLastModified(stamp + 2_000)
            val r = FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL).single()
            assertEquals(listOf("pb.txt"), (r.fetch as FilterUpdater.Fetch.NotModified).scan?.includes)
            assertEquals(true, java.io.File(store.includes("l"), "pb.txt").exists())
        } finally {
            server.stop(0)
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
        // Lines ended by a bare CR (as the line reader split them), a `!` line too long to be an
        // include, and an include on the last line without a newline.
        val odd = "! Title\r||a.test^\r||b.test^\r!#include cr.txt\r!#include foo.txt" + " ".repeat(500) + "junk\n" +
            "!#include " + "a".repeat(505) + "\n! " + "x".repeat(5000) + "\n!#include last.txt"
        val s2 = ListScanner()
        odd.toByteArray().let { s2.feed(it, it.size) }
        val scan2 = s2.finish()
        assertEquals(old(odd), scan2.lines)
        val reader = odd.lines().mapNotNull { Regex("""^!#include\s+([A-Za-z0-9][A-Za-z0-9._-]*)\s*$""").matchEntire(it.trim())?.groupValues?.get(1) }
        assertEquals(reader, scan2.includes)
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
        val oneMissing = FilterUpdater.Result(0, 25, listOf("HaGeZi Threat Intelligence"), false, false, checked = 1, unreachable = 1)
        assertEquals(true, FilterJobs.retryAfter(oneMissing))
        // Held back because it shrank: not asked again 30 s later (the same copy again would
        // pass the shrink guard); the next scheduled update decides.
        assertEquals(false, FilterJobs.retryAfter(oneMissing.copy(unreachable = 0)))
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

    /**
     * Settings changed while an update's downloads are in flight (a user page rule saved and
     * compiled by its own compile job, or a DNS setting toggled) are kept when the update takes
     * the lock to compile, never overwritten by the older snapshot taken before downloading.
     */
    @Test
    fun settingsChangedWhileUpdateFetchIsInFlightAreKept() {
        val fetchesStarted = java.util.concurrent.CountDownLatch(2)
        val allowResponse = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        server.createContext("/w.txt") { ex ->
            fetchesStarted.countDown()
            allowResponse.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val b = "##.ad\n##.banner\n".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
            ex.close()
        }
        server.createContext("/d.txt") { ex ->
            fetchesStarted.countDown()
            allowResponse.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val b = "||ads.test^\n".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
            ex.close()
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-update-race").toFile()
        try {
            val store = FilterStore(tmp)
            val base = "http://127.0.0.1:${server.address.port}"
            val d = FilterList("custom-00000001", "DNS", "$base/d.txt", false, Group.CUSTOM, true, "")
            val w = FilterList("custom-00000002", "Web", "$base/w.txt", true, Group.CUSTOM, true, "")
            store.raw(w.id).writeText("##.ad\n")
            store.dnsTable.writeText("dns 1")
            val native = FakeWeb("d1")
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(w), "", false, native))

            val customLists = listOf(
                app.aduster.settings.CustomList(d.id, d.name, d.url, web = false),
                app.aduster.settings.CustomList(w.id, w.name, w.url, web = true),
            )
            val current = java.util.concurrent.atomic.AtomicReference(
                app.aduster.settings.Settings(httpsFiltering = true, userRules = "", customLists = customLists),
            )
            val dnsSettingsSeen = java.util.Collections.synchronizedList(mutableListOf<app.aduster.settings.Settings>())
            val workerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val worker = Thread {
                try {
                    FilterUpdater.updateStore(
                        store = store,
                        readSettings = { current.get() },
                        check = FilterUpdater.Check.ALL,
                        webWanted = { it.httpsFiltering },
                        compileDns = { s -> dnsSettingsSeen += s; false },
                        compileWeb = { s -> FilterUpdater.compileWebFiles(store, listOf(w), s.userRules, false, native) },
                        dnsListsOf = { listOf(d) },
                        webListsOf = { listOf(w) },
                    )
                } catch (t: Throwable) {
                    workerError.set(t)
                }
            }
            worker.start()
            assertEquals(true, fetchesStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // While the update's fetches are in flight, the user saves a page rule (compiled by
            // its own job) and toggles a DNS setting.
            current.set(current.get().copy(userRules = "site.test##.mine", blockDnsBypass = false))
            store.locked {
                assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(w), current.get().userRules, false, native))
            }
            val userDatBefore = store.webUserTable.readText()
            val userCompilesBefore = native.compiledOuts.count { it.endsWith("web-user.dat") }

            allowResponse.countDown()
            worker.join(10_000)
            assertNull("update worker must not fail", workerError.get())

            assertEquals("DNS compile must see updated settings", listOf(false), dnsSettingsSeen.map { it.blockDnsBypass })
            assertEquals("newer user pair must stay on disk", true, store.webUserTable.exists() && store.webUserExtra.exists())
            assertEquals("newer user pair must not be overwritten", userDatBefore, store.webUserTable.readText())
            assertEquals("user pair already matched current settings", userCompilesBefore, native.compiledOuts.count { it.endsWith("web-user.dat") })
            assertEquals("site.test##.mine", File(store.dir, "user-web.txt").readText())
        } finally {
            allowResponse.countDown()
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    /**
     * A failed page-rule compile records `webError`, and reverting the failed change clears it
     * even when both pairs are already up to date or no engine files exist on disk yet.
     */
    @Test
    fun revertingAFailedChangeClearsStaleWebError() {
        val tmp = Files.createTempDirectory("aduster-weberr").toFile()
        try {
            val store = FilterStore(tmp)
            val l = FilterList("w", "Web", "https://lists.test/w.txt", true, Group.WEB, true, "")
            store.raw("w").writeText("##.ad\n")
            store.dnsTable.writeText("dns 1")
            val native = FakeWeb("d1")

            // 1. Both pairs built cleanly, then a user rule edit fails once, then is reverted.
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "a.test##.ad", false, native))
            assertNull(store.readMeta().webError)
            native.failUser = "disk full"
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "b.test##.bad", false, native))
            assertEquals("disk full", store.readMeta().webError)
            native.failUser = null
            // Reverting back to "a.test##.ad": both pairs are already up to date, webError must clear.
            assertEquals(false, FilterUpdater.compileWebFiles(store, listOf(l), "a.test##.ad", false, native))
            assertNull("stale webError must clear when both pairs are up to date", store.readMeta().webError)

            // 2. Nothing exists on disk yet: first compile fails, then the user clears the rule.
            val emptyStore = FilterStore(File(tmp, "empty"))
            native.failLists = "disk full"
            assertEquals(false, FilterUpdater.compileWebFiles(emptyStore, emptyList(), "b.test##.bad", false, native))
            assertEquals("disk full", emptyStore.readMeta().webError)
            native.failLists = null
            assertEquals(false, FilterUpdater.compileWebFiles(emptyStore, emptyList(), "", false, native))
            assertNull("stale webError must clear when no files exist yet", emptyStore.readMeta().webError)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * When the lists' compile fails while a valid current-format lists' engine is already on disk,
     * the lists' error is recorded and the user pair can still be edited or deleted; a user pair
     * is never built against a missing, mismatched or older-format lists' engine.
     */
    @Test
    fun failingListsCompileDoesNotBlockUserPairWhenValidListsEngineExists() {
        val tmp = Files.createTempDirectory("aduster-lists-fail").toFile()
        try {
            val store = FilterStore(tmp)
            val l = FilterList("w", "Web", "https://lists.test/w.txt", true, Group.WEB, true, "")
            store.raw("w").writeText("##.ad\n")
            store.dnsTable.writeText("dns 1")
            val native = FakeWeb("d1")
            val userCompiles = { native.compiledOuts.count { it.endsWith("web-user.dat") } }

            // Build valid lists + user pair first.
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "broken.test##.ad", false, native))
            assertEquals(1, userCompiles())

            // List file changes, lists' compile fails: editing the user rule must still update the user pair.
            store.raw("w").writeText("##.ad\n##.more\n")
            native.failLists = "no space for lists"
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "fixed.test##.ad", false, native))
            assertEquals(2, userCompiles())
            assertEquals("no space for lists", store.readMeta().webError)
            assertEquals(true, store.webUserTable.exists())

            // Deleting the user rule while lists still fail must remove the user pair and keep lists' error.
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals(false, store.webUserTable.exists() || store.webUserExtra.exists())
            assertEquals("no space for lists", store.readMeta().webError)

            // Missing lists' engine: user pair must NOT be built.
            val missing = FilterStore(File(tmp, "missing"))
            missing.raw("w").writeText("##.ad\n")
            val beforeMissing = userCompiles()
            assertEquals(false, FilterUpdater.compileWebFiles(missing, listOf(l), "mine.test##.ad", false, native))
            assertEquals(beforeMissing, userCompiles())
            assertEquals(false, missing.webUserTable.exists())
            assertEquals("no space for lists", missing.readMeta().webError)

            // Older-format lists' engine on disk: user pair must NOT be paired with it.
            val oldFmt = FilterStore(File(tmp, "oldfmt"))
            oldFmt.raw("w").writeText("##.ad\n")
            oldFmt.dnsTable.writeText("dns 1")
            native.failLists = null
            assertEquals(true, FilterUpdater.compileWebFiles(oldFmt, listOf(l), "", false, native))
            val m = oldFmt.readMeta()
            oldFmt.writeMeta(m.copy(webSignature = m.webSignature.replace(Regex("\\|format=\\d+\\|"), "|format=1|")))
            native.failLists = "no space for lists"
            val beforeOld = userCompiles()
            assertEquals(false, FilterUpdater.compileWebFiles(oldFmt, listOf(l), "mine.test##.ad", false, native))
            assertEquals(beforeOld, userCompiles())
            assertEquals(false, oldFmt.webUserTable.exists())
            assertEquals("no space for lists", oldFmt.readMeta().webError)

            // Mismatched lists' engine on disk (missing web.extra): user pair must NOT be paired with it.
            val broken = FilterStore(File(tmp, "broken"))
            broken.raw("w").writeText("##.ad\n")
            broken.dnsTable.writeText("dns 1")
            native.failLists = null
            assertEquals(true, FilterUpdater.compileWebFiles(broken, listOf(l), "", false, native))
            broken.webExtra.delete()
            broken.raw("w").writeText("##.ad\n##.more\n")
            native.failLists = "no space for lists"
            val beforeBroken = userCompiles()
            assertEquals(false, FilterUpdater.compileWebFiles(broken, listOf(l), "mine.test##.ad", false, native))
            assertEquals(beforeBroken, userCompiles())
            assertEquals(false, broken.webUserTable.exists())
            assertEquals("no space for lists", broken.readMeta().webError)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * A half-written user pair (e.g. kill between renaming `web-user.dat` and `web-user.extra`)
     * makes `needsCheck` return true so app open schedules a compile that rebuilds only the user pair.
     */
    @Test
    fun halfWrittenUserPairTriggersNeedsCheckAndRebuildsUserOnly() {
        val tmp = Files.createTempDirectory("aduster-half-user").toFile()
        try {
            val store = FilterStore(tmp)
            val l = FilterList("w", "Web", "https://lists.test/w.txt", true, Group.WEB, true, "")
            store.raw("w").writeText("##.ad\n")
            store.dnsTable.writeText("dns 1")
            val native = FakeWeb("d1")
            val listCompiles = { native.compiledOuts.count { it.endsWith("web.dat") } }
            val userCompiles = { native.compiledOuts.count { it.endsWith("web-user.dat") } }

            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "site.test##.ad", false, native))
            store.writeMeta(store.readMeta().copy(appVersion = 42L))
            assertEquals("clean state needs no check", false, FilterUpdater.needsCheck(store, 42L))

            // Simulate a kill between renaming web-user.dat and web-user.extra.
            store.webUserTable.writeText("replaced user dat from interrupted compile")
            assertEquals("mismatched user pair must need check", true, FilterUpdater.needsCheck(store, 42L))

            // Scheduled compile rebuilds only the user pair, leaving lists untouched.
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "site.test##.ad", false, native))
            assertEquals("lists must not recompile", 1, listCompiles())
            assertEquals("user pair must rebuild", 2, userCompiles())
            assertEquals("rebuilt pair is clean again", false, FilterUpdater.needsCheck(store, 42L))

            // Missing web-user.extra also triggers needsCheck.
            store.webUserExtra.delete()
            assertEquals(true, FilterUpdater.needsCheck(store, 42L))
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "site.test##.ad", false, native))
            assertEquals(false, FilterUpdater.needsCheck(store, 42L))

            // Removing user rules cleanly leaves needsCheck false.
            assertEquals(true, FilterUpdater.compileWebFiles(store, listOf(l), "", false, native))
            assertEquals(false, FilterUpdater.needsCheck(store, 42L))

            // Half-written first user pair (no webUserFiles recorded yet, stray web-user.dat left behind).
            store.webUserTable.writeText("stray first compile")
            assertEquals(true, FilterUpdater.needsCheck(store, 42L))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun dnsRulesApplyWhilePageListsStillDownload() {
        val webGate = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        server.createContext("/") { ex ->
            try {
                val page = ex.requestURI.path == "/w.txt"
                if (page) webGate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                val b = (if (page) "##.ad" else "||ads.test^").toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            } finally {
                ex.close()
            }
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-dns-first").toFile()
        try {
            val store = FilterStore(tmp)
            val base = "http://127.0.0.1:${server.address.port}"
            val d = FilterList("custom-00000001", "DNS", "$base/d.txt", false, Group.CUSTOM, true, "")
            val w = FilterList("custom-00000002", "Web", "$base/w.txt", true, Group.CUSTOM, true, "")
            val settings = app.aduster.settings.Settings(httpsFiltering = true, userRules = "")
            val dnsApplied = java.util.concurrent.CountDownLatch(1)
            val phases = java.util.Collections.synchronizedList(mutableListOf<UpdatePhase>())
            val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val worker = Thread {
                try {
                    FilterUpdater.updateStore(
                        store = store,
                        readSettings = { settings },
                        check = FilterUpdater.Check.ALL,
                        webWanted = { it.httpsFiltering },
                        compileDns = { true },
                        compileWeb = { false },
                        dnsListsOf = { listOf(d) },
                        webListsOf = { listOf(w) },
                        onDns = { dnsApplied.countDown() },
                        progress = { phases += it.phase },
                    )
                } catch (t: Throwable) {
                    error.set(t)
                }
            }
            worker.start()
            // The page list is still held by the server: the new DNS rules must not wait for it.
            assertEquals("DNS rules waited for the page lists", true, dnsApplied.await(5, java.util.concurrent.TimeUnit.SECONDS))
            webGate.countDown()
            worker.join(10_000)
            assertNull(error.get())
            assertEquals(UpdatePhase.BUILDING_PAGES, phases.last())
            assertEquals(phases.sortedBy { it.ordinal }, phases.toList())
        } finally {
            webGate.countDown()
            server.stop(0)
            tmp.deleteRecursively()
        }
    }
}
