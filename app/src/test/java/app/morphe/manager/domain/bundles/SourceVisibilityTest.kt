package app.morphe.manager.domain.bundles

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class,
    manifest = org.robolectric.annotation.Config.NONE)
class SourceVisibilityTest {
    private val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)
    private val prefs = app.morphe.manager.domain.manager.PreferencesManager(org.robolectric.RuntimeEnvironment.getApplication())
    private val http = app.morphe.manager.network.service.HttpService(kotlinx.serialization.json.Json, client, okhttp3.OkHttpClient(), app.morphe.manager.network.service.AlwaysOnline)
    private val api = app.morphe.manager.network.api.ReleaseAPI(http, prefs)
    private val services = RemoteBundleServices(http, api,
        app.morphe.manager.network.service.AssetDownloader(http, api, prefs), prefs)
    @org.junit.After fun closeClient() { client.close() }

    private fun failedRemote(directory: File, uid: Int = 123) = JsonPatchBundle(
        name = "Unavailable community patches", uid = uid, displayName = null,
        createdAt = null, updatedAt = null, installedVersionSignature = null,
        error = IOException("The patch bundle could not be downloaded"),
        directory = directory, endpoint = "https://example.invalid/patches.json",
        autoUpdate = true, enabled = true, services = services
    )

    @Test fun `failed uncached remote is hidden and remains eligible to reappear`() {
        val directory = Files.createTempDirectory("source-visibility-").toFile()
        try {
            val source = failedRemote(directory)
            assertTrue(source.state is PatchBundleSource.State.Failed)
            assertFalse(source.visibleInManagement())
            // The failure state must not conceal the presence of an installed copy.
            directory.resolve("patches.jar").writeBytes(byteArrayOf(1))
            assertTrue(source.visibleInManagement())
        } finally {
            directory.resolve("patches.jar").delete()
            directory.delete()
        }
    }

    @Test fun `failed default source remains actionable without a cached bundle`() {
        val directory = Files.createTempDirectory("source-visibility-").toFile()
        try {
            assertTrue(failedRemote(directory, uid = 0).visibleInManagement())
        } finally { directory.delete() }
    }

    @Test fun `missing imported remote is hidden without excluding its retry identity`() {
        assertFalse(sourceVisibleInManagement(isRemote = true, isDefault = false, isMissing = true))
        assertTrue(sourceVisibleInManagement(isRemote = true, isDefault = false, isMissing = false))
    }

    @Test fun `cached failures and default or local diagnostics stay visible`() {
        assertTrue(sourceVisibleInManagement(isRemote = true, isDefault = false, isMissing = false))
        assertTrue(sourceVisibleInManagement(isRemote = true, isDefault = true, isMissing = true))
        assertTrue(sourceVisibleInManagement(isRemote = false, isDefault = false, isMissing = true))
    }

}
