package app.morphe.manager.downloaders

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MetadataFreeXapkTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { archive ->
            entries.forEach { (name, content) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(content)
                archive.closeEntry()
            }
        }
    }.toByteArray()

    private fun apk(split: String? = null): ByteArray = zip("AndroidManifest.xml" to AndroidManifestBlock().apply {
        packageName = "com.markOne.ss_app"
        if (split != null) setSplit(split, true)
        refresh()
    }.bytes)

    private fun detect(vararg entries: Pair<String, ByteArray>): ArchiveFormat {
        val root = File("D:/Temp").takeIf { System.getProperty("os.name", "").orEmpty().startsWith("Windows") && it.isDirectory }
        val file = File.createTempFile("metadata-free-xapk-", ".xapk", root)
        return try { file.writeBytes(zip(*entries)); ArchiveFormat.detect(file) } finally { file.delete() }
    }

    @Test fun acceptsUptodownPackageNamedBaseAndSplitsWithoutVendorJson() {
        assertEquals(ArchiveFormat.XAPK, detect(
            "com.markOne.ss_app.apk" to apk(), "config.arm64_v8a.apk" to apk("config.arm64_v8a"),
            "config.en.apk" to apk("config.en")))
    }

    @Test fun rejectsConfigurationOnlyAndFakeOrEmptyApkPayloads() {
        assertThrows(IOException::class.java) { detect("config.en.apk" to apk("config.en")) }
        assertThrows(IOException::class.java) { detect("com.markOne.ss_app.apk" to "<html>Verification</html>".toByteArray()) }
        assertThrows(IOException::class.java) { detect("com.markOne.ss_app.apk" to apk(), "config.en.apk" to byteArrayOf()) }
    }

    @Test fun rejectsNestedResourceApksAndUnsafePaths() {
        assertThrows(IOException::class.java) { detect("res/raw/embedded.apk" to apk()) }
        assertThrows(IOException::class.java) { detect("../base.apk" to apk()) }
    }
}
