package com.example.flash.handshake

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Analyzes camera frames using real YUV chroma channels.
 *
 * Three safeguards prevent false positives:
 * 1. Saturation threshold — rejects dull environmental colors (walls, furniture)
 * 2. Fill ratio — requires >35% of the whole sampled centre area (not just of its saturated
 *    pixels) to match, so a few warm pixels in a room cannot trigger a lock
 * 3. Sustained match — held for 1 s (with a short dropout grace) before confirmation
 */
class ColorMatcher(private val targetColor: Int) {

    private val targetHsv = FloatArray(3)
    private var firstMatchTimeMs = -1L
    private var lastMatchTimeMs = -1L
    private val dropoutGraceMs = 400L  // tolerate brief misses (banding, flicker) mid-lock
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
        val radius = minOf(width, height) / 4

        var matchingPixels = 0
        var sampledPixels = 0
        val hsv = FloatArray(3)

        for (y in maxOf(0, cy - radius)..minOf(height - 1, cy + radius)) {
            for (x in maxOf(0, cx - radius)..minOf(width - 1, cx + radius)) {
                val yIdx = y * yRowStride + x
                val uvIdx = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
                if (yIdx >= yData.size || uvIdx >= uData.size || uvIdx >= vData.size) continue

                sampledPixels++
                val yv = (yData[yIdx].toInt() and 0xFF) - 16
                val uv = (uData[uvIdx].toInt() and 0xFF) - 128
                val vv = (vData[uvIdx].toInt() and 0xFF) - 128

                val r = ((298 * yv + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * yv - 100 * uv - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * yv + 516 * uv + 128) shr 8).coerceIn(0, 255)

                Color.RGBToHSV(r, g, b, hsv)

                // Ignore very dark pixels: hue is meaningless there (also the dark bands a
                // phone screen shows on camera when the shutter beats against its refresh).
                if (hsv[2] < 0.12f) continue
                // Safeguard 1: ignore low-saturation pixels (walls, shadows, furniture)
                if (hsv[1] < 0.45f) continue

                val hueDiff = minOf(abs(hsv[0] - targetHsv[0]), 360f - abs(hsv[0] - targetHsv[0]))
                if (hueDiff < 28f) matchingPixels++
            }
        }

        if (sampledPixels == 0) {
            lastMatchStrength = 0f
            return missedFrame()
        }

        // Safeguard 2: fill ratio — the coloured phone screen must fill a good part of the
        // whole sampled area (denominator is ALL sampled pixels, not only saturated ones)
        val fillRatio = matchingPixels.toFloat() / sampledPixels
        lastMatchStrength = fillRatio

        val isMatch = fillRatio > 0.35f

        // Safeguard 3: must sustain match for 1 second continuously
        val now = System.currentTimeMillis()
        if (isMatch) {
            if (firstMatchTimeMs < 0) firstMatchTimeMs = now
            lastMatchTimeMs = now
            return (now - firstMatchTimeMs) >= lockDurationMs
        } else {
            return missedFrame()
        }
    }

    /** A non-matching frame only breaks the lock once it has lasted past the grace window. */
    private fun missedFrame(): Boolean {
        if (firstMatchTimeMs >= 0 && System.currentTimeMillis() - lastMatchTimeMs > dropoutGraceMs) {
            firstMatchTimeMs = -1L
        }
        return false
    }
}

/**
 * Converts a YUV_420_888 camera frame to a small ARGB bitmap (about [outWidth] px wide, sensor
 * orientation) so it can be drawn inside MotherCore's morphing blob.
 */
