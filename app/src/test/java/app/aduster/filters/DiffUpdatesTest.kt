package app.aduster.filters

import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Differential list updates: patch names, addresses, the RCS patch and the list's own checksum. */
class DiffUpdatesTest {
    @Test
    fun patchNamesSayWhenTheNextOneIsDue() {
        // AdGuard's form: seconds since the epoch, valid for an hour.
        assertEquals((1_795_450_274L + 3600) * 1000, DiffUpdates.dueAt("../patches/20/20-s-1795450274-3600.patch"))
        assertEquals((100L + 60) * 60_000, DiffUpdates.dueAt("list_v1.0-m-100-60.patch#list1"))
        // No resolution: hours.
        assertEquals((10L + 2) * 3_600_000, DiffUpdates.dueAt("/patches/name-10-2.patch"))
        assertNull(DiffUpdates.dueAt("../patches/20/20-s-abc-3600.patch"))
        assertNull(DiffUpdates.dueAt("../patches/20/20.txt"))
        assertNull(DiffUpdates.dueAt("name-s-10-0.patch"))
    }

    @Test
    fun aPatchIsFetchedBesideTheList() {
        assertEquals(
            "https://filters.adtidy.org/extension/ublock/patches/20/20-s-1-3600.patch",
            DiffUpdates.patchUrl("https://filters.adtidy.org/extension/ublock/filters/20.txt", "../patches/20/20-s-1-3600.patch#x"),
        )
        // Only from the list's own server.
        assertNull(DiffUpdates.patchUrl("https://filters.adtidy.org/extension/ublock/filters/20.txt", "https://elsewhere.example/p-s-1-1.patch"))
        assertNull(DiffUpdates.patchUrl("https://filters.adtidy.org/extension/ublock/filters/20.txt", "//elsewhere.example/p-s-1-1.patch"))
    }

    @Test
    fun theDiffPathIsReadFromTheHeader() {
        val head = "[Adblock Plus 2.0]\r\n! Checksum: abc\r\n! Diff-Path: ../patches/20/20-s-1-3600.patch\r\n! Title: x\r\n||a^\r\n"
        assertEquals("../patches/20/20-s-1-3600.patch", DiffUpdates.diffPath(head))
        assertNull(DiffUpdates.diffPath("! Title: x\n||a^\n"))
        // Only in the header: a rule-looking line further down does not count.
        assertNull(DiffUpdates.diffPath("! Title: x\n||a^\n! Diff-Path: ../p-s-1-1.patch\n"))
    }

    @Test
    fun rcsPatchesApplyToTheOriginalLineNumbers() {
        val old = "a\r\nb\r\nc\r\nd\r\n".toByteArray()
        // Commands end in LF; the lines they add keep the list's CRLF.
        val patch = "d2 1\na3 2\nX\r\nY\r\n".toByteArray()
        assertEquals("a\r\nc\r\nX\r\nY\r\nd\r\n", DiffUpdates.applyRcs(old, patch)?.decodeToString())
        assertEquals("a\r\nb\r\nc\r\nd\r\nE\r\n", DiffUpdates.applyRcs(old, "a4 1\nE\r\n".toByteArray())?.decodeToString())
        assertEquals("Z\r\nb\r\nc\r\nd\r\n", DiffUpdates.applyRcs(old, "d1 1\na1 1\nZ\r\n".toByteArray())?.decodeToString())
        // Anything else is refused: past the end, out of order, too few lines, not a command.
        assertNull(DiffUpdates.applyRcs(old, "d9 1\n".toByteArray()))
        assertNull(DiffUpdates.applyRcs(old, "d4 2\n".toByteArray()))
        assertNull(DiffUpdates.applyRcs(old, "d3 1\nd1 1\n".toByteArray()))
        assertNull(DiffUpdates.applyRcs(old, "a1 2\nonly\r\n".toByteArray()))
        assertNull(DiffUpdates.applyRcs(old, "<html>\n".toByteArray()))
        assertNull(DiffUpdates.applyRcs(old, ByteArray(0)))
    }

    @Test
    fun theListsOwnChecksumIsChecked() {
        // Reference value from an independent implementation (the one that matched AdGuard's
        // published list 20 of 2026-11-24, "QKeDUjDOlBpZCyWj/1zK7g").
        val text = "[Adblock Plus 2.0]\r\n! Checksum: placeholder\r\n! Title: Sample\r\n\r\n\r\n||ads.example^\r\nexample.org##.banner\r\n"
        assertEquals("jD+kES4oyrr8L11br9bNvQ", DiffUpdates.abpChecksum(text))
        assertEquals(false, DiffUpdates.checksumHolds(text.toByteArray()))
        assertEquals(true, DiffUpdates.checksumHolds(text.replace("placeholder", "jD+kES4oyrr8L11br9bNvQ").toByteArray()))
        // No checksum line: nothing to check against.
        assertNull(DiffUpdates.checksumHolds("! Title: x\r\n||a^\r\n".toByteArray()))
    }

