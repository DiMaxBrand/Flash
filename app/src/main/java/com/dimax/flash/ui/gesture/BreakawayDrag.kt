package com.dimax.flash.ui.gesture

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

fun Modifier.breakawayDrag(
    coreCenter: Offset,
    onDragToCore: (spherizeProgress: Float) -> Unit,
    onDragStart: () -> Unit = {},
    onDragCancel: () -> Unit = {}
): Modifier = composed {
    val density = LocalDensity.current
    val context = LocalContext.current

    var isDragging by remember { mutableStateOf(false) }
    var isDropped by remember { mutableStateOf(false) }   // one-shot guard
    var accumulatedDrag by remember { mutableStateOf(Offset.Zero) }
    var startPosition by remember { mutableStateOf(Offset.Zero) }
    var lastSpherize by remember { mutableStateOf(0f) }

    val threshold = with(density) { 30.dp.toPx() }

    pointerInput(coreCenter) {
        detectDragGestures(
            onDragStart = { offset ->
                startPosition = offset
                accumulatedDrag = Offset.Zero
                isDragging = false
                isDropped = false
                lastSpherize = 0f
            },
            onDrag = { change, dragAmount ->
                change.consume()
                accumulatedDrag += dragAmount

                // Upward drag beyond threshold enters breakaway mode (once per gesture)
                if (!isDragging && -accumulatedDrag.y > threshold) {
                    isDragging = true
                    onDragStart()
                    triggerHaptic(context)
                }

                if (isDragging && !isDropped) {
                    // Compute spherize for visual morphing only — no side effects here
                    val distanceTraveled = accumulatedDrag.getDistance()
                    val totalDistance = (startPosition - coreCenter).getDistance()
                    lastSpherize = (distanceTraveled / totalDistance.coerceAtLeast(1f)).coerceIn(0f, 1f)
                }
            },
            onDragEnd = {
                if (isDragging && !isDropped) {
                    // Fire transfer setup exactly once when the gesture ends
                    isDropped = true
                    onDragToCore(lastSpherize)
                } else if (!isDragging) {
                    onDragCancel()
                }
                isDragging = false
                accumulatedDrag = Offset.Zero
            },
            onDragCancel = {
                isDragging = false
                isDropped = false
                accumulatedDrag = Offset.Zero
                onDragCancel()
            }
        )
    }
}

private fun triggerHaptic(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50L)
            }
        }
    } catch (_: Exception) { }
}
