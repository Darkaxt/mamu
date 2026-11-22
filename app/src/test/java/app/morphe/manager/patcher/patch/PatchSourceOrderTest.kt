package app.morphe.manager.patcher.patch

import kotlinx.collections.immutable.immutableListOf
import kotlinx.collections.immutable.immutableSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PatchSourceOrderTest {
    private val app = "example.app"
    private fun custom(name: String, version: String? = "2") = PatchInfo(
        name, null, true,
        immutableListOf(CompatiblePackage(app, version?.let { immutableSetOf(it) })), null
    )
    private fun universal(name: String, namedEntry: Boolean = false) = PatchInfo(
        name, null, true,
        if (namedEntry) immutableListOf(CompatiblePackage(null, null)) else null, null
    )
    private fun source(id: Int, vararg patches: PatchInfo) =
        PatchBundleInfo.Global("Source $id", null, id, true, patches.toList()).forPackage(app, "2")

    @Test fun `info preview retains app-specific versions and drops universal-only sources`() {
        val older = custom("Older APK", "1")
        val current = custom("Current APK", "2")
        val anotherApp = current.copy(compatiblePackages = immutableListOf(
            CompatiblePackage("another.app", immutableSetOf("2")), CompatiblePackage(null, null)))
        val sources = linkedMapOf(
            1 to listOf(universal("Generic", true), older, current, anotherApp),
            2 to listOf(universal("More generic"))
        )
        val preview = sources.appSpecificPatches(app)
        assertEquals(setOf(1), preview.keys)
        assertEquals(listOf(older, current), preview[1])
        assertSame(older, preview[1]?.first())
        assertEquals(4, sources[1]?.size)
    }

    @Test fun `Amazon preview ranks Doom eight above Debakar one and unrestricted DeVanced`() {
        val preview = linkedMapOf(
            1 to listOf(custom("Sort ratings", "32.13.2.100")),
            2 to listOf(custom("Deep links", null)),
            3 to List(8) { custom("Doom $it", "32.17.0.100") },
            4 to List(40) { universal("Universal $it", true) }
        )
        val sorted = preview.entries.sortedWith(patchSourceDisplayOrder(app, "32.17.0.100") { it.value })
        assertEquals(listOf(3, 2, 1, 4), sorted.map { it.key })
        assertEquals(9, preview.values.sumOf { customPatchCount(it, app, "32.17.0.100") })
        assertEquals(2, preview.values.sumOf { customPatchCount(it, app, "32.13.2.100") })
    }

    @Test fun `custom counts outrank universal counts and ties retain input order`() {
        val generic = source(1, *Array(20) { universal("u$it", it % 2 == 0) })
        val few = source(2, custom("a"), *Array(30) { universal("m$it") })
        val most = source(3, custom("a"), custom("b"))
        val tied = source(4, custom("c"), custom("d"))
        val input = listOf(generic, few, most, tied)
        val result = input.sortedWith(patchSourceDisplayOrder(app, "2"))
        assertEquals(listOf(3, 4, 2, 1), result.map { it.uid })
        assertEquals(listOf(1, 2, 3, 4), input.map { it.uid })
        assertSame(most, result.first())
    }

    @Test fun `recommended match comes before more custom patches for another version`() {
        val old = source(1, *Array(10) { custom("old$it", "1") })
        val recommended = source(2, custom("new"))
        val unrestricted = source(3, custom("any", null))
        val generic = source(4, universal("generic", true))
        assertEquals(listOf(2, 3, 1, 4),
            listOf(old, generic, recommended, unrestricted)
                .sortedWith(patchSourceDisplayOrder(app, "2")).map { it.uid })
    }

    @Test fun `universal entry cannot make another package version count as custom match`() {
        val mixed = PatchInfo("mixed", null, true, immutableListOf(
            CompatiblePackage(app, immutableSetOf("1")), CompatiblePackage(null, null)
        ), null)
        val other = PatchInfo("other", null, true, immutableListOf(
            CompatiblePackage("another.app", immutableSetOf("2")), CompatiblePackage(null, null)
        ), null)
        assertEquals(listOf(3, 1, 2), listOf(source(1, mixed), source(2, other), source(3, custom("new")))
            .sortedWith(patchSourceDisplayOrder(app, "2")).map { it.uid })
    }

    @Test fun `without version information all custom sources rank by custom count`() {
        assertEquals(listOf(2, 1, 3), listOf(source(1, custom("a")),
            source(2, custom("b"), custom("c", "1")), source(3, universal("u")))
            .sortedWith(patchSourceDisplayOrder(app, null)).map { it.uid })
    }
}
