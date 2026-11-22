package app.morphe.manager.domain.bundles

import app.morphe.manager.domain.apk.SavedApkInfo
import app.morphe.patcher.patch.AppTarget
import kotlin.test.*

class OriginalAcquisitionTest {
    private fun saved(version: String = "3.72.0", code: Long? = 843) =
        SavedApkInfo("original.apk", "/private/original.apk", version, code)
    private fun target(version: String? = "3.72.0", codes: Set<Int>? = null) =
        BundledAppTarget(AppTarget(version = version), 1, "source", buildCodes = codes)

    @Test fun `matching retained build bypasses acquisition in either mode`() {
        assertTrue(canReuseOriginal(saved(), "3.72.0", listOf(target(codes = setOf(843)))))
        assertFalse(canReuseOriginal(null, "3.72.0", listOf(target())))
    }
    @Test fun `old version downloads recommended version even if old patches still exist`() {
        assertFalse(canReuseOriginal(saved("3.71.0"), "3.72.0", listOf(target("3.71.0"), target())))
    }
    @Test fun `wrong or unknown build cannot silently satisfy a constrained target`() {
        assertFalse(canReuseOriginal(saved(code = 842), "3.72.0", listOf(target(codes = setOf(843)))))
        assertFalse(canReuseOriginal(saved(code = null), "3.72.0", listOf(target(codes = setOf(843)))))
        assertTrue(canReuseOriginal(saved(code = null), "3.72.0", listOf(target())))
        assertFalse(canReuseOriginal(saved(code = 842), "3.72.0", listOf(target(null), target(codes = setOf(843)))))
    }
    @Test fun `universal patches reuse the retained original without choosing another version`() {
        assertTrue(canReuseOriginal(saved(), null, listOf(target(null))))
        assertTrue(canReuseOriginal(saved(), null, emptyList()))
        assertFalse(canReuseOriginal(saved("3.71.0"), null, listOf(target())))
    }
    @Test fun `explicit version cannot be masked by an unrelated universal target`() {
        assertFalse(canReuseOriginal(saved("3.71.0"), "3.72.0", listOf(target(null), target())))
    }
}
