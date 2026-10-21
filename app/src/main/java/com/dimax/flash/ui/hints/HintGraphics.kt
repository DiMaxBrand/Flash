package com.dimax.flash.ui.hints

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.dimax.flash.ui.core.rememberContinuousTime
import com.dimax.flash.ui.theme.OceanAqua
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Small animated drawings for the welcome screen and the hints. Their motion runs on the continuous clock
 * and is periodic (a cosine, a sine), so nothing jumps at a loop point.
 */

private val PhoneLight = Color(0xFF41545B)
private val PhoneBody = Color(0xFF1E2A2E)
private val PhoneEdge = Color(0xFF0B1113)
private val LensRing = Color(0xFF8FA3AA)

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Two phones seen from the back, each turned a little in 3D (a perspective transform), moving together until
 * their backs meet. Where they meet, NFC waves spread out.
 */
@Composable
fun TapPhonesIllustration(modifier: Modifier = Modifier) {
    val seconds by rememberContinuousTime()
    Canvas(modifier.size(160.dp, 112.dp)) {
        // 0 = apart, 1 = touching; smooth, and it rests a moment at each end (3.6 s round trip)
        val together = smoothstep(((0.5 - 0.5 * cos(2 * PI * seconds / 3.6)).toFloat() - 0.1f) / 0.8f)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val phoneW = size.height * 0.46f
        val phoneH = size.height * 0.88f
        val gap = size.width * 0.26f * (1f - together)
        // The inner edges meet at the middle when together = 1
        drawPhoneBack(Offset(cx - phoneW * 0.5f - gap, cy), phoneW, phoneH, yawDegrees = 24f)
        drawPhoneBack(Offset(cx + phoneW * 0.5f + gap, cy), phoneW, phoneH, yawDegrees = -24f)

        val waves = ((together - 0.75f) / 0.25f).coerceIn(0f, 1f)
        if (waves > 0f) {
            val phase = (seconds * 0.9) % 1.0
            for (i in 0..2) {
                val t = ((phase + i / 3.0) % 1.0).toFloat()
                drawCircle(
                    color = OceanAqua.copy(alpha = waves * (1f - t) * 0.7f),
                    radius = phoneH * (0.1f + 0.38f * t),
                    center = Offset(cx, cy - phoneH * 0.12f),
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }
    }
}

/** One phone's back, turned about its vertical axis in perspective, with a darker slab behind it for thickness. */
private fun DrawScope.drawPhoneBack(center: Offset, w: Float, h: Float, yawDegrees: Float) {
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val camera = android.graphics.Camera()
        val matrix = android.graphics.Matrix()
        camera.save()
        camera.setLocation(0f, 0f, -14f)
        camera.rotateY(yawDegrees)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-center.x, -center.y)
        matrix.postTranslate(center.x, center.y)
        native.save()
        native.concat(matrix)

        val corner = CornerRadius(w * 0.2f)
        val topLeft = Offset(center.x - w / 2f, center.y - h / 2f)
        // thickness
        drawRoundRect(PhoneEdge, topLeft = topLeft + Offset(w * 0.07f, h * 0.03f), size = Size(w, h), cornerRadius = corner)
        // the back
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(PhoneLight, PhoneBody),
                start = topLeft,
                end = topLeft + Offset(w, h)
            ),
            topLeft = topLeft,
            size = Size(w, h),
            cornerRadius = corner
        )
        // camera island, top left, with two lenses
        val island = Size(w * 0.42f, h * 0.2f)
        val islandTopLeft = topLeft + Offset(w * 0.1f, h * 0.06f)
        drawRoundRect(PhoneEdge, topLeft = islandTopLeft, size = island, cornerRadius = CornerRadius(w * 0.1f))
        for (i in 0..1) {
            val lens = Offset(islandTopLeft.x + island.width * (0.3f + 0.4f * i), islandTopLeft.y + island.height / 2f)
            drawCircle(LensRing, radius = island.height * 0.3f, center = lens, style = Stroke(width = 1.5.dp.toPx()))
        }
        native.restore()
    }
}

