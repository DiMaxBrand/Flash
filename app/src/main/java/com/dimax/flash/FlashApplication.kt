package com.dimax.flash

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import com.dimax.flash.handshake.CameraHandshakeManager
import com.dimax.flash.transfer.FileClient
import com.dimax.flash.transfer.FileServer
import com.dimax.flash.transfer.TransferRepository
import com.dimax.flash.ui.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FlashApplication : Application() {

    // Process-wide scope that outlives any Activity/ViewModel rotation.
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

    // True while the app is leaving: the opaque layer behind every screen (MainActivity) fades out so the
    // exit animation shows what was behind the app instead of black.
    val revealBehindApp = androidx.compose.runtime.mutableStateOf(false)

    // Manual on/off overrides for the experimental feature flags (see FeatureFlags.kt).
    val featureFlags: FeatureFlagStore by lazy { FeatureFlagStore(this) }

    // The in-app updater (check, download, install); shared by Settings and the update sheet.
    val updateController: com.dimax.flash.ui.update.UpdateController by lazy {
        com.dimax.flash.ui.update.UpdateController(this)
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
