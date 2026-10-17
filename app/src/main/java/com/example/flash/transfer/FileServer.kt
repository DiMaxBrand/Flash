package com.example.flash.transfer

import android.content.Context
import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class FileServer(private val scope: CoroutineScope) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val entries = CopyOnWriteArrayList<Pair<String, Uri>>()
    private var appContext: Context? = null
    private val checksumCache = mutableMapOf<String, String>()

    // ── Display previews: a tiny blurred thumbnail plus the photo in horizontal bands ──────────
    // The receiver shows these while the untouched original is still downloading. Bands are
    // numbered from the BOTTOM of the picture (0 = bottom) so it can be revealed bottom-to-top.
    private class PreviewMeta(val index: Int, val width: Int, val height: Int, val pw: Int, val ph: Int, val thumb: ByteArray)

    private val metaCache = ConcurrentHashMap<Int, PreviewMeta>()
    private val bandCache = ConcurrentHashMap<Int, List<ByteArray>>()

    private fun exifOrientation(uri: Uri): Int = runCatching {
        appContext!!.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orientationMatrix(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }

    /** Decodes [uri] so that its long side is about [target] px, upright (EXIF applied). */
    private fun decodeUpright(uri: Uri, target: Int, orientation: Int, bounds: BitmapFactory.Options): Bitmap? {
        var sample = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = appContext!!.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val m = orientationMatrix(orientation) ?: return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun buildMeta(index: Int): PreviewMeta? = metaCache[index] ?: runCatching {
        val uri = entries.getOrNull(index)?.second ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext!!.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = exifOrientation(uri)
        val swap = orientation in intArrayOf(
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE
        )
        val w = if (swap) bounds.outHeight else bounds.outWidth
        val h = if (swap) bounds.outWidth else bounds.outHeight
        val scale = minOf(1f, PREVIEW_WIDTH.toFloat() / w)
        val pw = (w * scale).toInt().coerceAtLeast(1)
        val ph = (h * scale).toInt().coerceAtLeast(1)

        val small = decodeUpright(uri, 64, orientation, bounds) ?: return null
        val tw = 32
        val th = (tw.toFloat() * small.height / small.width).toInt().coerceAtLeast(1)
        val tiny = Bitmap.createScaledBitmap(small, tw, th, true)
        val out = ByteArrayOutputStream()
        tiny.compress(Bitmap.CompressFormat.JPEG, 45, out)
        PreviewMeta(index, w, h, pw, ph, out.toByteArray()).also { metaCache[index] = it }
    }.getOrNull()

    private fun buildBands(index: Int): List<ByteArray>? = bandCache[index] ?: runCatching {
        val meta = buildMeta(index) ?: return null
        val uri = entries[index].second
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext!!.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val upright = decodeUpright(uri, maxOf(meta.pw, meta.ph), exifOrientation(uri), bounds) ?: return null
        val preview = Bitmap.createScaledBitmap(upright, meta.pw, meta.ph, true)
        val bandHeight = (meta.ph + BAND_COUNT - 1) / BAND_COUNT
        val bands = ArrayList<ByteArray>()
        for (n in 0 until BAND_COUNT) {
            val yBottom = meta.ph - n * bandHeight
            if (yBottom <= 0) break
            val yTop = maxOf(0, yBottom - bandHeight)
            val piece = Bitmap.createBitmap(preview, 0, yTop, meta.pw, yBottom - yTop)
            val out = ByteArrayOutputStream()
            piece.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bands.add(out.toByteArray())
        }
        bands.also { bandCache[index] = it }
    }.getOrNull()

    var port: Int = 0
        private set

    // Which files have been fully sent at least once; lets the sender know the transfer is done.
    private val delivered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var allDeliveredFired = false
    private val _allDelivered = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once every file currently served has been sent in full. */
    val allDelivered: kotlinx.coroutines.flow.SharedFlow<Unit> = _allDelivered

    // Bytes written to receivers vs. bytes to send, for the sender's progress display
    private val sentBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val totalBytes = java.util.concurrent.atomic.AtomicLong(0)
    private var lastProgress = 0f
    private val _sendProgress = kotlinx.coroutines.flow.MutableStateFlow(0f)
    /** 0..1 share of the served bytes already sent (aggregate over all files). */
    val sendProgress: kotlinx.coroutines.flow.StateFlow<Float> = _sendProgress

    private fun onBytesSent(count: Int) {
        val total = totalBytes.get()
        val sent = sentBytes.addAndGet(count.toLong())
        val p = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
        if (p - lastProgress >= 0.005f || p >= 1f) {
            lastProgress = p
            _sendProgress.value = p
        }
    }

    private fun refreshTotalBytes() {
        val cr = appContext?.contentResolver ?: return
        totalBytes.set(entries.sumOf { (_, uri) ->
            runCatching { cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)
        })
    }

    private fun markDelivered(index: Int) {
        delivered.add(index)
        if (!allDeliveredFired && entries.isNotEmpty() && delivered.size >= entries.size) {
            allDeliveredFired = true
            _allDelivered.tryEmit(Unit)
        }
    }

    private fun resetDelivery() {
        delivered.clear()
        allDeliveredFired = false
        sentBytes.set(0)
        lastProgress = 0f
        _sendProgress.value = 0f
    }

    val fileCount: Int get() = entries.size

    private class HashingOutputStream(private val out: OutputStream, private val digest: MessageDigest) : OutputStream() {
        override fun write(b: Int) {
            digest.update(b.toByte())
            out.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            digest.update(b, off, len)
            out.write(b, off, len)
        }
        override fun close() = out.close()
    }

    suspend fun start(token: String, uris: List<Uri>, context: Context): Int {
        stop()
        entries.clear()
        resetDelivery()
        appContext = context.applicationContext
        uris.forEach { entries.add(token to it) }
        refreshTotalBytes()
        port = findFreePort()
        server = embeddedServer(CIO, port = port) {
            routing {
                // Sizes plus a tiny blurred thumbnail for every file (no bands yet: cheap for many photos)
                get("/meta/{token}") {
                    val requestToken = call.parameters["token"] ?: ""
                    if (entries.none { it.first == requestToken }) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val json = withContext(Dispatchers.Default) {
                        val files = JSONArray()
                        for (i in entries.indices) {
                            val meta = buildMeta(i) ?: continue
                            files.put(JSONObject().apply {
                                put("i", i); put("w", meta.width); put("h", meta.height)
                                put("pw", meta.pw); put("ph", meta.ph); put("bands", BAND_COUNT)
                                put("thumb", Base64.encodeToString(meta.thumb, Base64.NO_WRAP))
                            })
                        }
                        JSONObject().put("files", files).toString()
                    }
                    call.respondText(json, ContentType.Application.Json)
                }
                // One horizontal band of the display preview; n = 0 is the bottom band
                get("/band/{token}/{index}/{n}") {
                    val requestToken = call.parameters["token"] ?: ""
                    val index = call.parameters["index"]?.toIntOrNull()
                    val n = call.parameters["n"]?.toIntOrNull()
                    if (index == null || n == null || entries.getOrNull(index)?.first != requestToken) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val bytes = withContext(Dispatchers.Default) { buildBands(index)?.getOrNull(n) }
                    if (bytes == null) call.respond(HttpStatusCode.NotFound)
                    else call.respondBytes(bytes, ContentType.Image.JPEG)
                }
                get("/transfer/{token}/{index}") {
                    val requestToken = call.parameters["token"] ?: ""
                    val index = call.parameters["index"]?.toIntOrNull()
                    if (index == null || index < 0) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@get
                    }
                    val entry = entries.getOrNull(index)
                    if (entry == null || entry.first != requestToken) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val fileUri = entry.second
                    val cr = appContext!!.contentResolver
                    val mimeType = cr.getType(fileUri) ?: "application/octet-stream"
                    val fileName = fileUri.lastPathSegment ?: "file"
                    val fileSize = cr.openFileDescriptor(fileUri, "r")?.use { it.statSize } ?: 0L

                    val cacheKey = "$token/$index"
                    val hash = checksumCache.getOrPut(cacheKey) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        cr.openInputStream(fileUri)?.use { input ->
                            val buffer = ByteArray(8192)
                            var count: Int
                            while (input.read(buffer).also { count = it } > 0) {
                                digest.update(buffer, 0, count)
                            }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }

                    val input = cr.openInputStream(fileUri)
                    if (input == null) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }

                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$fileName\"")
                    call.response.header(HttpHeaders.ContentLength, fileSize.toString())
                    call.response.header("X-File-Checksum", hash)
                    call.respondOutputStream(contentType = ContentType.parse(mimeType)) {
                        input.use { src ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val n = src.read(buffer)
                                if (n < 0) break
                                write(buffer, 0, n)
                                onBytesSent(n)
                            }
                        }
                    }
                    // Reached only if the whole body was written (a dropped client throws above)
                    markDelivered(index)
                }
            }
        }.start(wait = false)
        return port
    }

    fun addUri(token: String, uri: Uri) {
        entries.add(token to uri)
        refreshTotalBytes()
        allDeliveredFired = false   // a new file is waiting; report again when everything is out
    }

    private companion object {
        const val PREVIEW_WIDTH = 1080
        const val BAND_COUNT = 6
    }

    fun stop() {
        metaCache.clear()
        bandCache.clear()
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
        port = 0
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
