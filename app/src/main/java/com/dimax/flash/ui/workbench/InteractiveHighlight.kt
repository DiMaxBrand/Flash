package com.dimax.flash.ui.workbench

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {
    private val pressSpec = spring(0.5f, 300f, 0.001f)
    private val posSpec   = spring(0.5f, 300f, Offset.VisibilityThreshold)
    private val pressAnim = Animatable(0f, 0.001f)
    private val posAnim   = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    val pressProgress: Float get() = pressAnim.value
    val offset: Offset get() = posAnim.value - startPosition

    private val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        RuntimeShader("""
            uniform float2 size;
            layout(color) uniform half4 color;
            uniform float radius;
            uniform float2 position;
            half4 main(float2 coord) {
                float dist = distance(coord, position);
                float intensity = smoothstep(radius, radius * 0.5, dist);
                return color * intensity;
            }
        """)
    } else null

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressAnim.value
        if (progress > 0f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                drawRect(Color.White.copy(0.08f * progress), blendMode = BlendMode.Plus)
                shader.apply {
                    val pos = position(size, posAnim.value)
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(0.15f * progress).toArgb())
                    setFloatUniform("radius", size.minDimension * 1.5f)
                    setFloatUniform("position",
                        pos.x.fastCoerceIn(0f, size.width),
                        pos.y.fastCoerceIn(0f, size.height))
                }
                drawRect(ShaderBrush(shader), blendMode = BlendMode.Plus)
            } else {
                drawRect(Color.White.copy(0.25f * progress), blendMode = BlendMode.Plus)
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressAnim.animateTo(1f, pressSpec) }
                    launch { posAnim.snapTo(startPosition) }
                }
            },
            onDragEnd = { _ ->
                animationScope.launch {
                    launch { pressAnim.animateTo(0f, pressSpec) }
                    launch { posAnim.animateTo(startPosition, posSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressAnim.animateTo(0f, pressSpec) }
                    launch { posAnim.animateTo(startPosition, posSpec) }
                }
            }
        ) { change, _ ->
            animationScope.launch { posAnim.snapTo(change.position) }
        }
    }
}
