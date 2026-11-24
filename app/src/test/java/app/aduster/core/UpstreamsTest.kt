package app.aduster.core

import org.junit.Assert.assertEquals
import org.junit.Test

class UpstreamsTest {
    /**
     * One provider's DNS-over-HTTPS and DNS-over-TLS entries reach the same servers, so they are
     * described alike past the protocol (Quad9 filters malware on both).
     */
    @Test
    fun oneProviderIsDescribedAlikeInBothProtocols() {
        val byProvider = Upstreams.presets.filter { it.id.endsWith("-doh") || it.id.endsWith("-dot") }.groupBy { it.id.substringBeforeLast('-') }
        for ((provider, presets) in byProvider) {
            val notes = presets.map { it.detail.substringAfter(", ") }.toSet()
            assertEquals(provider, 1, notes.size)
        }
    }
}
