package com.dimax.flash.handshake

import android.content.Context
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.dimax.flash.util.LocalNetwork
import java.net.Inet4Address
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
    private val main = Handler(Looper.getMainLooper())
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // NsdManager can only resolve one service at a time (FAILURE_ALREADY_ACTIVE otherwise)
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val _colorHandshakeFlow = MutableSharedFlow<ColorHandshake>(extraBufferCapacity = 1)
    val colorHandshakeFlow: SharedFlow<ColorHandshake> = _colorHandshakeFlow.asSharedFlow()

    /** Last few mDNS events, shown on screen so pairing problems can be diagnosed without a computer. */
    private val _status = MutableStateFlow<List<String>>(emptyList())
    val status: StateFlow<List<String>> = _status.asStateFlow()

    private fun log(line: String) {
        _status.value = (_status.value + line).takeLast(5)
    }

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
        private const val SERVICE_TYPE = "_flash-handshake._tcp"
        private const val FAILURE_ALREADY_ACTIVE = 3
    }

    fun generateDisplayColor(): Int {
        val index = ((peerId.hashCode() and 0x7FFFFFFF) % COLOR_PALETTE_HUES.size)
        val hue = COLOR_PALETTE_HUES[index]
        // High saturation + brightness for camera detectability; light variants
        return Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.95f))
    }

    fun startAdvertising(displayColor: Int, ip: String, port: Int, token: String, fileCount: Int) {
        if (nsdManager == null) { log("NSD unavailable on this phone"); return }
        stopAdvertising()
        register(displayColor, ip, port, token, fileCount, attempt = 1)
    }

    private fun register(displayColor: Int, ip: String, port: Int, token: String, fileCount: Int, attempt: Int) {
        val manager = nsdManager ?: return
        val wifi = LocalNetwork.wifiNetwork(context)
        val serviceInfo = NsdServiceInfo().apply {
            // With a VPN on, the default network is the tunnel; announce on the real Wi-Fi instead
            if (wifi != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                network = wifi
            }
            serviceName = "flash-$peerId"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("color", Integer.toHexString(displayColor))
            setAttribute("ip", ip)
            setAttribute("token", token)
            setAttribute("fileCount", fileCount.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                log("advertise FAILED code $errorCode (try $attempt)")
                if (attempt < 3 && registrationListener === this) {
                    registrationListener = null
                    main.postDelayed({ register(displayColor, ip, port, token, fileCount, attempt + 1) }, 800)
                }
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceRegistered(info: NsdServiceInfo?) {
                log("advertising as ${info?.serviceName} @ $ip:$port")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo?) {}
        }
        registrationListener = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            log("advertise error: ${e.javaClass.simpleName}")
        }
    }

    fun startDiscovery() {
        val manager = nsdManager ?: run { log("NSD unavailable on this phone"); return }
        stopDiscovery()
        resolveQueue.clear()
        resolving = false

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) { log("searching for senders…") }
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                log("search FAILED code $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                if (serviceInfo.serviceName == "flash-$peerId") return
                log("found ${serviceInfo.serviceName}")
                main.post { enqueueResolve(serviceInfo) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { log("lost ${it.serviceName}") }
            }
        }
        discoveryListener = listener
        val wifi = LocalNetwork.wifiNetwork(context)
        log(
            "Wi-Fi " + (LocalNetwork.localIpv4(context) ?: "none") +
                if (LocalNetwork.isVpnActive(context)) " (VPN on, using Wi-Fi directly)" else ""
        )
        try {
            if (wifi != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                manager.discoverServices(
                    SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, wifi, context.mainExecutor, listener
                )
            } else {
                manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            log("search error: ${e.javaClass.simpleName}")
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        resolveNext()
    }

    private fun resolveNext() {
        val manager = nsdManager ?: return
        if (resolving) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                log("resolve FAILED code $errorCode")
                resolving = false
                if (errorCode == FAILURE_ALREADY_ACTIVE) {
                    main.postDelayed({ enqueueResolve(info) }, 400)
                } else {
                    resolveNext()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                resolving = false
                serviceInfo?.let { handleResolved(it) }
                resolveNext()
            }
        })
    }

    private fun handleResolved(resolved: NsdServiceInfo) {
        // NSD often resolves to an IPv6 link-local address that the HTTP client
        // cannot use, so the sender advertises its IPv4 explicitly.
        val advertisedIp = resolved.attributes["ip"]?.let { String(it, Charsets.UTF_8) }
        val hostAddress = advertisedIp
            ?.takeIf { ip ->
                ip.isNotBlank() && runCatching {
                    (java.net.InetAddress.getByName(ip) as? Inet4Address)?.let { LocalNetwork.isUsableLanIpv4(it) } == true
                }.getOrDefault(false)
            }
            ?: resolved.host?.hostAddress?.substringBefore('%')
        if (hostAddress == null) { log("resolved but no usable address"); return }
        val colorStr = resolved.attributes["color"]?.let { String(it, Charsets.UTF_8) }
        val token = resolved.attributes["token"]?.let { String(it, Charsets.UTF_8) }
        if (colorStr == null || token == null) { log("resolved but missing color/token"); return }
        val color = try { Integer.parseUnsignedInt(colorStr, 16) } catch (_: Exception) { Color.BLUE }
        val fileCount = resolved.attributes["fileCount"]?.let { String(it, Charsets.UTF_8) }?.toIntOrNull() ?: 1

        log("sender $hostAddress:${resolved.port}")
        _colorHandshakeFlow.tryEmit(
            ColorHandshake(
                peerId = resolved.serviceName,
                displayColor = color,
                ip = hostAddress,
                port = resolved.port,
                token = token,
                fileCount = fileCount
            )
        )
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
