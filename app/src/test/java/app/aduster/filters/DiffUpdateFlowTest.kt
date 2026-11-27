package app.aduster.filters

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A list that names a patch (`! Diff-Path:`) is updated by its small patches instead of a full
 * download; any patch that does not apply cleanly, or whose result fails the list's own
 * checksum, falls back to the full download, and a full download still comes every few days.
 */
class DiffUpdateFlowTest {
    private fun withSum(t: String) = t.replace("SUM", DiffUpdates.abpChecksum(t.replace("SUM", "x")))

    // Patches valid for a minute: p1 is due 50 s after the test's first check, p2 60 s after it.
    private val v1 = withSum("[Adblock Plus 2.0]\r\n! Checksum: SUM\r\n! Diff-Path: ../patches/p1-s-1799999990-60.patch\r\n! Title: T\r\n||a.example^\r\n||b.example^\r\n")
    private val v2 = withSum("[Adblock Plus 2.0]\r\n! Checksum: SUM\r\n! Diff-Path: ../patches/p2-s-1800000000-60.patch\r\n! Title: T\r\n||a.example^\r\n||b.example^\r\n||c.example^\r\n")
    private val v3 = withSum("[Adblock Plus 2.0]\r\n! Checksum: SUM\r\n! Diff-Path: ../patches/p9-s-99999999999-1.patch\r\n! Title: T\r\n||a.example^\r\n||d.example^\r\n")

