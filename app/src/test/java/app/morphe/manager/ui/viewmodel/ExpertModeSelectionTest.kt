package app.morphe.manager.ui.viewmodel

import app.morphe.manager.patcher.patch.*
import app.morphe.manager.ui.model.SelectedApp
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.InstallerType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class ExpertModeSelectionTest {
    private fun patch(name: String, default: Boolean = true) = PatchInfo(name, null, default, null, null)
    private val custom = patch("Custom")
    private val universal = patch("Universal", false)
    private val sources = listOf(1, 2).map { uid -> PatchBundleInfo.Scoped("Source $uid", "release", uid, true,
        listOf(custom, universal), listOf(custom), emptyList(), listOf(universal)) }
    private val context = PatchFailureContext("exact-apk", mapOf(1 to "release", 2 to "release"))
    private val app = SelectedApp.Installed("app", "2", 20)
    private fun editor(scope: CoroutineScope, records: List<KnownPatchFailure> = emptyList()) =
        ExpertModeSelection(scope, { _, _, _, _ -> }, MutableStateFlow(records))
    private fun open(editor: ExpertModeSelection) = editor.open(app, sources, emptySet(), emptySet(),
        mapOf(1 to setOf("Custom", "Universal"), 2 to setOf("Custom")),
        mapOf(1 to mapOf("Custom" to mapOf("option" to "kept"))), emptyMap(),
        InstallerType.STANDARD, ApkArchitecture.UNIVERSAL, false, context)

    @Test fun `hidden source keeps choices but cannot enter the run or its count`() = runBlocking {
        val editor = editor(this); open(editor)
        editor.setSourceHidden(1, false, true)
        editor.awaitVisibilityWrites()
        assertEquals(mapOf(2 to setOf("Custom")), editor.runSelection)
        assertEquals(1, editor.totalSelectedCount)
        assertEquals(setOf("Custom", "Universal"), editor.patches[1])
        assertEquals("kept", editor.options[1]?.get("Custom")?.get("option"))
        editor.setSourceHidden(1, false, false); editor.awaitVisibilityWrites()
        assertEquals(3, editor.totalSelectedCount)
    }

    @Test fun `section defaults and restore preserve other source section and option values`() = runBlocking {
        val editor = editor(this); open(editor)
        editor.resetToDefault(1, setOf("Universal"))
        assertEquals(setOf("Custom"), editor.patches[1])
        assertEquals(setOf("Custom"), editor.patches[2])
        editor.restoreSaved(1, setOf("Universal"))
        assertEquals(setOf("Custom", "Universal"), editor.patches[1])
        assertEquals("kept", editor.options[1]?.get("Custom")?.get("option"))
        editor.toggle(1, "Custom")
        assertFalse("Custom" in editor.patches[1].orEmpty())
        assertTrue("Custom" in editor.patches[2].orEmpty())
    }

    @Test fun `clearing and reopening resets editor permissions and warning context`() = runBlocking {
        val editor = editor(this); open(editor)
        editor.allowUnsupported(); editor.setSourceHidden(1, true, true); editor.awaitVisibilityWrites()
        editor.clear()
        assertNull(editor.app); assertNull(editor.failureContext)
        assertTrue(editor.runSelection.isEmpty()); assertTrue(editor.options.isEmpty())
        assertFalse(editor.allowIncompatible)
        open(editor)
        assertEquals(3, editor.totalSelectedCount)
        assertTrue(editor.globalHiddenSources.isEmpty())
        assertEquals(context, editor.failureContext)
    }
}
