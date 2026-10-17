package com.example.flash.ui.workbench

import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.colorControls
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sign

/** The text / icon colour a glass surface currently wants; set by [LiquidButton] and the settings panel. */
val LocalGlassContentColor = compositionLocalOf { Color.White }

/**
 * Adaptive luminance (the idea of the backdrop catalog's "Adaptive luminance glass" demo): the picture
 * seen through a glass surface is recorded into [layer], shrunk to 5x5 pixels a few times a second and
 * averaged. [luminance] (0 dark .. 1 bright) drives the glass itself ([adaptiveColorControls]) and
 * [contentColor] flips between black and white, with a dead zone so it never flickers.
 */
@Stable
class AdaptiveLuminance internal constructor(
    val layer: GraphicsLayer,
    private val lum: Animatable<Float, *>,
    private val ink: Animatable<Color, *>
) {
    val luminance: Float get() = lum.value
    val contentColor: Color get() = ink.value

    /** Draws the backdrop into the sampling layer too (call from `onDrawBackdrop`, after drawing it normally). */
    fun record(scope: DrawScope, drawBackdrop: DrawScope.() -> Unit) = with(scope) {
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w > 0 && h > 0) {
            layer.record(density = this, layoutDirection = layoutDirection, size = IntSize(w, h)) { drawBackdrop() }
        }
    }
}

@Composable
fun rememberAdaptiveLuminance(initial: Float = 0.3f): AdaptiveLuminance {
    val layer = rememberGraphicsLayer()
    val lum = remember { Animatable(initial) }
    val ink = remember { Animatable(if (initial > 0.5f) Color.Black else Color.White) }
    LaunchedEffect(layer) {
        val pixels = IntArray(25)
        while (isActive) {
            delay(250)
            val average = runCatching {
                val shot = layer.toImageBitmap().asAndroidBitmap()
                val small = Bitmap.createScaledBitmap(shot, 5, 5, false).copy(Bitmap.Config.ARGB_8888, false)
                small.getPixels(pixels, 0, 5, 0, 0, 5, 5)
                pixels.sumOf { argb ->
                    val r = (argb shr 16 and 0xFF) / 255.0
                    val g = (argb shr 8 and 0xFF) / 255.0
                    val b = (argb and 0xFF) / 255.0
                    0.2126 * r + 0.7152 * g + 0.0722 * b
                }.toFloat() / pixels.size
            }.getOrNull() ?: continue
            // Dead zone: only flip the text colour when clearly past the middle
            val wantDark = when {
                average > 0.55f -> true
                average < 0.45f -> false
                else -> ink.targetValue == Color.Black
            }
            launch { ink.animateTo(if (wantDark) Color.Black else Color.White, tween(450)) }
            lum.animateTo(average, tween(450))
        }
    }
    return remember(layer) { AdaptiveLuminance(layer, lum, ink) }
}

/** Brighten and flatten the glass over bright pictures, darken it a little over dark ones. */
fun BackdropEffectScope.adaptiveColorControls(luminance: Float) {
    val l = (luminance * 2f - 1f).let { sign(it) * it * it }
    colorControls(
        brightness = if (l > 0f) lerp(0.05f, 0.4f, l) else lerp(0.05f, -0.15f, -l),
        contrast = if (l > 0f) lerp(1f, 0.25f, l) else 1f,
        saturation = 1f
    )
}
