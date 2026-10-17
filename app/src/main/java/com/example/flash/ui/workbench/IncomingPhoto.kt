package com.example.flash.ui.workbench

import android.graphics.Bitmap
import android.net.Uri

/** Where a photo is in the Liquid receive session. */
enum class Stage { WAITING, FEATURED, FLYING, LANDED, PARKED }

/** One band of a photo's display preview, positioned in preview pixels ([y] from the top, [h] tall). */
class BandImage(val n: Int, val y: Int, val h: Int, val bitmap: Bitmap)

/**
 * A photo that is on its way to this phone (Expressive style). The sender announced its size and a
 * tiny blurred [thumb]; [bands] fill in from the bottom while the photo is shown big; the untouched
 * original arrives as [originalUri] and is trusted once [verified] is true.
 */
data class IncomingPhoto(
    val index: Int,
    val width: Int,
    val height: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val bandCount: Int,
    val thumb: Bitmap?,
    val bands: Map<Int, BandImage> = emptyMap(),
    val originalUri: Uri? = null,
    val verified: Boolean? = null,
    val open: Boolean = false,
    val stage: Stage = Stage.WAITING,
    /** 0 = full animation, 1 = quicker, 2 = fast (concurrent), 3 = instant */
    val tier: Int = 0,
    val retries: Int = 0,
    /** Retried 5 times and the checksum still does not match: shown as received, flagged at the end. */
    val unverifiable: Boolean = false
) {
    /** Complete and checksum-verified: safe to reveal the real photo. */
    val ready: Boolean get() = originalUri != null && verified == true
    val aspect: Float get() = width.toFloat() / height.coerceAtLeast(1)
}
