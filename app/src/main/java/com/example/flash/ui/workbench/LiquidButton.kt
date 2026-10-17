package com.example.flash.ui.workbench

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isInteractive: Boolean = true,
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    buttonHeight: Dp = 48.dp,
    shape: () -> Shape = { Capsule() },
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    // Adaptive luminance: the glass and its text follow the brightness of what is behind the button
    val adaptive = rememberAdaptiveLuminance()
    Row(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = shape,
                effects = {
                    vibrancy()
                    adaptiveColorControls(adaptive.luminance)
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                onDrawBackdrop = { drawBackdrop -> drawBackdrop(); adaptive.record(this, drawBackdrop) },
                layerBlock = if (isInteractive) {
                    {
                        val width = size.width
                        val height = size.height
                        val progress = interactiveHighlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)
                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val dragOffset = interactiveHighlight.offset
                        translationX = maxOffset * tanh(initialDerivative * dragOffset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * dragOffset.y / maxOffset)
                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(dragOffset.y, dragOffset.x)
                        scaleX = scale +
                                maxDragScale * abs(cos(offsetAngle) * dragOffset.x / size.maxDimension) *
                                (width / height).fastCoerceAtMost(1f)
                        scaleY = scale +
                                maxDragScale * abs(sin(offsetAngle) * dragOffset.y / size.maxDimension) *
                                (height / width).fastCoerceAtMost(1f)
                    }
                } else null,
                onDrawSurface = {
                    if (tint.isSpecified) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.75f))
                    }
                    if (surfaceColor.isSpecified) {
                        drawRect(surfaceColor)
                    }
                }
            )
            .clickable(
                interactionSource = null,
                indication = if (isInteractive) null else LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (isInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else Modifier
            )
            .height(buttonHeight)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            CompositionLocalProvider(LocalGlassContentColor provides adaptive.contentColor) { content() }
        }
    )
}
