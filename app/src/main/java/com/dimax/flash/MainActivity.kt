package com.dimax.flash

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.dimax.flash.ui.theme.ThemeMode
import com.dimax.flash.navigation.NavGraph
import com.dimax.flash.navigation.Screen
import com.dimax.flash.nfc.NfcManager
import com.dimax.flash.ui.theme.FlashTheme
import com.dimax.flash.ui.update.UpdateSheetHost

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
            // Read ONCE, and nothing but the empty background is shown until it is known. It used to start as
            // "not shown" and flip to "shown" a moment later, which flashed the welcome for a second at every
            // start; and the start screen must not change again when the welcome is completed.
            val welcomeShown by produceState<Boolean?>(initialValue = null) {
                value = app.themeRepository.welcomeShown.first()
            }
            val navController = rememberNavController()

            FlashTheme(themeMode = themeMode) {
                // The window is translucent (see the theme), so something opaque must sit behind the screens:
                // navigation crossfades would otherwise show what is behind the app. It fades out only while
                // the app is leaving, so the exit animation reveals the home screen or the app below.
                val revealBehind by app.revealBehindApp
                val rootAlpha by animateFloatAsState(
                    targetValue = if (revealBehind) 0f else 1f,
                    animationSpec = tween(400, easing = FastOutSlowInEasing),
                    label = "root_background"
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = rootAlpha))
                ) {
                    welcomeShown?.let { shown ->
                        NavGraph(
                            navController = navController,
                            startDestination = if (shown) Screen.Main.route else Screen.Onboarding.route,
                            themeRepository = app.themeRepository,
                            transferRepository = app.transferRepository,
                            nfcManager = nfcManager,
                            cameraHandshakeManager = app.cameraHandshakeManager,
                            updateController = app.updateController
                        )
                    }
                }
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
