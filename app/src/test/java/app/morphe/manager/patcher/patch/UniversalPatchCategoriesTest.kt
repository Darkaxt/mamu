package app.morphe.manager.patcher.patch

import kotlin.test.*

class UniversalPatchCategoriesTest {
    @Test fun `declared semantic categories normalize while generic verbs derive from name`() {
        assertEquals("Privacy & telemetry", UniversalPatchCategories.category("Disable Sentry", null, "Telemetry"))
        assertEquals("Manifest & app lifecycle", UniversalPatchCategories.category("Change package name", null, "Manifest"))
        assertEquals("Display & interface", UniversalPatchCategories.category("Universal Overlay", "Uses Ads Block", "★ Universal Overlay"))
    }
    @Test fun `name outranks incidental description warnings and unmatched rows remain visible`() {
        assertEquals("Identity & cloning", UniversalPatchCategories.category("Clone app", "May break Google Services and ads", null))
        assertEquals("Other", UniversalPatchCategories.category("Unrecognized", null, "Misc"))
        assertEquals("Author category", UniversalPatchCategories.category("Unrecognized", null, "Author category"))
        assertEquals("Resources & optimization", UniversalPatchCategories.category("Remove debug info", null, null))
    }
}
