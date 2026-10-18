package com.example.flash.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.graphics.shapes.Morph
import com.example.flash.R
import com.example.flash.update.UpdateChannel
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialShapes
import kotlinx.coroutines.launch

@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalSharedTransitionApi::class,
)
@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    onChannelSelected: (UpdateChannel) -> Unit,
    onAutoCheckToggled: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onMinIntervalSelected: (Int) -> Unit = {},
    onAllowInstallApps: () -> Unit = {},
    // Flash: the settings groups come first on the same scrolling page; the update settings follow
    header: @Composable ColumnScope.() -> Unit = {},
) {
    var intervalPickerVisible by remember { mutableStateOf(false) }
    var channelPickerVisible by remember { mutableStateOf(false) }
    var infoChannel by remember { mutableStateOf<UpdateChannel?>(null) }

    // Reset info page whenever the picker closes
    LaunchedEffect(channelPickerVisible) {
        if (!channelPickerVisible) infoChannel = null
    }

    // Scrim animates independently of the shared-element transition
    val scrimAlpha by animateFloatAsState(
        targetValue = if (channelPickerVisible || intervalPickerVisible) 0.32f else 0f,
        animationSpec = spring(stiffness = 1600f, dampingRatio = 1.0f),
        label = "scrim_alpha",
    )

    // Outer SharedTransitionLayout: settings channel row ↔ picker dialog
    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ── Settings column ───────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                header()

                Text(
                    text = stringResource(R.string.updates_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                )

                ExpressiveGroupRow(GroupPosition.SINGLE) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.updates_current_version_label)) },
                        trailingContent = {
                            Text(
                                text = state.currentVersion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }

                Spacer(Modifier.height(6.dp))

                // Channel row — source of the settings→picker container transform
                AnimatedVisibility(
                    visible = !channelPickerVisible,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sharedBounds(
                                rememberSharedContentState("channel_picker"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                enter = fadeIn(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                                exit = fadeOut(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                                boundsTransform = BoundsTransform { _, _ ->
                                    spring(stiffness = 380f, dampingRatio = 0.8f)
                                },
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                            )
                            .clickable { channelPickerVisible = true },
                    ) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.updates_channel_label)) },
                            supportingContent = {
                                Text(
                                    text = stringResource(channelDisplayName(state.selectedChannel)),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                ExpressiveGroupRow(GroupPosition.TOP) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.updates_auto_check_label)) },
                        trailingContent = {
                            Switch(
                                checked = state.autoCheck,
                                onCheckedChange = onAutoCheckToggled,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                // Without the "install unknown apps" permission Flash cannot install an update.
                // Shown only while it is off.
                if (!state.canInstallApps) {
                    ExpressiveGroupRow(GroupPosition.MIDDLE) {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.updates_allow_install_label))
                            },
                            supportingContent = {
                                Text(stringResource(R.string.updates_allow_install_description))
                            },
                            trailingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickableRow(onClick = onAllowInstallApps),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
                // Source of the row -> picker container transform, same technique as the channel
                // row above. The row leaves layout while the card is up, so a Spacer of its own
                // measured height holds its place -- otherwise the "Check now" row below would
                // jump up under the scrim.
                val rowDensity = LocalDensity.current
                var intervalRowHeightPx by remember { mutableStateOf(0) }
                Box {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !intervalPickerVisible,
                        enter = EnterTransition.None,
                        exit = ExitTransition.None,
                    ) {
                        ExpressiveGroupRow(
                            GroupPosition.MIDDLE,
                            modifier = Modifier
                                .onSizeChanged { intervalRowHeightPx = it.height }
                                .sharedBounds(
                                    rememberSharedContentState("interval_picker"),
                                    animatedVisibilityScope = this@AnimatedVisibility,
                                    enter = fadeIn(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                                    exit = fadeOut(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                                    boundsTransform = BoundsTransform { _, _ ->
                                        spring(stiffness = 380f, dampingRatio = 0.8f)
                                    },
                                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                ),
                        ) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.updates_wait_label)) },
                                supportingContent = {
                                    Text(
                                        text = stringResource(waitOptionLabel(state.minUpdateIntervalHours)),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_expand_more_24dp),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                modifier = Modifier.clickableRow(onClick = { intervalPickerVisible = true }),
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                    if (intervalPickerVisible) {
                        Spacer(Modifier.height(with(rowDensity) { intervalRowHeightPx.toDp() }))
                    }
                }
                ExpressiveGroupRow(GroupPosition.BOTTOM) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.updates_check_now)) },
                        modifier = Modifier.clickableRow(onClick = onCheckNow),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }

                Spacer(Modifier.height(12.dp))

                val mainText = mainStatusText(state)
                AnimatedContent(
                    targetState = mainText,
                    transitionSpec = {
                        (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                                (slideOutHorizontally { it / 3 } + fadeOut())
                    },
                    label = "main_status_text",
                ) { text ->
                    if (text != null) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Box(Modifier.fillMaxWidth())
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            // ── Scrim (animated independently) ───────────────────────────
            if (scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrimAlpha)),
                )
            }

            // ── Channel picker overlay — destination of container transform ─
            AnimatedVisibility(
                visible = channelPickerVisible,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { channelPickerVisible = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .fillMaxWidth()
                            .sharedBounds(
                                rememberSharedContentState("channel_picker"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                enter = fadeIn(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                                exit = fadeOut(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                                boundsTransform = BoundsTransform { _, _ ->
                                    spring(stiffness = 380f, dampingRatio = 0.8f)
                                },
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                            )
                            // Consume touches so tapping inside the picker doesn't dismiss it
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {},
                    ) {
                        // Inner SharedTransitionLayout: channel list row ↔ info page
                        SharedTransitionLayout {
                            AnimatedContent(
                                targetState = infoChannel,
                                transitionSpec = {
                                    EnterTransition.None togetherWith ExitTransition.None using
                                            SizeTransform(clip = false) { _, _ ->
                                                spring(stiffness = 380f, dampingRatio = 0.8f)
                                            }
                                },
                                label = "channel_picker_content",
                            ) { channel ->
                                if (channel == null) {
                                    ChannelList(
                                        selectedChannel = state.selectedChannel,
                                        onChannelSelected = { ch ->
                                            onChannelSelected(ch)
                                            channelPickerVisible = false
                                        },
                                        onInfoClicked = { infoChannel = it },
                                        animatedContentScope = this,
                                    )
                                } else {
                                    ChannelInfoPage(
                                        channel = channel,
                                        onBack = { infoChannel = null },
                                        animatedContentScope = this,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Wait-between-updates picker overlay — destination of its own container
            // transform (same spring and fades as the channel picker above) ─
            AnimatedVisibility(
                visible = intervalPickerVisible,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { intervalPickerVisible = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .fillMaxWidth()
                            .sharedBounds(
                                rememberSharedContentState("interval_picker"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                enter = fadeIn(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                                exit = fadeOut(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                                boundsTransform = BoundsTransform { _, _ ->
                                    spring(stiffness = 380f, dampingRatio = 0.8f)
                                },
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                            )
                            // Consume touches so tapping inside the card doesn't dismiss it
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) {},
                    ) {
                        MinIntervalContent(
                            selectedHours = state.minUpdateIntervalHours,
                            onSelect = { hours ->
                                onMinIntervalSelected(hours)
                                intervalPickerVisible = false
                            },
                        )
                    }
                }
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = intervalPickerVisible) {
        intervalPickerVisible = false
    }

    // Download-progress UI (the sheet) is no longer rendered inline here -- it's the shared
    // UpdateSheetFragment, shown by UpdatesActivity via FragmentManager. See
    // UpdatesActivity's class doc for why.
}

// ─── Channel picker: list ────────────────────────────────────────────────────

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SharedTransitionScope.ChannelList(
    selectedChannel: UpdateChannel,
    onChannelSelected: (UpdateChannel) -> Unit,
    onInfoClicked: (UpdateChannel) -> Unit,
    animatedContentScope: AnimatedContentScope,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 20.dp, bottom = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_filter_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.updates_channel_label),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))

        val channels = UpdateChannel.entries
        channels.forEachIndexed { index, channel ->
            val position = when {
                channels.size == 1 -> GroupPosition.SINGLE
                index == 0 -> GroupPosition.TOP
                index == channels.lastIndex -> GroupPosition.BOTTOM
                else -> GroupPosition.MIDDLE
            }
            val rowShape = when (position) {
                GroupPosition.TOP -> RoundedCornerShape(28.dp, 28.dp, 8.dp, 8.dp)
                GroupPosition.MIDDLE -> RoundedCornerShape(8.dp)
                GroupPosition.BOTTOM -> RoundedCornerShape(8.dp, 8.dp, 28.dp, 28.dp)
                GroupPosition.SINGLE -> RoundedCornerShape(28.dp)
            }
            // Each row's Surface is the source of its own row→info container transform
            Surface(
                shape = rowShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .sharedBounds(
                        rememberSharedContentState("channel_info_$channel"),
                        animatedVisibilityScope = animatedContentScope,
                        enter = fadeIn(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                        exit = fadeOut(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                        boundsTransform = BoundsTransform { _, _ ->
                            spring(stiffness = 380f, dampingRatio = 0.8f)
                        },
                        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    ),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(channelDisplayName(channel))) },
                    leadingContent = {
                        RadioButton(
                            selected = channel == selectedChannel,
                            onClick = { onChannelSelected(channel) },
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onInfoClicked(channel) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_info_outline_24dp),
                                contentDescription = stringResource(R.string.update_channel_info_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier.clickableRow { onChannelSelected(channel) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (index < channels.lastIndex) Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ─── Channel picker: info page ────────────────────────────────────────────────

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SharedTransitionScope.ChannelInfoPage(
    channel: UpdateChannel,
    onBack: () -> Unit,
    animatedContentScope: AnimatedContentScope,
) {
    // Surface matches the source row surface (same color/shape) so the container transform
    // morphs the row surface into the full-width info card
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .sharedBounds(
                rememberSharedContentState("channel_info_$channel"),
                animatedVisibilityScope = animatedContentScope,
                enter = fadeIn(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                exit = fadeOut(spring(stiffness = 1600f, dampingRatio = 1.0f)),
                boundsTransform = BoundsTransform { _, _ ->
                    spring(stiffness = 380f, dampingRatio = 0.8f)
                },
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info_outline_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(channelDisplayName(channel)),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(channelDescription(channel)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            // Deliberately a static `shape=`, not the animated `shapes = ButtonDefaults.shapes()`
            // press-morph used elsewhere in this screen. This button's very first composition
            // happens while its container (the Surface above) is still mid shared-element
            // transition from the channel row — the press-morph's shape state seems to capture
            // that transient moment as its resting value, rendering fully square until the first
            // press/release cycle nudges it into the correct rounded shape. A fixed shape has no
            // such first-composition state to get stuck on, at the cost of losing the squish
            // animation on this one button.
            FilledTonalButton(
                onClick = onBack,
                shape = ButtonDefaults.filledTonalShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

// ─── Sheet content (reused by both UpdatesActivity and UpdateSheetFragment) ───

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheetContent(
    state: UpdatesUiState,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onInstall: () -> Unit,
    onConfirmInstall: () -> Unit,
    onDownloadCircleTapped: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The whole sheet scrolls as one unit now — release notes no longer carry their own
            // capped-height inner scroll (see ReleaseNotesSection), so without this an expanded
            // "What's new" panel could push content taller than the sheet's own bounds with no
            // way to reach the rest of it.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Hero section
        Spacer(Modifier.height(8.dp))
        Icon(
            painter = painterResource(R.drawable.ic_system_update_24dp),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.releaseTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        // "Version X is available" only makes sense before a download has started — once it's
        // downloading/ready, sheetStatusText below already says so. Also skipped whenever the
        // hero above is already showing a real release title, since that title leads with the
        // version by convention — this line would just repeat it right underneath.
        if (state.pendingVersion != null &&
            state.downloadPhase == DownloadPhase.IDLE &&
            !state.versionAlreadyShownInTitle()
        ) {
            Text(
                text = stringResource(R.string.updates_new_version_available, state.pendingVersion),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))

        StatusSection(
            state = state,
            onDownload = onDownload,
            onStop = onStop,
            onContinue = onContinue,
            onInstall = onInstall,
            onConfirmInstall = onConfirmInstall,
            onDownloadCircleTapped = onDownloadCircleTapped,
        )

        ReleaseNotesSection(state.releaseNotes)
    }
}

// The GitHub release body — collapsed by default (release descriptions run long: bilingual RU+EN
// sections plus dev notes per this project's release-notes convention). Rendered via
// markdownToAnnotatedString (MarkdownText.kt): headers/`code`/**bold**/*italic* — the styling this
// project's own release descriptions actually use — instead of showing the raw Markdown source
// with literal `#`/`*` characters.
//
// A full-width Expressive "reveal" button, not a plain TextButton: expanding it flattens its own
// bottom corners down to the same 8dp the channel/settings rows use for a TOP-positioned
// ExpressiveGroupRow, and the revealed panel below picks up the matching BOTTOM shape (28dp) —
// same connected-group language already established for those rows, just two pieces instead of a
// whole column of them. Deliberately a separate Surface with its own small gap (not one seamless
// merged shape) — the *panel* is what's being revealed here, not something inside the button.
@Composable
internal fun ReleaseNotesSection(releaseNotes: String?, modifier: Modifier = Modifier) {
    if (releaseNotes.isNullOrBlank()) return
    var expanded by remember(releaseNotes) { mutableStateOf(false) }
    val spatialSpring = spring<Float>(stiffness = 380f, dampingRatio = 0.8f)
    val bottomCorner by animateDpAsState(
        targetValue = if (expanded) 8.dp else 28.dp,
        animationSpec = spring(stiffness = 380f, dampingRatio = 0.8f),
        label = "whats_new_bottom_corner",
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spatialSpring,
        label = "whats_new_arrow",
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 28.dp,
                bottomStart = bottomCorner,
                bottomEnd = bottomCorner,
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.updates_whats_new),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(arrowRotation),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = 380f, dampingRatio = 0.8f)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = 380f, dampingRatio = 0.8f)) + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 8.dp,
                    topEnd = 8.dp,
                    bottomStart = 28.dp,
                    bottomEnd = 28.dp,
                ),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // No heightIn()/verticalScroll() of its own anymore — the whole sheet
                // (UpdateSheetContent's outer Column) scrolls as one unit instead, so this just
                // takes whatever height its text naturally needs.
                val bodyStyle = MaterialTheme.typography.bodySmall
                val codeColor = MaterialTheme.colorScheme.primary
                val codeBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                Text(
                    text = remember(releaseNotes, bodyStyle.fontSize, codeColor, codeBackground) {
                        markdownToAnnotatedString(releaseNotes, bodyStyle.fontSize, codeColor, codeBackground)
                    },
                    style = bodyStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

// ─── Status + Download flow ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
internal fun StatusSection(
    state: UpdatesUiState,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onInstall: () -> Unit,
    onConfirmInstall: () -> Unit,
    onDownloadCircleTapped: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = sheetStatusText(state),
            transitionSpec = {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 3 } + fadeOut())
            },
            label = "sheet_status_text",
        ) { text ->
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Speed/ETA — only populated while actively downloading (not paused/queued/failed),
        // see UpdatesActivity.pollDownload().
        AnimatedVisibility(visible = state.downloadSpeedText != null) {
            Text(
                text = state.downloadSpeedText ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SharedTransitionLayout {
            AnimatedContent(
                targetState = state.downloadPhase,
                transitionSpec = {
                    when {
                        // instant swap between identical circles — no animation needed
                        initialState == DownloadPhase.DOWNLOADING && targetState == DownloadPhase.PROCESSING ->
                            EnterTransition.None togetherWith ExitTransition.None
                        // sharedBounds drives these transitions
                        (initialState == DownloadPhase.NO_WIFI_PENDING && targetState == DownloadPhase.DOWNLOADING) ||
                        (initialState == DownloadPhase.PROCESSING && targetState == DownloadPhase.READY) ->
                            EnterTransition.None togetherWith ExitTransition.None using
                                SizeTransform(clip = false) { _, _ -> spring(stiffness = 380f, dampingRatio = 0.8f) }
                        else -> {
                            val spatial = spring<Float>(stiffness = 380f, dampingRatio = 0.8f)
                            val effects = spring<Float>(stiffness = 1600f, dampingRatio = 1.0f)
                            (fadeIn(effects) + scaleIn(spatial, initialScale = 0.88f)) togetherWith
                                (fadeOut(effects) + scaleOut(spatial, targetScale = 0.88f)) using
                                SizeTransform(clip = false) { _, _ -> spring(stiffness = 380f, dampingRatio = 0.8f) }
                        }
                    }
                },
                label = "download_phase",
            ) outerAC@{ phase ->
                val sharedSpring = BoundsTransform { _, _ -> spring<androidx.compose.ui.geometry.Rect>(stiffness = 380f, dampingRatio = 0.8f) }
                when (phase) {
                    DownloadPhase.IDLE -> Box(Modifier.fillMaxWidth())

                    DownloadPhase.NO_WIFI_PENDING -> {
                        Button(
                            onClick = onDownload,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .sharedBounds(
                                    rememberSharedContentState("download_circle"),
                                    animatedVisibilityScope = this@outerAC,
                                    boundsTransform = sharedSpring,
                                ),
                            contentPadding = PaddingValues(horizontal = 24.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download_24dp),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.updates_download),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    DownloadPhase.DOWNLOADING -> {
                        DownloadingCircle(
                            progress = state.downloadProgress,
                            cancelConfirm = state.cancelConfirmVisible,
                            onTap = onDownloadCircleTapped,
                            onStop = onStop,
                            onContinue = onContinue,
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState("download_circle"),
                                animatedVisibilityScope = this@outerAC,
                                boundsTransform = sharedSpring,
                            ),
                        )
                    }

                    DownloadPhase.PROCESSING -> {
                        ProcessingCircle(
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState("ready_expand"),
                                animatedVisibilityScope = this@outerAC,
                                boundsTransform = sharedSpring,
                            ),
                        )
                    }

                    DownloadPhase.CANCELING -> CancelingCircle()

                    DownloadPhase.READY -> {
                        val needsCard = !state.canInstallDirectly
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sharedBounds(
                                    rememberSharedContentState("ready_expand"),
                                    animatedVisibilityScope = this@outerAC,
                                    boundsTransform = sharedSpring,
                                ),
                        ) {
                            SharedTransitionLayout {
                                AnimatedContent(
                                    targetState = state.showInstallCard && needsCard,
                                    transitionSpec = {
                                        EnterTransition.None togetherWith ExitTransition.None using
                                            SizeTransform(clip = false) { _, _ -> spring(stiffness = 380f, dampingRatio = 0.8f) }
                                    },
                                    label = "install_card",
                                ) { showCard ->
                                    if (showCard) {
                                        InstallCard(
                                            isFirstTime = state.isFirstUpdate,
                                            canInstallDirectly = state.canInstallDirectly,
                                            onConfirm = onConfirmInstall,
                                            modifier = Modifier.sharedBounds(
                                                rememberSharedContentState("install_surface"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                boundsTransform = sharedSpring,
                                            ),
                                        )
                                    } else {
                                        Button(
                                            onClick = if (needsCard) onInstall else onConfirmInstall,
                                            shapes = ButtonDefaults.shapes(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(64.dp)
                                                .sharedBounds(
                                                    rememberSharedContentState("install_surface"),
                                                    animatedVisibilityScope = this@AnimatedContent,
                                                    boundsTransform = sharedSpring,
                                                ),
                                            contentPadding = PaddingValues(horizontal = 24.dp),
                                        ) {
                                            Text(
                                                stringResource(R.string.updates_proceed_to_install),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Download/processing/canceling circles ────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DownloadingCircle(
    progress: Float,
    cancelConfirm: Boolean,
    onTap: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val swipeDelta = remember { Animatable(0f) }
    // Which side is collapsing during the end animation — hides the opposite button
    var collapsingToward by remember { mutableStateOf<SwipeAction?>(null) }
    val amplitudePx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(stiffness = 80f, dampingRatio = 1.0f),
        label = "download_progress",
    )
    val stopFraction by animateFloatAsState(
        targetValue = (-swipeDelta.value / 80f).coerceIn(0f, 1f),
        animationSpec = spring(stiffness = 1600f, dampingRatio = 1.0f),
        label = "stop_tint",
    )
    // Pill expansion is driven entirely by cancelConfirm — no expansion on drag alone
    val expansionFraction by animateFloatAsState(
        targetValue = if (cancelConfirm) 1f else 0f,
        animationSpec = spring(stiffness = 380f, dampingRatio = 0.8f),
        label = "pill_expansion",
    )

    val circleColor = lerp(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.errorContainer,
        stopFraction,
    )
    val contentColor = lerp(
        MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.onErrorContainer,
        stopFraction,
    )
    val effects = spring<Float>(stiffness = 1600f, dampingRatio = 1.0f)
    val spatialSlide = spring<IntOffset>(stiffness = 380f, dampingRatio = 0.8f)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pillWidth = lerpDp(64.dp, maxWidth, expansionFraction)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = circleColor,
                onClick = onTap,
                modifier = modifier
                    .width(pillWidth)
                    .height(64.dp)
                    // Drag is only active after the pill is revealed (cancelConfirm = true)
                    .pointerInput(cancelConfirm) {
                        if (cancelConfirm) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        when {
                                            swipeDelta.value < -50f -> {
                                                // Snap indicator to left edge (collapse right side), then stop
                                                collapsingToward = SwipeAction.STOP
                                                swipeDelta.animateTo(-120f, spring(stiffness = 1200f, dampingRatio = 1.0f))
                                                onStop()
                                                collapsingToward = null
                                                swipeDelta.snapTo(0f)
                                            }
                                            swipeDelta.value > 50f -> {
                                                // Snap indicator to right edge (collapse left side), then continue
                                                collapsingToward = SwipeAction.CONTINUE
                                                swipeDelta.animateTo(120f, spring(stiffness = 1200f, dampingRatio = 1.0f))
                                                onContinue()
                                                collapsingToward = null
                                                swipeDelta.animateTo(0f, spring(stiffness = 500f, dampingRatio = 1.0f))
                                            }
                                            else -> swipeDelta.animateTo(0f, spring(stiffness = 500f, dampingRatio = 1.0f))
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { swipeDelta.animateTo(0f, spring(stiffness = 500f, dampingRatio = 1.0f)) }
                                },
                            ) { _, dragAmount ->
                                scope.launch {
                                    // Progressive resistance: gets heavier the further you pull
                                    val r = (1f - (kotlin.math.abs(swipeDelta.value) / 100f)).coerceAtLeast(0.15f)
                                    swipeDelta.snapTo((swipeDelta.value + dragAmount * r).coerceIn(-80f, 80f))
                                }
                            }
                        }
                    },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Stop button — left; hidden while collapsing toward Continue
                    AnimatedVisibility(
                        visible = cancelConfirm && collapsingToward != SwipeAction.CONTINUE,
                        enter = fadeIn(effects) + slideInHorizontally(spatialSlide) { -it / 2 },
                        exit = fadeOut(effects) + slideOutHorizontally(spatialSlide) { -it / 2 },
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onStop,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(stringResource(R.string.updates_stop)) }
                    }

                    // Progress indicator — physically follows the drag
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset { IntOffset(swipeDelta.value.toInt(), 0) },
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularWavyProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.size(40.dp),
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.22f),
                            amplitude = { amplitudePx },
                        )
                    }

                    // Continue button — right; hidden while collapsing toward Stop
                    AnimatedVisibility(
                        visible = cancelConfirm && collapsingToward != SwipeAction.STOP,
                        enter = fadeIn(effects) + slideInHorizontally(spatialSlide) { it / 2 },
                        exit = fadeOut(effects) + slideOutHorizontally(spatialSlide) { it / 2 },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onContinue,
                            shapes = ButtonDefaults.shapes(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) { Text(stringResource(R.string.updates_continue)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProcessingCircle(modifier: Modifier = Modifier) {
    var showAlmostDone by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { showAlmostDone = true },
            modifier = modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val amplitudePx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.22f),
                    amplitude = amplitudePx,
                )
            }
        }
        AnimatedVisibility(visible = showAlmostDone) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2000)
                showAlmostDone = false
            }
            Text(
                text = stringResource(R.string.updates_almost_done),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CancelingCircle() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CancelMorphIndicator(
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

// The requested loop, specifically — not the stock M3 LoadingIndicator (which cycles through its
// own built-in shape set, not this one), and not a reusable everyday spinner either: fan -> arrow
// -> semi-circle -> arrow -> fan, repeating for as long as CANCELING is shown. Arrow sitting
// between the two rounder shapes is what gives the whole loop its "turning" read — an inherent
// property of how Morph interpolates between these three vertex sets, not an added rotation
// transform. Same Morph/MaterialShapes technique as the Developer Options shape catalog, but a
// dedicated, self-contained implementation here rather than a shared component: this one drives
// itself on a timer in a fixed loop, not from taps, so reusing ShapeCatalogActivity's
// tap-queueing MorphingShape would be the wrong shape (no pun intended) for this job.
private val CANCEL_MORPH_SEQUENCE: List<androidx.graphics.shapes.RoundedPolygon> by lazy {
    listOf(
        MaterialShapes.Fan,
        MaterialShapes.Arrow,
        MaterialShapes.SemiCircle,
        MaterialShapes.Arrow,
    )
}

@Composable
private fun CancelMorphIndicator(color: Color, modifier: Modifier = Modifier) {
    var index by remember { mutableIntStateOf(0) }
    val morphProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            morphProgress.snapTo(0f)
            morphProgress.animateTo(
                targetValue = 1f,
                // A little overshoot, not the catalog's full bounce — this loop repeats
                // continuously for as long as the cancel screen is up, so DampingRatioMediumBouncy
                // (the catalog's feel) would read as jittery on repeat. LowBouncy gives just enough
                // spring to feel alive without that jitter. StiffnessMedium (1500) was a mistake
                // here, not a "brisk" version of the catalog's StiffnessLow (200) — at that
                // stiffness the spring settles almost instantly regardless of damping ratio, so
                // the overshoot was never actually visible; this needs to be *at least* as
                // unhurried as the catalog, just less bouncy, not faster.
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            )
            index = (index + 1) % CANCEL_MORPH_SEQUENCE.size
        }
    }

    val fromShape = CANCEL_MORPH_SEQUENCE[(index - 1 + CANCEL_MORPH_SEQUENCE.size) % CANCEL_MORPH_SEQUENCE.size]
    val toShape = CANCEL_MORPH_SEQUENCE[index]
    val morph = remember(fromShape, toShape) { Morph(fromShape, toShape) }
    val reusedPath = remember { Path() }
    val reusedMatrix = remember { android.graphics.Matrix() }

    Canvas(modifier = modifier) {
        // Same 78%-scale-with-margin treatment as the shape catalog — Morph interpolation between
        // very different silhouettes can bulge past both endpoints' own [0,1] bounds mid-transition,
        // and filling the canvas edge-to-edge means that overshoot gets clipped by the canvas
        // itself instead of just breathing into the margin.
        val drawScale = 0.78f
        val margin = (1f - drawScale) / 2f
        reusedMatrix.reset()
        reusedMatrix.postScale(size.width * drawScale, size.height * drawScale)
        reusedMatrix.postTranslate(size.width * margin, size.height * margin)
        morph.toPath(morphProgress.value, reusedPath)
        reusedPath.asAndroidPath().transform(reusedMatrix)
        clipPath(reusedPath) { drawRect(color) }
    }
}

// ─── Install card ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallCard(
    isFirstTime: Boolean,
    canInstallDirectly: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_system_update_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(
                    if (isFirstTime) R.string.updates_install_first_time_title
                    else R.string.updates_install_title
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    if (!canInstallDirectly) R.string.updates_install_grant_permission
                    else R.string.updates_install_ready
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.updates_install_now))
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

enum class GroupPosition { TOP, MIDDLE, BOTTOM, SINGLE }
private enum class SwipeAction { STOP, CONTINUE }

@Composable
fun ExpressiveGroupRow(
    position: GroupPosition,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = when (position) {
        GroupPosition.TOP -> RoundedCornerShape(28.dp, 28.dp, 8.dp, 8.dp)
        GroupPosition.MIDDLE -> RoundedCornerShape(8.dp)
        GroupPosition.BOTTOM -> RoundedCornerShape(8.dp, 8.dp, 28.dp, 28.dp)
        GroupPosition.SINGLE -> RoundedCornerShape(28.dp)
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        content()
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

fun channelDisplayName(channel: UpdateChannel): Int = when (channel) {
    UpdateChannel.STABLE -> R.string.update_channel_stable
    UpdateChannel.BETA -> R.string.update_channel_beta
}

private fun channelDescription(channel: UpdateChannel): Int = when (channel) {
    UpdateChannel.STABLE -> R.string.update_channel_stable_description
    UpdateChannel.BETA -> R.string.update_channel_beta_description
}

// The hero already shows the release title, which leads with the version number, so once a title
// is present, repeating the version underneath would show the same string twice. Only worth
// calling out separately when the hero has nothing to show it with — a blank title, which falls
// back to the plain "Flash" wordmark with no version anywhere.
private fun UpdatesUiState.versionAlreadyShownInTitle(): Boolean = !releaseTitle.isNullOrBlank()

@Composable
private fun mainStatusText(state: UpdatesUiState): String? = when {
    state.checkStatus == CheckStatus.CHECKING -> stringResource(R.string.updates_status_checking)
    state.checkStatus == CheckStatus.UP_TO_DATE -> stringResource(R.string.updates_status_up_to_date)
    state.checkStatus == CheckStatus.CHANNEL_BEHIND -> stringResource(R.string.updates_status_channel_behind)
    state.checkStatus == CheckStatus.CHECK_FAILED -> stringResource(R.string.updates_status_check_failed)
    state.pendingVersion != null &&
        state.downloadPhase == DownloadPhase.IDLE &&
        !state.versionAlreadyShownInTitle() ->
        stringResource(R.string.updates_new_version_available, state.pendingVersion)
    else -> null
}

@Composable
private fun sheetStatusText(state: UpdatesUiState): String? = when {
    state.downloadPhase == DownloadPhase.CANCELING -> stringResource(R.string.updates_canceling)
    state.cancelConfirmVisible -> stringResource(R.string.updates_stop_download_question)
    state.downloadPhase == DownloadPhase.NO_WIFI_PENDING ->
        if (state.pendingVersion != null && !state.versionAlreadyShownInTitle()) {
            stringResource(R.string.updates_status_no_wifi_detected, state.pendingVersion)
        } else {
            stringResource(R.string.updates_status_no_wifi_detected_unknown_version)
        }
    state.downloadPhase == DownloadPhase.DOWNLOADING ->
        state.downloadStatusText
            ?: if (state.pendingVersion != null && !state.versionAlreadyShownInTitle()) {
                stringResource(R.string.updates_status_downloading, state.pendingVersion)
            } else {
                stringResource(R.string.updates_status_downloading_unknown_version)
            }
    state.downloadPhase == DownloadPhase.PROCESSING ->
        if (state.pendingVersion != null && !state.versionAlreadyShownInTitle()) {
            stringResource(R.string.updates_status_processing, state.pendingVersion)
        } else {
            stringResource(R.string.updates_status_processing_unknown_version)
        }
    state.downloadPhase == DownloadPhase.READY &&
        state.pendingVersion != null &&
        !state.versionAlreadyShownInTitle() ->
        stringResource(R.string.updates_status_ready, state.pendingVersion)
    state.downloadPhase == DownloadPhase.READY ->
        stringResource(R.string.updates_status_ready_unknown_version)
    else -> null
}

// ─── State model ─────────────────────────────────────────────────────────────

enum class CheckStatus { IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, CHANNEL_BEHIND, CHECK_FAILED }
enum class DownloadPhase { IDLE, NO_WIFI_PENDING, DOWNLOADING, PROCESSING, READY, CANCELING }

data class UpdatesUiState(
    val currentVersion: String = "",
    val selectedChannel: UpdateChannel = UpdateChannel.BETA,
    val autoCheck: Boolean = true,
    val minUpdateIntervalHours: Int = 0,
    val canInstallApps: Boolean = true,
    val checkStatus: CheckStatus = CheckStatus.IDLE,
    val downloadPhase: DownloadPhase = DownloadPhase.IDLE,
    val downloadProgress: Float = 0f,
    val downloadStatusText: String? = null,
    val downloadSpeedText: String? = null,
    val cancelConfirmVisible: Boolean = false,
    val pendingVersion: String? = null,
    val releaseNotes: String? = null,
    val releaseTitle: String? = null,
    val showInstallCard: Boolean = false,
    val canInstallDirectly: Boolean = true,
    val isFirstUpdate: Boolean = false,
)

/** The "Wait between updates" choices: hours -> label. 0 = off. */
private val WAIT_OPTIONS = listOf(
    0 to R.string.updates_wait_off,
    1 to R.string.updates_wait_1h,
    6 to R.string.updates_wait_6h,
    12 to R.string.updates_wait_12h,
    24 to R.string.updates_wait_1d,
    72 to R.string.updates_wait_3d,
    168 to R.string.updates_wait_7d,
)

private fun waitOptionLabel(hours: Int): Int =
    WAIT_OPTIONS.firstOrNull { it.first == hours }?.second ?: R.string.updates_wait_off

/** The inside of the "Wait between updates" card; the Surface/transform shell is supplied by the
 * screen, same split as the channel picker. */
@Composable
private fun MinIntervalContent(
    selectedHours: Int,
    onSelect: (Int) -> Unit,
) {
    // Laid out exactly like ChannelList: hero icon + centered title on top, then one grouped
    // surface per option (top/middle/bottom shapes, radio button leading). Scrollable: seven
    // options plus the header is tall enough to overflow a small screen.
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 20.dp, bottom = 4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_schedule_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.updates_wait_label),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.updates_wait_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))

        WAIT_OPTIONS.forEachIndexed { index, (hours, labelRes) ->
            val position = when {
                index == 0 -> GroupPosition.TOP
                index == WAIT_OPTIONS.lastIndex -> GroupPosition.BOTTOM
                else -> GroupPosition.MIDDLE
            }
            ExpressiveGroupRow(position, modifier = Modifier.padding(horizontal = 12.dp)) {
                ListItem(
                    headlineContent = { Text(stringResource(labelRes)) },
                    leadingContent = {
                        RadioButton(selected = hours == selectedHours, onClick = { onSelect(hours) })
                    },
                    modifier = Modifier.clickableRow { onSelect(hours) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (index < WAIT_OPTIONS.lastIndex) Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}
