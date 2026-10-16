package com.example.flash.transfer

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class DownloadedFile(val file: File, val expectedChecksum: String?)

class FileClient {

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }

    private val corruptedFiles = mutableListOf<String>()
    private val corruptedIndices = mutableListOf<Int>()
    private val verifiedFiles = mutableListOf<Pair<Int, Boolean>>()

    suspend fun downloadAll(
        ip: String,
        port: Int,
        token: String,
        fileCount: Int,
        destDir: File,
        onProgress: (Float) -> Unit,
        onFileVerified: (index: Int, isValid: Boolean) -> Unit = { _, _ -> },
        onCorrupted: (List<String>, List<Int>) -> Unit = { _, _ -> }
    ): List<File> {
        val progresses = FloatArray(fileCount)
        val files = arrayOfNulls<File>(fileCount)
        val checksums = arrayOfNulls<String>(fileCount)
        val lock = Any()

        supervisorScope {
            (0 until fileCount).forEach { index ->
                launch {
                    runCatching {
                        download(ip, port, token, index, destDir) { progress ->
                            synchronized(lock) {
                                progresses[index] = progress
                                onProgress(progresses.average().toFloat())
                            }
                        }
                    }.onSuccess { (file, checksum) ->
                        synchronized(lock) {
                            files[index] = file
                            checksums[index] = checksum
                        }
                    }
                }
            }
        }

        onProgress(1f)
        val result = files.filterNotNull()

        // Async corruption check in background
        launchVerification(files, checksums, onFileVerified, onCorrupted)

        return result
    }

    private suspend fun launchVerification(
        files: Array<File?>,
        checksums: Array<String?>,
        onFileVerified: (index: Int, isValid: Boolean) -> Unit,
        onCorrupted: (List<String>, List<Int>) -> Unit
    ) {
        supervisorScope {
            launch {
                delay(500)  // Let UI settle
                corruptedFiles.clear()
                corruptedIndices.clear()
                verifiedFiles.clear()
                checksums.forEachIndexed { index, checksum ->
                    val file = files[index]
                    if (checksum != null && file != null) {
                        val isValid = verifyChecksum(file, checksum, index)
                        verifiedFiles.add(index to isValid)
                        onFileVerified(index, isValid)
                    }
                }
                if (corruptedFiles.isNotEmpty()) {
                    corruptedFiles.forEach { fileName ->
                        files.filterNotNull().find { it.name == fileName }?.delete()
                    }
                    onCorrupted(corruptedFiles.toList(), corruptedIndices.toList())
                }
            }
        }
    }

    suspend fun download(
        ip: String,
        port: Int,
        token: String,
        index: Int,
        destDir: File,
        onProgress: (Float) -> Unit
    ): DownloadedFile {
        destDir.mkdirs()

        val response: HttpResponse = client.get("http://$ip:$port/transfer/$token/$index")

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
        val buffer = ByteArray(8192)
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
        return DownloadedFile(dest, expectedChecksum)
    }

    private fun verifyChecksum(file: File, expectedChecksum: String, index: Int): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var count: Int
            while (input.read(buffer).also { count = it } > 0) {
                digest.update(buffer, 0, count)
            }
        }
        val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }
        val isValid = actualChecksum == expectedChecksum
        if (!isValid) {
            corruptedFiles.add(file.name)
            corruptedIndices.add(index)
        }
        return isValid
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

    fun getCorruptedFiles(): List<String> = corruptedFiles.toList()

    fun close() = client.close()
}
