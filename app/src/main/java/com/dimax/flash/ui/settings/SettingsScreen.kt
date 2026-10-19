package com.dimax.flash.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dimax.flash.R
import com.dimax.flash.ui.theme.FlashExpressiveTheme
import com.dimax.flash.ui.theme.HandshakeMode
import com.dimax.flash.ui.theme.ThemeMode
import com.dimax.flash.ui.theme.ThemeRepository
import com.dimax.flash.ui.theme.UiStyle
import com.dimax.flash.ui.update.ExpressiveGroupRow
import com.dimax.flash.ui.update.GroupPosition
import com.dimax.flash.ui.update.UpdateController
import com.dimax.flash.ui.update.UpdatesScreen
import com.dimax.flash.ui.workbench.SimulationDialog
import com.dimax.flash.ui.workbench.WorkbenchViewModel
import kotlinx.coroutines.launch

/**
 * Settings as its own screen, the same for the Liquid and the Expressive interface: expressive
 * button groups for the settings first, then the update settings (current version, channel,
 * auto-check, wait between updates, check now) further down.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    themeRepository: ThemeRepository,
    workbenchViewModel: WorkbenchViewModel?,
    updates: UpdateController,
    onOpenPlayground: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeMode by themeRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    var showSimulation by remember { mutableStateOf(false) }

    // Coming back from the "install unknown apps" page: re-read what is allowed now
    LifecycleResumeEffect(Unit) {
        updates.refresh()
        onPauseOrDispose { }
    }

    FlashExpressiveTheme(themeMode) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = null)
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                UpdatesScreen(
                    state = updates.ui,
                    onChannelSelected = updates::setChannel,
                    onAutoCheckToggled = updates::setAutoCheck,
                    onCheckNow = updates::checkNow,
                    onMinIntervalSelected = updates::setMinInterval,
                    onAllowInstallApps = { updates.openInstallPermissionSettings(context) },
                    header = {
                        SettingsGroups(
                            themeRepository = themeRepository,
                            showSimulation = workbenchViewModel != null,
                            onOpenPlayground = onOpenPlayground,
                            onOpenSimulation = { showSimulation = true },
                            onLanguage = { lang ->
                                scope.launch {
                                    themeRepository.setLanguage(lang)
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
                                }
                            }
                        )
                    }
                )
            }
        }
        if (showSimulation && workbenchViewModel != null) {
            SimulationDialog(workbenchViewModel, onDismiss = { showSimulation = false })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.SettingsGroups(
    themeRepository: ThemeRepository,
    showSimulation: Boolean,
    onOpenPlayground: () -> Unit,
    onOpenSimulation: () -> Unit,
    onLanguage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val themeMode by themeRepository.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val handshake by themeRepository.handshakeMode.collectAsStateWithLifecycle(HandshakeMode.AUTO)
    val uiStyle by themeRepository.uiStyle.collectAsStateWithLifecycle(UiStyle.LIQUID)
    val language by themeRepository.language.collectAsStateWithLifecycle("")
    val saveReceived by themeRepository.saveReceived.collectAsStateWithLifecycle(true)

    ChoiceGroup(
        title = stringResource(R.string.settings_theme),
        items = ThemeMode.entries,
        selected = themeMode,
        label = {
            when (it) {
                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
            }
        },
        onSelect = { scope.launch { themeRepository.setThemeMode(it) } }
    )
    ChoiceGroup(
        title = stringResource(R.string.settings_handshake),
        items = HandshakeMode.entries,
        selected = handshake,
        label = {
            when (it) {
                HandshakeMode.AUTO -> stringResource(R.string.handshake_auto)
                HandshakeMode.NFC -> stringResource(R.string.handshake_nfc)
                HandshakeMode.CAMERA -> stringResource(R.string.handshake_camera)
            }
        },
        onSelect = { scope.launch { themeRepository.setHandshakeMode(it) } }
    )
    ChoiceGroup(
        title = stringResource(R.string.settings_ui_style),
        items = UiStyle.entries,
        selected = uiStyle,
        label = {
            when (it) {
                UiStyle.LIQUID -> stringResource(R.string.ui_style_liquid)
                UiStyle.EXPRESSIVE -> stringResource(R.string.ui_style_expressive)
            }
        },
        onSelect = { scope.launch { themeRepository.setUiStyle(it) } }
    )
    ChoiceGroup(
        title = stringResource(R.string.settings_language),
        items = listOf("en", "ru"),
        selected = if (language.isEmpty()) "en" else language,
        label = { if (it == "en") stringResource(R.string.lang_en) else stringResource(R.string.lang_ru) },
        onSelect = onLanguage
    )

    ExpressiveGroupRow(GroupPosition.SINGLE) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Save received files to the phone",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = saveReceived, onCheckedChange = { scope.launch { themeRepository.setSaveReceived(it) } })
        }
    }

    FilledTonalButton(onClick = onOpenPlayground) { Text("Playground (experiments)…") }
    if (showSimulation) {
        FilledTonalButton(onClick = onOpenSimulation) { Text("Simulation…") }
    }
}

/** One settings group: a title and an expressive connected button group (the selected button morphs). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceGroup(
    title: String,
    items: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    ExpressiveGroupRow(GroupPosition.SINGLE) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
            ) {
                items.forEachIndexed { index, item ->
                    val shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ToggleButton(
                        checked = item == selected,
                        onCheckedChange = { onSelect(item) },
                        shapes = shapes,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(label(item), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
