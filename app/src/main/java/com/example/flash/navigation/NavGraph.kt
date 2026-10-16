package com.example.flash.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.flash.handshake.CameraHandshakeManager
import com.example.flash.nfc.NfcManager
import com.example.flash.transfer.TransferRepository
import com.example.flash.ui.onboarding.OnboardingScreen
import com.example.flash.ui.settings.SettingsScreen
import com.example.flash.ui.theme.ThemeRepository
import com.example.flash.ui.workbench.WorkbenchScreen
import com.example.flash.handshake.CameraHandshakeManager

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Main       : Screen("main")
    object Settings   : Screen("settings")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String,
    themeRepository: ThemeRepository,
    transferRepository: TransferRepository,
    nfcManager: NfcManager,
    cameraHandshakeManager: CameraHandshakeManager
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                themeRepository = themeRepository,
                onComplete = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Main.route) {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.flash.FlashApplication
            WorkbenchScreen(
                transferRepository = transferRepository,
                nfcManager = nfcManager,
                cameraHandshakeManager = cameraHandshakeManager,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                themeRepository = themeRepository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}