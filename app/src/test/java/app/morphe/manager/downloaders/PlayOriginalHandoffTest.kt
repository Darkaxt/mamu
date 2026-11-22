package app.morphe.manager.downloaders

import android.app.Application
import app.morphe.manager.domain.installer.InstallerFileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlayOriginalHandoffTest {
    @Test fun independentPlayStagingIsRetiredWithoutTouchingTheOriginalOrOtherImports() {
        val app = RuntimeEnvironment.getApplication()
        val original = File.createTempFile("play-original-", ".apk", app.cacheDir).apply { writeText("owned original") }
        val playName = "native-original-play-${UUID.randomUUID()}.apk"
        val otherName = "user-import-${UUID.randomUUID()}.apk"
        try {
            val play = InstallerFileProvider.getUriForFile(app, original, playName)
            val other = InstallerFileProvider.getUriForFile(app, original, otherName)
            val share = File(app.cacheDir, InstallerFileProvider.SHARE_DIR)
            assertEquals(original.readText(), File(share, playName).readText())
            InstallerFileProvider.discardNativeOriginal(app, other)
            assertTrue(File(share, otherName).exists())
            InstallerFileProvider.discardNativeOriginal(app, play)
            assertFalse(File(share, playName).exists())
            assertEquals("owned original", original.readText())
            assertTrue(File(share, otherName).exists())
        } finally {
            original.delete()
            File(app.cacheDir, "${InstallerFileProvider.SHARE_DIR}/$playName").delete()
            File(app.cacheDir, "${InstallerFileProvider.SHARE_DIR}/$otherName").delete()
        }
    }
}
