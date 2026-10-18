package com.example.flash.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.flash.ui.update.UpdateController
import com.example.flash.ui.workbench.WorkbenchViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.flash.handshake.CameraHandshakeManager
import com.example.flash.nfc.NfcManager
import com.example.flash.transfer.TransferRepository
import com.example.flash.ui.onboarding.OnboardingScreen
import com.example.flash.ui.settings.SettingsScreen
import com.example.flash.ui.theme.ThemeRepository
import com.example.flash.ui.workbench.WorkbenchScreen

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
    cameraHandshakeManager: CameraHandshakeManager,
    updateController: UpdateController
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
            WorkbenchScreen(
                transferRepository = transferRepository,
                nfcManager = nfcManager,
                cameraHandshakeManager = cameraHandshakeManager,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.Settings.route) { entry ->
            // The workbench's ViewModel (owned by the Main entry) runs the Simulation from Settings
            val mainEntry = remember(entry) { runCatching { navController.getBackStackEntry(Screen.Main.route) }.getOrNull() }
            val workbenchViewModel: WorkbenchViewModel? = mainEntry?.let {
                viewModel(
                    viewModelStoreOwner = it,
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            WorkbenchViewModel(transferRepository, nfcManager, cameraHandshakeManager) as T
                    }
                )
            }
            SettingsScreen(
                themeRepository = themeRepository,
                workbenchViewModel = workbenchViewModel,
                updates = updateController,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
