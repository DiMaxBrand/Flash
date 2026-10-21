package com.dimax.flash.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Opens the phone's own "Portable hotspot" page. Plain `ACTION_WIRELESS_SETTINGS` only reaches "More connectivity
 * options" on Xiaomi (MIUI/HyperOS: airplane mode, private DNS, printing, ...), which has no hotspot switch, so
 * those phones get their own pages first. Every intent is tried and the next one is used if the phone refuses it
 * (the pages are not public API and move between versions).
 */
object HotspotSettings {
    fun open(context: Context) {
        val intents = buildList {
            if (isXiaomiFamily()) {
                add(component("com.android.settings", "com.android.settings.TetherSettings"))
                add(component("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"))
                add(component("com.android.settings", "com.android.settings.wifi.tether.WifiTetherSettings"))
            }
            add(Intent("android.settings.TETHER_SETTINGS"))
            add(component("com.android.settings", "com.android.settings.TetherSettings"))
            add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // not there on this phone, try the next one
            }
        }
    }

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun isXiaomiFamily(): Boolean {
        val s = (android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER).lowercase()
        return DeviceDetector.isHyperOSDevice() || s.contains("redmi") || s.contains("poco") || s.contains("xiaomi")
    }
}
