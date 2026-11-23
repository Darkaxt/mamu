package app.morphe.manager.ui.viewmodel

import android.content.pm.PackageInfo
import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.data.room.apps.installed.InstalledApp
import app.morphe.manager.domain.bundles.AppVersionStatus
import app.morphe.manager.domain.manager.HomeAppSortMode
import app.morphe.manager.ui.model.HomeAppItem
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeAppSortingTest {
    private fun item(name: String, id: String = name) = HomeAppItem(
        id = id, packageName = id, displayName = name, gradientColors = emptyList(),
        installedApp = null, packageInfo = null, version = "1.0",
        isPinnedByDefault = false, isInstalledOnDevice = true, isDeleted = false,
        isInstallStateNotPatched = false, isInstallStateUnknown = false,
        isInstallStatePending = false, savedApkFile = null, hasUpdate = false,
        versionStatus = null, patchCount = 0, isClone = false
    )

    private fun record(item: HomeAppItem, patchedAt: Long) = InstalledApp(
        item.id, item.packageName, "1.0", InstallType.DEFAULT, patchedAt = patchedAt
    )

    private fun List<HomeAppItem>.ordered(mode: HomeAppSortMode) =
        sortHomeAppItems(this, mode, emptyList())

    @Test
    fun `updates then currently patched then rest each alphabetically without official priority`() {
        val official = item("YouTube").copy(isPinnedByDefault = true, packageInfo = PackageInfo())
        val patched = item("Showly").let { it.copy(installedApp = record(it, 100)) }
        val cards = listOf(
            official, item("zebra").let { it.copy(installedApp = record(it, 1), hasUpdate = true) },
            patched, item("Bravo"),
            item("amazon").let { it.copy(installedApp = record(it, 2),
                versionStatus = AppVersionStatus("1.0", "2.0", true)) }
        )
        for (mode in listOf(HomeAppSortMode.RECOMMENDED, HomeAppSortMode.UPDATES_FIRST)) {
            assertEquals(listOf("amazon", "zebra", "Showly", "Bravo", "YouTube"),
                cards.ordered(mode).map { it.displayName }, mode.name)
            assertEquals(cards.ordered(mode), cards.reversed().ordered(mode))
        }
    }

    @Test
    fun `uninstalled historical clone gets no priority over ordinary apps or live updates`() {
        val shopping = item("Amazon Shopping").let { it.copy(installedApp = record(it, 200)) }
        val sorted = item("Amazon Sorted").let {
            it.copy(installedApp = record(it, 100), isInstalledOnDevice = false,
                isDeleted = true, isClone = true, isPinnedByDefault = true,
                hasUpdate = true, versionStatus = AppVersionStatus("1.0", "2.0", true))
        }
        val update = item("Zulu").let { it.copy(installedApp = record(it, 300), hasUpdate = true) }
        val cards = listOf(sorted, shopping, item("Alpha"), update)
        for (mode in listOf(HomeAppSortMode.RECOMMENDED, HomeAppSortMode.UPDATES_FIRST)) {
            assertEquals(listOf("Zulu", "Amazon Shopping", "Alpha", "Amazon Sorted"),
                cards.ordered(mode).map { it.displayName }, mode.name)
        }
    }

    @Test
    fun `settled update badges determine priority and clearing an update restores alphabetical order`() {
        val pending = item("Zulu pending").copy(hasUpdate = true, isInstallStatePending = true)
        val unknown = item("Zulu unknown").copy(hasUpdate = true, isInstallStateUnknown = true)
        val deleted = item("Zulu deleted").copy(hasUpdate = true, isDeleted = true)
        val replaced = item("Zulu replaced").copy(hasUpdate = true, isInstallStateNotPatched = true)
        val updated = item("Beta").let { it.copy(installedApp = record(it, 100), hasUpdate = true) }
        val cards = listOf(pending, unknown, deleted, replaced, item("Alpha"), updated)
        assertEquals(listOf("Beta", "Alpha", "Zulu deleted", "Zulu pending", "Zulu replaced", "Zulu unknown"),
            cards.ordered(HomeAppSortMode.RECOMMENDED).map { it.displayName })
        val cleared = cards.map { it.copy(hasUpdate = false) }.ordered(HomeAppSortMode.RECOMMENDED)
        assertEquals(listOf("Beta", "Alpha"), cleared.take(2).map { it.displayName })
    }

    @Test
    fun `clone identities break equal case-insensitive names deterministically`() {
        val first = item("Example", "com.example")
        val clone = item("example", "com.example.clone").copy(packageName = first.packageName, isClone = true)
        assertEquals(listOf(first, clone), listOf(clone, first).ordered(HomeAppSortMode.RECOMMENDED))
    }

    @Test
    fun `manual order is preserved and unassigned apps follow the new default`() {
        val cards = listOf(item("Zulu").copy(isPinnedByDefault = true), item("Alpha"),
            item("Bravo").let { it.copy(installedApp = record(it, 100), hasUpdate = true) })
        assertEquals(listOf("Zulu", "Bravo", "Alpha"),
            sortHomeAppItems(cards, HomeAppSortMode.MANUAL, listOf("Zulu")).map { it.id })
        assertEquals(cards.ordered(HomeAppSortMode.RECOMMENDED), cards.ordered(HomeAppSortMode.MANUAL))
    }

    @Test
    fun `explicit name and recently patched sorting remain available without official priority`() {
        val alpha = item("Alpha").let { it.copy(installedApp = record(it, 1)) }
        val zulu = item("Zulu").let { it.copy(installedApp = record(it, 2)) }
        val bravo = item("Bravo").copy(isPinnedByDefault = true)
        val cards = listOf(zulu, bravo, alpha)
        assertEquals(listOf("Alpha", "Bravo", "Zulu"), cards.ordered(HomeAppSortMode.NAME_ASC).map { it.id })
        assertEquals(listOf("Zulu", "Bravo", "Alpha"), cards.ordered(HomeAppSortMode.NAME_DESC).map { it.id })
        assertEquals(listOf("Zulu", "Alpha", "Bravo"), cards.ordered(HomeAppSortMode.RECENTLY_PATCHED).map { it.id })
        assertEquals(listOf("Alpha", "Bravo"), listOf(bravo, item("Alpha"))
            .ordered(HomeAppSortMode.RECENTLY_PATCHED).map { it.id })
    }

    @Test
    fun `startup cache reorders visible and hidden cards using current preferences`() {
        val official = item("YouTube").copy(isPinnedByDefault = true)
        val versionUpdate = item("Bravo").let { it.copy(installedApp = record(it, 100),
            versionStatus = AppVersionStatus("1.0", "2.0", true)) }
        val patched = item("Zulu").let { it.copy(installedApp = record(it, 200)) }
        val cards = listOf(official, item("Alpha"), patched, versionUpdate)
        val records = listOfNotNull(patched.installedApp, versionUpdate.installedApp)
        val cache = CachedHome(cards.map(::CachedCard), cards.map(::CachedCard), emptyList())
        val prefs = HomePrefs(emptySet(), emptyList(), emptyMap(), HomeAppSortMode.RECOMMENDED)
        val restored = cache.toCards(records, prefs)
        // Last-run install flags are presentation cache, not current patched-install evidence.
        assertEquals(listOf("Alpha", "Bravo", "YouTube", "Zulu"), restored.visible.map { it.id })
        assertEquals(emptyList(), restored.hidden)
        val hiddenNow = cache.toCards(records, prefs.copy(hiddenPackages = cards.map { it.id }.toSet()))
        assertEquals(emptyList(), hiddenNow.visible)
        assertEquals(restored.visible, hiddenNow.hidden)
        val manual = cache.toCards(records, prefs.copy(sortMode = HomeAppSortMode.MANUAL,
            customOrder = listOf("YouTube", "Alpha", "Bravo", "Zulu")))
        assertEquals(listOf("YouTube", "Alpha", "Bravo", "Zulu"), manual.visible.map { it.id })
        assertEquals(listOf("Alpha", "Bravo", "YouTube", "Zulu"),
            cache.toCards(emptyList(), prefs).visible.map { it.id })
    }

    @Test
    fun `tablet stock apps with newer supported versions cannot outrank patched app updates`() {
        val youtube = item("YouTube Morphe").let { it.copy(installedApp = record(it, 1), hasUpdate = true) }
        val music = item("YT Music Morphe").let { it.copy(installedApp = record(it, 2),
            versionStatus = AppVersionStatus("1.0", "2.0", true)) }
        val stock = listOf("AfterShip", "Amazon Shopping", "Photos", "Samsung Keyboard").map {
            item(it).copy(versionStatus = AppVersionStatus("1.0", "2.0", true))
        }
        val patched = item("Zed patched").let { it.copy(installedApp = record(it, 3)) }
        val clone = item("Alpha clone").let { it.copy(installedApp = record(it, 4), isClone = true) }
        val cards = stock + listOf(music, patched, youtube, clone, item("#Notepad"))
        for (mode in listOf(HomeAppSortMode.RECOMMENDED, HomeAppSortMode.UPDATES_FIRST)) {
            assertEquals(listOf("YouTube Morphe", "YT Music Morphe", "Alpha clone", "Zed patched",
                "#Notepad", "AfterShip", "Amazon Shopping", "Photos", "Samsung Keyboard"),
                cards.ordered(mode).map { it.displayName }, mode.name)
        }
    }

    @Test
    fun `a tracked record alone never admits excluded install states into either leading group`() {
        val base = item("Alpha historical").let { it.copy(installedApp = record(it, 1),
            hasUpdate = true, versionStatus = AppVersionStatus("1.0", "2.0", true)) }
        val excluded = listOf(base.copy(isDeleted = true),
            base.copy(isInstallStateUnknown = true), base.copy(isInstallStateNotPatched = true),
            base.copy(isInstalledOnDevice = false))
        val patched = item("Zulu patched").let { it.copy(installedApp = record(it, 2)) }
        for (item in excluded) {
            assertEquals(listOf(patched, item), listOf(item, patched).ordered(HomeAppSortMode.RECOMMENDED))
        }
    }

    @Test
    fun `an install still being checked keeps its group so a cold start opens on updates and patched apps`() {
        val pending = { name: String, at: Long -> item(name).let { it.copy(installedApp = record(it, at),
            isInstallStatePending = true) } }
        val cards = listOf(item("Alpha"), pending("Patched", 1), pending("Update", 2).copy(hasUpdate = true),
            pending("Behind", 3).copy(versionStatus = AppVersionStatus("1.0", "2.0", true)))
        assertEquals(listOf("Behind", "Update", "Patched", "Alpha"),
            cards.ordered(HomeAppSortMode.RECOMMENDED).map { it.displayName })
        // The badge itself still waits for the check
        assertEquals(false, cards[2].showsUpdateBadge)
    }
}
