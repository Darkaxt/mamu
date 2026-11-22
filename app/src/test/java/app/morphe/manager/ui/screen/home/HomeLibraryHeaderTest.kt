package app.morphe.manager.ui.screen.home

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.morphe.manager.network.PlayStoreListing
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.viewmodel.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLibraryHeaderTest {
    @get:Rule val rule = createComposeRule()
    private fun row(pkg: String, name: String) = HomeAppItem(pkg, pkg, name, emptyList(), null, null, "1",
        false, false, false, false, false, false, null, false, null, 1, false)
    private val rows = listOf(row("com.example.shopping", "Shopping"), row("com.example.game", "Game"), row("com.example.other", "Other"))
    private val listings = mapOf(rows[0].packageName to PlayStoreListing(rows[0].packageName, null, "SHOPPING"),
        rows[1].packageName to PlayStoreListing(rows[1].packageName, null, "GAME_ACTION"))

    private fun notices(): HomeNotificationsUi {
        val off = AlertState(false) {}
        return HomeNotificationsUi(off, off, off, off, off,
            RepatchAlertState(1, true) {}, BatchQueueAlertState(null) {},
            BundleUpdateState(false, BundleUpdateStatus.Success, null))
    }

    private fun capture(name: String) {
        System.getenv("MORPHE_LIBRARY_EVIDENCE")?.let { path ->
            val file = File(path, "$name.png"); file.parentFile?.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Composable private fun previewRow(name: String, category: String = "Shopping") {
        AppCardLayout(Modifier.testTag("card.$name"), listOf(Color(0xFF151F37), Color(0xFF1C324C)), {}) {
            AppCardContent(null, null, name, "Not patched yet", listOf(Color(0xFF151F37), Color(0xFF1C324C)), category,
                actions = { Row {
                    for (label in listOf("store", "info", "download")) IconButton({}, Modifier.size(48.dp).testTag("$name.$label")) {
                        Icon(Icons.Outlined.Info, label)
                    }
                } })
        }
    }

    @Test fun `native header routes plus menu and counted facets without bottom navigation`() {
        var selection by mutableStateOf(HomeLibrarySelection())
        var action = ""
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFA4C9FF))) {
            Surface { Column(Modifier.fillMaxWidth()) {
                HomeLibraryHeader(projectHomeLibrary(rows, emptyList(), listings, selection, HomeAppFilterMode.ALL),
                    { selection = it }, { action = "add" }, { action = "sort" }, { action = "filter" },
                    { action = "settings" }, { action = "sources" }, { action = "queue" }, { action = "grouping" })
                for (item in projectHomeLibrary(rows, emptyList(), listings, selection, HomeAppFilterMode.ALL).visible)
                    previewRow(item.displayName)
            } }
        } }
        rule.onNodeWithTag("library.add").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals("add", action) }
        rule.onNodeWithTag("library.type.GAMES").performClick()
        rule.onNodeWithTag("library.category.GAME_ACTION").assertIsDisplayed()
        rule.onNodeWithTag("library.category.SHOPPING").assertDoesNotExist()
        rule.onNodeWithTag("library.search").performTextInput("Shopping")
        rule.onNodeWithTag("library.type.GAMES").assertDoesNotExist()
        rule.onNodeWithTag("library.type.APPS").assertIsDisplayed()
        rule.onNodeWithTag("library.more").performClick()
        rule.onNodeWithText("Download queue").performClick()
        rule.runOnIdle { assertEquals("queue", action) }
        rule.onNodeWithTag("library.more").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.runOnIdle { assertEquals("settings", action) }
        capture("library-phone")
    }

    @Test fun `prepared queue remains accessible from compact notices without starting work`() {
        var prepared by mutableIntStateOf(2)
        var opened = false
        rule.setContent { MaterialTheme { Surface {
            HomeLibraryNotices(notices().copy(
                repatchAvailable = RepatchAlertState(0, false) {},
                batchQueue = BatchQueueAlertState(null, prepared) { opened = true }), wide = true)
        } } }
        rule.onNodeWithTag("library.notifications").assertExists()
        rule.onNodeWithText("2 apps in prepared queue").assertIsDisplayed().performClick()
        rule.runOnIdle { assertTrue(opened); prepared = 0 }
        rule.onNodeWithTag("library.notifications").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w840dp-h1000dp-xhdpi")
    fun `tablet native header preserves controls and populated facet counts`() {
        rule.setContent { MaterialTheme { Surface {
            HomeLibraryHeader(projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.ALL),
                {}, {}, {}, {}, {}, {}, {}, {})
        } } }
        rule.onNodeWithTag("library.add").assertIsDisplayed()
        rule.onNodeWithTag("library.more").assertIsDisplayed()
        rule.onNodeWithTag("library.type.GAMES").assertIsDisplayed()
        capture("library-tablet")
    }

    @Test fun `search echoes input while asynchronous catalogue result is still old`() {
        val input = MutableStateFlow(HomeLibrarySelection())
        val heldProjection = projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.ALL)
        rule.setContent { MaterialTheme { Surface {
            val selection by input.collectAsState()
            HomeLibraryHeader(heldProjection, { input.value = it }, {}, {}, {}, {}, {}, {}, {}, inputSelection = selection)
        } } }
        rule.onNodeWithTag("library.search").performTextInput("Ama")
        rule.onNodeWithTag("library.search").assertTextEquals("Ama")
        rule.onNodeWithTag("library.search").performTextInput("zon")
        rule.onNodeWithTag("library.search").assertTextEquals("Amazon")
        rule.runOnIdle { assertEquals("Amazon", input.value.query); assertEquals("", heldProjection.selection.query) }
    }

    @Test @Config(qualifiers = "w1691dp-h1056dp-280dpi")
    fun `actual tablet dark header paints labels and actions without ancestor Surface and tiles span rows`() {
        val apps = (0 until 12).map { row("com.example.$it", "Supported application $it") }
        var clicked = ""
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            // Reproduce actual home integration: a custom black background, no parent Surface,
            // and a black inherited content color. Header must establish its own boundary.
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    HomeLibraryHeader(projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.ALL),
                        {}, { clicked = "add" }, {}, {}, {}, {}, {}, {})
                    // Header/tile readability without active foreground notices is this fixture's scope.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                        items(homeTileRows(apps, 4, false), key = { it.startIndex }) { tileRow ->
                            HomeTileRowLayout(tileRow.items, 4) { _, app ->
                                AppCardLayout(Modifier.testTag("tile.${app.id}"),
                                    listOf(Color(0xFF153853), Color(0xFF20534D)), {}) {
                                    HomeTileContent(app.displayName, "Find everyday essentials and discover helpful offers.", "v32.18.0.100", "", Color.White, Color.LightGray,
                                        icon = { HomeTileIcon(true, "Patched") { Box(Modifier.size(40.dp).testTag("${app.id}.icon").background(Color.Gray)) } },
                                        actions = { for (label in listOf("info", "store", "download")) {
                                            IconButton({ clicked = label }, Modifier.size(48.dp).testTag("${app.id}.$label")) {
                                                Icon(Icons.Outlined.Info, label, tint = Color.White)
                                            }
                                        } })
                                }
                            }
                        }
                    }
                    }
                }
            }
        } }
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        for (node in listOf(rule.onNodeWithText("Morphe Debug"), rule.onNodeWithTag("library.add"),
            rule.onNodeWithTag("library.sort"), rule.onNodeWithTag("library.filter"), rule.onNodeWithTag("library.more"),
            rule.onNodeWithText("All", useUnmergedTree = true), rule.onNodeWithText("Apps", useUnmergedTree = true),
            rule.onNodeWithText("Games", useUnmergedTree = true), rule.onNodeWithText("Others", useUnmergedTree = true))) {
            val b = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            var bright = 0
            for (x in b.left.toInt().coerceAtLeast(0) until b.right.toInt().coerceAtMost(pixels.width))
                for (y in b.top.toInt().coerceAtLeast(0) until b.bottom.toInt().coerceAtMost(pixels.height)) {
                    val c = pixels[x, y]; if (c.red > 0.5f && c.green > 0.5f && c.blue > 0.5f) bright++
                }
            assertTrue(bright > 10, "Header node must paint visible content on black: $b")
        }
        val first = rule.onNodeWithTag("tile.com.example.0").fetchSemanticsNode().boundsInRoot
        assertEquals(rule.onNodeWithTag("library.add").fetchSemanticsNode().boundsInRoot.top,
            rule.onNodeWithText("Morphe Debug").fetchSemanticsNode().boundsInRoot.top, 1f)
        val categories = rule.onNodeWithTag("library.categories").fetchSemanticsNode().boundsInRoot
        assertTrue(first.top - categories.bottom <= 24f * rule.density.density, "No notification strip between categories and tiles")
        val fourth = rule.onNodeWithTag("tile.com.example.3").fetchSemanticsNode().boundsInRoot
        val fifth = rule.onNodeWithTag("tile.com.example.4").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, fourth.top)
        assertTrue(fourth.left > first.right && fifth.top > first.bottom)
        assertTrue(first.height <= 144f * rule.density.density, "Ordinary tiles must stay compact: $first")
        val preview = rule.onAllNodesWithText("Find everyday essentials and discover helpful offers.", useUnmergedTree = true)
            .onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue(preview.height <= 40f * rule.density.density)
        rule.onAllNodesWithText("Patched").assertCountEquals(0)
        val shortcut = rule.onNodeWithTag("com.example.3.download").fetchSemanticsNode().boundsInRoot
        val name = rule.onNodeWithText("Supported application 3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val icon = rule.onNodeWithTag("com.example.3.icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(name.left >= icon.right && name.top < icon.bottom, "Name remains beside the icon")
        assertTrue(shortcut.left >= name.right && shortcut.top <= name.bottom, "Shortcuts stay at the right of the icon/name row: $shortcut vs $name")
        rule.onNodeWithTag("com.example.0.download").assertIsDisplayed().assertWidthIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals("download", clicked) }
        rule.onNodeWithTag("library.add").performClick()
        rule.runOnIdle { assertEquals("add", clicked) }
        capture("tablet-dark-header-grid")
    }

    @Test fun `large font header keeps primary actions visible`() {
        rule.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) { Surface { Column {
                HomeLibraryHeader(projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.ALL),
                    {}, {}, {}, {}, {}, {}, {}, {})
                previewRow("Amazon Shopping")
            } } }
        } }
        rule.onNodeWithTag("library.add").assertIsDisplayed()
        rule.onNodeWithTag("library.more").assertIsDisplayed()
        rule.onNodeWithTag("library.search").assertIsDisplayed()
        rule.onNodeWithTag("Amazon Shopping.download").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("library-large-font")
    }

    @Test fun `phone notices use available width without a tablet sidebar`() {
        rule.setContent { MaterialTheme { Surface { HomeLibraryNotices(notices(), wide = false) } } }
        val notice = rule.onNodeWithTag("library.notifications").fetchSemanticsNode().boundsInRoot
        val viewport = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.width - 32f * rule.density.density, notice.width, 1f)
    }

    @Test @Config(qualifiers = "w1691dp-h1056dp-280dpi")
    fun `tablet notice removal leaves no category strip and comparison colours retain accessible verdicts`() {
        var visible by mutableStateOf(true)
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Column(Modifier.fillMaxWidth().background(Color.Black)) {
            HomeLibraryHeader(projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.ALL),
                {}, {}, {}, {}, {}, {}, {}, {})
            HomeLibraryNotificationHost(notices().copy(repatchAvailable = RepatchAlertState(1, visible) {}), true,
                modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.testTag("versions.first")) {
                for (verdict in PlayVersionVerdict.entries) HomePlayVersionText(HomePlayVersion("1.9.0", "1.10.0", null, verdict))
            }
            }
        } } }
        val categories = rule.onNodeWithTag("library.categories").fetchSemanticsNode().boundsInRoot
        val first = rule.onNodeWithTag("versions.first").fetchSemanticsNode().boundsInRoot
        assertEquals(categories.bottom, first.top, 1f)
        for (v in PlayVersionVerdict.entries) {
            val node = rule.onNodeWithTag("library.play-version.$v").assertIsDisplayed()
                .assertTextEquals("v1.9.0")
            assertTrue(!node.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            var tinted = 0
            for (x in bounds.left.toInt() until bounds.right.toInt()) for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                val c = pixels[x,y]
                if (when (v) {
                    PlayVersionVerdict.UNKNOWN -> c.red > .8f && c.green > .8f && c.blue > .8f
                    PlayVersionVerdict.MATCHED -> c.green > .35f && c.green > c.red * 1.2f && c.green > c.blue * 1.2f
                    PlayVersionVerdict.BEHIND -> c.red > .45f && c.green > .3f && c.blue < c.red * .65f
                }) tinted++
            }
            assertTrue(tinted > 5, "Expected $v text colour")
            assertTrue(node.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.StateDescription))
        }
        capture("play-version-colours")
        rule.runOnIdle { visible = false }
        rule.onNodeWithTag("library.notifications").assertDoesNotExist()
        assertEquals(rule.onNodeWithTag("library.categories").fetchSemanticsNode().boundsInRoot.bottom,
            rule.onNodeWithTag("versions.first").fetchSemanticsNode().boundsInRoot.top, 1f)
        rule.onNodeWithTag("library.add").assertIsDisplayed()
    }

    @Test fun `phone observed versions project to compact supported labels and accessible verdicts`() {
        val samples = listOf("6.1.2" to "6.5", "32.18.0.100" to "32.18.0.100",
            "2.0.3-rc0" to "2.2.1-rc0", "21.20.405" to null)
        val targets = samples.mapIndexed { index, (version, _) -> "com.example.app$index" to listOf(
            app.morphe.manager.domain.bundles.BundledAppTarget(
                app.morphe.patcher.patch.AppTarget(version = version), 1, "Source", setOf(42))) }.toMap()
        val observed = samples.mapIndexed { index, (_, official) -> "com.example.app$index" to
            PlayStoreListing("com.example.app$index", null, null, version = official,
                versionSource = "ds5.application.version", versionObservedAt = 123L, versionVerified = true) }.toMap()
        val versions = projectHomePlayVersions(targets, observed)
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Column {
                versions.values.forEachIndexed { index, value ->
                    Row(Modifier.fillMaxWidth().testTag("version.tile.$index")) {
                        HomeTileContent("Application $index", "A short description", "v0.1", "",
                            Color.White, Color.LightGray,
                            icon = { Box(Modifier.size(40.dp)) }, actions = {},
                            versionFeedback = { HomePlayVersionText(value, fallback = "v0.1") })
                    }
                }
            }
        } }
        samples.forEachIndexed { index, (supported, _) ->
            val version = rule.onNodeWithText("v$supported").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val name = rule.onNodeWithText("Application $index").fetchSemanticsNode().boundsInRoot
            assertTrue(version.top >= name.bottom, "Only version belongs directly below the app name")
            assertEquals(name.left, version.left, 1f)
        }
        rule.onAllNodesWithText("v0.1").assertCountEquals(0)
        rule.onAllNodesWithText("Supported", substring = true).assertCountEquals(0)
        rule.onAllNodesWithTag("library.play-version.BEHIND").assertCountEquals(2)
        rule.onAllNodesWithTag("library.play-version.MATCHED").assertCountEquals(1)
        rule.onAllNodesWithTag("library.play-version.UNKNOWN").assertCountEquals(1)
        rule.onAllNodesWithText("Play", substring = true).assertCountEquals(0)
        capture("phone-version-feedback")
    }

    @Test fun `phone row replaces installed version with one coloured supported version`() {
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            AppCardLayout(gradientColors = listOf(Color.DarkGray, Color.Black), onClick = {}) {
                AppCardContent(null, null, "Example app", "v1.0", listOf(Color.DarkGray, Color.Black),
                    versionFeedback = { HomePlayVersionText(
                        HomePlayVersion("2.0", "2.0", 123L, PlayVersionVerdict.MATCHED), fallback = "v1.0") })
            }
        } } }
        val name = rule.onNodeWithText("Example app", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val version = rule.onNodeWithText("v2.0", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(name.left, version.left, 1f)
        assertTrue(version.top >= name.bottom)
        rule.onAllNodesWithText("v2.0", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText("v1.0").assertDoesNotExist()
        rule.onAllNodesWithText("Supported", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Play", substring = true).assertCountEquals(0)
        capture("phone-single-version-row")
    }

    @Test fun `home Play shortcut requires its original listing and preserves cached validity`() {
        val item = row("com.example.app", "Example")
        var metadata by mutableStateOf<Map<String, PlayStoreListing>>(emptyMap())
        var opened: String? = null
        val handler = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) { opened = uri }
        }
        rule.setContent { MaterialTheme { CompositionLocalProvider(
            LocalStoreListings provides metadata,
            androidx.compose.ui.platform.LocalUriHandler provides handler
        ) { HomeAppRowShortcuts(item, Color.White, null) } } }
        val tag = "library.play-link.com.example.app"
        rule.onNodeWithTag(tag).assertDoesNotExist()
        // A restored positive listing stays useful even while its version is unverified/offline.
        val cached = PlayStoreListing(item.packageName, null, "VIDEO_PLAYERS", name = "Example")
        rule.runOnIdle { metadata = mapOf(item.packageName to cached) }
        rule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("https://play.google.com/store/apps/details?id=com.example.app", opened) }
        rule.runOnIdle { metadata = mapOf(item.packageName to cached.copy(packageName = "com.example.related")) }
        rule.onNodeWithTag(tag).assertDoesNotExist()
        rule.runOnIdle { metadata = mapOf(item.packageName to cached) }
        rule.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun `tile badges distinguish patched unpatched and uncertain without status text rows`() {
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((state, label) in listOf(true to "Patched", false to "Not patched", null to "Unverified")) {
                    HomeTileIcon(state, label) { Box(Modifier.size(40.dp).background(Color.Gray)) }
                }
            }
        } } }
        for (label in listOf("Patched", "Not patched", "Unverified")) {
            rule.onNodeWithContentDescription(label).assertIsDisplayed()
            rule.onNodeWithText(label).assertDoesNotExist()
        }
        rule.onAllNodesWithTag("library.patch-badge.patched", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("library.patch-badge.uncertain", useUnmergedTree = true).assertCountEquals(1)
        capture("tile-patch-badges")
    }

    @Test @Config(qualifiers = "w1691dp-h1056dp-280dpi")
    fun `tiles have equal compact dimensions with missing descriptions long names and exceptional status`() {
        val apps = (0..3).map { row("com.example.$it", "App $it") }
        val titles = listOf("Short", "An application with a very long display name", "No description", "Clone")
        val descriptions = listOf(null, "An opening paragraph with enough detail to occupy both preview lines. ".repeat(4), "Brief.", null)
        val states = listOf("", "An update is available", "Uninstalled", "Clone")
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            HomeTileRowLayout(apps, 4) { index, app ->
                AppCardLayout(Modifier.testTag("uniform.${app.id}"), listOf(Color(0xFF153853), Color(0xFF20534D)), {}) {
                    HomeTileContent(titles[index], descriptions[index], if (index == 0) "" else "v32.18.0.100", states[index],
                        Color.White, Color.LightGray, icon = { Box(Modifier.size(40.dp).background(Color.Gray)) },
                        actions = { repeat(3) { IconButton({}, Modifier.size(48.dp)) { Icon(Icons.Outlined.Info, null) } } })
                }
            }
        } } }
        val bounds = apps.map { rule.onNodeWithTag("uniform.${it.id}").fetchSemanticsNode().boundsInRoot }
        for (tile in bounds) {
            assertEquals(bounds.first().height, tile.height, 1f)
            assertEquals(bounds.first().width, tile.width, 1f)
            assertTrue(tile.height <= 144f * rule.density.density)
        }
        capture("tablet-uniform-tiles")
    }

    @Test @Config(qualifiers = "w1056dp-h1691dp-280dpi")
    fun `portrait tablet tiles grow for large text and keep all shortcuts within each tile`() {
        val versionOverflow = mutableMapOf<String, Boolean>()
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                Column(Modifier.fillMaxWidth().background(Color.Black)) {
                    HomeLibraryHeader(projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.ALL),
                        {}, {}, {}, {}, {}, {}, {}, {})
                    HomeTileRowLayout(rows.take(2), 2) { _, app ->
                        AppCardLayout(Modifier.testTag("large.${app.id}"), listOf(Color(0xFF153853), Color(0xFF20534D)), {}) {
                            HomeTileContent(if (app.id == rows.first().id) "A supported application with a longer name" else "Short",
                                if (app.id == rows.first().id) "A short preview of the opening description paragraph, with useful details about the app." else null,
                                "v32.18.0.100", if (app.id == rows.first().id) "" else "Uninstalled",
                                Color.White, Color.LightGray, icon = { HomeTileIcon(false, "Not patched") { Box(Modifier.size(40.dp).background(Color.Gray)) } },
                                actions = { for (label in listOf("info", "store", "download")) {
                                    IconButton({}, Modifier.size(48.dp).testTag("large.${app.id}.$label")) {
                                        Icon(Icons.Outlined.Info, label, tint = Color.White)
                                    }
                                } }, versionFeedback = {
                                    androidx.compose.material3.Text(
                                        listOf("v32.18.0.100", "Uninstalled".takeIf { app.id != rows.first().id })
                                            .filterNotNull().joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium, color = Color.LightGray,
                                        minLines = 1, maxLines = 1,
                                        onTextLayout = { versionOverflow[app.id] = it.didOverflowHeight })
                                })
                        }
                    }
                }
            }
        } }
        for (app in rows.take(2)) {
            val tile = rule.onNodeWithTag("large.${app.id}").fetchSemanticsNode().boundsInRoot
            for (label in listOf("info", "store", "download")) {
                val action = rule.onNodeWithTag("large.${app.id}.$label").assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                assertTrue(action.left >= tile.left && action.right <= tile.right && action.bottom <= tile.bottom)
            }
        }
        assertEquals(rule.onNodeWithTag("large.${rows[0].id}").fetchSemanticsNode().boundsInRoot.height,
            rule.onNodeWithTag("large.${rows[1].id}").fetchSemanticsNode().boundsInRoot.height, 1f)
        rule.onNodeWithTag("library.add").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Not patched").assertCountEquals(2)
        rule.runOnIdle {
            assertEquals(2, versionOverflow.size)
            assertTrue(versionOverflow.values.none { it }, "Each version line must fit at enlarged font scale")
        }
        capture("tablet-large-font-tiles")
    }

    @Test @Config(qualifiers = "w320dp-h844dp-xxhdpi")
    fun `narrow native rows keep title and all actions within an expanding card`() {
        rule.setContent { MaterialTheme { Surface { previewRow("Amazon Shopping") } } }
        val card = rule.onNodeWithTag("card.Amazon Shopping").fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithText("Amazon Shopping").fetchSemanticsNode().boundsInRoot
        for (label in listOf("store", "info", "download")) {
            val node = rule.onNodeWithTag("Amazon Shopping.$label").assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            assertTrue(node.bottom <= card.bottom && node.right <= card.right && node.left >= card.left)
        }
        assertTrue(title.width > 100 * rule.density.density)
        assertTrue(title.top >= card.top && title.bottom <= card.bottom)
        capture("library-narrow-row")
    }
}