/**
 * The back of a phone with the place where the NFC chip usually is marked: in the upper middle, a bit below
 * the camera. It differs between phones, so it is drawn as an area, not a point.
 */
@Composable
fun NfcChipIllustration(modifier: Modifier = Modifier) {
    val seconds by rememberContinuousTime()
    Canvas(modifier.size(96.dp, 112.dp)) {
        val w = size.width * 0.78f
        val h = size.height * 0.96f
        val topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f)
        val corner = CornerRadius(w * 0.2f)
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(PhoneLight, PhoneBody),
                start = topLeft,
                end = topLeft + Offset(w, h)
            ),
            topLeft = topLeft, size = Size(w, h), cornerRadius = corner
        )
        // camera island
        val island = Size(w * 0.42f, h * 0.18f)
        val islandTopLeft = topLeft + Offset(w * 0.1f, h * 0.05f)
        drawRoundRect(PhoneEdge, topLeft = islandTopLeft, size = island, cornerRadius = CornerRadius(w * 0.1f))
        for (i in 0..1) {
            drawCircle(
                LensRing,
                radius = island.height * 0.3f,
                center = Offset(islandTopLeft.x + island.width * (0.3f + 0.4f * i), islandTopLeft.y + island.height / 2f),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // the usual area of the chip: dashed, upper middle
        val areaCenter = Offset(topLeft.x + w * 0.5f, topLeft.y + h * 0.36f)
        val areaSize = Size(w * 0.56f, h * 0.3f)
        drawRoundRect(
            color = OceanAqua.copy(alpha = 0.9f),
            topLeft = Offset(areaCenter.x - areaSize.width / 2f, areaCenter.y - areaSize.height / 2f),
            size = areaSize,
            cornerRadius = CornerRadius(areaSize.height * 0.3f),
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        )
        // pulsing NFC waves
        val phase = (seconds * 0.8) % 1.0
        for (i in 0..2) {
            val t = ((phase + i / 3.0) % 1.0).toFloat()
            drawCircle(
                color = OceanAqua.copy(alpha = (1f - t) * 0.8f),
                radius = areaSize.height * (0.08f + 0.35f * t),
                center = areaCenter,
                style = Stroke(width = 2.dp.toPx())
            )
        }
        drawCircle(OceanAqua, radius = areaSize.height * 0.07f, center = areaCenter)
    }
}

/**
 * A wobbling blob (the other phone's code) with scanning corners that start loose and close in until they fit
 * snugly around it and rest there: as close as possible without being so close that it blurs.
 */
@Composable
fun CameraScanIllustration(modifier: Modifier = Modifier) {
    val seconds by rememberContinuousTime()
    Canvas(modifier.size(160.dp, 112.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.height * 0.26f

        val blob = Path()
        val points = 56
        for (i in 0..points) {
            val a = 2.0 * PI * i / points
            val r = radius * (1.0 + 0.07 * sin(3 * a + seconds * 1.6) + 0.05 * sin(5 * a - seconds * 1.1))
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) blob.moveTo(x, y) else blob.lineTo(x, y)
        }
        blob.close()
        drawPath(
            blob,
            Brush.radialGradient(
                colors = listOf(Color(0xFF80FFFF), OceanAqua, Color(0xFF003C3C)),
                center = Offset(cx - radius * 0.25f, cy - radius * 0.3f),
                radius = radius * 1.8f
            )
        )

        // 0 = loose, 1 = snug; rests when snug
        val snug = smoothstep(((0.5 - 0.5 * cos(2 * PI * seconds / 3.2)).toFloat() - 0.05f) / 0.6f)
        val half = radius * (1.2f + 0.95f * (1f - snug))
        val arm = half * 0.34f
        val stroke = 3.5.dp.toPx()
        val color = Color.White.copy(alpha = 0.9f)
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
            val corner = Offset(cx + sx * half, cy + sy * half)
            drawLine(color, corner, Offset(corner.x - sx * arm, corner.y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, corner, Offset(corner.x, corner.y - sy * arm), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
