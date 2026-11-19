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
    }
}
