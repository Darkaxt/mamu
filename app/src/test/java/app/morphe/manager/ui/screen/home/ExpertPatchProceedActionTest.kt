package app.morphe.manager.ui.screen.home

import app.morphe.manager.patcher.patch.*
import kotlin.test.*

class ExpertPatchProceedActionTest {
    private fun row(uid: Int, scope: PatchVersionScope, selected: Boolean = true) = PanelPatch(
        uid, PatchInfo("Same patch", null, false, null, null), selected, scope, 0
    )

    @Test fun `deselecting an unsupported patch replaces the immutable footer callback`() {
        val verified = row(1, PatchVersionScope.VERIFIED)
        val older = row(2, PatchVersionScope.UNSUPPORTED)
        var dispatched = emptyList<PanelPatch>()
        val proceed: (List<PanelPatch>) -> Unit = { dispatched = it }
        var retained = expertPatchProceedAction("Patch", listOf(verified, older), proceed)
        retained.onClick()
        assertEquals(listOf(older), selectedUnsupportedPatches(dispatched))

        val current = expertPatchProceedAction("Patch", listOf(verified, older.copy(selected = false)), proceed)
        // An immutable action consumer must see the changed callback, with the same
        // label/enabled/icon. Function-reference equality used to hide this change.
        assertNotEquals(retained, current)
        if (retained != current) retained = current
        retained.onClick()
        assertEquals(listOf(verified), dispatched)
        assertTrue(selectedUnsupportedPatches(dispatched).isEmpty())
        assertEquals(app.morphe.manager.ui.screen.shared.SemanticTone.Success,
            selectionCompatibilityTone(dispatched))
    }

    @Test fun `selecting an unsupported patch replaces a previously compatible callback`() {
        val verified = row(1, PatchVersionScope.VERIFIED)
        val older = row(2, PatchVersionScope.UNSUPPORTED)
        var dispatched = emptyList<PanelPatch>()
        val proceed: (List<PanelPatch>) -> Unit = { dispatched = it }
        val previous = expertPatchProceedAction("Patch", listOf(verified, older.copy(selected = false)), proceed)
        val current = expertPatchProceedAction("Patch", listOf(verified, older), proceed)
        assertNotEquals(previous, current)
        current.onClick()
        assertEquals(listOf(older.key), selectedUnsupportedPatches(dispatched).map { it.key })
    }

    @Test fun `current visible source identities and empty selection control dispatch`() {
        val first = row(1, PatchVersionScope.VERIFIED)
        val duplicate = row(2, PatchVersionScope.VERIFIED)
        var dispatched = emptyList<PanelPatch>()
        val proceed: (List<PanelPatch>) -> Unit = { dispatched = it }
        val both = expertPatchProceedAction("Patch", listOf(first, duplicate), proceed)
        both.onClick()
        assertEquals(listOf(first.key, duplicate.key), dispatched.map { it.key })
        val current = expertPatchProceedAction("Patch", listOf(duplicate), proceed)
        assertNotEquals(both, current)
        current.onClick()
        assertEquals(listOf(duplicate.key), dispatched.map { it.key })
        assertFalse(expertPatchProceedAction("Patch", emptyList(), proceed).enabled)
        assertFalse(expertPatchProceedAction("Patch", listOf(first.copy(selected = false)), proceed).enabled)
    }
}
