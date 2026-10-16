package com.example.flash

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import com.example.flash.handshake.CameraHandshakeManager
import com.example.flash.transfer.FileClient
import com.example.flash.transfer.FileServer
import com.example.flash.transfer.TransferRepository
import com.example.flash.ui.theme.ThemeRepository
import com.example.flash.util.DeviceDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FlashApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by preferencesDataStore(name = "flash_prefs")
    val themeRepository: ThemeRepository by lazy {
        ThemeRepository(dataStore)
    }
    val transferRepository: TransferRepository by lazy {
        TransferRepository(
            server = FileServer(applicationScope),
            client = FileClient(),
            scope  = applicationScope
        )
    }

    // Always created; whether it is used is decided by the HandshakeMode setting.
    val cameraHandshakeManager: CameraHandshakeManager by lazy {
        CameraHandshakeManager(this)
    }

    companion object {
        lateinit var instance: FlashApplication
            private set
    }
    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}