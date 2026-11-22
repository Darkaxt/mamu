package app.morphe.manager.ui.screen.home

import app.morphe.manager.patcher.patch.*
import kotlin.test.*
import app.morphe.manager.ui.screen.shared.SemanticTone

class PanelPatchGroupingTest {
    private fun row(uid: Int, name: String, scope: PatchVersionScope, selected: Boolean = false,
                    category: String? = null) = PanelPatch(uid,
        PatchInfo(name, null, false, null, null, category = category), selected, scope, 0)

    private fun groups(rows: List<PanelPatch>, custom: String = "Custom") =
        buildPanelPatchGroups(orderPanelPatches(rows), custom, "Universal")

    @Test fun `custom starts open and every universal category starts closed even when selected or alone`() {
        val specific = row(1, "Specific", PatchVersionScope.VERIFIED, true)
        val compatible = row(2, "Compatible", PatchVersionScope.COMPATIBLE)
        val universal = row(3, "Block ads", PatchVersionScope.UNRESTRICTED, true, "Ads")
        val older = row(4, "Older", PatchVersionScope.UNSUPPORTED)
        val sections = groups(listOf(specific, compatible, universal, older))
        assertEquals(listOf("custom", "universal:Ads & promotions"), sections.map { it.key })
        assertEquals(listOf(true, false), sections.map { it.defaultExpanded })
        assertEquals(listOf(1, 1), sections.map { it.selectedCount })
        assertEquals(listOf(specific, compatible, older), sections.first().items)
        assertFalse(groups(listOf(universal)).single().defaultExpanded)
        assertTrue(groups(listOf(specific)).single().defaultExpanded)
    }

    @Test fun `independent explicit folds survive filtering relabeling and selection without changing rows`() {
        val custom = row(1, "Specific", PatchVersionScope.VERIFIED, true)
        val universal = row(2, "Block ads", PatchVersionScope.UNRESTRICTED, true, "Ads")
        val sections = groups(listOf(custom, universal))
        val folds = PatchFolds(emptyMap()).with("app", sections[0].key, false)
            .with("app", sections[1].key, true)
        val relabeled = groups(listOf(custom), "Translated custom").single()
        assertFalse(folds.isExpanded("app", relabeled))
        val filtered = groups(listOf(universal.copy(selected = false))).single()
        assertTrue(folds.isExpanded("app", filtered))
        assertFalse(folds.isExpanded("different-app", filtered))
        assertEquals(listOf(custom, universal), sections.flatMap { it.items })
        assertEquals(listOf(true, true), sections.flatMap { it.items }.map { it.selected })
        // Showing search/selection matches does not write folds; ordinary mode reads these again.
        assertFalse(folds.isExpanded("app", sections[0]))
        assertTrue(folds.isExpanded("app", sections[1]))
    }

    @Test fun `category ordering preserves duplicate source identities and alphabetical patch rows`() {
        val rows = listOf(row(2, "Z", PatchVersionScope.UNRESTRICTED, category = "Ads"),
            row(1, "A", PatchVersionScope.UNRESTRICTED, true, "Ads"),
            row(3, "A", PatchVersionScope.UNRESTRICTED, category = "Ads"),
            row(4, "Unrecognized", PatchVersionScope.UNRESTRICTED))
        val sections = groups(rows)
        assertEquals(listOf("universal:Ads & promotions", "universal:Other"), sections.map { it.key })
        assertEquals(listOf("1:A", "3:A", "2:Z"), sections.first().items.map { it.key })
        assertEquals(rows.map { it.key }.toSet(), sections.flatMap { it.items }.map { it.key }.toSet())
        assertEquals(1, sections.first().selectedCount)
    }
    @Test fun `section colour follows selected compatibility and ignores unselected unsupported rows`() {
        val supported = listOf(row(1, "Verified", PatchVersionScope.VERIFIED, true),
            row(2, "Compatible", PatchVersionScope.COMPATIBLE, true),
            row(3, "Universal", PatchVersionScope.UNRESTRICTED, true))
        val older = row(4, "Other build", PatchVersionScope.UNSUPPORTED)
        assertNull(selectionCompatibilityTone(emptyList()))
        assertNull(selectionCompatibilityTone(listOf(older)))
        assertEquals(SemanticTone.Success, selectionCompatibilityTone(supported))
        assertEquals(SemanticTone.Success, selectionCompatibilityTone(supported + older))
        assertEquals(SemanticTone.Warning, selectionCompatibilityTone(supported + older.copy(selected = true)))
        assertEquals(SemanticTone.Warning, selectionCompatibilityTone(listOf(older.copy(selected = true))))
    }

    @Test fun `unsupported selections retain source identity when other rows are filtered out`() {
        val custom = row(1, "Same", PatchVersionScope.VERIFIED, true)
        val older = row(2, "Same", PatchVersionScope.UNSUPPORTED, true)
        val unselected = row(3, "Older", PatchVersionScope.UNSUPPORTED)
        val rows = listOf(custom, older, unselected)
        assertEquals(listOf(older), selectedUnsupportedPatches(rows))
        assertEquals(listOf(older.key), selectedUnsupportedPatches(rows).map { it.key })
        assertEquals(listOf("custom"), groups(rows).map { it.key })
        assertEquals(SemanticTone.Warning, selectionCompatibilityTone(groups(rows).single().items))
        assertTrue(selectedUnsupportedPatches(rows.filterNot { it.bundleUid == 2 }).isEmpty())
        assertEquals(listOf(custom, older, unselected), rows)
    }

}
