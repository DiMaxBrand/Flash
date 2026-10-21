package com.dimax.flash.ui.hints

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.dimax.flash.ui.core.rememberContinuousTime
import com.dimax.flash.ui.theme.OceanAqua
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Small animated drawings for the welcome screen and the hints. Their motion runs on the continuous clock
 * and is periodic (a cosine, a sine), so nothing jumps at a loop point.
 */

private val PhoneLight = Color(0xFF4B6169)
private val PhoneBody = Color(0xFF3A4E56)
private val PhoneEdge = Color(0xFF162024)
private val PhoneGlass = Color(0xFF7FA6B4)
private val LensRing = Color(0xFF8FA3AA)

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

// ───────────────────────── Two phones, back to back ─────────────────────────

/** A tiny 3D view: the scene is turned a little about the vertical axis, tipped forward, and seen in perspective. */
private class Projector(
    private val cx: Float,
    private val cy: Float,
    private val scale: Float,
    yawDegrees: Float,
    pitchDegrees: Float,
    private val camera: Float
) {
    private val cosYaw = cos(Math.toRadians(yawDegrees.toDouble())).toFloat()
    private val sinYaw = sin(Math.toRadians(yawDegrees.toDouble())).toFloat()
    private val cosPitch = cos(Math.toRadians(pitchDegrees.toDouble())).toFloat()
    private val sinPitch = sin(Math.toRadians(pitchDegrees.toDouble())).toFloat()

    /** Scene to view coordinates (x right, y up, z towards the viewer). */
    fun rotate(x: Float, y: Float, z: Float): FloatArray {
        val x1 = x * cosYaw + z * sinYaw
        val z1 = -x * sinYaw + z * cosYaw
        val y2 = y * cosPitch - z1 * sinPitch
        val z2 = y * sinPitch + z1 * cosPitch
        return floatArrayOf(x1, y2, z2)
    }

    fun toScreen(r: FloatArray): Offset {
        val p = camera / (camera - r[2])
        return Offset(cx + r[0] * p * scale, cy - r[1] * p * scale)
    }

    val viewer get() = floatArrayOf(0f, 0f, camera)
}

/**
 * A box (a phone is a thick slab) drawn face by face: only the faces turned towards the viewer, shaded by a
 * fixed light, with a same-coloured round-joined outline so the corners look soft.
 */
private fun DrawScope.drawBox(
    p: Projector,
    x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float,
    color: Color,
    outline: Float
) {
    // each face: outward normal and its four corners
    val faces = arrayOf(
        floatArrayOf(1f, 0f, 0f) to arrayOf(floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y0, z1)),
        floatArrayOf(-1f, 0f, 0f) to arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y1, z0)),
        floatArrayOf(0f, 1f, 0f) to arrayOf(floatArrayOf(x0, y1, z0), floatArrayOf(x0, y1, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0)),
        floatArrayOf(0f, -1f, 0f) to arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1)),
        floatArrayOf(0f, 0f, 1f) to arrayOf(floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1)),
        floatArrayOf(0f, 0f, -1f) to arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y0, z0))
    )
    // light from above, the viewer's side and the left
    val lightLength = sqrt(0.4f * 0.4f + 0.8f * 0.8f + 0.5f * 0.5f)
    val lx = -0.4f / lightLength
    val ly = 0.8f / lightLength
    val lz = 0.5f / lightLength
    val path = Path()
    for ((normal, corners) in faces) {
        val n = p.rotate(normal[0], normal[1], normal[2])
        val rotated = corners.map { p.rotate(it[0], it[1], it[2]) }
        val cxr = rotated.sumOf { it[0].toDouble() }.toFloat() / 4f
        val cyr = rotated.sumOf { it[1].toDouble() }.toFloat() / 4f
        val czr = rotated.sumOf { it[2].toDouble() }.toFloat() / 4f
        val toViewer = floatArrayOf(-cxr, -cyr, p.viewer[2] - czr)
        if (n[0] * toViewer[0] + n[1] * toViewer[1] + n[2] * toViewer[2] <= 0f) continue
        val diffuse = (n[0] * lx + n[1] * ly + n[2] * lz).coerceAtLeast(0f)
        val shaded = lerp(Color.Black, color, 0.35f + 0.65f * diffuse)
        path.reset()
        rotated.forEachIndexed { i, r ->
            val s = p.toScreen(r)
            if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
        }
        path.close()
        drawPath(path, shaded)
        drawPath(path, shaded, style = Stroke(width = outline, join = StrokeJoin.Round))
    }
}

