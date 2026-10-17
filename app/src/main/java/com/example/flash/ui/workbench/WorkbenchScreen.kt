package com.example.flash.ui.workbench

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.flash.FlashApplication
import com.example.flash.R
import com.example.flash.handshake.MotherCoreViewfinder
import com.example.flash.handshake.ScanCompletePopup
import com.example.flash.nfc.NfcManager
import com.example.flash.ui.workbench.ColorDetectionState
import com.example.flash.transfer.TransferRepository
import com.example.flash.ui.core.MotherCore
import com.example.flash.ui.shader.RippleOverlay
import com.example.flash.ui.theme.OceanAqua
import com.example.flash.ui.theme.HandshakeMode
import com.example.flash.ui.theme.ThemeMode
import com.example.flash.util.DeviceDetector
import com.example.flash.ui.theme.ThemeRepository
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.ui.zIndex
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Blob size constants mirrored from MotherCore (keep in sync)
private const val BLOB_BASE_RADIUS_DP  = 60f
private const val BLOB_NOISE_OFFSET_DP = 14f
private const val BLOB_PADDING_DP      = 32f
private val BLOB_SIZE_DP = (BLOB_BASE_RADIUS_DP * 2 + BLOB_NOISE_OFFSET_DP * 2 + BLOB_PADDING_DP).dp

