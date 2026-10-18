package com.example.flash.transfer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.call.body
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Size and a tiny blurred thumbnail the sender announces for each photo (preview space is pw x ph). */
class FileMeta(
    val index: Int, val width: Int, val height: Int,
    val previewWidth: Int, val previewHeight: Int, val bandCount: Int,
    val thumb: ByteArray,
    /** "image", "video" or "file" (an older sender sends none: everything is an image). */
    val kind: String = "image", val name: String = "", val size: Long = 0L, val mime: String = ""
)

/** A file saved by [FileClient.download]; [index] is its position on the sender. */
data class DownloadedFile(val index: Int, val file: File, val expectedChecksum: String?)

class FileClient {

    private companion object {
        const val MAX_PARALLEL_DOWNLOADS = 4
    }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 5_000
        }
    }

    /**
     * Downloads the files at [indices] in parallel. Checksum verification is started in
     * [verifyScope] as soon as the downloads finish and runs concurrently with whatever the caller
     * does next (the receiving animation); each result arrives through [onFileVerified]. This
     * function does not wait for it. Corrupted files are NOT deleted here — the caller decides.
     */
    suspend fun downloadAll(
        ip: String,
        port: Int,
        token: String,
        fileCount: Int,
        destDir: File,
        verifyScope: CoroutineScope,
        indices: List<Int> = (0 until fileCount).toList(),
        onProgress: (Float) -> Unit,
        onFileDownloaded: (DownloadedFile) -> Unit = {},
        onFileProgress: (index: Int, progress: Float) -> Unit = { _, _ -> },
        onFileVerified: (index: Int, isValid: Boolean) -> Unit
    ): List<DownloadedFile> {
        val progresses = HashMap<Int, Float>()
        val results = HashMap<Int, DownloadedFile>()
        val lock = Any()
        var firstError: Throwable? = null

        // A few files at a time, in order: with 27 photos all downloading at once every one of them
        // finishes at the very end, so nothing could be shown until the whole batch was done.
        val slots = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_DOWNLOADS)
        supervisorScope {
            indices.forEach { index ->
                launch {
                    runCatching {
                        slots.acquire()
                        try {
                            download(ip, port, token, index, destDir) { progress ->
                                onFileProgress(index, progress)
                                synchronized(lock) {
                                    progresses[index] = progress
                                    onProgress(indices.sumOf { (progresses[it] ?: 0f).toDouble() }.toFloat() / indices.size)
                                }
                            }
                        } finally {
                            slots.release()
                        }
                    }.onSuccess { downloaded ->
                        synchronized(lock) { results[index] = downloaded }
                        // Each file is announced and verified the moment IT is complete, so the UI
                        // can start on the first photo while the others are still downloading.
                        onFileDownloaded(downloaded)
                        verifyScope.launch(Dispatchers.IO) {
                            val expected = downloaded.expectedChecksum
                            val valid = expected == null || verifyChecksum(downloaded.file, expected)
                            onFileVerified(downloaded.index, valid)
                        }
                    }.onFailure { error ->
                        synchronized(lock) { if (firstError == null) firstError = error }
                    }
                }
            }
        }

        val downloaded = indices.mapNotNull { results[it] }
        if (downloaded.isEmpty() && indices.isNotEmpty()) {
            throw IOException("Could not download from $ip:$port — ${firstError?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "no response"}")
        }
        onProgress(1f)

        return downloaded
    }

    /** Downloads ONE file again and verifies it (used to retry a photo that failed its checksum). */
    suspend fun downloadAndVerify(ip: String, port: Int, token: String, index: Int, destDir: File): Pair<DownloadedFile, Boolean>? =
        runCatching {
            val file = download(ip, port, token, index, destDir) {}
            val expected = file.expectedChecksum
            file to (expected == null || verifyChecksum(file.file, expected))
        }.getOrNull()

    /** Asks the sender for photo sizes and tiny thumbnails; null if it is an older sender without previews. */
    suspend fun fetchMeta(ip: String, port: Int, token: String): List<FileMeta>? = runCatching {
        // The sender builds a preview per file: many photos can take a while, so wait generously
        val response = client.get("http://$ip:$port/meta/$token") {
            timeout { requestTimeoutMillis = 120_000 }
        }
        if (!response.status.isSuccess()) return null
        val files = org.json.JSONObject(response.bodyAsText()).getJSONArray("files")
        (0 until files.length()).map { i ->
            val o = files.getJSONObject(i)
            FileMeta(
                index = o.getInt("i"), width = o.getInt("w"), height = o.getInt("h"),
                previewWidth = o.getInt("pw"), previewHeight = o.getInt("ph"), bandCount = o.getInt("bands"),
                thumb = android.util.Base64.decode(o.getString("thumb"), android.util.Base64.DEFAULT),
                kind = o.optString("kind", "image"), name = o.optString("name", ""),
                size = o.optLong("size", 0L), mime = o.optString("mime", "")
            )
        }
    }.getOrNull()

    /** One horizontal band of a photo's display preview (n = 0 is the bottom band), or null. */
    suspend fun fetchBand(ip: String, port: Int, token: String, index: Int, n: Int): ByteArray? = runCatching {
        val response = client.get("http://$ip:$port/band/$token/$index/$n")
        if (!response.status.isSuccess()) return null
        response.body<ByteArray>()
    }.getOrNull()

    suspend fun download(
        ip: String,
        port: Int,
        token: String,
        index: Int,
        destDir: File,
        onProgress: (Float) -> Unit
    ): DownloadedFile {
        destDir.mkdirs()

        // Videos and big files take as long as they take: no overall limit, only a stall limit
        val response: HttpResponse = client.get("http://$ip:$port/transfer/$token/$index") {
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 60_000
            }
        }

        if (!response.status.isSuccess()) {
            throw IOException("Transfer failed: HTTP ${response.status.value}")
        }

        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val expectedChecksum = response.headers["X-File-Checksum"]
        val rawDisposition = response.headers[HttpHeaders.ContentDisposition] ?: ""
        val baseName = sanitizeFileName(extractFileName(rawDisposition))
            ?: "received_${System.currentTimeMillis()}"
        val fileName = "${index}_$baseName"

        val dest = File(destDir, fileName)
        require(dest.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
            "Resolved destination escapes destDir"
        }

        val channel = response.bodyAsChannel()
        val buffer = ByteArray(64 * 1024)
        var bytesRead = 0L

        dest.outputStream().use { out ->
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                    bytesRead += count
                    if (contentLength != null && contentLength > 0) {
                        onProgress((bytesRead.toFloat() / contentLength).coerceIn(0f, 1f))
                    }
                }
            }
        }

        onProgress(1f)
        return DownloadedFile(index, dest, expectedChecksum)
    }

    private fun verifyChecksum(file: File, expectedChecksum: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var count: Int
            while (input.read(buffer).also { count = it } > 0) {
                digest.update(buffer, 0, count)
            }
        }
        val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }
        return actualChecksum == expectedChecksum
    }

    private fun extractFileName(disposition: String): String? {
        return disposition
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("filename=") }
            ?.removePrefix("filename=")
            ?.trim('"')
    }

    private fun sanitizeFileName(raw: String?): String? {
        if (raw == null) return null
        val base = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        return if (base.isBlank() || base == "." || base == "..") null else base
    }

    fun close() = client.close()
}
