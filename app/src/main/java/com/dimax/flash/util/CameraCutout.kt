package com.dimax.flash.util

import android.os.Build
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Where the front camera hole is, as far as Android tells us. The Mother Core is born at this spot (it starts
 * as a black blob that looks like the hole growing), and the "Show the camera box" feature flag draws it.
 *
 * Android only reports a hole after the view is attached to its window, and the view has no width before it
 * is laid out, so read this once both are true ([isReadable]), not at the first frame.
 */
data class CameraCutout(
    /** Every cutout area the system reports (the camera hole, a notch, ...), in window pixels. */
    val rects: List<android.graphics.Rect>,
    /** The real outline of the hole, when the phone gives it (Android 12 and newer). */
    val path: android.graphics.Path?,
    /** Where the blob starts from, in window pixels. */
    val center: Offset,
    /** How big the camera area is: the larger side of its box (or a guess when nothing was reported). */
    val sizePx: Float,
    /** Height of the status bar, in pixels. */
    val topInsetPx: Float,
    /** False = the phone reported no cutout and the top centre of the status bar was assumed. */
    val fromSystem: Boolean
)

fun isReadable(view: View): Boolean = view.width > 0 && view.rootWindowInsets != null

/**
 * The cutout nearest the top edge of the screen. A phone can report several (a notch and a side cutout, for
 * example) and only the one at the top is the front camera. [fallbackSizePx] is used when none is reported.
 */
fun readCameraCutout(view: View, fallbackSizePx: Float): CameraCutout {
    val topInset = ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top?.toFloat() ?: 0f
    var rects = emptyList<android.graphics.Rect>()
    var path: android.graphics.Path? = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val cutout = view.rootWindowInsets?.displayCutout
        if (cutout != null) {
            rects = cutout.boundingRects
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) path = cutout.cutoutPath
        }
    }
    val top = rects.minByOrNull { it.top }
    return if (top != null) {
        CameraCutout(
            rects = rects,
            path = path,
            center = Offset(top.exactCenterX(), top.exactCenterY()),
            sizePx = max(top.width(), top.height()).toFloat(),
            topInsetPx = topInset,
            fromSystem = true
        )
    } else {
        CameraCutout(
            rects = rects,
            path = path,
            center = Offset(view.width / 2f, topInset / 2f),
            sizePx = fallbackSizePx,
            topInsetPx = topInset,
            fromSystem = false
        )
    }
}
