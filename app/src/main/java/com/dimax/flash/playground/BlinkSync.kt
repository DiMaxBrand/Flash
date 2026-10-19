package com.dimax.flash.playground

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.cio.CIOApplicationEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * The leader's side of the blink test. It fires a blink at a moment it chooses, and the follower
 * flashes as soon as it hears about it and reports its own time back.
 */
class BlinkServer(private val token: String) {

    private val fires = Channel<Long>(Channel.UNLIMITED)
    private val reports = Channel<Long>(Channel.UNLIMITED)
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    var port = 0
        private set

    fun start(): Int {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, port = port) {
            routing {
                // Clock comparison: the follower asks for the leader's time several times
                get("/clock") { call.respondText(System.currentTimeMillis().toString()) }
                // The follower waits here until the next blink (up to a minute)
                get("/next") {
                    if (call.request.queryParameters["token"] != token) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@get
                    }
                    val fire = withTimeoutOrNull(60_000) { fires.receive() }
                    if (fire == null) call.respond(HttpStatusCode.NoContent)
                    else call.respondText(fire.toString())
                }
                // The follower's flash time, already converted to the leader's clock
                post("/flashed") {
                    if (call.request.queryParameters["token"] != token) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    }
                    val t = call.request.queryParameters["t"]?.toLongOrNull()
                    if (t == null) call.respond(HttpStatusCode.BadRequest)
                    else {
                        reports.send(t)
                        call.respond(HttpStatusCode.OK)
                    }
                }
            }
        }.start(wait = false)
        return port
    }

    fun fire(atMs: Long) {
        fires.trySend(atMs)
    }

    /** The follower's flash time on the leader's clock, or null if it didn't answer in time. */
    suspend fun nextReport(): Long? = withTimeoutOrNull(5_000) { reports.receive() }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
        server = null
    }
}

/** The follower's side: align clocks, wait for each blink, flash, and report. */
class BlinkClient(private val target: PlaygroundTarget) {

    /** Leader's clock minus this phone's clock, from the sample with the shortest round trip. */
    fun clockOffsetMs(): Long {
        var bestRtt = Long.MAX_VALUE
        var bestOffset = 0L
        repeat(7) {
            val t0 = System.currentTimeMillis()
            val leaderTime = get("/clock")?.toLongOrNull() ?: return@repeat
            val t1 = System.currentTimeMillis()
            val rtt = t1 - t0
            if (rtt < bestRtt) {
                bestRtt = rtt
                bestOffset = leaderTime - (t0 + t1) / 2
            }
        }
        return bestOffset
    }

    /** Waits for the next blink; null when none came in time. */
    fun awaitBlink(): Boolean = get("/next?token=${target.token}") != null

    fun report(leaderClockMs: Long): Boolean =
        post("/flashed?token=${target.token}&t=$leaderClockMs") != null

    private fun url(path: String) = URL("http://${target.ip}:${target.port}$path")

    private fun get(path: String): String? {
        val connection = url(path).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 65_000
            if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun post(path: String): String? {
        val connection = url(path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (connection.responseCode in 200..299) "ok" else null
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
