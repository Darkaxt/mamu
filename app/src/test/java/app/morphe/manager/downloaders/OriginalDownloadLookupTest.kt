package app.morphe.manager.downloaders

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class OriginalDownloadLookupTest {
    @Test fun alternativeDiscoveryUsesTheShopRatherThanGoogle() {
        val intent = OriginalDownloadTarget("com.miniclip.plagueinc", "1.27.5", emptySet())
        for (source in DownloadSource.entries.filter { it != DownloadSource.APK_MIRROR }) {
            val query = StoreSearch.query(intent, source)
            assertEquals("Discovery must contact ${source.label} directly", source, DownloadPolicy(false).source(query))
            assertFalse(query.contains("google.com"))
        }
    }
    private val target = OriginalDownloadTarget("com.miniclip.plagueinc", "1.27.5", setOf(180609152))
    private val mirror = StoreSearch.mirror(target, listOf("arm64-v8a"))
    private val pure = "https://apkpure.net/plague-inc/com.miniclip.plagueinc/download/1.27.5"
    private val emptyMirror = "<title>APKMirror search</title><div id='content' class='search-area'></div>"
    private fun results(vararg urls: String) = "<div data-package_name='com.miniclip.plagueinc'>" + urls.joinToString("") {
        "<a class='da download_apk' href='$it'>Plague Inc 1.27.5</a>"
    } + "</div>"
    private class Fixture(private val resolvedUrl: (String) -> String = { it }, private val response: (String) -> Pair<Int, String>) : AutoCloseable {
        val seen = CopyOnWriteArrayList<String>()
        private val workers = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = "http://127.0.0.1:${server.address.port}/v1"
        val client = ByparrClient(true)
        @Volatile var queueFeedback = false
        init {
            server.executor = workers
            server.createContext("/v1") { exchange ->
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                assertEquals("request.get", request.getString("cmd"))
                val url = request.getString("url")
                seen.add(url)
                val (status, html) = response(url)
                val bytes = JSONObject().put("status", "ok").put("solution", JSONObject()
                    .put("status", status).put("url", resolvedUrl(url)).put("userAgent", "fixture browser")
                    .put("contentType", "text/html").put("response", html)).toString().toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/queue/") { exchange ->
                if (!queueFeedback) { exchange.sendResponseHeaders(404, -1); exchange.close() }
                else {
                    val body = JSONObject().put("requestId", exchange.requestURI.path.substringAfterLast('/'))
                        .put("state", "queued").put("position", 2).put("total", 2).put("queueLimit", 32).toString().toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            server.start()
        }
        override fun close() { client.cancel(); server.stop(0); workers.shutdownNow() }
    }

    @Test fun mirrorMissContinuesToExactPackageVersionAtApkPureWithShopSteps() {
        val canonical = "$pure?from=version"
        Fixture(resolvedUrl = { if (it == pure) canonical else it }) { url -> 200 to when {
            url == mirror -> emptyMirror
            url == StoreSearch.query(target, DownloadSource.APK_PURE) -> javaClass.getResource("/discovery/pure-package.html")!!.readText()
            url == pure -> javaClass.getResource("/discovery/pure-version.html")!!.readText()
            else -> error("Unexpected browser request $url")
        } }.use { server ->
            val lookup = OriginalDownloadLookup(target)
            val progress = CopyOnWriteArrayList<OriginalDownloadLookup.Progress>()
            val result = lookup.fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a"), progress::add)
                as OriginalDownloadLookup.Result.Page
            assertEquals(listOf(mirror, StoreSearch.query(target, DownloadSource.APK_PURE), pure), server.seen.toList())
            assertEquals(DownloadSource.APK_PURE, result.page.content.source)
            assertTrue(result.page.content.next!!.contains("/XAPK/com.miniclip.plagueinc?versionCode=180609152"))
            assertTrue(progress.any { it.message.contains("APKMirror · Resolving package") })
            assertTrue(progress.any { it.source == DownloadSource.APK_MIRROR && it.step == "Requested version not found" && it.level == app.morphe.manager.patcher.logger.LogLevel.WARN })
            assertTrue(progress.any { it.source == DownloadSource.APK_MIRROR && it.step == "Requested version not found" && it.pageUrl == mirror && it.detail == null })
            assertTrue(progress.any { it.source == DownloadSource.APK_PURE && it.step.startsWith("Matching version") && it.pageUrl == canonical })
            assertTrue(progress.any { it.message.contains("APKPure · Resolving package") })
            assertTrue(progress.any { it.message.contains("APKPure · Matching package") })
            assertTrue(progress.any { it.message.contains("APKPure · Matching version") })
            assertTrue(progress.any { it.message.contains("APKPure · Requesting download link") })
            assertTrue(progress.none { lookup.isCurrent(it) })
        }
    }

    @Test fun allSitesAreCheckedOnceAndUnknownOrFailedRepliesNeverAdvance() {
        Fixture { url -> when {
            url == mirror -> 200 to emptyMirror
            url == StoreSearch.query(target, DownloadSource.APK_PURE) -> 404 to ""
            url == StoreSearch.query(target, DownloadSource.APK_COMBO) -> 200 to "<form action='/search'><input name='q' value='com.miniclip.plagueinc'></form>"
            else -> 200 to "<h1 id='search-query-text'>com.miniclip.plagueinc</h1><div id='content-list'></div>"
        } }.use { server ->
            val failure = assertThrows(IOException::class.java) {
                OriginalDownloadLookup(target).fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a")) {}
            }
            assertTrue(failure.message!!.contains("APKPure") && failure.message!!.contains("Uptodown") && failure.message!!.contains("APKCombo"))
            assertEquals(4, server.seen.size)
            assertEquals(4, server.seen.toSet().size)
        }
        for ((status, html) in listOf(408 to "<title>Timeout</title>", 200 to "<title>Just a moment...</title><div id='challenge-stage'></div>",
            200 to "<title>Unexpected markup</title>")) {
            Fixture { status to html }.use { server ->
                assertThrows(IOException::class.java) {
                    OriginalDownloadLookup(target).fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a")) {}
                }
                assertEquals(listOf(mirror), server.seen.toList())
            }
        }
    }

    @Test fun discoveryRejectsForeignPackagesWrappersCredentialsAndAttachmentHosts() {
        val search = StoreSearch.query(target, DownloadSource.APK_PURE)
        val html = results("https://apkpure.net/other/com.example.other/download/1.27.5",
            "https://apkpure.net.evil.test/x/com.miniclip.plagueinc", "https://user:pw@apkpure.net/x/com.miniclip.plagueinc",
            "https://d.apkpure.net/b/XAPK/com.miniclip.plagueinc", "https://dw.uptodown.com/dwn/x", "https://evil.test/x")
        assertThrows(IOException::class.java) { StoreSearch.parse(search, html, target, DownloadSource.APK_PURE) }
        assertThrows(IOException::class.java) { StoreSearch.parse("https://google.com.evil.test/search", results(pure), target, DownloadSource.APK_PURE) }
        assertThrows(IOException::class.java) { StoreSearch.parse(search, "<title>Unknown page</title>", target, DownloadSource.APK_PURE) }
        assertEquals(search, DownloadPolicy(false).pageUrl(search))
        assertFalse(DownloadPolicy(false).downloadUrl(search))
    }

    @Test fun multipleProviderResultsStayAnExplicitChoiceIncludingBackgroundPolicy() {
        Fixture { 200 to if (it == mirror) emptyMirror else results(pure,
            "https://apkpure.com/plague-inc/com.miniclip.plagueinc/versions") }.use { server ->
            val result = OriginalDownloadLookup(target).fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a")) {}
                as OriginalDownloadLookup.Result.Choices
            assertEquals(DownloadSource.APK_PURE, result.source)
            assertEquals(2, result.choices.size)
            assertNull(backgroundDownloadChoice(result.choices, listOf("arm64-v8a")))
            assertEquals(2, server.seen.size)
        }
    }

    @Test fun cancellationDuringAConfirmedMissStopsBeforeAnotherSiteIsSubmitted() {
        Fixture { 200 to emptyMirror }.use { server ->
            assertThrows(IOException::class.java) {
                OriginalDownloadLookup(target).fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a")) {
                    if (it.source == DownloadSource.APK_PURE) server.client.cancel()
                }
            }
            assertEquals(listOf(mirror), server.seen.toList())
        }
    }

    @Test fun queuedAlternativeNamesTheStoreAndStepAndCancellationDoesNotReplayIt() {
        val release = CountDownLatch(1)
        val observed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<OriginalDownloadLookup.Progress>()
        Fixture { url ->
            if (url == mirror) 200 to emptyMirror else {
                release.await()
                200 to results(pure)
            }
        }.use { server ->
            server.queueFeedback = true
            val executor = Executors.newSingleThreadExecutor()
            val lookup = OriginalDownloadLookup(target)
            try {
                val pending = executor.submit<OriginalDownloadLookup.Result> {
                    try {
                        lookup.fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a")) {
                            events.add(it)
                            if (it.source == DownloadSource.APK_PURE && it.queue != null) observed.countDown()
                        }
                    } finally { observed.countDown() }
                }
                observed.await()
                val feedback = events.first { it.source == DownloadSource.APK_PURE && it.queue != null }
                assertTrue(feedback.message.contains("Resolving package"))
                assertTrue(feedback.message.contains("Queue position 2 of 2"))
                assertFalse(pending.isDone)
                server.client.cancel()
                assertThrows(java.util.concurrent.ExecutionException::class.java) { pending.get() }
                assertEquals(listOf(mirror, StoreSearch.query(target, DownloadSource.APK_PURE)), server.seen.toList())
                assertFalse(lookup.isCurrent(feedback))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun capturedUptodownMetadataSelectsTheRequestedOlderBuildRatherThanLatest() {
        val page = RemotePage.parse("https://plague-inc.en.uptodown.com/android",
            javaClass.getResource("/discovery/uptodown-app.html")!!.readText(), expectedVersion = "1.26.1")
        assertEquals("https://plague-inc.en.uptodown.com/android/download/1221514833", page.next)
        assertTrue(page.choices.single().label.contains("1.26.1"))
        assertFalse(page.choices.single().label.contains("1.27.3"))
    }

    @Test fun completed404CanAdvanceButUnknownProviderMarkupRemainsUncertain() {
        Fixture { url -> when {
            url == mirror -> 404 to ""
            url == StoreSearch.query(target, DownloadSource.APK_PURE) -> 200 to results(pure)
            else -> 200 to javaClass.getResource("/discovery/pure-version.html")!!.readText()
        } }.use { server ->
            val result = OriginalDownloadLookup(target).fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a")) {}
                as OriginalDownloadLookup.Result.Page
            assertEquals(DownloadSource.APK_PURE, result.page.content.source)
            assertEquals(3, server.seen.size)
        }
        for (url in listOf(pure, "https://apkcombo.com/plague-inc/com.miniclip.plagueinc/download/phone-1.27.5-apk")) {
            val error = assertThrows(IOException::class.java) {
                RemotePage.parse(url, "<title>Unexpected provider response</title>", expectedVersion = target.version)
            }
            assertFalse(error is NoMatchingApk)
        }
        assertThrows(NoMatchingApk::class.java) {
            RemotePage.parse(pure, "<title>Download Plague Inc. 1.27.3 APK for Android</title>", expectedVersion = target.version)
        }
    }

    @Test fun anInternallyFollowedAlternativeCannotBeFetchedAgainByAnOuterCaller() {
        Fixture { url -> 200 to when {
            url == mirror -> emptyMirror
            url == StoreSearch.query(target, DownloadSource.APK_PURE) -> results(pure)
            else -> "<title>Download Plague Inc. 1.27.5 APK for Android</title><a class='download-btn' href='$pure'>1.27.5</a>"
        } }.use { server ->
            val lookup = OriginalDownloadLookup(target)
            val first = lookup.fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a")) {} as OriginalDownloadLookup.Result.Page
            assertEquals(pure, first.page.content.next)
            assertThrows(IOException::class.java) {
                lookup.fetch(server.client, server.endpoint, checkNotNull(first.page.content.next), listOf("arm64-v8a")) {}
            }
            assertEquals(3, server.seen.size)
        }
    }

    @Test fun unrelatedSearchesAreRejectedAndWrappedStoreLinksKeepTheirEscapedKeys() {
        Fixture { 200 to "<title>Example</title>" }.use { server ->
            assertThrows(IOException::class.java) {
                OriginalDownloadLookup(target).fetch(server.client, server.endpoint,
                    "https://www.google.com/search?q=weather+tomorrow", listOf("arm64-v8a")) {}
            }
            assertTrue(server.seen.isEmpty())
            val direct = "https://www.apkmirror.com/apk/example/?key=a%2Fb%2Bc%26d"
            val wrapped = "https://www.google.com/url?q=" + java.net.URLEncoder.encode(direct, "UTF-8")
            OriginalDownloadLookup(target).fetch(server.client, server.endpoint, wrapped, listOf("arm64-v8a")) {}
            assertEquals(listOf(direct), server.seen.toList())
        }
    }

    @Test fun nativeUptodownSearchCandidatesArePackageCheckedBeforeVersionResolution() {
        val query = StoreSearch.query(target, DownloadSource.UPTODOWN)
        val captured = javaClass.getResource("/discovery/uptodown-search.html")!!.readText()
        val choices = StoreSearch.parse(query, captured, target, DownloadSource.UPTODOWN)
        assertEquals("https://plague-inc.en.uptodown.com/android", choices.single().url)
        Fixture { url -> 200 to when (url) {
            query -> captured
            else -> "<title>Different app</title><table><tr><td>com.example.foreign</td></tr></table>"
        } }.use { server ->
            val candidates = server.client.discover(server.endpoint, target, DownloadSource.UPTODOWN)
            val error = assertThrows(IOException::class.java) {
                server.client.fetch(server.endpoint, candidates.single().url, target.version,
                    listOf("arm64-v8a"), target.packageName)
            }
            assertTrue(error.message!!.contains("did not identify the requested package"))
        }
    }

    @Test fun nativeRateLimitAndChallengesKeepActualShopAttributionAndNeverReplay() {
        for (status in listOf(429, 408, 503)) {
            Fixture { url -> if (url == mirror) 200 to emptyMirror else status to "<title>Request unavailable</title>" }.use { server ->
                val error = assertThrows(IOException::class.java) {
                    OriginalDownloadLookup(target).fetch(server.client, server.endpoint, mirror, listOf("arm64-v8a")) {}
                }
                assertTrue(error.message!!.contains("APKPure lookup returned HTTP $status"))
                assertEquals(listOf(mirror, StoreSearch.query(target, DownloadSource.APK_PURE)), server.seen.toList())
                assertTrue(server.seen.none { it.contains("google.com") })
            }
        }
        assertThrows(IOException::class.java) {
            StoreSearch.parse(StoreSearch.query(target, DownloadSource.APK_PURE),
                "<title>Just a moment...</title><div id='challenge-stage'></div>", target, DownloadSource.APK_PURE)
        }
    }
}
