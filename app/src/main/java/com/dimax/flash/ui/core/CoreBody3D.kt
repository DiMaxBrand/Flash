package com.dimax.flash.ui.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The 3D Mother Core (feature flag "3D core"). The core is a rounded glass puck: its flat face is the
 * existing 2D core, drawn onto the front of the puck with a perspective transform, and the rest is
 * glass. The glass is NOT drawn as coloured facets: the whole puck gets ONE smooth outline (the
 * silhouette of its rounded rim seen in perspective), what is behind it is refracted inside that
 * outline in screen space (so it is never tilted along with the face), and only soft gradients and
 * thin edge lines are laid over it.
 *
 * Axes: the phone's frame is x right, y up, z out of the screen towards the viewer. The puck has its own
 * axes (ex right on its face, ey up on its face, ez out of its face), given in the phone's frame.
 */

/** The puck's axes and the light direction, all in the phone's frame. */
internal class CoreFrame(
    val ex: FloatArray,
    val ey: FloatArray,
    val ez: FloatArray,
    val light: FloatArray
)

/** The puck seen on the screen (coordinates inside the core's box). */
internal class CoreProjection(
    /** One smooth outline around the whole puck. */
    val silhouette: Path,
    /** The edge of the front face, and of the back face seen through the glass. */
    val frontOutline: Path,
    val backOutline: Path,
    val bounds: Rect,
    /** Unit vector on the screen pointing towards the light. */
    val light: Offset
)

private const val RINGS = 6              // slices of the rounded rim
private const val POINTS = 44            // points around the outline
private const val BEVEL = 0.1f           // how much smaller the faces are than the widest part
private const val HALF_THICKNESS = 0.22f // of the base radius
private const val CAMERA = 3.4f          // viewing distance, in base radii

/**
 * [r] is the phone to world rotation (world z is up), [standFraction] 0 = the puck lies flat and
 * 1 = it stands upright, [yaw] the horizontal direction from the puck towards the viewer.
 */
internal fun coreFrame(r: FloatArray, standFraction: Float, yaw: Float): CoreFrame {
    val hx = cos(yaw)
    val hy = sin(yaw)
    val theta = standFraction * (PI / 2).toFloat()
    val c = cos(theta)
    val s = sin(theta)

    // World vector to the phone's frame: the transpose of the rotation
    fun phone(x: Float, y: Float, z: Float) = floatArrayOf(
        r[0] * x + r[3] * y + r[6] * z,
        r[1] * x + r[4] * y + r[7] * z,
        r[2] * x + r[5] * y + r[8] * z
    )

    // Lying: the face looks up and its top points away from the viewer. Standing: the face looks at
    // the viewer and its top points up. The same horizontal axis (ex) turns one into the other.
    val ex = phone(-hy, hx, 0f)
    val ey = phone(-c * hx, -c * hy, s)
    val ez = phone(s * hx, s * hy, c)
    // Light from above, the viewer's side and a little to the left
    val light = unit(phone(0.5f * hx + 0.35f * hy, 0.5f * hy - 0.35f * hx, 0.6f))
    return CoreFrame(ex, ey, ez, light)
}

private fun unit(v: FloatArray): FloatArray {
    val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
    return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
}

/**
 * Maps the core's flat 2D drawing (a [width] x [height] box) onto the front of the puck. Returns false
 * when the front is turned away or edge-on, in which case it should not be drawn at all.
 */
internal fun coreFaceMatrix(
    frame: CoreFrame,
    width: Float,
    height: Float,
    baseR: Float,
    matrix: android.graphics.Matrix
): Boolean {
    val dist = baseR * CAMERA
    val t = baseR * HALF_THICKNESS
    if (dist * frame.ez[2] - t <= dist * 0.02f) return false
    val cx = width / 2f
    val cy = height / 2f
    val src = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
    val dst = FloatArray(8)
    for (i in 0 until 4) {
        val lx = (src[2 * i] - cx) * (1f - BEVEL)
        val ly = -(src[2 * i + 1] - cy) * (1f - BEVEL)
        val x = lx * frame.ex[0] + ly * frame.ey[0] + t * frame.ez[0]
        val y = lx * frame.ex[1] + ly * frame.ey[1] + t * frame.ez[1]
        val z = lx * frame.ex[2] + ly * frame.ey[2] + t * frame.ez[2]
        val p = dist / (dist - z)
        dst[2 * i] = cx + x * p
        dst[2 * i + 1] = cy - y * p
    }
    return matrix.setPolyToPoly(src, 0, dst, 0, 4)
}

/**
 * Where the puck lands on the screen: the outlines of its faces and one smooth silhouette around
 * everything (the convex outline of all the slices of its rounded rim).
 */
