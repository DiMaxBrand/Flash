package com.dimax.flash.ui.hints

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dimax.flash.R

/** What the hint card explains. Hints are an opt-in setting for people who are not comfortable with phones. */
enum class HintKind {
    /** NFC mode, nothing selected: to receive just touch, to send first choose files. */
    NFC_IDLE,

    /** NFC mode, files chosen: now touch the backs together. */
    NFC_SEND,

    /** Camera mode, the other phone has files ready: scan its code. Only the receiver sees it. */
    CAMERA_SCAN
}

/**
 * A card in the middle of the screen with a drawing and one or two short sentences. Tapping it hides it until
 * the situation changes. Draws nothing for null, and does not catch touches outside the card.
 */
@Composable
fun HintsOverlay(kind: HintKind?, onDismiss: (HintKind) -> Unit) {
    if (kind == null) return
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 12.dp,
            modifier = Modifier.clickable { onDismiss(kind) }
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (kind) {
                    HintKind.NFC_IDLE, HintKind.NFC_SEND -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TapPhonesIllustration()
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            NfcChipIllustration()
                            Text(
                                stringResource(R.string.hint_nfc_chip_caption),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    HintKind.CAMERA_SCAN -> CameraScanIllustration()
                }
                Text(
                    text = stringResource(
                        when (kind) {
                            HintKind.NFC_IDLE -> R.string.hint_nfc_idle
                            HintKind.NFC_SEND -> R.string.hint_nfc_send
                            HintKind.CAMERA_SCAN -> R.string.hint_camera_scan
                        }
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.hint_tap_to_hide),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
