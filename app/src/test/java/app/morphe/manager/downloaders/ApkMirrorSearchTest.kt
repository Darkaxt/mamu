package app.morphe.manager.downloaders

import java.net.URLEncoder
import java.io.IOException
import kotlin.test.*

class ApkMirrorSearchTest {
    private fun google(query: String) = "https://google.com/search?q=${URLEncoder.encode(query, "UTF-8")}&udm=14"
    private fun translated() = ApkMirrorSearch.translate(google("\"com.example.app\" \"3.72.0\" nodpi site:apkmirror.com"), "com.example.app", "3.72.0")

    @Test fun `multi site search keeps alternative stores instead of becoming a terminal APKMirror lookup`() {
        val request = google("com.miniclip.plagueinc 1.27.5 arm64-v8a apkmirror.com OR uptodown.com OR apkpure.com OR apkcube.com OR apkcombo.com")
        assertEquals(request, ApkMirrorSearch.translate(request, "com.miniclip.plagueinc", "1.27.5"))
    }

    @Test fun `Morphe fallback becomes an exact package version architecture search`() {
        val result = translated()
        assertEquals(DownloadSource.APK_MIRROR, DownloadPolicy(false).source(result))
        assertEquals("\"com.example.app\" \"3.72.0\"", ApkMirrorSearch.terms(result))
        assertTrue(result.contains("&dpi%5B%5D=nodpi"))
        val arm64 = ApkMirrorSearch.translate(google("\"com.example.app\" \"3.72.0\" arm64-v8a site:apkmirror.com"))
        assertTrue(arm64.contains("&arch%5B%5D=arm64-v8a&arch%5B%5D=universal"))
        assertFalse(ApkMirrorSearch.terms(arm64)!!.contains("arm64"))
        assertEquals("3.72.0", ApkMirrorSearch.requestedVersion(result))
        assertNull(DownloadPolicy(false).pageUrl(google("site:apkmirror.com")))
        assertEquals("3.72.0", ApkMirrorSearch.requestedVersion(ApkMirrorSearch.translate(google("Showly 3.72.0 site:apkmirror.com"))))
    }

    @Test fun `direct and wrapped store links retain escaped values while unrelated searches stay rejected`() {
        val direct = "https://www.apkmirror.com/apk/example/?key=a%2Fb%2Bc%26d"
        assertEquals(direct, ApkMirrorSearch.translate(direct))
        assertEquals(direct, ApkMirrorSearch.translate("https://www.google.com/url?q=${URLEncoder.encode(direct, "UTF-8")}"))
        for (url in listOf(google("site:apkmirror.com.evil.test"), google("weather tomorrow"),
            "https://google.com.evil.test/search?q=site:apkmirror.com", "https://user@google.com/search?q=site:apkmirror.com",
            "https://google.com/url?q=https%3A%2F%2Fevil.test%2Fapp.apk")) assertEquals(url, ApkMirrorSearch.translate(url))
    }

    @Test fun `search returns only exact version result rows and ignores navigation spoofed hosts and newer versions`() {
        val url = translated()
        val path = "/apk/example/showly/showly-3-72-0-release/"
        val html = "<title>Search - APKMirror</title>" +
            "<div id='sidebar'><div class='appRow'><a href='/apk/other/other-3-72-0-release/'>Sidebar 3.72.0</a></div></div>" +
            "<div id='content' class='search-area'>" +
            "<a href='/apk/other/other-3-72-0-release/'>Sidebar 3.72.0</a>" +
            "<div class='appRow'><h5><a href='$path'>Showly 3.72.0</a></h5><a href='$path#comments'>Comments</a></div>" +
            "<div class='appRow'><a href='/apk/example/showly/showly-3-72-0-1-release/'>Showly 3.72.0.1</a></div>" +
            "<div class='appRow'><a href='/apk/example/showly/showly-3-72-0-beta-release/'>Showly 3.72.0-beta</a></div>" +
            "<div class='appRow'><a href='https://apkmirror.com.evil.test/apk/evil/evil-release/'>Showly 3.72.0</a></div></div>"
        val page = RemotePage.parse(url, html)
        assertEquals(1, page.choices.size)
        assertEquals("https://www.apkmirror.com$path", page.next)
        assertTrue(page.choices.single().label.contains("3.72.0"))
    }

