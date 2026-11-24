package app.aduster.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
