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
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Small animated drawings for the welcome screen and the hints. Their motion runs on the continuous clock
 * and is periodic (a cosine, a sine), so nothing jumps at a loop point.
 */

private val PhoneLight = Color(0xFF4B6169)
private val PhoneBody = Color(0xFF34444B)
private val PhoneEdge = Color(0xFF151D21)
private val PhoneGlass = Color(0xFF7FA6B4)
private val LensRing = Color(0xFF8FA3AA)
private val LensInside = Color(0xFF0B1113)

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

/** The corners of a rounded rectangle in a phone's plane (y up the phone, z across it), going once round. */
private fun roundedRect(y0: Float, y1: Float, z0: Float, z1: Float, r: Float, steps: Int = 6): List<FloatArray> {
    val points = ArrayList<FloatArray>()
    // each corner: its centre and where its quarter turn starts (the angle runs from +y towards +z)
    val corners = arrayOf(
        floatArrayOf(y1 - r, z1 - r, 0f),
        floatArrayOf(y0 + r, z1 - r, 90f),
        floatArrayOf(y0 + r, z0 + r, 180f),
        floatArrayOf(y1 - r, z0 + r, 270f)
    )
    for (c in corners) {
        for (k in 0..steps) {
            val a = Math.toRadians((c[2] + 90f * k / steps).toDouble())
            points.add(floatArrayOf(c[0] + r * cos(a).toFloat(), c[1] + r * sin(a).toFloat()))
        }
    }
    return points
}

private fun circlePoints(y: Float, z: Float, r: Float, n: Int = 20): List<FloatArray> =
    List(n) { k ->
        val a = 2.0 * PI * k / n
        floatArrayOf(y + r * cos(a).toFloat(), z + r * sin(a).toFloat())
    }

/**
 * One flat face with an outward normal, drawn only if it faces the viewer, shaded by a fixed light, with a
 * same-coloured round-joined outline so edges look soft.
 */
private fun DrawScope.drawFace(
    p: Projector,
    normal: FloatArray,
    corners: List<FloatArray>,
    color: Color,
    outline: Float
) {
    val n = p.rotate(normal[0], normal[1], normal[2])
    val rotated = corners.map { p.rotate(it[0], it[1], it[2]) }
    var cx = 0f
    var cy = 0f
    var cz = 0f
    for (r in rotated) {
        cx += r[0]
        cy += r[1]
        cz += r[2]
    }
    cx /= rotated.size
    cy /= rotated.size
    cz /= rotated.size
    if (n[0] * -cx + n[1] * -cy + n[2] * (p.viewer[2] - cz) <= 0f) return
    // light from above, the viewer's side and the left
    val lightLength = kotlin.math.sqrt(0.4f * 0.4f + 0.8f * 0.8f + 0.5f * 0.5f)
    val diffuse = (n[0] * -0.4f / lightLength + n[1] * 0.8f / lightLength + n[2] * 0.5f / lightLength).coerceAtLeast(0f)
    val shaded = lerp(Color.Black, color, 0.35f + 0.65f * diffuse)
    val path = Path()
    rotated.forEachIndexed { i, r ->
        val s = p.toScreen(r)
        if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
    }
    path.close()
    drawPath(path, shaded)
    drawPath(path, shaded, style = Stroke(width = outline, join = StrokeJoin.Round))
}

