package app.morphe.manager.domain.bundles

import app.morphe.patcher.patch.AppTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class RecommendedAppVersionTest {
    @Test fun `source aware recommendation retains identity and installed source support`() {
        val old = BundledAppTarget(AppTarget(version = "7.13.0"), 1, "First")
        val newest = BundledAppTarget(AppTarget(version = "7.14.0"), 2, "Second")
        val targets = listOf(old, newest)
        assertEquals(newest, targets.recommendedEntry())
        assertEquals(newest, targets.reversed().recommendedEntry())
        assertEquals("7.13.0", targets.supportedFor(setOf(1))?.version)
        assertEquals("7.14.0", targets.supportedFor(setOf(99))?.version)
    }

    @Test fun `recommendation uses newest target across alphabetical source groups`() {
        val targets = listOf(
            BundledAppTarget(AppTarget(version = "32.13.2.100"), 1, "Debakar"),
            BundledAppTarget(AppTarget(version = "32.17.0.100"), 2, "Doom")
        )
        assertEquals("32.17.0.100", targets.recommended()?.version)
        assertEquals("32.17.0.100", targets.reversed().recommended()?.version)
    }

    @Test fun `Gboard recommendation never falls back to explicit unsupported architecture`() {
        val arm32 = BundledAppTarget(AppTarget(version = "18.5.1.993608717-lite_beta-armeabi-v7a"), 1, "source")
        val arm64 = arm32.copy(target = AppTarget(version = "18.5.1.993608717-lite_beta-arm64-v8a"))
        val abis = listOf("arm64-v8a")
        assertEquals(arm64.target.version, listOf(arm32, arm64).recommended(abis)?.version)
        assertEquals(arm64.target.version, listOf(arm64, arm32).recommended(abis)?.version)
        kotlin.test.assertNull(listOf(arm32).recommended(abis))
        kotlin.test.assertFalse(arm32.installableOnDevice(abis))
        kotlin.test.assertTrue(arm64.installableOnDevice(abis))
        assertEquals("2.0", listOf(BundledAppTarget(AppTarget(version = "2.0"), 1, "source")).recommended(abis)?.version)
    }

    @Test fun `an incompatible selected source cannot become an unconstrained single or batch search`() {
        val arm32 = BundledAppTarget(AppTarget(version = "18.5.1.993608717-lite_beta-armeabi-v7a"), 1, "32-bit source")
        val arm64 = BundledAppTarget(AppTarget(version = "18.5.1.993608717-lite_beta-arm64-v8a"), 2, "64-bit source")
        val all = listOf(arm32, arm64)
        val abis = listOf("arm64-v8a")
        kotlin.test.assertFalse(all.offered().deviceIncompatible(abis))
        kotlin.test.assertTrue(all.filter { it.bundleUid == 1 }.offered().deviceIncompatible(abis))
        kotlin.test.assertFalse(all.filter { it.bundleUid == 2 }.offered().deviceIncompatible(abis))
        kotlin.test.assertFalse(emptyList<BundledAppTarget>().deviceIncompatible(abis))
        kotlin.test.assertFalse(listOf(arm32.copy(target=AppTarget(version=null))).deviceIncompatible(abis))
    }
}
