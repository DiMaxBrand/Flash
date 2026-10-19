package com.dimax.flash.playground

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.nfc.Tag
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import com.dimax.flash.nfc.HandshakeHceService
import com.dimax.flash.util.LocalNetwork
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/** The experiments the lab can run. The card side answers, the reader side drives. */
enum class LabMode { STOPWATCH, RACE, TAP, RELAY, FIELD, STREAM, PINGPONG, QUIET }

/** Everything the lab screen shows, taken from [NfcLab] a few times a second. */
data class LabSnapshot(
    val role: String = "",
    val touches: Int = 0,
    val sessionMs: Long = 0,
    val bytes: Long = 0,
    val speedKBs: Double = 0.0,
    val rtt: List<Long> = emptyList(),
    val lostAt: String = "",
    val lostReason: String = "",
    val note: String = "",
    val tag: String = "",
    val races: List<Long> = emptyList(),
    val taps: String = "",
    val maxGapMs: Long = 0,
    val passes: Int = 0
)

/**
 * NFC lab. Our own command class (0x80) carries the experiments over the NFC link:
 *  - HELLO (0xD0): the card answers with its Wi-Fi address, token and how much data it can serve
 *  - PULL (0xD1): reads [offset, offset + 240) of the served data, so a 1 MB photo arrives in chunks
 *  - PUSH (0xD2): the reader sends data to the card (tap times)
 *  - PING (0xD3): a short text, answered with "pong ..."
 * Android gives no field strength, so the lab measures the link itself: round-trip time per command,
 * bytes per second, the longest pause the card saw, and the moment the link dropped (the card's
 * [HostApduService.onDeactivated] reason is the reliable signal; the reader only learns when it talks).
 */
object NfcLab {

    val CLA: Byte = 0x80.toByte()
    private const val INS_HELLO = 0xD0
    private const val INS_PULL = 0xD1
    private const val INS_PUSH = 0xD2
    private const val INS_PING = 0xD3
    private const val CHUNK = 240
    private const val PASS_BYTES = 1 shl 20
    private val CUSTOM_AID = HandshakeHceService.CUSTOM_AID
    private val OK = byteArrayOf(0x90.toByte(), 0x00)
    private val END_OF_DATA = byteArrayOf(0x6A, 0x83.toByte())
    private val UNKNOWN = byteArrayOf(0x6D, 0x00)
    private val clockFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val streamData: ByteArray by lazy { ByteArray(PASS_BYTES) { (it * 31 xor (it shr 8)).toByte() } }

    private val lock = Any()

    // ── card side (written by the NFC service thread) ──
    @Volatile var cardActive = false
        private set
    @Volatile var cardMode: LabMode? = null
        private set
    /** What the Forward card serves in the relay chain. */
    @Volatile var relay = ByteArray(0)
    /** Our own accelerometer spikes, for matching against the other phone's taps. */
    @Volatile var cardPeakSource: (() -> List<Long>)? = null
    @Volatile private var blink: BlinkServer? = null
    @Volatile private var target: PlaygroundTarget? = null

    // ── statistics (guarded by [lock]) ──
    private var role = ""
    private var touches = 0
    private var sessionStart = 0L
    private var sessionEnd = 0L
    private var lastCommandAt = 0L
    private var maxGap = 0L
    private var bytes = 0L
    private var passes = 0
    private var lostAt = 0L
    private var lostReason = ""
    private var lastNote = ""
    private var tagText = ""
    private val recent = ArrayDeque<Pair<Long, Int>>()
    private val rtts = ArrayDeque<Long>()
    private val races = ArrayList<Long>()
    private val receivedTaps = ArrayList<Long>()

    fun startCard(context: Context, mode: LabMode) {
        stopCard()
        val token = UUID.randomUUID().toString()
        val server = BlinkServer(token)
        val port = server.start()
        blink = server
        target = PlaygroundTarget(LocalNetwork.localIpv4(context).orEmpty(), port, token)
        cardMode = mode
        cardActive = true
    }

    fun stopCard() {
        cardActive = false
        cardMode = null
        blink?.stop()
        blink = null
        target = null
    }

