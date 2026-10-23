package com.dimax.flash.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Finds and uses the phone's real local (Wi-Fi) network even when a VPN is the default
 * network. With a VPN on, the "active" network is the tunnel: its address (often 100.x) is
 * unreachable from other phones, and connections to 192.168.x.x can be swallowed by the tunnel.
 */
object LocalNetwork {

    private fun cm(context: Context) =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The Wi-Fi network itself (not a VPN riding on top of it), or null. */
    @Suppress("DEPRECATION")
    fun wifiNetwork(context: Context): Network? = runCatching {
        val cm = cm(context)
        cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }.getOrNull()

    fun isVpnActive(context: Context): Boolean = runCatching {
        val cm = cm(context)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /** True for an address another phone on the LAN could reach (not loopback, link-local or CGNAT/VPN 100.64/10). */
    fun isUsableLanIpv4(address: Inet4Address): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return false
        val b = address.address
        val cgnat = (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
        return !cgnat
    }

    private val HOTSPOT_IF = Regex("^(swlan|ap|softap)\\d*$")

    private val WIFI_IF = Regex("^(wlan|swlan|ap|softap|p2p)[-\\w]*\\d*$")

    /** This phone's LAN IPv4: the real Wi-Fi network first, then Wi-Fi/hotspot interfaces. */
    fun localIpv4(context: Context): String? {
        // Hosting a hotspot: the phones joining it are on the hotspot's own network, so that address wins even if
        // this phone is also a client of another Wi-Fi (e.g. one that needs a login). Samsung: swlan0, others: ap0.
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && HOTSPOT_IF.matches(it.name) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { isUsableLanIpv4(it) }
                ?.hostAddress
                ?.let { return it }
        }
        runCatching {
            val wifi = wifiNetwork(context)
            if (wifi != null) {
                cm(context).getLinkProperties(wifi)?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { isUsableLanIpv4(it) }
                    ?.hostAddress
                    ?.let { return it }
            }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && WIFI_IF.matches(it.name) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { isUsableLanIpv4(it) }
                ?.hostAddress
                ?.let { return it }
        }
        return null
    }

    /** Sends this process's new sockets over Wi-Fi, bypassing a VPN. Pair with [unbind]. */
    private var bindCount = 0

    /** Reference counted: the download and the preview fetches overlap, so only the last [unbind] releases it. */
    @Synchronized
    fun bindProcessToWifi(context: Context): Boolean = runCatching {
        val wifi = wifiNetwork(context) ?: return false
        val ok = cm(context).bindProcessToNetwork(wifi)
        if (ok) bindCount++
        ok
    }.getOrDefault(false)

    @Synchronized
    fun unbind(context: Context) {
        if (bindCount > 0) bindCount--
        if (bindCount == 0) runCatching { cm(context).bindProcessToNetwork(null) }
    }

    /**
     * Tries to reach ip:port three ways so a failed transfer says WHY: over the default network,
     * over the real Wi-Fi network, and with a ping. "No route to host" on all of them while both
     * phones show the same Wi-Fi usually means the router isolates Wi-Fi clients from each other.
     */
    suspend fun probe(context: Context, ip: String, port: Int): String = withContext(Dispatchers.IO) {
        fun attempt(open: () -> Socket): String = try {
            open().use { it.connect(InetSocketAddress(ip, port), 2500) }
            "ok"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
        }
        coroutineScope {
            val wifi = wifiNetwork(context)
            val default = async { attempt { Socket() } }
            val viaWifi = async { if (wifi != null) attempt { wifi.socketFactory.createSocket() } else "no Wi-Fi network" }
            val ping = async { runCatching { InetAddress.getByName(ip).isReachable(1500) }.getOrDefault(false) }
            "default=${default.await()} | wifi=${viaWifi.await()} | ping=${if (ping.await()) "reply" else "none"}"
        }
    }
}
