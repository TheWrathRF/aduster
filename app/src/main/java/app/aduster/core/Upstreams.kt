package app.aduster.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Upstream resolver presets. Encrypted presets carry bootstrap IPs so no plain lookup is needed. */
object Upstreams {
    data class Preset(val id: String, val label: String, val detail: String, val json: JsonObject?)

    private fun doh(url: String, vararg ips: String) = buildJsonObject {
        put("type", "doh")
        put("url", url)
        putJsonArray("bootstrap") { ips.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }

    private fun dot(host: String, vararg ips: String) = buildJsonObject {
        put("type", "dot")
        put("host", host)
        putJsonArray("bootstrap") { ips.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }

    val SYSTEM = Preset("system", "Network default", "Your Wi-Fi or carrier DNS server, plain DNS", buildJsonObject { put("type", "system") })

    val presets = listOf(
        SYSTEM,
        Preset("cloudflare-doh", "Cloudflare", "DNS-over-HTTPS · 1.1.1.1", doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111")),
        Preset("quad9-doh", "Quad9", "DNS-over-HTTPS · 9.9.9.9, malware filtering", doh("https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112", "2620:fe::fe")),
        Preset("google-doh", "Google", "DNS-over-HTTPS · 8.8.8.8", doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888")),
        Preset("adguard-doh", "AdGuard (unfiltered)", "DNS-over-HTTPS · 94.140.14.140", doh("https://unfiltered.adguard-dns.com/dns-query", "94.140.14.140", "94.140.14.141")),
        Preset("cloudflare-dot", "Cloudflare", "DNS-over-TLS · 1.1.1.1", dot("one.one.one.one", "1.1.1.1", "1.0.0.1")),
        Preset("quad9-dot", "Quad9", "DNS-over-TLS · 9.9.9.9", dot("dns.quad9.net", "9.9.9.9", "149.112.112.112")),
        Preset("custom", "Custom", "https://… for DoH, tls://host for DoT", null),
    )

    fun byId(id: String) = presets.firstOrNull { it.id == id } ?: SYSTEM

    /** Engine JSON for the chosen upstream; falls back to the network resolver on bad input. */
    fun json(id: String, custom: String): JsonObject {
        val preset = byId(id)
        if (preset.json != null) return preset.json
        val c = custom.trim()
        return when {
            c.startsWith("https://") -> buildJsonObject { put("type", "doh"); put("url", c) }
            c.startsWith("tls://") -> {
                val (host, port) = dotHostPort(c) ?: return SYSTEM.json!!
                buildJsonObject { put("type", "dot"); put("host", host); put("port", port) }
            }
            else -> SYSTEM.json!!
        }
    }

    /**
     * Host and port of `tls://host[:port]`; an IPv6 address goes in brackets
     * (`tls://[2606:4700::1111]:853`). Null when there is no host or the port is not a number.
     */
    fun dotHostPort(url: String): Pair<String, Int>? {
        val hostPort = url.trim().removePrefix("tls://").substringBefore('/')
        val (host, rest) = if (hostPort.startsWith("[")) {
            hostPort.removePrefix("[").substringBefore(']') to hostPort.substringAfter(']', "")
        } else {
            hostPort.substringBefore(':') to hostPort.substring(hostPort.substringBefore(':').length)
        }
        val port = if (rest.isEmpty()) 853 else rest.removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return if (host.isEmpty()) null else host to port
    }

    /** Whether a custom resolver address can be used (checked before saving it). */
    fun validCustom(url: String): Boolean {
        val c = url.trim()
        return when {
            c.startsWith("https://") -> runCatching { java.net.URI(c).host }.getOrNull()?.isNotEmpty() == true
            c.startsWith("tls://") -> dotHostPort(c) != null
            else -> false
        }
    }

    fun label(id: String, custom: String): String {
        val p = byId(id)
        return if (p.id == "custom") custom.ifBlank { "Custom" } else if (p.id == "system") p.label else "${p.label} · ${p.detail.substringBefore(" ·")}"
    }
}
