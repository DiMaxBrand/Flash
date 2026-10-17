package com.example.flash.ui.workbench

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import com.example.flash.util.LocalNetwork
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.flash.FlashApplication
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
    val incoming: List<IncomingPhoto> = emptyList(),   // photos on their way here (Expressive cards / Liquid session)
    val liquidSession: Boolean = false,                 // the Liquid receive session is running (photos handled one by one)
    val legacyReceive: Boolean = false,                 // the sender has no previews: old orbit flow
    val featuredIndex: Int? = null,                     // the photo currently materializing inside the core
    val explainIndex: Int? = null,                      // the user tapped a damaged photo
    val unverifiedCount: Int = 0,                       // photos that could not be proven intact after retries
    val sessionColor: Int? = null,                   // the sender's colour, kept for the whole receive session
    val landingPhotos: Set<Uri> = emptySet(),        // good photos currently flying into the grid
    val rejectedPhotos: List<Uri> = emptyList(),     // failed their checksum: red, parked in the core
    val isWifiConnected: Boolean = false,
    val showHotspotPrompt: Boolean = false,
    val displayColor: Int? = null,
    val detectedPeerColor: ColorHandshake? = null,
    val colorDetectionState: ColorDetectionState = ColorDetectionState.Idle,
    val detectionStrength: Float = 0f,
    val cameraHandshakeEnabled: Boolean = false,
    val transferError: String? = null,
    val infoMessage: String? = null,
    val sendProgress: Float = 0f
)

