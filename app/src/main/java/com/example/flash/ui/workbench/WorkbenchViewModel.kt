package com.example.flash.ui.workbench

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.flash.handshake.CameraHandshakeManager
import com.example.flash.handshake.ColorHandshake
import com.example.flash.nfc.NfcManager
import com.example.flash.nfc.PeerHandshake
import com.example.flash.transfer.TransferRepository
import com.example.flash.transfer.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface NfcUiState {
    object Idle         : NfcUiState
    object Advertising  : NfcUiState
    data class PeerDetected(val handshake: PeerHandshake) : NfcUiState
    object Transferring : NfcUiState
    object Complete     : NfcUiState
    data class Error(val message: String) : NfcUiState
}

enum class ColorDetectionState { Idle, Detecting, Locked }

data class WorkbenchUiState(
    val photos: List<Uri>        = emptyList(),
    val selectedPhotos: Set<Uri> = emptySet(),
    val nfcState: NfcUiState     = NfcUiState.Idle,
    val transferProgress: Float  = 0f,
    val isReceiving: Boolean     = false,
    val crystallizedPhotoUri: Uri? = null,
    val showRipple: Boolean      = false,
    val shouldExit: Boolean      = false,
    val receivedPhotos: List<Uri> = emptyList(),
    val receivingPhotos: List<Uri> = emptyList(),
    val corruptedPhotos: List<Uri> = emptyList(),
    val corruptedIndicesInOrbit: Set<Int> = emptySet(),
    val isWifiConnected: Boolean = false,
    val showHotspotPrompt: Boolean = false,
    val displayColor: Int? = null,
    val detectedPeerColor: ColorHandshake? = null,
    val colorDetectionState: ColorDetectionState = ColorDetectionState.Idle,
    val detectionStrength: Float = 0f,
    val cameraHandshakeEnabled: Boolean = false,
    val transferError: String? = null
)

private const val LOCK_DISPLAY_MS = 3500L

