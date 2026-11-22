package app.morphe.manager.ui.screen.home

import androidx.compose.ui.Modifier
import androidx.compose.foundation.LocalIndication
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelStore
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.downloaders.DownloadSource
import app.morphe.manager.downloaders.OriginalDownloadLookup
import app.morphe.manager.downloaders.RemotePage
import app.morphe.manager.patcher.logger.LogLevel
import app.morphe.manager.ui.screen.shared.UserMessages
import app.morphe.manager.ui.viewmodel.NativeOriginalDownloadViewModel
import app.morphe.manager.util.AppCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalDownloadPopupTest {
    @get:Rule val rule = createComposeRule()
    private val messagesScope = AppCoroutineScope()
    private val owner = ViewModelStore()
    private lateinit var model: NativeOriginalDownloadViewModel
    private lateinit var states: MutableStateFlow<NativeOriginalDownloadViewModel.State>
    private val opened = mutableListOf<String>()
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        startKoin { modules(module { single { UserMessages(app, messagesScope) } }) }
        model = NativeOriginalDownloadViewModel(app, PreferencesManager(app))
        owner.put("original-download-popup", model)
        // Drive the real renderer's state boundary without starting a device/network operation.
        @Suppress("UNCHECKED_CAST")
        states = NativeOriginalDownloadViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(model) as MutableStateFlow<NativeOriginalDownloadViewModel.State>
    }
    @After fun close() {
        rule.runOnIdle { owner.clear() }
        runBlocking { messagesScope.coroutineContext[Job]?.cancelAndJoin() }
        stopKoin()
    }
    private var manualCalls = 0
    private fun show() = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f),
            LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Press ripples are platform RippleDrawables animated off the test clocks and drawn over
                // the row's content, so pixel checks of a just-clicked row see them; leave them out here
                CompositionLocalProvider(LocalIndication provides NoPressIndication) {
                    NativeOriginalApkDialog("Plague Inc.", null, null, "1.27.5", {}, {}, {}, onManual = { manualCalls++ },
                        onChooseApk = {}, model = model)
                }
            }
        }
    }
    @Test fun withoutByparrTheDownloadSitesFallBackToBrowserInstructions() {
        // Google Play failed with the sites offered; no Byparr server is set in fresh preferences
        NativeOriginalDownloadViewModel::class.java.getDeclaredField("requestUrl").apply { isAccessible = true }
            .set(model, "https://www.apkmirror.com/?s=com.miniclip.plagueinc")
        states.value = NativeOriginalDownloadViewModel.State.Failure("Google Play · HTTP 429. Retry explicitly.", true)
        show()
        rule.onNodeWithText("Try other download sites").performClick()
        rule.waitUntil(5_000) { states.value == NativeOriginalDownloadViewModel.State.Manual }
        rule.runOnIdle { assertEquals(1, manualCalls) }
    }
    @Test fun shopStepQueueAndTransferStayVisibleInTheActualLargeFontPopup() {
        val progress = OriginalDownloadLookup.Progress(2, DownloadSource.APK_PURE,
            "Resolving package…", "Byparr queue: 2 of 2")
        states.value = NativeOriginalDownloadViewModel.State.Resolving(progress.message)
        show()
        rule.onNodeWithText(progress.message.substringBefore('\n')).assertIsDisplayed()
        rule.onNodeWithText(progress.queue!!).assertIsDisplayed()
        capture("cross-site-popup-shop-and-queue.png")
        rule.runOnIdle { states.value = NativeOriginalDownloadViewModel.State.Transfer(1024, 2048, false, "APKPure") }
        rule.onNodeWithText("APKPure ·", substring = true).assertIsDisplayed()
        rule.onNodeWithText("1.0", substring = true).assertIsDisplayed()
    }
    @Test fun ambiguousStoreResultsRemainChoicesAndFailureKeepsExplicitRetry() {
        val choices = NativeOriginalDownloadViewModel.State.StoreChoices(DownloadSource.APK_PURE,
            listOf(RemotePage.Choice("https://apkpure.net/app/com.example.app/download/1.27.5", "First matching build"),
                RemotePage.Choice("https://apkpure.com/app/com.example.app/download/1.27.5", "Second matching build")))
        states.value = choices
        show()
        rule.onNodeWithText("APKPure · Choose a matching store result").assertIsDisplayed()
        rule.onNodeWithText("First matching build").assertIsDisplayed()
        rule.onNodeWithText("Second matching build").assertIsDisplayed()
        rule.runOnIdle { states.value = NativeOriginalDownloadViewModel.State.Failure("APKPure: request outcome is uncertain. Retry explicitly.") }
        rule.onNodeWithText("APKPure: request outcome is uncertain. Retry explicitly.").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertIsDisplayed()
    }
    @Test fun playTransferAndServiceFailureExposeTheActualStepAndExplicitShopHandoff() {
        states.value = NativeOriginalDownloadViewModel.State.Transfer(1024, 2048, false, "Google Play", "Google Play · Downloading 2 of 3 APKs…")
        show()
        rule.onNodeWithText("Google Play · Downloading 2 of 3 APKs…", substring = true).assertIsDisplayed()
        capture("google-play-original-progress.png")
        rule.runOnIdle { states.value = NativeOriginalDownloadViewModel.State.Failure("Google Play · HTTP 429. Retry explicitly.", true) }
        rule.onNodeWithText("Try other download sites").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertIsDisplayed()
        capture("google-play-original-failure.png")
    }
    @Test fun compactLogExpandsPreviousStoreReasonsWithoutChangingAcquisition() {
        val play = "https://play.google.com/store/apps/details?id=com.miniclip.plagueinc"
        val pure = "https://apkpure.net/plague-inc/com.miniclip.plagueinc/download/1.27.5"
        model.activity.record(0, "play-miss", "Google Play", "Requested version unavailable", LogLevel.WARN,
            pageUrl = play)
        model.activity.record(0, "mirror-miss", "APKMirror", "Requested version not found", LogLevel.WARN)
        model.activity.record(0, "pure", "APKPure", "Requesting download link", pageUrl = pure)
        states.value = NativeOriginalDownloadViewModel.State.Resolving("APKPure · Requesting download link")
        show()
        rule.onNodeWithText("APKPure · Requesting download link").assertIsDisplayed()
        rule.onNodeWithText("Google Play · Requested version unavailable").assertDoesNotExist()
        rule.onNodeWithText("APKPure · Requesting download link").assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals(listOf(pure), opened); assertTrue(states.value is NativeOriginalDownloadViewModel.State.Resolving) }
        rule.onNodeWithText("Google Play · Requested version unavailable").assertDoesNotExist()
        rule.onNodeWithTag("download-log-toggle").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Google Play · Requested version unavailable").assertIsDisplayed().assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals(listOf(pure, play), opened); assertEquals(3, model.activityLog.value.size) }
        rule.onNodeWithText("Offers 1.27.3, requested 1.27.5").assertDoesNotExist()
        rule.onNodeWithText("APKMirror · Requested version not found").assertHasNoClickAction()
        assertLinkedRowTheme("play-miss")
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Choose another APK or version").assertIsDisplayed()
        capture("download-log-expanded-phone.png")
        rule.onNodeWithTag("download-log-toggle").performClick()
        rule.onNodeWithText("Google Play · Requested version unavailable").assertDoesNotExist()
        rule.onNodeWithText("APKPure · Requesting download link").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w840dp-h800dp-xhdpi")
    fun expandedHistoryAndFailureKeepFooterAvailableOnTablet() {
        model.activity.record(0, "mirror", "APKMirror", "Requested version not found", LogLevel.WARN,
            pageUrl = "https://www.apkmirror.com/?s=com.miniclip.plagueinc")
        model.activity.record(0, "pure", "APKPure", "HTTP 429. Retry explicitly.", LogLevel.ERROR,
            pageUrl = "https://apkpure.net/plague-inc/com.miniclip.plagueinc/download/1.27.5")
        states.value = NativeOriginalDownloadViewModel.State.Failure("APKPure · HTTP 429. Retry explicitly.")
        show()
        rule.onNodeWithTag("download-log-toggle").performClick()
        rule.onNodeWithText("APKPure · HTTP 429. Retry explicitly.").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertIsDisplayed()
        assertLinkedRowTheme("pure")
        assertLinkedRowTheme("mirror")
        capture("download-log-expanded-tablet-error.png")
    }

    private fun assertLinkedRowTheme(key: String) {
        // A row clicked just before carries a press ripple, drawn by View animators off the Compose
        // clock; let both settle so the capture shows the row's resting colours
        rule.mainClock.advanceTimeBy(2_000)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        rule.waitForIdle()
        val entry = model.activityLog.value.single { it.key == key }
        val row = rule.onNodeWithTag("download-log-row.$key", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val background = row.getPixel(1, row.height / 2)
        assertEquals(background, row.getPixel(row.width - 2, row.height / 2), "The row background must continue behind its link")
        fun foreground(bitmap: Bitmap, behind: Int): Int = (0 until bitmap.height).flatMap { y ->
            (0 until bitmap.width).map { x -> bitmap.getPixel(x, y) }
        }.filter { it != behind }.groupingBy { it }.eachCount().maxBy { it.value }.key
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(entry.text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val icon = rule.onNodeWithTag("download-log-link.$key", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        // What lies behind the icon is read from its own corner: a fading press ripple can still tint
        // the middle of a just-clicked row after the row's edges are back to its resting colour
        val behindIcon = icon.getPixel(0, 0)
        val textPixel = layouts.single().layoutInput.style.color.compositeOver(Color(behindIcon)).toArgb()
        val iconPixel = foreground(icon, behindIcon)
        assertEquals(textPixel ushr 24, iconPixel ushr 24)
        // Font coverage is not a colour oracle: thin glyphs may have no fully covered pixel.
        // Compare icon paint with the actual text colour composited over this row.
        // LogEntryRowNativeTest separately checks both slots receive the exact Compose colour.
        for (shift in listOf(0, 8, 16)) assertTrue(
            kotlin.math.abs(((textPixel ushr shift) and 255) - ((iconPixel ushr shift) and 255)) <= 1,
            "The link must render with its row's text colour: expected=${textPixel.toUInt().toString(16)}, actual=${iconPixel.toUInt().toString(16)}"
        )
    }
    private fun capture(name: String) {
        System.getenv("MORPHE_LIBRARY_EVIDENCE")?.let { directory ->
            val target = File(directory, name)
            target.parentFile?.mkdirs()
            val bitmap = rule.runOnIdle {
                val decor = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
            }
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue((0 until bitmap.height step 16).flatMap { y ->
                (0 until bitmap.width step 16).map { x -> bitmap.getPixel(x, y) }
            }.toSet().size > 2)
        }
    }
}

/** An indication that draws nothing, so pixel checks see a row's resting colours. */
private object NoPressIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}
