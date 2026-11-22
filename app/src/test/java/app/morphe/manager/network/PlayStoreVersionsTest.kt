package app.morphe.manager.network

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PlayStoreVersionsTest {
    private val pkg = "com.example.app"
    private fun page(version: String?, recordPackage: String = pkg): String {
        val app = JSONArray(); app.put(77, JSONArray().put(recordPackage))
        app.put(140, JSONArray().put(JSONArray().put(version?.let { JSONArray().put(it) } ?: org.json.JSONObject.NULL)))
        val data = JSONArray().put(org.json.JSONObject.NULL).put(JSONArray().put(org.json.JSONObject.NULL).put(org.json.JSONObject.NULL).put(app))
        return """<link rel="canonical" href="https://play.google.com/store/apps/details?id=$pkg">
            <meta property="og:image" content="https://play-lh.googleusercontent.com/icon">
            <script>AF_initDataCallback({key: 'ds:5', hash: '6', data:$data, sideChannel: {}});</script>"""
    }

    @Test fun `observed application version is package bound and missing fields stay unknown`() {
        assertEquals("1.10.0", parsePlayStoreListing(pkg, page("1.10.0"))?.version)
        assertNull(parsePlayStoreListing(pkg, page("1.10.0", "com.example.related"))?.version)
        assertNull(parsePlayStoreListing(pkg, page(null))?.version)
        assertNull(parsePlayStoreListing(pkg, page("1.10.0") + page("1.20.0"))?.version)
        val conflicting = page("1.10.0") + """<script type="application/ld+json">{"@type":"SoftwareApplication","url":"https://play.google.com/store/apps/details?id=$pkg","softwareVersion":"1.20.0"}</script>"""
        assertNull(parsePlayStoreListing(pkg, conflicting)?.version)
    }

    @Test fun `structured version requires its own exact canonical listing identity`() {
        fun structured(url: String?) = page(null) + """<script type="application/ld+json">{"@type":"SoftwareApplication","url":${url?.let { "\"$it\"" } ?: "null"},"softwareVersion":"1.20.0"}</script>"""
        assertNull(parsePlayStoreListing(pkg, structured(null))?.version)
        assertNull(parsePlayStoreListing(pkg, structured("https://play.google.com/store/apps/details?id=com.example.related"))?.version)
        assertEquals("1.20.0", parsePlayStoreListing(pkg, structured("https://play.google.com/store/apps/details?id=$pkg"))?.version)
    }

    @Test fun `cached observations restore immediately but require current verification and failed refresh stays unknown`() = runBlocking {
        val dir = Files.createTempDirectory("play-versions").toFile()
        try {
            val first = PlayStoreIcons(dir) { page("1.10.0") }.resolveListing(pkg)!!
            assertTrue(first.versionVerified); assertNotNull(first.versionObservedAt)
            var calls = 0
            val restarted = PlayStoreIcons(dir) { calls++; throw java.io.IOException("offline") }
            restarted.prime(setOf(pkg))
            assertEquals("1.10.0", restarted.listings.value[pkg]?.version)
            assertFalse(restarted.listings.value[pkg]!!.versionVerified)
            assertNotNull(restarted.peekIcon(pkg))
            restarted.resolveListing(pkg, refreshVersion = true)
            restarted.resolveListing(pkg, refreshVersion = true)
            assertEquals(1, calls); assertFalse(restarted.listings.value[pkg]!!.versionVerified)
            val online = PlayStoreIcons(dir) { page("1.11.0") }
            assertEquals("1.11.0", online.resolveListing(pkg, refreshVersion = true)?.version)
            assertTrue(online.listings.value[pkg]!!.versionVerified)
        } finally { dir.deleteRecursively() }
    }

    @Test fun `captured live listings extract versions without mistaking ratings or SDKs for YouTube version`() {
        val path = System.getenv("MORPHE_PLAY_EVIDENCE")
        org.junit.Assume.assumeNotNull(path)
        for ((id, expected) in listOf("com.amazon.mShop.android.shopping" to "32.18.0.100",
            "com.cloudflare.onedotonedotonedotone" to "6.38.10", "com.google.android.youtube" to null)) {
            assertEquals(expected, parsePlayStoreListing(id, File(path, "$id.html").readText())?.version)
        }
    }
}