class WorkbenchViewModel(
    private val transferRepository: TransferRepository,
    private val nfcManager: NfcManager,
    cameraHandshakeManager: CameraHandshakeManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkbenchUiState())
    val uiState: StateFlow<WorkbenchUiState> = _uiState.asStateFlow()

    private var currentToken: String = ""
    private var currentIp: String = ""
    private var currentPort: Int = 0
    private val cameraHandshakeManager = cameraHandshakeManager
    private var nfcEnabled = true
    private var cameraEnabled = false

    init {
        nfcManager.peerHandshakeFlow
            .onEach { handshake -> onNfcPeerDetected(handshake) }
            .launchIn(viewModelScope)

        cameraHandshakeManager?.colorHandshakeFlow
            ?.onEach { handshake -> onColorPeerDetected(handshake) }
            ?.launchIn(viewModelScope)

        transferRepository.transferState
            .onEach { state -> onTransferStateChanged(state) }
            .launchIn(viewModelScope)

        transferRepository.progressFlow
            .onEach { progress ->
                _uiState.update { it.copy(transferProgress = progress) }
            }
            .launchIn(viewModelScope)

        transferRepository.fileVerifiedFlow
            .onEach { result ->
                if (result != null) {
                    val (index, isValid) = result
                    onPhotoVerified(index, isValid)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun onPhotoVerified(index: Int, isValid: Boolean) {
        if (!isValid) {
            _uiState.update {
                it.copy(corruptedIndicesInOrbit = it.corruptedIndicesInOrbit + index)
            }
        } else {
            val allReceiving = _uiState.value.receivingPhotos
            if (index < allReceiving.size) {
                val photoUri = allReceiving[index]
                viewModelScope.launch {
                    delay(100)
                    _uiState.update { state ->
                        state.copy(
                            photos = (state.photos + photoUri).distinct(),
                            receivingPhotos = state.receivingPhotos - photoUri
                        )
                    }
                }
            }
        }
    }

    private fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun updateWifiStatus(context: Context) {
        val connected = isWifiConnected(context)
        _uiState.update { it.copy(isWifiConnected = connected) }
        checkHotspotPromptVisibility()
    }

    private fun checkHotspotPromptVisibility() {
        val state = _uiState.value
        val shouldShow = state.selectedPhotos.isNotEmpty() && !state.isWifiConnected && !state.isReceiving
        _uiState.update { it.copy(showHotspotPrompt = shouldShow) }
    }

    fun dismissHotspotPrompt() {
        _uiState.update { it.copy(showHotspotPrompt = false) }
    }

    fun loadGalleryPhotos(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val photos = mutableListOf<Uri>()
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder  = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && photos.size < 60) {
                    photos.add(
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(col)
                        )
                    )
                }
            }
            _uiState.update { it.copy(photos = photos) }
        }
    }

    fun onPhotosSelected(uris: List<Uri>, context: Context) {
        _uiState.update {
            it.copy(
                photos = (it.photos + uris).distinct(),
                selectedPhotos = it.selectedPhotos + uris
            )
        }
        checkHotspotPromptVisibility()
        addToOrUpdateServer(uris)
    }

    fun onPhotoAddedToOrbit(uri: Uri, context: Context) {
        _uiState.update { it.copy(selectedPhotos = it.selectedPhotos + uri) }
        checkHotspotPromptVisibility()
        addToOrUpdateServer(listOf(uri))
    }

    fun onPhotoRemovedFromOrbit(uri: Uri) {
        _uiState.update { it.copy(selectedPhotos = it.selectedPhotos - uri) }
        checkHotspotPromptVisibility()
        if (_uiState.value.selectedPhotos.isEmpty()) {
            nfcManager.clearOutboundHandshake()
            cameraHandshakeManager?.stopAdvertising()
            currentPort = 0
        }
    }

    /** Publishes the sender's handshake over whichever channels the current mode allows. */
    private fun publishHandshakes(localIp: String) {
        if (nfcEnabled) {
            nfcManager.setOutboundHandshake(
                localIp, currentPort, currentToken, "en",
                transferRepository.servingFileCount
            )
        } else {
            nfcManager.clearOutboundHandshake()
        }
        if (cameraEnabled) {
            cameraHandshakeManager?.startAdvertising(
                displayColor = _uiState.value.displayColor ?: 0xFFFF0000.toInt(),
                ip = localIp,
                port = currentPort,
                token = currentToken,
                fileCount = transferRepository.servingFileCount
            )
        } else {
            cameraHandshakeManager?.stopAdvertising()
        }
    }

    /**
     * Applies the user's handshake mode. Safe to call repeatedly; re-publishes the
     * current handshake if a server is already running.
     */
    fun applyHandshakeMode(nfc: Boolean, camera: Boolean) {
        if (nfc == nfcEnabled && camera == cameraEnabled) return
        nfcEnabled = nfc
        cameraEnabled = camera
        _uiState.update { it.copy(cameraHandshakeEnabled = camera) }
        if (!camera) {
            cameraHandshakeManager?.stopDiscovery()
            _uiState.update {
                it.copy(
                    detectedPeerColor = null,
                    colorDetectionState = ColorDetectionState.Idle,
                    detectionStrength = 0f
                )
            }
        }
        if (currentPort != 0) {
            viewModelScope.launch {
                try {
                    publishHandshakes(transferRepository.getLocalIp(com.example.flash.FlashApplication.instance))
                } catch (_: Exception) { }
            }
        }
    }

    private fun addToOrUpdateServer(uris: List<Uri>) {
        viewModelScope.launch {
            try {
                val context = com.example.flash.FlashApplication.instance
                val localIp = transferRepository.getLocalIp(context)

                if (currentPort != 0) {
                    // Server already running — add files without restarting
                    uris.forEach { uri -> transferRepository.addFileToServing(currentToken, uri) }
                    publishHandshakes(localIp)
                } else {
                    // No server yet — start fresh
                    currentToken = UUID.randomUUID().toString()
                    currentIp = localIp
                    currentPort = transferRepository.startServing(currentToken, uris, context)
                    publishHandshakes(localIp)
                    _uiState.update { it.copy(nfcState = NfcUiState.Advertising) }
                }
            } catch (_: Exception) { }
        }
    }

    fun onNdefHandshakeReceived(message: android.nfc.NdefMessage) {
        nfcManager.handleNdefMessage(message)
    }

    fun onPhotoDraggedToCore(uri: Uri, context: Context) {
        _uiState.update { it.copy(selectedPhotos = it.selectedPhotos + uri) }
        checkHotspotPromptVisibility()
        addToOrUpdateServer(listOf(uri))
    }

    private fun onNfcPeerDetected(handshake: PeerHandshake) {
        if (nfcEnabled && _uiState.value.selectedPhotos.isEmpty()) {
            _uiState.update { it.copy(nfcState = NfcUiState.PeerDetected(handshake), isReceiving = true) }
        }
    }

    private fun onColorPeerDetected(handshake: ColorHandshake) {
        if (cameraEnabled && _uiState.value.selectedPhotos.isEmpty() &&
            _uiState.value.colorDetectionState != ColorDetectionState.Locked) {
            _uiState.update {
                it.copy(
                    detectedPeerColor = handshake,
                    colorDetectionState = ColorDetectionState.Detecting
                )
            }
        }
    }

    fun onDetectionStrengthChanged(strength: Float) {
        _uiState.update { it.copy(detectionStrength = strength) }
    }

    fun onColorLocked() {
        if (_uiState.value.colorDetectionState == ColorDetectionState.Locked) return
        _uiState.update { it.copy(colorDetectionState = ColorDetectionState.Locked) }
    }

    fun onColorConfirmed(context: Context) {
        val handshake = _uiState.value.detectedPeerColor ?: return
        _uiState.update { it.copy(isReceiving = true) }
        startDownload(
            PeerHandshake(
                ip = handshake.ip,
                port = handshake.port,
                token = handshake.token,
                lang = "en",
                fileCount = handshake.fileCount
            ),
            context
        )
        // Keep the locked color and the "scan complete" popup on screen long enough to read.
        viewModelScope.launch {
            delay(LOCK_DISPLAY_MS)
            _uiState.update {
                it.copy(
                    colorDetectionState = ColorDetectionState.Idle,
                    detectedPeerColor = null,
                    detectionStrength = 0f
                )
            }
        }
    }

    fun startDownload(handshake: PeerHandshake, context: Context) {
        transferRepository.startDownload(handshake.ip, handshake.port, handshake.token, handshake.fileCount, context)
        _uiState.update { it.copy(nfcState = NfcUiState.Transferring) }
    }

    private fun onTransferStateChanged(state: TransferState) {
        when (state) {
            is TransferState.Complete -> {
                val allFileUris = state.receivedFiles.map { file ->
                    android.net.Uri.fromFile(file)
                }
                val corruptedSet = state.corruptedIndices.toSet()
                val corruptedPhotos = allFileUris.filterIndexed { idx, _ -> idx in corruptedSet }

                _uiState.update {
                    it.copy(
                        nfcState = NfcUiState.Complete,
                        transferProgress = 1f,
                        showRipple = true,
                        receivingPhotos = allFileUris,
                        corruptedPhotos = corruptedPhotos
                    )
                }
            }
            is TransferState.Failed -> {
                _uiState.update {
                    it.copy(
                        nfcState = NfcUiState.Error(state.reason),
                        isReceiving = false,
                        transferError = state.reason
                    )
                }
                viewModelScope.launch {
                    // A failed transfer drops the receiver back to scanning; mDNS only reports a
                    // sender once per search, so restart the search to let it be found again.
                    delay(4000)
                    if (cameraEnabled && _uiState.value.selectedPhotos.isEmpty()) {
                        cameraHandshakeManager?.startDiscovery()
                    }
                    delay(4000)
                    _uiState.update { it.copy(transferError = null) }
                }
            }
            else -> {}
        }
    }

    fun onRippleComplete() {
        _uiState.update { it.copy(showRipple = false, isReceiving = false, transferProgress = 0f) }
        transferRepository.reset()
        currentPort = 0
    }

    fun onReceivingPhotosReadyForGallery() {
        val receiving = _uiState.value.receivingPhotos
        if (receiving.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    photos = (it.photos + receiving).distinct(),
                    receivingPhotos = emptyList()
                )
            }
        }
    }

    fun startGalleryTransitionForReceivingPhotos() {
        viewModelScope.launch {
            delay(2100L)  // Wait for gallery flight animation to complete
            onReceivingPhotosReadyForGallery()
        }
    }

    fun onExitRequested() {
        _uiState.update { it.copy(shouldExit = true) }
    }

    fun dismissCorruptionAlert() {
        _uiState.update { it.copy(corruptedPhotos = emptyList()) }
    }

    fun retryCorruptedPhotos(context: Context) {
        val currentState = _uiState.value
        if (currentState.corruptedPhotos.isNotEmpty()) {
            dismissCorruptionAlert()
            _uiState.update { it.copy(nfcState = NfcUiState.Idle) }
        }
    }

    fun initializeCameraHandshake() {
        val displayColor = cameraHandshakeManager?.generateDisplayColor() ?: 0xFFFF0000.toInt()
        _uiState.update { it.copy(displayColor = displayColor) }
    }

    fun startDiscoveringCameraPeers() {
        if (!cameraEnabled) return
        cameraHandshakeManager?.startDiscovery()
    }

    fun stopDiscoveringCameraPeers() {
        cameraHandshakeManager?.stopDiscovery()
    }

    fun dismissColorHandshake() {
        _uiState.update {
            it.copy(
                detectedPeerColor = null,
                colorDetectionState = ColorDetectionState.Idle,
                detectionStrength = 0f
            )
        }
    }
}
