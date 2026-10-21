package com.dimax.flash.ui.core

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dimax.flash.ui.theme.AquaGlow
import com.dimax.flash.ui.theme.AquaPulse
import com.dimax.flash.ui.theme.OceanAqua
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val CONTROL_POINTS  = 16
private const val BASE_RADIUS_DP  = 60f
private const val NOISE_OFFSET_DP = 20f

/** Ease in and out, clamped to 0..1. */
private fun smooth01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
fun MotherCore(
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    isReceiving: Boolean = false,
    crystallizedBitmap: ImageBitmap? = null,
    shouldExit: Boolean = false,
    cutoutOffset: Offset = Offset.Zero,
    /** How big the camera hole is; the core is born at about this size. 0 = unknown. */
    cutoutSizePx: Float = 0f,
    /** False until the camera position is known; the core stays hidden (it must not start from a guess). */
    spawnReady: Boolean = true,
    backdrop: Backdrop? = null,
    accentColor: Color? = null,
    accentAlpha: Float = 0.80f,
    viewfinderContent: (@Composable () -> Unit)? = null,
    /** A photo materializing inside the core (clipped to the blob, above the colour, below the highlights). */
    featuredContent: (@Composable () -> Unit)? = null,
    /** Sensor state behind the two experimental feature flags (null = both off: the core is unchanged). */
    motion: CoreMotionState? = null,
    onAnimationComplete: () -> Unit = {}
) {
    val viewfinderActive = viewfinderContent != null
    val isDark = isSystemInDarkTheme()
    val density = androidx.compose.ui.platform.LocalDensity.current

    // A clock that never restarts (same pace as before: 1000 units per 2.7 s). The old 0..1000 animation
    // jumped back to 0 every 2.7 s, and the noise and the highlight both jumped with it.
    val seconds by rememberContinuousTime()
    val timeD = seconds * (1000.0 / 2.7)
    val time = timeD.toFloat()

    // Feature flag "core reacts to motion": lean downhill, spring jiggle, rounder blob when shaken
    val tiltOn = motion != null && motion.tilt
    val shake = if (tiltOn) motion!!.shake else 0f
    val motionTx = if (tiltOn) (motion!!.driftX * 10f + motion.jiggleX) * density.density else 0f
    val motionTy = if (tiltOn) (motion!!.driftY * 10f + motion.jiggleY) * density.density else 0f
    // Feature flag "3D core": the puck's axes from the phone's orientation (null = the flat 2D core)
    val frame = if (motion != null && motion.threeD) {
        motion.rotation?.let { coreFrame(it, motion.standFraction.value, motion.yaw) }
    } else null

    val hollowProgress by animateFloatAsState(
        targetValue   = if (isReceiving) progress else 0f,
        animationSpec = tween(300),
        label         = "hollow"
    )

    // The birth: the core starts as a BLACK blob at the camera hole (the same black as the hole), swells there,
    // then drifts to its place while the black turns into glass. One progress value, 0..1 over 3.2 s. It waits
    // for the camera position (spawnReady): starting before it was known made the blob come from a wrong spot.
    val spawn = remember { Animatable(0f) }
    LaunchedEffect(spawnReady) {
        if (spawnReady) spawn.animateTo(1f, tween(3200, easing = LinearEasing))
    }

    val exitScale = remember { Animatable(1f) }
    val exitTx    = remember { Animatable(0f) }
    val exitTy    = remember { Animatable(0f) }
    var exitDone  by remember { mutableStateOf(false) }
    LaunchedEffect(shouldExit) {
        if (shouldExit && !exitDone) {
            exitDone = true
            val spec = tween<Float>(3000, easing = FastOutSlowInEasing)
            coroutineScope {
                launch { exitScale.animateTo(0f, spec) }
                launch { exitTx.animateTo(cutoutOffset.x, spec) }
                launch { exitTy.animateTo(cutoutOffset.y, spec) }
            }
            onAnimationComplete()
        }
    }

    val transferIntensity = (progress * 0.5f)
    val intensifiedNoiseOffset = NOISE_OFFSET_DP + (5f * transferIntensity)
    val blobSize   = (BASE_RADIUS_DP * 2 + intensifiedNoiseOffset * 2 + 20f).dp
    // Birth, in three overlapping parts: swell at the camera (fast, then settling), drift to the place (starts
    // part-way through the swell), and the black fading into glass (once it is mostly grown). Leaving does the
    // reverse of the last part: the core goes black again as it is swallowed back into the camera.
    val p = spawn.value
    val blobPx = with(density) { blobSize.toPx() }
    val startScale = if (cutoutSizePx > 0f) (cutoutSizePx / blobPx).coerceIn(0.05f, 0.35f) else 0.1f
    val grow = 1f - (1f - (p / 0.5f).coerceIn(0f, 1f)).let { it * it * it }
    val spawnScale = if (spawnReady) startScale + (1f - startScale) * grow else 0f
    val move = smooth01((p - 0.35f) / 0.65f)
    val spawnTx = cutoutOffset.x * (1f - move)
    val spawnTy = cutoutOffset.y * (1f - move)
    val spawnBlack = 1f - smooth01((p - 0.55f) / 0.4f)
    val exitBlack = smooth01(((1f - exitScale.value) - 0.4f) / 0.6f)
    val blackAlpha = if (spawnReady) maxOf(spawnBlack, exitBlack) else 0f
    val combinedSx = spawnScale * exitScale.value
    val combinedTx = spawnTx + exitTx.value
    val combinedTy = spawnTy + exitTy.value

    Box(
        modifier = modifier
            .size(blobSize)
            .graphicsLayer {
                scaleX       = combinedSx
                scaleY       = combinedSx
                translationX = combinedTx + motionTx
                translationY = combinedTy + motionTy
            }
    ) {
        val baseRPx  = with(density) { BASE_RADIUS_DP.dp.toPx() }
        // A shaken core is rounder-edged and wobblier, and so is a newborn one; the extra stays inside the box's
        // spare margin
        val spawnWobble = (1f - smooth01(p)) * 8f
        val noiseRPx = with(density) { (intensifiedNoiseOffset + minOf(8f, shake * 8f + spawnWobble)).dp.toPx() }
        val blobCx   = with(density) { blobSize.toPx() / 2f }
        // The noise flows several times faster at birth and eases back to its normal pace. It is a smooth extra
        // time offset that ends as a constant, so there is no jump anywhere.
        val noiseTimeD = timeD + 1500.0 * (1.0 - (1.0 - p.toDouble()).let { it * it * it })

        val blobPath = remember(noiseTimeD, baseRPx, noiseRPx, blobCx) {
            buildBlobPath(blobCx, blobCx, baseRPx, noiseRPx, noiseTimeD)
        }

        // Everything is clipped to the morphing blob itself: no static circle anywhere.
        val blobShape = remember(blobPath) {
            object : androidx.compose.ui.graphics.Shape {
                override fun createOutline(
                    size: androidx.compose.ui.geometry.Size,
                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                    density: androidx.compose.ui.unit.Density
                ) = androidx.compose.ui.graphics.Outline.Generic(blobPath)
            }
        }

        // Feature flag "3D core": a glass puck. What is behind it is refracted over its WHOLE outline (rim and
        // front) in screen space, so the picture behind is never tilted along with the face; soft gradients
        // and edge lines are laid over that. The flat core below only carries highlights, colour and content.
        val projection = if (frame != null) projectCore(blobPath, frame, baseRPx, blobCx * 2f) else null
        if (projection != null) {
            val silhouetteShape = pathShape(projection.silhouette)
            if (backdrop != null) {
                Box(modifier = Modifier.fillMaxSize().clip(silhouetteShape)) {
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    projection.bounds.left.roundToInt(),
                                    projection.bounds.top.roundToInt()
                                )
                            }
                            .size(
                                with(density) { projection.bounds.width.toDp() },
                                with(density) { projection.bounds.height.toDp() }
                            )
                            .drawBackdrop(
                                backdrop = backdrop,
                                shape = { RoundedCornerShape(50) },
                                effects = {
                                    lens(16f.dp.toPx(), 32f.dp.toPx())
                                },
                                onDrawSurface = {
                                    drawRect(AquaGlow.copy(alpha = 0.10f))
                                }
                            )
                    )
                }
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCoreGlass(projection, accentColor, accentAlpha)
            }
        }
        // ...and the flat core itself becomes the puck's front: its whole drawing goes through a
        // perspective transform (nothing is drawn while the front is turned away or edge-on)
        val faceMatrix = remember { android.graphics.Matrix() }
        val faceModifier = if (frame != null) {
            Modifier.fillMaxSize().drawWithContent {
                if (coreFaceMatrix(frame, size.width, size.height, baseRPx, faceMatrix)) {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.concat(faceMatrix)
                        drawContent()
                        canvas.nativeCanvas.restore()
                    }
                }
            }
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = faceModifier) {
        // ── Live camera (receiver scanning) ───────────────────────────────────
        if (viewfinderContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(blobShape)
            ) { viewfinderContent() }
        }

        // ── Clear glass: refracts what is behind the blob, no blur ────────────
        // The backdrop lens only supports rounded-rect SDF shapes, so it renders in a
        // circle and the result is clipped to the morphing blob. Skipped while the
        // camera is showing (the lens would hide it).
        if (backdrop != null && !viewfinderActive && projection == null) {
            val blobDiameterDp = with(density) { ((baseRPx + noiseRPx) * 2).toDp() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(blobShape)
            ) {
                Box(
                    modifier = Modifier
                        .size(blobDiameterDp)
                        .align(Alignment.Center)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(50) },
                            effects = {
                                lens(16f.dp.toPx(), 32f.dp.toPx())
                            },
                            onDrawSurface = {
                                drawRect(AquaGlow.copy(alpha = 0.10f))
                            }
                        )
                )
            }
        }

        // Animated specular wobble — the highlight drifts slowly across the surface
        // With the motion flag on (and no 3D lighting of its own) the highlight also slides against the tilt
        val tiltLightX = if (tiltOn && frame == null) -motion!!.driftX * baseRPx * 0.22f else 0f
        val tiltLightY = if (tiltOn && frame == null) -motion!!.driftY * baseRPx * 0.22f else 0f
        val specularShiftX = (sin(timeD * 0.00063 * PI) * baseRPx * 0.12f).toFloat() + tiltLightX
        val specularShiftY = (cos(timeD * 0.00047 * PI) * baseRPx * 0.09f).toFloat() + tiltLightY

        // Tint (the sender's colour / lock-on colour): flat so the other phone's camera can read its hue
        if (accentColor != null && accentAlpha > 0f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                clipPath(blobPath) { drawRect(accentColor.copy(alpha = accentAlpha)) }
            }
        }

        // A photo materializing inside the core, above the colour but under the glass highlights
        if (featuredContent != null) {
            Box(modifier = Modifier.fillMaxSize().clip(blobShape)) { featuredContent() }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            // ── Blob fill: radial gradient for 3D depth ───────────────────────
            val lightAqua = Color(0xFF80FFFF)   // top-left highlight
            val darkTeal  = Color(0xFF003C3C)   // bottom-right shadow

            val blobFill = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to lightAqua.copy(alpha = 0.95f),
                    0.45f to OceanAqua,
                    1.0f to darkTeal
                ),
                center = Offset(center.x - baseRPx * 0.28f, center.y - baseRPx * 0.35f),
                radius = baseRPx * 2.2f
            )

            if (isReceiving && hollowProgress > 0.01f) {
                val hollowR = baseRPx * hollowProgress * 0.85f
                val coreWithHole = Path().apply {
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                    addPath(blobPath)
                    addOval(androidx.compose.ui.geometry.Rect(center, hollowR))
                }
                drawPath(coreWithHole, brush = blobFill)

                if (crystallizedBitmap != null && hollowProgress > 0.2f) {
                    val hollowPath = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, hollowR)) }
                    clipPath(hollowPath) {
                        drawImage(crystallizedBitmap, topLeft = Offset(center.x - hollowR, center.y - hollowR))
                    }
                }
            }

            clipPath(blobPath) {
                if (!viewfinderActive) {
                // ── Rim light: bright aqua glow on the bottom-right edge ──────
                if (accentColor == null) drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, AquaPulse.copy(0.55f), Color.Transparent),
                        center = Offset(center.x + baseRPx * 0.55f, center.y + baseRPx * 0.5f),
                        radius = baseRPx * 0.9f
                    ),
                    radius = baseRPx * 0.9f,
                    center = Offset(center.x + baseRPx * 0.55f, center.y + baseRPx * 0.5f)
                )

                // ── Primary specular: large soft highlight (top-left), animated ─
                val spec1Center = Offset(
                    center.x - baseRPx * 0.3f + specularShiftX,
                    center.y - baseRPx * 0.42f + specularShiftY
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        center = spec1Center,
                        radius = baseRPx * 0.82f
                    ),
                    radius = baseRPx * 0.82f,
                    center = spec1Center
                )

                // ── Secondary specular: small sharp pinpoint ──────────────────
                val spec2Center = Offset(
                    center.x - baseRPx * 0.18f + specularShiftX * 0.6f,
                    center.y - baseRPx * 0.32f + specularShiftY * 0.6f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(0.92f), Color.Transparent),
                        center = spec2Center,
                        radius = baseRPx * 0.22f
                    ),
                    radius = baseRPx * 0.22f,
                    center = spec2Center
                )

                // ── Depth shadow: subtle dark gradient at the bottom ──────────
                if (accentColor == null) drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                        startY = center.y + baseRPx * 0.1f,
                        endY   = center.y + baseRPx + noiseRPx
                    )
                )
                }
            }

            // ── Glassy rim outline: thin bright ring around the edge ─────────
            drawPath(
                path  = blobPath,
                color = Color.White.copy(alpha = 0.22f),
                style = Stroke(width = 1.5f.dp.toPx(), cap = StrokeCap.Round)
            )
        }
            // The birth colour: black like the camera hole, over everything, fading away into the glass
            if (blackAlpha > 0.003f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawPath(blobPath, Color.Black.copy(alpha = blackAlpha))
                }
            }
        } // the flat core (the puck's front when the 3D flag is on)
    }
}

