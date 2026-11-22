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
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DownloadTargetMetadataTest {
    @Test fun selected_source_build_and_form_factor_are_sent_only_for_that_exact_version() {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        val client = HttpClient()
        try {
            val resolver = DownloadUrlResolver(ReleaseAPI(HttpService(Json, client, okhttp3.OkHttpClient()),
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
}
