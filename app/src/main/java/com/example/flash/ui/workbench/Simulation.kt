package com.example.flash.ui.workbench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.File

/** Which phone the simulation pretends to be. */
enum class SimRole { RECEIVER, SENDER }

/** FAST = a normal Wi-Fi where everything is over almost at once; SLOW = about one second per file. */
enum class SimSpeed { FAST, SLOW }

/** What happens to the second photo: arrives intact, arrives damaged but a retry fixes it, or never verifies. */
enum class SimDamage { NONE, RECOVERS, STAYS }

/** A generated test photo: the file stands in for the original, [preview] for the sender's display preview. */
class SimPhoto(
    val index: Int,
    val file: File,
    val width: Int,
    val height: Int,
    val preview: Bitmap,
    val thumb: Bitmap
)

object SimImages {
    private val ASPECTS = floatArrayOf(0.75f, 1.333f, 1f, 0.5625f)   // width / height

    /** Draws [count] colourful numbered pictures into cacheDir/sim (old ones are removed first). */
    fun generate(context: Context, count: Int): List<SimPhoto> {
        val dir = File(context.cacheDir, "sim").apply { deleteRecursively(); mkdirs() }
        return (0 until count).map { i ->
            val aspect = ASPECTS[i % ASPECTS.size]
            val w = 1080
            val h = (w / aspect).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val hue = (i * 47f) % 360f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.95f)),
                Color.HSVToColor(floatArrayOf((hue + 70f) % 360f, 0.75f, 0.45f)),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
            paint.color = Color.argb(70, 255, 255, 255)
            canvas.drawCircle(w * 0.25f, h * 0.30f, w * 0.22f, paint)
            canvas.drawCircle(w * 0.75f, h * 0.70f, w * 0.30f, paint)
            paint.color = Color.WHITE
            paint.textSize = w * 0.45f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${i + 1}", w / 2f, h / 2f + paint.textSize * 0.35f, paint)
            // Horizontal rulers so the bottom-to-top wipe is easy to follow
            paint.color = Color.argb(110, 0, 0, 0)
            for (k in 1 until 10) canvas.drawRect(0f, h * k / 10f - 3f, w.toFloat(), h * k / 10f + 3f, paint)

            val file = File(dir, "sim_${i + 1}.jpg")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            val preview = Bitmap.createScaledBitmap(bmp, 540, (540f * h / w).toInt(), true)
            val thumb = Bitmap.createScaledBitmap(bmp, 32, (32f * h / w).toInt().coerceAtLeast(1), true)
            bmp.recycle()
            SimPhoto(i, file, w, h, preview, thumb)
        }
    }
}
