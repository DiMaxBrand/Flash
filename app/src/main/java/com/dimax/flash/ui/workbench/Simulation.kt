package com.dimax.flash.ui.workbench

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

/** FAST = a normal Wi-Fi where everything is over almost at once; SLOW = about one second per picture. */
enum class SimSpeed { FAST, SLOW }

/** What happens to the second item: arrives intact, arrives damaged but a retry fixes it, or never verifies. */
enum class SimDamage { NONE, RECOVERS, STAYS }

/** What is sent: pictures only, a mix of pictures / a video / a document, only videos, or only documents. */
enum class SimContent { PHOTOS, MIXED, VIDEOS, FILES }

/** A generated test item: the file stands in for the original, [preview] for the sender's display preview. */
class SimPhoto(
    val index: Int,
    val file: File,
    val width: Int,
    val height: Int,
    val preview: Bitmap,
    val thumb: Bitmap,
    val kind: MediaKind = MediaKind.IMAGE,
    val name: String = file.name,
    val size: Long = file.length()
)

object SimImages {
    private val ASPECTS = floatArrayOf(0.75f, 1.333f, 1f, 0.5625f)   // width / height
    private val DOC_NAMES = arrayOf("Report.docx", "Budget.xlsx", "Notes.pdf", "Slides.pptx")

    private fun kindFor(content: SimContent, i: Int): MediaKind = when (content) {
        SimContent.PHOTOS -> MediaKind.IMAGE
        SimContent.VIDEOS -> MediaKind.VIDEO
        SimContent.FILES -> MediaKind.FILE
        SimContent.MIXED -> when (i % 3) { 0 -> MediaKind.IMAGE; 1 -> MediaKind.VIDEO; else -> MediaKind.FILE }
    }

    /** Creates [count] items in cacheDir/sim (old ones are removed first). Videos and documents are dummy bytes. */
    fun generate(context: Context, count: Int, content: SimContent = SimContent.PHOTOS): List<SimPhoto> {
        val dir = File(context.cacheDir, "sim").apply { deleteRecursively(); mkdirs() }
        return (0 until count).map { i ->
            val kind = kindFor(content, i)
            if (kind == MediaKind.FILE) {
                val name = DOC_NAMES[(i / 3 + i) % DOC_NAMES.size].let { "${it.substringBeforeLast('.')}_${i + 1}.${it.substringAfterLast('.')}" }
                val file = File(dir, name).apply { writeBytes(ByteArray(48 * 1024) { (it * 31).toByte() }) }
                val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                return@map SimPhoto(i, file, 1, 1, blank, blank, kind, name, file.length())
            }
            val aspect = if (kind == MediaKind.VIDEO) 1.778f else ASPECTS[i % ASPECTS.size]
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
            paint.textSize = minOf(w, h) * 0.45f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${i + 1}", w / 2f, h / 2f + paint.textSize * 0.35f, paint)
            // Horizontal rulers so the bottom-to-top wipe is easy to follow
            paint.color = Color.argb(110, 0, 0, 0)
            for (k in 1 until 10) canvas.drawRect(0f, h * k / 10f - 3f, w.toFloat(), h * k / 10f + 3f, paint)

            val isVideo = kind == MediaKind.VIDEO
            val name = if (isVideo) "Clip_${i + 1}.mp4" else "sim_${i + 1}.jpg"
            val file = File(dir, name)
            if (isVideo) file.writeBytes(ByteArray(600 * 1024) { (it * 17).toByte() })
            else file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            val preview = Bitmap.createScaledBitmap(bmp, 540, (540f * h / w).toInt(), true)
            val thumb = Bitmap.createScaledBitmap(bmp, 32, (32f * h / w).toInt().coerceAtLeast(1), true)
            bmp.recycle()
            SimPhoto(i, file, w, h, preview, thumb, kind, name, file.length())
        }
    }
}
