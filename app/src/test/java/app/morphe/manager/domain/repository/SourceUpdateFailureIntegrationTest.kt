package app.morphe.manager.domain.repository

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import app.morphe.manager.data.redux.*
import app.morphe.manager.data.room.AppDatabase
import app.morphe.manager.di.*
import app.morphe.manager.domain.bundles.JsonPatchBundle
import app.morphe.manager.domain.manager.AutoUpdates
import app.morphe.manager.domain.manager.PreferencesManager
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real repository, JSON metadata and HTTP transport against a loopback fixture only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SourceUpdateFailureIntegrationTest {
    private lateinit var koin: org.koin.core.Koin
    private lateinit var server: HttpServer
    private lateinit var directory: File
    private lateinit var repository: PatchBundleRepository
    private val metadataRequests = AtomicInteger()
    private val assetRequests = AtomicInteger()
    private var missingManifest = false
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        koin = startKoin { androidContext(app); modules(httpModule, preferencesModule,
            repositoryModule, serviceModule, managerModule, databaseModule) }.koin
        repository = koin.get()
        directory = File(app.cacheDir, "source-update-fixture").apply { mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/manifest.json") { exchange ->
            metadataRequests.incrementAndGet()
            val body = if (missingManifest) "missing" else """{
                "download_url":"http://127.0.0.1:${server.address.port}/bundle.mpp",
                "created_at":"2026-11-15T15:14:23Z","description":"fixture","version":"v2"
            }"""
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (missingManifest) 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/bundle.mpp") { exchange ->
            assetRequests.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        // Deliberately metered: this keeps metadata-only checks out of catalogue discovery.
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork!!, NetworkCapabilities())
    }

    @Suppress("UNCHECKED_CAST")
    private fun store(): Store<PatchBundleRepository.BundleState> =
        PatchBundleRepository::class.java.getDeclaredField("store").run {
            isAccessible = true; get(repository) as Store<PatchBundleRepository.BundleState>
        }

    private suspend fun source(installed: Boolean = false): JsonPatchBundle {
        val source = JsonPatchBundle("Fixture", 22, null,
            Instant.parse("2026-11-15T15:14:23Z").toEpochMilli(), null,
            if (installed) "v2" else "v1", null, directory,
            "http://127.0.0.1:${server.address.port}/manifest.json", true, true, koin.get())
        if (installed) source.patchesJarFile.writeText("unchanged cached bundle bytes")
        store().dispatchAndAwait(object : Action<PatchBundleRepository.BundleState> {
            override suspend fun ActionContext.execute(current: PatchBundleRepository.BundleState) =
                PatchBundleRepository.BundleState.Ready(sources = persistentMapOf(22 to source))
        })
        @Suppress("UNCHECKED_CAST")
        val pending = PatchBundleRepository::class.java.getDeclaredField("pendingUpdatesFlow").run {
            isAccessible = true; get(repository) as MutableStateFlow<Map<Int, String>>
        }
        pending.value = mapOf(22 to "v2")
        return source
    }

    private suspend fun update(source: JsonPatchBundle, allowDownload: Boolean) {
        repository.update(source, allowUnsafeNetwork = allowDownload)
        // A store barrier proves the update request has started before joining its owned job.
        store().dispatchAndAwait(object : Action<PatchBundleRepository.BundleState> {
            override suspend fun ActionContext.execute(current: PatchBundleRepository.BundleState) = current
        })
        val job = PatchBundleRepository::class.java.getDeclaredField("updateJob").run {
            isAccessible = true; get(repository) as Job?
        }
        job?.join()
    }

    @After fun close() {
        val scope = PatchBundleRepository::class.java.getDeclaredField("scope").run {
            isAccessible = true; get(repository) as CoroutineScope
        }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        server.stop(0)
        koin.get<HttpClient>().close()
        koin.get<AppDatabase>().close()
        stopKoin()
        directory.deleteRecursively()
    }

    @Test fun failed_asset_delivery_is_error_and_keeps_the_pending_version() = runBlocking {
        val source = source()
        update(source, allowDownload = true)
        assertEquals(PatchBundleRepository.BundleUpdateResult.Error, repository.bundleUpdateProgress.value?.result)
        assertEquals(mapOf(22 to "v2"), repository.pendingUpdates.value)
        assertNotNull(repository.metadataFetchErrors.value[22])
        assertTrue(assetRequests.get() > 0)
    }

    @Test fun metadata_only_failure_keeps_pending_and_cache() = runBlocking {
        missingManifest = true
        koin.get<PreferencesManager>().autoUpdates.update(AutoUpdates.WIFI_ONLY)
        val source = source(installed = true)
        val before = source.patchesJarFile.readBytes()
        update(source, allowDownload = false)
        assertEquals(PatchBundleRepository.BundleUpdateResult.Error, repository.bundleUpdateProgress.value?.result)
        assertEquals(mapOf(22 to "v2"), repository.pendingUpdates.value)
        assertContentEquals(before, source.patchesJarFile.readBytes())
        assertEquals(0, assetRequests.get())
    }

    @Test fun failure_names_the_source_and_stays_until_dismissed() = runBlocking {
        update(source(), allowDownload = true)
        val shown = repository.bundleUpdateProgress.value
        assertEquals(PatchBundleRepository.BundleUpdateResult.Error, shown?.result)
        assertEquals(listOf("Fixture"), shown?.failedNames)
        // Longer than any timed result stays up
        delay(4_500)
        assertEquals(shown, repository.bundleUpdateProgress.value)
    }

    @Test fun a_source_that_keeps_failing_is_reported_once() = runBlocking {
        missingManifest = true
        koin.get<PreferencesManager>().autoUpdates.update(AutoUpdates.WIFI_ONLY)
        val source = source(installed = true)
        update(source, allowDownload = false)
        assertEquals(PatchBundleRepository.BundleUpdateResult.Error, repository.bundleUpdateProgress.value?.result)
        update(source, allowDownload = false)
        assertNotEquals(PatchBundleRepository.BundleUpdateResult.Error, repository.bundleUpdateProgress.value?.result)
        // Source management still marks it
        assertNotNull(repository.metadataFetchErrors.value[22])
        assertEquals(2, metadataRequests.get())
    }

    @Test fun current_check_clears_pending_keeps_cached_bytes() = runBlocking {
        val source = source(installed = true)
        val before = source.patchesJarFile.readBytes()
        update(source, allowDownload = true)
        assertEquals(PatchBundleRepository.BundleUpdateResult.NoUpdates, repository.bundleUpdateProgress.value?.result)
        assertTrue(repository.pendingUpdates.value.isEmpty())
        assertContentEquals(before, source.patchesJarFile.readBytes())
        assertEquals(0, assetRequests.get())
    }

    @Test fun disabling_launch_checks_skips_source_and_catalogue_requests() = runBlocking {
        source()
        koin.get<PreferencesManager>().autoUpdates.update(AutoUpdates.OFF)
        repository.updateCheck()
        assertEquals(0, metadataRequests.get())
        assertEquals(0, assetRequests.get())
        assertEquals(mapOf(22 to "v2"), repository.pendingUpdates.value)
    }
}
