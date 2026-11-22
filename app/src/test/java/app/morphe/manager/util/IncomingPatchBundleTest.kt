package app.morphe.manager.util

import android.app.Application
import android.net.Uri
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class IncomingPatchBundleTest {
    @get:Rule val temp = TemporaryFolder()
    private fun archive(name: String, bundle: Boolean): File = temp.newFile(name).also { file ->
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            if (bundle) {
                mainAttributes.putValue("Name", "Fixture patches")
                mainAttributes.putValue("Version", "2.4.0")
            } else {
                // A signed APK's per-entry names are not patch-bundle metadata.
                entries["classes.dex"] = Attributes().apply { putValue("SHA-256-Digest", "fixture") }
            }
        }
        JarOutputStream(file.outputStream(), manifest).use { }
    }

    @Test fun a_shared_bundle_renamed_by_a_messenger_is_recognized_by_its_main_manifest() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val uri = Uri.fromFile(archive("shared-file.bin", bundle = true))
        assertTrue(uri.isPatchBundle(resolver))
        assertEquals("Fixture patches", uri.readMppManifest(resolver)?.name)
    }

    @Test fun an_apk_or_jar_signature_manifest_does_not_become_a_patch_source() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val uri = Uri.fromFile(archive("shared-file.bin", bundle = false))
        assertFalse(uri.isPatchBundle(resolver))
        assertNull(uri.readMppManifest(resolver))
    }
}
