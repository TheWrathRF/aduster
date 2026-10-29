package app.aduster.vpn

/** Addresses of the DNS-only tunnel. Nothing but these routes ever enters the TUN device. */
object TunConfig {
    const val MTU = 9000
    const val ADDRESS_V4 = "10.111.222.2"
    const val ADDRESS_V6 = "fd00:ad::2"
    const val DNS_V4 = "10.111.222.1"
    const val DNS_V6 = "fd00:ad::53"
    const val PROXY_PORT = 8899

    /** One public resolver service and its addresses in both families. */
    class Resolver(val name: String, val v4: List<String>, val v6: List<String>)

    /**
     * Well-known public resolvers. Routed into the tunnel only when "Block DNS bypass" is on:
     * plain DNS to them is answered (filtered) by us, DoT/DoH/DoQ to them is refused, so apps
     * with hardcoded resolvers fall back to the system (filtered) one. Each service lists both
     * families: on a dual-stack or IPv6-only network an app reaches whichever it is given.
     * Resolver-only addresses; the ones with a source below were checked on that page
     * (Cloudflare and AdGuard 2026-10-05, OpenDNS and Control D 2026-10-28).
     */
    val PUBLIC_DNS = listOf(
        Resolver("Google", listOf("8.8.8.8", "8.8.4.4"), listOf("2001:4860:4860::8888", "2001:4860:4860::8844")),
        Resolver("Cloudflare", listOf("1.1.1.1", "1.0.0.1"), listOf("2606:4700:4700::1111", "2606:4700:4700::1001")),
        // developers.cloudflare.com/1.1.1.1/ip-addresses
        Resolver("Cloudflare for Families", listOf("1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3"),
            listOf("2606:4700:4700::1112", "2606:4700:4700::1002", "2606:4700:4700::1113", "2606:4700:4700::1003")),
        Resolver("Quad9", listOf("9.9.9.9", "149.112.112.112"), listOf("2620:fe::fe", "2620:fe::9")),
        // securitydocs.cisco.com, Cisco Secure Access DNS resolvers
        Resolver("OpenDNS", listOf("208.67.222.222", "208.67.220.220"), listOf("2620:119:35::35", "2620:119:53::53")),
        // adguard-dns.io/kb/general/dns-providers: default, family, non-filtering
        Resolver("AdGuard", listOf("94.140.14.14", "94.140.15.15"), listOf("2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")),
        Resolver("AdGuard Family", listOf("94.140.14.15", "94.140.15.16"), listOf("2a10:50c0::bad1:ff", "2a10:50c0::bad2:ff")),
        Resolver("AdGuard non-filtering", listOf("94.140.14.140", "94.140.14.141"), listOf("2a10:50c0::1:ff", "2a10:50c0::2:ff")),
        // docs.controld.com/docs/free-dns: unfiltered
        Resolver("Control D", listOf("76.76.2.0", "76.76.10.0"), listOf("2606:1a40::", "2606:1a40:1::")),
    )
    val PUBLIC_DNS_V4 = PUBLIC_DNS.flatMap { it.v4 }
    val PUBLIC_DNS_V6 = PUBLIC_DNS.flatMap { it.v6 }
}
