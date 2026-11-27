/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.api

import app.morphe.manager.network.dto.ReleaseAsset
import app.morphe.manager.network.dto.parseTimestamp
import app.morphe.manager.network.service.HttpService
import app.morphe.manager.network.utils.APIResponse
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.url
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

/**
 * Release times reach the app from GitHub, from source manifests and from the Morphe API. Each is
 * kept in UTC, and its text is part of the key a refused release is remembered by on disk.
 */
class ReleaseTimestampTest {
    @Test
    fun `timestamps of every shape are read in UTC`() {
        assertEquals("2024-01-15T12:00", parseTimestamp("2024-01-15T12:00:00Z").toString())
        assertEquals("2024-01-15T10:00", parseTimestamp("2024-01-15T12:00:00+02:00").toString())
        assertEquals("2024-01-15T17:00", parseTimestamp("2024-01-15T12:00:00-05:00").toString())
        assertEquals("2024-01-15T12:00:30", parseTimestamp("2024-01-15T12:00:30").toString())
    }

    @Test
    fun `a manifest gives its release time without an offset`() {
        val asset = Json.decodeFromString<ReleaseAsset>(
            """{"download_url":"https://example.com/patches.mpp","created_at":"2026-11-03T12:13:45","description":"","version":"v1.0.0"}"""
        )

        assertEquals("2026-11-03T12:13:45", asset.createdAt.toString())
        assertEquals(1_793_708_025_000, asset.createdAtMillis)
    }

    @Test
    fun `manifest UTC and offset timestamps decode and round trip to the same publication instant`() {
        val timestamps = listOf("2026-11-06T00:00:00Z", "2026-11-06T02:00:00+02:00",
            "2026-11-05T19:00:00-05:00", "2026-11-06T00:00:00")
        for (timestamp in timestamps) {
            val manifest = """{"download_url":"https://example.com/patches.mpp","created_at":"$timestamp","description":"","version":"v1.0.0"}"""
            val asset = Json.decodeFromString<ReleaseAsset>(manifest)
            assertEquals("2026-11-06T00:00", asset.createdAt.toString(), timestamp)
            assertEquals(1_793_923_200_000, asset.createdAtMillis, timestamp)
            assertEquals(asset, Json.decodeFromString<ReleaseAsset>(Json.encodeToString(asset)))
        }
    }

    @Test
    fun `manifest timestamps preserve fractions and reject missing or malformed evidence`() {
        fun decode(timestamp: String) = Json.decodeFromString<ReleaseAsset>(
            """{"download_url":"https://example.com/patches.mpp","created_at":"$timestamp","description":"","version":"v1.0.0"}""")
        assertEquals("2026-11-06T00:00:00.123456", decode("2026-11-06T02:00:00.123456+02:00").createdAt.toString())
        for (invalid in listOf("", "not-a-date", "2026-02-30T00:00:00Z")) assertFails(invalid) { decode(invalid) }
    }

    @Test
    fun `real HTTP manifest requests admit valid UTC dates and keep malformed dates as failures`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val client = HttpClient(OkHttp)
        val timestamps = listOf("2026-11-06T00:00:00Z", "2026-11-06T02:00:00+02:00", "")
        timestamps.forEachIndexed { index, timestamp -> server.createContext("/manifest/$index") { exchange ->
            val body = """{"download_url":"https://example.com/patches.mpp","created_at":"$timestamp","description":"","version":"v1.0.0"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } }
        server.start()
        try {
            val http = HttpService(Json, client, okhttp3.OkHttpClient(), app.morphe.manager.network.service.AlwaysOnline)
            for (index in timestamps.indices) {
                val response = http.request<ReleaseAsset>(retryOn429 = false) {
                    url("http://127.0.0.1:${server.address.port}/manifest/$index")
                }
                if (timestamps[index].isEmpty()) assertIs<APIResponse.Failure<*>>(response)
                else assertEquals(1_793_923_200_000, assertIs<APIResponse.Success<ReleaseAsset>>(response).data.createdAtMillis)
            }
        } finally { client.close(); server.stop(0) }
    }
}
