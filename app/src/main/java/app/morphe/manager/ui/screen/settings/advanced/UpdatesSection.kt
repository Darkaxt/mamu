/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.advanced

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import app.morphe.manager.domain.manager.AutoUpdates
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.SettingsViewModel

/**
 * Updates section settings items for the Advanced tab.
 */
@Composable
fun UpdatesSettingsItem(
    settingsViewModel: SettingsViewModel,
    onCheckManagerUpdates: () -> Unit
) {
    val prefs = settingsViewModel.prefs
    val autoUpdates by prefs.autoUpdates.getAsState()
    var showAutoUpdates by remember { mutableStateOf(false) }
    DialogHost(visible = showAutoUpdates) {
        AutoUpdatesDialog(autoUpdates, settingsViewModel::setAutoUpdates) { showAutoUpdates = false }
    }

    SettingsGroup {
        SettingsItem(
            icon = Icons.Outlined.Verified,
            title = stringResource(R.string.extended_stable_updates),
            subtitle = stringResource(R.string.extended_stable_updates_description),
            onClick = onCheckManagerUpdates,
        )
        SettingsDivider()

        SettingsItem(
            onClick = { showAutoUpdates = true },
            icon = Icons.Outlined.Sync,
            title = stringResource(R.string.settings_auto_updates),
            subtitle = stringResource(autoUpdates.titleRes)
        )
    }
}

/** Picks how far the manager and its sources update on their own, see [AutoUpdates]. */
@Composable
private fun DialogHostScope.AutoUpdatesDialog(
    selected: AutoUpdates,
    onSelect: (AutoUpdates) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_auto_updates),
        description = stringResource(R.string.settings_auto_updates_description),
        footer = {
            AppDialogOutlinedButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        ModeChoice(
            modes = AutoUpdates.entries,
            selected = selected,
            onSelect = { mode ->
                onSelect(mode)
                onDismiss()
            },
            titleRes = AutoUpdates::titleRes,
            descriptionRes = AutoUpdates::descriptionRes
        )
    }
}
