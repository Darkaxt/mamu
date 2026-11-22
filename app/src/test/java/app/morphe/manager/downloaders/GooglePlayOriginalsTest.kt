package app.morphe.manager.downloaders

import com.aurora.gplayapi.model.App
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import com.aurora.gplayapi.api.AppApi
import com.aurora.gplayapi.api.DeliveryApi
import com.aurora.gplayapi.model.AppFile
import com.aurora.gplayapi.model.Delivery
import com.aurora.gplayapi.model.AppDependencies
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class GooglePlayOriginalsTest {
    private val target = OriginalDownloadTarget("com.miniclip.plagueinc", "1.27.5", null)
    @Test fun exactVersionAndBuildAreRequiredWithoutSubstitutingTheCurrentPlayVersion() {
        val app = App(packageName = target.packageName, versionName = "1.27.5", versionCode = 180609152, isFree = true)
        assertEquals(180609152L, playOriginalVersion(target, app))
        val missing = assertThrows(PlayOriginalUnavailable::class.java) { playOriginalVersion(target, app.copy(versionName = "1.27.3")) }
        assertTrue(missing.versionMismatch)
        assertEquals("https://play.google.com/store/apps/details?id=com.miniclip.plagueinc", missing.pageUrl)
        assertEquals(123L, playOriginalVersion(target.copy(buildCodes = setOf(123)), app))
        assertThrows(PlayOriginalUnavailable::class.java) { playOriginalVersion(target.copy(buildCodes = setOf(123, 124)), app) }
        assertThrows(IOException::class.java) { playOriginalVersion(target, app.copy(packageName = "com.example.other")) }
        assertThrows(PlayOriginalUnavailable::class.java) { playOriginalVersion(target, app.copy(isFree = false)) }
    }

    @Test fun playFileHostsCannotAdmitCredentialsDowngradesOrLookalikeDomains() {
        assertTrue(playOriginalFileUrl("https://play.googleapis.com/download/by-token/download?token=test"))
        assertTrue(playOriginalFileUrl("https://r1---example.gvt1.com/play-apps-download-default/file"))
        for (url in listOf("http://play.googleapis.com/file", "https://play.googleapis.com.evil.test/file",
            "https://user:secret@play.googleapis.com/file", "https://evil.test/file", "https://play.googleapis.com:1234/file"))
            assertFalse(url, playOriginalFileUrl(url))
    }

    private inline fun <reified T> api(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private fun apk(split: String? = null): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(AndroidManifestBlock().apply {
                packageName = target.packageName
                if (split != null) setSplit(split, true)
                refresh()
            }.bytes)
            zip.closeEntry()
        }
    }.toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun play(delivery: Delivery, lookups: AtomicInteger = AtomicInteger()) = GooglePlayOriginals(
        api<AppApi> { method, args ->
            check(method == "details" && args[0] == target.packageName)
            lookups.incrementAndGet()
            App(target.packageName, versionName = target.version, versionCode = 7, isFree = true,
                dependencies = AppDependencies(dependentPackages = listOf("com.google.android.gms")))
        }, api<DeliveryApi> { method, args ->
            check(method == "resolveDelivery" && args[0] == target.packageName && args[1] == 7L)
            check(args[5] == null && args[6] == emptyList<Any>()) // full files, no installed base or patch formats
            delivery
        }, OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build(), true)

    @Test fun completeBaseAndSplitsReachTheExistingArchivePathWithRealByteProgress() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = apk(); val split = apk("config.arm64_v8a")
        val requests = AtomicInteger()
        listOf("base" to base, "split" to split).forEach { (path, bytes) -> server.createContext("/$path") { exchange ->
            requests.incrementAndGet(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        } }
        server.start()
        val root = Files.createTempDirectory("play-split-").toFile()
        try {
            val files = listOf(AppFile(name = "original.apk", url = "http://127.0.0.1:${server.address.port}/base", size = base.size.toLong(), sha256 = hash(base)),
                AppFile(name = "arm64.apk", url = "http://127.0.0.1:${server.address.port}/split", size = split.size.toLong(), sha256 = hash(split), type = AppFile.Type.SPLIT))
            val progress = mutableListOf<GooglePlayOriginals.Progress>()
            val result = play(Delivery(files = files, versionCode = 7)).use { it.acquire(target, root, progress::add) }
            assertEquals(ArchiveFormat.XAPK, ArchiveFormat.detect(result))
            ZipFile(result).use { zip ->
                assertArrayEquals(base, zip.getInputStream(zip.getEntry("base.apk")).readBytes())
                assertArrayEquals(split, zip.getInputStream(zip.getEntry("split-1.apk")).readBytes())
            }
            assertEquals(2, requests.get())
            assertEquals((base.size + split.size).toLong(), progress.last { it.transferring }.bytes)
            assertTrue(progress.all { it.step.startsWith("Google Play ·") })
            assertTrue(progress.indexOfFirst { it.step.contains("Resolving package") } < progress.indexOfFirst { it.step.contains("Matching version") })
            assertTrue(progress.indexOfFirst { it.step.contains("Matching version") } < progress.indexOfFirst { it.step.contains("Requesting download links") })
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun httpFailureAndHashMismatchAreErrorsWithoutReplayOrAFalseMissingVersion() = runBlocking {
        val attempts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/limited") { exchange -> attempts.incrementAndGet(); exchange.sendResponseHeaders(429, -1); exchange.close() }
        val bytes = apk()
        server.createContext("/bad-hash") { exchange -> attempts.incrementAndGet(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close() }
        server.start()
        val root = Files.createTempDirectory("play-errors-").toFile()
        try {
            for (path in listOf("limited", "bad-hash")) {
                val file = AppFile(name = "base.apk", url = "http://127.0.0.1:${server.address.port}/$path", sha256 = "0".repeat(64))
                val error = try { play(Delivery(files = listOf(file), versionCode = 7)).use { it.acquire(target, root) {} }; error("Expected rejection") }
                    catch (error: IOException) { error }
                assertFalse(error is PlayOriginalUnavailable)
                assertTrue(error.message.orEmpty(), if (path == "limited") "HTTP 429" in error.message.orEmpty() else "hash check" in error.message.orEmpty())
            }
            assertEquals(2, attempts.get())
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun cancellationDisconnectsActualFileReadBeforeTheOwnerRetiresItsWorkspace() = runBlocking {
        val release = CountDownLatch(1)
        val transferred = CompletableDeferred<Unit>()
        val executor = Executors.newSingleThreadExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        server.createContext("/held") { exchange ->
            try { exchange.sendResponseHeaders(200, 100000); exchange.responseBody.write(ByteArray(1024)); exchange.responseBody.flush(); release.await() }
            finally { exchange.close() }
        }
        server.start()
        val root = Files.createTempDirectory("play-cancel-").toFile()
        try {
            val file = AppFile(name = "base.apk", url = "http://127.0.0.1:${server.address.port}/held", size = 100000)
            play(Delivery(files = listOf(file), versionCode = 7)).use { play ->
                val job = launch(Dispatchers.IO) { play.acquire(target, root) { if (it.bytes > 0) transferred.complete(Unit) } }
                transferred.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                // Actual writer has exited: exclusive rename succeeds without deleting a live input.
                val partial = File(root, "base.apk")
                assertTrue(partial.renameTo(File(root, "cancelled.apk")))
            }
        } finally { release.countDown(); server.stop(0); executor.shutdown(); root.deleteRecursively() }
    }
}