    /** Every command the card receives while a lab card is active (the handshake SELECTs too). */
    fun noteCommand(apdu: ByteArray) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (sessionStart == 0L || sessionEnd != 0L) startSession("card", now)
            else if (lastCommandAt != 0L) maxGap = maxOf(maxGap, now - lastCommandAt)
            lastCommandAt = now
            addBytes(now, apdu.size)
        }
    }

    /** The lab commands. Anything else is left to the handshake logic in [HandshakeHceService]. */
    fun onCardApdu(apdu: ByteArray): ByteArray {
        val ins = apdu[1].toInt() and 0xFF
        val lc = apdu[4].toInt() and 0xFF
        val data = apdu.copyOfRange(5, minOf(apdu.size, 5 + lc))
        return when (ins) {
            INS_HELLO -> withOk(helloJson().toByteArray(Charsets.UTF_8))
            INS_PULL -> pullReply(data)
            INS_PUSH -> {
                receivePushed(data)
                OK
            }
            INS_PING -> withOk("pong ".toByteArray() + data.copyOf(minOf(data.size, 200)))
            else -> UNKNOWN
        }
    }

    /** The phone went out of range or was moved away from. */
    fun cardDropped(reason: Int) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (sessionStart == 0L || sessionEnd != 0L) return
            sessionEnd = now
            lostAt = now
            lostReason = if (reason == HostApduService.DEACTIVATION_LINK_LOSS) {
                "field gone (link loss)"
            } else {
                "reader moved on (deselected)"
            }
        }
    }

    /** Touch race, card side: each reader touch time that arrives over Wi-Fi, against our first command. */
    suspend fun listenForRaces() {
        while (true) {
            val server = blink
            if (server == null) {
                delay(200)
                continue
            }
            server.nextReport()?.let { reportedTouch ->
                synchronized(lock) {
                    if (sessionStart > 0) races.add(sessionStart - reportedTouch)
                }
            }
        }
    }

    // ── reader side (runs on the NFC callback thread) ──

    fun readerSession(tag: Tag, iso: IsoDep, mode: LabMode, tapSensor: TapSensor?) {
        val touchAt = System.currentTimeMillis()
        synchronized(lock) { startSession("reader", touchAt) }
        try {
            iso.connect()
            iso.timeout = 2_000
            synchronized(lock) { tagText = describe(tag, iso) }

            var reply = exchange(iso, selectCommand())
            if (!ok(reply)) {
                note("not a Flash phone, or no lab card there (sw ${sw(reply)})")
                return
            }
            val selectedAt = System.currentTimeMillis()
            reply = exchange(iso, command(INS_HELLO, ByteArray(0), le = true))
            if (!ok(reply)) {
                note("the other phone does not run the lab (sw ${sw(reply)})")
                return
            }
            val helloAt = System.currentTimeMillis()
            val hello = JSONObject(String(body(reply), Charsets.UTF_8))
            val other = PlaygroundTarget(hello.optString("ip"), hello.optInt("port"), hello.optString("token"))
            val size = hello.optInt("size")

            when (mode) {
                LabMode.STOPWATCH -> note(
                    "touch to select ${selectedAt - touchAt} ms, select to answer ${helloAt - selectedAt} ms. Lift to end."
                )
                LabMode.RACE -> raceTouch(other, touchAt)
                LabMode.TAP -> tapTogether(iso, other, tapSensor)
                LabMode.RELAY -> relayHop(iso, size, touchAt)
                LabMode.FIELD -> pingLoop(iso, 200)
                LabMode.STREAM -> streamLoop(iso, size)
                LabMode.PINGPONG -> pingLoop(iso, 250)
                LabMode.QUIET -> quietWait()
            }
        } catch (e: Exception) {
            readerLost("${e.javaClass.simpleName}: ${e.message ?: "contact lost"}")
        } finally {
            runCatching { iso.close() }
            synchronized(lock) { if (sessionEnd == 0L) sessionEnd = System.currentTimeMillis() }
        }
    }

    private fun raceTouch(other: PlaygroundTarget, touchAt: Long) {
        if (other.ip.isBlank()) {
            note("the other phone has no Wi-Fi address, so the race cannot be timed")
            return
        }
        val client = BlinkClient(other)
        val offset = client.clockOffsetMs()
        val sent = client.report(touchAt + offset)
        note(if (sent) "touch time sent to the other phone (clock offset $offset ms)" else "could not reach the other phone over Wi-Fi")
    }

    private fun tapTogether(iso: IsoDep, other: PlaygroundTarget, sensor: TapSensor?) {
        if (other.ip.isBlank()) {
            note("the other phone has no Wi-Fi address, so taps cannot be matched")
            return
        }
        val offset = BlinkClient(other).clockOffsetMs()
        var sent = 0
        while (true) {
            Thread.sleep(400)
            val taps = sensor?.take().orEmpty()
            taps.chunked(25).forEach { chunk ->
                val buffer = ByteBuffer.allocate(chunk.size * 8)
                chunk.forEach { buffer.putLong(it + offset) }
                exchange(iso, command(INS_PUSH, buffer.array()))
            }
            if (taps.isNotEmpty()) {
                sent += taps.size
                note("sent $sent tap times (clock offset $offset ms)")
            }
        }
    }

    private fun relayHop(iso: IsoDep, size: Int, touchAt: Long) {
        val payload = pullAll(iso, size)
        val hop = System.currentTimeMillis() - touchAt
        relay = (String(payload, Charsets.UTF_8) + " | $hop ms").toByteArray(Charsets.UTF_8)
        note("received ${payload.size} bytes, hop $hop ms. Now set this phone to Forward and touch the next one.")
    }

    private fun streamLoop(iso: IsoDep, size: Int) {
        if (size <= 0) {
            note("the other phone has nothing to stream")
            return
        }
        while (true) {
            val got = pullAll(iso, size)
            if (got.size < size) {
                note("stream stopped early: ${got.size} of $size bytes")
                return
            }
            synchronized(lock) { passes++ }
        }
    }

    private fun pingLoop(iso: IsoDep, pauseMs: Long) {
        var n = 0
        while (true) {
            val reply = exchange(iso, command(INS_PING, "ping $n".toByteArray(Charsets.UTF_8), le = true))
            if (!ok(reply)) {
                note("ping $n got no answer (sw ${sw(reply)})")
                return
            }
            n++
            note("ping $n answered: ${String(body(reply), Charsets.UTF_8)}")
            Thread.sleep(pauseMs)
        }
    }

    private fun quietWait() {
        note("quiet: synced. Nothing more is sent; the card reports the drop.")
        Thread.sleep(120_000)
        note("quiet for 2 minutes with no traffic. The card reports the drop.")
    }

    /** Reads the whole served data in chunks. Stops early if the card answers with an error. */
    private fun pullAll(iso: IsoDep, size: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset < size) {
            val reply = exchange(iso, command(INS_PULL, ByteBuffer.allocate(4).putInt(offset).array(), le = true))
            if (!ok(reply)) break
            val part = body(reply)
            if (part.isEmpty()) break
            out.write(part)
            offset += part.size
        }
        return out.toByteArray()
    }

    private fun readerLost(reason: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (sessionEnd == 0L) {
                sessionEnd = now
                lostAt = now
                lostReason = reason
            }
        }
    }

    private fun exchange(iso: IsoDep, apdu: ByteArray): ByteArray {
        val t0 = System.nanoTime()
        val reply = iso.transceive(apdu)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            rtts.addLast((System.nanoTime() - t0) / 1_000_000)
            if (rtts.size > 200) rtts.removeFirst()
            addBytes(now, apdu.size + reply.size)
        }
        return reply
    }

    private fun note(text: String) = synchronized(lock) { lastNote = text }

    // ── helpers ──

    private fun helloJson(): String {
        val t = target
        return JSONObject()
            .put("ip", t?.ip.orEmpty())
            .put("port", t?.port ?: 0)
            .put("token", t?.token.orEmpty())
            .put("size", servedData().size)
            .toString()
    }

    private fun servedData(): ByteArray = when (cardMode) {
        LabMode.STREAM -> streamData
        LabMode.RELAY -> relay
        else -> ByteArray(0)
    }

    private fun pullReply(data: ByteArray): ByteArray {
        if (data.size < 4) return UNKNOWN
        val offset = ByteBuffer.wrap(data).int
        val served = servedData()
        if (offset < 0 || offset >= served.size) return END_OF_DATA
        val n = minOf(CHUNK, served.size - offset)
        synchronized(lock) { addBytes(System.currentTimeMillis(), n) }
        return withOk(served.copyOfRange(offset, offset + n))
    }

    private fun receivePushed(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        synchronized(lock) {
            while (buffer.remaining() >= 8) receivedTaps.add(buffer.long)
            while (receivedTaps.size > 500) receivedTaps.removeAt(0)
        }
    }

    private fun startSession(newRole: String, now: Long) {
        role = newRole
        touches++
        sessionStart = now
        sessionEnd = 0
        lastCommandAt = 0
        maxGap = 0
        bytes = 0
        passes = 0
        lostAt = 0
        lostReason = ""
        lastNote = ""
        recent.clear()
        rtts.clear()
    }

    private fun addBytes(now: Long, n: Int) {
        bytes += n
        recent.addLast(now to n)
        while (recent.isNotEmpty() && now - recent.first().first > 2_000) recent.removeFirst()
    }

    private fun speedLocked(now: Long): Double {
        val inWindow = recent.filter { now - it.first <= 2_000 }.sumOf { it.second }
        return inWindow / 2.0 / 1024.0
    }

    /** Matches the other phone's taps (already on our clock) with our own spikes within 300 ms. */
    private fun tapSummary(): String {
        if (receivedTaps.isEmpty()) return ""
        val peaks = cardPeakSource?.invoke().orEmpty()
        val gaps = receivedTaps
            .mapNotNull { r -> peaks.minOfOrNull { abs(it - r) }?.takeIf { it <= 300 } }
            .sorted()
        val median = if (gaps.isEmpty()) "-" else "${gaps[gaps.size / 2]} ms"
        return "${receivedTaps.size} taps from the other phone, ${gaps.size} matched with this phone's sensor, median gap $median"
    }

    fun snapshot(): LabSnapshot = synchronized(lock) {
        val now = System.currentTimeMillis()
        val sorted = rtts.sorted()
        LabSnapshot(
            role = role,
            touches = touches,
            sessionMs = if (sessionStart == 0L) 0L else (if (sessionEnd == 0L) now else sessionEnd) - sessionStart,
            bytes = bytes,
            speedKBs = speedLocked(now),
            rtt = if (sorted.isEmpty()) emptyList() else listOf(sorted.first(), sorted[sorted.size / 2], sorted.last()),
            lostAt = if (lostAt == 0L) "" else clockFormat.format(Date(lostAt)),
            lostReason = lostReason,
            note = lastNote,
            tag = tagText,
            races = races.takeLast(5),
            taps = tapSummary(),
            maxGapMs = maxGap,
            passes = passes
        )
    }

    /** Wi-Fi signal of the network both phones use: the closest thing to signal strength the phone exposes. */
    @Suppress("DEPRECATION")
    fun wifiLine(context: Context): String {
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
            ?: return "Wi-Fi: unknown"
        return if (info.networkId == -1) {
            "Wi-Fi: not connected"
        } else {
            "Wi-Fi signal ${info.rssi} dBm, link speed ${info.linkSpeed} Mb/s"
        }
    }

    private fun describe(tag: Tag, iso: IsoDep): String {
        val id = tag.id.joinToString("") { "%02X".format(it) }
        val techs = tag.techList.joinToString { it.substringAfterLast('.') }
        return "id $id, $techs, max command ${iso.maxTransceiveLength} bytes, timeout ${iso.timeout} ms, " +
            "ATS ${iso.historicalBytes?.size ?: 0} bytes"
    }

    private fun command(ins: Int, data: ByteArray, le: Boolean = false): ByteArray {
        val lc = if (data.isEmpty()) byteArrayOf() else byteArrayOf(data.size.toByte()) + data
        return byteArrayOf(CLA, ins.toByte(), 0, 0) + lc + (if (le) byteArrayOf(0) else byteArrayOf())
    }

    private fun selectCommand(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, CUSTOM_AID.size.toByte()) + CUSTOM_AID + byteArrayOf(0x00)

    private fun withOk(bytes: ByteArray): ByteArray = bytes + OK

    private fun ok(reply: ByteArray): Boolean =
        reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()

    private fun body(reply: ByteArray): ByteArray = reply.copyOfRange(0, reply.size - 2)

    private fun sw(reply: ByteArray): String =
        if (reply.size < 2) "none" else "%02X%02X".format(reply[reply.size - 2], reply[reply.size - 1])
}

/**
 * Accelerometer spikes: a tap on a table shows up as a sharp jolt. Each phone records the moments of
 * its own jolts, and [NfcLab] compares them with the other phone's.
 */
class TapSensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val peaks = ArrayList<Long>()
    private val unsent = ArrayList<Long>()
    private var lastPeak = 0L

    fun start() {
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    fun all(): List<Long> = synchronized(this) { peaks.toList() }

    /** The spikes not yet sent to the other phone. */
    fun take(): List<Long> = synchronized(this) {
        val out = unsent.toList()
        unsent.clear()
        out
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (sqrt(x * x + y * y + z * z) < 12f) return
        val now = System.currentTimeMillis()
        if (now - lastPeak < 300) return
        lastPeak = now
        synchronized(this) {
            peaks.add(now)
            unsent.add(now)
            if (peaks.size > 500) peaks.removeAt(0)
            if (unsent.size > 500) unsent.removeAt(0)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
