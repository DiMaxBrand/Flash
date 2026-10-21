package com.dimax.flash.ui.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The 3D core's geometry: it must face the viewer in both resting poses and stay fixed in the room. */
class CoreFrameTest {

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    // Phone upright, screen towards a viewer standing to the world's +x side: device x -> world y,
    // device y -> world z (up), device z -> world x. The columns of the matrix are the device axes.
    private val upright = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)

    private fun assertAxis(expected: FloatArray, actual: FloatArray) {
        for (i in 0 until 3) assertEquals(expected[i], actual[i], 1e-4f)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** A rotation of the phone about its own x axis (tilting it back by [angle]). */
    private fun tilted(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        // world = rotation about the world x axis of the flat phone
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    private fun world(r: FloatArray, v: FloatArray) = floatArrayOf(
        r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
        r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
        r[6] * v[0] + r[7] * v[1] + r[8] * v[2]
    )

    /** The phone turned about the vertical axis by [angle]: a horizontal turn. */
    private fun turnedHorizontally(r: FloatArray, angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        // world' = Rz(angle) * world, so the new matrix is Rz * r
        val rz = floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
        val out = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var sum = 0f
            for (k in 0 until 3) sum += rz[i * 3 + k] * r[k * 3 + j]
            out[i * 3 + j] = sum
        }
        return out
    }

    @Test
    fun aHorizontalTurnNeverChangesWhatYouSee() {
        // Upright, a little tilted back, and flat: turn each one right round. The core faces the viewer
        // through the whole turn, so its axes in the phone's own frame (what you see) stay the same.
        for (base in listOf(upright, tilted(0.5f), tilted(1.2f), identity)) {
            for (standFraction in listOf(0f, 0.5f, 1f)) {
                val yaw0 = viewerYaw(base) ?: continue
                val expected = coreFrame(base, standFraction, yaw0)
                for (step in 1 until 12) {
                    val turned = turnedHorizontally(base, step * (PI / 6).toFloat())
                    val yaw = viewerYaw(turned) ?: continue
                    val frame = coreFrame(turned, standFraction, yaw)
                    assertAxis(expected.ex, frame.ex)
                    assertAxis(expected.ey, frame.ey)
                    assertAxis(expected.ez, frame.ez)
                }
            }
        }
    }

    @Test
    fun uprightPhoneSeesTheStandingCoreFaceOn() {
        // Standing, facing the viewer's side (yaw 0): the core's axes are the phone's own
        val frame = coreFrame(upright, 1f, 0f)
        assertAxis(floatArrayOf(1f, 0f, 0f), frame.ex)
        assertAxis(floatArrayOf(0f, 1f, 0f), frame.ey)
        assertAxis(floatArrayOf(0f, 0f, 1f), frame.ez)
    }

    @Test
    fun flatPhoneSeesTheLyingCoreFaceOn() {
        // The viewer sits at the bottom edge (world -y), so the core faces world -y when it stands
        val frame = coreFrame(identity, 0f, (-PI / 2).toFloat())
        assertAxis(floatArrayOf(1f, 0f, 0f), frame.ex)
        assertAxis(floatArrayOf(0f, 1f, 0f), frame.ey)
        assertAxis(floatArrayOf(0f, 0f, 1f), frame.ez)
    }

    @Test
    fun axesStayOrthonormalWhileSwitching() {
        val frame = coreFrame(tilted(0.7f), 0.37f, 1.1f)
        assertEquals(1f, dot(frame.ex, frame.ex), 1e-4f)
        assertEquals(1f, dot(frame.ey, frame.ey), 1e-4f)
        assertEquals(1f, dot(frame.ez, frame.ez), 1e-4f)
        assertEquals(0f, dot(frame.ex, frame.ey), 1e-4f)
        assertEquals(0f, dot(frame.ex, frame.ez), 1e-4f)
        assertEquals(0f, dot(frame.ey, frame.ez), 1e-4f)
    }

    @Test
    fun coreStaysFixedInTheRoomWhateverTheOrientation() {
        // Same pose (lying, same yaw), two different ways of holding the phone: the axes seen from the
        // room are identical, which is what makes the core look like it hangs in the world
        val a = coreFrame(identity, 0f, 0.4f)
        val b = coreFrame(tilted(0.9f), 0f, 0.4f)
        assertAxis(world(identity, a.ez), world(tilted(0.9f), b.ez))
        assertAxis(world(identity, a.ex), world(tilted(0.9f), b.ex))
        assertAxis(world(identity, a.ey), world(tilted(0.9f), b.ey))
        // ...and it is lying: its face looks at the ceiling
        assertAxis(floatArrayOf(0f, 0f, 1f), world(identity, a.ez))
    }
}
