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
import androidx.compose.ui.draw.clip
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

@Composable
fun MotherCore(
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    isReceiving: Boolean = false,
    crystallizedBitmap: ImageBitmap? = null,
    shouldExit: Boolean = false,
    cutoutOffset: Offset = Offset.Zero,
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

    val infiniteTransition = rememberInfiniteTransition(label = "core")
    val timeFraction by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label         = "timeFraction"
    )
    val time = timeFraction * 1000f

    // Feature flag "core reacts to motion": lean downhill, spring jiggle, rounder blob when shaken
    val tiltOn = motion != null && motion.tilt
    val shake = if (tiltOn) motion!!.shake else 0f
    val motionTx = if (tiltOn) (motion!!.driftX * 10f + motion.jiggleX) * density.density else 0f
    val motionTy = if (tiltOn) (motion!!.driftY * 10f + motion.jiggleY) * density.density else 0f
    // Feature flag "3D core": the puck's axes from the phone's orientation (null = the flat 2D core)
    val frame = if (motion != null && motion.threeD) {
        motion.rotation?.let { coreFrame(it, motion.standFraction.value, motion.yaw.value) }
    } else null

    val hollowProgress by animateFloatAsState(
        targetValue   = if (isReceiving) progress else 0f,
        animationSpec = tween(300),
        label         = "hollow"
    )

    val spawnScale = remember { Animatable(0f) }
    val spawnTx    = remember { Animatable(cutoutOffset.x) }
    val spawnTy    = remember { Animatable(cutoutOffset.y) }
    LaunchedEffect(Unit) {
        val spec = tween<Float>(3000, easing = FastOutSlowInEasing)
        launch { spawnScale.animateTo(1f, spec) }
        launch { spawnTx.animateTo(0f, spec) }
        launch { spawnTy.animateTo(0f, spec) }
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
    val combinedSx = spawnScale.value * exitScale.value
    val combinedTx = spawnTx.value   + exitTx.value
    val combinedTy = spawnTy.value   + exitTy.value

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
        // A shaken core is rounder-edged and wobblier; the extra stays inside the box's spare margin
        val noiseRPx = with(density) { (intensifiedNoiseOffset + shake * 8f).dp.toPx() }
        val blobCx   = with(density) { blobSize.toPx() / 2f }

        val blobPath = remember(time, baseRPx, noiseRPx, blobCx) {
            buildBlobPath(blobCx, blobCx, baseRPx, noiseRPx, time.toDouble())
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

        // Feature flag "3D core": the rounded rim and back of the puck, drawn under the flat core
        if (frame != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCoreBody(blobPath, frame, baseRPx, accentColor, accentAlpha)
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
        if (backdrop != null && !viewfinderActive) {
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
        val specularShiftX = (sin(time * 0.00063 * PI) * baseRPx * 0.12f).toFloat() + tiltLightX
        val specularShiftY = (cos(time * 0.00047 * PI) * baseRPx * 0.09f).toFloat() + tiltLightY

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
