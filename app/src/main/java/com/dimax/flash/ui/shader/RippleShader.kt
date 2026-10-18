package com.dimax.flash.ui.shader

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Canvas

private const val RIPPLE_AGSL = """
uniform float2 u_resolution;
uniform float2 u_core_center;
uniform float  u_time;
uniform shader u_content;

half4 main(float2 fragCoord) {
    float2 uv     = fragCoord / u_resolution;
    float2 center = u_core_center / u_resolution;
    float  dist   = distance(uv, center);
    float2 dir    = uv - center;
    float  len    = length(dir);
    float2 norm   = len > 0.001 ? dir / len : float2(0.0, 0.0);
    float2 offset = sin(dist * 10.0 - u_time * 5.0) * 0.02 * norm;
    float2 sampleUv = clamp(uv + offset, float2(0.0), float2(1.0));
    return u_content.eval(sampleUv * u_resolution);
}
"""

@Composable
fun RippleOverlay(
    coreCenter: Offset,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        RippleOverlayAgsl(coreCenter = coreCenter, onComplete = onComplete, modifier = modifier)
    } else {
        // Graceful no-op on API < 33; transfer still completes normally
        LaunchedEffect(Unit) { onComplete() }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun RippleOverlayAgsl(
    coreCenter: Offset,
    onComplete: () -> Unit,
    modifier: Modifier
) {
    val time = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        time.animateTo(
            targetValue = 3f,
            animationSpec = tween(durationMillis = 1500, easing = LinearEasing)
        )
        onComplete()
    }

    val shader = remember {
        android.graphics.RuntimeShader(RIPPLE_AGSL)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        shader.setFloatUniform("u_resolution", size.width, size.height)
        shader.setFloatUniform("u_core_center", coreCenter.x, coreCenter.y)
        shader.setFloatUniform("u_time", time.value)

        drawIntoCanvas { canvas ->
            val ripplePaint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 4f
                color = android.graphics.Color.CYAN
                setShader(shader)
            }
            val elapsed = time.value
            for (i in 0..3) {
                val radius = elapsed * 200f + i * 60f
                val alpha = ((1f - (radius / 800f)).coerceIn(0f, 1f) * 180).toInt()
                ripplePaint.alpha = alpha
                canvas.nativeCanvas.drawCircle(coreCenter.x, coreCenter.y, radius, ripplePaint)
            }
        }
    }
}