    @Test
    fun aListIsUpdatedByItsPatches() {
        val hits = ConcurrentHashMap<String, AtomicInteger>()
        val bodies = ConcurrentHashMap<String, String>()
        bodies["/f/list.txt"] = v1
        val sum2 = v2.lines()[1].removePrefix("! Checksum: ").trim()
        bodies["/patches/p1-s-1799999990-60.patch"] = "d2 2\na3 2\n! Checksum: $sum2\r\n! Diff-Path: ../patches/p2-s-1800000000-60.patch\r\na6 1\n||c.example^\r\n"
        bodies["/patches/p2-s-1800000000-60.patch"] = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.getOrPut(ex.requestURI.path) { AtomicInteger() }.incrementAndGet()
            val b = bodies[ex.requestURI.path]?.toByteArray()
            if (b == null) ex.sendResponseHeaders(404, -1) else {
                ex.sendResponseHeaders(200, if (b.isEmpty()) -1 else b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            ex.close()
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-diff").toFile()
        fun hit(p: String) = hits[p]?.get() ?: 0
        try {
            val store = FilterStore(tmp)
            val l = FilterList("l", "L", "http://127.0.0.1:${server.address.port}/f/list.txt", true, Group.WEB, true, "")
            val raw = File(store.raw("l").path)
            val now = 1_800_000_000_000L
            // The first copy is a full download.
            assertEquals(true, FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now).single().fetch is FilterUpdater.Fetch.Updated)
            assertEquals(v1, raw.readText())
            // Then its patch: the list itself is not downloaded again; the next patch, not out yet, is asked once.
            val second = FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now + 60_000).single()
            assertEquals(true, second.fetch is FilterUpdater.Fetch.Updated)
            assertEquals(v2, raw.readText())
            assertEquals(1, hit("/f/list.txt"))
            assertEquals(1, hit("/patches/p1-s-1799999990-60.patch"))
            assertEquals(1, hit("/patches/p2-s-1800000000-60.patch"))
            assertEquals(3, store.readMeta().lists["l"]?.scan?.lines)
            // Nothing new: nothing changes, and still no full download.
            assertEquals(true, FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now + 120_000).single().fetch is FilterUpdater.Fetch.NotModified)
            assertEquals(1, hit("/f/list.txt"))
            // A patch whose result fails the list's checksum: the full list instead.
            bodies["/patches/p2-s-1800000000-60.patch"] = "a7 1\n||e.example^\r\n"
            bodies["/f/list.txt"] = v3
            assertEquals(true, FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now + 180_000).single().fetch is FilterUpdater.Fetch.Updated)
            assertEquals(v3, raw.readText())
            assertEquals(2, hit("/f/list.txt"))
            // Its next patch is not due: no request at all.
            val before = hits.values.sumOf { it.get() }
            assertEquals(true, FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now + 240_000).single().fetch is FilterUpdater.Fetch.NotModified)
            assertEquals(before, hits.values.sumOf { it.get() })
            // Days after the last full download, the list is downloaded in full again (a broken chain heals).
            FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now + 5L * 86_400_000)
            assertEquals(3, hit("/f/list.txt"))
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    /** The list as stored after the second check, the requests that check made, and the newest version. */
    private data class Chain(val text: String, val patchHits: Int, val listHits: Int, val latest: String)

    /**
     * A list first downloaded in full, then checked once more [hours] later (hourly patches),
     * when [behind] versions are out (each patch turns version k into k + 1; a version a quiet
     * list did not publish answers empty); [breakLast]: the last patch's result fails the list's
     * own checksum.
     */
    private fun chain(behind: Int, breakLast: Boolean = false, hours: Int = behind): Chain {
        val now = 1_800_000_000_000L
        val start = now / 1000 - hours * 3600L - 30
        fun version(k: Int) = withSum(
            "[Adblock Plus 2.0]\r\n! Checksum: SUM\r\n! Diff-Path: ../patches/p$k-s-${start + k * 3600L}-3600.patch\r\n! Title: T\r\n" +
                (0 until k).joinToString("") { "||r$it.example^\r\n" },
        )
        val bodies = ConcurrentHashMap<String, String>()
        val patchHits = AtomicInteger()
        val listHits = AtomicInteger()
        bodies["/f/list.txt"] = version(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            (if (ex.requestURI.path.startsWith("/patches/")) patchHits else listHits).incrementAndGet()
            val b = bodies[ex.requestURI.path]?.toByteArray()
            if (b == null) ex.sendResponseHeaders(404, -1) else {
                ex.sendResponseHeaders(200, if (b.isEmpty()) -1 else b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            ex.close()
        }
        server.start()
        val tmp = Files.createTempDirectory("aduster-diff").toFile()
        try {
            val store = FilterStore(tmp)
            val l = FilterList("l", "L", "http://127.0.0.1:${server.address.port}/f/list.txt", true, Group.WEB, true, "")
            FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now)
            for (k in 0 until behind) {
                val next = version(k + 1).lines()
                val sum = if (breakLast && k == behind - 1) "! Checksum: wrong" else next[1]
                bodies["/patches/p$k-s-${start + k * 3600L}-3600.patch"] = "d2 2\na3 2\n$sum\r\n${next[2]}\r\na${4 + k} 1\n||r$k.example^\r\n"
            }
            bodies["/patches/p$behind-s-${start + behind * 3600L}-3600.patch"] = ""
            bodies["/f/list.txt"] = version(behind)
            patchHits.set(0)
            listHits.set(0)
            FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now + 60_000)
            return Chain(File(store.raw("l").path).readText(), patchHits.get(), listHits.get(), version(behind))
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }

    /** A few versions behind: their patches, one request each, and no full download. */
    @Test
    fun aListAFewVersionsBehindTakesItsPatches() {
        val c = chain(behind = 3)
        assertEquals(c.latest, c.text)
        assertEquals(3, c.patchHits)
        assertEquals(0, c.listHits)
    }

    /** A chain whose result fails the list's own checksum: the full list instead. */
    @Test
    fun aChainWhoseResultFailsItsChecksumTakesTheFullList() {
        val c = chain(behind = 3, breakLast = true)
        assertEquals(c.latest, c.text)
        assertEquals(1, c.listHits)
    }

    /**
     * More patches out than one check applies (hourly patches, a daily check, or the first check
     * after an app update): after the first patch says the list changed, the full list at once,
     * without asking for the patches it would throw away, and the list fully up to date, not left
     * a few patches behind for good.
     */
    @Test
    fun aListFarBehindIsDownloadedInFull() {
        val c = chain(behind = 12)
        assertEquals(c.latest, c.text)
        assertEquals(1, c.patchHits)
        assertEquals(1, c.listHits)
    }

    /** A quiet list checked a day later: its first patch says nothing new, and nothing is downloaded. */
    @Test
    fun aQuietListADayLaterIsNotDownloaded() {
        val c = chain(behind = 0, hours = 24)
        assertEquals(c.latest, c.text)
        assertEquals(1, c.patchHits)
        assertEquals(0, c.listHits)
    }
}