    @Test fun `multiple matching results remain choices and a missing requested version fails clearly`() {
        val html = "<title>Search - APKMirror</title><div id='content' class='search-area'>" +
            "<div class='appRow'><a href='/apk/example/app-3-72-0-release/'>Example 3.72.0</a></div>" +
            "<div class='appRow'><a href='/apk/example/app-3-72-0-other-release/'>Example 3.72.0</a></div></div>"
        val page = RemotePage.parse(translated(), html)
        assertNull(page.next)
        assertEquals(2, page.choices.size)
        assertFailsWith<IOException> { RemotePage.parse(translated(), "<div class='appRow'><a href='/apk/example/latest-release/'>Example 9.0</a></div>") }
    }

    @Test fun `live APKMirror advanced search row resolves the requested Showly release`() {
        val url = ApkMirrorSearch.translate(google("\"com.michaldrabik.showly2\" \"3.72.0\" nodpi site:apkmirror.com"))
        val html = javaClass.getResource("/apkmirror/search-showly-3.72.0.html")!!.readText()
        val page = RemotePage.parse(url, html)
        assertEquals("https://www.apkmirror.com/apk/trakt/showly/showly-track-shows-movies-3-72-0-release/", page.next)
        assertEquals(1, page.choices.size)
        assertTrue(page.choices.single().label.contains("3.72.0"))
    }

    @Test fun `Gboard searches release number and admits exact full lite ARM64 variant only`() {
        val version = "18.5.1.993608717-lite_beta-arm64-v8a"
        val search = ApkMirrorSearch.translate(google("com.google.android.inputmethod.latin $version arm64-v8a apkmirror.com"),
            "com.google.android.inputmethod.latin", version)
        assertEquals("\"com.google.android.inputmethod.latin\" \"18.5.1.993608717\"", ApkMirrorSearch.terms(search))
        val release = "https://www.apkmirror.com/apk/google-inc/gboard/gboard-18-5-1-993608717-release/"
        val searchHtml = "<title>Search</title><div id='content' class='search-area'>" +
            "<div class='appRow'><a href='$release'>Gboard 18.5.1.993608717 beta</a></div>" +
            "<div class='appRow'><a href='/apk/google-inc/gboard/gboard-18-5-2-993608717-release/'>Gboard 18.5.2.993608717</a></div></div>"
        assertEquals(release, RemotePage.parse(search, searchHtml, expectedVersion=version, supportedAbis=listOf("arm64-v8a")).next)
        fun row(v: String, suffix: String) = "<div class='table-row'><a href='${release}gboard-$suffix-android-apk-download/'>$v</a> nodpi</div>"
        val html = "<title>Gboard 18.5.1.993608717 - APKMirror</title>" +
            // Actual release-page button: scrolls within this document, never a download hop.
            "<a type='button' class='accent_bg btn btn-flat downloadButton variantsButton smooth-scroll' " +
            "data-google-interstitial='false' href='#downloads'>Scroll to available downloads</a>" +
            row("18.5.1.993608717-beta-arm64-v8a", "full") +
            row("18.5.1.993608717-lite_beta-armeabi-v7a", "lite32") + row(version, "lite64") +
            row("18.5.1.993608717-lite_beta-x86_64", "litex86")
        val page = RemotePage.parse(release, html, expectedVersion=version, supportedAbis=listOf("arm64-v8a"))
        assertEquals(1, page.choices.size)
        assertEquals("${release}gboard-lite64-android-apk-download/", page.next)
        assertTrue(page.choices.single().label.contains(version))
        assertFalse(page.choices.any { it.url == release })
        assertFailsWith<IOException> { RemotePage.parse(release, html,
            expectedVersion="18.5.1.993608717-lite_beta-armeabi-v7a", supportedAbis=listOf("arm64-v8a")) }
        val ambiguous = RemotePage.parse(release, html + row(version, "lite64-other-signature"),
            expectedVersion=version, supportedAbis=listOf("arm64-v8a"))
        assertNull(ambiguous.next)
        assertEquals(2, ambiguous.choices.size)
        assertFailsWith<IOException> { RemotePage.parse(release, row("18.5.2.993608717-lite_beta-arm64-v8a", "newer"),
            expectedVersion=version, supportedAbis=listOf("arm64-v8a")) }
    }

