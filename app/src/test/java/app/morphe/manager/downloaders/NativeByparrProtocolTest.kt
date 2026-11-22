package app.morphe.manager.downloaders

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException

class NativeByparrProtocolTest {
    @Test fun queueFeedbackNeverReplaysTheHeldBrowserRequest() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val workers = Executors.newFixedThreadPool(2)
        server.executor = workers
        val replies = CountDownLatch(1)
        val observed = CountDownLatch(1)
        val posts = AtomicInteger()
        val feedback = AtomicReference<ByparrClient.QueueStatus>()
        val url = "http://127.0.0.1:${server.address.port}"
        val requests = mutableListOf<JSONObject>()
        server.createContext("/v1") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            synchronized(requests) { requests.add(request) }
            posts.incrementAndGet()
            replies.await()
            val body = JSONObject().put("status", "ok").put("solution", JSONObject()
                .put("status", 200).put("url", "$url/release/").put("userAgent", "fixture browser")
                .put("contentType", "text/html").put("response", "<title>Queue fixture</title>")).toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/queue/") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            val body = JSONObject().put("requestId", exchange.requestURI.path.substringAfterLast('/'))
                .put("state", "queued").put("position", 2).put("total", 2).put("queueLimit", 32)
                .toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = ByparrClient(true)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val pending = caller.submit<ByparrClient.Page> {
                try {
                    client.fetch("$url/v1", "$url/release/") {
                        feedback.set(it)
                        observed.countDown()
                    }
                } finally { observed.countDown() }
            }
            observed.await()
            assertEquals(2, feedback.get()?.position)
            assertTrue(feedback.get()?.message.orEmpty().contains("1 request queued ahead"))
            assertFalse(pending.isDone)
            assertEquals(1, posts.get())
            replies.countDown()
            assertEquals("Queue fixture", pending.get().content.title)
            assertFalse(client.receivingQueueFeedback)
            val request = synchronized(requests) { requests.single() }
            assertEquals("request.get", request.getString("cmd"))
            assertEquals(60000, request.getInt("maxTimeout"))
            assertTrue(request.getString("requestId").isNotBlank())
            client.cancel()
            assertThrows(IOException::class.java) { client.fetch("$url/v1", "$url/release/") }
            assertEquals(1, posts.get())
        } finally {
            replies.countDown()
            client.cancel()
            server.stop(0)
            caller.shutdownNow()
            workers.shutdownNow()
        }
    }

    @Test fun queuePositionOnlyComesFromValidMatchingServerFeedback() {
        val valid = JSONObject().put("requestId", "mine").put("state", "queued")
            .put("position", 7).put("total", 8).put("queueLimit", 32)
        assertEquals(7, ByparrClient.QueueStatus.parse(valid, "mine")?.position)
        assertNull(ByparrClient.QueueStatus.parse(valid, "someone-else"))
        assertNull(ByparrClient.QueueStatus.parse(JSONObject(valid.toString()).put("position", 0), "mine"))
        assertNull(ByparrClient.QueueStatus.parse(JSONObject(valid.toString()).put("total", 99), "mine"))
        assertEquals(0, ByparrClient.QueueStatus.parse(JSONObject(valid.toString())
            .put("state", "active").put("position", 0), "mine")?.position)
    }

    @Test fun fullVariantIdentitySurvivesSearchAndReleaseHttpHops() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = "http://127.0.0.1:${server.address.port}/v1"
        val version = "18.5.1.993608717-lite_beta-arm64-v8a"
        val search = "https://www.apkmirror.com/?post_type=app_release&s=%22com.google.android.inputmethod.latin%22+%2218.5.1.993608717%22"
        val release = "https://www.apkmirror.com/apk/google-inc/gboard/gboard-18-5-1-993608717-release/"
        val expected = "${release}gboard-lite64-android-apk-download/"
        val posts = AtomicInteger()
        server.createContext("/v1") { exchange ->
            posts.incrementAndGet()
            val url = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }).getString("url")
            val html = if (url == search)
                "<div id='content' class='search-area'><div class='appRow'><a href='$release'>Gboard 18.5.1.993608717 beta</a></div></div>"
            else "<a class='downloadButton variantsButton smooth-scroll' href='#downloads'>Scroll to available downloads</a>" +
                "<div class='table-row'><a href='$expected'>$version</a> arm64-v8a</div>" +
                "<div class='table-row'><a href='${release}gboard-lite32-android-apk-download/'>18.5.1.993608717-lite_beta-armeabi-v7a</a></div>"
            val body = JSONObject().put("status", "ok").put("solution", JSONObject()
                .put("status", 200).put("url", url).put("userAgent", "fixture browser")
                .put("contentType", "text/html").put("response", html)).toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = ByparrClient(true)
        try {
            val first = client.fetch(endpoint, search, version, listOf("arm64-v8a"))
            assertEquals(release, first.content.next)
            val second = client.fetch(endpoint, checkNotNull(first.content.next), version, listOf("arm64-v8a"))
            assertEquals(expected, second.content.next)
            assertEquals(2, posts.get())
            assertThrows(IOException::class.java) {
                client.fetch(endpoint, release, "18.5.1.993608717-lite_beta-armeabi-v7a", listOf("arm64-v8a"))
            }
            assertEquals(2, posts.get())
        } finally { client.cancel(); server.stop(0) }
    }
}
