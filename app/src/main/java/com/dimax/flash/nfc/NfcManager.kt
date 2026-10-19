package com.dimax.flash.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class PeerHandshake(
    val ip: String,
    val port: Int,
    val token: String,
    val lang: String,
    val fileCount: Int = 1
)

/**
 * NFC pairing. The SENDER is a card (Host Card Emulation, see [HandshakeHceService]) that serves a
 * tiny NDEF message with its address and token. The RECEIVER is a reader: it runs reader mode, opens
 * the other phone as ISO-DEP, selects our application and reads that message. The photos themselves
 * never travel over NFC; they go over Wi-Fi once the receiver knows where to connect.
 *
 * A phone in reader mode cannot act as a card, so the screen enables reader mode only while NO photos
 * are selected (receiver) and turns it off while photos are selected (sender).
 */
class NfcManager(private val context: Context) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    val isNfcAvailable: Boolean get() = adapter != null
    val isNfcEnabled: Boolean   get() = adapter?.isEnabled == true

    // Holds the last handshake until the screen takes it. When the app was closed, the touch starts
    // MainActivity before any screen exists, so an event that is only emitted could be missed.
    private val _pendingHandshake = MutableStateFlow<PeerHandshake?>(null)
    val pendingHandshake: StateFlow<PeerHandshake?> = _pendingHandshake.asStateFlow()

    // Messages from the playground (Wi-Fi tags, blink targets) are not handshakes: the playground
    // screen takes them from here.
    private val _readNdef = MutableStateFlow<NdefMessage?>(null)
    val readNdef: StateFlow<NdefMessage?> = _readNdef.asStateFlow()

    fun consumeReadNdef() {
        _readNdef.value = null
    }

    /** Makes this phone a card that serves [message] (null stops serving). Needs reader mode off. */
    fun serveNdef(message: NdefMessage?) {
        HandshakeHceService.ndefMessageBytes = message?.toByteArray()
    }

    private fun routeRead(message: NdefMessage) {
        if (com.dimax.flash.playground.PlaygroundNdef.isPlayground(message)) {
            log("playground tag read")
            _readNdef.value = message
        } else {
            parseAndEmit(message)
        }
    }

    fun consumeHandshake() {
        _pendingHandshake.value = null
    }

    /** The last few NFC events, shown on screen so a failed tap can be diagnosed without a computer. */
    private val _status = MutableStateFlow<List<String>>(emptyList())
    val status: StateFlow<List<String>> = _status.asStateFlow()

    private var outboundMessage: NdefMessage? = null
    private var activity: Activity? = null
    private var lastToken: String? = null
    private var lastTokenAt = 0L
    private var readerOn = false

    // The playground's lab experiments take over the tag themselves (see NfcLab.readerSession)
    @Volatile private var labReader: ((Tag, IsoDep) -> Unit)? = null

    fun setLabReader(handler: ((Tag, IsoDep) -> Unit)?) {
        labReader = handler
    }

    init {
        HandshakeHceService.listener = { log(it) }
    }

    fun log(line: String) {
        _status.update { (it + line).takeLast(6) }
    }

    /** A touch that reached a phone but could not be read (contact too short, no handshake, ...). */
    private val _readFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val readFailed: SharedFlow<String> = _readFailed.asSharedFlow()

    /** Logs the reason (kept for diagnosing) and tells the screen to ask for another touch. */
    private fun fail(reason: String) {
        log(reason)
        _readFailed.tryEmit(reason)
    }

    fun initialize(act: Activity) {
        activity = act
    }

    // ───────────────────────────── Sender (card) ─────────────────────────────

    fun setOutboundHandshake(ip: String, port: Int, token: String, lang: String, fileCount: Int = 1) {
        val json = JSONObject().apply {
            put("ip", ip)
            put("port", port)
            put("token", token)
            put("lang", lang)
            put("fileCount", fileCount)
        }.toString()

        val mimeRecord = NdefRecord.createMime("application/vnd.flash.handshake", json.toByteArray(Charsets.UTF_8))
        // Android Application Record: opens Flash if the receiving phone's app is not running
        val aarRecord = NdefRecord.createApplicationRecord(context.packageName)

        val msg = NdefMessage(arrayOf(mimeRecord, aarRecord))
        outboundMessage = msg
        HandshakeHceService.ndefMessageBytes = msg.toByteArray()
        log("card ready: $ip:$port (${msg.toByteArray().size} bytes)")
        logDefaultHandler()
    }

    fun clearOutboundHandshake() {
        outboundMessage = null
        HandshakeHceService.ndefMessageBytes = null
    }

    /** Tells whether Android will actually route our AIDs to Flash (another NFC app could be in the way). */
    private fun logDefaultHandler() {
        val ad = adapter ?: return
        runCatching {
            val emulation = CardEmulation.getInstance(ad)
            val me = ComponentName(context, HandshakeHceService::class.java)
            val own = emulation.isDefaultServiceForAid(me, "F0394D4B03")
            if (!own) log("card: Android routes our NFC id to another app")
        }
    }

    /** While photos are selected, ask Android to prefer Flash's card service (only works with the app in front). */
    fun setPreferredCardService(act: Activity, preferred: Boolean) {
        val ad = adapter ?: return
        runCatching {
            val emulation = CardEmulation.getInstance(ad)
            if (preferred) emulation.setPreferredService(act, ComponentName(context, HandshakeHceService::class.java))
            else emulation.unsetPreferredService(act)
        }
    }

    // ───────────────────────────── Receiver (reader) ─────────────────────────────

    fun enableReaderMode(act: Activity) {
        val ad = adapter
        if (ad == null) { log("this phone has no NFC"); return }
        if (!ad.isEnabled) { log("NFC is switched off in the system settings"); return }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        val options = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        runCatching {
            ad.enableReaderMode(act, { tag -> readIsoDep(tag) }, flags, options)
            if (!readerOn) log("reader on: touch the sender's phone")
            readerOn = true
        }.onFailure { log("reader mode failed: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun disableReaderMode(act: Activity) {
        runCatching { adapter?.disableReaderMode(act) }
        readerOn = false
    }

    /**
     * Phone to phone: SELECT our application, SELECT the NDEF file, READ BINARY (length, then the message).
     * Falls back to the standard NDEF tag application if ours is not answered.
     */
    private fun readIsoDep(tag: Tag) {
        val iso = IsoDep.get(tag)
        if (iso == null) {
            log("touched something that is not a phone (${tag.techList.joinToString { it.substringAfterLast('.') }})")
            return
        }
        labReader?.let {
            it(tag, iso)
            return
        }
        try {
            iso.connect()
            iso.timeout = 3000
            var reply = iso.transceive(selectAid(HandshakeHceService.CUSTOM_AID))
            var ownApp = true
            if (!ok(reply)) {
                log("our app: sw=${sw(reply)}, trying the standard NDEF app")
                ownApp = false
                reply = iso.transceive(selectAid(HandshakeHceService.NDEF_AID))
                if (!ok(reply)) { fail("no Flash handshake on that phone (sw=${sw(reply)})"); return }
            }
            reply = iso.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04))
            if (!ok(reply)) { fail("select NDEF file: sw=${sw(reply)}"); return }

            // As few round trips as possible: a short touch is all it takes. Our own app answers
            // big reads, so the length and (normally) the whole message arrive in ONE read; the
            // standard application only promises 59 bytes per read.
            val chunk = if (ownApp) 250 else 48
            reply = iso.transceive(readBinary(0, chunk))
            if (!ok(reply) || reply.size < 4) { fail("read length: sw=${sw(reply)}"); return }
            val length = ((reply[0].toInt() and 0xFF) shl 8) or (reply[1].toInt() and 0xFF)
            if (length <= 0 || length > 1022) { fail("nothing to share yet (length $length)"); return }

            val message = ByteArray(length)
            var have = minOf(reply.size - 2 - 2, length)          // bytes of the message in the first reply
            reply.copyInto(message, 0, 2, 2 + have)
            while (have < length) {
                val n = minOf(chunk, length - have)
                reply = iso.transceive(readBinary(have + 2, n))
                if (!ok(reply) || reply.size < n + 2) { fail("read at $have: sw=${sw(reply)}"); return }
                reply.copyInto(message, have, 0, n)
                have += n
            }
            log("read $length bytes")
            routeRead(NdefMessage(message))
        } catch (e: Exception) {
            fail("read failed: ${e.javaClass.simpleName}: ${e.message ?: "contact lost, hold still"}")
        } finally {
            runCatching { iso.close() }
        }
    }

    private fun selectAid(aid: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid + byteArrayOf(0x00)

    private fun readBinary(offset: Int, length: Int): ByteArray =
        byteArrayOf(0x00, 0xB0.toByte(), ((offset shr 8) and 0x7F).toByte(), (offset and 0xFF).toByte(), length.toByte())

    private fun ok(reply: ByteArray?): Boolean =
        reply != null && reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()

    private fun sw(reply: ByteArray?): String =
        if (reply == null || reply.size < 2) "none" else "%02X%02X".format(reply[reply.size - 2], reply[reply.size - 1])

    // ───────────────────────────── Foreground dispatch / launch intents ─────────────────────────────

    fun enableForegroundDispatch(
        act: Activity,
        pendingIntent: PendingIntent,
        filters: Array<IntentFilter>
    ) {
        if (!isNfcEnabled) return
        adapter?.enableForegroundDispatch(act, pendingIntent, filters, null)
    }

    fun disableForegroundDispatch(act: Activity) {
        runCatching { adapter?.disableForegroundDispatch(act) }
    }

    /** A tag/NDEF intent from the system (for example the app was started by touching the sender's phone). */
    fun handleIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED) return

        val rawMessages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }

        if (!rawMessages.isNullOrEmpty()) {
            val msg = rawMessages[0] as? NdefMessage
            msg?.let { parseAndEmit(it) }
            return
        }

        val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return
        // Off the main thread: reading a tag blocks
        Thread { readIsoDep(tag) }.start()
    }

    fun handleNdefMessage(message: NdefMessage) {
        parseAndEmit(message)
    }

    private fun parseAndEmit(message: NdefMessage) {
        for (record in message.records) {
            val payload = record.payload ?: continue
            val json = runCatching {
                JSONObject(String(payload, Charsets.UTF_8))
            }.getOrNull() ?: continue

            val handshake = runCatching {
                val parsedFileCount = json.optInt("fileCount", 1)
                require(parsedFileCount > 0) { "Invalid fileCount: $parsedFileCount" }
                PeerHandshake(
                    ip        = json.getString("ip"),
                    port      = json.getInt("port"),
                    token     = json.getString("token"),
                    lang      = json.optString("lang", "en"),
                    fileCount = parsedFileCount
                )
            }.getOrNull() ?: continue

            // The phones stay together for a while: do not start the same transfer twice
            val now = System.currentTimeMillis()
            if (handshake.token == lastToken && now - lastTokenAt < 8_000) return
            lastToken = handshake.token
            lastTokenAt = now

            log("got ${handshake.ip}:${handshake.port}, ${handshake.fileCount} file(s)")
            _pendingHandshake.value = handshake
            return
        }
        fail("message had no Flash handshake")
    }
}
