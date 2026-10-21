package com.dimax.flash.ui.workbench

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.dimax.flash.ui.core.updateBlobPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

// Golden angle — each new photo lands at a position that never bunches with others,
// regardless of how many photos are added over time.
private val GOLDEN_ANGLE = (PI * (3.0 - sqrt(5.0))).toFloat()

@Composable
fun PhotoOrbit(
    photos: List<Uri>,
    coreCenter: Offset,
    modifier: Modifier = Modifier,
    receivingPhotos: List<Uri> = emptyList(),
    transferProgress: Float = 0f,
    shouldExit: Boolean = false,
    rejectedPhotos: List<Uri> = emptyList(),
    sendProgress: Float = 0f,
    // Good received photos fly from the orbit to these grid-cell centres (px) and grow to the cell size
    landingTargets: Map<Uri, Offset> = emptyMap(),
    landingSizePx: Float = 0f,
    onPhotoLanded: (Uri) -> Unit = {}
) {
    val density = LocalDensity.current

    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "orbit")

    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_time"
    )

    // The photo edges' noise and the in-and-out drift used to restart from zero (the noise is not periodic and
    // the drift was a sawtooth), which snapped at every loop point. A clock that only moves forward, and a
    // smooth swing for the drift, have no seam. The orbit angle above is periodic, so it can stay.
    val seconds by com.dimax.flash.ui.core.rememberContinuousTime()
    val blobTime = (seconds * (1000.0 / 12.0)).toFloat()
    val radialDrift = with(density) { 8.dp.toPx() } * (0.5f - 0.5f * cos(2.0 * PI * seconds / 4.0).toFloat())

    val baseOrbitRadiusPx = with(density) { 100.dp.toPx() }
    val photoSizeDp = 56.dp
    val photoSizePx = with(density) { photoSizeDp.toPx() }

    // Sender: while files are going out, the photo grows to 2x and lights up bottom-to-top.
    // Progress arrives in jumps, so it is eased with a critically damped (non-bouncy) spring.
    val sending = sendProgress > 0f
    val sendScale by animateFloatAsState(
        targetValue = if (sending) 2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "send_scale"
    )
    val litProgress by animateFloatAsState(
        targetValue = sendProgress,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "send_lit"
    )

    val visiblePhotos = remember { mutableStateListOf<Uri>() }
    val exitingPhotos = remember { mutableStateSetOf<Uri>() }
    // Each URI gets a golden-angle phase assigned once on entry — never recalculated,
    // so adding a new photo cannot shift existing photos' positions.
    val phaseMap = remember { mutableStateMapOf<Uri, Float>() }
    var nextPhaseIndex by remember { mutableIntStateOf(0) }

    val successPulse = remember { Animatable(0f) }
    LaunchedEffect(transferProgress, receivingPhotos) {
        if (transferProgress < 1f && successPulse.value != 0f) {
            successPulse.snapTo(0f)  // Reset for next transfer
        } else if ((transferProgress >= 1f || receivingPhotos.isNotEmpty()) && successPulse.value == 0f) {
            successPulse.animateTo(0.5f, spring(dampingRatio = 0.5f, stiffness = 180f))
            successPulse.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 180f))
        }
    }

    LaunchedEffect(photos, receivingPhotos) {
        val allPhotos = (photos + receivingPhotos).distinct()
        allPhotos.forEach { uri ->
            if (uri !in visiblePhotos) {
                visiblePhotos.add(uri)
            }
            if (uri !in phaseMap) {
                phaseMap[uri] = nextPhaseIndex * GOLDEN_ANGLE
                nextPhaseIndex++
            }
        }
        visiblePhotos.filter { it !in allPhotos && it !in exitingPhotos }.forEach { uri ->
            exitingPhotos.add(uri)
        }
    }

    if (visiblePhotos.isEmpty()) return

    Box(modifier = modifier.fillMaxSize()) {
        visiblePhotos.toList().forEach { uri ->
            key(uri) {
                val phaseOffset = phaseMap[uri] ?: 0f
                val isExiting = uri in exitingPhotos || shouldExit
                val isReceiving = uri in receivingPhotos
                val rejectIndex = rejectedPhotos.indexOf(uri)
                OrbitPhotoItem(
                    uri = uri,
                    phaseOffset = phaseOffset,
                    coreCenter = coreCenter,
                    time = time,
                    blobTime = blobTime,
                    baseOrbitRadiusPx = baseOrbitRadiusPx,
                    radialDrift = radialDrift,
                    photoSizeDp = photoSizeDp,
                    photoSizePx = photoSizePx,
                    isExiting = isExiting,
                    isReceiving = isReceiving,
                    rejectIndex = rejectIndex,
                    landingTarget = landingTargets[uri],
                    landingSizePx = landingSizePx,
                    onLanded = { onPhotoLanded(uri) },
                    sendScale = sendScale,
                    sendLit = if (sending && !isReceiving) litProgress else -1f,
                    successProgress = if (isExiting || isReceiving) 0f else successPulse.value,
                    onExitComplete = {
                        visiblePhotos.remove(uri)
                        exitingPhotos.remove(uri)
                        phaseMap.remove(uri)
                    }
                )
            }
        }
    }
}

