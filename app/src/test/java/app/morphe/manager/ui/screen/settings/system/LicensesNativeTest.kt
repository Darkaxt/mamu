package app.morphe.manager.ui.screen.settings.system

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.UserMessages
import app.morphe.manager.util.AppCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicensesNativeTest {
    @get:Rule val rule = createComposeRule()
    private val messagesScope = AppCoroutineScope()

    @Before fun owners() {
        val app = RuntimeEnvironment.getApplication()
        startKoin { modules(module { single { UserMessages(app, messagesScope) } }) }
    }

    @After fun close() {
        runBlocking { messagesScope.coroutineContext[Job]?.cancelAndJoin() }
        stopKoin()
    }

    @Test fun generatedLicenses() {
        val app = RuntimeEnvironment.getApplication()
        var dismissed = false
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { LicensesDialog { dismissed = true } } }
        rule.onAllNodesWithText(app.getString(R.string.opensource_licenses)).assertCountEquals(1)
        val searchLabel = app.getString(R.string.opensource_licenses_search)
        // Wait for the actual asset-loading publication; this is a test assertion boundary.
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasContentDescription(searchLabel) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription(searchLabel).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Mamu")
        rule.onNode(hasText("Mamu") and hasClickAction() and !hasSetTextAction())
            .assertIsDisplayed().performClick()
        rule.onNodeWithText("Mamu NOTICE", substring = true).assertIsDisplayed()
        System.getenv("MORPHE_LIBRARY_EVIDENCE")?.let { directory ->
            val target = File(directory, "licenses-extended-notice.png")
            target.parentFile?.mkdirs()
            // Robolectric PixelCopy does not composite separate dialog windows. Draw the
            // actual top dialog's decor with the native Canvas instead of the activity root.
            val capture = rule.runOnIdle {
                val decor = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
            }
            target.outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val samples = (0 until capture.height step 16).flatMap { y ->
                (0 until capture.width step 16).map { x -> capture.getPixel(x, y) }
            }.toSet()
            assertTrue(samples.size > 2, "The native dialog capture must contain rendered content")
        }
        assertTrue(!dismissed)
    }
}
