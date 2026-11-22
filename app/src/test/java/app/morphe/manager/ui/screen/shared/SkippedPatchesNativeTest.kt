package app.morphe.manager.ui.screen.shared

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.morphe.manager.patcher.patch.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkippedPatchesNativeTest {
    @get:Rule val rule=createComposeRule()
    @Test fun phone_details_keep_named_omissions_and_footer_reachable() = render("phone")
    @Test @Config(qualifiers="w1280dp-h800dp-xhdpi") fun tablet_details_keep_named_omissions_and_footer_reachable() = render("tablet")
    private fun render(layout:String) {
        val omissions=(1..12).map { PatchOmission(PatchKey(it,"Patch $it"),"Cannot resolve the required certificate fingerprint after the preceding pack rewrite",
            "Certificate dependency","Independent source $it","2026.11.$it") }
        rule.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.3f)) {
                Surface { SkippedPatchesNotice(omissions) }
            }
        } }
        val details=rule.onNodeWithText("Skipped patch details");details.assertIsDisplayed().performClick()
        rule.onNodeWithText("Patch 1").assertIsDisplayed()
        rule.onNodeWithText("Patch 12").performScrollTo().assertIsDisplayed()
        val close=rule.onNodeWithText("Close");close.assertIsDisplayed()
        close.assertHeightIsAtLeast(48.dp)
        System.getenv("MORPHE_LIBRARY_EVIDENCE")?.let { directory ->
            val file=File(directory,"skipped-patches-$layout.png");file.parentFile?.mkdirs()
            val bitmap=rule.runOnIdle { val decor=requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) } }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        close.performClick();rule.onNodeWithText("Patch 12").assertDoesNotExist()
        details.assertIsDisplayed()
    }
}
