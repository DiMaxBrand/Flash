package com.example.flash.ui.workbench

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.example.flash.ui.expressive.FittedCard
import com.example.flash.ui.expressive.RevealContent
import kotlin.math.ceil
import kotlin.math.roundToInt

private val GRID_PAD = 8.dp
private val GRID_GAP = 4.dp

/** The photo being revealed inside the Mother Core (clipped to the blob by MotherCore itself). */
@Composable
fun LiquidFeatured(photo: IncomingPhoto, viewModel: WorkbenchViewModel) {
    key(photo.index) {
        Box(Modifier.fillMaxSize().padding(26.dp)) {
            FittedCard(photo.aspect, Modifier.fillMaxSize()) { mod ->
                RevealContent(
                    photo = photo,
                    useBands = photo.tier <= 1,
                    holdMs = if (photo.tier == 0) 250L else 0L,
                    cropToFill = false,
                    onFullyRevealed = { viewModel.onFeaturedRevealed(photo.index) },
                    modifier = mod,
                    wipeStiffness = when (photo.tier) { 0 -> 10f; 1 -> 22f; else -> 60f }
                )
            }
        }
    }
}

/**
 * Everything of the Liquid receive session that lives below the (enlarged) core: the session's
 * photo cells, the flights from the core into those cells, and the two dialogs. Drawn in the outer
 * Box, so [coreCenter] and every position here are in window-inset-inclusive coordinates.
 *
 * @param coreCenter centre of the core at scale 1
 * @param coreSizePx side of the core container at scale 1
 * @param coreScale current core scale (the core grows from its top edge)
 */
@Composable
fun LiquidSessionLayer(
    state: WorkbenchUiState,
    viewModel: WorkbenchViewModel,
    coreCenter: Offset,
    coreSizePx: Float,
    coreScale: Float
) {
    val photos = state.incoming.sortedBy { it.index }
    if (photos.isEmpty() || state.legacyReceive) return
    val density = LocalDensity.current
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val pad = with(density) { GRID_PAD.toPx() }
    val gap = with(density) { GRID_GAP.toPx() }
    val cell = (screenW - pad * 2 - gap * 2) / 3f

    val coreTop = coreCenter.y - coreSizePx / 2f
    val scaledCoreCenter = Offset(coreCenter.x, coreTop + coreSizePx * coreScale / 2f)
    val gridTop = coreTop + coreSizePx * 2f + with(density) { 8.dp.toPx() }
    val scroll = rememberScrollState()
    val rows = ceil(photos.size / 3f).toInt()
    val contentH = rows * cell + (rows - 1).coerceAtLeast(0) * gap + pad

    val ending = !state.liquidSession
    val layerAlpha by animateFloatAsState(
        targetValue = if (ending) 0f else 1f,
        animationSpec = tween(900, delayMillis = if (ending) 500 else 0),
        label = "session_alpha"
    )

    Box(Modifier.fillMaxSize().alpha(layerAlpha)) {
        Box(
            Modifier
                .offset { IntOffset(0, gridTop.roundToInt()) }
                .fillMaxWidth()
                .height(with(density) { (contentH).toDp() }.coerceAtMost(
                    with(density) { (LocalConfiguration.current.screenHeightDp.dp) }
                ))
                .verticalScroll(scroll)
        ) {
            Box(Modifier.fillMaxWidth().height(with(density) { contentH.toDp() })) {
                photos.forEachIndexed { pos, photo ->
                    val x = pad + (pos % 3) * (cell + gap)
                    val y = (pos / 3) * (cell + gap)
                    SessionCell(
                        photo = photo,
                        modifier = Modifier
                            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                            .size(with(density) { cell.toDp() }),
                        onTap = { viewModel.explainDamaged(photo.index) }
                    )
                }
            }
        }

        photos.forEachIndexed { pos, photo ->
            if (photo.stage == Stage.FLYING) {
                key(photo.index) {
                    val end = Offset(
                        pad + (pos % 3) * (cell + gap) + cell / 2f,
                        gridTop + (pos / 3) * (cell + gap) + cell / 2f - scroll.value
                    )
                    FlightPhoto(
                        photo = photo,
                        start = scaledCoreCenter,
                        startSize = coreSizePx * coreScale * (120f / 172f),
                        end = end,
                        endSize = cell,
                        durationMs = when (photo.tier) { 0 -> 900; 1 -> 550; else -> 380 },
                        onFinished = { viewModel.onFlightFinished(photo.index) }
                    )
                }
            }
        }
    }

    state.explainIndex?.let {
        AlertDialog(
            onDismissRequest = { viewModel.dismissExplain() },
            confirmButton = { TextButton(onClick = { viewModel.dismissExplain() }) { Text("OK") } },
            text = {
                Text(
                    "This photo has been damaged while transferring, but don't worry, everything is safe, " +
                        "and we are retrying the send right now."
                )
            }
        )
    }
    if (state.unverifiedCount > 0 && !state.liquidSession) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnverified() },
            confirmButton = { TextButton(onClick = { viewModel.dismissUnverified() }) { Text("OK") } },
            text = {
                Text(
                    (if (state.unverifiedCount == 1) "One photo couldn't" else "${state.unverifiedCount} photos couldn't") +
                        " be proved to be transferred successfully. Check if it looks good, and if it looks good, " +
                        "then you can ignore this message."
                )
            }
        )
    }
}

