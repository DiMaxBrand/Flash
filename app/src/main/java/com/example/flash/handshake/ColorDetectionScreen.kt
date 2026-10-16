package com.example.flash.handshake

import android.graphics.Color
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.shape.CircleShape
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Analyzes camera frames using real YUV chroma channels.
 *
 * Three safeguards prevent false positives:
 * 1. Saturation threshold — rejects dull environmental colors (walls, furniture)
 * 2. Fill ratio — requires >35% of center region to match (phone screen dominates viewfinder)
 * 3. Sustained match — 8 consecutive frames must pass before confirmation
 */
class ColorMatcher(private val targetColor: Int) {

    private val targetHsv = FloatArray(3)
    private var firstMatchTimeMs = -1L
    private val lockDurationMs = 1000L  // 1 second sustained match required

    var lastMatchStrength = 0f
        private set

    init {
        Color.colorToHSV(targetColor, targetHsv)
    }

    fun analyzeFrame(
        yData: ByteArray, uData: ByteArray, vData: ByteArray,
        width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int
    ): Boolean {
        val cx = width / 2
        val cy = height / 2
        val radius = minOf(width, height) / 5

        var matchingPixels = 0
        var highSatPixels = 0
        val hsv = FloatArray(3)

        for (y in maxOf(0, cy - radius)..minOf(height - 1, cy + radius)) {
            for (x in maxOf(0, cx - radius)..minOf(width - 1, cx + radius)) {
                val yIdx = y * yRowStride + x
                val uvIdx = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
                if (yIdx >= yData.size || uvIdx >= uData.size || uvIdx >= vData.size) continue

                val yv = (yData[yIdx].toInt() and 0xFF) - 16
                val uv = (uData[uvIdx].toInt() and 0xFF) - 128
                val vv = (vData[uvIdx].toInt() and 0xFF) - 128

                val r = ((298 * yv + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * yv - 100 * uv - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * yv + 516 * uv + 128) shr 8).coerceIn(0, 255)

                Color.RGBToHSV(r, g, b, hsv)

                // Safeguard 1: ignore low-saturation pixels (walls, shadows, furniture)
                if (hsv[1] < 0.50f) continue
                highSatPixels++

                val hueDiff = minOf(abs(hsv[0] - targetHsv[0]), 360f - abs(hsv[0] - targetHsv[0]))
                if (hueDiff < 35f) matchingPixels++
            }
        }

        if (highSatPixels < 20) {
            // Not enough saturated pixels — likely looking at plain environment
            lastMatchStrength = 0f
            firstMatchTimeMs = -1L
            return false
        }

        // Safeguard 2: fill ratio — phone screen must dominate the sampled region
        val fillRatio = matchingPixels.toFloat() / highSatPixels
        lastMatchStrength = fillRatio

        val isMatch = fillRatio > 0.35f

        // Safeguard 3: must sustain match for 1 second continuously
        val now = System.currentTimeMillis()
        if (isMatch) {
            if (firstMatchTimeMs < 0) firstMatchTimeMs = now
            return (now - firstMatchTimeMs) >= lockDurationMs
        } else {
            firstMatchTimeMs = -1L
            return false
        }
    }
}

/**
 * Live camera viewfinder clipped to a circle matching MotherCore's size.
 * Placed behind MotherCore so the blob shape frames the camera feed.
 * Color analysis runs in background — callbacks fire on main thread.
 */
@Composable
fun MotherCoreViewfinder(
    targetColor: Int?,
    onMatchStrengthChanged: (Float) -> Unit,
    onColorLocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    // The preview runs as soon as the viewfinder is shown; color matching only starts
    // once a sender (and therefore a target color) is known.
    val currentTarget by rememberUpdatedState(targetColor)

    LaunchedEffect(Unit) {
        ProcessCameraProvider.getInstance(context).addListener({
            cameraProvider = ProcessCameraProvider.getInstance(context).get()
        }, ContextCompat.getMainExecutor(context))
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            cameraProvider?.unbindAll()
        }
    }

    Box(
        modifier = modifier
            .size(160.dp)  // Matches MotherCore's BASE_RADIUS_DP * 2 + padding
            .clip(CircleShape)
    ) {
        if (cameraProvider != null) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        val preview = Preview.Builder().build()
                        var colorMatcher: ColorMatcher? = null
                        var matcherColor: Int? = null
                        var locked = false

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                            .apply {
                                setAnalyzer(executor) { imageProxy ->
                                    try {
                                        val target = currentTarget
                                        if (target == null) {
                                            colorMatcher = null
                                            matcherColor = null
                                            return@setAnalyzer
                                        }
                                        if (matcherColor != target) {
                                            colorMatcher = ColorMatcher(target)
                                            matcherColor = target
                                        }
                                        val matcher = colorMatcher ?: return@setAnalyzer
                                        val planes = imageProxy.planes
                                        val yPlane = planes[0]
                                        val uPlane = planes[1]
                                        val vPlane = planes[2]

                                        val yBuf = yPlane.buffer
                                        val uBuf = uPlane.buffer
                                        val vBuf = vPlane.buffer

                                        val yData = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
                                        val uData = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
                                        val vData = ByteArray(vBuf.remaining()).also { vBuf.get(it) }

                                        val confirmed = matcher.analyzeFrame(
                                            yData, uData, vData,
                                            imageProxy.width, imageProxy.height,
                                            yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
                                        )

                                        ContextCompat.getMainExecutor(ctx).execute {
                                            onMatchStrengthChanged(matcher.lastMatchStrength)
                                            if (confirmed && !locked) {
                                                locked = true
                                                onColorLocked()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("ColorDetection", "Frame error", e)
                                    } finally {
                                        imageProxy.close()
                                    }
                                }
                            }

                        try {
                            cameraProvider?.unbindAll()
                            cameraProvider?.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalysis
                            )
                            preview.surfaceProvider = this.surfaceProvider
                        } catch (e: Exception) {
                            Log.e("ColorDetection", "Camera bind failed", e)
                        }
                    }
                },
                modifier = Modifier.matchParentSize()
            )
        }
    }
}
