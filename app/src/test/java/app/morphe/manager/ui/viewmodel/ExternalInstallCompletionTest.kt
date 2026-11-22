package app.morphe.manager.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Looper
import app.morphe.manager.data.room.AppDatabase
import app.morphe.manager.data.room.apps.installed.InstalledApp
import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.data.room.apps.installed.SelectionPayload
import app.morphe.manager.di.*
import app.morphe.manager.domain.installer.InstallerManager
import app.morphe.manager.util.AppCoroutineScope
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

/** Real package receiver, pending owner, persistence callback and Room; no installer is launched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ExternalInstallCompletionTest {
    private fun field(name: String) = InstallViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(vm: InstallViewModel, name: String, value: Any?) = field(name).set(vm, value)
    private fun pump(job: Job?) {
        while (job != null && !job.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun scenario(external: Boolean, updated: Boolean = true) {
        val app = RuntimeEnvironment.getApplication()
        val koin = startKoin { androidContext(app); modules(httpModule, preferencesModule,
            repositoryModule, serviceModule, managerModule, databaseModule) }.koin
        val scope = koin.get<AppCoroutineScope>()
        val vm = InstallViewModel()
        val pkg = "fixture.patched.clone"
        val expected = app.cacheDir.resolve("morphe.apk").also { it.writeText("Morphe signed APK") }
        val installed = app.cacheDir.resolve("installerx.apk").also { it.writeText("APK repackaged and signed by InstallerX") }
        val payload = SelectionPayload(listOf(SelectionPayload.BundleSelection(42, listOf("Patch A", "Patch B"),
            options = mapOf("Patch A" to mapOf("value" to "kept")), bundleName = "Selected source", bundleVersion = "1.47.0")))
        val db = koin.get<AppDatabase>()
        var calls = 0
        val persisted = CompletableDeferred<Unit>()
        val callback: suspend (String, InstallType) -> Boolean = { name, type ->
            calls++
            db.installedAppDao().upsertApp(InstalledApp(name, "fixture.original", "9.5.0", type, payload,
                patchedAt = 3000L, isClone = true))
            persisted.complete(Unit)
            true
        }
        try {
            shadowOf(app.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg; versionName = "9.5.0"; longVersionCode = if (updated) 2 else 1
                lastUpdateTime = if (updated) 3000L else 1000L
                applicationInfo = ApplicationInfo().apply { packageName = pkg; sourceDir = installed.path }
            })
            set(vm, "pendingInstallFile", expected)
            set(vm, "pendingPersistCallback", callback)
            set(vm, "externalInstallBaseline", 1L to 1000L)
            set(vm, "externalInstallStartTime", 2000L)
            if (external) {
                set(vm, "pendingExternalInstall", InstallerManager.InstallPlan.External(
                    InstallerManager.InstallTarget.PATCHER, Intent(), expected,
                    Uri.parse("content://fixture/shared.apk"), pkg, "InstallerX Resigned", null,
                    InstallerManager.Token.Component(ComponentName("fixture.installer", "fixture.Installer"))))
            } else {
                set(vm, "pendingIntentFallbackPackage", pkg)
                set(vm, "pendingIntentFallbackInstallType", InstallType.PLAY_STORE)
            }
            val receiver = field("packageReceiver").get(vm) as BroadcastReceiver
            fun event(name: String) = receiver.onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:$name")))
            event("unrelated.app")
            assertNull(field("packageResultJob").get(vm))
            assertEquals(0, calls)
            event(pkg)
            pump(field("packageResultJob").get(vm) as? Job)
            if (!external && !updated) {
                assertEquals(0, calls)
                assertFalse(vm.installState is InstallViewModel.InstallState.Installed)
                return
            }
            assertEquals(InstallViewModel.InstallState.Installed(pkg), vm.installState)
            // Persistence can finish after the visual transition; join its actual owned jobs.
            while (!persisted.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue(persisted.isCompleted)
            val row = runBlocking { db.installedAppDao().get(pkg) }
            assertEquals(payload, row?.selectionPayload)
            assertEquals("fixture.original", row?.originalPackageName)
            assertEquals("9.5.0", row?.version)
            assertEquals(true, row?.isClone)
            assertEquals(if (external) InstallType.CUSTOM else InstallType.PLAY_STORE, row?.installType)
            event(pkg)
            pump(field("packageResultJob").get(vm) as? Job)
            assertEquals(1, calls)
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            koin.get<HttpClient>().close()
            db.close()
            stopKoin()
        }
    }

    @Test fun externalResigningCompletesAndRecordsCurrentRun() = scenario(external = true)
    @Test fun intentFallbackCompletesDespiteChangedApkBytes() = scenario(external = false)
    @Test fun unchangedFallbackDoesNotCompleteFromAnUnrelatedEvent() = scenario(external = false, updated = false)
}
