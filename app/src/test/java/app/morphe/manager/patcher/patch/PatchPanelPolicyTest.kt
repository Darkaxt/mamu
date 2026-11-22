package app.morphe.manager.patcher.patch

import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class PatchPanelPolicyTest {
    private fun patch(name: String = "Same label", vararg targets: CompatiblePackage) =
        PatchInfo(name, null, false, targets.toImmutableList(), null)

    @Test fun `section actions keep source identities and ignore unchosen or unavailable sources`() {
        val rows = listOf(PanelPatch(1, patch(), false, PatchVersionScope.VERIFIED, 1),
            PanelPatch(2, patch(), true, PatchVersionScope.VERIFIED, 2))
        assertEquals(setOf(1, 2), sectionActionTargets(rows, setOf(1, 2, 3)).keys)
        assertEquals(listOf(patch() to true), sectionActionTargets(rows, setOf(2))[2])
        assertTrue(sectionActionTargets(rows, emptySet()).isEmpty())
        assertTrue(sectionActionTargets(emptyList(), setOf(1)).isEmpty())
    }

    @Test fun `verified requires explicit actual version and respects build restrictions`() {
        val patch = patch(targets = arrayOf(CompatiblePackage("app", persistentSetOf("2"),
            versionCodes = persistentMapOf("2" to persistentSetOf(20)))))
        assertEquals(PatchVersionScope.VERIFIED, patch.versionScope("app", "2", 20))
        assertEquals(PatchVersionScope.UNSUPPORTED, patch.versionScope("app", "1", 20))
        assertEquals(PatchVersionScope.UNSUPPORTED, patch.versionScope("app", "2", 21))
        assertEquals(PatchVersionScope.UNSUPPORTED, patch.versionScope("app", null, null))
    }

    @Test fun `compatible and universal declarations remain distinct`() {
        assertEquals(PatchVersionScope.COMPATIBLE,
            patch(targets = arrayOf(CompatiblePackage("app", null))).versionScope("app", "9", null))
        assertEquals(PatchVersionScope.UNRESTRICTED, patch().versionScope("app", "9", null))
        assertEquals(PatchVersionScope.UNRESTRICTED,
            patch(targets = arrayOf(CompatiblePackage(null, null))).versionScope("app", "9", null))
        assertEquals(PatchVersionScope.UNSUPPORTED,
            patch(targets = arrayOf(CompatiblePackage("other", null))).versionScope("app", "9", null))
    }

    @Test fun `specific matching target wins over universal declaration`() {
        assertEquals(PatchVersionScope.VERIFIED, patch(targets = arrayOf(
            CompatiblePackage(null, null), CompatiblePackage("app", persistentSetOf("2"))
        )).versionScope("app", "2", null))
    }

    @Test fun `specific ranking uses release date while universal rows are alphabetical and identities survive`() {
        fun row(uid: Int, name: String, scope: PatchVersionScope, date: Long) =
            PanelPatch(uid, patch(name), false, scope, date)
        val rows = orderPanelPatches(listOf(
            row(1, "Z", PatchVersionScope.UNRESTRICTED, 100),
            row(2, "A", PatchVersionScope.UNRESTRICTED, 1),
            row(3, "Older", PatchVersionScope.VERIFIED, 1),
            row(4, "Newer", PatchVersionScope.VERIFIED, 2),
            row(5, "Same", PatchVersionScope.COMPATIBLE, 1),
            row(6, "Same", PatchVersionScope.COMPATIBLE, 1)))
        assertEquals(listOf(4, 3, 5, 6, 2, 1), rows.map { it.bundleUid })
        assertEquals(6, rows.map { it.key }.toSet().size)
    }

    @Test fun `hiding every source gives empty run and leaves stored selections intact`() {
        val saved = mapOf(1 to setOf("one"), 2 to setOf("two"))
        assertTrue(saved.withoutHiddenSources(setOf(1, 2)).isEmpty())
        assertEquals(mapOf(2 to setOf("two")), saved.withoutHiddenSources(setOf(1)))
        assertEquals(2, saved.size)
    }

    @Test fun `per app hides do not match another app or malformed entries`() {
        assertEquals(setOf(1, 2), panelHiddenFor("app", setOf("app|1", "app|2", "app.more|3", "other|4", "app|oops")))
        assertTrue(panelHiddenFor("different", setOf("app|1")).isEmpty())
    }

    @Test fun `universal declarations keep bulk selection safeguards and scoping`() {
        val universal = patch(targets = arrayOf(CompatiblePackage(null, null)))
        assertTrue(universal.isUniversal)
        assertTrue(universal.compatibleWith("app"))
        assertTrue(universal.supports("app", "2", 20))
        // Upstream 257dea3da counts patches as the patcher does: an empty list names no app
        val empty = patch()
        assertFalse(empty.isUniversal)
        assertFalse(empty.compatibleWith("app"))
        assertFalse(empty.supports("app", "2", 20))
    }

    @Test fun `warning expires on version build or source release change`() {
        fun key(version: String = "2", code: Long = 20, release: String = "r1", date: Long = 1) =
            unsupportedAcknowledgementKey("app", version, code, 4, release, date)
        assertEquals(key(), key())
        assertNotEquals(key(), key(version = "3"))
        assertNotEquals(key(), key(code = 21))
        assertNotEquals(key(), key(release = "r2"))
        assertNotEquals(key(), key(date = 2))
    }
    @Test fun `selected unsupported rows bypass the other versions filter until deselected`() {
        val selected = PanelPatch(1, patch(), true, PatchVersionScope.UNSUPPORTED, 0)
        val supported = selected.copy(scope = PatchVersionScope.VERIFIED)
        val scopes = setOf(PatchVersionScope.VERIFIED, PatchVersionScope.COMPATIBLE, PatchVersionScope.UNRESTRICTED)
        assertTrue(selected.matchesVersionScopes(scopes))
        assertTrue(selected.matchesVersionScopes(emptySet()))
        assertFalse(selected.copy(selected = false).matchesVersionScopes(scopes))
        assertTrue(selected.copy(selected = false).matchesVersionScopes(scopes + PatchVersionScope.UNSUPPORTED))
        assertTrue(supported.matchesVersionScopes(scopes))
        assertFalse(supported.matchesVersionScopes(setOf(PatchVersionScope.COMPATIBLE)))
        assertTrue(selected.selected)
    }

}
