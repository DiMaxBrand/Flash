package com.dimax.flash.playground

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Build
import org.json.JSONObject

/**
 * NDEF messages for the playground. A tag is just a list of records; each record names its type
 * (a MIME type or a well-known type), and Android hands a matching message to the app that
 * declared that type. Wi-Fi uses the NFC Forum Wi-Fi Simple Configuration record, and the blink test
 * uses our own type that carries an address and a token.
 */
const val PLAYGROUND_MIME = "application/vnd.flash.playground"
const val WSC_MIME = "application/vnd.wfa.wsc"

data class WifiCredential(val ssid: String, val password: String)

data class PlaygroundTarget(val ip: String, val port: Int, val token: String)

object PlaygroundNdef {

    /** Wi-Fi: one record holding a credential (SSID, WPA2 key), as Android and other readers expect. */
    fun wifiMessage(credential: WifiCredential): NdefMessage {
        val auth = tlv(0x1026, byteArrayOf(0x00, 0x20))          // WPA2-Personal
        val key = tlv(0x1027, credential.password.toByteArray(Charsets.UTF_8))
        val ssid = tlv(0x1045, credential.ssid.toByteArray(Charsets.UTF_8))
        val encryption = tlv(0x100F, byteArrayOf(0x00, 0x08))    // AES
        val payload = tlv(0x100E, auth + key + ssid + encryption)  // 0x100E = credential
        return NdefMessage(arrayOf(NdefRecord.createMime(WSC_MIME, payload)))
    }

    fun parseWifi(message: NdefMessage): WifiCredential? {
        val record = message.records.firstOrNull { it.type.contentEquals(WSC_MIME.toByteArray()) } ?: return null
        val credential = find(record.payload, 0x100E) ?: return null
        val ssid = find(credential, 0x1045)?.toString(Charsets.UTF_8) ?: return null
        val key = find(credential, 0x1027)?.toString(Charsets.UTF_8) ?: ""
        return WifiCredential(ssid, key)
    }

    /** The blink test: where the leader listens and the token that proves the phone is the one touched. */
    fun targetMessage(ip: String, port: Int, token: String): NdefMessage {
        val json = JSONObject().put("ip", ip).put("port", port).put("token", token).toString()
        return NdefMessage(arrayOf(NdefRecord.createMime(PLAYGROUND_MIME, json.toByteArray(Charsets.UTF_8))))
    }

    fun parseTarget(message: NdefMessage): PlaygroundTarget? {
        val record = message.records.firstOrNull { it.type.contentEquals(PLAYGROUND_MIME.toByteArray()) } ?: return null
        val json = runCatching { JSONObject(record.payload.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
        return PlaygroundTarget(json.optString("ip"), json.optInt("port"), json.optString("token"))
    }

    private fun tlv(type: Int, value: ByteArray): ByteArray =
        byteArrayOf((type shr 8).toByte(), type.toByte(), (value.size shr 8).toByte(), value.size.toByte()) + value

    /** Finds one attribute in a list of type-length-value attributes (big-endian, as Wi-Fi Simple Configuration uses). */
    private fun find(data: ByteArray, wanted: Int): ByteArray? {
        var i = 0
        while (i + 4 <= data.size) {
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            val length = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (i + 4 + length > data.size) return null
            if (type == wanted) return data.copyOfRange(i + 4, i + 4 + length)
            i += 4 + length
        }
        return null
    }
}

object WifiSuggestions {
    /**
     * Offers the network to Android. Android decides when to join it, and the user may be asked to
     * confirm. Needs Android 10 or newer.
     */
    fun add(context: Context, credential: WifiCredential): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Needs Android 10 or newer"
        val wifi = context.getSystemService(WifiManager::class.java)
        val builder = WifiNetworkSuggestion.Builder().setSsid(credential.ssid).setIsAppInteractionRequired(true)
        if (credential.password.isNotEmpty()) builder.setWpa2Passphrase(credential.password)
        val status = wifi.addNetworkSuggestions(listOf(builder.build()))
        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> "Offered '${credential.ssid}' to Android"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "'${credential.ssid}' was already offered"
            else -> "Android refused the network (status $status)"
        }
    }
}