@Composable
private fun SessionCell(photo: IncomingPhoto, modifier: Modifier, onTap: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val shown = photo.stage == Stage.LANDED || photo.stage == Stage.PARKED
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (photo.tier >= 2) 250 else 150), label = "cell_alpha")
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
        if (alpha > 0f) {
            Box(Modifier.fillMaxSize().alpha(alpha)) {
                if (photo.kind == MediaKind.FILE) {
                    FileTile(photo.name, video = false, modifier = Modifier.fillMaxSize())
                } else {
                    photo.thumb?.let {
                        Image(
                            bitmap = it.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().blur(14.dp)
                        )
                    }
                    if (photo.kind == MediaKind.VIDEO) {
                        photo.poster?.let {
                            Image(
                                bitmap = it.asImageBitmap(), contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                        }
                        PlayMark(Modifier.align(Alignment.Center))
                    } else if (photo.stage != Stage.PARKED) {
                        photo.originalUri?.let {
                            AsyncImage(
                                model = it, contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                // Videos and files: the real download is still running, dim what has not arrived yet
                if (photo.kind != MediaKind.IMAGE && photo.verified == null && photo.stage != Stage.PARKED) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f * (1f - photo.progress))))
                    Text(
                        "${(photo.progress * 100).toInt()}%", color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                    )
                }
                if (photo.stage == Stage.PARKED) {
                    Box(Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.45f)).clickable(onClick = onTap))
                }
            }
        }
    }
}

/** A photo morphing from the round core into its rounded grid cell. */
@Composable
private fun FlightPhoto(
    photo: IncomingPhoto,
    start: Offset, startSize: Float,
    end: Offset, endSize: Float,
    durationMs: Int,
    onFinished: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
        onFinished()
    }
    val density = LocalDensity.current
    val p = progress.value
    val size = lerp(startSize, endSize, p)
    val cx = lerp(start.x, end.x, p)
    val cy = lerp(start.y, end.y, p)
    val corner = lerp(size / 2f, with(density) { 12.dp.toPx() }, p)
    Box(
        Modifier
            .offset { IntOffset((cx - size / 2f).roundToInt(), (cy - size / 2f).roundToInt()) }
            .size(with(density) { size.toDp() })
            .clip(RoundedCornerShape(with(density) { corner.toDp() }))
    ) {
        photo.thumb?.let {
            Image(
                bitmap = it.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(14.dp)
            )
        }
        photo.originalUri?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
