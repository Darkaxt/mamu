package app.morphe.manager.domain.manager

import android.app.Application
import android.os.Build
import app.morphe.manager.domain.bundles.BundledAppTarget
import app.morphe.manager.network.api.ReleaseAPI
import app.morphe.manager.network.service.HttpService
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.FormFactor
import app.morphe.patcher.patch.SupportedAbi
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DownloadTargetMetadataTest {
    @Test fun selected_source_build_and_form_factor_are_sent_only_for_that_exact_version() {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        val client = HttpClient()
        try {
            val resolver = DownloadUrlResolver(ReleaseAPI(HttpService(Json, client, okhttp3.OkHttpClient(), app.morphe.manager.network.service.AlwaysOnline),
                PreferencesManager(RuntimeEnvironment.getApplication())))
            val factor = FormFactor.entries.first { it != FormFactor.MOBILE }
            val target = BundledAppTarget(AppTarget(version = "3.0"), 7, "Selected source",
                abiBuildCodes = mapOf(SupportedAbi.ARM64_V8A to 300, SupportedAbi.ARMEABI_V7A to 200),
                formFactor = factor)
            val exact = URLDecoder.decode(resolver.apiSearchUrl("example.original", "3.0", target), "UTF-8")
            assertTrue(exact.endsWith("example.original~3.0~arm64-v8a~300~${factor.name.lowercase()}"))
            val other = URLDecoder.decode(resolver.apiSearchUrl("example.original", "2.0", target), "UTF-8")
            assertTrue(other.endsWith("example.original~2.0~arm64-v8a"))
        } finally { client.close() }
    }

    /**
     * Seen on 2026-11-28 with YouTube Music 9.46.51: the API did not know the bundle's arm64 build
     * code and answered with a Google search, so Mamu searched APKMirror and only offered split
     * bundles. Without the build code the API leads to the full arm64 APK.
     */
    @Test fun unknown_build_code_retries_the_version_query_before_searching() = runBlocking {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        val client = HttpClient()
        try {
            val resolver = DownloadUrlResolver(ReleaseAPI(HttpService(Json, client, okhttp3.OkHttpClient(), app.morphe.manager.network.service.AlwaysOnline),
                PreferencesManager(RuntimeEnvironment.getApplication())))
            val target = BundledAppTarget(AppTarget(version = "9.46.51"), 7, "Selected source",
                abiBuildCodes = mapOf(SupportedAbi.ARM64_V8A to 94651230))
            val api = "https://api.morphe.software/v2"
            val full = "https://www.apkmirror.com/apk/google-inc/youtube-music/youtube-music-9-46-51-release/youtube-music-9-46-51-4-android-apk-download/"
            val answers = mapOf(
                "$api/web-search/com.google.android.apps.youtube.music~9.46.51~arm64-v8a~94651230" to
                    "$api/web-search-fallback/com.google.android.apps.youtube.music~9.46.51~arm64-v8a~94651230",
                "$api/web-search-fallback/com.google.android.apps.youtube.music~9.46.51~arm64-v8a~94651230" to
                    "https://google.com/search?q=com.google.android.apps.youtube.music%209.46.51%20arm64-v8a%20apkmirror.com",
                "$api/web-search/com.google.android.apps.youtube.music~9.46.51~arm64-v8a" to full)
            val asked = mutableListOf<String>()
            val url = resolver.follow("com.google.android.apps.youtube.music", "9.46.51", target) {
                asked += URLDecoder.decode(it, "UTF-8"); answers[URLDecoder.decode(it, "UTF-8")]
            }
            assertEquals(full, url)
            assertFalse(asked.any { "google.com" in it }, "Google itself is never requested")

            // A build the API knows is used as before, without the extra query.
            asked.clear()
            val known = answers + ("$api/web-search/com.google.android.apps.youtube.music~9.46.51~arm64-v8a~94651230" to full)
            assertEquals(full, resolver.follow("com.google.android.apps.youtube.music", "9.46.51", target) {
                asked += it; known[URLDecoder.decode(it, "UTF-8")]
            })
            assertEquals(2, asked.size)

            // When neither query leads anywhere, the native APKMirror search stays the fallback.
            assertEquals(resolver.webSearchUrl("com.google.android.apps.youtube.music", "9.46.51"),
                resolver.follow("com.google.android.apps.youtube.music", "9.46.51", target) { null })
        } finally { client.close() }
    }
}
