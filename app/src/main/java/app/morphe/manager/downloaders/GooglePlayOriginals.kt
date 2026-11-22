package app.morphe.manager.downloaders

import android.content.Context
import com.aurora.gplayapi.PlayClient
import com.aurora.gplayapi.PlayException
import com.aurora.gplayapi.android.NativeDeviceProperties
import com.aurora.gplayapi.api.AppApi
import com.aurora.gplayapi.api.DeliveryApi
import com.aurora.gplayapi.device.DeviceProfile
import com.aurora.gplayapi.device.DeviceRegistry
import com.aurora.gplayapi.engine.OkHttpEngine
import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.AppFile
import com.aurora.gplayapi.model.Delivery
import com.aurora.gplayapi.model.ItemFields
import com.aurora.gplayapi.session.DefaultPlaySessions
import app.morphe.manager.patcher.split.SplitApkPreparer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A known Play eligibility/version miss. Network/authentication/parser errors are not misses. */
class PlayOriginalUnavailable(message: String, val pageUrl: String? = null, val versionMismatch: Boolean = false) : IOException(message)

private suspend fun <T> playRequest(request: suspend () -> T): T = try { request() }
    catch (error: PlayException.RateLimited) { throw IOException("Google Play returned HTTP 429. Retry explicitly.", error) }
    catch (error: PlayException.ServerError) { throw IOException("Google Play returned HTTP ${error.code}. Retry explicitly.", error) }
    catch (error: PlayException.Unauthorized) { throw IOException("Google Play rejected the anonymous device session (HTTP ${error.code}). Retry or choose another site.", error) }

internal fun playOriginalVersion(target: OriginalDownloadTarget, app: App): Long {
    if (app.packageName != target.packageName) throw IOException("Google Play returned another package.")
    if (!app.isFree) throw PlayOriginalUnavailable("Google Play requires an account that owns this app. No purchase was attempted.", playActivityPage(app.packageName))
    if (app.versionName == target.version && app.versionCode > 0 &&
        target.buildCodes?.let { app.versionCode in it.map(Int::toLong) } != false) return app.versionCode
    // A source-declared single build can request an older release. Its downloaded manifest
    // still has to match the full requested version; delivery metadata alone never admits it.
    target.buildCodes?.singleOrNull()?.takeIf { it > 0 }?.let { return it.toLong() }
    throw PlayOriginalUnavailable("Google Play offers ${app.versionName.ifBlank { "an unidentified version" }} (build ${app.versionCode}), not the requested ${target.version} and build.", playActivityPage(app.packageName), versionMismatch = true)
}

internal fun playOriginalFileUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
        (uri.host in setOf("play.googleapis.com", "android.clients.google.com") ||
            listOf("gvt1.com", "gvt2.com", "googleusercontent.com").any { uri.host == it || uri.host.endsWith(".$it") })
}.getOrDefault(false)