    @Test fun `ordinary beta versions remain exact while only ABI packaging broadens release discovery`() {
        assertEquals("3.72.0-beta", ApkMirrorSearch.releaseVersion("3.72.0-beta"))
        assertFalse(ApkMirrorSearch.matchesRelease("Example 3.72.0", "3.72.0-beta"))
        assertTrue(ApkMirrorSearch.matchesRelease("Gboard 18.5.1.993608717 beta", "18.5.1.993608717-lite_beta-arm64-v8a"))
        assertFalse(ApkMirrorSearch.matchesRelease("Gboard 18.5.1.993608717.1", "18.5.1.993608717-lite_beta-arm64-v8a"))
        assertFalse(ApkMirrorSearch.matchesRelease("Gboard 18.5.2.993608717", "18.5.1.993608717-lite_beta-arm64-v8a"))
    }

    @Test fun `release page picks the device's full nodpi APK without asking`() {
        // Row texts of the live YouTube Music 9.40.51 release page, which Mamu used to show as a picker
        val release = "https://www.apkmirror.com/apk/google-inc/youtube-music/youtube-music-9-40-51-release/"
        val rows = listOf(
            "BUNDLE 2 S 94051230 armeabi-v7a Android 10+ 320dpi", "BUNDLE 25 S 94051230 armeabi-v7a Android 8.0+ 480dpi",
            "APK 94051230 armeabi-v7a Android 8.0+ nodpi", "BUNDLE 30 S 94051240 arm64-v8a Android 12L+ 120-480dpi",
            "BUNDLE 26 S 94051240 arm64-v8a Android 10+ 480-640dpi", "BUNDLE 26 S 94051240 arm64-v8a Android 8.0+ 480-640dpi",
            "APK 94051240 arm64-v8a Android 8.0+ nodpi", "BUNDLE 25 S 94051270 x86 Android 12L+ 480dpi",
            "APK 94051270 x86 Android 8.0+ nodpi", "BUNDLE 25 S 94051280 x86_64 Android 8.0+ 480dpi",
            "APK 94051280 x86_64 Android 8.0+ nodpi")
        val html = "<title>YouTube Music 9.40.51 - APKMirror</title>" +
            "<a class='downloadButton variantsButton' href='#downloads'>Scroll to available downloads</a>" +
            rows.mapIndexed { i, row -> "<div class='table-row'><a href='youtube-music-9-40-51-${i + 1}-android-apk-download/'>9.40.51</a> $row</div>" }
                .joinToString("")
        fun next(abis: List<String>) = RemotePage.parse(release, html, expectedVersion = "9.40.51", supportedAbis = abis).next
        assertEquals("${release}youtube-music-9-40-51-7-android-apk-download/", next(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals("${release}youtube-music-9-40-51-3-android-apk-download/", next(listOf("armeabi-v7a", "armeabi")))
        assertEquals("${release}youtube-music-9-40-51-9-android-apk-download/", next(listOf("x86")))
        assertEquals("${release}youtube-music-9-40-51-11-android-apk-download/", next(listOf("x86_64", "x86")))
        // Only bundles for this device: the most compatible one is still chosen
        val bundles = rows.take(2).mapIndexed { i, row -> "<div class='table-row'><a href='v-${i + 1}-android-apk-download/'>9.40.51</a> $row</div>" }
        assertEquals("${release}v-2-android-apk-download/",
            RemotePage.parse(release, bundles.joinToString(""), expectedVersion = "9.40.51", supportedAbis = listOf("armeabi-v7a")).next)
        // Nothing for this architecture, or no device ABIs known: the variants stay a choice
        assertNull(RemotePage.parse(release, bundles.joinToString(""), expectedVersion = "9.40.51", supportedAbis = listOf("arm64-v8a")).next)
        assertNull(RemotePage.parse(release, html).next)
        assertEquals(11, RemotePage.parse(release, html).choices.size)
    }
}
