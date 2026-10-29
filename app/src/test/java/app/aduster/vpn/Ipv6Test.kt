package app.aduster.vpn

import app.aduster.core.Upstreams
import app.aduster.https.HttpsConfig
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IPv6 on every kind of network: what the engine is told, the proxy list, the tunnel's routes. */
class Ipv6Test {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    private fun linkLocal(s: String, scope: Int): InetAddress = Inet6Address.getByAddress(null, ip(s).address, scope)

    @Test
    fun familiesOfEachKindOfNetwork() {
        val home = ip("192.168.1.5")
        val global = ip("2001:db8::5")
        val ll = ip("fe80::5")
        val ula = ip("fd12::5")
        val siteLocal = ip("fec0::15")
        // name, addresses, NAT64 prefix, expected (v4, v6)
        val cases = listOf(
            Triple("IPv4 only", listOf(home, ll), false) to (true to false),
            Triple("dual stack", listOf(home, ll, global), false) to (true to true),
            Triple("IPv6 only, NAT64 (464XLAT)", listOf(ll, global), true) to (true to true),
            Triple("IPv6 only, no NAT64", listOf(ll, global), false) to (false to true),
            Triple("unique-local IPv6 only", listOf(home, ll, ula), false) to (true to false),
            // The emulator's Wi-Fi: fec0::/64 and no IPv6 internet.
            Triple("site-local IPv6 only", listOf(home, ll, siteLocal), false) to (true to false),
        )
        for ((c, want) in cases) {
            val n = NetworkMonitor.facts(emptyList(), c.second, c.third)
            assertEquals(c.first, want, n.v4 to n.v6)
        }
    }

    @Test
    fun resolversKeepLinkLocalScopeAndSkipTheTunnel() {
        val servers = listOf(
            linkLocal("fe80::1", 12),
            ip("2001:db8::53"),
            linkLocal("fe80::2", 0), // no interface: cannot be reached
            ip(TunConfig.DNS_V6),
            ip(TunConfig.DNS_V4),
            ip("192.168.1.1"),
        )
        val dns = NetworkMonitor.facts(servers, listOf(ip("2001:db8::5")), false).dns.split(",")
        assertEquals(3, dns.size)
        // Global ones first, then the link-local one with its interface's scope id.
        assertTrue(dns[0], InetAddress.getByName(dns[0]) == ip("2001:db8::53"))
        assertEquals("192.168.1.1", dns[1])
        val (addr, scope) = dns[2].split('%')
        assertEquals(ip("fe80::1"), InetAddress.getByName(addr))
        assertEquals("12", scope)
    }

    /** Android's own exclusion-list pattern (AOSP ProxyUtils, EXCLLIST_REGEXP): one invalid entry voids the whole proxy. */
    private val excl = """[a-zA-Z0-9*]+(\-[a-zA-Z0-9*]+)*(\.[a-zA-Z0-9*]+(\-[a-zA-Z0-9*]+)*)*"""
    private val exclList = Regex("^$|^$excl(,$excl)*$")

    @Test
    fun proxyBypassListIsValidForAndroid() {
        assertTrue(HttpsConfig.PROXY_BYPASS.joinToString(","), exclList.matches(HttpsConfig.PROXY_BYPASS.joinToString(",")))
        // Why IPv6 local ranges are handled in the engine instead: no IPv6 entry is accepted.
        for (v6 in listOf("::1", "[::1]", "fe80::*", "fd00:*")) assertFalse(v6, exclList.matches("localhost,$v6"))
    }

    @Test
    fun everyRoutedResolverHasBothFamilies() {
        for (p in TunConfig.PUBLIC_DNS) {
            assertTrue(p.name, p.v4.isNotEmpty() && p.v6.isNotEmpty())
            p.v4.forEach { assertTrue("${p.name} $it", ip(it) is Inet4Address) }
            p.v6.forEach { assertTrue("${p.name} $it", ip(it) is Inet6Address) }
        }
        assertEquals(TunConfig.PUBLIC_DNS.flatMap { it.v6 }, TunConfig.PUBLIC_DNS_V6)
        assertEquals(TunConfig.PUBLIC_DNS_V4.size, TunConfig.PUBLIC_DNS_V4.distinct().size)
        assertEquals(TunConfig.PUBLIC_DNS_V6.size, TunConfig.PUBLIC_DNS_V6.distinct().size)
    }

    @Test
    fun encryptedResolversGivenAsIpv6Literals() {
        assertEquals("2606:4700:4700::1111" to 853, Upstreams.dotHostPort("tls://[2606:4700:4700::1111]"))
        assertEquals("2606:4700:4700::1111" to 8853, Upstreams.dotHostPort("tls://[2606:4700:4700::1111]:8853"))
        // Without brackets the port cannot be told from the address: refused, not misread.
        assertNull(Upstreams.dotHostPort("tls://2606:4700:4700::1111"))
        assertTrue(Upstreams.validCustom("tls://[2620:fe::fe]"))
        assertTrue(Upstreams.validCustom("https://[2620:fe::fe]/dns-query"))
    }
}
