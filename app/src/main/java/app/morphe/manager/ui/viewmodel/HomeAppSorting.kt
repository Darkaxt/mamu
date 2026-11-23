/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import app.morphe.manager.domain.manager.HomeAppSortMode
import app.morphe.manager.ui.model.HomeAppItem

/** Shared by fresh and cached cards, without priority for any patch source. */
internal fun homeAppPriority(item: HomeAppItem): Int = when {
    item.groupsAsRebuild -> 0
    item.groupsAsPatchedInstall -> 1
    else -> 2
}

internal fun sortHomeAppItems(
    items: List<HomeAppItem>,
    sortMode: HomeAppSortMode,
    customOrder: List<String>
): List<HomeAppItem> {
    // Cards of the same app share a name and a package, so the card id decides between them
    val nameComparator = compareBy<HomeAppItem, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.id }
        .thenBy { it.id }
    val updateComparator = compareBy<HomeAppItem> {
        // A stock app can have a newer supported version too, but its row is "Not patched yet".
        // Only confirmed patched installs qualify for either leading group.
        homeAppPriority(it)
    }
        .then(nameComparator)

    return when (sortMode) {
        HomeAppSortMode.MANUAL -> {
            val defaultSorted = items.sortedWith(updateComparator)
            if (customOrder.isEmpty()) {
                defaultSorted
            } else {
                val indexMap = customOrder.mapIndexed { index, id -> id to index }.toMap()
                defaultSorted.sortedBy { indexMap[it.id] ?: Int.MAX_VALUE }
            }
        }
        HomeAppSortMode.RECOMMENDED, HomeAppSortMode.UPDATES_FIRST -> items.sortedWith(updateComparator)
        HomeAppSortMode.NAME_ASC -> items.sortedWith(nameComparator)
        HomeAppSortMode.NAME_DESC -> items.sortedWith(nameComparator.reversed())
        HomeAppSortMode.RECENTLY_PATCHED -> items.sortedWith(
            // Newest patch first; apps never patched sort last, then use update/name order
            compareByDescending<HomeAppItem> { it.installedApp?.patchedAt ?: Long.MIN_VALUE }
                .then(updateComparator)
        )
    }
}

