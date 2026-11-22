package app.morphe.manager.downloaders

import app.morphe.manager.domain.bundles.BundledAppTarget
import app.morphe.patcher.patch.AppTarget
import kotlin.test.*

class OriginalDownloadTargetTest {
    @Test fun `interactive Play requests retain the selected source build instead of another source build`() {
        val rows = listOf(target("2", setOf(20)), target("2", setOf(21)).copy(bundleUid = 2), target("1", setOf(10)))
        assertEquals(setOf(20), downloadBuildCodes(rows, "2", 1))
        assertEquals(setOf(21), downloadBuildCodes(rows, "2", 2))
        assertEquals(setOf(20, 21), downloadBuildCodes(rows, "2", null))
        assertNull(downloadBuildCodes(rows + target("2"), "2", 1))
    }
    private fun target(version: String?, codes: Set<Int>? = null, minSdk: Int? = null,
                       experimental: Boolean = false, enabled: Boolean = false) =
        BundledAppTarget(AppTarget(version=version, minSdk=minSdk, isExperimental=experimental), 1, "source", codes, experimentalEnabled = enabled)
    @Test fun `updates require newer explicit supported installable targets`() {
        assertNull(supportedDownloadTarget("app", "1", listOf(target(null)), 35))
        assertNull(supportedDownloadTarget("app", null, listOf(target("2")), 35))
        assertNull(supportedDownloadTarget("app", "2", listOf(target("2")), 35))
        assertNull(supportedDownloadTarget("app", "3", listOf(target("2")), 35))
        val data=listOf(target("2", setOf(20)), target("3", minSdk=36), target("4", experimental=true))
        assertEquals(OriginalDownloadTarget("app", "2", setOf(20)), supportedDownloadTarget("app", "1", data, 35))
    }
    @Test fun `pinned manifest and current build restrictions both apply`() {
        val pinned=OriginalDownloadTarget("app", "2", setOf(20,21))
        val current=listOf(target("2",setOf(21)))
        assertFalse(pinned.accepts(ArchiveIdentity("name","other","2",21)))
        assertFalse(pinned.accepts(ArchiveIdentity("name","app","3",21)))
        assertFalse(pinned.acceptedByCurrent(ArchiveIdentity("name","app","2",20),current,35))
        assertTrue(pinned.acceptedByCurrent(ArchiveIdentity("name","app","2",21),current,35))
        assertFalse(pinned.stillSupported(emptyList(),35))
        assertFalse(pinned.stillSupported(listOf(target("2",setOf(22))),35))
    }
    @Test fun `equivalent sources union allowed builds without admitting unrelated versions`() {
        val targets = listOf(target("2",setOf(20)), target("2",setOf(21)), target("1",setOf(10)))
        val pinned = assertNotNull(supportedDownloadTarget("app","1",targets,35))
        assertEquals(setOf(20,21),pinned.buildCodes)
        assertFalse(pinned.accepts(ArchiveIdentity("name","app","2",22)))
        assertEquals(null,supportedDownloadTarget("app","1",targets + target("2"),35)?.buildCodes)
        assertNull(supportedDownloadTarget("app","1",listOf(target("2",experimental=true)),35))
        assertNotNull(supportedDownloadTarget("app","1",listOf(target("2",experimental=true,enabled=true)),35))
        assertFalse(pinned.acceptedByCurrent(ArchiveIdentity("name","app","2",20),listOf(target("2",minSdk=36)),35))
    }
    @Test fun `resume never replays uncertain browser actions and cancellation wins`() {
        assertEquals(OriginalResume.RESOLVE,originalResume(null,false,false,false))
        assertEquals(OriginalResume.INTERRUPTED,originalResume("resolving",false,false,false))
        assertEquals(OriginalResume.TRANSFER,originalResume("resolving",false,true,false))
        assertEquals(OriginalResume.READY,originalResume("retaining",false,false,true))
        assertEquals(OriginalResume.CANCEL,originalResume("retaining",true,true,true))
    }
    @Test fun `variant selection rejects ambiguity instead of guessing a build`() {
        val arm=RemotePage.Choice("https://site/arm", "arm64-v8a")
        val x86=RemotePage.Choice("https://site/x86", "x86_64")
        assertEquals(arm,backgroundDownloadChoice(listOf(arm,x86),listOf("arm64-v8a")))
        assertNull(backgroundDownloadChoice(listOf(arm,arm.copy(url="other")),listOf("arm64-v8a")))
    }

    @Test fun `queued Gboard eligibility keeps ABI version and build identity pinned`() {
        val arm32 = target("18.5.1.993608717-lite_beta-armeabi-v7a", setOf(176049545))
        val arm64 = target("18.5.1.993608717-lite_beta-arm64-v8a", setOf(176049546))
        val abis = listOf("arm64-v8a")
        val pinned = assertNotNull(supportedDownloadTarget("gboard", "18.4", listOf(arm32,arm64), 35, abis))
        assertEquals(arm64.target.version, pinned.version)
        assertNull(supportedDownloadTarget("gboard", "18.4", listOf(arm32), 35, abis))
        assertFalse(OriginalDownloadTarget("gboard", checkNotNull(arm32.target.version), arm32.buildCodes).stillSupported(listOf(arm32),35,abis))
        assertTrue(pinned.accepts(ArchiveIdentity("Gboard","gboard",arm64.target.version,176049546)))
        assertFalse(pinned.accepts(ArchiveIdentity("Gboard","gboard",arm32.target.version,176049545)))
        assertFalse(pinned.accepts(ArchiveIdentity("Gboard","gboard",arm64.target.version,176049545)))
        assertFalse(pinned.accepts(ArchiveIdentity("Gboard","other",arm64.target.version,176049546)))
    }
}
