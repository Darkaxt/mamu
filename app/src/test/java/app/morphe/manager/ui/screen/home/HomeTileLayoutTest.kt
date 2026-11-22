package app.morphe.manager.ui.screen.home

import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.data.room.apps.installed.InstalledApp
import app.morphe.manager.ui.model.HomeAppItem
import kotlin.test.*

class HomeTileLayoutTest {
    private fun row(name: String) = HomeAppItem(name, name, name, emptyList(), null, null, "1",
        false, true, false, false, false, false, null, false, null, 1, false)
    private fun patched(name: String, update: Boolean = false) = row(name).let {
        it.copy(installedApp = InstalledApp(name, name, "1", InstallType.DEFAULT), hasUpdate = update)
    }

    @Test fun `tablet and split window columns respect real width and font scaling`() {
        assertEquals(1, homeTileColumns(390f, 1f))
        assertEquals(1, homeTileColumns(599f, 1f))
        assertEquals(2, homeTileColumns(600f, 1f))
        assertEquals(3, homeTileColumns(1024f, 1f))
        assertEquals(4, homeTileColumns(1552f, 1f))
        assertEquals(1, homeTileColumns(600f, 1.6f))
        assertEquals(3, homeTileColumns(1552f, 1.6f))
    }

    @Test fun `tile icon follows actual renamed install and version never impersonates an uninstalled app`() {
        val renamed = row("com.original").copy(installedApp = InstalledApp("com.renamed", "com.original", "1", InstallType.DEFAULT))
        assertEquals("com.renamed", homeTileIconPackage(renamed))
        assertEquals("com.original", homeTileIconPackage(row("com.original")))
        assertEquals("v1", homeTileSubtitle(renamed.copy(isInstalledOnDevice = false)))
        assertEquals("", homeTileSubtitle(row("com.original").copy(isInstalledOnDevice = false, version = "999")))
        assertEquals("variant", homeTileSubtitle(row("com.original").copy(isInstalledOnDevice = false, nameSuffix = "variant")))
    }

    @Test fun `packing preserves app identities order and recommendation boundaries`() {
        val items = listOf(patched("A", true), patched("B"), patched("C"), row("D"), row("E"), row("F"))
        val result = homeTileRows(items, 4, true)
        assertEquals(listOf(0, 1, 2), result.map { it.priority })
        assertEquals(listOf(1, 2, 3), result.map { it.items.size })
        assertEquals(listOf(0, 1, 3), result.map { it.startIndex })
        assertEquals(items, result.flatMap { it.items })
        assertEquals(items.map { listOf(it) }, homeTileRows(items, 1, true).map { it.items })
        assertEquals(listOf(4, 2), homeTileRows(items, 4, false).map { it.items.size })
    }

    @Test fun `alphabet scroll targets address tile rows and account for collapsed source headers`() {
        val items = listOf("A", "B", "C", "D", "E").map(::row)
        assertEquals(listOf(0, 0, 0, 1, 1), buildFlatHomeScrollTargets(items, 3).map { it.listIndex })
        val groups = listOf(
            HomeCategoryGroup("hidden", title = "Hidden", items = listOf(row("Z")), collapsed = true, collapsible = true, editable = true),
            HomeCategoryGroup("shown", title = "Shown", items = items, collapsed = false, collapsible = true, editable = true))
        assertEquals(listOf(2, 2, 2, 3, 3), buildGroupedHomeScrollTargets(groups, 3).map { it.listIndex })
    }
}
