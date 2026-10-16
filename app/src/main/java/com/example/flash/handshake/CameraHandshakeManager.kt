package com.example.flash.handshake

import android.content.Context
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

data class ColorHandshake(
    val peerId: String,
    val displayColor: Int,
    val ip: String,
    val port: Int,
    val token: String,
    val fileCount: Int = 1
)

class CameraHandshakeManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val peerId = UUID.randomUUID().toString().substring(0, 8)
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    private val _colorHandshakeFlow = MutableSharedFlow<ColorHandshake>(extraBufferCapacity = 1)
    val colorHandshakeFlow: SharedFlow<ColorHandshake> = _colorHandshakeFlow.asSharedFlow()

    companion object {
        // Priority order: calm colors first, red last (avoids "error" association)
        private val COLOR_PALETTE_HUES = floatArrayOf(
            200f,  // Light blue
            140f,  // Light green
            280f,  // Purple
            30f,   // Orange
            170f,  // Teal
            60f,   // Yellow
            320f,  // Pink
            0f     // Red (last resort)
        )
    }

    fun generateDisplayColor(): Int {
        val index = ((peerId.hashCode() and 0x7FFFFFFF) % COLOR_PALETTE_HUES.size)
        val hue = COLOR_PALETTE_HUES[index]
        // High saturation + brightness for camera detectability; light variants
        return Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.95f))
    }

    fun startAdvertising(displayColor: Int, ip: String, port: Int, token: String, fileCount: Int) {
        if (nsdManager == null) return
        stopAdvertising()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "flash-$peerId"
            serviceType = "_flash-handshake._tcp"
            setPort(port)
            setAttribute("color", Integer.toHexString(displayColor))
            setAttribute("ip", ip)
            setAttribute("token", token)
            setAttribute("fileCount", fileCount.toString())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun startDiscovery() {
        if (nsdManager == null) return
        stopDiscovery()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let {
                    if (it.serviceName != "flash-$peerId") resolveService(it)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }

        nsdManager.discoverServices("_flash-handshake._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (nsdManager == null) return

        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { resolved ->
                    // NSD often resolves to an IPv6 link-local address that the HTTP client
                    // cannot use, so the sender advertises its IPv4 explicitly.
                    val advertisedIp = resolved.attributes["ip"]?.let { String(it, Charsets.UTF_8) }
                    val hostAddress = advertisedIp
                        ?.takeIf { it.isNotBlank() && it != "0.0.0.0" && !it.startsWith("127.") }
                        ?: resolved.host?.hostAddress?.substringBefore('%')
                        ?: return@let
                    val port = resolved.port
                    val colorStr = resolved.attributes["color"]?.let { String(it, Charsets.UTF_8) } ?: return@let
                    val color = try { Integer.parseUnsignedInt(colorStr, 16) } catch (_: Exception) { Color.BLUE }
                    val token = resolved.attributes["token"]?.let { String(it, Charsets.UTF_8) } ?: return@let
                    val fileCount = resolved.attributes["fileCount"]?.let { String(it, Charsets.UTF_8) }?.toIntOrNull() ?: 1

                    _colorHandshakeFlow.tryEmit(ColorHandshake(
                        peerId = resolved.serviceName,
                        displayColor = color,
                        ip = hostAddress,
                        port = port,
                        token = token,
                        fileCount = fileCount
                    ))
                }
            }
        }

        nsdManager.resolveService(serviceInfo, resolveListener)
    }

    fun stopAdvertising() {
        val listener = registrationListener ?: return
        registrationListener = null
        try { nsdManager?.unregisterService(listener) } catch (_: IllegalArgumentException) { }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try { nsdManager?.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { }
    }
}
