package app.morphe.manager.network

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PlayStoreIconsTest {
    private val pkg = "com.cloudflare.onedotonedotonedotone"
    private val icon = "https://play-lh.googleusercontent.com/official-icon=s0-br30"
    private fun listing(id: String = pkg, image: String = icon) = """
        <link rel="canonical" href="https://play.google.com/store/apps/details?id=$id&amp;hl=en_US">
        <meta property="og:image" content="$image">
        <img src="https://play-lh.googleusercontent.com/screenshot">
    """.trimIndent()

    @Test fun `uses exact canonical package icon rather than screenshots or a related app`() {
        assertEquals("https://play-lh.googleusercontent.com/official-icon=w512-h512-rw", parsePlayStoreIcon(pkg, listing()))
        assertNull(parsePlayStoreIcon(pkg, listing("other.package")))
        assertNull(parsePlayStoreIcon(pkg, "<title>Not found</title>"))
        for (url in listOf("http://play-lh.googleusercontent.com/image", "https://play-lh.googleusercontent.com.attacker.test/image",
            "https://secret@play-lh.googleusercontent.com/image")) assertNull(parsePlayStoreIcon(pkg, listing(image = url)))
    }

    @Test fun `successful lookup is cached across resolver instances without another request`() = runBlocking {
        val dir = Files.createTempDirectory("play-icons").toFile()
        try {
            var calls = 0
            val resolver = PlayStoreIcons(dir) { url ->
                calls++; assertEquals("https://play.google.com/store/apps/details?id=$pkg&hl=en_US", url); listing()
            }
            val found = resolver.resolve(pkg)
            assertNotNull(found)
            assertEquals(found, resolver.resolve(pkg)); assertEquals(1, calls)
            val restarted = PlayStoreIcons(dir) { error("Cached lookup must not request the listing") }
            assertEquals(found, restarted.resolve(pkg))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `unavailable listing stays a placeholder without a persistent negative cache`() = runBlocking {
        val dir = Files.createTempDirectory("play-icons").toFile()
        try {
            var calls = 0
            val missing = PlayStoreIcons(dir) { calls++; "<title>Unavailable</title>" }
            assertNull(missing.resolve(pkg)); assertNull(missing.resolve(pkg)); assertEquals(1, calls)
            assertTrue(dir.listFiles().isNullOrEmpty())
            assertNull(PlayStoreIcons(dir) { throw java.io.IOException("offline") }.resolve(pkg))
            assertNotNull(PlayStoreIcons(dir) { listing() }.resolve(pkg))
            assertNull(missing.resolve("../../untrusted"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `simultaneous lookup shares result and cancellation does not poison cache`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("play-icons").toFile()
        try {
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
            val resolver = PlayStoreIcons(dir) { calls++; started.complete(Unit); release.await(); listing() }
            val first = async { resolver.resolve(pkg) }; started.await()
            val second = async { resolver.resolve(pkg) }; release.complete(Unit)
            assertEquals(first.await(), second.await()); assertEquals(1, calls)
            val cancelled = PlayStoreIcons(dir) { throw CancellationException("cancelled") }
            assertFailsWith<CancellationException> { cancelled.resolve("com.example.other") }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `warm and disk cached packages never wait for another package network request`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("play-icons").toFile()
        try {
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val resolver = PlayStoreIcons(dir) { url ->
                if (url.contains("com.example.slow")) { started.complete(Unit); release.await() }
                listing(if (url.contains("com.example.slow")) "com.example.slow" else pkg)
            }
            val expected = resolver.resolve(pkg)
            val slow = async { resolver.resolve("com.example.slow") }; started.await()
            try {
                assertEquals(expected, resolver.peekIcon(pkg))
                assertEquals(expected, resolver.resolve(pkg))
                val busyStarted = CompletableDeferred<Unit>()
                val restarted = PlayStoreIcons(dir) { url ->
                    if (url.contains("com.example.busy")) { busyStarted.complete(Unit); release.await(); listing("com.example.busy") }
                    else error("Cached package must not fetch")
                }
                val busy = async { restarted.resolve("com.example.busy") }
                busyStarted.await()
                assertEquals(expected, restarted.resolve(pkg))
                release.complete(Unit); busy.await()
            } finally { release.complete(Unit); slow.await() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun `metadata is package bound persisted and restored without losing legacy icons`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("play-icons").toFile()
        try {
            val html = listing() + """<script type="application/ld+json">{"@type":"SoftwareApplication","applicationCategory":"GAME_ACTION","name":"Game","author":{"name":"Studio"},"description":"Description","keywords":["Hunting","Offline"]}</script>"""
            val first = PlayStoreIcons(dir) { html }
            val metadata = first.resolveListing(pkg)
            assertEquals("GAME_ACTION", metadata?.categoryId)
            assertEquals("Studio", metadata?.developer)
            assertEquals("Description", metadata?.description)
            assertEquals(listOf("Hunting", "Offline"), metadata?.tags)
            val restarted = PlayStoreIcons(dir) { error("Metadata must be cached") }
            restarted.prime(setOf(pkg))
            assertEquals(metadata?.copy(versionVerified = false), restarted.listings.value[pkg])
            assertEquals(metadata?.iconUrl, restarted.peekIcon(pkg))
            assertNull(parsePlayStoreListing("com.example.other", html))
            dir.resolve("com.example.legacy.url").writeText(icon)
            assertNotNull(restarted.resolve("com.example.legacy"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `description preview decodes markup and retains only opening paragraphs`() {
        assertEquals("First & useful. Second paragraph.", storeDescriptionPreview(
            "<p>First &amp; useful.</p><p>Second paragraph.</p><p>Do not show this later paragraph.</p>"))
        assertEquals("First. Second.", storeDescriptionPreview("First.\n\nSecond.\n\nThird."))
        assertEquals("Plain description", storeDescriptionPreview("Plain description"))
        assertEquals(400, storeDescriptionPreview("x".repeat(2000))?.length)
        assertNull(storeDescriptionPreview("  "))
        assertNull(storeDescriptionPreview(null))
    }

    @Test fun `captured live WARP listing resolves the supplied official icon`() = runBlocking {
        val path = System.getenv("PLAY_STORE_LIVE_HTML")
        org.junit.Assume.assumeNotNull(path)
        val html = java.io.File(checkNotNull(path)).readText()
        val found = parsePlayStoreIcon(pkg, html)
        assertNotNull(found)
        assertTrue(found.contains("PDwXxcvulfxi2bH4UjcMGJ7i0CQ7_QNypYohkhhg02zE7FdNTNRyF21-ikrLM5kyvcmNxtr77Va-7sS3NLbpNg"))
    }
}
