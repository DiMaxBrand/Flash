package com.example.flash.ui.expressive

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.flash.R
import com.example.flash.handshake.MotherCoreViewfinder
import com.example.flash.handshake.ScanCompletePopup
import com.example.flash.ui.theme.HandshakeMode
import com.example.flash.ui.theme.ThemeMode
import com.example.flash.ui.theme.ThemeRepository
import com.example.flash.ui.theme.UiStyle
import com.example.flash.ui.workbench.ColorDetectionState
import com.example.flash.ui.workbench.IncomingPhoto
import com.example.flash.ui.workbench.WorkbenchUiState
import com.example.flash.ui.workbench.WorkbenchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plain Material 3 Expressive layout (Settings > Interface > Expressive). No Mother Core and no
 * glass: a tray at the top holds the photos being sent or received and fills up as they go,
 * the gallery grid fills the middle, and a bottom app bar holds Exit / Settings / Add.
 * It drives the same [WorkbenchViewModel] as the liquid UI, so pairing and transfer are identical.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveWorkbench(
    viewModel: WorkbenchViewModel,
    state: WorkbenchUiState,
    themeRepository: ThemeRepository,
    handshakeLog: List<String>,
    permissionSummary: String,
    missingPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    cameraGranted: Boolean,
    onAddPhotos: () -> Unit
) {
    val context = LocalContext.current
    val themeMode by themeRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    // The pairing/transfer plumbing the liquid screen also runs
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.updateWifiStatus(context)
            delay(1500)
        }
    }
    LaunchedEffect(state.colorDetectionState) {
        if (state.colorDetectionState == ColorDetectionState.Locked) viewModel.onColorConfirmed(context)
    }
    LaunchedEffect(state.showRipple) {
        if (state.showRipple) {
            delay(1200)
            viewModel.onRippleComplete()
        }
    }

    // No orbit/flight in this layout: good photos "land" in the grid at once
    LaunchedEffect(state.landingPhotos) {
        state.landingPhotos.forEach { viewModel.onPhotoLanded(it) }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showSimulation by remember { mutableStateOf(false) }
    val viewfinderActive = cameraGranted && state.cameraHandshakeEnabled &&
        state.selectedPhotos.isEmpty() && !state.isReceiving &&
        state.colorDetectionState != ColorDetectionState.Locked

    // ── Reveal cards: which photos are shown big, and when every reveal is finished ──────────────
    val incoming = state.incoming
    val bigIncoming: List<IncomingPhoto> = when {
        incoming.isEmpty() -> emptyList()
        incoming.size <= 2 -> incoming
        else -> incoming.filter { it.open }
    }
    val bigSending: List<android.net.Uri> =
        // Only while the transfer is actually running: selecting photos must not take over the screen
        // (the gallery stays scrollable so more photos can be picked)
        if (incoming.isEmpty() && !state.isReceiving && state.sendProgress > 0f && state.selectedPhotos.size in 1..2) state.selectedPhotos.toList()
        else emptyList()
    val bigMode = bigIncoming.isNotEmpty() || bigSending.isNotEmpty()

    DisposableEffect(Unit) {
        viewModel.setHoldLanding(true)   // photos stay as cards until their reveal is over
        onDispose { viewModel.setHoldLanding(false) }
    }
    val revealed = remember { mutableStateListOf<Int>() }
    val onRevealed: (Int) -> Unit = { index -> if (index !in revealed) revealed.add(index) }
    LaunchedEffect(incoming.isEmpty()) { if (incoming.isEmpty()) revealed.clear() }
    LaunchedEffect(revealed.size, incoming.map { it.verified }, incoming.size) {
        if (incoming.isNotEmpty() && incoming.all { it.index in revealed || it.verified == false }) {
            viewModel.onRevealFinished()
        }
    }
    // An older sender without previews: nothing to reveal, let the photos settle right away
    LaunchedEffect(state.receivingPhotos.isNotEmpty(), incoming.isEmpty()) {
        if (state.receivingPhotos.isNotEmpty() && incoming.isEmpty()) viewModel.onRevealFinished()
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            bottomBar = {
                BottomAppBar(
                    actions = {
                        IconButton(onClick = { (context as? Activity)?.finish() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.exit_button))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                        }
                    },
                    floatingActionButton = {
                        FloatingActionButton(onClick = onAddPhotos) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_photos))
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                    Tray(
                        state = state,
                        viewfinderActive = viewfinderActive,
                        viewModel = viewModel,
                        bigIndices = bigIncoming.map { it.index }.toSet(),
                        onRevealed = onRevealed,
                        onRemove = { viewModel.onPhotoRemovedFromOrbit(it) }
                    )
                    AnimatedContent(
                        targetState = bigMode,
                        transitionSpec = {
                            (fadeIn(spring(stiffness = Spring.StiffnessLow)) + scaleIn(initialScale = 0.94f)) togetherWith
                                (fadeOut() + scaleOut(targetScale = 0.8f))   // big cards shrink away into the grid
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "main_area"
                    ) { big ->
                        if (big) {
                            BigCards(
                                state = state,
                                viewModel = viewModel,
                                bigIncoming = bigIncoming,
                                bigSending = bigSending,
                                onRevealed = onRevealed
                            )
                        } else {
                            PhotoGridArea(state, viewModel)
                        }
                    }
                }

                // Status, errors and the scan popup sit above the grid, clear of the bottom bar
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.cameraHandshakeEnabled) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                            modifier = Modifier.clickable { onRequestPermissions() }
                        ) {
                            Text(
                                text = permissionSummary +
                                    (if (missingPermissions) "  (tap to grant)" else "") +
                                    (if (handshakeLog.isNotEmpty()) "\n" + handshakeLog.joinToString("\n") else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                    state.transferError?.let { reason ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "Transfer failed: $reason",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                    state.infoMessage?.let { info ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                    val popupColor = state.detectedPeerColor?.displayColor
                    if (popupColor != null) {
                        ScanCompletePopup(
                            visible = state.colorDetectionState == ColorDetectionState.Locked,
                            lockedColor = popupColor,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ── Dialogs ──────────────────────────────────────────────────────────────
        if (state.showHotspotPrompt) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissHotspotPrompt() },
                title = { Text(stringResource(R.string.hotspot_title)) },
                text = {
                    Text(stringResource(R.string.hotspot_subtitle) + "\n\n" + stringResource(R.string.hotspot_description))
                },
                confirmButton = {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        viewModel.dismissHotspotPrompt()
                    }) { Text(stringResource(R.string.hotspot_button_enable)) }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissHotspotPrompt() }) {
                        Text(stringResource(R.string.hotspot_button_cancel))
                    }
                }
            )
        }
        if (state.corruptedPhotos.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissCorruptionAlert() },
                title = { Text(stringResource(R.string.corruption_title)) },
                text = {
                    Text(stringResource(R.string.corruption_description) + "\n\n" + stringResource(R.string.corruption_question))
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.retryCorruptedPhotos(context) }) {
                        Text(stringResource(R.string.corruption_button_retry))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissCorruptionAlert() }) {
                        Text(stringResource(R.string.corruption_button_skip))
                    }
                }
            )
        }
        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }) {
                SettingsContent(themeRepository, onOpenSimulation = { showSimulation = true })
            }
        }
        if (showSimulation) {
            com.example.flash.ui.workbench.SimulationDialog(viewModel, onDismiss = { showSimulation = false; showSettings = false })
        }
    }
}

