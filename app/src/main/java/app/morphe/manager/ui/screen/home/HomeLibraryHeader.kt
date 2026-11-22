package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import app.morphe.manager.R
import app.morphe.manager.network.PlayStoreListing
import app.morphe.manager.ui.viewmodel.*

internal val LocalStoreListings = staticCompositionLocalOf<Map<String, PlayStoreListing>> { emptyMap() }

/** Native design A: only cheap immutable state is read while composing. */
@Composable
internal fun HomeLibraryHeader(
    projection: HomeLibraryProjection?,
    onSelectionChange: (HomeLibrarySelection) -> Unit,
    onPatchAnyApp: () -> Unit,
    onSort: () -> Unit,
    onFilter: () -> Unit,
    onSettings: () -> Unit,
    onSources: () -> Unit,
    onQueue: () -> Unit,
    onGrouping: () -> Unit,
    modifier: Modifier = Modifier,
    onboardingState: OnboardingState? = null,
    showSearch: Boolean = true,
    showSort: Boolean = true,
    subtitle: String? = null,
    inputSelection: HomeLibrarySelection = projection?.selection ?: HomeLibrarySelection(),
    onBatch: (() -> Unit)? = null
) {
    // Input must echo immediately, independently of the Default-dispatcher catalogue result.
    val selection = inputSelection
    val displayedSelection = projection?.selection ?: selection
    var menu by remember { mutableStateOf(false) }
    // Home draws its own background without an ancestor Material Surface.
    Surface(modifier = modifier, color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(44.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle ?: stringResource(R.string.library_supported), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onPatchAnyApp, modifier = Modifier.size(48.dp).testTag("library.add").onGloballyPositioned { onboardingState?.patchAnyAppBounds = it.boundsInWindow() }) {
                Icon(Icons.Outlined.Add, stringResource(R.string.library_patch_any))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showSearch || selection.query.isNotEmpty()) OutlinedTextField(value = selection.query,
                onValueChange = { onSelectionChange(selection.copy(query = it)) },
                placeholder = { Text(stringResource(R.string.search), maxLines = 1) }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.weight(1f).testTag("library.search"), shape = MaterialTheme.shapes.extraLarge)
            else Spacer(Modifier.weight(1f))
            if (showSort) IconButton(onClick = onSort, modifier = Modifier.size(48.dp).testTag("library.sort")) {
                Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.sort))
            }
            IconButton(onClick = onFilter, modifier = Modifier.size(48.dp).testTag("library.filter")) {
                Icon(Icons.Outlined.FilterList, stringResource(R.string.filter))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("library.more")
                    .onGloballyPositioned { coordinates ->
                        // Both destinations now live behind this anchor rather than a bottom dock.
                        onboardingState?.sourcesButtonBounds = coordinates.boundsInWindow()
                        onboardingState?.settingsButtonBounds = coordinates.boundsInWindow()
                    }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.library_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings)) }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.sources)) }, onClick = { menu = false; onSources() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_queue)) }, onClick = { menu = false; onQueue() })
                    if (onBatch != null) DropdownMenuItem(text = { Text(stringResource(R.string.batch_patch_title)) },
                        onClick = { menu = false; onBatch() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_grouping)) }, onClick = { menu = false; onGrouping() })
                }
            }
        }
        val types = StoreAppType.entries.filter { it == StoreAppType.ALL || (projection?.typeCounts?.get(it) ?: 0) > 0 }
        if (LocalDensity.current.fontScale > 1.3f) {
            LazyRow(Modifier.fillMaxWidth()) {
                items(types, key = { it.name }) { type ->
                    StoreTypeTab(type, projection?.typeCounts?.get(type) ?: 0, displayedSelection.type == type,
                        { onSelectionChange(selection.copy(type = type, category = null)) }, Modifier.widthIn(min = 112.dp))
                }
            }
        } else Row(Modifier.fillMaxWidth()) {
            types.forEach { type ->
                StoreTypeTab(type, projection?.typeCounts?.get(type) ?: 0, displayedSelection.type == type,
                    { onSelectionChange(selection.copy(type = type, category = null)) }, Modifier.weight(1f))
            }
        }
        val categories = projection?.categoryCounts.orEmpty().entries
            .map { (category, count) -> Triple(category, count, stringResource(category?.label ?: R.string.library_all_categories)) }
            .sortedWith(compareBy<Triple<StoreCategory?, Int, String>> { it.first != null }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.third })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().testTag("library.categories")) {
            items(categories, key = { it.first?.storeId ?: "all" }) { (category, count, label) ->
                FilterChip(selected = displayedSelection.category == category,
                    onClick = { onSelectionChange(selection.copy(category = category)) },
                    label = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, maxLines = 1)
                        Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text(count.toString()) }
                    } }, modifier = Modifier.testTag("library.category.${category?.storeId ?: "all"}"))
            }
        }
    }
    }
}

@Composable
private fun StoreTypeTab(type: StoreAppType, count: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        Tab(selected = selected, onClick = onClick, modifier = Modifier.testTag("library.type.${type.name}"),
            selectedContentColor = MaterialTheme.colorScheme.onBackground,
            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(type.label), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text(count.toString()) }
            } })
        HorizontalDivider(thickness = 2.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    }
}
