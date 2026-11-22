package app.morphe.manager.patcher.patch

import kotlin.test.*

class VersionSupportTest {
    private val patch = PatchInfo("Patch", null, true, null, null)
    private fun source(uid: Int, experimental: Boolean = false, mismatch: Boolean = false) =
        PatchBundleInfo.Scoped("Source $uid", "1", uid, true, listOf(patch),
            compatible = if (mismatch) emptyList() else listOf(patch),
            incompatible = if (mismatch) listOf(patch) else emptyList(),
            universal = emptyList(), isVersionExperimental = experimental)

    @Test fun `stable supporting source settles aggregate experimental status`() {
        val stable = source(1)
        val experimental = source(2, experimental = true)
        assertFalse(VersionSupport(listOf(stable, experimental)).isExperimental)
        assertTrue(VersionSupport(listOf(experimental)).isExperimental)
        assertEquals(setOf(2), VersionSupport(listOf(experimental)).experimentalUids)
    }

    @Test fun `universal patches do not approve a version rejected by the chosen source`() {
        val unsupported = source(1, mismatch = true)
        val universal = source(2).copy(compatible = emptyList(), universal = listOf(patch))
        assertTrue(VersionSupport(listOf(unsupported, universal)).isMismatch)
        assertFalse(VersionSupport(listOf(source(3), unsupported)).isMismatch)
        assertTrue(VersionSupport(listOf(universal)).hasPatches)
        assertFalse(VersionSupport(emptyList()).hasPatches)
    }
}
