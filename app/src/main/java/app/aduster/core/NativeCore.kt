package app.aduster.core

import android.net.ConnectivityManager
import java.net.InetAddress
import java.net.InetSocketAddress

/** The Rust core (libaduster.so). All calls are thread-safe and never throw across JNI. */
object NativeCore {
    init {
        System.loadLibrary("aduster")
    }

    /** Set in the :core process; used by [ownerUid] to attribute queries to apps. */
    @Volatile
    var connectivity: ConnectivityManager? = null

    /** Set by the VPN service; called if the DNS engine exits on its own (e.g. a TUN error). */
    @Volatile
    var engineExitListener: (() -> Unit)? = null

    @JvmStatic external fun nativeInit(configJson: String): Boolean
    /** Whether this copy may start protection; [nativeStart] refuses when it may not. */
    @JvmStatic external fun nativeIntact(): Boolean
    @JvmStatic external fun nativeStart(tunFd: Int): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeIsRunning(): Boolean
    /**
     * Resolvers (comma-separated), reachable address families and handle (0 = none) of the
     * underlying network, its search domains (comma-separated; names under them are local) and a
     * name for the recorder. A new handle closes connections made on the previous network.
     */
    @JvmStatic external fun nativeSetNetwork(csv: String, hasV4: Boolean, hasV6: Boolean, handle: Long, domains: String, label: String)
    @JvmStatic external fun nativeCompile(sourcesJson: String, outPath: String): String
    @JvmStatic external fun nativeLoadFilters(tablePath: String, userRules: String): String
    @JvmStatic external fun nativeValidateRules(text: String): String
    @JvmStatic external fun nativeCheckDomain(name: String): String
    @JvmStatic external fun nativeStats(): String
    @JvmStatic external fun nativeBlockedToday(): Long
    @JvmStatic external fun nativeLog(since: Long, max: Int): String
    /** The oldest [max] entries after [since] (forward paging). */
    @JvmStatic external fun nativeLogAfter(since: Long, max: Int): String
    /** Whether this build has the page engine (the public source has a stand-in without it). */
    @JvmStatic external fun nativeProxyAvailable(): Boolean
    /** How a browser's connections to [host] are handled right now, and why. */
    @JvmStatic external fun nativeExplainHost(host: String): String
    /** Whether the running proxy has a page engine loaded. */
    @JvmStatic external fun nativeWebLoaded(): Boolean
    @JvmStatic external fun nativeDiagnostics(): String
    /** Stall report: recorded problems, requests waiting right now, resolver and proxy state. */
    @JvmStatic external fun nativeReport(): String
    @JvmStatic external fun nativeClearLog()
    @JvmStatic external fun nativeResetStats()
    @JvmStatic external fun nativePersist()
    /** Screen off / memory pressure: frees heap, writes stats, closes idle encrypted DNS connections. */
    @JvmStatic external fun nativeTrimMemory()
    /** Ends the bot-check bypass of [host] ("Filter again"); true if it was bypassed. */
    @JvmStatic external fun nativeUnbypass(host: String): Boolean
    @JvmStatic external fun nativeGenerateCa(): String
    @JvmStatic external fun nativeProxyStart(configJson: String): Int
    @JvmStatic external fun nativeProxyStop()
    @JvmStatic external fun nativeCompileWeb(sourcesJson: String, outPath: String, dnsTablePath: String): String
    @JvmStatic external fun nativeReloadWeb(enginePath: String, resourcesPath: String): String
    /** What the page engine at [enginePath] depends on in the DNS table (a short fingerprint). */
    @JvmStatic external fun nativeWebDnsDigest(enginePath: String, dnsTablePath: String): String

    /** Called from the Rust DNS thread when it exits without being stopped. */
    @JvmStatic
    fun onEngineExit() {
        engineExitListener?.invoke()
    }

    /**
     * Called from the Rust DNS thread for each query when the log is on. Addresses arrive as
     * primitive halves so the call allocates nothing on the native side.
     */
    @JvmStatic
    fun ownerUid(proto: Int, v6: Boolean, srcHi: Long, srcLo: Long, srcPort: Int, dstHi: Long, dstLo: Long, dstPort: Int): Int {
        val cm = connectivity ?: return -1
        return try {
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(address(v6, srcHi, srcLo), srcPort),
                InetSocketAddress(address(v6, dstHi, dstLo), dstPort),
            )
        } catch (_: Exception) {
            -1
        }
    }

    private fun address(v6: Boolean, hi: Long, lo: Long): InetAddress =
        if (!v6) {
            val v = lo.toInt()
            InetAddress.getByAddress(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        } else {
            val b = ByteArray(16)
            for (i in 0 until 8) {
                b[i] = (hi ushr (56 - 8 * i)).toByte()
                b[8 + i] = (lo ushr (56 - 8 * i)).toByte()
            }
            InetAddress.getByAddress(b)
        }
}