private const val LOCK_DISPLAY_MS = 3500L
private const val ORBIT_MIN_MS = 3000L

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

        transferRepository.sendCompleteFlow
            .onEach { onAllFilesDelivered() }
            .launchIn(viewModelScope)

        transferRepository.sendProgressFlow
            .onEach { p ->
                val s = _uiState.value
                // Only meaningful on the sending phone (photos selected, not receiving)
                if (s.selectedPhotos.isNotEmpty() && !s.isReceiving && p != s.sendProgress) {
                    _uiState.update { it.copy(sendProgress = p) }
                }
            }
            .launchIn(viewModelScope)

        transferRepository.fileDownloadedFlow
            .onEach { file ->
                val uri = Uri.fromFile(file.file)
                downloadedUris[file.index] = uri
                updateIncoming(file.index) { it.copy(originalUri = uri) }
            }
            .launchIn(viewModelScope)

        // Checksum verdicts arrive while the receiving animation plays; they are only recorded here
        // and acted on once the photos have orbited for ORBIT_MIN_MS (see settleReceivedPhotos).
        transferRepository.fileVerifiedFlow
            .onEach { (index, isValid) ->
                verifyResults[index] = isValid
                updateIncoming(index) { it.copy(verified = isValid) }
            }
            .launchIn(viewModelScope)
    }

    private val verifyResults = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val downloadedUris = java.util.concurrent.ConcurrentHashMap<Int, Uri>()
    private var metaReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
    private var sequenceJob: kotlinx.coroutines.Job? = null
    private val revealWaiters = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.CompletableDeferred<Unit>>()

    // ── Expressive cards: previews and bands fetched from the sender while the original downloads ──
    @Volatile private var holdLanding = false
    @Volatile private var revealDone = false
    private var previewJob: kotlinx.coroutines.Job? = null

    /** The Expressive screen shows reveal cards and tells us (onRevealFinished) when they are done. */
    fun setHoldLanding(hold: Boolean) { holdLanding = hold }
    fun onRevealFinished() { revealDone = true }

    private fun updateIncoming(index: Int, change: (IncomingPhoto) -> IncomingPhoto) {
        _uiState.update { s ->
            if (s.incoming.none { it.index == index }) s
            else s.copy(incoming = s.incoming.map { if (it.index == index) change(it) else it })
        }
    }

    private fun launchPreviews(handshake: PeerHandshake, indices: List<Int>?) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            val app = FlashApplication.instance
            val bound = LocalNetwork.bindProcessToWifi(app)
            try {
                val meta = transferRepository.fetchMeta(handshake.ip, handshake.port, handshake.token)
                if (meta == null) {
                    // An older sender without previews: the old orbit flow takes over
                    _uiState.update { it.copy(legacyReceive = true) }
                    metaReady.complete(false)
                    return@launch
                }
                val wanted = meta.filter { indices == null || it.index in indices }
                val liquid = !holdLanding
                val list = wanted.map { m ->
                    IncomingPhoto(
                        index = m.index, width = m.width, height = m.height,
                        previewWidth = m.previewWidth, previewHeight = m.previewHeight, bandCount = m.bandCount,
                        thumb = BitmapFactory.decodeByteArray(m.thumb, 0, m.thumb.size),
                        // Expressive: one or two photos are shown big right away. Liquid: only the photo
                        // currently starring in the core asks for bands.
                        open = !liquid && wanted.size <= 2,
                        // Whatever the original download has already reported
                        originalUri = downloadedUris[m.index],
                        verified = verifyResults[m.index]
                    )
                }
                _uiState.update { it.copy(incoming = list, legacyReceive = false, liquidSession = liquid) }
                metaReady.complete(true)
                if (liquid) {
                    runLiquidSequence(handshake, list.map { it.index }.sorted())
                } else {
                    kotlinx.coroutines.coroutineScope {
                        list.filter { it.open }.forEach { p -> launch { fetchBands(handshake, p) } }
                    }
                }
            } finally {
                if (bound) LocalNetwork.unbind(app)
            }
        }
    }

    // ───────────────────────── Liquid receive session ─────────────────────────
    // Photos are handled one by one, in order:
    //   1-3  full animation (materialize in the core from the bands, then fly to their cell), sequential
    //   4-5  the same, a bit faster, still sequential
    //   6-10 faster and concurrent, by readiness; the first of them still shows briefly in the core
    //   11+  just appear in their cell when ready
    // A photo that fails its checksum is parked in its cell (blurred, red) and retries itself.

    private fun photoOf(index: Int) = _uiState.value.incoming.firstOrNull { it.index == index }

    private fun setStage(index: Int, stage: Stage) = updateIncoming(index) { it.copy(stage = stage) }

    /** Waits until a photo's original is complete and its verdict is known. True = intact. */
    private suspend fun awaitDecided(index: Int): Boolean {
        var verdict: Boolean? = null
        kotlinx.coroutines.withTimeoutOrNull(120_000) {
            while (true) {
                val p = photoOf(index)
                if (p?.originalUri != null && p.verified != null) { verdict = p.verified; break }
                delay(40)
            }
        }
        return verdict == true
    }

    private suspend fun runLiquidSequence(handshake: PeerHandshake, order: List<Int>) {
        order.forEachIndexed { pos, idx ->
            val tier = when { pos < 3 -> 0; pos < 5 -> 1; pos < 10 -> 2; else -> 3 }
            updateIncoming(idx) { it.copy(tier = tier) }
        }
        val rest = ArrayList<kotlinx.coroutines.Job>()
        kotlinx.coroutines.coroutineScope {
            // The stars: strictly one after the other
            for ((pos, idx) in order.withIndex()) {
                if (pos >= 5) break
                if (!awaitDecided(idx)) { parkDamaged(handshake, idx); continue }
                updateIncoming(idx) { it.copy(open = true) }
                launch(Dispatchers.IO) { fetchBands(handshake, photoOf(idx) ?: return@launch) }
                showInCore(idx)
                delay(if (pos < 3) 450 else 250)   // let it clear the core before the next one starts
            }
            // The rest: concurrent, by readiness
            order.drop(5).forEachIndexed { offset, idx ->
                rest += launch {
                    if (!awaitDecided(idx)) { parkDamaged(handshake, idx); return@launch }
                    when {
                        offset == 0 -> { showInCore(idx) }                   // still show the first of the batch
                        offset < 5 -> setStage(idx, Stage.FLYING)              // quick flight from the core
                        else -> setStage(idx, Stage.LANDED)                    // just appears
                    }
                }
            }
        }
        rest.forEach { it.join() }
        // Wait for flights to finish and retries to resolve
        kotlinx.coroutines.withTimeoutOrNull(60_000) {
            while (_uiState.value.incoming.any { it.stage != Stage.LANDED }) delay(100)
        }
        finishLiquidSession()
    }

    /** The photo materializes inside the core (UI) and reports back through [onFeaturedRevealed]. */
    private suspend fun showInCore(index: Int) {
        val waiter = kotlinx.coroutines.CompletableDeferred<Unit>()
        revealWaiters[index] = waiter
        setStage(index, Stage.FEATURED)
        _uiState.update { it.copy(featuredIndex = index) }
        kotlinx.coroutines.withTimeoutOrNull(60_000) { waiter.await() }
        _uiState.update { it.copy(featuredIndex = null) }
        setStage(index, Stage.FLYING)
    }

    fun onFeaturedRevealed(index: Int) { revealWaiters.remove(index)?.complete(Unit) }

    /** The flying photo reached its cell. */
    fun onFlightFinished(index: Int) = setStage(index, Stage.LANDED)

    /** A photo failed its checksum: it waits in its cell (blurred, red) while it retries itself up to 5 times. */
    private suspend fun parkDamaged(handshake: PeerHandshake, index: Int) {
        setStage(index, Stage.PARKED)
        val app = FlashApplication.instance
        repeat(5) { attempt ->
            updateIncoming(index) { it.copy(retries = attempt + 1) }
            delay(700)
            val result = transferRepository.retryFile(handshake.ip, handshake.port, handshake.token, index, app)
            if (result != null) {
                val (file, valid) = result
                updateIncoming(index) { it.copy(originalUri = Uri.fromFile(file.file)) }
                if (valid) {
                    updateIncoming(index) { it.copy(verified = true, stage = Stage.LANDED) }
                    return
                }
            }
        }
        // Still not provable: show it as received, flag it at the end
        updateIncoming(index) { it.copy(unverifiable = true, stage = Stage.LANDED) }
        _uiState.update { it.copy(unverifiedCount = it.unverifiedCount + 1) }
    }

    fun explainDamaged(index: Int) = _uiState.update { it.copy(explainIndex = index) }
    fun dismissExplain() = _uiState.update { it.copy(explainIndex = null) }
    fun dismissUnverified() = _uiState.update { it.copy(unverifiedCount = 0) }

    private suspend fun finishLiquidSession() {
        val uris = _uiState.value.incoming.sortedBy { it.index }.mapNotNull { it.originalUri }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { uris.count { uri -> uri.path?.let { saveToGallery(File(it)) } == true } }
            if (saved > 0) {
                _uiState.update { it.copy(infoMessage = "Saved to Pictures/Flash in your gallery") }
                delay(5000)
                _uiState.update { it.copy(infoMessage = null) }
            }
        }
        // The session ends: the UI plays its end animations (core springs back, colour fades to the camera)
        _uiState.update { it.copy(photos = (uris + it.photos).distinct(), liquidSession = false, isReceiving = false, transferProgress = 0f) }
        transferRepository.reset()
        currentPort = 0
        delay(1600)
        _uiState.update { it.copy(incoming = emptyList(), sessionColor = null, featuredIndex = null) }
    }

    /** Fetches the bands a big card needs, bottom first. Photos that are not open never request any. */
    private suspend fun fetchBands(handshake: PeerHandshake, photo: IncomingPhoto) {
        val bandHeight = (photo.previewHeight + photo.bandCount - 1) / photo.bandCount
        for (n in 0 until photo.bandCount) {
            val have = _uiState.value.incoming.firstOrNull { it.index == photo.index }?.bands?.containsKey(n) == true
            if (have) continue
            val bytes = transferRepository.fetchBand(handshake.ip, handshake.port, handshake.token, photo.index, n) ?: break
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: break
            val yBottom = photo.previewHeight - n * bandHeight
            val yTop = maxOf(0, yBottom - bandHeight)
            updateIncoming(photo.index) { it.copy(bands = it.bands + (n to BandImage(n, yTop, yBottom - yTop, bitmap))) }
        }
    }

    /** Tapping a tray photo opens it big; only now are its bands requested. */
    fun openIncoming(index: Int) {
        val photo = _uiState.value.incoming.firstOrNull { it.index == index } ?: return
        updateIncoming(index) { it.copy(open = true) }
        val handshake = lastDownload ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val app = FlashApplication.instance
            val bound = LocalNetwork.bindProcessToWifi(app)
            try { fetchBands(handshake, photo) } finally { if (bound) LocalNetwork.unbind(app) }
        }
    }

    fun closeIncoming(index: Int) = updateIncoming(index) { it.copy(open = false) }
    private var lastDownload: PeerHandshake? = null
    private var failedIndices: List<Int> = emptyList()
    private var receiveJob: kotlinx.coroutines.Job? = null

    /**
     * Sender side: every file has been sent in full. Play the success pulse (photos collapse into
     * the core), then clear the selection and stop advertising. The server stays up for a minute
     * so a receiver whose checksum failed can still retry.
     */
    private fun onAllFilesDelivered() {
        val state = _uiState.value
        if (state.selectedPhotos.isEmpty() || state.isReceiving) return
        currentPort = 0   // the next selection starts a fresh server and token
        viewModelScope.launch {
            _uiState.update { it.copy(transferProgress = 1f, infoMessage = "Photos delivered") }
            delay(2600)   // let the success pulse play
            _uiState.update {
                it.copy(
                    selectedPhotos = emptySet(),
                    transferProgress = 0f,
                    sendProgress = 0f,
                    showHotspotPrompt = false,
                    infoMessage = null
                )
            }
            nfcManager.clearOutboundHandshake()
            cameraHandshakeManager?.stopAdvertising()
            delay(60_000)
            val now = _uiState.value
            if (currentPort == 0 && now.selectedPhotos.isEmpty() && !now.isReceiving) {
                transferRepository.stopServing()
            }
        }
    }

    /**
     * Receiving phone: the photos orbit for at least [ORBIT_MIN_MS] while their checksums are
     * verified in parallel. Then good photos fly into the grid (making room for them) and photos
     * that failed turn red and move back into the Mother Core.
     */
    private suspend fun settleReceivedPhotos(uris: List<Uri>, indices: List<Int>) {
        if (holdLanding) {
            // Expressive: the reveal cards decide when they are finished (with a safety timeout)
            kotlinx.coroutines.withTimeoutOrNull(90_000) { while (!revealDone) delay(100) }
        } else {
            delay(ORBIT_MIN_MS)
        }
        // Verification started when the download finished; wait only if it is somehow still running
        kotlinx.coroutines.withTimeoutOrNull(10_000) {
            while (indices.any { !verifyResults.containsKey(it) }) delay(50)
        }
        val good = uris.filterIndexed { i, _ -> verifyResults[indices[i]] != false }
        val bad = uris.filterIndexed { i, _ -> verifyResults[indices[i]] == false }
        failedIndices = indices.filter { verifyResults[it] == false }

        if (good.isNotEmpty()) {
            // Space opens in the grid (animated) while the photos fly in; copies go to the gallery
            _uiState.update { it.copy(photos = (good + it.photos).distinct(), landingPhotos = good.toSet()) }
            viewModelScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    good.count { uri -> uri.path?.let { saveToGallery(File(it)) } == true }
                }
                if (saved > 0) {
                    _uiState.update { it.copy(infoMessage = "Saved to Pictures/Flash in your gallery") }
                    delay(5000)
                    _uiState.update { it.copy(infoMessage = null) }
                }
            }
        }
        if (bad.isNotEmpty()) {
            _uiState.update { it.copy(rejectedPhotos = bad, corruptedPhotos = bad) }
        }
        if (good.isEmpty() && bad.isEmpty()) finishReceiveSession()
    }

    /** A flying photo reached its grid cell. */
    fun onPhotoLanded(uri: Uri) {
        _uiState.update { it.copy(landingPhotos = it.landingPhotos - uri, receivingPhotos = it.receivingPhotos - uri) }
        finishReceiveSession()
    }

    private fun finishReceiveSession() {
        val s = _uiState.value
        if (s.landingPhotos.isEmpty() && s.rejectedPhotos.isEmpty() && s.receivingPhotos.isEmpty()) {
            previewJob?.cancel()
            _uiState.update { it.copy(isReceiving = false, transferProgress = 0f, sessionColor = null, incoming = emptyList()) }
        }
    }

    private fun deleteFailedFiles() {
        _uiState.value.rejectedPhotos.forEach { uri -> uri.path?.let { runCatching { File(it).delete() } } }
    }

    /**
     * True when this phone is on a local network another phone could reach: joined to Wi-Fi
     * (even with a VPN on top) or hosting a hotspot. Polled by the screen, so the prompt
     * follows Wi-Fi / hotspot changes instead of showing stale state.
     */
    private fun hasLocalNetwork(context: Context): Boolean = LocalNetwork.localIpv4(context) != null

    private var hotspotPromptDismissed = false
    private val savedToGallery = mutableSetOf<String>()

    fun updateWifiStatus(context: Context) {
        val connected = hasLocalNetwork(context)
        if (connected) hotspotPromptDismissed = false   // ask again next time it drops
        if (connected != _uiState.value.isWifiConnected) {
            _uiState.update { it.copy(isWifiConnected = connected) }
        }
        checkHotspotPromptVisibility()
    }

    private fun checkHotspotPromptVisibility() {
        val state = _uiState.value
        val shouldShow = state.selectedPhotos.isNotEmpty() && !state.isWifiConnected &&
            !state.isReceiving && !hotspotPromptDismissed
        if (shouldShow != state.showHotspotPrompt) {
            _uiState.update { it.copy(showHotspotPrompt = shouldShow) }
        }
    }

    fun dismissHotspotPrompt() {
        hotspotPromptDismissed = true
        _uiState.update { it.copy(showHotspotPrompt = false) }
    }

    /** Android 8-9: write into Pictures/Flash directly (needs WRITE_EXTERNAL_STORAGE) and let the gallery scan it. */
    @Suppress("DEPRECATION")
    private fun saveToGalleryLegacy(file: File): Boolean = try {
        val app = FlashApplication.instance
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            false
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Flash")
            dir.mkdirs()
            val name = file.name.replaceFirst(Regex("^\\d+_"), "")
            var target = File(dir, name)
            var n = 1
            while (target.exists()) { target = File(dir, "${target.nameWithoutExtension}_${n++}.${target.extension}") }
            file.copyTo(target)
            android.media.MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), null, null)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** Copies a received photo into MediaStore (Pictures/Flash). Android 10+ needs no permission. */
    private fun saveToGallery(file: File): Boolean {
        if (!file.exists() || !savedToGallery.add(file.absolutePath)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return saveToGalleryLegacy(file)
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val originalName = file.name.replaceFirst(Regex("^\\d+_"), "")
            val mime = bounds.outMimeType
                ?: URLConnection.guessContentTypeFromName(originalName)
                ?: "image/jpeg"
            val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "image/heic" -> "heic"; else -> "jpg" }
            val name = if (originalName.contains('.')) originalName else "$originalName.$ext"
            val resolver = FlashApplication.instance.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Flash")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw java.io.IOException("no output stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        } catch (e: Exception) {
            false
        }
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
        // The core keeps the sender's colour until the whole receive session is over
        _uiState.update { it.copy(isReceiving = true, sessionColor = handshake.displayColor) }
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

    fun startDownload(handshake: PeerHandshake, context: Context, indices: List<Int>? = null) {
        lastDownload = handshake
        verifyResults.clear()
        downloadedUris.clear()
        metaReady = kotlinx.coroutines.CompletableDeferred()
        sequenceJob?.cancel()
        transferRepository.startDownload(handshake.ip, handshake.port, handshake.token, handshake.fileCount, context, indices)
        launchPreviews(handshake, indices)
        _uiState.update { it.copy(nfcState = NfcUiState.Transferring) }
    }

    private fun onTransferStateChanged(state: TransferState) {
        when (state) {
            is TransferState.Complete -> viewModelScope.launch {
                // The preview meta decides which flow runs: the Liquid session sequencer (it already
                // follows every file by itself) or the older orbit flow.
                kotlinx.coroutines.withTimeoutOrNull(4000) { metaReady.await() }
                if (_uiState.value.liquidSession) {
                    _uiState.update {
                        it.copy(nfcState = NfcUiState.Complete, transferProgress = 1f, showRipple = false)
                    }
                    return@launch
                }
                val uris = state.files.map { Uri.fromFile(it.file) }
                val indices = state.files.map { it.index }
                revealDone = false
                _uiState.update {
                    it.copy(
                        incoming = it.incoming.map { p ->
                            val file = state.files.firstOrNull { f -> f.index == p.index }
                            if (file != null) p.copy(originalUri = Uri.fromFile(file.file)) else p
                        },
                        nfcState = NfcUiState.Complete,
                        transferProgress = 1f,
                        showRipple = true,
                        receivingPhotos = uris,
                        landingPhotos = emptySet(),
                        rejectedPhotos = emptyList(),
                        corruptedPhotos = emptyList()
                    )
                }
                receiveJob?.cancel()
                receiveJob = viewModelScope.launch { settleReceivedPhotos(uris, indices) }
            }
            is TransferState.Failed -> {
                _uiState.update {
                    it.copy(
                        nfcState = NfcUiState.Error(state.reason),
                        isReceiving = false,
                        sessionColor = null,
                        incoming = emptyList(),
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
        // The ripple is only decoration; the receive session ends when every photo has landed in
        // the grid or the rejected ones were dismissed (finishReceiveSession).
        _uiState.update { it.copy(showRipple = false) }
        transferRepository.reset()
        currentPort = 0
    }

    fun onExitRequested() {
        _uiState.update { it.copy(shouldExit = true) }
    }

    /** "Skip": forget the damaged photos (their files are deleted). */
    fun dismissCorruptionAlert() {
        deleteFailedFiles()
        _uiState.update {
            it.copy(
                corruptedPhotos = emptyList(),
                receivingPhotos = it.receivingPhotos - it.rejectedPhotos.toSet(),
                rejectedPhotos = emptyList()
            )
        }
        failedIndices = emptyList()
        finishReceiveSession()
    }

    /** "Retry": download only the damaged files again from the sender (its server stays up for a minute). */
    fun retryCorruptedPhotos(context: Context) {
        val handshake = lastDownload
        val indices = failedIndices
        if (handshake == null || indices.isEmpty()) {
            dismissCorruptionAlert()
            return
        }
        deleteFailedFiles()
        _uiState.update {
            it.copy(
                corruptedPhotos = emptyList(),
                receivingPhotos = it.receivingPhotos - it.rejectedPhotos.toSet(),
                rejectedPhotos = emptyList(),
                isReceiving = true
            )
        }
        failedIndices = emptyList()
        startDownload(handshake, context, indices)
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
