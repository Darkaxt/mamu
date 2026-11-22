package app.morphe.manager.ui.viewmodel

import app.morphe.manager.domain.manager.PreferencesManager.SettingsSnapshot
import app.morphe.manager.domain.manager.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunitySettingsBackupTest {
    @Test fun `Byparr config is device specific and legacy exports do not replace it`() {
        val snapshot = SettingsSnapshot(nativeByparrEndpoint = "https://phone.example/v1", installerPrimary = "internal")
        val recovered = ImportExportViewModel.settingsJson.decodeFromString<SettingsSnapshot>(
            ImportExportViewModel.settingsJson.encodeToString(snapshot))
        assertEquals(snapshot.nativeByparrEndpoint, recovered.restrictedTo(setOf(SettingsSection.PATCHING)).nativeByparrEndpoint)
        assertNull(recovered.restrictedTo(setOf(SettingsSection.SOURCES)).nativeByparrEndpoint)
        assertNull(ImportExportViewModel.settingsJson.decodeFromString<SettingsSnapshot>("{}").nativeByparrEndpoint)
    }

    @Test fun `source exclusions survive recovery export and stay in the Sources section`() {
        val snapshot = SettingsSnapshot(communityCatalogueExclusions = setOf("github=owner/deleted"), installerPrimary = "internal")
        val recovered = ImportExportViewModel.settingsJson.decodeFromString<SettingsSnapshot>(
            ImportExportViewModel.settingsJson.encodeToString(snapshot))
        assertEquals(snapshot, recovered)
        val sourcesOnly = recovered.restrictedTo(setOf(SettingsSection.SOURCES))
        assertEquals(snapshot.communityCatalogueExclusions, sourcesOnly.communityCatalogueExclusions)
        assertNull(sourcesOnly.installerPrimary)
        assertNull(recovered.restrictedTo(setOf(SettingsSection.PATCHING)).communityCatalogueExclusions)
        assertNull(ImportExportViewModel.settingsJson.decodeFromString<SettingsSnapshot>("{}").communityCatalogueExclusions)
    }
}
