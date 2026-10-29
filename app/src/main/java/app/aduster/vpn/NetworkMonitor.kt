package app.aduster.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import app.aduster.core.NativeCore
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Tracks the best physical (non-VPN) network and feeds its resolvers to the engine.
 *
 * The default-network callback cannot be used: for the VPN owner, some vendor builds (Xiaomi's)
 * report the VPN itself, whose resolver is our own tunnel address. Matching on NOT_VPN yields the same
 * network the system routes through (Wi-Fi over cellular), and it is event-driven, no polling.
 */
class NetworkMonitor(context: Context) : ConnectivityManager.NetworkCallback() {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var lastDns: String? = null
    private var current: Network? = null

    /** Private DNS in strict mode sends queries around the tunnel; the UI warns about it. */
    @Volatile
    var privateDnsStrict = false
        private set

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerBestMatchingNetworkCallback(request, this, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(this)
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onAvailable(network: Network) {
        current = network
        cm.getLinkProperties(network)?.let { apply(it) }
    }

    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
        if (network == current) apply(lp)
    }

    override fun onLost(network: Network) {
        if (network == current) {
            current = null
            push("", handle = 0)
        }
    }

    private fun apply(lp: LinkProperties) {
        privateDnsStrict = lp.privateDnsServerName != null
        val f = facts(lp.dnsServers, lp.linkAddresses.map { it.address }, lp.nat64Prefix != null)
        // The network's own search domains (DHCP option 15/119): a campus or company network
        // answers its own names with private addresses (split DNS), which is not rebinding.
        val domains = lp.domains.orEmpty().split(' ', ',', ';').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
        push(f.dns, f.v4, f.v6, current?.networkHandle ?: 0, domains.joinToString(","), label())
    }

    /** What the engine is told about a network: its resolvers (comma-separated) and the families it reaches. */
    data class Facts(val dns: String, val v4: Boolean, val v6: Boolean)

    companion object {
        fun facts(dnsServers: List<InetAddress>, addrs: List<InetAddress>, nat64: Boolean): Facts {
            // A link-local resolver goes with its interface's scope id ("fe80::1%12"), after the global
            // ones: some routers offer only that one, and without it the network would count as offline.
            val (linkLocal, global) = dnsServers.partition { it is Inet6Address && it.isLinkLocalAddress }
            val dns = (global.filterNot { it == TUN_V4 || it == TUN_V6 }.mapNotNull { it.hostAddress } +
                linkLocal.mapNotNull { a -> (a as Inet6Address).scopeId.takeIf { it != 0 }?.let { "${a.hostAddress?.substringBefore('%')}%$it" } })
                .joinToString(",")
            // IPv6-only mobile data still reaches IPv4 through the platform's 464XLAT (NAT64 prefix).
            val v4 = addrs.any { it is Inet4Address && !it.isLoopbackAddress } || nat64
            // Only a global IPv6 address counts: link-local, ULA (fc00::/7) and site-local (fec0::/10,
            // the emulator's Wi-Fi) never reach the internet.
            val v6 = addrs.any {
                it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isLoopbackAddress && (it.address[0].toInt() and 0xFE) != 0xFC
            }
            return Facts(dns, v4, v6)
        }

        // Compared as addresses: Android writes IPv6 compressed, other runtimes in full.
        private val TUN_V4 = InetAddress.getByName(TunConfig.DNS_V4)
        private val TUN_V6 = InetAddress.getByName(TunConfig.DNS_V6)
    }

    /**
     * "Wi-Fi", "Mobile data": how the recorder names this network. Its kind only: no SSID (that
     * needs location access) and no search domain (a school's or company's own, and the recorder
     * goes into reports people post).
     */
    private fun label(): String {
        val caps = current?.let { cm.getNetworkCapabilities(it) }
        return when {
            caps == null -> "Network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Network"
        }
    }

    private fun push(dns: String, v4: Boolean = true, v6: Boolean = true, handle: Long, domains: String = "", label: String = "") {
        val key = "$dns|$v4|$v6|$handle|$domains|$label"
        if (key != lastDns) {
            lastDns = key
            NativeCore.nativeSetNetwork(dns, v4, v6, handle, domains, label)
        }
    }
}
