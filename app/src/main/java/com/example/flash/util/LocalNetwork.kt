package com.example.flash.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

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

    private val WIFI_IF = Regex("^(wlan|swlan|ap|softap|p2p)[-\\w]*\\d*$")

    /** This phone's LAN IPv4: the real Wi-Fi network first, then Wi-Fi/hotspot interfaces. */
    fun localIpv4(context: Context): String? {
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
    fun bindProcessToWifi(context: Context): Boolean = runCatching {
        val wifi = wifiNetwork(context) ?: return false
        cm(context).bindProcessToNetwork(wifi)
    }.getOrDefault(false)

    fun unbind(context: Context) {
        runCatching { cm(context).bindProcessToNetwork(null) }
    }
}
