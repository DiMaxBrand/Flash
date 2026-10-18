package com.dimax.flash.util

import android.os.Build

object DeviceDetector {
    /**
     * Check if the device is a Xiaomi/HyperOS device.
     * These devices have custom NFC routing that blocks third-party HCE apps.
     */
    fun isHyperOSDevice(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val device = Build.DEVICE.lowercase()

        return brand.contains("xiaomi") ||
               manufacturer.contains("xiaomi") ||
               device.contains("xiaomi") ||
               Build.DISPLAY.lowercase().contains("hyperos")
    }

    /**
     * Get a human-readable device identifier.
     */
    fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
    }
}
