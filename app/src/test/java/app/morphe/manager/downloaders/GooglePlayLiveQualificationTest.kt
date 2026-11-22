package app.morphe.manager.downloaders

import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.device.DeviceRegistry
import com.aurora.gplayapi.engine.OkHttpEngine
import com.aurora.gplayapi.model.ItemFields
import com.aurora.gplayapi.session.DefaultPlaySessions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Explicit opt-in external qualification. Never reads a Google account or installs an app. */
class GooglePlayLiveQualificationTest {
    @Test fun anonymousFreeOriginalDelivery() = runBlocking {
        val directory = System.getenv("MORPHE_PLAY_QUALIFICATION")
        assumeTrue("External acquisition requires the explicit qualification launcher", !directory.isNullOrBlank())
        val root = File(checkNotNull(directory)).apply { mkdirs() }
        val http = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
            .readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val engine = OkHttpEngine(http)
        val device = DeviceRegistry.default
        val client = PlayClient(engine, DefaultPlaySessions(engine, device, Locale.US, authenticated = null))
        val steps = mutableListOf<String>()
        try {
            val game = client.apps.details("com.miniclip.plagueinc", ItemFields.UPDATE)
            File(root, "plague-metadata.json").writeText(buildJsonObject {
                put("package", game.packageName); put("version", game.versionName); put("versionCode", game.versionCode)
                put("isFree", game.isFree); put("assetPacks", game.assetPacks.size)
                put("dependentLibraries", JsonArray(game.dependencies.dependentLibraries.map { JsonPrimitive(it.packageName) }))
                put("dependentPackages", JsonArray(game.dependencies.dependentPackages.map(::JsonPrimitive)))
                put("requestedByPatch", "1.27.5"); put("matchesPatchVersion", game.versionName == "1.27.5")
                put("account", "none"); put("deviceProfile", device.id)
            }.toString())
            // Qualify a free APK/split delivery separately from the game's extra-assets limitation.
            val app = client.apps.details("com.google.android.calculator", ItemFields.UPDATE)
            val target = OriginalDownloadTarget(app.packageName, app.versionName, setOf(app.versionCode.toInt()))
            val original = GooglePlayOriginals(client.apps, client.delivery, http).use {
                it.acquire(target, File(root, "original")) { progress -> steps.add(progress.step) }
            }
            val proof = buildJsonObject {
                put("state", "ACQUIRED"); put("account", "none"); put("deviceProfile", device.id)
                put("package", app.packageName); put("version", app.versionName); put("versionCode", app.versionCode)
                put("file", original.absolutePath); put("format", ArchiveFormat.detect(original).name)
                put("bytes", original.length()); put("sha256", MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString("") { "%02x".format(it) })
                put("steps", JsonArray(steps.distinct().map(::JsonPrimitive))); put("installed", false)
            }
            File(root, "proof.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), proof))
        } catch (error: Exception) {
            // Do not persist session tokens, cookies or signed download URLs in failure evidence.
            File(root, "failure.json").writeText(buildJsonObject { put("type", error.javaClass.name); put("deviceProfile", device.id); put("account", "none") }.toString())
            throw error
        } finally { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
}