private fun yuvToBitmap(
    yData: ByteArray, uData: ByteArray, vData: ByteArray,
    width: Int, height: Int,
    yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
    outWidth: Int = 200
): Bitmap {
    val step = max(1, width / outWidth)
    val ow = width / step
    val oh = height / step
    val pixels = IntArray(ow * oh)
    for (j in 0 until oh) {
        val y = j * step
        for (i in 0 until ow) {
            val x = i * step
            val yIdx = y * yRowStride + x
            val uvIdx = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
            if (yIdx >= yData.size || uvIdx >= uData.size || uvIdx >= vData.size) continue
            val yv = (yData[yIdx].toInt() and 0xFF) - 16
            val uv = (uData[uvIdx].toInt() and 0xFF) - 128
            val vv = (vData[uvIdx].toInt() and 0xFF) - 128
            val r = ((298 * yv + 409 * vv + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * yv - 100 * uv - 208 * vv + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * yv + 516 * uv + 128) shr 8).coerceIn(0, 255)
            pixels[j * ow + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(pixels, ow, oh, Bitmap.Config.ARGB_8888)
}

/**
 * Live camera view that fills whatever clips it (MotherCore clips it to the morphing blob).
 *
 * The frames are drawn with a plain Compose [Canvas] from the analysis stream instead of a
 * PreviewView: a TextureView/SurfaceView inside a shape clip rendered black on some phones
 * (Samsung) while the colour analysis kept working. Colour matching starts once [targetColor]
 * is known; callbacks fire on the main thread.
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
    val executor = remember { Executors.newSingleThreadExecutor() }
    val currentTarget by rememberUpdatedState(targetColor)
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var rotation by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        var colorMatcher: ColorMatcher? = null
        var matcherColor: Int? = null
        var locked = false
        var lastFrameMs = 0L
        var disposed = false
        val mainExecutor = ContextCompat.getMainExecutor(context)

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .apply {
                setAnalyzer(executor) { imageProxy ->
                    try {
                        val planes = imageProxy.planes
                        val yPlane = planes[0]
                        val uPlane = planes[1]
                        val vPlane = planes[2]
                        val yData = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
                        val uData = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
                        val vData = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
                        val w = imageProxy.width
                        val h = imageProxy.height

                        // Picture for the blob (about 15 fps is plenty)
                        val now = System.currentTimeMillis()
                        if (now - lastFrameMs >= 66) {
                            lastFrameMs = now
                            val bitmap = yuvToBitmap(
                                yData, uData, vData, w, h,
                                yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
                            )
                            val degrees = imageProxy.imageInfo.rotationDegrees
                            mainExecutor.execute {
                                if (!disposed) { frame = bitmap; rotation = degrees }
                            }
                        }

                        // Colour match, once a sender's colour is known
                        val target = currentTarget
                        if (target == null) {
                            colorMatcher = null
                            matcherColor = null
                        } else {
                            if (matcherColor != target) {
                                colorMatcher = ColorMatcher(target)
                                matcherColor = target
                            }
                            val matcher = colorMatcher
                            if (matcher != null) {
                                val confirmed = matcher.analyzeFrame(
                                    yData, uData, vData, w, h,
                                    yPlane.rowStride, uPlane.rowStride, uPlane.pixelStride
                                )
                                mainExecutor.execute {
                                    if (disposed) return@execute
                                    onMatchStrengthChanged(matcher.lastMatchStrength)
                                    if (confirmed && !locked) {
                                        locked = true
                                        onColorLocked()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ColorDetection", "Frame error", e)
                    } finally {
                        imageProxy.close()
                    }
                }
            }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                provider = future.get()
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e("ColorDetection", "Camera bind failed", e)
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            provider?.unbindAll()
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val bmp = frame ?: return@Canvas
            val rot = rotation
            val quarterTurn = rot % 180 != 0
            val bw = if (quarterTurn) bmp.height else bmp.width
            val bh = if (quarterTurn) bmp.width else bmp.height
            val fillScale = max(size.width / bw, size.height / bh)   // fill (centre crop)
            withTransform({
                translate(size.width / 2f, size.height / 2f)
                rotate(rot.toFloat(), Offset.Zero)
                scale(fillScale, fillScale, Offset.Zero)
            }) {
                drawImage(
                    image = bmp.asImageBitmap(),
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bmp.width, bmp.height),
                    dstOffset = IntOffset(-bmp.width / 2, -bmp.height / 2),
                    dstSize = IntSize(bmp.width, bmp.height),
                    filterQuality = FilterQuality.Low
                )
            }
        }
    }
}
