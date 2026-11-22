package app.morphe.manager.domain.batch

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import app.morphe.manager.ui.viewmodel.PatchFlow
import app.morphe.manager.domain.apk.InstalledApkInfo
import androidx.work.Configuration
import androidx.work.WorkManager
import app.morphe.manager.di.*
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.util.AppCoroutineScope
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Production coordinator, preferences and resolver; no APK download, patching or install. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class PreparedBatchQueueIntegrationTest {
    private fun coordinator(koin: Koin, scope: AppCoroutineScope) = BatchPatchCoordinator(
        app = RuntimeEnvironment.getApplication(), fs = koin.get(), pm = koin.get(), prefs = koin.get(),
        resolver = koin.get(), workerRepository = koin.get(), patchBundleRepository = koin.get(),
        patchSelectionRepository = koin.get(), patchOptionsRepository = koin.get(),
        installedAppRepository = koin.get(), originalApkRepository = koin.get(),
        installerManager = koin.get(), sessionInstaller = koin.get(),
        scope = scope, knownPatchFailures = koin.get(), foregroundWork = koin.get(), json = koin.get())

    @Test fun preparedQueueLifecycle() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        WorkManager.initialize(app, Configuration.Builder().build())
        // This query runs after WorkManager's serial startup reconciliation. Await it
        // before Robolectric can remove this test application's database directory.
        WorkManager.getInstance(app).getWorkInfosByTag("queue-fixture-startup").get()
        val koin = startKoin { androidContext(app); modules(httpModule, preferencesModule,
            repositoryModule, serviceModule, managerModule, databaseModule) }.koin
        val firstScope = AppCoroutineScope()
        val secondScope = AppCoroutineScope()
        try {
            val prefs = koin.get<PreferencesManager>()
            prefs.batchQueue.update("")
            withContext(Dispatchers.Default) { koin.get<app.morphe.manager.domain.repository.PatchBundleRepository>().reload() }
            println("QUEUE_FIXTURE catalogue initialized")
            val target = BatchTarget("missing.original", "missing.clone")
            val first = coordinator(koin, firstScope)
            first.plan(listOf(target), true, BatchInstallPolicy.SAVE_ONLY)
            first.state.first { it?.phase == BatchPhase.PREFLIGHT }
            println("QUEUE_FIXTURE initial preparation resolved")
            first.toggleExcluded(target.id)
            first.setPolicy(BatchInstallPolicy.INSTALL_AFTER)
            first.savedQueue.first { it?.excluded == setOf(target.id) && it.policy == BatchInstallPolicy.INSTALL_AFTER }
            println("QUEUE_FIXTURE intent persisted")
            firstScope.coroutineContext[Job]!!.cancelAndJoin() // discard the live owner; preparation is read from preferences

            val second = coordinator(koin, secondScope)
            assertNull(second.state.value)
            val saved = second.savedQueue.first { it != null }!!
            assertEquals(listOf(target), saved.targets)
            assertEquals(true, saved.resolvedMountChoices[target.id])
            val added = BatchTarget("missing.another")
            second.enqueue(listOf(target, added, added))
            val restored = second.state.first { it?.phase == BatchPhase.PREFLIGHT }!!
            println("QUEUE_FIXTURE restored preparation resolved")
            assertEquals(listOf(target, added), restored.targets)
            assertEquals(BatchInstallPolicy.INSTALL_AFTER, restored.policy)
            assertTrue(restored.items.first().useMount)
            assertFalse(restored.items.last().useMount)
            assertEquals(BatchItemState.EXCLUDED, restored.items.first().state)
            assertFalse(restored.items.any { it.forceVersionMismatch || it.forceUnverifiedSignature })
            assertTrue(restored.items.all { it.knownFailureRetries.isEmpty() })
            second.setInstallTarget(added.id, true)
            second.state.first { it?.items?.last()?.useMount == true }
            second.savedQueue.first { it?.resolvedMountChoices?.get(added.id) == true }
            second.setVersionSource(added.id, 40, "7.14.0")
            second.state.first { it?.items?.last()?.target?.versionSourceUid == 40 }
            second.savedQueue.first { it?.targets?.last()?.versionSourceUid == 40 }
            assertEquals("7.14.0", second.savedQueue.value?.targets?.last()?.versionSourceVersion)
            assertEquals(BatchItemState.EXCLUDED, second.state.value!!.items.first().state)
            second.start() // Missing inputs must remain preparation; reopening never launches a worker.
            assertEquals(BatchPhase.PREFLIGHT, second.state.value?.phase)
            second.enqueue(listOf(BatchTarget("missing.third")))
            val appended = second.state.first { it?.phase == BatchPhase.PREFLIGHT && it.items.size == 3 }!!
            println("QUEUE_FIXTURE additive preparation resolved")
            assertEquals(restored.items.first(), appended.items.first())
            assertTrue(appended.items.first { it.id == added.id }.useMount)
            second.clear()
            prefs.batchQueue.flow.first { it.isBlank() }
            assertNull(second.state.value)
            println("QUEUE_FIXTURE explicit clear persisted")

            second.plan(listOf(added), false, BatchInstallPolicy.SAVE_ONLY)
            second.state.first { it?.phase == BatchPhase.PREFLIGHT }
            val (oldInput, reading) = second.reserveImport("apk")
            oldInput.parentFile!!.mkdirs(); oldInput.writeText("owned incomplete input")
            val beforeReplacement = secondScope.coroutineContext[Job]!!.children.toSet()
            second.plan(listOf(target), false, BatchInstallPolicy.SAVE_ONLY)
            second.state.first { it?.phase == BatchPhase.PREFLIGHT && it.targets == listOf(target) }
            val retirementJobs = secondScope.coroutineContext[Job]!!.children.filter { it !in beforeReplacement }.toList()
            val (newInput, copying) = second.reserveImport("apk")
            assertNotEquals(oldInput.parentFile, newInput.parentFile)
            assertTrue(oldInput.exists()) // Actual consumer still holds the old queue's input.
            copying.finish(); reading.finish()
            retirementJobs.forEach { it.join() }
            assertFalse(oldInput.exists())
            second.clear()
            prefs.batchQueue.flow.first { it.isBlank() }

            // Exercise the actual extracted flow owner, including a suspended external
            // acquisition replaced by the universal picker. No package/file is imported.
            prefs.useExpertMode.update(true)
            val ready = mutableStateOf(false)
            val readinessObserved = CompletableDeferred<Unit>()
            val existingJobs = secondScope.coroutineContext[Job]!!.children.toSet()
            val flow = PatchFlow(secondScope, app, koin.get(), koin.get(), koin.get(),
                koin.get(), koin.get(), koin.get(), koin.get<app.morphe.manager.domain.manager.PatchOptionsPreferencesManager>(), koin.get(), koin.get(), koin.get(),
                koin.get(), koin.get(), koin.get(), koin.get(), second, koin.get(),
                koin.get(), koin.get(), koin.get(), {}, {
                    readinessObserved.complete(Unit)
                    ready.value
                })
            val acquisition = secondScope.coroutineContext[Job]!!.children.first { it !in existingJobs }
            flow.handleExternalApkUri(Uri.parse("content://fixture/never-imported.apk"))
            readinessObserved.await()
            flow.chooseAnyApp("Any app")
            acquisition.join()
            assertTrue(acquisition.isCancelled)
            assertTrue(secondScope.isActive)
            assertTrue(flow.showFilePickerPromptDialog)
            assertEquals("Any app", flow.pendingAppName)
            assertNull(flow.selection.app)
            assertFalse(flow.processingApkSelection)
            val userInput = koin.get<app.morphe.manager.data.platform.Filesystem>().uiTempDir.resolve("fixture-user.apk")
                .also { it.writeText("original bytes") }
            val info = InstalledApkInfo("1", 1, userInput.path)
            val firstCopy = CompletableDeferred<app.morphe.manager.ui.model.SelectedApp.Local>()
            val secondCopy = CompletableDeferred<app.morphe.manager.ui.model.SelectedApp.Local>()
            val hold = CompletableDeferred<Unit>()
            val firstImport = launch { flow.withInstalledInput("same.app", info) { firstCopy.complete(it); hold.await() } }
            val copiedFirst = firstCopy.await()
            val secondImport = launch { flow.withInstalledInput("same.app", info) { secondCopy.complete(it); hold.await() } }
            val copiedSecond = secondCopy.await()
            assertNotEquals(copiedFirst.file, copiedSecond.file)
            firstImport.cancelAndJoin()
            assertFalse(copiedFirst.file.exists())
            assertTrue(copiedSecond.file.exists())
            secondImport.cancelAndJoin()
            assertFalse(copiedSecond.file.exists())
            assertEquals("original bytes", userInput.readText())
            flow.close()
            assertFalse(flow.showFilePickerPromptDialog)
            assertTrue(secondScope.isActive)
            println("QUEUE_FIXTURE replaced acquisition cancelled without closing lifetime owner")
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            secondScope.coroutineContext[Job]!!.cancelAndJoin()
            koin.get<AppCoroutineScope>().coroutineContext[Job]!!.cancelAndJoin()
            koin.get<HttpClient>().close()
            koin.get<app.morphe.manager.data.room.AppDatabase>().close()
            stopKoin()
        }
    }
}
