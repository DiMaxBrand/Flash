package com.dimax.flash.ui.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.dimax.flash.ui.theme.OceanAqua
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The 3D Mother Core (feature flag "3D core"). The core is a rounded puck: its flat face is the existing
 * 2D core, drawn onto the front of the puck with a perspective transform, and the rounded rim and the
 * back are drawn here from the same morphing outline. Everything is plain geometry, no extra library.
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

private const val RINGS = 7              // slices of the rounded rim
private const val POINTS = 44            // points around the outline
private const val BEVEL = 0.2f           // how much the face is inset against the widest part
private const val HALF_THICKNESS = 0.3f  // of the base radius
private const val CAMERA = 3.4f          // viewing distance, in base radii

private val ShadowColor = Color(0xFF003C3C)

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

/** The rounded rim and, when the puck is seen from behind, its back. The front is the 2D core itself. */
internal fun DrawScope.drawCoreBody(
    blobPath: Path,
    frame: CoreFrame,
    baseR: Float,
    tint: Color?,
    tintAlpha: Float
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val dist = baseR * CAMERA
    val t = baseR * HALF_THICKNESS
    val n = POINTS

    // The outline of the morphing blob, relative to the centre (screen coordinates, y down)
    val measure = android.graphics.PathMeasure(blobPath.asAndroidPath(), true)
    val length = measure.length
    if (length <= 0f) return
    val ox = FloatArray(n)
    val oy = FloatArray(n)
    val pos = FloatArray(2)
    for (i in 0 until n) {
        measure.getPosTan(length * i / n, pos, null)
        ox[i] = pos[0] - cx
        oy[i] = pos[1] - cy
    }
    // Outward unit normals of the outline
    val nx = FloatArray(n)
    val ny = FloatArray(n)
    for (i in 0 until n) {
        val a = (i + n - 1) % n
        val b = (i + 1) % n
        var px = oy[b] - oy[a]
        var py = -(ox[b] - ox[a])
        if (px * ox[i] + py * oy[i] < 0f) {
            px = -px
            py = -py
        }
        val l = hypot(px, py).coerceAtLeast(1e-4f)
        nx[i] = px / l
        ny[i] = py / l
    }

    val ex = frame.ex
    val ey = frame.ey
    val ez = frame.ez
    val light = frame.light
    val count = (RINGS + 1) * n
    val sx = FloatArray(count)
    val sy = FloatArray(count)
    val diffuse = FloatArray(count)
    val specular = FloatArray(count)
    val facing = FloatArray(count)

    for (k in 0..RINGS) {
        val phi = (-PI / 2 + PI * k / RINGS).toFloat()
        val cp = cos(phi)
        val sp = sin(phi)
        val scale = (1f - BEVEL) + BEVEL * cp     // widest in the middle, the face-sized at both faces
        val z0 = t * sp
        for (i in 0 until n) {
            val lx = ox[i] * scale
            val ly = -oy[i] * scale
            val x = lx * ex[0] + ly * ey[0] + z0 * ez[0]
            val y = lx * ex[1] + ly * ey[1] + z0 * ez[1]
            val z = lx * ex[2] + ly * ey[2] + z0 * ez[2]
            val p = dist / (dist - z)
            val idx = k * n + i
            sx[idx] = cx + x * p
            sy[idx] = cy - y * p

            // Surface normal of the rounded rim, in the phone's frame
            val mx = cp * nx[i]
            val my = -cp * ny[i]
            val mz = sp
            val normalX = mx * ex[0] + my * ey[0] + mz * ez[0]
            val normalY = mx * ex[1] + my * ey[1] + mz * ez[1]
            val normalZ = mx * ex[2] + my * ey[2] + mz * ez[2]
            // Towards the viewer, who sits on the z axis
            val vx = -x
            val vy = -y
            val vz = dist - z
            val vl = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-6f)
            facing[idx] = (normalX * vx + normalY * vy + normalZ * vz) / vl
            diffuse[idx] = max(0f, normalX * light[0] + normalY * light[1] + normalZ * light[2])
            val hx = light[0] + vx / vl
            val hy = light[1] + vy / vl
            val hz = light[2] + vz / vl
            val hl = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-6f)
            specular[idx] = max(0f, (normalX * hx + normalY * hy + normalZ * hz) / hl).pow(24)
        }
    }

    val base = if (tint != null) lerp(OceanAqua, tint, tintAlpha) else OceanAqua
    val path = Path()

    // The back, when the puck is seen from behind: the first ring, face-sized
    val backVisible = -dist * ez[2] - t > dist * 0.02f
    if (backVisible) {
        val backShade = max(0f, -(ez[0] * light[0] + ez[1] * light[1] + ez[2] * light[2]))
        val color = lerp(ShadowColor, base, 0.3f + 0.55f * backShade)
        path.reset()
        path.moveTo(sx[0], sy[0])
        for (i in 1 until n) path.lineTo(sx[i], sy[i])
        path.close()
        drawPath(path, color)
    }

    // The rim: quads facing the viewer; the face itself covers whatever is behind it
    for (k in 0 until RINGS) {
        for (i in 0 until n) {
            val a = k * n + i
            val b = k * n + (i + 1) % n
            val c = (k + 1) * n + (i + 1) % n
            val d = (k + 1) * n + i
            if (facing[a] + facing[b] + facing[c] + facing[d] <= 0f) continue
            val diff = (diffuse[a] + diffuse[b] + diffuse[c] + diffuse[d]) / 4f
            val spec = (specular[a] + specular[b] + specular[c] + specular[d]) / 4f
            val shaded = lerp(ShadowColor, base, 0.25f + 0.75f * diff)
            val color = lerp(shaded, Color.White, (spec * 0.6f).coerceAtMost(0.7f))
            path.reset()
            path.moveTo(sx[a], sy[a])
            path.lineTo(sx[b], sy[b])
            path.lineTo(sx[c], sy[c])
            path.lineTo(sx[d], sy[d])
            path.close()
            drawPath(path, color)
            // A thin stroke in the same colour hides the hairlines between neighbouring quads
            drawPath(path, color, style = Stroke(width = 1.2f))
        }
    }
}