/** A thin plate: [outline] (in the y-z plane) extruded along x from [x0] to [x1], so a rounded phone, not a box. */
private fun DrawScope.drawPlate(
    p: Projector,
    outline: List<FloatArray>,
    x0: Float,
    x1: Float,
    color: Color,
    lineWidth: Float
) {
    var cy = 0f
    var cz = 0f
    for (pt in outline) {
        cy += pt[0]
        cz += pt[1]
    }
    cy /= outline.size
    cz /= outline.size
    for (i in outline.indices) {
        val a = outline[i]
        val b = outline[(i + 1) % outline.size]
        val ny = (a[0] + b[0]) / 2f - cy
        val nz = (a[1] + b[1]) / 2f - cz
        val length = hypot(ny, nz).coerceAtLeast(1e-4f)
        drawFace(
            p, floatArrayOf(0f, ny / length, nz / length),
            listOf(
                floatArrayOf(x0, a[0], a[1]), floatArrayOf(x1, a[0], a[1]),
                floatArrayOf(x1, b[0], b[1]), floatArrayOf(x0, b[0], b[1])
            ),
            color, lineWidth
        )
    }
    drawFace(p, floatArrayOf(-1f, 0f, 0f), outline.map { floatArrayOf(x0, it[0], it[1]) }, color, lineWidth)
    drawFace(p, floatArrayOf(1f, 0f, 0f), outline.map { floatArrayOf(x1, it[0], it[1]) }, color, lineWidth)
}

/**
 * Two phones, thin with rounded corners, seen from slightly above and the side, with their BACKS facing each
 * other. The left phone shows its back: the camera island with two lenses. The right one shows its screen
 * side (its own back and island are on the hidden side). They close until the islands meet in the middle,
 * and NFC waves spread out from there. (The first versions drew thick boxes, which looked like bricks.)
 */
@Composable
fun TapPhonesIllustration(modifier: Modifier = Modifier) {
    val seconds by rememberContinuousTime()
    Canvas(modifier.size(160.dp, 112.dp)) {
        // 0 = apart, 1 = touching; smooth, and it rests a moment at each end (3.6 s round trip)
        val together = smoothstep(((0.5 - 0.5 * cos(2 * PI * seconds / 3.6)).toFloat() - 0.1f) / 0.8f)
        val unit = size.height / 100f
        val projector = Projector(
            cx = size.width / 2f,
            cy = size.height * 0.53f,
            scale = unit,
            yawDegrees = -24f,
            pitchDegrees = 14f,
            camera = 380f
        )

        val thickness = 4.2f
        val halfH = 32f
        val halfW = 15.5f
        val bump = 1.5f
        val glass = 0.9f
        val gap = 34f * (1f - together)        // how far each phone is from touching
        val line = 1.0f * unit

        val body = roundedRect(-halfH, halfH, -halfW, halfW, 6.5f)
        val island = roundedRect(halfH - 19f, halfH - 5f, -halfW + 3.5f, -halfW + 17f, 3.6f)
        val lensZ = -halfW + 10.2f

        // Left phone: its back faces right, with the island and two lenses on it
        val leftBack = -bump - gap
        drawPlate(projector, body, leftBack - thickness, leftBack, PhoneBody, line)
        drawPlate(projector, body, leftBack - thickness - glass, leftBack - thickness, PhoneGlass, line)
        drawPlate(projector, island, leftBack, leftBack + bump, PhoneEdge, line)
        for (lensY in floatArrayOf(halfH - 8.8f, halfH - 15.2f)) {
            drawFace(projector, floatArrayOf(1f, 0f, 0f), circlePoints(lensY, lensZ, 3.0f).map { floatArrayOf(leftBack + bump + 0.05f, it[0], it[1]) }, LensRing, line * 0.6f)
            drawFace(projector, floatArrayOf(1f, 0f, 0f), circlePoints(lensY, lensZ, 1.9f).map { floatArrayOf(leftBack + bump + 0.1f, it[0], it[1]) }, LensInside, line * 0.3f)
        }

        // Right phone: its back faces left; the island is on the side we cannot see, so the body covers it
        val rightBack = bump + gap
        drawPlate(projector, island, rightBack - bump, rightBack, PhoneEdge, line)
        drawPlate(projector, body, rightBack, rightBack + thickness, PhoneBody, line)
        drawPlate(projector, body, rightBack + thickness, rightBack + thickness + glass, PhoneGlass, line)

        // NFC waves from where the islands meet
        val waves = ((together - 0.75f) / 0.25f).coerceIn(0f, 1f)
        if (waves > 0f) {
            val centre = projector.toScreen(projector.rotate(0f, halfH - 12f, lensZ))
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
