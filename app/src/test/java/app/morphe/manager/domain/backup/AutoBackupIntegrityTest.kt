package app.morphe.manager.domain.backup

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.morphe.manager.data.room.AppDatabase
import app.morphe.manager.di.*
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.ui.viewmodel.ManagerSettingsExportFile
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import kotlin.test.*

/**
 * Real exporter, database and file provider; no device/network access or signing-key export.
 * Keep test names short: Robolectric puts the method name in its data directory, and on Windows a
 * longer name pushes the database journal past MAX_PATH (SQLITE_CANTOPEN in setJournalMode).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AutoBackupIntegrityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var directory: File
    private lateinit var koin: org.koin.core.Koin
    private val backup get() = koin.get<AutoBackup>()

    @Before fun setup() {
        directory = File(app.cacheDir, "backup-integrity").apply { mkdirs() }
        koin = startKoin { androidContext(app); modules(httpModule, preferencesModule,
            repositoryModule, serviceModule, managerModule, databaseModule) }.koin
        runBlocking {
            val prefs = koin.get<PreferencesManager>()
            prefs.autoBackupFolder.update(Uri.fromFile(directory).toString())
            prefs.autoBackupSigningKey.update(false)
        }
    }

    @After fun close() {
        val repo = koin.get<PatchBundleRepository>()
        val field = PatchBundleRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        runBlocking { (field.get(repo) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin() }
        koin.get<HttpClient>().close()
        koin.get<AppDatabase>().close()
        stopKoin()
        directory.deleteRecursively()
    }

    private fun write(name: String, body: (OutputStream) -> Unit) {
        val method = AutoBackup::class.java.getDeclaredMethod("writeNew", DocumentFile::class.java,
            String::class.java, String::class.java, kotlin.jvm.functions.Function1::class.java)
            .apply { isAccessible = true }
        try { method.invoke(backup, DocumentFile.fromFile(directory), "application/json", name, body) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    @Test fun an_existing_copy_is_never_truncated_or_deleted_on_a_colliding_write() {
        val saved = File(directory, "saved.json").apply { writeText("verified existing copy") }
        assertFailsWith<IOException> { write(saved.name) { it.write("replacement".toByteArray()); error("interrupted") } }
        assertEquals("verified existing copy", saved.readText())
    }

    @Test fun a_failed_new_write_removes_only_its_own_partial_copy() {
        val saved = File(directory, "saved.json").apply { writeText("verified existing copy") }
        assertFailsWith<IOException> { write("new.json") { it.write(byteArrayOf(1)); throw IOException("write failed") } }
        assertFalse(File(directory, "new.json").exists())
        assertEquals("verified existing copy", saved.readText())
    }

    @Test fun concurrent_backups_keep_independent_copies() = runBlocking {
        val outcomes = coroutineScope { List(8) { async(Dispatchers.Default) { backup.backUpNow() } }.awaitAll() }
        assertTrue(outcomes.all { it }, ShadowLog.getLogs().filter { it.throwable != null }
            .joinToString("\n") { "${it.tag}: ${it.msg}\n${it.throwable.stackTraceToString()}" })
        val files = directory.listFiles()!!.filter { it.extension == "json" }
        assertEquals(AutoBackup.KEPT_COPIES, files.size)
        files.forEach { file ->
            val decoded = SettingsExporter.settingsJson.decodeFromString<ManagerSettingsExportFile>(file.readText())
            assertNotNull(decoded.settings)
        }
        assertFalse(directory.listFiles()!!.any { it.extension == "p12" })
    }
}
