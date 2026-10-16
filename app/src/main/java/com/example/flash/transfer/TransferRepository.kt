package com.example.flash.transfer

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import com.example.flash.util.LocalNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface TransferState {
    object Idle : TransferState
    data class Serving(val port: Int, val token: String) : TransferState
    data class Downloading(val progress: Float) : TransferState
    data class Complete(
        val receivedFiles: List<File>,
        val corruptedFiles: List<String> = emptyList(),
        val corruptedIndices: List<Int> = emptyList()
    ) : TransferState
    data class Failed(val reason: String) : TransferState
}

class TransferRepository(
    private val server: FileServer,
    private val client: FileClient,
    private val scope: CoroutineScope
) {

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private val _progressFlow = MutableStateFlow(0f)
    val progressFlow: StateFlow<Float> = _progressFlow.asStateFlow()

    private val _fileVerifiedFlow = MutableStateFlow<Pair<Int, Boolean>?>(null)
    val fileVerifiedFlow: StateFlow<Pair<Int, Boolean>?> = _fileVerifiedFlow.asStateFlow()

    private var downloadJob: Job? = null

    val servingFileCount: Int get() = server.fileCount

    suspend fun startServing(token: String, uris: List<Uri>, context: Context): Int {
        _transferState.value = TransferState.Idle
        val port = server.start(token, uris, context)
        _transferState.value = TransferState.Serving(port, token)
        return port
    }

    fun addFileToServing(token: String, uri: Uri) {
        server.addUri(token, uri)
    }

    fun startDownload(ip: String, port: Int, token: String, fileCount: Int, context: Context) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            // Go out over Wi-Fi even when a VPN is the default network
            val boundToWifi = LocalNetwork.bindProcessToWifi(context)
            try {
                _progressFlow.value = 0f
                _transferState.value = TransferState.Downloading(0f)

                val destDir = File(context.cacheDir, "transfers")
                val files = client.downloadAll(
                    ip, port, token, fileCount, destDir,
                    onProgress = { progress ->
                        _progressFlow.value = progress
                        _transferState.value = TransferState.Downloading(progress)
                    },
                    onFileVerified = { index, isValid ->
                        _fileVerifiedFlow.value = index to isValid
                    },
                    onCorrupted = { corrupted, indices ->
                        val current = _transferState.value
                        if (current is TransferState.Complete) {
                            _transferState.value = current.copy(corruptedFiles = corrupted, corruptedIndices = indices)
                        }
                    }
                )

                _transferState.value = TransferState.Complete(files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _transferState.value = TransferState.Failed(e.message ?: "Unknown error")
            } finally {
                if (boundToWifi) LocalNetwork.unbind(context)
            }
        }
    }

    fun stopAll() {
        downloadJob?.cancel()
        downloadJob = null
        server.stop()
        _transferState.value = TransferState.Idle
        _progressFlow.value = 0f
    }

    fun reset() {
        stopAll()
    }

    /**
     * This phone's IPv4 address on the local network, or "127.0.0.1" if none is found.
     * Uses the real Wi-Fi network even when a VPN is active (see [LocalNetwork]); the
     * deprecated `WifiManager.connectionInfo` is only the last resort.
     */
    fun getLocalIp(context: Context): String {
        LocalNetwork.localIpv4(context)?.let { return it }
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) "127.0.0.1"
            else "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }
}