// Golden angle for orbit phase assignment (mirrored from PhotoOrbit)
private val GOLDEN_ANGLE = (kotlin.math.PI * (3.0 - kotlin.math.sqrt(5.0))).toFloat()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    transferRepository: TransferRepository,
    nfcManager: NfcManager,
    cameraHandshakeManager: com.example.flash.handshake.CameraHandshakeManager? = null,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view    = LocalView.current
    val app     = context.applicationContext as FlashApplication

    val viewModel: WorkbenchViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WorkbenchViewModel(transferRepository, nfcManager, cameraHandshakeManager) as T
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // ── Handshake mode (Settings): which channels pair the two phones ───────
    // AUTO = NFC always, plus camera on HyperOS. NFC = NFC only. CAMERA = camera only.
    val handshakeMode by app.themeRepository.handshakeMode
        .collectAsStateWithLifecycle(HandshakeMode.AUTO)
    val nfcOn = handshakeMode != HandshakeMode.CAMERA
    val cameraOn = handshakeMode == HandshakeMode.CAMERA ||
        (handshakeMode == HandshakeMode.AUTO && DeviceDetector.isHyperOSDevice())

    LaunchedEffect(nfcOn, cameraOn) {
        viewModel.applyHandshakeMode(nfc = nfcOn, camera = cameraOn)
    }

    // ── Camera permission: asked up front as soon as camera pairing is active ─
    var cameraGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> cameraGranted = granted }
    LaunchedEffect(cameraOn) {
        if (cameraOn && !cameraGranted) {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // ── Initialize camera handshake on first load ───────────────────────────
    LaunchedEffect(Unit) {
        viewModel.initializeCameraHandshake()
    }

    // ── Start discovery when in receiver mode ────────────────────────────────
    LaunchedEffect(uiState.selectedPhotos.isEmpty(), uiState.cameraHandshakeEnabled) {
        if (uiState.selectedPhotos.isEmpty() && uiState.cameraHandshakeEnabled) {
            viewModel.startDiscoveringCameraPeers()
        } else {
            viewModel.stopDiscoveringCameraPeers()
        }
    }

    // ── NFC Foreground Dispatch (intercepts NFC before system dialog) ────────
    // Enable foreground dispatch ALWAYS when screen is visible, regardless of mode
    // This prevents Android's NFC app selector dialog from interfering with the handshake
    LaunchedEffect(nfcOn) {
        val act = context as? android.app.Activity ?: return@LaunchedEffect
        if (!nfcOn) {
            nfcManager.disableForegroundDispatch(act)
            return@LaunchedEffect
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, act::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        )
        // Enable foreground dispatch in both send and receive modes
        nfcManager.enableForegroundDispatch(act, pendingIntent, filters)
    }

    // ── NFC Preferred Service (receiver mode only) ──────────────────────────
    // When receiving, set Flash as the preferred HCE service to override system NFC routing
    LaunchedEffect(uiState.isReceiving) {
        val act = context as? android.app.Activity ?: return@LaunchedEffect
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return@LaunchedEffect

        if (uiState.isReceiving && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val cardEmulation = CardEmulation.getInstance(adapter)
                val hostApduService = ComponentName(context, "com.example.flash.nfc.HandshakeHceService")
                cardEmulation.setPreferredService(act, hostApduService)
            } catch (e: Exception) {
                android.util.Log.w("Flash", "Could not set preferred NFC service", e)
            }
        } else if (!uiState.isReceiving) {
            try {
                val cardEmulation = CardEmulation.getInstance(adapter)
                cardEmulation.unsetPreferredService(act)
            } catch (e: Exception) {
                // Device may not support this
            }
        }
    }

    // ── Permission + auto-load camera roll ───────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) viewModel.loadGalleryPhotos(context)
    }
    LaunchedEffect(Unit) {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val allGranted = needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) viewModel.loadGalleryPhotos(context) else permissionLauncher.launch(needed)
    }

    // ── Zero-click NFC download ──────────────────────────────────────────────
    LaunchedEffect(uiState.nfcState) {
        val s = uiState.nfcState
        if (s is NfcUiState.PeerDetected) viewModel.startDownload(s.handshake, context)
    }

    // ── Camera cutout → blob-local offset ────────────────────────────────────
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val blobSizePx     = with(density) { BLOB_SIZE_DP.toPx() }

    val cutoutOffset: Offset = remember(view, statusBarTopPx, blobSizePx) {
        val cutoutCenterInWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val rect = view.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull()
            if (rect != null) Offset(rect.centerX().toFloat(), rect.centerY().toFloat())
            else Offset(view.width / 2f, statusBarTopPx / 2f)
        } else {
            Offset(view.width / 2f, statusBarTopPx / 2f)
        }
        val cutoutLocal = cutoutCenterInWindow - Offset(0f, statusBarTopPx)
        val blobCenter  = Offset(view.width / 2f, blobSizePx / 2f)
        cutoutLocal - blobCenter
    }

    var coreCenter   by remember { mutableStateOf(Offset.Zero) }
    var showSettings by remember { mutableStateOf(false) }
    var shouldStartGalleryTransition by remember { mutableStateOf(false) }

    var exitEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600L); exitEnabled = true }

    // Auto-trigger gallery transition after receiving photos settle in orbit (~3 seconds)
    LaunchedEffect(uiState.receivingPhotos) {
        if (uiState.receivingPhotos.isNotEmpty() && !shouldStartGalleryTransition) {
            delay(3000L)  // Let them orbit for a bit
            shouldStartGalleryTransition = true
            viewModel.startGalleryTransitionForReceivingPhotos()
        }
    }

    val screenExitY = remember { Animatable(0f) }
    var screenExitStarted by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.shouldExit) {
        if (uiState.shouldExit && !screenExitStarted) {
            screenExitStarted = true
            screenExitY.animateTo(
                targetValue = with(density) { 1200.dp.toPx() },
                animationSpec = tween(3000, easing = FastOutSlowInEasing)
            )
        }
    }

    // Close settings panel on back press
    BackHandler(enabled = showSettings) { showSettings = false }

    val photoPicker      = rememberPhotoPicker { uris -> viewModel.onPhotosSelected(uris, context) }

    // ── Single shared backdrop — grid is the capture source for all glass ───
    val backdrop = rememberLayerBackdrop()

    val bgAlpha by animateFloatAsState(
        targetValue = if (uiState.shouldExit) 0f else 1f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "bg_alpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Sliding content (everything except MotherCore) ───────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = screenExitY.value }
                .background(
                    color = MaterialTheme.colorScheme.background.copy(alpha = bgAlpha)
                )
                .systemBarsPadding()
        ) {
        // ── Full-screen photo grid — source for all glass effects ────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement   = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            items(uiState.photos) { uri ->
                PhotoGridItem(
                    uri       = uri,
                    isInOrbit = uri in uiState.selectedPhotos,
                    onTap = {
                        if (uri in uiState.selectedPhotos) viewModel.onPhotoRemovedFromOrbit(uri)
                        else                               viewModel.onPhotoAddedToOrbit(uri, context)
                    }
                )
            }
        }

        // ── Orbiting selected photos & received photos ─────────────────────────
        PhotoOrbit(
            photos           = uiState.selectedPhotos.toList(),
            coreCenter       = coreCenter,
            receivingPhotos  = uiState.receivingPhotos,
            transferProgress = uiState.transferProgress,
            shouldExit       = uiState.shouldExit,
            corruptedIndices = uiState.corruptedIndicesInOrbit
        )

        // ── Received photos materializing into orbit ─────────────────────────
        uiState.receivingPhotos.forEachIndexed { index, uri ->
            ReceivedPhotoMaterializeFlyer(
                uri = uri,
                coreCenter = coreCenter,
                phaseOffset = index * (2f * kotlin.math.PI.toFloat() / uiState.receivingPhotos.size)
                    + (uiState.selectedPhotos.size * GOLDEN_ANGLE)  // offset by existing photos
            )
        }

        // ── Received photos flying from orbit into gallery ────────────────────
        if (uiState.receivingPhotos.isNotEmpty() && shouldStartGalleryTransition) {
            uiState.receivingPhotos.forEachIndexed { index, uri ->
                ReceivedPhotoOrbitToGalleryFlyer(
                    uri = uri,
                    coreCenter = coreCenter,
                    gridItemIndex = index
                )
            }
        }

        // ── Auto-confirm when color locks (no user tap needed) ───────────────
        LaunchedEffect(uiState.colorDetectionState) {
            if (uiState.colorDetectionState == ColorDetectionState.Locked) {
                viewModel.onColorConfirmed(context)  // starts the download right away
            }
        }

        // ── Bottom area: two buttons or settings panel ───────────────────────
        SharedTransitionLayout(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            AnimatedContent(
                targetState = showSettings,
                transitionSpec = {
                    fadeIn(tween(500, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(350, easing = FastOutSlowInEasing))
                },
                label = "settings_transform"
            ) { isOpen ->
                if (!isOpen) {
                    // ── Two liquid buttons with spacer ───────────────────────
                    Row(
                        modifier = Modifier.padding(bottom = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Exit button
                        LiquidButton(
                            onClick      = { viewModel.onExitRequested() },
                            backdrop     = backdrop,
                            enabled      = exitEnabled,
                            surfaceColor = OceanAqua.copy(alpha = 0.25f),
                            modifier     = Modifier.width(128.dp)
                        ) {
                            Text(
                                text  = stringResource(R.string.exit_button),
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        // Settings button — sharedBounds connects it to the panel
                        LiquidButton(
                            onClick      = { showSettings = true },
                            backdrop     = backdrop,
                            enabled      = true,
                            surfaceColor = OceanAqua.copy(alpha = 0.25f),
                            modifier     = Modifier
                                .width(128.dp)
                                .sharedBounds(
                                    rememberSharedContentState(key = "settings-panel"),
                                    animatedVisibilityScope = this@AnimatedContent,
                                    enter = fadeIn(tween(500)),
                                    exit  = fadeOut(tween(350))
                                )
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.cd_settings),
                                tint               = Color.White
                            )
                        }
                    }
                } else {
                    // ── Settings panel expands from the button ───────────────
                    SettingsPanel(
                        themeRepository = app.themeRepository,
                        backdrop        = backdrop,
                        onClose         = { showSettings = false },
                        modifier        = Modifier
                            .fillMaxWidth()
                            .sharedBounds(
                                rememberSharedContentState(key = "settings-panel"),
                                animatedVisibilityScope = this@AnimatedContent,
                                enter = fadeIn(tween(500)),
                                exit  = fadeOut(tween(350))
                            )
                    )
                }
            }
        }

        // ── "+" liquid glass button — hidden when settings panel is open ─────
        AnimatedVisibility(
            visible = !showSettings,
            enter   = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.85f),
            exit    = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 88.dp)
        ) {
            LiquidButton(
                onClick      = { photoPicker.launch() },
                backdrop     = backdrop,
                surfaceColor = OceanAqua.copy(alpha = 0.30f),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.cd_add_photos),
                    tint = Color.White
                )
            }
        }

        // ── AGSL ripple on transfer complete ─────────────────────────────────
        if (uiState.showRipple) {
            RippleOverlay(
                coreCenter = coreCenter,
                onComplete = { viewModel.onRippleComplete() }
            )
        }

        // ── Update Wi-Fi status periodically ────────────────────────────────────
        LaunchedEffect(Unit) {
            while (true) {
                viewModel.updateWifiStatus(context)
                delay(1500)
            }
        }

        // ── Hotspot prompt modal ────────────────────────────────────────────────
        HotspotPromptModal(
            visible = uiState.showHotspotPrompt,
            backdrop = backdrop,
            onDismiss = { viewModel.dismissHotspotPrompt() },
            onEnable = {
                val intent = android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                context.startActivity(intent)
                viewModel.dismissHotspotPrompt()
            }
        )

        // ── Corruption alert modal ──────────────────────────────────────────
        CorruptionAlert(
            corruptedPhotos = uiState.corruptedPhotos,
            backdrop = backdrop,
            onDismiss = { viewModel.dismissCorruptionAlert() },
            onRetry = { viewModel.retryCorruptedPhotos(context) }
        )
        } // end sliding Box

        // ── MotherCore: outside sliding box, counter-translated to stay fixed ─
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .graphicsLayer { translationY = -screenExitY.value }
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInParent()
                    val sz  = coords.size
                    coreCenter = Offset(pos.x + sz.width / 2f, pos.y + sz.height / 2f)
                },
            contentAlignment = Alignment.Center
        ) {
            // ── Live camera viewfinder behind MotherCore ───────────────────────
            // Shown whenever this phone is a camera-mode receiver (no photos selected,
            // not mid-transfer). The preview starts immediately; color matching starts
            // once a sender has been discovered and its color is known.
            // Once the color is locked the camera is dropped and the core switches to that color.
            val viewfinderActive = cameraGranted && uiState.cameraHandshakeEnabled &&
                uiState.selectedPhotos.isEmpty() && !uiState.isReceiving &&
                uiState.colorDetectionState != ColorDetectionState.Locked
            val viewfinderContent: (@Composable () -> Unit)? = if (viewfinderActive) {
                {
                    MotherCoreViewfinder(
                        targetColor = uiState.detectedPeerColor?.displayColor,
                        onMatchStrengthChanged = { viewModel.onDetectionStrengthChanged(it) },
                        onColorLocked = { viewModel.onColorLocked() }
                    )
                }
            } else null

            // Receiver: tint to the peer's color once locked.
            // Sender: show our own advertised color so the other phone's camera can find it.
            val lockedAccent = when {
                uiState.colorDetectionState == ColorDetectionState.Locked ->
                    uiState.detectedPeerColor?.displayColor?.let { androidx.compose.ui.graphics.Color(it) }
                uiState.cameraHandshakeEnabled && uiState.selectedPhotos.isNotEmpty() ->
                    uiState.displayColor?.let { androidx.compose.ui.graphics.Color(it) }
                else -> null
            }

            MotherCore(
                progress     = uiState.transferProgress,
                isReceiving  = uiState.isReceiving,
                shouldExit   = uiState.shouldExit,
                cutoutOffset = cutoutOffset,
                backdrop     = backdrop,
                accentColor  = lockedAccent,
                viewfinderContent = viewfinderContent,
                onAnimationComplete = {
                    val activity = context as? android.app.Activity
                    @Suppress("DEPRECATION") activity?.overridePendingTransition(0, 0)
                    activity?.finish()
                }
            )

            // ── Detecting hint label ─────────────────────────────────────────
            if (viewfinderActive && uiState.colorDetectionState != ColorDetectionState.Locked) {
                androidx.compose.material3.Text(
                    text = if (uiState.colorDetectionState == ColorDetectionState.Detecting)
                        "Point at their phone" else "Looking for devices…",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 40.dp)
                        .background(
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // ── Status, errors and the scan popup: one column ABOVE everything else ──
        // Drawn after MotherCore and the "+" button, and kept in the free band between the
        // photo grid and the bottom buttons, so nothing (blob, "+", buttons) covers it.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 160.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Pairing diagnostics (camera mode): what mDNS is doing, readable on the phone ──
            if (uiState.cameraHandshakeEnabled) {
                val handshakeLog by (cameraHandshakeManager ?: app.cameraHandshakeManager)
                    .status.collectAsStateWithLifecycle()
                if (handshakeLog.isNotEmpty()) {
                    androidx.compose.material3.Text(
                        text = handshakeLog.joinToString("\n"),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // ── Transfer failure, with the reason (otherwise the app just silently re-scans) ──
            uiState.transferError?.let { reason ->
                androidx.compose.material3.Text(
                    text = "Transfer failed: $reason",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(
                            androidx.compose.ui.graphics.Color(0xFF7A1F1F).copy(alpha = 0.92f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            uiState.infoMessage?.let { info ->
                androidx.compose.material3.Text(
                    text = info,
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(
                            androidx.compose.ui.graphics.Color(0xFF1F5C3A).copy(alpha = 0.92f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            // ── Scan complete popup (springs up above buttons) ────────────────────
            val showScanPopup = uiState.colorDetectionState == ColorDetectionState.Locked
            val scanPopupColor = uiState.detectedPeerColor?.displayColor
            if (scanPopupColor != null) {
                ScanCompletePopup(
                    visible = showScanPopup,
                    lockedColor = scanPopupColor,
                    modifier = Modifier.fillMaxWidth()
                )
            }

        }
    }
}

// ── Liquid glass segmented button row ──────────────────────────────────────
@Composable
private fun <T> LiquidSegmentedButtonRow(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    backdrop: Backdrop,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == items.size - 1
            val isSelected = selectedItem == item

            val buttonShape: () -> Shape = when {
                isFirst && isLast -> { { com.kyant.shapes.Capsule() } }
                isFirst -> { {
                    RoundedCornerShape(
                        topStart = 50.dp, bottomStart = 50.dp,
                        topEnd = 15.dp,   bottomEnd = 15.dp
                    )
                } }
                isLast -> { {
                    RoundedCornerShape(
                        topStart = 15.dp, bottomStart = 15.dp,
                        topEnd = 50.dp,   bottomEnd = 50.dp
                    )
                } }
                else -> { { RoundedCornerShape(15.dp) } }
            }

            LiquidButton(
                onClick = { onItemSelected(item) },
                backdrop = backdrop,
                surfaceColor = if (isSelected) OceanAqua.copy(alpha = 0.45f) else OceanAqua.copy(alpha = 0.15f),
                buttonHeight = 40.dp,
                shape = buttonShape,
                modifier = Modifier.weight(1f),
                isInteractive = true
            ) {
                Text(
                    text = label(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ── Settings panel: liquid glass bottom container ───────────────────────────
@Composable
private fun SettingsPanel(
    themeRepository: ThemeRepository,
    backdrop: Backdrop,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope     = rememberCoroutineScope()
    val context   = LocalContext.current
    val themeMode by themeRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val handshakeMode by themeRepository.handshakeMode.collectAsStateWithLifecycle(HandshakeMode.AUTO)

    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape    = { RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) },
                effects  = {
                    vibrancy()
                    blur(18f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                onDrawSurface = {
                    drawRect(OceanAqua.copy(alpha = 0.07f))
                }
            )
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // ── Liquid glass close button ────────────────────────────────────────
        LiquidButton(
            onClick      = onClose,
            backdrop     = backdrop,
            surfaceColor = OceanAqua.copy(alpha = 0.20f),
            buttonHeight = 40.dp,
            modifier     = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector        = Icons.Default.Close,
                contentDescription = "Close settings",
                tint               = Color.White
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // ── Liquid glass "Settings" title pill ───────────────────────────
            Box(
                modifier = Modifier
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape    = { RoundedCornerShape(50) },
                        effects  = {
                            vibrancy()
                            blur(6f.dp.toPx())
                            lens(8f.dp.toPx(), 16f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(OceanAqua.copy(alpha = 0.12f)) }
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text  = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text  = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
                LiquidSegmentedButtonRow(
                    items = ThemeMode.entries,
                    selectedItem = themeMode,
                    onItemSelected = { scope.launch { themeRepository.setThemeMode(it) } },
                    backdrop = backdrop,
                    label = { mode ->
                        when (mode) {
                            ThemeMode.LIGHT  -> stringResource(R.string.theme_light)
                            ThemeMode.DARK   -> stringResource(R.string.theme_dark)
                            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                        }
                    }
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text  = stringResource(R.string.settings_handshake),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
                LiquidSegmentedButtonRow(
                    items = HandshakeMode.entries,
                    selectedItem = handshakeMode,
                    onItemSelected = { scope.launch { themeRepository.setHandshakeMode(it) } },
                    backdrop = backdrop,
                    label = { mode ->
                        when (mode) {
                            HandshakeMode.AUTO   -> stringResource(R.string.handshake_auto)
                            HandshakeMode.NFC    -> stringResource(R.string.handshake_nfc)
                            HandshakeMode.CAMERA -> stringResource(R.string.handshake_camera)
                        }
                    }
                )
            }

            val versionText = remember {
                runCatching {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    "Flash ${info.versionName} (build ${info.longVersionCode})"
                }.getOrDefault("Flash")
            }
            Text(
                text  = versionText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Received photo materializing into orbit ────────────────────────────────────
@Composable
private fun ReceivedPhotoMaterializeFlyer(
    uri: Uri,
    coreCenter: Offset,
    phaseOffset: Float
) {
    val density = LocalDensity.current
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
    }

    val progress = animProgress.value
    if (progress >= 1f) return  // Animation complete, now in orbit via PhotoOrbit

    // Phase 1 (0-0.15): Materialize with scale pulse at blob center
    val pulsePhase = (progress / 0.15f).coerceIn(0f, 1f)
    val pulseScale = if (pulsePhase < 0.5f) {
        lerp(0f, 1.2f, pulsePhase * 2f)
    } else {
        lerp(1.2f, 1f, (pulsePhase - 0.5f) * 2f)
    }

    // Phase 2 (0.15-1): Fly outward to orbit position
    val flightPhase = ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)

    val baseOrbitRadiusPx = with(density) { 100.dp.toPx() }
    val orbitX = coreCenter.x + baseOrbitRadiusPx * kotlin.math.cos(phaseOffset)
    val orbitY = coreCenter.y + baseOrbitRadiusPx * kotlin.math.sin(phaseOffset)

    val currentScale = if (progress < 0.15f) pulseScale else 1f
    val x = lerp(coreCenter.x, orbitX, flightPhase)
    val y = lerp(coreCenter.y, orbitY, flightPhase)

    val photoSizeDp = 56.dp
    val photoSizePx = with(density) { photoSizeDp.toPx() }

    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(photoSizeDp)
            .offset {
                IntOffset(
                    (x - photoSizePx / 2f).roundToInt(),
                    (y - photoSizePx / 2f).roundToInt()
                )
            }
            .graphicsLayer {
                scaleX = currentScale
                scaleY = currentScale
            }
            .zIndex(3f)
            .clip(RoundedCornerShape(8.dp))
    )
}

// ── Received photo flying from orbit into gallery ─────────────────────────────
@Composable
private fun ReceivedPhotoOrbitToGalleryFlyer(
    uri: Uri,
    coreCenter: Offset,
    gridItemIndex: Int
) {
    val density = LocalDensity.current
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(1f, tween(2000, easing = FastOutSlowInEasing))
    }

    val progress = animProgress.value
    if (progress >= 1f) return  // Animation complete, now in gallery

    val gridItemSizeDp = 100.dp
    val paddingDp = 8.dp
    val gapDp = 4.dp
    val col = gridItemIndex % 3
    val row = gridItemIndex / 3

    val targetX = with(density) {
        paddingDp.toPx() + col * (gridItemSizeDp.toPx() + gapDp.toPx()) + gridItemSizeDp.toPx() / 2f
    }
    val targetY = with(density) {
        paddingDp.toPx() + row * (gridItemSizeDp.toPx() + gapDp.toPx()) + gridItemSizeDp.toPx() / 2f
    }

    // Start from current orbit position (roughly at base radius)
    val baseOrbitRadiusPx = with(density) { 100.dp.toPx() }
    val orbitX = coreCenter.x + baseOrbitRadiusPx
    val orbitY = coreCenter.y

    // Fly from orbit to gallery position
    val x = lerp(orbitX, targetX, progress)
    val y = lerp(orbitY, targetY, progress)

    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(gridItemSizeDp)
            .offset {
                IntOffset(
                    (x - gridItemSizeDp.toPx() / 2f).roundToInt(),
                    (y - gridItemSizeDp.toPx() / 2f).roundToInt()
                )
            }
            .clip(RoundedCornerShape(12.dp))
            .zIndex(1f)
    )
}

// ── Received photo flying into gallery ──────────────────────────────────────────
@Composable
private fun ReceivedPhotoFlyer(
    uri: Uri,
    coreCenter: Offset,
    gridItemIndex: Int
) {
    val density = LocalDensity.current
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(1f, tween(2000, easing = FastOutSlowInEasing))
    }

    val progress = animProgress.value

    // Only render while animating; once complete, photo is in gallery at Z0
    if (progress >= 1f) return

    val gridItemSizeDp = 100.dp
    val paddingDp = 8.dp
    val gapDp = 4.dp
    val col = gridItemIndex % 3
    val row = gridItemIndex / 3

    val targetX = with(density) {
        paddingDp.toPx() + col * (gridItemSizeDp.toPx() + gapDp.toPx()) + gridItemSizeDp.toPx() / 2f
    }
    val targetY = with(density) {
        paddingDp.toPx() + row * (gridItemSizeDp.toPx() + gapDp.toPx()) + gridItemSizeDp.toPx() / 2f
    }

    // Phase 1 (0-0.2): Materialize with scale pulse + brief orbit glow
    val pulsePhase = (progress / 0.2f).coerceIn(0f, 1f)
    val pulseScale = if (pulsePhase < 0.5f) {
        lerp(0f, 1.2f, pulsePhase * 2f)
    } else {
        lerp(1.2f, 1f, (pulsePhase - 0.5f) * 2f)
    }

    // Phase 2 (0.2-0.4): Move horizontally to clear blob
    val horizontalPhase = ((progress - 0.2f) / 0.2f).coerceIn(0f, 1f)
    val horizontalDir = if (targetX < coreCenter.x) -1f else 1f
    val clearDistance = with(density) { 150.dp.toPx() }
    val clearX = coreCenter.x + (clearDistance * horizontalDir * horizontalPhase)

    // Phase 3 (0.4-1): Fly to target grid position at Z1
    val flightPhase = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
    val x = lerp(clearX, targetX, flightPhase)
    val y = lerp(coreCenter.y, targetY, flightPhase)

    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(gridItemSizeDp)
            .offset {
                IntOffset(
                    (x - gridItemSizeDp.toPx() / 2f).roundToInt(),
                    (y - gridItemSizeDp.toPx() / 2f).roundToInt()
                )
            }
            .clip(RoundedCornerShape(12.dp))
            .graphicsLayer {
                val displayScale = if (progress < 0.2f) pulseScale else 1f
                scaleX = displayScale
                scaleY = displayScale
            }
            .zIndex(1f)
    )
}

// ── Photo grid item ─────────────────────────────────────────────────────────────
@Composable
private fun PhotoGridItem(
    uri: Uri,
    isInOrbit: Boolean,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
    ) {
        AsyncImage(
            model              = uri,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize()
        )
        if (isInOrbit) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(OceanAqua.copy(alpha = 0.35f)))
        }
    }
}

// ── Corruption Alert Modal ──────────────────────────────────────────────────
@Composable
private fun CorruptionAlert(
    corruptedPhotos: List<Uri>,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    val density = LocalDensity.current

    AnimatedVisibility(
        visible = corruptedPhotos.isNotEmpty(),
        enter   = fadeIn(tween(400, delayMillis = 500)) + scaleIn(tween(400, delayMillis = 500), initialScale = 0.85f),
        exit    = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.9f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
                    )
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(20.dp) },
                        effects = {
                            vibrancy()
                            blur(6f.dp.toPx())
                        },
                        onDrawSurface = {
                            drawRect(OceanAqua.copy(alpha = 0.05f))
                        }
                    )
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.corruption_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.corruption_subtitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    // Corrupted photos as circles in circular arrangement
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        corruptedPhotos.forEachIndexed { index, uri ->
                            val angle = (index / corruptedPhotos.size.coerceAtLeast(1)) * 2f * kotlin.math.PI.toFloat()
                            val radiusPx = 70f
                            val offsetX = (radiusPx * kotlin.math.cos(angle)).toInt()
                            val offsetY = (radiusPx * kotlin.math.sin(angle)).toInt()

                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .offset(offsetX.dp, offsetY.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(0.7f)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Red.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✕",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.corruption_question),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.corruption_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LiquidButton(
                            onClick      = onDismiss,
                            backdrop     = backdrop,
                            enabled      = true,
                            surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier     = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.corruption_button_skip),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center
                            )
                        }

                        LiquidButton(
                            onClick      = onRetry,
                            backdrop     = backdrop,
                            enabled      = true,
                            surfaceColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            modifier     = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.corruption_button_retry),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Hotspot Prompt Modal ──────────────────────────────────────────────────
@Composable
private fun HotspotPromptModal(
    visible: Boolean,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    onEnable: () -> Unit
) {
    val scaleAnim = remember { Animatable(0.25f) }

    LaunchedEffect(visible) {
        if (visible) {
            scaleAnim.snapTo(0.25f)
            scaleAnim.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.3f),
        exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.3f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
                    )
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(24.dp) },
                        effects = {
                            vibrancy()
                            blur(8f.dp.toPx())
                        },
                        onDrawSurface = {
                            drawRect(OceanAqua.copy(alpha = 0.08f))
                        }
                    )
                    .graphicsLayer {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                    }
                    .padding(28.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.hotspot_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.hotspot_subtitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    Text(
                        text = stringResource(R.string.hotspot_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        LiquidButton(
                            onClick = onDismiss,
                            backdrop = backdrop,
                            enabled = true,
                            surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.hotspot_button_cancel),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center
                            )
                        }

                        LiquidButton(
                            onClick = onEnable,
                            backdrop = backdrop,
                            enabled = true,
                            surfaceColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.hotspot_button_enable),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
