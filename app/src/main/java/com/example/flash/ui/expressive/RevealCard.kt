package com.example.flash.ui.expressive

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.flash.ui.workbench.IncomingPhoto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * Mask for the bottom-to-top wipe: everything below the wipe line is opaque, and just above it the
 * sharp image fades out over [EDGE] of the height, so the top of the revealed area dissolves into
 * the blurred picture behind it instead of ending in a hard line. [wipe] runs 0 (nothing) to 1 (all).
 */
private fun wipeMask(wipe: Float): Brush {
    val edge = 0.22f
    val line = (1f + edge) * (1f - wipe)       // fraction from the top where full opacity starts
    val p0 = (line - edge).coerceIn(0f, 1f)
    val p1 = line.coerceIn(0f, 1f)
    return Brush.verticalGradient(
        colorStops = arrayOf(0f to Color.Transparent, p0 to Color.Transparent, p1 to Color.Black, 1f to Color.Black)
    )
}

/** A grey card with a light wave sweeping across: "something is coming". */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val t by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "shimmer_x"
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.background(base)) {
        val x = size.width * t
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                start = Offset(x, 0f),
                end = Offset(x + size.width * 0.5f, size.height * 0.35f)
            )
        )
    }
}

/**
 * A photo that is arriving.
 *
 * Layers, bottom to top: shimmer (until the tiny thumbnail is known) -> the blurred thumbnail ->
 * the sharp picture, masked by a wipe that rises from the bottom.
 *
 * Big cards ([useBands]) wipe up over the bands as they arrive, slowly and with a non-bouncy
 * spring, never past the bands that are available. If the original finishes while the wipe is
 * mid-way, the wipe pauses where it is, the original is crossfaded in under the same mask (nothing
 * visible changes) and the wipe continues to the top using the original. Small tray tiles request no
 * bands: they just wipe up once the original is complete and verified.
 */
@Composable
fun RevealCard(
    photo: IncomingPhoto,
    useBands: Boolean,
    holdMs: Long,
    cropToFill: Boolean,
    onFullyRevealed: () -> Unit,
    modifier: Modifier = Modifier,
    rejected: Boolean = false
) {
    val ready = photo.ready

    // How far up the bands reach: contiguous bands from the bottom, as a fraction of the height
    val bandLevel = remember(photo.bands, photo.previewHeight) {
        var sum = 0
        var n = 0
        while (photo.bands.containsKey(n)) { sum += photo.bands.getValue(n).h; n++ }
        if (photo.previewHeight > 0) sum.toFloat() / photo.previewHeight else 0f
    }
    val bandImages = remember(photo.bands) { photo.bands.values.map { it to it.bitmap.asImageBitmap() } }

    val wipe = remember { Animatable(0f) }
    val originalAlpha = remember { Animatable(0f) }

    LaunchedEffect(ready) {
        if (ready) originalAlpha.animateTo(1f, tween(350))
    }
    LaunchedEffect(ready, bandLevel, useBands) {
        val target = when {
            ready -> 1f
            useBands -> bandLevel
            else -> 0f
        }
        // Original arrived mid-wipe: pause where we are, then carry on up using the original
        if (ready && wipe.value in 0.01f..0.99f) delay(180)
        wipe.animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 10f))   // slow, no bounce
    }
    LaunchedEffect(ready) {
        if (ready) {
            snapshotFlow { wipe.value }.first { it >= 0.995f }
            delay(holdMs)
            onFullyRevealed()
        }
    }

    Box(modifier.clip(RoundedCornerShape(if (cropToFill) 28.dp else 32.dp))) {
        val thumb = photo.thumb
        if (thumb == null) {
            ShimmerBox(Modifier.fillMaxSize())
        } else {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(18.dp)
            )
        }

        // The sharp content, masked by the wipe (offscreen so DstIn only affects this layer)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = wipeMask(wipe.value), blendMode = BlendMode.DstIn)
                }
        ) {
            if (useBands && bandImages.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - originalAlpha.value }) {
                    val scale = size.width / photo.previewWidth.coerceAtLeast(1)
                    bandImages.forEach { (b, image) ->
                        drawImage(
                            image = image,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(0, (b.y * scale).roundToInt()),
                            dstSize = IntSize(size.width.roundToInt(), (b.h * scale).roundToInt() + 1)
                        )
                    }
                }
            }
            val original = photo.originalUri
            if (original != null) {
                AsyncImage(
                    model = original,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = originalAlpha.value }
                )
            }
        }

        if (rejected) {
            Box(Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.5f)))
        }
    }
}

/** Fits a card of the photo's aspect ratio into the available area (letterboxed, never cropped). */
@Composable
fun FittedCard(aspect: Float, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val w = minOf(maxWidth, maxHeight * aspect)
        val h = w / aspect
        content(Modifier.size(w, h))
    }
}

/** The sending phone's big card: the original photo, dimmed and lighting up from the bottom as it is sent. */
@Composable
fun SendCard(uri: Uri, progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(32.dp))) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (progress in 0f..0.999f) {
                        val band = 0.35f
                        val top = ((1f - progress) * (1f + band) - band).coerceIn(0f, 1f)
                        val bottom = (top + band).coerceIn(0f, 1f)
                        val dark = Color.Black.copy(alpha = 0.55f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(0f to dark, top to dark, bottom to Color.Transparent, 1f to Color.Transparent)
                            ),
                            size = size
                        )
                    }
                }
        )
    }
}
