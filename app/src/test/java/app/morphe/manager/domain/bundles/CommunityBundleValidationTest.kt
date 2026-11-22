package app.morphe.manager.domain.bundles

import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class CommunityBundleValidationTest {
    @Test fun `rejects HTML challenge response instead of admitting broken source`() {
        val file = Files.createTempFile("community", ".jar").toFile()
        try {
            file.writeText("<html>Verify you are human</html>")
            assertFails { validateCommunityBundle(file) }
        } finally { file.delete() }
    }

    @Test fun `accepts manifest and dex container and rejects missing truncated or non dex content`() {
        val file = Files.createTempFile("community", ".jar").toFile()
        fun write(dex: ByteArray?, includeManifest: Boolean = true) {
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes.putValue("Name", "Community patches")
            }
            val output = file.outputStream()
            (if (includeManifest) JarOutputStream(output, manifest) else JarOutputStream(output)).use { jar ->
                dex?.let {
                    jar.putNextEntry(JarEntry("classes.dex")); jar.write(it); jar.closeEntry()
                }
            }
        }
        try {
            write(null); assertFails { validateCommunityBundle(file) }
            write(byteArrayOf(1)); assertFails { validateCommunityBundle(file) }
            write(ByteArray(112)); assertFails { validateCommunityBundle(file) }
            write(ByteArray(112).apply { "dex\n035\u0000".toByteArray().copyInto(this) })
            assertEquals("Community patches", validateCommunityBundle(file))
            // Upstream accepts older bundles without optional manifest attributes.
            write(ByteArray(112).apply { "dex\n035\u0000".toByteArray().copyInto(this) }, includeManifest = false)
            assertNull(validateCommunityBundle(file))
        } finally { file.delete() }
    }
}
