package com.dimax.flash.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dimax.flash.util.CameraCutout
import com.dimax.flash.util.isReadable
import com.dimax.flash.util.readCameraCutout
import kotlinx.coroutines.delay

/**
 * Feature flag "Show the camera box". Draws where the app thinks the front camera is, over everything, so
 * you can see at a glance whether it sees the camera properly:
 *  - magenta box: every cutout area the phone reports
 *  - cyan dashed halo: the same box, larger, so it stands out around a small hole
 *  - yellow outline: the hole's real shape, when the phone gives it (Android 12+)
 *  - green cross and ring: the point the Mother Core is born from
 *  - red dashed circle instead of all of that: the phone reported nothing, so the top centre of the status
 *    bar was assumed (this is also where the core starts in that case)
 * It never catches touches.
 */
@Composable
fun CameraBoxOverlay() {
    val view = LocalView.current
    val density = LocalDensity.current
    var cutout by remember { mutableStateOf<CameraCutout?>(null) }
    LaunchedEffect(view) {
        val fallback = with(density) { 28.dp.toPx() }
        while (true) {
            if (isReadable(view)) cutout = readCameraCutout(view, fallback)
            delay(500)
        }
    }
    val c = cutout ?: return

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val magenta = Color(0xFFFF2BD6)
            val cyan = Color(0xFF00E5FF)
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            val halo = 10.dp.toPx()
            val line = 2.dp.toPx()

            if (c.fromSystem) {
                c.rects.forEach { r ->
                    val topLeft = Offset(r.left.toFloat(), r.top.toFloat())
                    val size = Size(r.width().toFloat(), r.height().toFloat())
                    drawRect(magenta.copy(alpha = 0.28f), topLeft, size)
                    drawRect(magenta, topLeft, size, style = Stroke(line))
                    drawRoundRect(
                        color = cyan,
                        topLeft = topLeft - Offset(halo, halo),
                        size = Size(size.width + 2 * halo, size.height + 2 * halo),
                        cornerRadius = CornerRadius(halo * 1.5f),
                        style = Stroke(line, pathEffect = dash)
                    )
                }
                c.path?.let { drawPath(it.asComposePath(), Color(0xFFFFEB3B), style = Stroke(line)) }
            } else {
                drawCircle(
                    Color(0xFFFF1744),
                    radius = c.sizePx / 2f + halo,
                    center = c.center,
                    style = Stroke(line, pathEffect = dash)
                )
            }

            // where the core is born
            val arm = 16.dp.toPx()
            val green = Color(0xFF00E676)
            drawLine(green, c.center - Offset(arm, 0f), c.center + Offset(arm, 0f), strokeWidth = line)
            drawLine(green, c.center - Offset(0f, arm), c.center + Offset(0f, arm), strokeWidth = line)
            drawCircle(green, radius = 6.dp.toPx(), center = c.center, style = Stroke(line))
        }

        val first = c.rects.minByOrNull { it.top }
        val label = if (c.fromSystem && first != null) {
            "camera box: reported ${first.width()}x${first.height()} px at (${first.left}, ${first.top})" +
                " · core starts at (${c.center.x.toInt()}, ${c.center.y.toInt()})" +
                " · real outline: ${if (c.path != null) "yes" else "not given"}" +
                " · ${c.rects.size} area(s)"
        } else {
            "camera box: the phone reported NO cutout, so the top centre of the status bar is assumed" +
                " · core starts at (${c.center.x.toInt()}, ${c.center.y.toInt()})"
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = with(density) { (c.center.y + c.sizePx / 2f + 26.dp.toPx()).toDp() }, start = 12.dp, end = 12.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
