package app.morphe.manager.ui.viewmodel

import app.morphe.manager.network.PlayStoreListing
import kotlin.test.*
import app.morphe.manager.domain.bundles.BundledAppTarget
import app.morphe.patcher.patch.AppTarget
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HomePlayVersionsTest {
    private fun listing(v: String, fresh: Boolean = true) = PlayStoreListing("com.example.app", null, null,
        version = v, versionSource = "ds5.application.version", versionObservedAt = 123L, versionVerified = fresh)
    @Test fun `numeric supported versions distinguish older matching and unverified without lexical ordering`() {
        assertEquals(PlayVersionVerdict.BEHIND, comparePlayVersion("1.9.0", listing("1.10.0")))
        assertEquals(PlayVersionVerdict.MATCHED, comparePlayVersion("1.10.0", listing("1.10.0")))
        assertEquals(PlayVersionVerdict.UNKNOWN, comparePlayVersion("1.11.0", listing("1.10.0")))
        assertEquals(PlayVersionVerdict.BEHIND, comparePlayVersion("1.99999999999999999999", listing("1.100000000000000000000")))
    }
    @Test fun `phone numeric and same channel discrepancies use component magnitude`() {
        for ((supported, official, expected) in listOf(
            Triple("6.1.2", "6.5", PlayVersionVerdict.BEHIND),
            Triple("32.18.0.100", "32.18.0.100", PlayVersionVerdict.MATCHED),
            Triple("3.72.0", "3.72.0", PlayVersionVerdict.MATCHED),
            Triple("18.2", "18.2", PlayVersionVerdict.MATCHED),
            Triple("6.38.9", "6.38.10", PlayVersionVerdict.BEHIND),
            Triple("2.0.3-rc0", "2.2.1-rc0", PlayVersionVerdict.BEHIND),
            Triple("2.2.1-rc9", "2.2.1-rc10", PlayVersionVerdict.BEHIND),
            Triple("2.2.1-RC0", "2.2.1-rc0", PlayVersionVerdict.MATCHED),
            Triple("1.10", "1.10.0", PlayVersionVerdict.MATCHED),
            Triple("1.10.0.0", "1.10", PlayVersionVerdict.MATCHED),
            Triple("1.10", "1.10.1", PlayVersionVerdict.BEHIND),
            Triple("1.10.1", "1.10", PlayVersionVerdict.UNKNOWN)
        )) assertEquals(expected, comparePlayVersion(supported, listing(official)), "$supported / $official")
    }
    @Test fun `cache failures missing support and ambiguous build or different channels never imply a match`() {
        assertEquals(PlayVersionVerdict.UNKNOWN, comparePlayVersion("1.10.0", listing("1.10.0", false)))
        assertEquals(PlayVersionVerdict.UNKNOWN, comparePlayVersion(null, listing("1.10.0")))
        assertEquals(PlayVersionVerdict.UNKNOWN, comparePlayVersion("1.10.0", null))
        for (v in listOf("1.10.0-arm64", "1.10.0 beta", "Varies with device", "1.10.0 (12)",
                "1.10.0-rc0", "1.10.0-lite_beta-arm64-v8a", "1..10", ""))
            assertEquals(PlayVersionVerdict.UNKNOWN, comparePlayVersion(v, listing("1.10.0")))
        assertEquals(PlayVersionVerdict.UNKNOWN, comparePlayVersion("1.10.0-beta1", listing("1.10.0-rc1")))
        assertEquals(PlayVersionVerdict.UNKNOWN, comparePlayVersion("1.10.0", listing("1.10.0").copy(versionSource = null)))
    }
    @Test fun `projection uses original packages and eligible targets with build independent version name feedback`() {
        val pkg = "com.example.app"
        fun target(v: String?, sdk: Int? = null, experimental: Boolean = false, codes: Set<Int>? = null) =
            BundledAppTarget(AppTarget(version = v, minSdk = sdk, isExperimental = experimental), 1, "Source", codes)
        val targets = mapOf(pkg to listOf(target("1.9.0"), target("1.10.0", experimental = true), target("1.11.0", sdk = 999)))
        val result = projectHomePlayVersions(targets, mapOf(pkg to listing("1.10.0")))
        assertEquals("1.9.0", result[pkg]?.supported)
        assertEquals(PlayVersionVerdict.BEHIND, result[pkg]?.verdict)
        assertFalse("com.example.clone" in result)
        assertTrue(projectHomePlayVersions(emptyMap(), mapOf(pkg to listing("1.10.0"))).isEmpty())
        assertNull(projectHomePlayVersions(mapOf(pkg to listOf(target(null))), emptyMap())[pkg]?.supported)
        assertEquals(PlayVersionVerdict.MATCHED, projectHomePlayVersions(mapOf(pkg to listOf(target("1.10.0", codes = setOf(42)))),
            mapOf(pkg to listing("1.10.0")))[pkg]?.verdict)
        assertEquals(PlayVersionVerdict.UNKNOWN, projectHomePlayVersions(targets,
            mapOf(pkg to listing("1.10.0").copy(packageName = "com.example.related")))[pkg]?.verdict)
    }
}
