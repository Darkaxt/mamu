package app.morphe.manager.network.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtendedManagerVersionTest {
    @Test fun `numeric revisions and old installs stay ordered`() {
        assertTrue(compareExtendedManagerVersions("1.33.0.3", "1.33.0-extended.2")!! > 0)
        assertTrue(compareExtendedManagerVersions("1.33.0.10", "1.33.0.9")!! > 0)
        assertTrue(compareExtendedManagerVersions("1.34.0.1", "1.33.99.99999")!! > 0)
        assertEquals(0, compareExtendedManagerVersions("v1.33.0.2", "1.33.0-extended.2"))
        assertTrue(compareExtendedManagerVersions("1.33.0.99999999999999999999999999", "1.33.0.9")!! > 0)
        assertTrue(compareExtendedManagerVersions("1.33.0", "1.33.0-dev.99")!! > 0)
        assertNull(compareExtendedManagerVersions("broken", "1.33.0.3"))
    }
}
