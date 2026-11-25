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

    private val v1 = withSum("[Adblock Plus 2.0]\r\n! Checksum: SUM\r\n! Diff-Path: ../patches/p1-s-1-1.patch\r\n! Title: T\r\n||a.example^\r\n||b.example^\r\n")
    private val v2 = withSum("[Adblock Plus 2.0]\r\n! Checksum: SUM\r\n! Diff-Path: ../patches/p2-s-1-1.patch\r\n! Title: T\r\n||a.example^\r\n||b.example^\r\n||c.example^\r\n")
    private val v3 = withSum("[Adblock Plus 2.0]\r\n! Checksum: SUM\r\n! Diff-Path: ../patches/p9-s-99999999999-1.patch\r\n! Title: T\r\n||a.example^\r\n||d.example^\r\n")

    @Test
    fun aListIsUpdatedByItsPatches() {
        val hits = ConcurrentHashMap<String, AtomicInteger>()
        val bodies = ConcurrentHashMap<String, String>()
        bodies["/f/list.txt"] = v1
        val sum2 = v2.lines()[1].removePrefix("! Checksum: ").trim()
        bodies["/patches/p1-s-1-1.patch"] = "d2 2\na3 2\n! Checksum: $sum2\r\n! Diff-Path: ../patches/p2-s-1-1.patch\r\na6 1\n||c.example^\r\n"
        bodies["/patches/p2-s-1-1.patch"] = ""
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
            assertEquals(1, hit("/patches/p1-s-1-1.patch"))
            assertEquals(1, hit("/patches/p2-s-1-1.patch"))
            assertEquals(3, store.readMeta().lists["l"]?.scan?.lines)
            // Nothing new: nothing changes, and still no full download.
            assertEquals(true, FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now + 120_000).single().fetch is FilterUpdater.Fetch.NotModified)
            assertEquals(1, hit("/f/list.txt"))
            // A patch whose result fails the list's checksum: the full list instead.
            bodies["/patches/p2-s-1-1.patch"] = "a7 1\n||e.example^\r\n"
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

    /**
     * More patches due than one check applies (hourly patches, a daily check): the list is
     * brought fully up to date by a full download, not left a few patches behind for good.
     */
    @Test
    fun aListFarBehindIsDownloadedInFull() {
        fun version(k: Int) = withSum(
            "[Adblock Plus 2.0]\r\n! Checksum: SUM\r\n! Diff-Path: ../patches/p$k-s-1-1.patch\r\n! Title: T\r\n" +
                (0 until k).joinToString("") { "||r$it.example^\r\n" },
        )
        val bodies = ConcurrentHashMap<String, String>()
        bodies["/f/list.txt"] = version(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
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
            val now = 1_800_000_000_000L
            FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now)
            // Twelve versions out since: each patch turns version k into k + 1.
            for (k in 0 until 12) {
                val next = version(k + 1).lines()
                bodies["/patches/p$k-s-1-1.patch"] = "d2 2\na3 2\n${next[1]}\r\n${next[2]}\r\na${4 + k} 1\n||r$k.example^\r\n"
            }
            bodies["/patches/p12-s-1-1.patch"] = ""
            bodies["/f/list.txt"] = version(12)
            FilterUpdater.checkAll(store, listOf(l), FilterUpdater.Check.ALL, now + 60_000)
            assertEquals(version(12), File(store.raw("l").path).readText())
        } finally {
            server.stop(0)
            tmp.deleteRecursively()
        }
    }
}
