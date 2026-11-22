package app.morphe.manager.patcher.patch

import kotlinx.collections.immutable.*
import kotlin.test.*
import app.morphe.manager.domain.bundles.installedPatchPublicationDay

class PatchReleaseAgeTest {
    private val apkDay = "2026-10-13"
    private fun patch(versions: Set<String>? = null, pkg: String? = "app") = PatchInfo(
        "OCR", null, false, persistentListOf(CompatiblePackage(pkg, versions?.toPersistentSet())), null)
    private fun row(uid: Int = 1, selected: Boolean = true, p: PatchInfo = patch()) = PanelPatch(
        uid, p, selected, p.versionScope("app", "18", 123), 0)
    private fun source(uid: Int = 1, day: String? = "2026-10-01") = PatchReleaseAgeSource(uid, "Adobo", "1.5.0", day)
    @Test fun `warn only selected app-specific unversioned patches from older installed releases`() {
        assertEquals(listOf("1:OCR"), patchReleaseAgeWarnings("app", "18", apkDay, listOf(row()), listOf(source())).map { it.patchKey })
        for (p in listOf(patch(setOf("18")), patch(setOf("17")), patch(pkg = null), patch(pkg = "other"))) {
            assertTrue(patchReleaseAgeWarnings("app", "18", apkDay, listOf(row(p = p)), listOf(source())).isEmpty())
        }
        assertTrue(patchReleaseAgeWarnings("app", "18", apkDay, listOf(row(selected = false)), listOf(source())).isEmpty())
        for (day in listOf(null, "invalid", apkDay, "2026-10-14")) {
            assertTrue(patchReleaseAgeWarnings("app", "18", apkDay, listOf(row()), listOf(source(day = day))).isEmpty())
        }
        assertTrue(patchReleaseAgeWarnings("app", "18", null, listOf(row()), listOf(source())).isEmpty())
        assertTrue(patchReleaseAgeWarnings("app", "18", apkDay, emptyList(), listOf(source())).isEmpty())
    }
    @Test fun `duplicate names retain source identities and disabling preserves other selections`() {
        val warnings = patchReleaseAgeWarnings("app", "18", apkDay, listOf(row(), row(2)), listOf(source(), source(2, "2026-10-14")))
        val selected = mapOf(1 to setOf("OCR", "Keep"), 2 to setOf("OCR"))
        assertEquals(mapOf(1 to setOf("Keep"), 2 to setOf("OCR")), selected.withoutAgeWarnings(warnings))
        assertEquals(mapOf(1 to setOf("OCR", "Keep"), 2 to setOf("OCR")), selected)
    }
    @Test fun `review and home do not start patching and confirmation refreshes changed warnings`() {
        val first = patchReleaseAgeWarnings("app", "18", apkDay, listOf(row()), listOf(source()))
        val second = patchReleaseAgeWarnings("app", "18", apkDay, listOf(row(2)), listOf(source(2)))
        var live = first
        var started = 0
        var reviewed = emptyList<PatchReleaseAgeWarning>()
        var home = 0
        val gate = PatchReleaseAgeGate()
        fun request() = gate.request({ live }, { started++ }, { reviewed = it }, { home++ })
        request(); live = second; gate.proceed()
        assertEquals(0, started); assertEquals(second, gate.pending?.warnings)
        gate.review(); assertEquals(second, reviewed); assertEquals(0, started); assertNull(gate.pending)
        request(); gate.home(); assertEquals(1, home); assertEquals(0, started)
        request(); gate.cancel(); assertEquals(0, started); assertNull(gate.pending)
        request(); gate.proceed(); assertEquals(1, started)
        live = emptyList(); request(); assertEquals(2, started); assertNull(gate.pending)
    }
    @Test fun `review refreshes newly suspicious rows before it can disable them`() {
        var live = patchReleaseAgeWarnings("app", "18", apkDay, listOf(row()), listOf(source()))
        var disabled = emptyList<PatchReleaseAgeWarning>()
        val gate = PatchReleaseAgeGate()
        gate.request({ live }, { fail("Must not start") }, { disabled = it }, {})
        live = patchReleaseAgeWarnings("app", "18", apkDay, listOf(row(2)), listOf(source(2)))
        gate.review()
        assertTrue(disabled.isEmpty()); assertEquals(live, gate.pending?.warnings)
        gate.review(); assertEquals(live, disabled)
    }
    @Test fun `batch review matches full version and build even for the same original package`() {
        val warnings = patchReleaseAgeWarnings("app", "18", apkDay, listOf(row()), listOf(source()), versionCode = 123)
        assertEquals(warnings, warnings.forApk("app", "18", 123))
        assertTrue(warnings.forApk("app", "18", 124).isEmpty())
        assertTrue(warnings.forApk("app", "17", 123).isEmpty())
        assertTrue(warnings.forApk("other", "18", 123).isEmpty())
    }
    @Test fun `source publication evidence is installed-release bound and cannot use import clocks`() {
        val record = """{"version":"release-1","day":"2026-09-19"}"""
        assertEquals("2026-09-19", installedPatchPublicationDay(record, "release-1"))
        assertNull(installedPatchPublicationDay(record, "release-2"))
        assertNull(installedPatchPublicationDay(record, null))
        assertNull(installedPatchPublicationDay(null, "release-1"))
        assertNull(installedPatchPublicationDay("""{"createdAt":1792198519795,"version":"release-1"}""", "release-1"))
        assertNull(installedPatchPublicationDay("""{"version":"release-1","day":"2026-02-30"}""", "release-1"))
    }
}
