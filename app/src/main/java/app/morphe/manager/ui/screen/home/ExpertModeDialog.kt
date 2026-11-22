/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import android.graphics.drawable.Drawable
import app.morphe.manager.downloaders.ApkPublications
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.domain.bundles.RemotePatchBundle
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.patcher.patch.*
import app.morphe.manager.ui.model.renamesByDefault
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.util.Options
import app.morphe.manager.util.PatchSelection
import app.morphe.manager.util.PatchSelectionUtils.hasCustomizedOptions
import app.morphe.manager.util.PatchSelectionUtils.hasMissingRequiredOptions
import app.morphe.manager.util.PatchSelectionUtils.hasUniversalToEnable
import app.morphe.manager.util.toast
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

data class ExpertPatchSelection(
    val allPatchesInfo: List<Pair<PatchBundleInfo.Scoped, List<Pair<PatchInfo, Boolean>>>>,
    val options: Options,
    val totalSelectedCount: Int,
    val newPatches: Map<Int, Set<String>> = emptyMap(),
    val savedPatches: PatchSelection = emptyMap(),
    val lockStateOf: (PatchInfo) -> PatchLockState = { PatchLockState.NONE },
    /** True while "Enable all" still holds the universal patches of the given list back. */
    val holdsUniversalPatches: (bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>) -> Boolean = { _, _ -> false },
    val failureContext: PatchFailureContext? = null
)

/** Callbacks the expert-mode dialog invokes on the underlying patch selection. */
@Stable
class ExpertPatchActions(
    val onPatchToggle: (bundleUid: Int, patchName: String) -> Unit,
    val onSelectAll: (bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>) -> Unit,
    val onDeselectAll: (bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>) -> Unit,
    val onResetToDefault: (bundleUid: Int, patchScope: Set<String>?) -> Unit,
    val onRestoreSaved: (bundleUid: Int, patchScope: Set<String>?) -> Unit,
    val onCopyFromBundle: ((bundleUid: Int, patchScope: Set<String>?) -> Unit)? = null,
    val onOptionChange: (bundleUid: Int, patchName: String, optionKey: String, value: Any?) -> Unit,
    val onResetOptions: (bundleUid: Int, patchName: String) -> Unit,
    val onApplySet: ((app.morphe.manager.domain.repository.SelectionSnapshot) -> Unit)? = null
)

private enum class SectionSelectionAction(val title: Int) {
    ALL(R.string.expert_mode_enable_all), DEFAULTS(R.string.expert_mode_reset_to_default),
    RESTORE(R.string.expert_mode_restore_saved), COPY(R.string.expert_mode_copy_from_bundle),
    NONE(R.string.expert_mode_disable_all)
}

