/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import app.morphe.manager.ui.screen.home.ByparrSettingsDialog
import app.morphe.manager.R
import app.morphe.manager.ui.screen.settings.system.*
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.ImportExportViewModel
import app.morphe.manager.ui.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

/**
 * System tab content.
 */
@Composable
fun SystemTabContent(
    settingsViewModel: SettingsViewModel,
    onShowInstallerDialog: () -> Unit,
    importExportViewModel: ImportExportViewModel,
    onImportKeystore: () -> Unit,
    onExportKeystore: () -> Unit,
    onImportSettings: () -> Unit,
    onExportSettings: () -> Unit,
    onExportDebugLogs: () -> Unit,
    onAboutClick: () -> Unit,
    onChangelogClick: () -> Unit,
    onStartTour: (() -> Unit)? = null,
    onOpenMiniGames: () -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    onInstallerSectionPositioned: ((Rect) -> Unit)? = null,
    onInstallerScrollTarget: ((Int) -> Unit)? = null,
    onFilePickerPositioned: ((Rect) -> Unit)? = null,
    onFilePickerScrollTarget: ((Int) -> Unit)? = null
) {
    val showByparrDialog = remember { mutableStateOf(false) }
    if (showByparrDialog.value) ByparrSettingsDialog(settingsViewModel.prefs) { showByparrDialog.value = false }
    val showNotificationsDialog = remember { mutableStateOf(false) }


    if (showNotificationsDialog.value) {
        NotificationsDialog(
            settingsViewModel = settingsViewModel,
            onDismiss = { showNotificationsDialog.value = false }
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScrollFade(scrollState)
            .verticalScroll(scrollState)
            .padding(settingsTabPadding()),
        verticalArrangement = Arrangement.spacedBy(Defaults.ContentPadding)
    ) {
        // Installers
        SectionTitle(
            text = stringResource(R.string.installer),
            icon = Icons.Outlined.InstallMobile
        )

        SettingsGroup(
            modifier = if (onInstallerScrollTarget != null) Modifier.onGloballyPositioned { coords ->
                onInstallerScrollTarget(coords.boundsInParent().top.roundToInt())
            } else Modifier
        ) {
            InstallerSection(
                settingsViewModel = settingsViewModel,
                onShowInstallerDialog = onShowInstallerDialog,
                onInstallerItemPositioned = onInstallerSectionPositioned
            )
        }

        SectionTitle(
            text = stringResource(R.string.extended_downloader_section),
            icon = Icons.Outlined.Download
        )
        SettingsGroup {
            SettingsItem(title = stringResource(R.string.extended_byparr_settings),
                icon = Icons.Outlined.Dns,
                onClick = { showByparrDialog.value = true })
        }

        // Background execution & notifications
        BackgroundSection(onNotificationsClick = { showNotificationsDialog.value = true })

        // Import & Export: the signing key is the one thing a lost install cannot recreate, so
        // backing it up is offered in simple mode too
        ImportExportSection(
            importExportViewModel = importExportViewModel,
            onImportKeystore = onImportKeystore,
            onExportKeystore = onExportKeystore,
            onImportSettings = onImportSettings,
            onExportSettings = onExportSettings
        )

        SectionTitle(
            text = stringResource(R.string.settings_system_auto_backup),
            icon = Icons.Outlined.History
        )
        AutoBackupItems(settingsViewModel.prefs, importExportViewModel)

        // Files & Storage
        FilesAndStorageSection(
            settingsViewModel = settingsViewModel,
            importExportViewModel = importExportViewModel,
            modifier = if (onFilePickerScrollTarget != null) Modifier.onGloballyPositioned { coords ->
                onFilePickerScrollTarget(coords.boundsInParent().top.roundToInt())
            } else Modifier,
            onFilePickerPositioned = onFilePickerPositioned
        )

        // About
        SectionTitle(
            text = stringResource(R.string.settings_system_about),
            icon = Icons.Outlined.Info
        )

        SettingsGroup {
            AboutSection(
                onAboutClick = onAboutClick,
                onChangelogClick = onChangelogClick,
                onExportDebugLogs = onExportDebugLogs,
                onStartTour = onStartTour,
                onOpenMiniGames = onOpenMiniGames
            )
        }
    }
}
