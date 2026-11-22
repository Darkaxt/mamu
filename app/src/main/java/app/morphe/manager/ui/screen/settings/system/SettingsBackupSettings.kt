/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.core.net.toUri
import app.morphe.manager.R
import app.morphe.manager.domain.backup.AutoBackup
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.manager.SettingsSection
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.ImportExportViewModel
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import app.morphe.manager.util.JSON_MIMETYPE
import app.morphe.manager.util.TEXT_MIMETYPE
import app.morphe.manager.util.getRelativeTimeString
import app.morphe.manager.util.pickedFolder
import app.morphe.manager.util.rememberAdaptiveFilePicker
import app.morphe.manager.util.rememberDocumentFolderPicker
import app.morphe.manager.util.rememberDocumentSaver
import app.morphe.manager.util.toFilePath
import app.morphe.manager.worker.ScheduleInterval
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/** When the last automatic copy was made, or what the page is for before the first. */
@Composable
private fun lastBackupSummary(lastBackupAt: Long): String =
    if (lastBackupAt > 0) {
        stringResource(R.string.settings_system_auto_backup_last, getRelativeTimeString(lastBackupAt))
    } else {
        stringResource(R.string.settings_system_auto_backup_description)
    }

/**
 * Automatic backups: the switch, which asks for a folder when turned on, then the folder, how
 * often a copy is made, whether the signing key goes along and a copy made on the spot.
 */
@Composable
fun AutoBackupItems(
    prefs: PreferencesManager,
    importExportViewModel: ImportExportViewModel
) {
    val folder by prefs.autoBackupFolder.getAsState()
    val interval by prefs.autoBackupInterval.getAsState()
    val includesSigningKey by prefs.autoBackupSigningKey.getAsState()
    val lastBackupAt by prefs.lastAutoBackupAt.getAsState()
    val failed by prefs.autoBackupFailed.getAsState()
    val enabled = folder.isNotEmpty()
    val pickFolder = rememberDocumentFolderPicker(
        suggestedFolderName = stringResource(R.string.settings_system_auto_backup_folder_name),
        onFolderPicked = importExportViewModel::enableAutoBackup
    )
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showSigningKeyWarning by remember { mutableStateOf(false) }

    DialogHost(visible = showIntervalDialog) {
        BackupIntervalDialog(
            selected = interval,
            onSelect = importExportViewModel::setAutoBackupInterval,
            onDismiss = { showIntervalDialog = false }
        )
    }

    // The key signs updates for every patched app, so a copy of it leaving the app is confirmed first
    DialogHost(visible = showSigningKeyWarning) {
        ConfirmDialog(
            title = stringResource(R.string.settings_system_auto_backup_signing_key_warning_title),
            message = stringResource(R.string.settings_system_auto_backup_signing_key_warning_message),
            primaryText = stringResource(R.string.enable),
            isPrimaryDestructive = false,
            onDismiss = { showSigningKeyWarning = false },
            onConfirm = {
                showSigningKeyWarning = false
                importExportViewModel.setAutoBackupSigningKey(true)
            }
        )
    }

    SettingsGroup {
        SettingsSwitchItem(
            checked = enabled,
            onToggle = { if (enabled) importExportViewModel.disableAutoBackup() else pickFolder() },
            icon = MaterialSymbols.Rounded.Backup,
            title = stringResource(R.string.settings_system_auto_backup_switch),
            subtitle = lastBackupSummary(lastBackupAt.takeIf { enabled } ?: 0L)
        )

        FoldingVisibility(visible = enabled) {
            Column {
                SettingsItem(
                    onClick = pickFolder,
                    leadingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Folder_open) },
                    title = stringResource(R.string.settings_system_auto_backup_folder),
                    subtitle = rememberFolderLabel(folder)
                )
                SettingsItem(
                    onClick = { showIntervalDialog = true },
                    leadingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Schedule) },
                    title = stringResource(R.string.settings_system_auto_backup_interval),
                    subtitle = stringResource(interval.labelResId)
                )
                SettingsSwitchItem(
                    checked = includesSigningKey,
                    onToggle = {
                        if (includesSigningKey) importExportViewModel.setAutoBackupSigningKey(false)
                        else showSigningKeyWarning = true
                    },
                    icon = MaterialSymbols.Rounded.Key,
                    title = stringResource(R.string.settings_system_auto_backup_signing_key),
                    subtitle = stringResource(R.string.settings_system_auto_backup_signing_key_description)
                )
                SettingsItem(
                    onClick = importExportViewModel::backUpNow,
                    leadingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Save) },
                    title = stringResource(R.string.settings_system_auto_backup_now)
                )
            }
        }
    }

    // When copies are made, which nothing above says, or that the last one could not be
    FoldingVisibility(visible = enabled) {
        SettingsFooter(
            text = if (failed) {
                stringResource(R.string.settings_system_auto_backup_failed)
            } else {
                stringResource(R.string.settings_system_auto_backup_footer, AutoBackup.KEPT_COPIES.toString())
            },
            tone = if (failed) SemanticTone.Warning else SemanticTone.Neutral
        )
    }
}

/** Picks the least time between two automatic copies. */
@Composable
private fun DialogHostScope.BackupIntervalDialog(
    selected: ScheduleInterval,
    onSelect: (ScheduleInterval) -> Unit,
    onDismiss: () -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_system_auto_backup_interval),
        description = stringResource(R.string.settings_system_auto_backup_interval_description),
        footer = {
            AppDialogOutlinedButton(
                text = stringResource(android.R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        ModeChoice(
            modes = ScheduleInterval.entries,
            selected = selected,
            onSelect = { interval ->
                onSelect(interval)
                onDismiss()
            },
            titleRes = ScheduleInterval::labelResId
        )
    }
}

/** Where [folder] is: its path where it maps to one, else the name its provider gives it. */
@Composable
private fun rememberFolderLabel(folder: String): String {
    val context = LocalContext.current
    // The provider is asked off the main thread, as it can be one that answers slowly
    return produceState(initialValue = "", folder) {
        if (folder.isEmpty()) return@produceState
        value = withContext(Dispatchers.IO) {
            val uri = folder.toUri()
            uri.toFilePath().takeIf { it.startsWith('/') } ?: context.pickedFolder(uri)?.name ?: folder
        }
    }.value
}
