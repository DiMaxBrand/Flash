package com.dimax.flash.ui.core

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the phone's motion sensors say, in the form the Mother Core uses. Two independent parts,
 * each behind its own feature flag:
 *  - [tilt]: the core leans downhill when the phone tilts and wobbles when it is shaken
 *  - [threeD]: the core is a 3D object fixed in the room; [rotation] is the phone's orientation
 *    and the core lies flat or stands upright, whichever is closer to how the phone is held
 */
@Stable
class CoreMotionState(
    val tilt: Boolean,
    val threeD: Boolean,
    private val scope: CoroutineScope
) {
    // ── tilt / shake (screen coordinates: x right, y down) ──
    /** Which way is downhill, -1..1 on each axis (low-passed gravity). */
    var driftX by mutableFloatStateOf(0f)
        private set
    var driftY by mutableFloatStateOf(0f)
        private set
    /** Spring offset in dp, pushed against the phone's acceleration. */
    var jiggleX by mutableFloatStateOf(0f)
        private set
    var jiggleY by mutableFloatStateOf(0f)
        private set
    /** 0 at rest, towards 1 while the phone is being shaken. */
    var shake by mutableFloatStateOf(0f)
        private set

    private var jx = 0f
    private var jy = 0f
    private var vx = 0f
    private var vy = 0f
    private var lastLinearNs = 0L

    // ── 3D pose ──
    /** Phone to world rotation (row-major 3x3, world z is up); null until the first sensor sample. */
    var rotation by mutableStateOf<FloatArray?>(null)
        private set
    /** 0 = the core lies flat, 1 = it stands upright; animated when the phone is moved across the middle. */
    val standFraction = Animatable(0f)
    /**
     * The horizontal angle from the core towards the viewer. It follows the phone's heading, so turning
     * the phone about the vertical axis (a horizontal turn) never swings the core away: only tilting
     * the phone (forward, back, sideways) shows the core from another side.
     */
    var yaw by mutableFloatStateOf(0f)
        private set

    private val matrix = FloatArray(9)
    private var poseKnown = false
    private var standing = false

    fun onGravity(x: Float, y: Float, z: Float) {
        val length = sqrt(x * x + y * y + z * z)
        if (length < 1f) return
        // The accelerometer points up; downhill on the screen is the opposite, and the screen's y runs down
        driftX += (-x / length - driftX) * 0.2f
        driftY += (y / length - driftY) * 0.2f
    }

    fun onLinear(x: Float, y: Float, z: Float, timestampNs: Long) {
        val dt = if (lastLinearNs == 0L) 0.02f else ((timestampNs - lastLinearNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastLinearNs = timestampNs
        // A soft body lags behind the phone: the core is pushed against the acceleration. Resting at
        // 10 m/s2 moves it about 10 dp, which the springs reach with a little overshoot.
        val ax = x
        val ay = -y
        val k = 60f
        val c = 7f
        val h = dt / 2f
        repeat(2) {
            vx += (-k * (jx + ax) - c * vx) * h
            vy += (-k * (jy + ay) - c * vy) * h
            jx = (jx + vx * h).coerceIn(-20f, 20f)
            jy = (jy + vy * h).coerceIn(-20f, 20f)
        }
        jiggleX = jx
        jiggleY = jy
        val magnitude = sqrt(x * x + y * y + z * z)
        shake = shake * 0.94f + 0.06f * min(1f, magnitude / 12f)
    }

    fun onRotation(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(matrix, values)
        val r = matrix.copyOf()
        rotation = r
        // The core always faces whoever is looking, so its heading follows the phone's heading almost at once
        val target = viewerYaw(r)
        if (target != null) yaw = if (!poseKnown) target else yaw + wrapPi(target - yaw) * 0.5f
        // How much the screen faces the ceiling or the floor: 1 flat, 0 upright
        val flat = abs(r[8])
        if (!poseKnown) {
            poseKnown = true
            standing = flat < 0.707f
            scope.launch { standFraction.snapTo(if (standing) 1f else 0f) }
        } else if (standing && flat > 0.80f) {
            switchTo(false)
        } else if (!standing && flat < 0.62f) {
            switchTo(true)
        }
    }

    private fun switchTo(stand: Boolean) {
        standing = stand
        scope.launch {
            standFraction.animateTo(if (stand) 1f else 0f, tween(700, easing = FastOutSlowInEasing))
        }
    }

    private fun wrapPi(angle: Float): Float {
        var a = angle
        val twoPi = (2 * PI).toFloat()
        while (a > PI) a -= twoPi
        while (a < -PI) a += twoPi
        return a
    }
}

/**
 * The horizontal direction from the core towards whoever is looking at the phone, from the phone to
 * world rotation [r]. An upright phone says it with where the screen points; a phone lying flat has no
 * horizontal screen direction, so there it is where the bottom edge points. The two are blended by how
 * upright the phone is. Turning the phone about the vertical axis turns this angle by the same amount,
 * which is what keeps the core facing the viewer through a horizontal turn.
 */
internal fun viewerYaw(r: FloatArray): Float? {
    val nx = r[2]
    val ny = r[5]       // the screen's normal in the world
    val bx = -r[1]
    val by = -r[4]      // the bottom edge's direction in the world
    val nh = hypot(nx, ny)
    val bh = hypot(bx, by)
    val upright = ((nh - 0.15f) / 0.35f).coerceIn(0f, 1f)
    var x = 0f
    var y = 0f
    if (nh > 1e-4f) {
        x += upright * nx / nh
        y += upright * ny / nh
    }
    if (bh > 1e-4f) {
        x += (1f - upright) * bx / bh
        y += (1f - upright) * by / bh
    }
    if (hypot(x, y) < 0.05f) return null
    return atan2(y, x)
}

/**
 * Starts the sensors the two feature flags need, and only while the screen is in front. Returns null
 * when both flags are off, so nothing is registered and the core is exactly what it was.
 */
@Composable
fun rememberCoreMotion(tilt: Boolean, threeD: Boolean): CoreMotionState? {
    if (!tilt && !threeD) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(tilt, threeD) { CoreMotionState(tilt, threeD, scope) }

    LifecycleResumeEffect(state) {
        val manager = context.getSystemService(SensorManager::class.java)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER ->
                        state.onGravity(event.values[0], event.values[1], event.values[2])
                    Sensor.TYPE_LINEAR_ACCELERATION ->
                        state.onLinear(event.values[0], event.values[1], event.values[2], event.timestamp)
                    Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR ->
                        state.onRotation(event.values)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun register(type: Int): Boolean {
            val sensor = manager.getDefaultSensor(type) ?: return false
            return manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        if (tilt) {
            if (!register(Sensor.TYPE_GRAVITY)) register(Sensor.TYPE_ACCELEROMETER)
            register(Sensor.TYPE_LINEAR_ACCELERATION)
        }
        if (threeD) {
            // The game vector has no compass drift; the full one is the fallback
            if (!register(Sensor.TYPE_GAME_ROTATION_VECTOR)) register(Sensor.TYPE_ROTATION_VECTOR)
        }
        onPauseOrDispose { manager.unregisterListener(listener) }
    }
    return state
}
