package app.morphe.manager.domain.manager

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class InstallModesMigrationTest {
    @Test fun `legacy install choices migrate without changing independent source or retention settings`() = runBlocking {
        data class Case(val token: String, val prompt: Boolean, val automatic: Boolean,
                        val mode: RootPatchMode, val after: AfterPatching, val installer: String)
        val cases = listOf(
            Case(InstallerPreferenceTokens.LEGACY_AUTO_SAVED, false, false, RootPatchMode.MOUNT, AfterPatching.MANUAL, InstallerPreferenceTokens.INTERNAL),
            Case(InstallerPreferenceTokens.SHIZUKU, true, false, RootPatchMode.ASK, AfterPatching.ASK_INSTALLER, InstallerPreferenceTokens.SHIZUKU),
            Case(InstallerPreferenceTokens.SHIZUKU, false, true, RootPatchMode.STANDARD, AfterPatching.INSTALL, InstallerPreferenceTokens.SHIZUKU)
        )
        cases.forEachIndexed { index, case ->
            val app = RuntimeEnvironment.getApplication()
            val directory = File(app.cacheDir, "install-migration-$index")
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = directory
            }
            val file = File(directory, "datastore/settings.preferences_pb")
            val writerJob = SupervisorJob()
            val writer = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + writerJob)) { file }
            writer.edit {
                it[longPreferencesKey("manager_installation_time")] = 1L
                it[stringPreferencesKey("installer_primary")] = case.token
                it[booleanPreferencesKey("prompt_installer_on_install")] = case.prompt
                it[booleanPreferencesKey("auto_install_with_shizuku")] = case.automatic
                it[booleanPreferencesKey("save_original_apks")] = true
                it[booleanPreferencesKey("auto_update")] = true
            }
            writerJob.cancelAndJoin()
            val prefs = PreferencesManager(context)
            assertEquals(case.mode, prefs.rootPatchMode.get())
            assertEquals(case.after, prefs.afterPatching.get())
            assertEquals(case.installer, prefs.installerPrimary.get())
            assertTrue(prefs.saveOriginalApks.get())
            // Independent choices remain editable after migration.
            prefs.rootPatchMode.update(RootPatchMode.STANDARD)
            assertEquals(RootPatchMode.STANDARD, prefs.rootPatchMode.get())
            assertEquals(case.after, prefs.afterPatching.get())
        }
    }
}
