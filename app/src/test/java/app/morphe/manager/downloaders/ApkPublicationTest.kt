package app.morphe.manager.downloaders

import org.jsoup.Jsoup
import kotlin.test.*
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import app.morphe.manager.patcher.patch.*
import kotlinx.collections.immutable.*

class ApkPublicationTest {
    private val url = "https://www.apkmirror.com/apk/google-inc/gboard/gboard-18-release/gboard-18-arm64-android-apk-download/"
    @Test fun `exact APKMirror file metadata wins over unrelated dates`() {
        val html = """<span data-utcdate="10/17/2026 01:00 UTC"></span>
            <div id="file"><div class="appspec-row"><div>Uploaded</div>
            <span data-utcdate="10/13/2026 08:26 UTC"></span></div></div>"""
        val actual = ApkPublication.parse(url, Jsoup.parse(html), "18")
        assertEquals("2026-10-13", actual?.day)
        assertEquals(url, actual?.pageUrl)
        assertNull(ApkPublication.parse(url.substringBeforeLast('/') + "/download/", Jsoup.parse(html), "18"))
        assertNull(ApkPublication.parse("https://www.apkmirror.com/?s=gboard", Jsoup.parse(html), "18"))
    }
    @Test fun `only matching application publication metadata is accepted`() {
        val page = "https://example.en.uptodown.com/android/download/123"
        fun parse(body: String) = ApkPublication.parse(page, Jsoup.parse("<script type='application/ld+json'>$body</script>"), "18")
        assertEquals("2026-10-13", parse("""{"@type":"SoftwareApplication","softwareVersion":"18","datePublished":"2026-10-13T08:26:00Z"}""")?.day)
        assertNull(parse("""{"@type":"SoftwareApplication","softwareVersion":"17","datePublished":"2026-10-13"}"""))
        assertNull(parse("""{"@type":"Article","datePublished":"2026-10-13"}"""))
        assertNull(parse("""{"@type":"SoftwareApplication","softwareVersion":"18","dateModified":"2026-10-13"}"""))
        assertNull(parse("""{"@type":"SoftwareApplication","softwareVersion":"18","datePublished":"invalid"}"""))
    }
    @Test fun `conflicting publication days and index pages stay unknown`() {
        val html = """<div id="file"><span data-utcdate="10/13/2026 08:26 UTC"></span>
            <span data-utcdate="10/14/2026 08:26 UTC"></span></div>"""
        assertNull(ApkPublication.parse(url, Jsoup.parse(html), "18"))
        assertNull(ApkPublication.parse(url.substringBefore("/gboard-18-arm64"), Jsoup.parse(html), "18"))
        assertNull(ApkPublication.parse(url, Jsoup.parse("<div id='file'><span data-utcdate='02/30/2026 08:26 UTC'></span></div>"), "18"))
    }
    @Test fun `actual identity keys never share different versions or builds`() {
        val key = ApkPublications.key("app", "18", 123)
        assertNotNull(key)
        assertNotEquals(key, ApkPublications.key("app", "17", 123))
        assertNotEquals(key, ApkPublications.key("app", "18", 124))
        assertNotEquals(key, ApkPublications.key("other", "18", 123))
        assertNull(ApkPublications.key("app", "18", null))
    }
    @Test fun `parsed exact build publication survives storage and drives only its admitted selection warning`() {
        val values = mutableMapOf<String, String?>()
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, String?>()
                    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, action, fields ->
                        when (action.name) {
                            "putString" -> { pending[fields[0] as String] = fields[1] as String?; proxy }
                            "commit" -> { values.putAll(pending); true }
                            else -> error(action.name)
                        }
                    }
                }
                else -> error(method.name)
            }
        } as SharedPreferences
        val publication = RemotePage.parse(url, """<div id="file"><span data-utcdate="10/13/2026 08:26 UTC"></span></div>""",
            expectedVersion = "18").publication
        ApkPublications(prefs).remember(ArchiveIdentity("App", "app", "18", 123), publication)
        val restored = ApkPublications(prefs)
        assertEquals(publication, restored.get("app", "18", 123))
        assertNull(restored.get("app", "18", 124))
        assertNull(restored.get("app", "17", 123))
        ApkPublications(prefs).remember(ArchiveIdentity("App", "app", "18", 123), null)
        assertEquals(publication, restored.get("app", "18", 123))
        val patch = PatchInfo("OCR", null, false, persistentListOf(CompatiblePackage("app", null)), null)
        val rows = listOf(PanelPatch(1, patch, true, PatchVersionScope.COMPATIBLE, 0))
        val warnings = patchReleaseAgeWarnings("app", "18", restored.get("app", "18", 123)?.day, rows,
            listOf(PatchReleaseAgeSource(1, "Adobo", "1.5.0", "2026-10-01")))
        assertEquals(listOf("1:OCR"), warnings.map { it.patchKey })
    }
}