/**
 * Two phones as thick slabs, seen from slightly above and the side, with their BACKS facing each other. Each
 * back has a camera bump; they close until the bumps meet in the middle, and NFC waves spread out from there.
 */
@Composable
fun TapPhonesIllustration(modifier: Modifier = Modifier) {
    val seconds by rememberContinuousTime()
    Canvas(modifier.size(160.dp, 112.dp)) {
        // 0 = apart, 1 = touching; smooth, and it rests a moment at each end (3.6 s round trip)
        val together = smoothstep(((0.5 - 0.5 * cos(2 * PI * seconds / 3.6)).toFloat() - 0.1f) / 0.8f)
        val unit = size.height / 118f
        val projector = Projector(
            cx = size.width / 2f,
            cy = size.height * 0.53f,
            scale = unit,
            yawDegrees = -32f,
            pitchDegrees = 18f,
            camera = 380f
        )

        val thickness = 9f
        val height = 60f
        val width = 30f
        val bump = 3.6f
        val glass = 1.4f
        val gap = 34f * (1f - together)        // how far each phone is from touching
        val halfH = height / 2f
        val halfW = width / 2f
        // the camera bump sits near the top of the back, on one side
        val bumpY0 = halfH - 17f
        val bumpY1 = halfH - 5f
        val bumpZ0 = -halfW + 3f
        val bumpZ1 = -halfW + 17f
        val outline = 1.2f * unit

        // Left phone: its back faces right. The far phone is drawn first; its bump is on the visible side.
        val leftInner = -bump - gap
        drawBox(projector, leftInner - thickness, leftInner, -halfH, halfH, -halfW, halfW, PhoneBody, outline)
        drawBox(projector, leftInner - thickness - glass, leftInner - thickness, -halfH, halfH, -halfW, halfW, PhoneGlass, outline)
        drawBox(projector, leftInner, leftInner + bump, bumpY0, bumpY1, bumpZ0, bumpZ1, PhoneEdge, outline)

        // Right phone: its back faces left. Its bump is on the side that is hidden, so it goes under the body.
        val rightInner = bump + gap
        drawBox(projector, rightInner - bump, rightInner, bumpY0, bumpY1, bumpZ0, bumpZ1, PhoneEdge, outline)
        drawBox(projector, rightInner, rightInner + thickness, -halfH, halfH, -halfW, halfW, PhoneBody, outline)
        drawBox(projector, rightInner + thickness, rightInner + thickness + glass, -halfH, halfH, -halfW, halfW, PhoneGlass, outline)

        // NFC waves from where the bumps meet
        val waves = ((together - 0.75f) / 0.25f).coerceIn(0f, 1f)
        if (waves > 0f) {
            val centre = projector.toScreen(projector.rotate(0f, (bumpY0 + bumpY1) / 2f, (bumpZ0 + bumpZ1) / 2f))
            val phase = (seconds * 0.9) % 1.0
            for (i in 0..2) {
                val t = ((phase + i / 3.0) % 1.0).toFloat()
                drawCircle(
                    color = OceanAqua.copy(alpha = waves * (1f - t) * 0.8f),
                    radius = size.height * (0.07f + 0.34f * t),
                    center = centre,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }
    }
}

// ───────────────────────── Where the NFC chip is ─────────────────────────

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

// ───────────────────────── Scanning the other phone's code ─────────────────────────

/**
 * A wobbling blob (the other phone's code) with scanning corners that start loose and close in until they fit
 * snugly around it and rest there: as close as possible without being so close that it blurs. The corners use
 * the theme's text colour, so they show on a light and on a dark background.
 */
@Composable
fun CameraScanIllustration(modifier: Modifier = Modifier) {
    val seconds by rememberContinuousTime()
    val bracketColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
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

        // 0 = loose, 1 = snug; rests when snug. The loosest corners still fit inside the drawing.
        val snug = smoothstep(((0.5 - 0.5 * cos(2 * PI * seconds / 3.2)).toFloat() - 0.05f) / 0.6f)
        val half = radius * (1.2f + 0.7f * (1f - snug))
        val arm = half * 0.34f
        val stroke = 3.5.dp.toPx()
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
            val corner = Offset(cx + sx * half, cy + sy * half)
            drawLine(bracketColor, corner, Offset(corner.x - sx * arm, corner.y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(bracketColor, corner, Offset(corner.x, corner.y - sy * arm), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
