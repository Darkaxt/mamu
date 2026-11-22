package app.morphe.manager.network.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtendedReleaseIdentityTest {
    @Test fun `only our named APK in our private origin is a manager update`() {
        assertTrue(isExtendedManagerRelease("https://github.com/Darkaxt/mamu/releases/download/v1.33.0-extended.2/Mamu-1.33.0-extended.2.apk"))
        assertFalse(isExtendedManagerRelease("https://github.com/MorpheApp/morphe-manager/releases/download/v1.33.0/morphe-manager-1.33.0.apk"))
        assertFalse(isExtendedManagerRelease("https://github.com/Darkaxt/mamu/releases/download/v1/other.apk"))
        assertFalse(isExtendedManagerRelease("https://github.com/Someone/mamu/releases/download/v1/Mamu-1.apk"))
        assertFalse(isExtendedManagerRelease("https://github.com/Darkaxt/mamu/releases/download/v1/Mamu-1.apk.asc"))
        assertFalse(isExtendedManagerRelease("https://example.com/Mamu-1.apk"))
    }
}
