package com.dimax.flash.ui.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember

/**
 * Seconds since this was first composed, counting up forever. The morphing noise of the core and of the
 * orbiting photos is driven by this instead of an animation that runs 0 to N and starts over: noise is not
 * periodic, so every restart was a sudden jump (a snap). With a clock that only ever moves forward the
 * shapes keep drifting without a seam.
 *
 * A long gap between frames (the app was in the background) is cut to a tenth of a second so coming back
 * does not jump either.
 */
@Composable
fun rememberContinuousTime(): State<Double> {
    val seconds = remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            seconds.doubleValue += ((now - last) / 1e9).coerceAtMost(0.1)
            last = now
        }
    }
    return seconds
}
