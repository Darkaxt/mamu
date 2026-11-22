package app.morphe.manager.domain.bundles

import app.morphe.patcher.patch.AppTarget
import kotlin.test.*

class VisibleVersionTargetsTest {
    private val targets = mapOf(
        "app" to listOf(BundledAppTarget(AppTarget(version = "1"), 1, "older"),
            BundledAppTarget(AppTarget(version = "2"), 2, "newer")),
        "other" to listOf(BundledAppTarget(AppTarget(version = "2"), 2, "newer")))

    @Test fun `per app hide changes supported recommendation only for that app`() {
        val visible = visibleVersionTargets(targets, emptyMap(), emptySet(), setOf("app|2"))
        assertEquals("1", visible["app"]?.recommended()?.version)
        assertEquals("2", visible["other"]?.recommended()?.version)
        assertEquals(2, targets["app"]?.size) // cached description remains intact
    }

    @Test fun `global hide applies to future app recommendations too`() {
        val visible = visibleVersionTargets(targets, emptyMap(), setOf("2"), emptySet())
        assertEquals("1", visible["app"]?.recommended()?.version)
        assertFalse("other" in visible)
    }

    @Test fun `all hidden does not revive unavailable source recommendations`() {
        assertTrue(visibleVersionTargets(targets, mapOf("app" to setOf(1)), setOf("2"), emptySet()).isEmpty())
        assertEquals(targets, visibleVersionTargets(targets, emptyMap(), emptySet(), emptySet()))
    }
}
