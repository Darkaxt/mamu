package app.morphe.manager.ui.screen.patcher

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.morphe.manager.patcher.logger.LogLevel
import java.io.File
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogEntryRowNativeTest {
    @get:Rule val rule = createComposeRule()
    @Test fun `trailing content receives exactly the text colour for each level and subdued info`() {
        val cases = LogLevel.entries.map { it.name to (it to false) } + ("subdued" to (LogLevel.INFO to true))
        val observed = mutableMapOf<String, Color>()
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Column {
            for ((key, value) in cases) LogEntryRow(value.first, "$key text", subdued = value.second,
                trailing = { observed[key] = LocalContentColor.current })
        } } } }
        for ((key, _) in cases) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText("$key text", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(layouts.single().layoutInput.style.color, observed[key], key)
        }
    }
    @Test fun `default patching rows retain their badges text and level theme without a trailing action`() {
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Column {
            LogEntryRow(LogLevel.INFO, "Ordinary patching progress")
            LogEntryRow(LogLevel.WARN, "A patching warning")
            LogEntryRow(LogLevel.ERROR, "A patching failure")
        } } } }
        for (label in listOf("I", "W", "E", "Ordinary patching progress", "A patching warning", "A patching failure"))
            rule.onNodeWithText(label).assertIsDisplayed()
        System.getenv("MORPHE_LIBRARY_EVIDENCE")?.let { directory ->
            val file = File(directory, "patching-log-default-rows.png"); file.parentFile.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
