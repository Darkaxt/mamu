package app.morphe.manager.util

import kotlin.test.*

class ApkVariantVersionTest {
    @Test fun `only explicit terminal ABIs constrain device eligibility`() {
        val abis = listOf("arm64-v8a")
        assertTrue(versionSupportsAbis("18.5.1.993608717-lite_beta-arm64-v8a", abis))
        assertFalse(versionSupportsAbis("18.5.1.993608717-lite_beta-armeabi-v7a", abis))
        assertTrue(versionSupportsAbis("18.5.1.993608717-lite_beta-armeabi-v7a", listOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(versionSupportsAbis("1.2-beta-x86_64", listOf("x86")))
        for (version in listOf(null, "1.2", "1.2-beta", "1.2-special", "1.2-arm64-v8a-extra")) {
            assertNull(versionArchitecture(version))
            assertTrue(versionSupportsAbis(version, abis))
        }
    }
}