internal fun projectCore(blobPath: Path, frame: CoreFrame, baseR: Float, boxSize: Float): CoreProjection? {
    val cx = boxSize / 2f
    val cy = boxSize / 2f
    val dist = baseR * CAMERA
    val t = baseR * HALF_THICKNESS
    val n = POINTS

    // The outline of the morphing blob, relative to the centre (screen coordinates, y down)
    val measure = android.graphics.PathMeasure(blobPath.asAndroidPath(), true)
    val length = measure.length
    if (length <= 0f) return null
    val ox = FloatArray(n)
    val oy = FloatArray(n)
    val pos = FloatArray(2)
    for (i in 0 until n) {
        measure.getPosTan(length * i / n, pos, null)
        ox[i] = pos[0] - cx
        oy[i] = pos[1] - cy
    }

    val ex = frame.ex
    val ey = frame.ey
    val ez = frame.ez
    val all = ArrayList<Offset>((RINGS + 1) * n)
    val front = ArrayList<Offset>(n)
    val back = ArrayList<Offset>(n)
    for (k in 0..RINGS) {
        val phi = (-PI / 2 + PI * k / RINGS).toFloat()
        val scale = (1f - BEVEL) + BEVEL * cos(phi)     // widest in the middle, face-sized at both faces
        val z0 = t * sin(phi)
        for (i in 0 until n) {
            val lx = ox[i] * scale
            val ly = -oy[i] * scale
            val x = lx * ex[0] + ly * ey[0] + z0 * ez[0]
            val y = lx * ex[1] + ly * ey[1] + z0 * ez[1]
            val z = lx * ex[2] + ly * ey[2] + z0 * ez[2]
            val p = dist / (dist - z)
            val point = Offset(cx + x * p, cy - y * p)
            all.add(point)
            if (k == RINGS) front.add(point)
            if (k == 0) back.add(point)
        }
    }

    val hull = convexHull(all)
    if (hull.size < 3) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in hull) {
        minX = min(minX, p.x)
        minY = min(minY, p.y)
        maxX = max(maxX, p.x)
        maxY = max(maxY, p.y)
    }
    val lightX = frame.light[0]
    val lightY = -frame.light[1]
    val lightLength = hypot(lightX, lightY)
    val light = if (lightLength < 0.05f) Offset(-0.5f, -0.85f) else Offset(lightX / lightLength, lightY / lightLength)
    return CoreProjection(
        silhouette = smoothClosedPath(hull),
        frontOutline = smoothClosedPath(front),
        backOutline = smoothClosedPath(back),
        bounds = Rect(minX, minY, maxX, maxY),
        light = light
    )
}

/**
 * The glass over what is behind it. It has no colour of its own (no aqua, no facets): a faint body,
 * a Fresnel edge (clear in the middle, brighter towards the rim), one soft sweep of light and
 * shade across it, and thin edge lines for the front face, the back face and the outline.
 */
internal fun DrawScope.drawCoreGlass(proj: CoreProjection, tint: Color?, tintAlpha: Float) {
    val radius = max(proj.bounds.width, proj.bounds.height) / 2f
    val center = proj.bounds.center

    drawPath(proj.silhouette, Color.White.copy(alpha = 0.05f))
    drawPath(
        proj.silhouette,
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.70f to Color.Transparent,
                0.90f to Color.White.copy(alpha = 0.12f),
                1.0f to Color.White.copy(alpha = 0.30f)
            ),
            center = center,
            radius = radius
        )
    )
    drawPath(
        proj.silhouette,
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = 0.26f),
                0.45f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.16f)
            ),
            start = center + proj.light * radius,
            end = center - proj.light * radius
        )
    )
    // The sender's colour (the other phone's camera reads it) also tints the rim, lightly
    if (tint != null) drawPath(proj.silhouette, tint.copy(alpha = tintAlpha * 0.4f))

    drawPath(proj.backOutline, Color.White.copy(alpha = 0.14f), style = Stroke(1.dp.toPx()))
    drawPath(proj.frontOutline, Color.White.copy(alpha = 0.30f), style = Stroke(1.5.dp.toPx()))
    drawPath(proj.silhouette, Color.White.copy(alpha = 0.38f), style = Stroke(1.5.dp.toPx()))
}

/** A [Shape] that is exactly [path], for clipping to the puck's silhouette. */
internal fun pathShape(path: Path): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Generic(path)
}

/** Andrew's monotone chain. */
private fun convexHull(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (p in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeAt(lower.size - 1)
        lower.add(p)
    }
    val upper = ArrayList<Offset>()
    for (p in sorted.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeAt(upper.size - 1)
        upper.add(p)
    }
    lower.removeAt(lower.size - 1)
    upper.removeAt(upper.size - 1)
    return lower + upper
}

/** A closed curve through [points], smoothed the same way as the blob itself (Catmull-Rom to cubics). */
private fun smoothClosedPath(points: List<Offset>): Path {
    val path = Path()
    val n = points.size
    if (n < 3) return path
    fun p(i: Int) = points[((i % n) + n) % n]
    path.moveTo(p(0).x, p(0).y)
    for (i in 0 until n) {
        val p0 = p(i - 1)
        val p1 = p(i)
        val p2 = p(i + 1)
        val p3 = p(i + 2)
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y
        )
    }
    path.close()
    return path
}