/** One acquisition owns its transport; anonymous device sessions never read a Google account. */
class GooglePlayOriginals internal constructor(
    private val apps: AppApi,
    private val delivery: DeliveryApi,
    private val http: OkHttpClient,
    private val allowFixtureHttp: Boolean = false
) : Closeable {
    data class Progress(val step: String, val bytes: Long = 0, val total: Long = -1, val transferring: Boolean = false,
                        val pageUrl: String? = null)

    suspend fun acquire(target: OriginalDownloadTarget, workspace: File, report: (Progress) -> Unit): File = withContext(Dispatchers.IO) {
        report(Progress("Google Play · Resolving package…"))
        val app = try { playRequest { apps.details(target.packageName, ItemFields.UPDATE) } }
            catch (missing: PlayException.NotFound) { throw PlayOriginalUnavailable("Google Play has no listing for this package on this device.") }
        report(Progress("Google Play · Matching version…", pageUrl = playActivityPage(app.packageName).takeIf { app.packageName == target.packageName }))
        val versionCode = playOriginalVersion(target, app)
        // Other packages (for example Play services) are runtime dependencies, not APK splits
        // of this original. Acquiring an APK must not install or reject those separate apps.
        if (app.assetPacks.isNotEmpty())
            throw PlayOriginalUnavailable("Google Play requires separate game asset packs. Choose another original source.")
        report(Progress("Google Play · Requesting download links…"))
        // No installed version/derived id is supplied: request full originals, never binary patches.
        val resolved = try { playRequest { delivery.resolveDelivery(target.packageName, versionCode, app.offerType, patchFormats = emptyList()) } }
            catch (missing: PlayException.NotFound) { throw PlayOriginalUnavailable("Google Play no longer serves the requested build.") }
            catch (unsupported: PlayException.AppNotSupported) { throw PlayOriginalUnavailable("Google Play does not offer this build for this device or region.") }
            catch (removed: PlayException.AppRemoved) { throw PlayOriginalUnavailable("This app was removed from Google Play.") }
        if (resolved.versionCode != 0L && resolved.versionCode != versionCode)
            throw IOException("Google Play returned a different build.")
        if (resolved.encryption != null || resolved.forwardLocked)
            throw IOException("Google Play returned a protected delivery that cannot be imported as an original APK.")
        val files = resolved.files
        if (files.count { it.type == AppFile.Type.BASE } != 1 || files.any { it.type !in setOf(AppFile.Type.BASE, AppFile.Type.SPLIT) })
            throw IOException("Google Play did not return a complete base/split APK delivery.")
        if (files.map { it.name }.toSet().size != files.size || files.any {
                it.name != File(it.name).name || !it.name.endsWith(".apk") || ".." in it.name || '\\' in it.name || it.size < 0 })
            throw IOException("Google Play returned invalid APK filenames or sizes.")
        workspace.mkdirs()
        val total = if (files.all { it.size > 0 }) files.fold(0L) { sum, file -> Math.addExact(sum, file.size) } else -1L
        var completed = 0L
        for ((index, file) in files.withIndex()) {
            ensureActive()
            val output = File(workspace, if (file.type == AppFile.Type.BASE) "base.apk" else "split-$index.apk")
            stream(file, resolved, output) { bytes ->
                report(Progress("Google Play · Downloading ${index + 1} of ${files.size} APKs…", completed + bytes, total, true))
            }
            completed += output.length()
        }
        ensureActive()
        report(Progress("Google Play · Preparing APK splits…", completed, total))
        val base = File(workspace, "base.apk")
        if (files.size == 1) return@withContext base
        val bundle = File(workspace, "original.apks")
        SplitApkPreparer.writeApksArchive(base.absolutePath, files.indices.filter { files[it].type == AppFile.Type.SPLIT }
            .map { File(workspace, "split-$it.apk").absolutePath }, bundle)
        ensureActive()
        bundle
    }

    private fun allowed(url: String) = playOriginalFileUrl(url) || allowFixtureHttp && runCatching {
        val uri = URI(url)
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.rawUserInfo == null
    }.getOrDefault(false)

    private suspend fun stream(file: AppFile, delivery: Delivery, output: File, report: (Long) -> Unit) = coroutineScope {
        val seen = mutableSetOf<String>()
        var url = file.url
        while (seen.add(url)) {
            ensureActive()
            if (!allowed(url)) throw IOException("Google Play returned an unsupported file host.")
            val request = Request.Builder().url(url)
            if (URI(url).host == URI(file.url).host && delivery.type == 0L && delivery.downloadAuthCookies.isNotEmpty()) {
                val cookies = delivery.downloadAuthCookies.joinToString("; ") {
                    if (!it.name.matches(Regex("[A-Za-z0-9_!#$%&'*+.^`|~-]+")) || it.value.any { c -> c.code !in 0x21..0x7e || c == ';' })
                        throw IOException("Google Play returned invalid download cookies.")
                    "${it.name}=${it.value}"
                }
                request.header("Cookie", cookies)
            }
            val call = http.newCall(request.build())
            // Structured child cancellation disconnects the read; its actual completion precedes cleanup.
            val disconnect = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        url = URI(url).resolve(response.header("Location") ?: throw IOException("Google Play returned an empty file redirect.")).toString()
                    } else {
                        if (response.code != 200) throw IOException("Google Play file request returned HTTP ${response.code}. Retry explicitly.")
                        val sha256 = MessageDigest.getInstance("SHA-256")
                        val sha1 = MessageDigest.getInstance("SHA-1")
                        var count = 0L
                        response.body.byteStream().use { input -> output.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                ensureActive()
                                val length = input.read(buffer)
                                if (length < 0) break
                                out.write(buffer, 0, length)
                                sha256.update(buffer, 0, length); sha1.update(buffer, 0, length)
                                count += length
                                report(count)
                            }
                        } }
                        if (count == 0L || file.size > 0 && count != file.size) throw IOException("Google Play returned an incomplete APK.")
                        fun verified(expected: String, actual: ByteArray, size: Int) = expected.isBlank() ||
                            expected.matches(Regex("[0-9a-fA-F]{$size}")) && expected.equals(actual.joinToString("") { "%02x".format(it) }, true)
                        if (!verified(file.sha256, sha256.digest(), 64) || !verified(file.sha1, sha1.digest(), 40))
                            throw IOException("Google Play APK failed its delivery hash check.")
                        return@coroutineScope
                    }
                }
            } finally { disconnect.cancelAndJoin() }
        }
        throw IOException("Google Play returned a file redirect cycle.")
    }

    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        fun create(context: Context): GooglePlayOriginals = forDevice(
            DeviceRegistry.parse("native", NativeDeviceProperties.read(context.applicationContext)), Locale.getDefault())

        internal fun forDevice(device: DeviceProfile, locale: Locale): GooglePlayOriginals {
            val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).build()
            val engine = OkHttpEngine(http)
            val client = PlayClient(engine, DefaultPlaySessions(engine, device, locale, authenticated = null))
            return GooglePlayOriginals(client.apps, client.delivery, http)
        }
    }
}
