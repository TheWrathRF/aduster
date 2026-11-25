package app.aduster.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/** Saved reports name visited hosts: only the newest stay in Downloads. */
class ReportsTest {
    @Test
    fun onlyTheNewestReportsAreKept() {
        val names = (1..12).map { "Aduster_report_202611%02d-120000.json".format(it) }
        assertEquals(names.take(2), Reports.beyondNewest(names.shuffled(java.util.Random(1)), keep = 10).sorted())
        assertEquals(emptyList<String>(), Reports.beyondNewest(names.take(10)))
        // A copy the system renamed counts as a report; anything else in Downloads is never touched.
        val other = listOf("Aduster_report_20261101-120000 (1).json", "aduster-settings.json", "Aduster_report_notes.txt")
        assertEquals(listOf("Aduster_report_20261101-120000 (1).json"), Reports.beyondNewest(other + names.drop(2), keep = 10))
        // After the clock went back, the report just saved has the oldest name: it stays, the oldest other goes.
        val early = "Aduster_report_20261001-120000.json"
        assertEquals(listOf(names[2]), Reports.beyondNewest(names.drop(2) + early, keep = 10, justSaved = early))
    }

    /** Share report sends the file just saved: by its name, or the system's renamed copy of it. */
    @Test
    fun theReportJustSavedIsTheOneShared() {
        val saved = "Aduster_report_20261125-101500.json"
        val older = "Aduster_report_20261125-090000.json" to 7L
        assertEquals(12L, Reports.savedId(mapOf(older, saved to 12L), saved))
        // A second report in the same second: the system names it "… (1).json"; the newest copy wins.
        val copies = mapOf(saved to 12L, "Aduster_report_20261125-101500 (1).json" to 13L, older)
        assertEquals(13L, Reports.savedId(copies, saved))
        // Never another report, never a file that is not a report.
        assertEquals(null, Reports.savedId(mapOf(older, "Aduster_report_20261125-101500.txt" to 20L), saved))
    }
}