@Composable
private fun OrbitPhotoItem(
    uri: Uri,
    phaseOffset: Float,
    coreCenter: Offset,
    time: Float,
    blobTime: Float,
    baseOrbitRadiusPx: Float,
    radialDrift: Float,
    photoSizeDp: androidx.compose.ui.unit.Dp,
    photoSizePx: Float,
    isExiting: Boolean,
    isReceiving: Boolean = false,
    rejectIndex: Int = -1,
    landingTarget: Offset? = null,
    landingSizePx: Float = 0f,
    onLanded: () -> Unit = {},
    successProgress: Float = 0f,
    sendScale: Float = 1f,
    sendLit: Float = -1f,
    onExitComplete: () -> Unit
) {
    val entryProgress = remember { Animatable(0f) }
    val exitProgress  = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Skip entry animation for receiving photos — they're animated in separately
        if (!isReceiving) {
            entryProgress.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 150f))
        } else {
            entryProgress.snapTo(1f)  // Receiving photos start fully visible in orbit
        }
    }

    LaunchedEffect(isExiting) {
        if (isExiting && exitProgress.value == 0f) {
            exitProgress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
            onExitComplete()
        }
    }

    val orbitR = baseOrbitRadiusPx + radialDrift
    val orbitX = coreCenter.x + orbitR * cos(time + phaseOffset)
    val orbitY = coreCenter.y + orbitR * sin(time + phaseOffset)

    val ep = entryProgress.value
    val xp = exitProgress.value
    val sp = successProgress

    // Success animation: 0→0.5 = scale bloom up, 0.5→1 = scale down to zero + move to center
    val successScale = when {
        sp < 0.5f -> lerp(1f, 1.2f, sp * 2f)  // Bloom up
        else -> lerp(1.2f, 0f, (sp - 0.5f) * 2f)  // Collapse to zero
    }
    val successX = if (sp > 0.5f) lerp(orbitX, coreCenter.x, (sp - 0.5f) * 2f) else orbitX
    val successY = if (sp > 0.5f) lerp(orbitY, coreCenter.y, (sp - 0.5f) * 2f) else orbitY

    val orbitPosX = lerp(lerp(coreCenter.x, successX, ep), coreCenter.x, xp)
    val orbitPosY = lerp(lerp(coreCenter.y, successY, ep), coreCenter.y, xp)
    val scale = lerp(ep * successScale, 0f, xp)

    // ── Received photo, verified good: fly from where it is orbiting to its grid cell and grow ──
    // ── Received photo, failed its checksum: turn red and move back into the Mother Core ──
    val density = androidx.compose.ui.platform.LocalDensity.current
    val baseSizePx = photoSizePx * sendScale
    val latest = androidx.compose.runtime.rememberUpdatedState(Triple(orbitPosX, orbitPosY, baseSizePx))
    val landProgress = remember { Animatable(0f) }
    val rejectProgress = remember { Animatable(0f) }
    var frozen by remember { mutableStateOf(Triple(0f, 0f, 0f)) }
    var landed by remember { mutableStateOf(false) }

    LaunchedEffect(landingTarget != null) {
        if (landingTarget != null) {
            frozen = latest.value
            landProgress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            landed = true
            onLanded()
        }
    }
    val rejected = rejectIndex >= 0
    LaunchedEffect(rejected) {
        if (rejected) {
            frozen = latest.value
            rejectProgress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
    }

    val lp = landProgress.value
    val rp = rejectProgress.value
    // Parked red photos sit inside the core, fanned out a little so several stay visible
    val parkAngle = rejectIndex * 2.4f
    val parkX = coreCenter.x + 14f * density.density * cos(parkAngle) * (if (rejectIndex > 0) 1f else 0f)
    val parkY = coreCenter.y + 14f * density.density * sin(parkAngle) * (if (rejectIndex > 0) 1f else 0f)
    val x = when {
        lp > 0f && landingTarget != null -> lerp(frozen.first, landingTarget.x, lp)
        rp > 0f -> lerp(frozen.first, parkX, rp)
        else -> orbitPosX
    }
    val y = when {
        lp > 0f && landingTarget != null -> lerp(frozen.second, landingTarget.y, lp)
        rp > 0f -> lerp(frozen.second, parkY, rp)
        else -> orbitPosY
    }
    val sizePx = when {
        lp > 0f && landingTarget != null && landingSizePx > 0f -> lerp(frozen.third, landingSizePx, lp)
        rp > 0f -> lerp(frozen.third, photoSizePx * 0.85f, rp)
        else -> baseSizePx
    }
    val landFade by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (landed) 0f else 1f,
        animationSpec = tween(180),
        label = "land_fade"
    )
    val isCorrupted = rejected

    val photoBlobTime = (blobTime + phaseOffset * 137f).toDouble()

    MediaImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(with(density) { sizePx.toDp() })
            .offset {
                IntOffset(
                    (x - sizePx / 2f).roundToInt(),
                    (y - sizePx / 2f).roundToInt()
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha  = scale * landFade
            }
            .drawWithCache {
                val path = Path()
                onDrawWithContent {
                    // Bloom glow during success pulse
                    if (sp > 0f && sp < 1f) {
                        val bloomAlpha = (1f - abs(sp - 0.5f) * 2f) * 0.4f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.6f * bloomAlpha),
                                    Color.White.copy(alpha = 0.2f * bloomAlpha),
                                    Color.Transparent
                                ),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = size.width * 0.8f
                            ),
                            radius = size.width * 0.8f,
                            center = Offset(size.width / 2f, size.height / 2f)
                        )
                    }

                    updateBlobPath(
                        path     = path,
                        cx       = size.width  / 2f,
                        cy       = size.height / 2f,
                        baseR    = minOf(size.width, size.height) / 2f - 3.dp.toPx(),
                        noiseAmp = 5.dp.toPx(),
                        time     = photoBlobTime,
                        octaves  = 1
                    )
                    clipPath(path) {
                        this@onDrawWithContent.drawContent()

                        if (sendLit >= 0f && sendLit < 1f) {
                            // Sender progress: the unlit part is dimmed and the boundary rises from
                            // the bottom with progress as a soft cross-fade, not a hard edge.
                            val band = 0.35f
                            val top = ((1f - sendLit) * (1f + band) - band).coerceIn(0f, 1f)
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

                    // Red for a photo that failed its checksum; deepens as it moves back into the core
                    if (isCorrupted) {
                        clipPath(path) {
                            drawRect(
                                color = Color.Red.copy(alpha = 0.25f + 0.40f * rp),
                                size = this.size
                            )
                        }
                    }
                }
            }
    )
}
