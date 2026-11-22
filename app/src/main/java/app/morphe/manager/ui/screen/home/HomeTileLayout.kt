package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.viewmodel.homeAppPriority
import app.morphe.manager.util.withVersionPrefix

internal fun homeTileIconPackage(item: HomeAppItem): String = item.installedApp?.currentPackageName ?: item.packageName
internal fun homeTileSubtitle(item: HomeAppItem): String = listOfNotNull(item.nameSuffix,
    item.version.takeIf { it.isNotEmpty() && (item.installedApp != null || item.isInstalledOnDevice) }
        ?.withVersionPrefix()).joinToString(" · ")

/** Actual available width, including split-screen; font scaling never squeezes actions. */
internal fun homeTileColumns(widthDp: Float, fontScale: Float): Int =
    if (widthDp < 600f) 1 else ((widthDp + 12f) / (280f * maxOf(1f, fontScale) + 12f)).toInt().coerceIn(1, 4)

internal data class HomeTileRow(val startIndex: Int, val items: List<HomeAppItem>, val priority: Int?)

/** Never pack two recommendation groups into one row. Headers live inside their first row. */
internal fun homeTileRows(items: List<HomeAppItem>, columns: Int, sectioned: Boolean): List<HomeTileRow> = buildList {
    var index = 0
    while (index < items.size) {
        val priority = homeAppPriority(items[index])
        val first = index == 0 || homeAppPriority(items[index - 1]) != priority
        var end = index + 1
        while (end < items.size && end - index < columns.coerceAtLeast(1) &&
            (!sectioned || homeAppPriority(items[end]) == priority)) end++
        add(HomeTileRow(index, items.subList(index, end), priority.takeIf { sectioned && first }))
        index = end
    }
}

internal val LocalHomeTileLayout = staticCompositionLocalOf { false }

/** LazyColumn virtualizes rows; each mounted tile retains its own app/source state. */
@Composable
internal fun HomeTileRowLayout(items: List<HomeAppItem>, columns: Int, content: @Composable (Int, HomeAppItem) -> Unit) {
    if (columns <= 1) {
        items.forEachIndexed { index, item -> key(item.id) { content(index, item) } }
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { index, item ->
            Box(Modifier.weight(1f)) {
                key(item.id) { CompositionLocalProvider(LocalHomeTileLayout provides true) { content(index, item) } }
            }
        }
        repeat((columns - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}
