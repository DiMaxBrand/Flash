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

class FileServer(private val scope: CoroutineScope) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val entries = CopyOnWriteArrayList<Pair<String, Uri>>()
    private var appContext: Context? = null
    private val checksumCache = mutableMapOf<String, String>()

    var port: Int = 0
        private set

    // Which files have been fully sent at least once; lets the sender know the transfer is done.
    private val delivered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var allDeliveredFired = false
    private val _allDelivered = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once every file currently served has been sent in full. */
    val allDelivered: kotlinx.coroutines.flow.SharedFlow<Unit> = _allDelivered

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
        port = findFreePort()
        server = embeddedServer(CIO, port = port) {
            routing {
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
                        input.use { it.copyTo(this) }
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
        allDeliveredFired = false   // a new file is waiting; report again when everything is out
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1000)
        server = null
        port = 0
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}
