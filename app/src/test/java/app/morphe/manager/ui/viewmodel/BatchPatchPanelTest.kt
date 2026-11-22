package app.morphe.manager.ui.viewmodel

import app.morphe.manager.patcher.patch.*
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.InstallerType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.*

class BatchPatchPanelTest {
    private fun patch(name: String) = PatchInfo(name, "Description", false,
        persistentListOf(CompatiblePackage("app", persistentSetOf("2"))), null)
    private fun bundle(uid: Int) = PatchBundleInfo.Scoped("Source $uid", "release", uid, true,
        patches = listOf(patch("Same")), compatible = listOf(patch("Same")),
        incompatible = emptyList(), universal = emptyList())
    private fun editor() = BatchPatchEdit("item", "app", "app", "App",
        bundles = listOf(bundle(1), bundle(2)),
        savedSelection = mapOf(1 to setOf("Same"), 2 to setOf("Same")),
        newPatches = emptyMap(), initialOptions = mapOf(1 to mapOf("Same" to mapOf("option" to "saved"))),
        installerType = InstallerType.STANDARD, apkArchitecture = ApkArchitecture.UNIVERSAL,
        allowIncompatible = false, version = "2", versionCode = 20)

    @Test fun `hide removes source from run while unhide restores choices and options`() {
        val edit = editor()
        edit.setSourceHidden(1, false, true)
        assertEquals(mapOf(2 to setOf("Same")), edit.runSelection)
        assertEquals("saved", edit.options[1]?.get("Same")?.get("option"))
        assertEquals(setOf("Same"), edit.selection[1])
        assertEquals(2, edit.allPatchesInfo.size) // hidden source remains recoverable
        edit.setSourceHidden(1, false, false)
        assertEquals(2, edit.totalSelectedCount)
        assertTrue(edit.hasMultipleBundles)
    }

    @Test fun `global and app hides compose and never revive an all hidden run`() {
        val edit = editor()
        edit.setSourceHidden(1, false, true)
        edit.setSourceHidden(1, true, true)
        edit.setSourceHidden(2, true, true)
        assertTrue(edit.runSelection.isEmpty())
        assertEquals(0, edit.totalSelectedCount)
        assertFalse(edit.hasMultipleBundles)
        edit.setSourceHidden(1, false, false)
        assertTrue(edit.runSelection.isEmpty()) // global hide still applies
        edit.setSourceHidden(1, true, false)
        assertEquals(mapOf(1 to setOf("Same")), edit.runSelection)
    }

    @Test fun `same label retains independent source selections`() {
        val edit = editor()
        edit.togglePatch(1, "Same")
        assertFalse("Same" in edit.selection[1].orEmpty())
        assertTrue("Same" in edit.selection[2].orEmpty())
        assertEquals("2", edit.version)
        assertEquals(20L, edit.versionCode)
    }
    private fun sectionEditor(): BatchPatchEdit {
        val custom = patch("Custom").copy(include = true)
        val universal = PatchInfo("Universal", null, false, null, null)
        val bundles = listOf(1, 2).map { uid -> PatchBundleInfo.Scoped("Source $uid", "release", uid, true,
            patches = listOf(custom, universal), compatible = listOf(custom), incompatible = emptyList(), universal = listOf(universal)) }
        return BatchPatchEdit("section", "app", "app", "App", bundles,
            savedSelection = mapOf(1 to setOf("Custom", "Universal"), 2 to setOf("Custom", "Universal")),
            newPatches = emptyMap(), initialOptions = mapOf(1 to mapOf("Custom" to mapOf("option" to "kept"))),
            installerType = InstallerType.STANDARD, apkArchitecture = ApkArchitecture.UNIVERSAL,
            allowIncompatible = false, version = "2", versionCode = 20)
    }

    @Test fun `section defaults restore and copy preserve other sections sources and options`() {
        val edit = sectionEditor()
        edit.resetToDefault(1, setOf("Universal"))
        assertEquals(setOf("Custom"), edit.selection[1])
        assertEquals(setOf("Custom", "Universal"), edit.selection[2])
        edit.restoreSaved(1, setOf("Universal"))
        assertEquals(setOf("Custom", "Universal"), edit.selection[1])
        edit.applyCopy(1, CopiedSelection(setOf("Unrelated"), emptyMap()), setOf("Universal"))
        assertEquals(setOf("Custom"), edit.selection[1])
        assertEquals("kept", edit.options[1]?.get("Custom")?.get("option"))
        edit.resetToDefault(1, setOf("Custom"))
        assertEquals(setOf("Custom"), edit.selection[1])
    }

    @Test fun `multi source universal section retains independent two step enable state`() {
        val edit = sectionEditor()
        val universal = edit.allPatchesInfo.first().second.filter { it.first.isUniversal }
        for (uid in listOf(1, 2)) edit.deselectAll(uid, universal)
        fun shown(uid: Int) = edit.allPatchesInfo.first { it.first.uid == uid }.second.filter { it.first.isUniversal }
        for (uid in listOf(1, 2)) edit.selectAll(uid, shown(uid))
        for (uid in listOf(1, 2)) {
            assertFalse("Universal" in edit.selection[uid].orEmpty())
            assertFalse(edit.selectAllHoldsUniversal(uid, shown(uid)))
        }
        assertTrue(edit.selectAllHoldsUniversal(1, edit.allPatchesInfo.first { it.first.uid == 1 }.second))
        for (uid in listOf(1, 2)) edit.selectAll(uid, shown(uid))
        for (uid in listOf(1, 2)) assertEquals(setOf("Custom", "Universal"), edit.selection[uid])
    }

    @Test fun `known failure is deselected on entry while duplicate source stays independent and deliberate retry works`() {
        val context = PatchFailureContext("app|2|20|arm64|sha", mapOf(1 to "one", 2 to "two"))
        val records = listOf(KnownPatchFailure(context.apk, 1, "one", "Same", "pattern absent"))
        val edit = BatchPatchEdit("item", "app", "app", "App", listOf(bundle(1), bundle(2)),
            savedSelection = mapOf(1 to setOf("Same"), 2 to setOf("Same")), newPatches = emptyMap(), initialOptions = emptyMap(),
            installerType = InstallerType.STANDARD, apkArchitecture = ApkArchitecture.UNIVERSAL, allowIncompatible = false,
            version = "2", versionCode = 20, failureContext = context, knownFailureRecords = records)
        assertEquals(emptySet(), edit.selection[1].orEmpty())
        assertEquals(setOf("Same"), edit.selection[2])
        edit.togglePatch(1, "Same")
        assertEquals(setOf("Same"), edit.selection[1])
        edit.togglePatch(1, "Same")
        assertEquals(emptySet(), edit.selection[1].orEmpty())
        assertEquals(mapOf(1 to 1), sourceFailureCounts(records, mapOf(1 to setOf("Other"))))
        edit.setSourceHidden(1, true, true)
        assertTrue(sourceFailureCounts(records, edit.runSelection).isEmpty())
    }

}
