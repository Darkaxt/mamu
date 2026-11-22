package app.morphe.manager.ui.screen.settings.system

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.morphe.manager.domain.installer.InstallerManager
import app.morphe.manager.domain.manager.AfterPatching
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstallerModesNativeTest {
    @get:Rule val rule = createComposeRule()
    @Test fun `after patching choices update independently and installer confirm remains reachable`() {
        var after by mutableStateOf(AfterPatching.MANUAL)
        var confirmed: InstallerManager.Token? = null
        val internal = InstallerManager.Entry(InstallerManager.Token.Internal, "System installer", null,
            InstallerManager.Availability(true), null)
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            InstallerSelectionDialog(listOf(internal), internal.token, {}, { confirmed = it }, null,
                afterPatching = after, onAfterPatchingChange = { after = it })
        } }
        rule.onNodeWithText("Install automatically").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(AfterPatching.INSTALL, after) }
        rule.onNodeWithText("Ask which installer").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(AfterPatching.ASK_INSTALLER, after) }
        rule.onNodeWithText("Install manually").performScrollTo().performClick()
        rule.onNodeWithText("Confirm").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(internal.token, confirmed) }
        System.getenv("MORPHE_LIBRARY_EVIDENCE")?.let { path ->
            val file = File(path, "installer-modes-phone.png"); file.parentFile.mkdirs()
            rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
