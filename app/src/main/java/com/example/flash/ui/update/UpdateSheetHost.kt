package com.example.flash.ui.update

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.flash.ui.theme.FlashExpressiveTheme
import com.example.flash.ui.theme.ThemeMode

/**
 * The "Flash update" bottom sheet, the same for the Liquid and the Expressive interface and for every
 * screen: it shows the release title and notes, then Download, progress (tap to stop) and Install.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheetHost(controller: UpdateController, themeMode: ThemeMode) {
    if (!controller.sheetVisible) return
    LaunchedEffect(Unit) { controller.onSheetShown() }
    FlashExpressiveTheme(themeMode) {
        ModalBottomSheet(
            onDismissRequest = { controller.dismissSheet() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            UpdateSheetContent(
                state = controller.ui,
                onDownload = controller::startUserDownload,
                onStop = controller::cancelDownload,
                onContinue = controller::hideCancelConfirm,
                onInstall = controller::showInstallCard,
                onConfirmInstall = controller::confirmInstall,
                onDownloadCircleTapped = controller::showCancelConfirm,
            )
        }
    }
}