/** The tray at the top: pairing beacon / camera, then the photos being sent or received. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Tray(
    state: WorkbenchUiState,
    viewfinderActive: Boolean,
    viewModel: WorkbenchViewModel,
    bigIndices: Set<Int>,
    onRevealed: (Int) -> Unit,
    onRemove: (android.net.Uri) -> Unit
) {
    val receiving = state.isReceiving || state.receivingPhotos.isNotEmpty()
    val rawProgress = if (receiving) state.transferProgress else state.sendProgress
    // Progress arrives in jumps: ease it with a critically damped (non-bouncy) spring
    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "tray_progress"
    )
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val sender = state.selectedPhotos.isNotEmpty() && !receiving
    val tiles = if (state.receivingPhotos.isNotEmpty()) state.receivingPhotos else state.selectedPhotos.toList()
    val incomingTiles = state.incoming

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = when {
                    receiving -> "Receiving…"
                    sender && state.sendProgress > 0f -> "Sending…"
                    sender -> "Ready to send"
                    viewfinderActive && state.detectedPeerColor != null -> "Point at their phone"
                    viewfinderActive -> "Looking for devices…"
                    else -> "Pick photos to send"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Camera pairing: the receiver's live view, or the sender's colour beacon
            if (viewfinderActive) {
                Box(Modifier.size(200.dp).clip(cookie)) {
                    MotherCoreViewfinder(
                        targetColor = state.detectedPeerColor?.displayColor,
                        onMatchStrengthChanged = { viewModel.onDetectionStrengthChanged(it) },
                        onColorLocked = { viewModel.onColorLocked() }
                    )
                }
            } else if (sender && state.cameraHandshakeEnabled && state.displayColor != null && state.sendProgress < 1f) {
                // Flat colour on purpose: the other phone's camera matches its hue
                Box(
                    Modifier
                        .size(200.dp)
                        .clip(cookie)
                        .background(Color(state.displayColor!!))
                )
            } else if (state.sessionColor != null || (state.colorDetectionState == ColorDetectionState.Locked && state.detectedPeerColor != null)) {
                Box(
                    Modifier
                        .size(200.dp)
                        .clip(cookie)
                        .background(Color(state.sessionColor ?: state.detectedPeerColor!!.displayColor))
                )
            }

            if (incomingTiles.isNotEmpty()) {
                // Photos on their way: thumbnail first, then they wipe up once complete. Tap to open one big.
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(incomingTiles, key = { it.index }) { photo ->
                        RevealCard(
                            photo = photo,
                            useBands = false,
                            holdMs = 0,
                            cropToFill = true,
                            rejected = photo.verified == false,
                            // A photo shown big reports through its big card instead
                            onFullyRevealed = { if (photo.index !in bigIndices) onRevealed(photo.index) },
                            modifier = Modifier
                                .size(96.dp)
                                .clickable(enabled = incomingTiles.size > 2) {
                                    if (photo.open) viewModel.closeIncoming(photo.index) else viewModel.openIncoming(photo.index)
                                }
                        )
                    }
                }
            } else if (tiles.isEmpty()) {
                Text(
                    text = "Tap photos below to add them here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tiles, key = { it.toString() }) { uri ->
                        TrayTile(
                            uri = uri,
                            progress = if (rawProgress > 0f) progress else -1f,
                            rejected = uri in state.rejectedPhotos,
                            onClick = { if (!receiving && state.sendProgress == 0f) onRemove(uri) }
                        )
                    }
                }
            }

            if (progress > 0f && progress < 0.999f) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                )
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** A tray photo. While [progress] is 0..1 the unlit part is dimmed and the light rises from the bottom. */
@Composable
private fun TrayTile(uri: android.net.Uri, progress: Float, rejected: Boolean = false, onClick: () -> Unit) {
    val done = progress >= 0.999f
    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (progress in 0f..1f && !done) {
                        // Soft cross-faded edge, not a hard line
                        val band = 0.35f
                        val top = ((1f - progress) * (1f + band) - band).coerceIn(0f, 1f)
                        val bottom = (top + band).coerceIn(0f, 1f)
                        val dark = Color.Black.copy(alpha = 0.62f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to dark,
                                    top to dark,
                                    bottom to Color.Transparent,
                                    1f to Color.Transparent
                                )
                            ),
                            size = size
                        )
                    }
                }
        )
        if (rejected) {
            Box(Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.5f)))
        }
        if (done && !rejected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(4.dp)
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = item == selected,
                    onClick = { onSelect(item) },
                    shape = SegmentedButtonDefaults.itemShape(index, items.size),
                    label = { Text(label(item)) }
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(themeRepository: ThemeRepository, onOpenSimulation: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val themeMode by themeRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val handshake by themeRepository.handshakeMode.collectAsStateWithLifecycle(HandshakeMode.AUTO)
    val uiStyle by themeRepository.uiStyle.collectAsStateWithLifecycle(UiStyle.EXPRESSIVE)
    val language by themeRepository.language.collectAsStateWithLifecycle("")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        ChoiceRow(
            title = stringResource(R.string.settings_theme),
            items = ThemeMode.entries,
            selected = themeMode,
            label = {
                when (it) {
                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                }
            },
            onSelect = { scope.launch { themeRepository.setThemeMode(it) } }
        )
        ChoiceRow(
            title = stringResource(R.string.settings_handshake),
            items = HandshakeMode.entries,
            selected = handshake,
            label = {
                when (it) {
                    HandshakeMode.AUTO -> stringResource(R.string.handshake_auto)
                    HandshakeMode.NFC -> stringResource(R.string.handshake_nfc)
                    HandshakeMode.CAMERA -> stringResource(R.string.handshake_camera)
                }
            },
            onSelect = { scope.launch { themeRepository.setHandshakeMode(it) } }
        )
        ChoiceRow(
            title = stringResource(R.string.settings_ui_style),
            items = UiStyle.entries,
            selected = uiStyle,
            label = {
                when (it) {
                    UiStyle.LIQUID -> stringResource(R.string.ui_style_liquid)
                    UiStyle.EXPRESSIVE -> stringResource(R.string.ui_style_expressive)
                }
            },
            onSelect = { scope.launch { themeRepository.setUiStyle(it) } }
        )
        ChoiceRow(
            title = stringResource(R.string.settings_language),
            items = listOf("en", "ru"),
            selected = if (language.isEmpty()) "en" else language,
            label = { if (it == "en") stringResource(R.string.lang_en) else stringResource(R.string.lang_ru) },
            onSelect = { lang ->
                scope.launch {
                    themeRepository.setLanguage(lang)
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
                }
            }
        )

        androidx.compose.material3.TextButton(onClick = onOpenSimulation) { Text("Simulation…") }

        val versionText = remember {
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                "Flash ${info.versionName} (build ${info.longVersionCode})"
            }.getOrDefault("Flash")
        }
        Text(
            versionText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PhotoGridArea(state: WorkbenchUiState, viewModel: WorkbenchViewModel) {
    val context = LocalContext.current
    LazyVerticalGrid(
                        columns = GridCells.Adaptive(104.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.photos, key = { it.toString() }) { uri ->
                            val selected = uri in state.selectedPhotos
                            Box(
                                Modifier
                                    .animateItem()   // neighbours glide apart when a photo is inserted
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable {
                                        if (selected) viewModel.onPhotoRemovedFromOrbit(uri)
                                        else viewModel.onPhotoAddedToOrbit(uri, context)
                                    }
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (selected) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                    )
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(24.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                                            .padding(3.dp)
                                    )
                                }
                            }
                        }
                    }
}

/** One or two photos (or a tray photo you tapped) shown almost full screen, stacked one above the other. */
@Composable
private fun BigCards(
    state: WorkbenchUiState,
    viewModel: WorkbenchViewModel,
    bigIncoming: List<IncomingPhoto>,
    bigSending: List<android.net.Uri>,
    onRevealed: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (bigIncoming.isNotEmpty()) {
            bigIncoming.forEach { photo ->
                FittedCard(
                    aspect = photo.aspect,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) { cardModifier ->
                    RevealCard(
                        photo = photo,
                        useBands = true,
                        holdMs = 1500,
                        cropToFill = false,
                        rejected = photo.verified == false,
                        onFullyRevealed = { onRevealed(photo.index) },
                        modifier = cardModifier.clickable(enabled = state.incoming.size > 2) {
                            viewModel.closeIncoming(photo.index)   // tapping an opened tray photo closes it again
                        }
                    )
                }
            }
        } else {
            bigSending.forEach { uri ->
                SendCard(
                    uri = uri,
                    progress = if (state.sendProgress > 0f) state.sendProgress else -1f,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}
