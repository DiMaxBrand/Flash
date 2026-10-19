package com.dimax.flash.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.dimax.flash.playground.NfcLab

/**
 * Host Card Emulation: the sending phone answers NFC like an NFC Forum Type 4 tag whose single NDEF
 * file holds the handshake (ip, port, token). It answers on two application IDs:
 *  - our own [CUSTOM_AID], which the Flash reader selects directly (the reliable phone-to-phone path), and
 *  - the standard NDEF tag application [NDEF_AID], so Android's own tag reading (and an app that is
 *    not open yet) can see the message as an ordinary NDEF tag too.
 */
class HandshakeHceService : HostApduService() {

    companion object {
        val CUSTOM_AID = byteArrayOf(0xF0.toByte(), 0x39, 0x4D, 0x4B, 0x03)
        val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)

        private const val FILE_CC = 0xE103
        private const val FILE_NDEF = 0xE104

        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,             // CCLEN
            0x20,                   // mapping version 2.0
            0x00, 0x3B,             // MLe: largest response a reader may ask for (59)
            0x00, 0x34,             // MLc: largest command payload (52)
            0x04, 0x06,             // NDEF file control TLV
            0xE1.toByte(), 0x04,    // NDEF file id
            0x04, 0x00,             // max NDEF file size: 1024
            0x00,                   // read access: open
            0xFF.toByte()           // write access: none
        )

        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val WRONG_PARAMS = byteArrayOf(0x6A, 0x86.toByte())
        private val UNKNOWN_INS = byteArrayOf(0x6D, 0x00)

        /** The NDEF message to serve (null = nothing to share right now). */
        @Volatile
        var ndefMessageBytes: ByteArray? = null

        /** Receives short log lines ("reader selected our app", ...) for the on-screen NFC status. */
        @Volatile
        var listener: ((String) -> Unit)? = null
    }

    private var selectedFile = 0
    private var reads = 0

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) return UNKNOWN_INS
        if (NfcLab.cardActive) {
            NfcLab.noteCommand(commandApdu)
            if (commandApdu[0] == NfcLab.CLA && commandApdu.size >= 5) return NfcLab.onCardApdu(commandApdu)
        }
        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF

        return when (ins) {
            0xA4 -> select(commandApdu, p1)
            0xB0 -> readBinary(commandApdu)
            else -> UNKNOWN_INS
        }
    }

    private fun select(apdu: ByteArray, p1: Int): ByteArray {
        if (apdu.size < 5) return WRONG_PARAMS
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return WRONG_PARAMS
        val data = apdu.copyOfRange(5, 5 + lc)

        if (p1 == 0x04) {                       // by application name (AID)
            return if (data.contentEquals(CUSTOM_AID) || data.contentEquals(NDEF_AID)) {
                selectedFile = 0
                reads = 0
                listener?.invoke("card: reader selected the Flash app")
                OK
            } else NOT_FOUND
        }
        if (p1 == 0x00 && lc == 2) {            // by file id
            val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            return when (id) {
                FILE_CC -> { selectedFile = 1; OK }
                FILE_NDEF -> { selectedFile = 2; OK }
                else -> NOT_FOUND
            }
        }
        return WRONG_PARAMS
    }

    private fun readBinary(apdu: ByteArray): ByteArray {
        if (apdu.size < 5) return WRONG_PARAMS
        val offset = ((apdu[2].toInt() and 0x7F) shl 8) or (apdu[3].toInt() and 0xFF)
        val le = (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it }

        val data: ByteArray = when (selectedFile) {
            1 -> CC_FILE
            2 -> {
                val msg = ndefMessageBytes ?: return NOT_FOUND
                // Type 4 NDEF file: 2-byte length, then the NDEF message
                ByteArray(msg.size + 2).also {
                    it[0] = ((msg.size shr 8) and 0xFF).toByte()
                    it[1] = (msg.size and 0xFF).toByte()
                    msg.copyInto(it, 2)
                }
            }
            else -> return NOT_FOUND
        }
        if (offset > data.size) return WRONG_PARAMS
        val n = minOf(le, data.size - offset)
        if (selectedFile == 2 && offset + n >= data.size) listener?.invoke("card: handshake read by the other phone (${data.size - 2} bytes)")
        val out = ByteArray(n + 2)
        data.copyInto(out, 0, offset, offset + n)
        out[n] = 0x90.toByte()
        out[n + 1] = 0x00
        return out
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = 0
        NfcLab.cardDropped(reason)
        listener?.invoke("card: link ended (${if (reason == DEACTIVATION_LINK_LOSS) "lost contact" else "reader moved on"})")
    }
}
