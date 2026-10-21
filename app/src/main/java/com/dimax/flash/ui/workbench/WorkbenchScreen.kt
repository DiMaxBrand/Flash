package com.dimax.flash.ui.workbench

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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.dimax.flash.FlashApplication
import com.dimax.flash.isOn
import com.dimax.flash.R
import com.dimax.flash.handshake.MotherCoreViewfinder
import com.dimax.flash.handshake.ScanCompletePopup
import com.dimax.flash.nfc.NfcManager
import com.dimax.flash.ui.workbench.ColorDetectionState
import com.dimax.flash.transfer.TransferRepository
import com.dimax.flash.ui.core.MotherCore
import com.dimax.flash.ui.shader.RippleOverlay
import com.dimax.flash.ui.theme.OceanAqua
import com.dimax.flash.ui.theme.HandshakeMode
import com.dimax.flash.ui.theme.UiStyle
import com.dimax.flash.ui.expressive.ExpressiveWorkbench
import com.dimax.flash.ui.theme.ThemeMode
import com.dimax.flash.util.DeviceDetector
import com.dimax.flash.ui.theme.ThemeRepository
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
    cameraHandshakeManager: com.dimax.flash.handshake.CameraHandshakeManager? = null,
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
    // Nearby devices (Android 13+) and local network access (newer Android / HyperOS) are
    // requested together with the camera, so one prompt chain covers pairing.
    fun isGranted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(
        context, permission
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val nearbyPermission = "android.permission.NEARBY_WIFI_DEVICES"
    val localNetPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    // A permission this Android version does not define can never be granted or prompted for
    fun isDefined(permission: String) = runCatching {
        context.packageManager.getPermissionInfo(permission, 0)
    }.isSuccess
    val nearbyKnown = Build.VERSION.SDK_INT >= 33 && isDefined(nearbyPermission)
    val localNetKnown = isDefined(localNetPermission)
    var nearbyGranted by remember { mutableStateOf(!nearbyKnown || isGranted(nearbyPermission)) }
    var localNetGranted by remember { mutableStateOf(!localNetKnown || isGranted(localNetPermission)) }
    var openSettingsIfStillDenied by remember { mutableStateOf(false) }

    fun missingPairingPermissions() = buildList {
        if (cameraOn && !cameraGranted) add(android.Manifest.permission.CAMERA)
        if (nearbyKnown && !nearbyGranted) add(nearbyPermission)
        if (localNetKnown && !localNetGranted) add(localNetPermission)
    }

    val pairingPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        grants[android.Manifest.permission.CAMERA]?.let { cameraGranted = it }
        grants[nearbyPermission]?.let { nearbyGranted = it }
        grants[localNetPermission]?.let { localNetGranted = it }
        // Tapped to fix it but Android showed no dialog (denied for good): go to the app's settings
        if (openSettingsIfStillDenied && missingPairingPermissions().isNotEmpty()) {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        }
        openSettingsIfStillDenied = false
    }
    // Asked automatically when pairing becomes active; tapping the status box asks again
    LaunchedEffect(cameraOn) {
        val missing = missingPairingPermissions()
        if (missing.isNotEmpty()) pairingPermissionLauncher.launch(missing.toTypedArray())
    }
    val requestPairingPermissions = {
        val missing = missingPairingPermissions()
        if (missing.isNotEmpty()) {
            openSettingsIfStillDenied = true
            pairingPermissionLauncher.launch(missing.toTypedArray())
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

    // ── NFC roles ───────────────────────────────────────────────────────────
    // No photos selected = RECEIVER: reader mode on, it reads the other phone's card.
    // Photos selected    = SENDER: reader mode off (a reader cannot be a card), and Flash is asked to be
    // the preferred card service. Re-applied on every return to the app, because onPause switches NFC off.
    val isSender = uiState.selectedPhotos.isNotEmpty()
    androidx.lifecycle.compose.LifecycleResumeEffect(nfcOn, isSender, uiState.nfcOff) {
        val act = context as? android.app.Activity
        if (act != null && nfcOn) {
            if (isSender) {
                nfcManager.disableReaderMode(act)
                nfcManager.setPreferredCardService(act, true)
            } else {
                nfcManager.setPreferredCardService(act, false)
                nfcManager.enableReaderMode(act)
            }
        }
        onPauseOrDispose {
            if (act != null) {
                nfcManager.disableReaderMode(act)
                nfcManager.setPreferredCardService(act, false)
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
        else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            // Android 9 and older also need WRITE to save received photos into the gallery
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
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

    var exitEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600L); exitEnabled = true }

    // Received photos are added at the top of the grid; make sure the top is what is on screen
    val gridState = rememberLazyGridState()
    LaunchedEffect(uiState.landingPhotos.isNotEmpty()) {
        if (uiState.landingPhotos.isNotEmpty()) gridState.animateScrollToItem(0)
    }

    // Grid geometry (square cells) so received photos can land exactly on their cell
    val gridCellWidthPx = with(density) {
        (LocalConfiguration.current.screenWidthDp.dp - 8.dp * 2 - 4.dp * 2).toPx() / 3f
    }
    val landingTargets: Map<Uri, Offset> = remember(uiState.landingPhotos, uiState.photos, gridCellWidthPx) {
        val gap = with(density) { 4.dp.toPx() }
        val pad = with(density) { 8.dp.toPx() }
        uiState.landingPhotos.mapNotNull { uri ->
            val i = uiState.photos.indexOf(uri)
            if (i < 0) null else uri to Offset(
                pad + (i % 3) * (gridCellWidthPx + gap) + gridCellWidthPx / 2f,
                pad + (i / 3) * (gridCellWidthPx + gap) + gridCellWidthPx / 2f
            )
        }.toMap()
    }

    // ── Liquid receive session: the core grows, the old grid slides away ──
    val sessionOn = uiState.liquidSession
    val coreScale = remember { Animatable(1f) }
    val gridSlide = remember { Animatable(0f) }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    LaunchedEffect(sessionOn) {
        if (sessionOn) {
            launch { gridSlide.animateTo(screenHeightPx, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 120f)) }
            coreScale.animateTo(2f, spring(dampingRatio = 0.7f, stiffness = 90f))
        } else if (coreScale.value > 1.01f || gridSlide.value > 0f) {
            // Finale: the core zooms a little bigger, then settles back; the old photos slide up
            launch {
                delay(350)
                gridSlide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 110f))
            }
            coreScale.animateTo(2.35f, spring(dampingRatio = 0.8f, stiffness = 300f))
            coreScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 140f))
        }
    }
    val accentFade by animateFloatAsState(
        targetValue = if (uiState.sessionColor != null && !sessionOn) 0f else 0.80f,
        animationSpec = tween(1200),
        label = "accent_fade"
    )

    // While leaving, the layer behind every screen fades out too (see MainActivity), so the window shows what
    // was behind the app. Leaving the screen any other way puts it back.
    LaunchedEffect(uiState.shouldExit) { app.revealBehindApp.value = uiState.shouldExit }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { app.revealBehindApp.value = false } }

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

    val photoPicker      = rememberPhotoPicker(
        onPhotos = { uris -> viewModel.onPhotosSelected(uris, context) },
        onMedia  = { uris -> viewModel.onMediaSelected(uris, context) }
    )
    var showAddMenu by remember { mutableStateOf(false) }

    // A touch that reached the other phone but could not be read (too short, lost contact): ask again.
    // The details stay in the NFC status box for diagnosing.
    var nfcReadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { nfcManager.readFailed.collect { nfcReadFailed = true } }
    LaunchedEffect(uiState.nfcState) { if (uiState.nfcState is NfcUiState.PeerDetected) nfcReadFailed = false }
    LaunchedEffect(nfcReadFailed) { if (nfcReadFailed) { delay(8000); nfcReadFailed = false } }
    if (nfcReadFailed && nfcOn) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { nfcReadFailed = false },
            title = { Text(stringResource(R.string.nfc_read_failed_title)) },
            text = { Text(stringResource(R.string.nfc_read_failed_text)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { nfcReadFailed = false }) { Text("OK") }
            }
        )
    }

    // ── Interface style: the Material 3 Expressive layout replaces the whole liquid UI ──
    val uiStyle by app.themeRepository.uiStyle.collectAsStateWithLifecycle(UiStyle.LIQUID)
    if (uiStyle == UiStyle.EXPRESSIVE) {
        val handshakeLog by (cameraHandshakeManager ?: app.cameraHandshakeManager)
            .status.collectAsStateWithLifecycle()
        val nfcLog by nfcManager.status.collectAsStateWithLifecycle()
        ExpressiveWorkbench(
            viewModel = viewModel,
            state = uiState,
            themeRepository = app.themeRepository,
            handshakeLog = handshakeLog,
            nfcLog = if (nfcOn) nfcLog else emptyList(),
            permissionSummary = "permissions: camera ${if (cameraGranted) "yes" else "NO"} · " +
                "nearby ${if (!nearbyKnown) "n/a" else if (nearbyGranted) "yes" else "NO"} · " +
                "local network ${if (!localNetKnown) "n/a" else if (localNetGranted) "yes" else "NO"}",
            missingPermissions = missingPairingPermissions().isNotEmpty(),
            onRequestPermissions = { requestPairingPermissions() },
            cameraGranted = cameraGranted,
            picker = photoPicker,
            onOpenSettings = onNavigateToSettings
        )
        return
    }

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
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement   = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = gridSlide.value }
                .layerBackdrop(backdrop)
        ) {
            items(uiState.photos, key = { it.toString() }) { uri ->
                PhotoGridItem(
                    uri       = uri,
                    isInOrbit = uri in uiState.selectedPhotos,
                    hidden    = uri in uiState.landingPhotos,
                    modifier  = Modifier.animateItem(),
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
            rejectedPhotos   = uiState.rejectedPhotos,
            sendProgress     = uiState.sendProgress,
            landingTargets   = landingTargets,
            landingSizePx    = gridCellWidthPx,
            onPhotoLanded    = { viewModel.onPhotoLanded(it) }
        )

        // ── Received photos materializing into orbit ─────────────────────────
        uiState.receivingPhotos.forEachIndexed { index, uri ->
            key(uri) {
                ReceivedPhotoMaterializeFlyer(
                    uri = uri,
                    coreCenter = coreCenter,
                    phaseOffset = index * (2f * kotlin.math.PI.toFloat() / uiState.receivingPhotos.size)
                        + (uiState.selectedPhotos.size * GOLDEN_ANGLE)  // offset by existing photos
                )
            }
        }

        // ── Auto-confirm when color locks (no user tap needed) ───────────────
        LaunchedEffect(uiState.colorDetectionState) {
            if (uiState.colorDetectionState == ColorDetectionState.Locked) {
                viewModel.onColorConfirmed(context)  // starts the download right away
            }
        }

        // ── Bottom area: Exit and Settings (Settings is its own screen) ──────
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            LiquidButton(
                onClick      = { viewModel.onExitRequested() },
                backdrop     = backdrop,
                enabled      = exitEnabled,
                surfaceColor = OceanAqua.copy(alpha = 0.25f),
                modifier     = Modifier.width(128.dp)
            ) {
                Text(
                    text  = stringResource(R.string.exit_button),
                    color = LocalGlassContentColor.current,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(Modifier.width(12.dp))

            LiquidButton(
                onClick      = onNavigateToSettings,
                backdrop     = backdrop,
                enabled      = true,
                surfaceColor = OceanAqua.copy(alpha = 0.25f),
                modifier     = Modifier.width(128.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                    tint               = LocalGlassContentColor.current
                )
            }
        }

        // ── "+" liquid glass button ──────────────────────────────────────────
        AnimatedVisibility(
            visible = true,
            enter   = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.85f),
            exit    = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 88.dp)
        ) {
            Box {
                LiquidButton(
                    onClick      = { showAddMenu = true },
                    backdrop     = backdrop,
                    surfaceColor = OceanAqua.copy(alpha = 0.30f),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_add_photos),
                        tint = LocalGlassContentColor.current
                    )
                }
                AddMenu(expanded = showAddMenu, onDismiss = { showAddMenu = false }, picker = photoPicker)
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
                .graphicsLayer {
                    scaleX = coreScale.value
                    scaleY = coreScale.value
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                }
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
                uiState.selectedPhotos.isEmpty() && !uiState.isReceiving && !sessionOn &&
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
                uiState.rejectedPhotos.isNotEmpty() -> androidx.compose.ui.graphics.Color(0xFFD32F2F)
                uiState.sessionColor != null -> androidx.compose.ui.graphics.Color(uiState.sessionColor!!)
                uiState.colorDetectionState == ColorDetectionState.Locked ->
                    uiState.detectedPeerColor?.displayColor?.let { androidx.compose.ui.graphics.Color(it) }
                uiState.cameraHandshakeEnabled && uiState.selectedPhotos.isNotEmpty() ->
                    uiState.displayColor?.let { androidx.compose.ui.graphics.Color(it) }
                else -> null
            }

            val featured = uiState.incoming.firstOrNull { it.index == uiState.featuredIndex && it.stage == Stage.FEATURED }
            val liquidSessionOn = sessionOn || (uiState.incoming.isNotEmpty() && !uiState.legacyReceive)
            // The two experimental core feature flags (Settings > Feature flags); both off = no sensors, nothing changes
            val flagOverrides by FlashApplication.instance.featureFlags.overrides.collectAsStateWithLifecycle()
            val coreMotion = com.dimax.flash.ui.core.rememberCoreMotion(
                tilt = flagOverrides.isOn(com.dimax.flash.FeatureFlag.CORE_MOTION),
                threeD = flagOverrides.isOn(com.dimax.flash.FeatureFlag.CORE_3D)
            )
            MotherCore(
                motion       = coreMotion,
                progress     = if (liquidSessionOn) 0f else uiState.transferProgress,
                isReceiving  = uiState.isReceiving && !liquidSessionOn,
                accentAlpha  = accentFade,
                featuredContent = featured?.let { f -> { LiquidFeatured(f, viewModel) } },
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

        // ── Liquid receive session: this session's photo cells, flights and notices ──
        LiquidSessionLayer(
            state = uiState,
            viewModel = viewModel,
            coreCenter = coreCenter,
            coreSizePx = blobSizePx,
            coreScale = coreScale.value
        )

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
                        text = "permissions: camera ${if (cameraGranted) "yes" else "NO"} · " +
                            "nearby ${if (!nearbyKnown) "n/a" else if (nearbyGranted) "yes" else "NO"} · " +
                            "local network ${if (!localNetKnown) "n/a" else if (localNetGranted) "yes" else "NO"}" +
                            (if (missingPairingPermissions().isNotEmpty()) "  (tap to grant)" else "") + "\n" +
                            handshakeLog.joinToString("\n"),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { requestPairingPermissions() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // ── NFC pairing: is it on, and what happened on the last touch ──
            if (nfcOn) {
                val nfcLog by nfcManager.status.collectAsStateWithLifecycle()
                if (uiState.nfcOff) {
                    androidx.compose.material3.Text(
                        text = "NFC is off — tap to turn it on",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .background(androidx.compose.ui.graphics.Color(0xFF7A1F1F).copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                            .clickable { context.startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                if (nfcLog.isNotEmpty()) {
                    androidx.compose.material3.Text(
                        text = "NFC\n" + nfcLog.joinToString("\n"),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
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

            if (uiState.rejectedPhotos.isNotEmpty()) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.damaged_photos, uiState.rejectedPhotos.size),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(
                            androidx.compose.ui.graphics.Color(0xFFB71C1C).copy(alpha = 0.95f),
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
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

    MediaImage(
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
    // Real grid columns are (screen width - padding - gaps) / 3 wide; items are 100dp inside them
    val cellWidthDp = (LocalConfiguration.current.screenWidthDp.dp - paddingDp * 2 - gapDp * 2) / 3

    val targetX = with(density) {
        paddingDp.toPx() + col * (cellWidthDp.toPx() + gapDp.toPx()) + gridItemSizeDp.toPx() / 2f
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

    MediaImage(
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
    // Real grid columns are (screen width - padding - gaps) / 3 wide; items are 100dp inside them
    val cellWidthDp = (LocalConfiguration.current.screenWidthDp.dp - paddingDp * 2 - gapDp * 2) / 3

    val targetX = with(density) {
        paddingDp.toPx() + col * (cellWidthDp.toPx() + gapDp.toPx()) + gridItemSizeDp.toPx() / 2f
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

    MediaImage(
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
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    hidden: Boolean = false
) {
    // A received photo's cell stays empty while its flying copy is still on the way
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hidden) 0f else 1f,
        animationSpec = tween(180),
        label = "cell_alpha"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)   // square cells: a flying photo lands exactly on its cell
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onTap)
    ) {
        MediaImage(
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
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.97f)
                    )
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(20.dp) },
                        effects = {
                            vibrancy()
                            blur(6f.dp.toPx())
                        },
                        onDrawSurface = {
                            drawRect(Color.Red.copy(alpha = 0.06f))
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
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.corruption_subtitle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
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
                                MediaImage(
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
                                color = LocalGlassContentColor.current,
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
                                color = LocalGlassContentColor.current,
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
                                color = LocalGlassContentColor.current,
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
                                color = LocalGlassContentColor.current,
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
