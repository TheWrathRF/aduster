package app.aduster.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * Every encrypted preset can be dialled on an IPv4-only and on an IPv6-only network: its
     * bootstrap holds addresses of both families (three presets had IPv4 only, so on IPv6-only
     * mobile data without 464XLAT they failed every lookup).
     */
    /** A DoT address is refused unless its host and port are whole: DNS would fail at every dial. */
    @Test
    fun malformedDotAddressesAreRefused() {
        assertNull(Upstreams.dotHostPort("tls://[2606:4700::1111]853"))
        assertNull(Upstreams.dotHostPort("tls://bad host.example"))
        assertNull(Upstreams.dotHostPort("tls://[2606:4700::1111"))
        assertNull(Upstreams.dotHostPort("tls://[not-an-address]:853"))
        assertNull(Upstreams.dotHostPort("tls://dns.example:853:1"))
        assertNull(Upstreams.dotHostPort("tls://dns..example"))
        assertNull(Upstreams.dotHostPort("tls://:853"))
        assertEquals("2606:4700::1111" to 853, Upstreams.dotHostPort("tls://[2606:4700::1111]:853"))
        assertEquals("2606:4700::1111" to 853, Upstreams.dotHostPort("tls://[2606:4700::1111]"))
        assertEquals("abc123.dns.example" to 8853, Upstreams.dotHostPort("tls://abc123.dns.example:8853"))
        assertEquals("1.1.1.1" to 853, Upstreams.dotHostPort("tls://1.1.1.1"))
        assertEquals("one.one.one.one" to 853, Upstreams.dotHostPort(" tls://one.one.one.one/ "))
    }

    @Test
    fun everyEncryptedPresetHasBothFamilies() {
        for (p in Upstreams.presets) {
            val ips = p.json?.get("bootstrap")?.jsonArray?.map { it.jsonPrimitive.content } ?: continue
            assertTrue("${p.id}: no IPv4 bootstrap address", ips.any { !it.contains(':') })
            assertTrue("${p.id}: no IPv6 bootstrap address", ips.any { it.contains(':') })
        }
    }
}
