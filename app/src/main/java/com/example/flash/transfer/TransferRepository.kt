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
    /** Every file is saved; checksums are still being verified and arrive on [TransferRepository.fileVerifiedFlow]. */
    data class Complete(val files: List<DownloadedFile>) : TransferState
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

    // Not a StateFlow: results must never be conflated away (each file's verdict matters)
    private val _fileVerifiedFlow = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Int, Boolean>>(extraBufferCapacity = 64)
    val fileVerifiedFlow: kotlinx.coroutines.flow.SharedFlow<Pair<Int, Boolean>> = _fileVerifiedFlow

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

    fun startDownload(
        ip: String, port: Int, token: String, fileCount: Int, context: Context,
        indices: List<Int>? = null
    ) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                _progressFlow.value = 0f
                _transferState.value = TransferState.Downloading(0f)

                val destDir = File(context.cacheDir, "transfers")
                var files: List<DownloadedFile>? = null
                var lastError: Exception? = null
                // First over the real Wi-Fi network (bypasses a VPN), then over the default network.
                for (viaWifi in listOf(true, false)) {
                    val bound = viaWifi && LocalNetwork.bindProcessToWifi(context)
                    if (viaWifi && !bound) continue
                    try {
                        files = client.downloadAll(
                            ip, port, token, fileCount, destDir,
                            verifyScope = scope,
                            indices = indices ?: (0 until fileCount).toList(),
                            onProgress = { progress ->
                                _progressFlow.value = progress
                                _transferState.value = TransferState.Downloading(progress)
                            },
                            onFileVerified = { index, isValid ->
                                _fileVerifiedFlow.tryEmit(index to isValid)
                            }
                        )
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                    } finally {
                        if (bound) LocalNetwork.unbind(context)
                    }
                }

                val result = files
                if (result == null) {
                    val probe = LocalNetwork.probe(context, ip, port)
                    throw java.io.IOException("${lastError?.message ?: "Unknown error"}\n$probe")
                }
                _transferState.value = TransferState.Complete(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _transferState.value = TransferState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /** Emits on the sending phone once every served file has been sent in full. */
    val sendCompleteFlow get() = server.allDelivered

    /** 0..1 progress of what the sender has pushed out so far. */
    val sendProgressFlow get() = server.sendProgress

    /** Stops only the file server (a running download is left alone). */
    fun stopServing() {
        server.stop()
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
