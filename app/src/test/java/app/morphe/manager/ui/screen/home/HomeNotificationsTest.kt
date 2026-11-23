package app.morphe.manager.ui.screen.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.data.room.AppDatabase
import app.morphe.manager.domain.batch.*
import app.morphe.manager.domain.manager.*
import app.morphe.manager.di.*
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.ui.viewmodel.BundleUpdateStatus
import app.morphe.manager.ui.viewmodel.HomeLibrarySelection
import app.morphe.manager.ui.viewmodel.projectHomeLibrary
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeNotificationsTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var koin: org.koin.core.Koin

    @Before fun setup() {
        koin = startKoin { androidContext(app); modules(httpModule, preferencesModule,
            repositoryModule, serviceModule, managerModule, databaseModule) }.koin
    }

    @After fun close() {
        // The real repository is used for source-icon lookup; no reload/network request is started.
        val repo = koin.get<PatchBundleRepository>()
        val field = PatchBundleRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        runBlocking { (field.get(repo) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin() }
        koin.get<HttpClient>().close()
        koin.get<AppDatabase>().close()
        stopKoin()
    }

    private fun notices(): HomeNotificationsUi {
        val off = AlertState(false) {}
        val on = AlertState(true) {}
        return HomeNotificationsUi(off, on, on, off, off,
            RepatchAlertState(1, true) {}, BatchQueueAlertState(null, preparedCount = 1) {},
            BundleUpdateState(true, BundleUpdateStatus.Updating, null))
    }

    private fun emptyNotices(): HomeNotificationsUi {
        val off = AlertState(false) {}
        return HomeNotificationsUi(off, off, off, off, off,
            RepatchAlertState(0, false) {}, BatchQueueAlertState(null) {},
            BundleUpdateState(false, BundleUpdateStatus.Success, null))
    }

    private val emptyProjection get() = projectHomeLibrary(emptyList(), emptyList(), emptyMap(),
        HomeLibrarySelection(), HomeAppFilterMode.ALL)

    private fun capture(name: String) {
        System.getenv("MORPHE_LIBRARY_EVIDENCE")?.let { path ->
            val file = File(path, "$name.png"); file.parentFile?.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Composable private fun actualHome(state: HomeNotificationsUi, fontScale: Float = 1f) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                // HomeScreen applies system status-bar padding before SectionsLayout.
                Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 24.dp)) {
                    SectionsLayout(state,
                        HomeAppListUi(emptyList(), emptyList(), false, false, HomeAppSortMode.MANUAL,
                            HomeAppFilterMode.ALL, HomeAppCategoryState(emptyList(), emptyMap()),
                            HomeAppCategoryViewMode.ALL_APPS, false, emptyList(), library = emptyProjection),
                        HomeAppActions(onAppClick = {}, onHideApp = {}, onHideMultiple = {},
                            onUninstallMultiple = {}, onReinstallMultiple = {}, onPatchMultiple = {},
                            onUnhideApp = {}, onShowPatches = {}, onGestureHintShown = {}, onSaveOrder = {},
                            onSaveSourceOrder = { _, _ -> }, onResetOrder = {}, onResetSourceOrder = {},
                            onSaveSourceGroupOrder = {}, onSortModeChange = {}, onFilterModeChange = {},
                            onCategoryViewModeChange = {}, onCreateCategory = { "test" },
                            onRenameCategory = { _, _ -> }, onDeleteCategory = {}, onSaveCategoryOrder = {},
                            onToggleCategoryCollapsed = {}, onToggleSourceGroupCollapsed = {},
                            onAssignAppsToCategory = { _, _ -> }),
                        HomeChromeActions({}, {}, {}, null), HomeChromeFlags(true, true, true, true), null)
                }
            }
        }
    }

    private fun assertScreenTop(fontScale: Float = 1f, name: String) {
        var state by mutableStateOf(emptyNotices())
        rule.setContent { actualHome(state, fontScale) }
        val categoriesBefore = rule.onNodeWithTag("library.categories").fetchSemanticsNode().boundsInRoot
        val headerBefore = rule.onNodeWithTag("library.add").fetchSemanticsNode().boundsInRoot
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals((screen.width - 1600f * rule.density.density).coerceAtLeast(0f) / 2f +
            12f * rule.density.density, categoriesBefore.left, 1f, "Wide Home content must retain its centered1600dp cap")
        rule.runOnIdle { state = notices() }
        val panel = rule.onNodeWithTag("library.notifications").fetchSemanticsNode().boundsInRoot
        val progress = rule.onNodeWithText(app.getString(R.string.home_updating_sources))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(32f * rule.density.density, panel.top, 1f,
            "Notice must start8dp below system-safe screen top, above the tabs: $panel / $categoriesBefore")
        assertTrue(panel.top < headerBefore.bottom && panel.top < categoriesBefore.top)
        assertTrue(progress.bottom <= panel.bottom)
        assertEquals(categoriesBefore, rule.onNodeWithTag("library.categories").fetchSemanticsNode().boundsInRoot)
        assertEquals(headerBefore, rule.onNodeWithTag("library.add").fetchSemanticsNode().boundsInRoot)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        var painted = 0
        var count = 0
        for (x in progress.left.toInt() until progress.right.toInt() step 4) {
            for (y in progress.top.toInt() until progress.bottom.toInt() step 4) {
                val c = pixels[x, y]
                if (c.red > .02f || c.green > .02f || c.blue > .02f) painted++
                count++
            }
        }
        assertTrue(painted > count * .8f, "Notice must paint above the actual header")
        capture(name)
        rule.runOnIdle { state = emptyNotices() }
        rule.onNodeWithTag("library.notifications").assertDoesNotExist()
        assertEquals(categoriesBefore, rule.onNodeWithTag("library.categories").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("library.more").performClick()
        rule.onNodeWithText(app.getString(R.string.settings)).assertIsDisplayed()
    }

    @Test fun screenTopPhoneOverlaysActualHeader() = assertScreenTop(name = "screen-top-phone")

    @Test @Config(qualifiers = "w750dp-h832dp-420dpi")
    fun screenTopFoldOverlaysActualHeader() = assertScreenTop(name = "screen-top-fold")

    @Test @Config(qualifiers = "w1691dp-h1056dp-280dpi")
    fun screenTopTabletLargeFontOverlaysActualHeader() = assertScreenTop(1.6f, "screen-top-tablet-large-font")

    private fun assertBatchDismissal(running: Boolean) {
        var opened = 0
        val run = BatchRunState(emptyList(), BatchPhase.RUNNING, BatchInstallPolicy.SAVE_ONLY)
        var batch by mutableStateOf(BatchQueueAlertState(if (running) run else null, if (running) 0 else 2) { opened++ })
        rule.setContent { actualHome(emptyNotices().copy(batchQueue = batch)) }
        rule.onNodeWithText(app.getString(R.string.batch_patch_title)).assertIsDisplayed()
            .performTouchInput { swipeLeft() }
        rule.onNodeWithText(app.getString(R.string.batch_patch_title)).assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, opened); assertEquals(if (running) run else null, batch.run) }
        // Recomposition/progress must not revive a dismissed card or alter prepared intent.
        rule.runOnIdle { batch = batch.copy(run = batch.run?.copy(activeIndex = 1)) }
        rule.onNodeWithText(app.getString(R.string.batch_patch_title)).assertDoesNotExist()
        rule.onNodeWithTag("library.more").performClick()
        rule.onNodeWithText(app.getString(R.string.batch_patch_title)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, opened); assertEquals(if (running) 0 else 2, batch.preparedCount) }
        rule.runOnIdle { batch = BatchQueueAlertState(null) {} }
        rule.runOnIdle { batch = BatchQueueAlertState(run) { opened++ } }
        rule.onNodeWithText(app.getString(R.string.batch_patch_title)).assertIsDisplayed()
        capture(if (running) "batch-running-next-run" else "batch-prepared-next-run")
        rule.onNodeWithText(app.getString(R.string.batch_patch_title)).performTouchInput { swipeRight() }
        val result = BatchPatchItem(BatchTarget("com.example.batch"), "Example", null,
            emptyMap(), emptyMap(), bundles = emptyList(), state = BatchItemState.SUCCEEDED)
        rule.runOnIdle { batch = BatchQueueAlertState(run.copy(items = listOf(result), phase = BatchPhase.FINISHED)) { opened++ } }
        rule.onNodeWithText(app.getString(R.string.patcher_complete_title)).assertIsDisplayed()
            .performTouchInput { swipeLeft() }
        rule.onNodeWithText(app.getString(R.string.patcher_complete_title)).assertDoesNotExist()
        rule.onNodeWithTag("library.more").performClick()
        rule.onNodeWithText(app.getString(R.string.batch_patch_title)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(2, opened); assertEquals(result, batch.run?.items?.single()) }
    }

    @Test fun runningBatchCanBeDismissedAndReopenedFromOverflow() = assertBatchDismissal(true)
    @Test fun preparedBatchWaitsInOverflowWithoutACard() {
        var opened = 0
        rule.setContent { actualHome(emptyNotices().copy(batchQueue = BatchQueueAlertState(null, 2) { opened++ })) }
        rule.onNodeWithTag("library.notifications").assertDoesNotExist()
        rule.onNodeWithTag("library.more").performClick()
        rule.onNodeWithText(app.getString(R.string.batch_patch_title)).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun sourceFailuresStayInDiagnosticsWithoutGenericHomeBanner() {
        val repository = koin.get<PatchBundleRepository>()
        val field = PatchBundleRepository::class.java.getDeclaredField("metadataFetchErrorsFlow")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val errors = field.get(repository) as MutableStateFlow<Map<Int, Throwable>>
        val failures = mapOf(0 to IOException("Default source refresh failed"),
            123 to IOException("Community source HTTP404"), 456 to IOException("Cached bundle cannot load"))
        errors.value = failures
        var state by mutableStateOf(notices().copy(managerUpdate = AlertState(true) {},
            blockedSources = AlertState(true) {}, meteredSkipped = AlertState(true) {}))
        rule.setContent { actualHome(state) }
        rule.onNodeWithText(app.getString(R.string.home_metadata_errors_title)).assertDoesNotExist()
        for (title in listOf(R.string.home_outdated_manager_title, R.string.home_held_back_title,
            R.string.home_blocked_source_title, R.string.home_update_available, R.string.home_source_updates_title)) {
            rule.onNodeWithText(app.getString(title)).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText(app.getString(R.string.home_updating_sources)).performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(failures, repository.metadataFetchErrors.value); state = emptyNotices() }
        rule.onNodeWithTag("library.notifications").assertDoesNotExist()
        rule.onNodeWithTag("library.more").performClick()
        rule.onNodeWithText(app.getString(R.string.sources)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(failures, repository.metadataFetchErrors.value) }
        capture("source-errors-no-home-banner")
    }

    @Composable private fun fixture(state: HomeNotificationsUi, wide: Boolean, fontScale: Float = 1f,
        onOutside: () -> Unit = {}, onAdd: () -> Unit = {}) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HomeLibraryNotificationHost(state, wide, modifier = Modifier.fillMaxSize().background(Color.Black), bottomInset = 48.dp) {
                    Column(Modifier.fillMaxSize()) {
                        HomeLibraryHeader(emptyProjection, {}, onAdd, {}, {}, {}, {}, {}, {})
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Box(Modifier.fillMaxSize().background(Color.Magenta).testTag("notification.list")) {
                                Button(onOutside, Modifier.align(Alignment.BottomStart).testTag("notification.outside")) {
                                    Text("List action")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun activeUpdateRemainsFullyVisibleWithConcurrentSourceAndBatchNotices() {
        rule.setContent { MaterialTheme { Surface { HomeLibraryNotices(notices(), wide = false) } } }
        val progress = rule.onNodeWithText(app.getString(R.string.home_updating_sources))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = rule.onNodeWithTag("library.notifications").fetchSemanticsNode().boundsInRoot
        assertTrue(progress.top >= viewport.top && progress.bottom <= viewport.bottom,
            "Active update must not sit below the clipped alert stack: $progress vs $viewport")
        capture("notification-phone-progress")
    }

    @Test @Config(qualifiers = "w750dp-h832dp-420dpi")
    fun foldNotificationPaintsAboveListWithoutMovingHeaderAndOutsideActionsRemainUsable() {
        var state by mutableStateOf(notices())
        var clicked = ""
        rule.setContent { fixture(state, wide = true, onOutside = { clicked = "list" }, onAdd = { clicked = "add" }) }
        val listBefore = rule.onNodeWithTag("notification.list").fetchSemanticsNode().boundsInRoot
        val categories = rule.onNodeWithTag("library.categories").fetchSemanticsNode().boundsInRoot
        val progress = rule.onNodeWithText(app.getString(R.string.home_updating_sources)).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("library.notifications").fetchSemanticsNode().boundsInRoot
        capture("notification-fold-foreground")
        assertTrue(panel.top < categories.top && panel.right <= listBefore.right, "$panel vs $categories / $listBefore")
        // 340dp is892.5px at the actual fold's420dpi; measured bounds round to whole pixels.
        assertTrue(panel.width <= 340f * rule.density.density + 1f && panel.left > listBefore.center.x,
            "Floating panel bounds: $panel, list: $listBefore, density: ${rule.density.density}")
        assertTrue(panel.bottom <= listBefore.bottom - 48f * rule.density.density + 1f,
            "Notice must clear the footer: $panel vs $listBefore")
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        var foreground = 0
        var sampled = 0
        for (x in progress.left.toInt() until progress.right.toInt() step 4) {
            for (y in progress.top.toInt() until progress.bottom.toInt() step 4) {
                val c = pixels[x, y]
                if (!(c.red > .95f && c.green < .05f && c.blue > .95f)) foreground++
                sampled++
            }
        }
        assertTrue(foreground > sampled * .8f, "The actual rendered card must paint above Home content")
        capture("notification-fold-foreground")
        rule.onNodeWithTag("notification.outside").performClick()
        rule.runOnIdle { assertEquals("list", clicked) }
        rule.runOnIdle { state = emptyNotices() }
        rule.onNodeWithTag("library.notifications").assertDoesNotExist()
        rule.onNodeWithTag("library.add").performClick()
        rule.runOnIdle { assertEquals("add", clicked) }
        assertEquals(listBefore, rule.onNodeWithTag("notification.list").fetchSemanticsNode().boundsInRoot)
    }

    @Test @Config(qualifiers = "w320dp-h844dp-xxhdpi")
    fun largeFontPhoneKeepsProgressReadableAndOtherNoticesReachable() {
        var clicked = false
        val state = notices().copy(heldBackSources = AlertState(true) { clicked = true })
        rule.setContent { fixture(state, wide = false, fontScale = 1.6f) }
        val title = app.getString(R.string.home_updating_sources)
        val progress = rule.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("library.notifications").fetchSemanticsNode().boundsInRoot
        assertTrue(progress.top >= panel.top && progress.bottom <= panel.bottom)
        capture("notification-phone-large-font")
        rule.onNodeWithText(app.getString(R.string.home_held_back_title)).performScrollTo().performClick()
        rule.runOnIdle { assertTrue(clicked) }
        rule.onNodeWithTag("library.add").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("library.more").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
    }

    @Test @Config(qualifiers = "w1691dp-h1056dp-280dpi")
    fun terminalUpdateScrollsIntoViewAndDismissalDoesNotHideOtherAlerts() {
        var state by mutableStateOf(notices())
        rule.setContent { fixture(state, wide = true) }
        rule.onNodeWithText(app.getString(R.string.home_held_back_title)).performScrollTo()
        rule.runOnIdle { state = state.copy(bundleUpdate = BundleUpdateState(true, BundleUpdateStatus.Success, null)) }
        val success = rule.onNodeWithText(app.getString(R.string.home_update_success)).assertIsDisplayed()
        success.performTouchInput { swipeLeft() }
        success.assertDoesNotExist()
        rule.onNodeWithText(app.getString(R.string.home_held_back_title)).performScrollTo().assertIsDisplayed()
        capture("notification-tablet-dismissed-result")
    }
}
