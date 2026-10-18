package com.example.flash

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.example.flash.ui.theme.ThemeMode
import com.example.flash.navigation.NavGraph
import com.example.flash.navigation.Screen
import com.example.flash.nfc.NfcManager
import com.example.flash.ui.theme.FlashTheme
import com.example.flash.ui.update.UpdateSheetHost

class MainActivity : ComponentActivity() {

    private lateinit var nfcManager: NfcManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val app = application as FlashApplication
        nfcManager = NfcManager(this)
        nfcManager.initialize(this)

        // Handle NFC intent that launched this Activity
        intent?.let { nfcManager.handleIntent(it) }

        setContent {
            val themeMode by app.themeRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val onboardingShown by app.themeRepository.onboardingShown.collectAsState(initial = false)
            val navController = rememberNavController()

            FlashTheme(themeMode = themeMode) {
                NavGraph(
                    navController = navController,
                    startDestination = if (onboardingShown) Screen.Main.route else Screen.Onboarding.route,
                    themeRepository = app.themeRepository,
                    transferRepository = app.transferRepository,
                    nfcManager = nfcManager,
                    cameraHandshakeManager = app.cameraHandshakeManager,
                    updateController = app.updateController
                )
                // The update bottom sheet appears over whichever screen is open
                UpdateSheetHost(app.updateController, themeMode)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // NFC modes are managed dynamically based on UI state (see WorkbenchScreen).
        // Look for an update when the app opens, and show one that is already pending.
        val updates = (application as FlashApplication).updateController
        updates.showSheetIfPending()
        updates.checkOnLaunchIfEligible()
    }

    override fun onPause() {
        super.onPause()
        // Stop NFC handling when app is backgrounded (prevent interference with other apps/cards)
        nfcManager.disableForegroundDispatch(this)
        nfcManager.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Extra safety: ensure NFC is fully disabled when app closes
        nfcManager.disableForegroundDispatch(this)
        nfcManager.disableReaderMode(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        nfcManager.handleIntent(intent)
    }
}