internal fun updateBlobPath(path: Path, cx: Float, cy: Float, baseR: Float, noiseAmp: Float, time: Double, octaves: Int = 3) {
    path.reset()
    val n    = CONTROL_POINTS
    val step = 2 * PI / n

    val radii = FloatArray(n) { i ->
        val angle = i * step
        val noise = PerlinNoise.octaveNoise(
            cos(angle) * 1.5 + time * 0.0004,
            sin(angle) * 1.5 + time * 0.0004,
            octaves = octaves, persistence = 0.55
        ).toFloat()
        baseR + noise * noiseAmp
    }

    fun getX(i: Int) = cx + radii[(i + n) % n] * cos((i % n) * step).toFloat()
    fun getY(i: Int) = cy + radii[(i + n) % n] * sin((i % n) * step).toFloat()

    path.moveTo(getX(0), getY(0))
    for (i in 0 until n) {
        val p1x = getX(i)
        val p1y = getY(i)
        val p2x = getX(i + 1)
        val p2y = getY(i + 1)
        val p0x = getX(i - 1)
        val p0y = getY(i - 1)
        val p3x = getX(i + 2)
        val p3y = getY(i + 2)
        val cp1x = p1x + (p2x - p0x) / 6f
        val cp1y = p1y + (p2y - p0y) / 6f
        val cp2x = p2x - (p3x - p1x) / 6f
        val cp2y = p2y - (p3y - p1y) / 6f
        path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2x, p2y)
    }
    path.close()
}

internal fun buildBlobPath(cx: Float, cy: Float, baseR: Float, noiseAmp: Float, time: Double): Path {
    val path = Path()
    updateBlobPath(path, cx, cy, baseR, noiseAmp, time)
    return path
}