@Composable
fun ExpertModeDialog(
    packageName: String,
    configurationKey: String = packageName,
    appName: String? = null,
    appIcon: Drawable? = null,
    apkVersion: String? = null,
    apkVersionCode: Long? = null,
    selection: ExpertPatchSelection,
    hasMultipleBundles: Boolean,
    patchActions: ExpertPatchActions,
    proceedText: String = stringResource(R.string.patch),
    warnOnMultipleBundles: Boolean = true,
    usesPrerelease: (Int) -> Boolean = { false },
    hiddenSources: Set<Int> = emptySet(),
    globalHiddenSources: Set<Int> = emptySet(),
    onSourceVisibilityChange: (Int, Boolean, Boolean) -> Unit,
    onAllowUnsupported: () -> Unit,
    onDismiss: () -> Unit,
    onProceed: () -> Unit
) {
    val (allPatchesInfo, options, _, newPatches, savedPatches, lockStateOf, holdsUniversalPatches) = selection
    val failures: KnownPatchFailures = koinInject()
    val failureRecords by failures.records.collectAsState()
    val known = knownFailures(selection.failureContext, failureRecords)
    val sourcesByUid = rememberSourcesByUid()
    val prefs: PreferencesManager = koinInject()
    val acknowledgements by prefs.patchPanelUnsupportedAcknowledgements.getAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val hidden = hiddenSources + globalHiddenSources
    val selectedPatchForOptions = remember { mutableStateOf<Pair<Int, PatchInfo>?>(null) }
    val search = rememberSearchFieldState()
    var showMultipleSourcesWarning by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var sourceInfoUid by remember { mutableStateOf<Int?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var showSets by remember { mutableStateOf(false) }
    var scopes by remember { mutableStateOf(PatchVersionScope.entries.filter { it != PatchVersionScope.UNSUPPORTED }.toSet()) }
    val sectionAllFeedback = remember(packageName, apkVersion, apkVersionCode) { mutableMapOf<String, PillConfirmationState>() }
    var pendingSectionAction by remember { mutableStateOf<Pair<String, SectionSelectionAction>?>(null) }
    var sectionSources by remember { mutableStateOf(emptySet<Int>()) }
    var pendingUniversalAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingWarning by remember { mutableStateOf<Triple<Set<String>, List<PanelPatch>, () -> Unit>?>(null) }
    // Include acknowledgements immediately; DataStore persists them before their action runs.
    var sessionAcknowledgements by remember { mutableStateOf(emptySet<String>()) }
    var selectedOnly by remember { mutableStateOf<Set<String>?>(null) }
    val sectionState = remember(packageName, apkVersion, apkVersionCode) { PatchSectionState() }
    // Read the snapshot in composition so toggles rebuild the lazy-list contents.
    val folds = sectionState.folds
    val rows = allPatchesInfo.flatMap { (bundle, patches) ->
        patches.map { (patch, enabled) -> PanelPatch(bundle.uid, patch, enabled,
            if ("${bundle.uid}:${patch.name}" in known) PatchVersionScope.UNSUPPORTED
            else patch.versionScope(packageName, apkVersion, apkVersionCode), sourcesByUid[bundle.uid]?.createdAt ?: 0L) }
    }
    val visibleRows = rows.filterNot { it.bundleUid in hidden }
    val apkPublication = remember(packageName, apkVersion, apkVersionCode) {
        ApkPublications(context).get(packageName, apkVersion, apkVersionCode)
    }
    val ageWarnings = patchReleaseAgeWarnings(packageName, apkVersion, apkPublication?.day,
        visibleRows, patchReleaseAgeSources(sourcesByUid.values.filter { source ->
            source.version == allPatchesInfo.firstOrNull { it.first.uid == source.uid }?.first?.version
        }), apkVersionCode)
    val ageKeys = ageWarnings.mapTo(mutableSetOf()) { it.patchKey }
    val unsupportedSelected = selectedUnsupportedPatches(visibleRows)
    val totalSelectedCount = visibleRows.count { it.selected }
    val totalPatchesCount = visibleRows.size
    val allPatches = remember(allPatchesInfo) { rows.map { it.patch } }
    val matchesPatch = rememberPatchMatcher(search.query, allPatches)
    val appColor = remember(allPatches, packageName) { allPatches.asSequence().appColorFor(packageName) }
    val headerTitle = appName ?: allPatchesInfo.firstNotNullOfOrNull { it.first.displayName } ?: packageName
    val patchesWithMissingRequired = allPatchesInfo.patchNamesWhere { bundle, patch, enabled ->
        enabled && patch.hasMissingRequiredOptions(options[bundle.uid]?.get(patch.name))
    }
    val patchesWithCustomOptions = allPatchesInfo.patchNamesWhere { bundle, patch, _ ->
        patch.hasCustomizedOptions(options[bundle.uid]?.get(patch.name))
    }
    val displayed = orderPanelPatches(visibleRows.filter { row ->
        row.matchesVersionScopes(scopes) && (search.query.isBlank() || matchesPatch(row.patch)) &&
            (selectedOnly?.contains(row.key) != false)
    })
    fun warningKey(row: PanelPatch): String {
        val source = sourcesByUid[row.bundleUid]
        val bundle = allPatchesInfo.first { it.first.uid == row.bundleUid }.first
        val base = unsupportedAcknowledgementKey(packageName, apkVersion, apkVersionCode,
            row.bundleUid, bundle.version, source?.createdAt ?: 0L)
        // A prior compatibility acknowledgement must not approve a newly observed failure.
        return if (row.key in known) "$base|known-failure|${knownFailureRetryKey(selection.failureContext, row.key)}" else base
    }
    fun guardUnsupported(candidates: List<PanelPatch>, action: () -> Unit) {
        val unsupported = candidates.filter { it.scope == PatchVersionScope.UNSUPPORTED }
        val missing = unsupported.mapTo(mutableSetOf(), ::warningKey) - acknowledgements - sessionAcknowledgements
        val acceptedAction = {
            if (unsupported.isNotEmpty()) onAllowUnsupported()
            action()
        }
        if (missing.isEmpty()) acceptedAction() else pendingWarning = Triple(missing, unsupported, acceptedAction)
    }
    fun changeVisibility(uid: Int, globally: Boolean, hide: Boolean) {
        val removed = visibleRows.count { it.bundleUid == uid && it.selected }
        onSourceVisibilityChange(uid, globally, hide)
        if (hide && removed > 0) context.toast(context.getString(R.string.panel_hidden_removed, removed))
    }
    val customTitle = stringResource(R.string.panel_custom)
    val universalTitle = stringResource(R.string.panel_universal)
    val groups = buildPanelPatchGroups(displayed, customTitle, universalTitle)
    val showMatches = search.query.isNotBlank() || selectedOnly != null
    val sourceCount = allPatchesInfo.count { (bundle, patches) -> patches.isNotEmpty() && bundle.uid in hidden }

    fun applySectionAction(group: PatchGroup<PanelPatch>, action: SectionSelectionAction, chosen: Set<Int>) {
        val liveRows = group.items.filterNot { it.bundleUid in hidden }
        val targets = sectionActionTargets(liveRows, chosen)
        if (targets.isEmpty()) return
        val affected = liveRows.filter { it.bundleUid in targets }
        val enabling = when (action) {
            SectionSelectionAction.ALL -> affected.filterNot { it.selected }
            SectionSelectionAction.DEFAULTS -> affected.filter { it.patch.include }
            SectionSelectionAction.RESTORE -> affected.filter { it.patch.name in savedPatches[it.bundleUid].orEmpty() }
            SectionSelectionAction.COPY -> affected
            SectionSelectionAction.NONE -> emptyList()
        }
        guardUnsupported(enabling) {
            val holdsUniversal = targets.any { (uid, patches) -> holdsUniversalPatches(uid, patches) }
            val apply = {
                targets.forEach { (uid, patches) ->
                    val names = patches.mapTo(mutableSetOf()) { it.first.name }
                    when (action) {
                        SectionSelectionAction.ALL -> patchActions.onSelectAll(uid, patches)
                        SectionSelectionAction.DEFAULTS -> patchActions.onResetToDefault(uid, names)
                        SectionSelectionAction.RESTORE -> patchActions.onRestoreSaved(uid, names)
                        SectionSelectionAction.COPY -> patchActions.onCopyFromBundle?.invoke(uid, names)
                        SectionSelectionAction.NONE -> patchActions.onDeselectAll(uid, patches)
                    }
                }
                if (action == SectionSelectionAction.ALL) sectionAllFeedback[group.key]?.show(context.getString(
                    if (holdsUniversal) R.string.expert_mode_enable_all_universal_pending else R.string.expert_mode_enable_all_done))
            }
            if (action == SectionSelectionAction.ALL && targets.any { (uid, patches) ->
                    !holdsUniversalPatches(uid, patches) && patches.hasUniversalToEnable(lockStateOf)
                }) pendingUniversalAction = apply
            else apply()
        }
    }
    fun chooseSectionSources(group: PatchGroup<PanelPatch>, action: SectionSelectionAction) {
        val available = group.items.mapTo(linkedSetOf()) { it.bundleUid }
        if (available.size == 1) applySectionAction(group, action, available)
        else {
            sectionSources = if (action == SectionSelectionAction.COPY) available.take(1).toSet() else available
            pendingSectionAction = group.key to action
        }
    }

    AppDialog(
        onDismissRequest = onDismiss, title = null, dismissOnClickOutside = false,
        accentColor = appColor, padding = DialogPadding.Compact, scrollable = false,
        hideFooterWhileTyping = true,
        footer = { AppDialogActions(actions = listOfNotNull(
            expertPatchProceedAction(proceedText, visibleRows) { selected ->
                guardUnsupported(selected) {
                    if (hasMultipleBundles && warnOnMultipleBundles) showMultipleSourcesWarning = true else onProceed()
                }
            }, translateAction())) }
    ) {
        SearchFieldBackHandler(search)
        BackHandler(enabled = selectedOnly != null) { selectedOnly = null }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Defaults.ContentPaddingSmall)) {
            val riskySources = sourceFailureCounts(failureRecords, visibleRows.filter { it.selected }
                .groupBy { it.bundleUid }.mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.patch.name } })
                .map { (uid, count) -> "${allPatchesInfo.first { it.first.uid == uid }.first.name}: $count" }
            if (riskySources.isNotEmpty()) Notice(
                text = stringResource(R.string.patch_source_failure_caution, riskySources.joinToString("; ")),
                tone = SemanticTone.Warning, density = NoticeDensity.Compact)

            ListDialogHeader(
                icon = { modifier -> AppIcon(packageName = packageName, icon = appIcon, contentDescription = null, modifier = modifier) },
                title = headerTitle,
                subtitle = listOfNotNull(apkVersion?.let { "v$it" }, apkVersionCode?.toString()).joinToString(" \u00b7 ")
                    .ifBlank { stringResource(R.string.panel_unknown_version) },
                search = search, searchLabel = stringResource(R.string.expert_mode_search)
            ) {
                StatusBadge(text = "$totalSelectedCount/$totalPatchesCount", icon = Icons.Outlined.FilterAlt,
                    tone = if (selectedOnly != null) SemanticTone.Primary else SemanticTone.Neutral,
                    onClick = { selectedOnly = if (selectedOnly != null) null else visibleRows.filter { it.selected }.mapTo(mutableSetOf()) { it.key } },
                    modifier = Modifier.semantics { contentDescription = context.getString(if (selectedOnly == null)
                        R.string.expert_mode_selected_only_off else R.string.expert_mode_selected_only_on) })
            }
            AppDialogSearchHeader(visible = search.visible, value = search.query,
                onValueChange = { search.query = it }, label = stringResource(R.string.expert_mode_search))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showFilters = true }) {
                    Icon(Icons.Outlined.FilterAlt, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.panel_filter_count, scopes.size))
                }
                if (patchActions.onApplySet != null) TextButton(onClick = { showSets = true }) {
                    Text(stringResource(R.string.extended_named_sets))
                }
                TextButton(onClick = { showSources = true }) {
                    Icon(Icons.Outlined.Layers, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.panel_sources_hidden, sourceCount))
                }
            }
            if (unsupportedSelected.isNotEmpty()) Notice(
                text = stringResource(R.string.panel_unsupported_selected, unsupportedSelected.size),
                icon = Icons.Outlined.WarningAmber, tone = SemanticTone.Warning, density = NoticeDensity.Compact,
                modifier = Modifier.clickable {
                    search.query = ""
                    selectedOnly = unsupportedSelected.mapTo(mutableSetOf()) { it.key }
                })
            if (ageWarnings.isNotEmpty()) Notice(
                text = stringResource(R.string.patch_age_selected, ageWarnings.size),
                icon = Icons.Outlined.WarningAmber, tone = SemanticTone.Warning, density = NoticeDensity.Compact,
                modifier = Modifier.clickable {
                    search.query = ""
                    selectedOnly = ageKeys
                })
            val state = rememberLazyListState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize().verticalScrollFade(state),
                    verticalArrangement = Arrangement.spacedBy(Defaults.ContentPaddingSmall)) {
                    if (displayed.isEmpty()) item { PatchesListEmptyState() }
                    groups.forEach { group ->
                        val expanded = showMatches || folds.isExpanded(packageName, group)
                        item(key = "heading:${group.key}") {
                            PatchGroupHeader(
                                title = checkNotNull(group.title), count = group.items.size,
                                isExpanded = expanded,
                                onToggle = if (showMatches) null else ({ sectionState.toggle(packageName, group) }),
                                icon = group.icon,
                                accentColor = appColor.takeIf { group.defaultExpanded },
                                selectedCount = group.selectedCount,
                                selectionTone = selectionCompatibilityTone(group.items)
                            )
                        }
                        if (expanded) {
                            item(key = "controls:${group.key}") {
                                val targets = sectionActionTargets(group.items, group.items.mapTo(mutableSetOf()) { it.bundleUid })
                                BundlePatchControls(
                                    enabledCount = group.items.count { it.selected }, totalCount = group.items.size,
                                    holdsUniversalPatches = targets.any { (uid, patches) -> holdsUniversalPatches(uid, patches) },
                                    onSelectAll = { chooseSectionSources(group, SectionSelectionAction.ALL) },
                                    onDeselectAll = { chooseSectionSources(group, SectionSelectionAction.NONE) },
                                    onResetToDefault = { chooseSectionSources(group, SectionSelectionAction.DEFAULTS) },
                                    onRestoreSaved = { chooseSectionSources(group, SectionSelectionAction.RESTORE) },
                                    onCopyFromBundle = patchActions.onCopyFromBundle?.let { { chooseSectionSources(group, SectionSelectionAction.COPY) } },
                                    hasSavedSelection = group.items.any { it.patch.name in savedPatches[it.bundleUid].orEmpty() },
                                    confirmActions = false,
                                    selectAllFeedback = sectionAllFeedback.getOrPut(group.key) { PillConfirmationState() })
                            }
                            items(group.items, key = { it.key }) { row ->
                                val patch = row.patch
                                val source = sourcesByUid[row.bundleUid]
                                val scopeLabel = when (row.scope) {
                                    PatchVersionScope.VERIFIED -> stringResource(R.string.panel_verified)
                                    PatchVersionScope.COMPATIBLE -> stringResource(R.string.panel_compatible)
                                    PatchVersionScope.UNRESTRICTED -> patch.category?.takeIf { it != UniversalPatchCategories.category(patch) }
                                    PatchVersionScope.UNSUPPORTED -> known[row.key]?.let { stringResource(R.string.patch_known_failure_row, it) }
                                        ?: unsupportedVersionLabel(patch, packageName)
                                }
                                PatchCard(patch = patch, isEnabled = row.selected,
                                    isNew = patch.name in newPatches[row.bundleUid].orEmpty(),
                                    buildsClone = patch.renamesByDefault,
                                    hasRequiredOptionsMissing = patch.name in patchesWithMissingRequired[row.bundleUid].orEmpty(),
                                    hasCustomOptions = patch.name in patchesWithCustomOptions[row.bundleUid].orEmpty(),
                                    lockState = lockStateOf(patch), versionLabel = listOfNotNull(scopeLabel,
                                        stringResource(R.string.patch_age_row).takeIf { row.key in ageKeys },
                                        stringResource(R.string.changelog_badge_prerelease).takeIf { usesPrerelease(row.bundleUid) })
                                        .joinToString(" / ").ifBlank { null },
                                    versionTone = when {
                                        row.scope == PatchVersionScope.UNSUPPORTED -> SemanticTone.Error
                                        row.key in ageKeys -> SemanticTone.Warning
                                        else -> SemanticTone.Neutral
                                    },
                                    onToggle = {
                                        if (row.selected) patchActions.onPatchToggle(row.bundleUid, patch.name)
                                        else guardUnsupported(listOf(row)) { patchActions.onPatchToggle(row.bundleUid, patch.name) }
                                    },
                                    onConfigureOptions = { selectedPatchForOptions.value = row.bundleUid to patch },
                                    hasOptions = !patch.options.isNullOrEmpty(), sourceAction = {
                                        IconButton(onClick = { sourceInfoUid = row.bundleUid }, modifier = Modifier.size(48.dp)
                                            .semantics { contentDescription = context.getString(R.string.panel_source_info,
                                                allPatchesInfo.first { it.first.uid == row.bundleUid }.first.name) }) {
                                            if (source != null) BundleIcon(source, Modifier.size(24.dp))
                                            else Icon(Icons.Outlined.Extension, null)
                                        }
                                    })
                            }
                        }
                    }
                }
                ListScrollbar(listState = state, modifier = Modifier.offset(x = LocalDialogHorizontalInset.current))
            }
        }
    }
    if (showSets) patchActions.onApplySet?.let { apply ->
        key(configurationKey, apkVersion, apkVersionCode, selection.failureContext) {
        NamedPatchSetsDialog(configurationKey = configurationKey,
            bundles = allPatchesInfo.map { it.first }.filterNot { it.uid in hidden },
            selection = visibleRows.filter { it.selected }.groupBy { it.bundleUid }
                .mapValues { (_, rows) -> rows.mapTo(mutableSetOf()) { it.patch.name } },
            options = options.filterKeys { it !in hidden }, onApply = apply,
            onDismiss = { showSets = false })
        }
    }
    pendingSectionAction?.let { (key, action) ->
        val group = groups.firstOrNull { it.key == key }
        val available = group?.items.orEmpty().map { it.bundleUid }.distinct()
        AlertDialog(onDismissRequest = { pendingSectionAction = null },
            title = { Text(stringResource(action.title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(group?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                if (action == SectionSelectionAction.COPY) Text(stringResource(R.string.panel_copy_target_help))
                available.forEach { uid ->
                    val label = allPatchesInfo.first { it.first.uid == uid }.first.name
                    val choose = {
                        sectionSources = if (action == SectionSelectionAction.COPY) setOf(uid)
                            else if (uid in sectionSources) sectionSources - uid else sectionSources + uid
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (action == SectionSelectionAction.COPY) RadioButton(selected = uid in sectionSources,
                            onClick = choose, modifier = Modifier.semantics { contentDescription = label })
                        else Checkbox(checked = uid in sectionSources, onCheckedChange = { choose() },
                            modifier = Modifier.semantics { contentDescription = label })
                        sourcesByUid[uid]?.let { BundleIcon(it, Modifier.size(24.dp)) }
                        Text(label, Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
            } },
            dismissButton = { TextButton(onClick = { pendingSectionAction = null }) { Text(stringResource(android.R.string.cancel)) } },
            confirmButton = { TextButton(enabled = group != null && available.any { it in sectionSources }, onClick = {
                pendingSectionAction = null
                group?.let { applySectionAction(it, action, sectionSources.intersect(available.toSet())) }
            }) { Text(stringResource(android.R.string.ok)) } })
    }
    pendingUniversalAction?.let { action ->
        ConfirmDialog(title = stringResource(R.string.expert_mode_universal_all_warning_title),
            message = stringResource(R.string.expert_mode_universal_all_warning_message),
            primaryText = stringResource(R.string.enable), isPrimaryDestructive = false,
            onDismiss = { pendingUniversalAction = null }, onConfirm = { pendingUniversalAction = null; action() })
    }
    if (showFilters) AlertDialog(onDismissRequest = { showFilters = false },
        title = { Text(stringResource(R.string.panel_compatibility)) },
        confirmButton = { TextButton(onClick = { showFilters = false }) { Text(stringResource(android.R.string.ok)) } },
        text = { Column {
        PatchVersionScope.entries.forEach { scope ->
            val label = stringResource(when (scope) {
                PatchVersionScope.VERIFIED -> R.string.panel_verified
                PatchVersionScope.COMPATIBLE -> R.string.panel_compatible
                PatchVersionScope.UNRESTRICTED -> R.string.panel_unrestricted
                PatchVersionScope.UNSUPPORTED -> R.string.panel_show_unsupported
            })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = scope in scopes, onCheckedChange = { checked -> scopes = if (checked) scopes + scope else scopes - scope },
                    modifier = Modifier.semantics { contentDescription = label })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text(stringResource(R.string.panel_scope_help), style = MaterialTheme.typography.bodySmall)
        } })
    if (showSources) AppDialog(onDismissRequest = { showSources = false }, title = stringResource(R.string.panel_sources)) {
        allPatchesInfo.filter { it.second.isNotEmpty() }.forEach { (bundle, _) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { sourceInfoUid = bundle.uid }, modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = context.getString(R.string.panel_source_info, bundle.name) }) {
                        sourcesByUid[bundle.uid]?.let { BundleIcon(it, Modifier.size(24.dp)) }
                            ?: Icon(Icons.Outlined.Info, stringResource(R.string.panel_source_info, bundle.name))
                    }
                    Text(bundle.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                }
                SourceVisibilityControls(bundle.uid, hiddenSources, globalHiddenSources, ::changeVisibility)
            }
        }
    }
    sourceInfoUid?.let { uid ->
        val bundle = allPatchesInfo.firstOrNull { it.first.uid == uid }?.first
        val source = sourcesByUid[uid]
        if (bundle != null) AlertDialog(onDismissRequest = { sourceInfoUid = null },
            title = { Text(bundle.name) },
            confirmButton = { TextButton(onClick = { sourceInfoUid = null }) { Text(stringResource(android.R.string.ok)) } },
            text = { Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(bundle.version, source?.createdAt?.let { DateFormat.getDateInstance().format(Date(it)) })
                .joinToString(" \u00b7 "), style = MaterialTheme.typography.bodyMedium)
            if (usesPrerelease(uid)) Notice(text = stringResource(R.string.expert_mode_prerelease_notice),
                icon = Icons.Outlined.WarningAmber, tone = SemanticTone.Warning, density = NoticeDensity.Compact)
            val url = (source as? RemotePatchBundle)?.browsePageUrl
            if (url != null) TextButton(onClick = { uriHandler.openUri(url) }) {
                Icon(Icons.Outlined.Info, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.panel_repository))
            }
            SourceVisibilityControls(uid, hiddenSources, globalHiddenSources, ::changeVisibility)
            if (uid !in hidden) {
                val patches = displayed.filter { it.bundleUid == uid }.map { it.patch to it.selected }
                val guardedActions = ExpertPatchActions(
                    onPatchToggle = patchActions.onPatchToggle,
                    onSelectAll = { id, list -> guardUnsupported(displayed.filter { it.bundleUid == id && !it.selected }) {
                        patchActions.onSelectAll(id, list) } },
                    onDeselectAll = patchActions.onDeselectAll,
                    onResetToDefault = { id, scope -> guardUnsupported(rows.filter { it.bundleUid == id && it.patch.include && (scope == null || it.patch.name in scope) }) {
                        patchActions.onResetToDefault(id, scope) } },
                    onRestoreSaved = { id, scope -> guardUnsupported(rows.filter { it.bundleUid == id && it.patch.name in savedPatches[id].orEmpty() && (scope == null || it.patch.name in scope) }) {
                        patchActions.onRestoreSaved(id, scope) } },
                    onCopyFromBundle = patchActions.onCopyFromBundle?.let { copy ->
                        { id, scope -> guardUnsupported(rows.filter { it.bundleUid == id && (scope == null || it.patch.name in scope) }) { copy(id, scope) } }
                    },
                    onOptionChange = patchActions.onOptionChange,
                    onResetOptions = patchActions.onResetOptions)
                BundleControls(bundle, patches, guardedActions, savedPatches, lockStateOf, holdsUniversalPatches, {})
            }
            } })
    }
    pendingWarning?.let { (keys, offending, action) ->
        ConfirmDialog(title = stringResource(if (offending.any { it.key in known }) R.string.patch_known_failure_title else R.string.panel_other_versions),
            message = stringResource(if (offending.any { it.key in known }) R.string.patch_known_failure_warning else R.string.panel_unsupported_warning),
            subject = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    offending.forEach { row ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(row.patch.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(allPatchesInfo.first { it.first.uid == row.bundleUid }.first.name,
                                style = MaterialTheme.typography.bodySmall)
                            Text(known[row.key]?.let { stringResource(R.string.patch_known_failure_row, it) } ?: unsupportedVersionLabel(row.patch, packageName),
                                color = SemanticTone.Error.accent, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            primaryText = stringResource(R.string.home_dialog_unsupported_version_dialog_proceed),
            isPrimaryDestructive = false, onDismiss = { pendingWarning = null }, onConfirm = {
                pendingWarning = null
                coroutineScope.launch {
                    prefs.edit { prefs.patchPanelUnsupportedAcknowledgements.value = prefs.patchPanelUnsupportedAcknowledgements.value + keys.filterNot { "|known-failure|" in it } }
                    sessionAcknowledgements += keys
                    action()
                }
            })
    }
    if (showMultipleSourcesWarning) ConfirmDialog(
        title = stringResource(R.string.expert_mode_multiple_sources_warning_title),
        message = stringResource(R.string.expert_mode_multiple_sources_warning_message),
        primaryText = stringResource(R.string.home_dialog_unsupported_version_dialog_proceed), isPrimaryDestructive = false,
        onConfirm = { showMultipleSourcesWarning = false; onProceed() }, onDismiss = { showMultipleSourcesWarning = false })
    // Options dialog
    val patchForOptions = selectedPatchForOptions.value
    if (patchForOptions != null) {
        val (bundleUid, patch) = patchForOptions
        val missingOptionsMessage = stringResource(R.string.patch_option_required_missing, patch.displayName)
        PatchOptionsDialog(
            patch = patch,
            packageName = packageName,
            appName = headerTitle,
            appIcon = appIcon,
            accentColor = appColor,
            isDefaultBundle = bundleUid == 0,
            values = options[bundleUid]?.get(patch.name),
            onValueChange = { key, value ->
                patchActions.onOptionChange(bundleUid, patch.name, key, value)
            },
            onReset = {
                patchActions.onResetOptions(bundleUid, patch.name)
            },
            onDismiss = {
                // Show a toast if the patch still has unfilled required options
                if (patch.name in patchesWithMissingRequired[bundleUid].orEmpty()) {
                    context.toast(missingOptionsMessage)
                }
                selectedPatchForOptions.value = null
            }
        )
    }
}

internal fun selectedUnsupportedPatches(rows: List<PanelPatch>): List<PanelPatch> =
    rows.filter { it.selected && it.scope == PatchVersionScope.UNSUPPORTED }

/** A captured local function reference compares equal even when its rows change. */
internal fun expertPatchProceedAction(
    text: String, rows: List<PanelPatch>, onProceed: (List<PanelPatch>) -> Unit
): DialogAction = DialogAction(
    text = text, onClick = { onProceed(rows.filter { it.selected }) },
    icon = Icons.Outlined.AutoFixHigh, enabled = rows.any { it.selected }
)

internal fun selectionCompatibilityTone(rows: List<PanelPatch>): SemanticTone? = when {
    rows.none { it.selected } -> null
    selectedUnsupportedPatches(rows).isNotEmpty() -> SemanticTone.Warning
    else -> SemanticTone.Success
}

@Composable
private fun unsupportedVersionLabel(patch: PatchInfo, packageName: String): String {
    val versions = patch.compatiblePackages.orEmpty().filter { it.packageName == packageName }.flatMap { target ->
        target.versions.orEmpty().map { version ->
            val builds = target.versionCodes?.get(version).orEmpty()
            if (builds.isEmpty()) version else stringResource(R.string.panel_version_builds,
                version, builds.sorted().joinToString(", "))
        }
    }.distinct()
    return stringResource(R.string.panel_unsupported_versions, versions.joinToString("; ")
        .ifBlank { stringResource(R.string.panel_no_matching_target) })
}

/** Stable fold identities are independent of headings, selected patches and filtered rows. */
internal fun buildPanelPatchGroups(
    displayed: List<PanelPatch>, customTitle: String, universalTitle: String
): List<PatchGroup<PanelPatch>> = buildList {
    val custom = displayed.filter { it.scope != PatchVersionScope.UNRESTRICTED }
    if (custom.isNotEmpty()) add(PatchGroup("custom", customTitle, custom,
        icon = Icons.Outlined.AutoFixHigh, selectedCount = custom.count { it.selected }))
    displayed.filter { it.scope == PatchVersionScope.UNRESTRICTED }
        .groupBy { UniversalPatchCategories.category(it.patch) }
        .toSortedMap(compareBy<String> { it == "Other" }.thenBy { it.lowercase(java.util.Locale.ROOT) })
        .forEach { (category, patches) ->
            add(PatchGroup("universal:$category", "$universalTitle · $category", patches,
                icon = Icons.Outlined.Public, selectedCount = patches.count { it.selected }, defaultExpanded = false))
        }
}


@Composable
private fun SourceVisibilityControls(uid: Int, hidden: Set<Int>, global: Set<Int>, change: (Int, Boolean, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(false, true).forEach { globally ->
            val isHidden = uid in if (globally) global else hidden
            val label = stringResource(if (globally) R.string.panel_all_apps else R.string.panel_this_app)
            TextButton(onClick = { change(uid, globally, !isHidden) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(if (isHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(if (isHidden) R.string.panel_show_scope else R.string.panel_hide_scope, label), Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp)); Text(label)
            }
        }
    }
}

private inline fun List<Pair<PatchBundleInfo.Scoped, List<Pair<PatchInfo, Boolean>>>>.patchNamesWhere(
    predicate: (bundle: PatchBundleInfo.Scoped, patch: PatchInfo, isEnabled: Boolean) -> Boolean
): Map<Int, Set<String>> = mapNotNull { (bundle, patches) ->
    val names = patches.mapNotNullTo(mutableSetOf()) { (patch, isEnabled) ->
        patch.name.takeIf { predicate(bundle, patch, isEnabled) }
    }
    if (names.isEmpty()) null else bundle.uid to names
}.toMap()

@Composable
private fun BundleControls(
    bundle: PatchBundleInfo.Scoped,
    patches: List<Pair<PatchInfo, Boolean>>,
    patchActions: ExpertPatchActions,
    savedPatches: PatchSelection,
    lockStateOf: (PatchInfo) -> PatchLockState,
    holdsUniversalPatches: (bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>) -> Boolean,
    onExpandUniversal: (bundleUid: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val holdsUniversal = holdsUniversalPatches(bundle.uid, patches)
    val copySelection: (() -> Unit)? = patchActions.onCopyFromBundle?.let { copy ->
        { copy(bundle.uid, null) }
    }

    BundlePatchControls(
        enabledCount = patches.count { (_, isEnabled) -> isEnabled },
        totalCount = patches.size,
        holdsUniversalPatches = holdsUniversal,
        // The second "Enable all" tap applies every universal patch at once; warn first
        warnOnUniversalAll = !holdsUniversal && patches.hasUniversalToEnable(lockStateOf),
        onSelectAll = {
            // This tap enables the universal patches, so it has to show what it turned on
            if (!holdsUniversal) onExpandUniversal(bundle.uid)
            patchActions.onSelectAll(bundle.uid, patches)
        },
        onDeselectAll = { patchActions.onDeselectAll(bundle.uid, patches) },
        onResetToDefault = { patchActions.onResetToDefault(bundle.uid, null) },
        onRestoreSaved = { patchActions.onRestoreSaved(bundle.uid, null) },
        onCopyFromBundle = copySelection,
        hasSavedSelection = savedPatches[bundle.uid]?.isNotEmpty() == true,
        modifier = modifier
    )
}
