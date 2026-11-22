package app.morphe.manager.domain.repository

import org.junit.Assert.*
import org.junit.Test

class SelectionSetScopeTest {
    @Test fun `a set replaces only live visible source identities including empty choices`() {
        val before = SelectionSnapshot(mapOf(1 to setOf("Same"), 2 to setOf("Same"), 3 to setOf("Old")),
            mapOf(1 to mapOf("Same" to mapOf("x" to "hidden")), 3 to mapOf("Old" to mapOf("x" to "old"))))
        val snapshot = SelectionSnapshot(mapOf(2 to setOf("New"), 4 to setOf("Foreign")),
            mapOf(2 to mapOf("New" to mapOf("x" to "new")), 4 to mapOf("Foreign" to mapOf("x" to "foreign"))))
        val result = applyVisibleSelectionSet(before, snapshot, setOf(2, 3))
        assertEquals(setOf("Same"), result.patches[1])
        assertEquals(setOf("New"), result.patches[2])
        assertEquals(emptySet<String>(), result.patches[3])
        assertNull(result.patches[4])
        assertEquals(before.options[1], result.options[1])
        assertEquals(snapshot.options[2], result.options[2])
        assertNull(result.options[3])
        assertNull(result.options[4])
    }

    @Test fun `no visible source leaves selections and options unchanged`() {
        val before = SelectionSnapshot(mapOf(1 to setOf("Patch")), emptyMap())
        assertEquals(before, applyVisibleSelectionSet(before, SelectionSnapshot(emptyMap(), emptyMap()), emptySet()))
    }
}