    @Test
    fun oversizedNamesAndResultsAreRefused() {
        // Fifteen digits of hours overflow a millisecond clock: not a name we can schedule.
        assertNull(DiffUpdates.dueAt("../patches/20/20-999999999999999-1.patch"))
        // A result over the size limit is refused, not built.
        val old = "a\nb\n".toByteArray()
        assertNull(DiffUpdates.applyRcs(old, "a2 1\n${"x".repeat(100)}\n".toByteArray(), maxBytes = 64))
        assertEquals("a\nb\nc\n", DiffUpdates.applyRcs(old, "a2 1\nc\n".toByteArray(), maxBytes = 64)?.decodeToString())
    }

    private val referencePattern = Regex("""^\s*!\s*checksum[\s\-:]+([\w+/=]+).*\n""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** The list checksum as it was first written (whole-text copies): the reference for [DiffUpdates.abpChecksum]. */
    private fun referenceChecksum(text: String): String {
        var d = text.replace("\r", "").replace(Regex("\n+"), "\n")
        d = referencePattern.replaceFirst(d, "")
        return Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("MD5").digest(d.toByteArray()))
    }

    private fun referenceHolds(list: ByteArray): Boolean? {
        val text = list.decodeToString()
        val want = referencePattern.find(text.replace("\r", ""))?.groupValues?.get(1)?.trimEnd('=') ?: return null
        return referenceChecksum(text) == want
    }

    @Test
    fun theChecksumIsTheSameAsTheWholeTextOne() {
        val pieces = listOf(
            "! Checksum: ", "!checksum:", "  ! CHECKSUM - ", "!  checksum\t", "||ads.test^", "\r", "\n", "\n\n", "\r\n", " ", "\t",
            "! Title: x", "абв.рф", "a", "==", "+/", "ÿ", "😀", "\n! Checksum: AAAA\n", "#", ".", "\u000B", "\u000C",
        )
        val rnd = Random(7)
        repeat(4000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(1, 30)) { sb.append(pieces[rnd.nextInt(pieces.size)]) }
            val t = sb.toString()
            val sum = referenceChecksum(t.replaceFirst("! Checksum: ", "! Checksum: x"))
            val withSum = t.replaceFirst("! Checksum: ", "! Checksum: $sum\n")
            for (text in listOf(t, withSum)) {
                assertEquals(text, referenceChecksum(text), DiffUpdates.abpChecksum(text))
                assertEquals(text, referenceHolds(text.toByteArray()), DiffUpdates.checksumHolds(text.toByteArray()))
            }
        }
        // Bytes that are not UTF-8 are read as the text decoder reads them.
        val bad = "! Checksum: AAAA\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), '\n'.code.toByte())
        assertEquals(referenceHolds(bad), DiffUpdates.checksumHolds(bad))
    }

    @Test
    fun aPatchOfALargeListAllocatesLittleBeyondTheResult() {
        // A 7 MB list with non-Latin comments (as regional lists have: UTF-16 strings in Java).
        val body = StringBuilder()
        var i = 0
        while (body.length < 7_000_000) {
            if (i % 50 == 0) body.append("! раздел ").append(i).append('\n')
            body.append("||host-").append(i).append(".example^\n")
            i++
        }
        val text = "! Title: big\n! Checksum: " + referenceChecksum("! Title: big\n! Checksum: x\n$body") + "\n" + body
        val old = text.toByteArray()
        val patch = "d3 1\na3 1\n||added.example^\n".toByteArray()
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        DiffUpdates.applyRcs(old, patch)?.let { DiffUpdates.checksumHolds(it) } // warm-up
        val before = bean.getThreadAllocatedBytes(id)
        val next = DiffUpdates.applyRcs(old, patch, 64L * 1024 * 1024)
        val holds = next?.let { DiffUpdates.checksumHolds(it) }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        println("list ${old.size} bytes: allocated $allocated bytes (${"%.1f".format(allocated.toDouble() / old.size)}x)")
        assertEquals(false, holds) // the patch replaced line 3, so the old checksum no longer holds
        val lines = text.split("\n")
        assertArrayEquals((lines.take(2) + "||added.example^" + lines.drop(3)).joinToString("\n").toByteArray(), next)
        // The result (1x) and one UTF-16 copy of its text for the checksum pattern (2x; the JVM
        // first tries a Latin-1 builder, +1x): 226 MB (32x) when every line and step was copied.
        assertTrue("allocated $allocated bytes for a ${old.size}-byte list", allocated < 5L * old.size)
    }
}
