/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import android.content.*
import androidx.compose.runtime.*
import app.morphe.manager.domain.apk.*
import app.morphe.manager.domain.bundles.*
import app.morphe.manager.domain.manager.*
import app.morphe.manager.domain.repository.*
import app.morphe.manager.patcher.patch.*
import app.morphe.manager.ui.model.*
import app.morphe.manager.ui.screen.shared.CopySelectionCandidate
import app.morphe.manager.util.*
import app.morphe.manager.util.PatchSelectionUtils.applyAvailability
import app.morphe.manager.util.PatchSelectionUtils.bulkEnableHoldsUniversal
import app.morphe.manager.util.PatchSelectionUtils.bulkEnablePatches
import app.morphe.manager.util.PatchSelectionUtils.mergeBundleOptions
import app.morphe.manager.util.PatchSelectionUtils.resetOptionsForPatch
import app.morphe.manager.util.PatchSelectionUtils.spansMultipleBundles
import app.morphe.manager.util.PatchSelectionUtils.togglePatch
import app.morphe.manager.util.PatchSelectionUtils.updateOption
import app.morphe.manager.util.PatchSelectionUtils.withBundle
import app.morphe.manager.util.PatchSelectionUtils.withBundleScope
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.InstallerType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** v2 expert-selection owner, retaining Extended scoped controls and exact known-failure rules. */
class ExpertModeSelection(
    private val scope: CoroutineScope,
    private val persistVisibility: suspend (packageName: String, uid: Int, globally: Boolean, hidden: Boolean) -> Unit,
    private val failureRecords: StateFlow<List<KnownPatchFailure>>
) {
    internal var currentInstallerType = InstallerType.STANDARD
    internal var currentApkArchitecture = ApkArchitecture.UNIVERSAL

    var app by mutableStateOf<SelectedApp?>(null)
        internal set

    var bundles by mutableStateOf<List<PatchBundleInfo.Scoped>>(emptyList())
    // Everything the app has patches in, so a source it is kept from can be offered back without
    // reopening the dialog. Only ever a superset of bundles
    internal var allBundles by mutableStateOf<List<PatchBundleInfo.Scoped>>(emptyList())
    // Source exclusions for this app, independent from global panel visibility.
    var mutedSources by mutableStateOf<Set<Int>>(emptySet())
    var globalHiddenSources by mutableStateOf<Set<Int>>(emptySet())
        internal set
    var patches by mutableStateOf<PatchSelection>(emptyMap())
    /** Snapshot of the selection at the moment the ExpertMode dialog was opened. Used by "Restore saved". */
    var savedPatches by mutableStateOf<PatchSelection>(emptyMap())
        internal set
    var options by mutableStateOf<Options>(emptyMap())
    // Patches that are new in the current bundle version relative to the last saved selection
    var newPatches by mutableStateOf<Map<Int, Set<String>>>(emptyMap())
    // Bundle and selection left behind by the last "Enable all". Universal patches are applied
    // only while this still matches the live selection, so any other edit disarms them again
    private var universalArmedFor by mutableStateOf<Map<Pair<Int, Set<String>>, Set<String>>>(emptyMap())
    // Whether the run this dialog configures reaches patches that declare other app versions,
    // so bulk actions offer the same set the selection was built from
    internal var allowIncompatible = false

    /** Picker behind the copy-from-another-bundle action of the expert-mode dialog. */
    val copy = CopySelectionController()

    var configurationKey: String = ""
        internal set

    fun applySet(snapshot: SelectionSnapshot) {
        val safe = snapshot.copy(patches = snapshot.patches.withoutKnownFailures(failureContext, failureRecords.value))
        val result = applyVisibleSelectionSet(SelectionSnapshot(patches, options), safe, bundles.mapTo(mutableSetOf()) { it.uid })
        patches = result.patches.applyAvailability()
        options = result.options
    }

    val allPatchesInfo: List<Pair<PatchBundleInfo.Scoped, List<Pair<PatchInfo, Boolean>>>>
        get() = allBundles.map { bundle ->
            val selected = patches[bundle.uid] ?: emptySet()
            val patches = bundle.patchSequence(true)
                .map { patch -> patch to (patch.name in selected) }
                .sortedBy { (patch, _) -> patch.name }
                .toList()
            bundle to patches
        }.filter { it.second.isNotEmpty() }
            .sortedWith(compareBy(
                patchSourceDisplayOrder(
                    app?.packageName.orEmpty(),
                    app?.version
                )
            ) { it.first })

    /**
     * The selection as the run would use it: what the dialog shows, minus what it holds back.
     *
     * A hidden source keeps its patches in [patches] so that offering it back returns
     * the selection made from it rather than an empty tab. Everything that speaks for the run -
     * the counter, the multiple-sources warning, the patches handed to the patcher - reads this
     * instead, or it would answer for a source the run never reaches.
     */
    val runSelection: PatchSelection
        get() {
            val shown = bundles.mapTo(mutableSetOf()) { it.uid }
            return patches.filterKeys { it in shown }
        }

    /** Total number of currently selected patches across all bundles. */
    val totalSelectedCount: Int
        get() = runSelection.values.sumOf { it.size }

    /** True when patches from more than one bundle are selected (triggers warning on proceed). */
    val hasMultipleBundles: Boolean
        get() = runSelection.spansMultipleBundles()

    /**
     * Toggle patch in expert mode.
     * Supports adding patches from bundles not yet in the selection.
     */
    fun toggle(bundleUid: Int, patchName: String) {
        // Locked patches are toggled only through availability rules; no-op here
        val patch = bundles
            .firstOrNull { it.uid == bundleUid }
            ?.patches
            ?.firstOrNull { it.name == patchName }
        val selected = patchName in patches[bundleUid].orEmpty()
        if (patch != null && lockState(patch).blocksToggle(selected)) return

        patches = patches.togglePatch(bundleUid, patchName)
            .applyAvailability()
    }

    /**
     * Select all given patches for a bundle.
     * Only adds patches that are not already selected. LOCKED_OFF patches are skipped.
     *
     * Universal patches are staged behind the regular ones and need a second call, see
     * [PatchSelectionUtils.bulkEnablePatches]. [patches] is the list the dialog currently
     * shows, so an active search narrows the scope of both stages.
     */
    fun selectAll(bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>) {
        val selected = this.patches[bundleUid].orEmpty()
        val updated = bulkEnablePatches(
            patches,
            selected,
            universalArmed(bundleUid, selected, patches),
            ::lockState
        )

        this.patches = this.patches.withBundle(bundleUid, updated)
            .applyAvailability()
        // Armed against what the availability rules left behind, so the next tap sees the
        // selection it is compared to
        universalArmedFor = universalArmedFor + ((bundleUid to patches.mapTo(mutableSetOf()) { it.first.name }) to this.patches[bundleUid].orEmpty())
    }

    /** True when the next [selectAll] holds universal patches back for another tap. */
    fun selectAllHoldsUniversal(bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>): Boolean {
        val selected = this.patches[bundleUid].orEmpty()
        return bulkEnableHoldsUniversal(
            patches,
            universalArmed(bundleUid, selected, patches),
            ::lockState
        )
    }

    /**
     * Lock state of [patch] for the install target the dialog is configuring.
     *
     * A REQUIRED patch only locks while the selection stays within one bundle, see
     * [PatchSelectionUtils.applyAvailability].
     */
    fun lockState(patch: PatchInfo): PatchLockState {
        val lock = patch.lockState(currentInstallerType, currentApkArchitecture, !hasMultipleBundles)
        val failed = knownFailures(failureContext, failureRecords.value)
        val isFailed = bundles.any { bundle -> "${bundle.uid}:${patch.name}" in failed && bundle.patches.any { it === patch } }
        return if (isFailed && lock == PatchLockState.LOCKED_ON) PatchLockState.NONE else lock
    }

    private fun universalArmed(bundleUid: Int, selected: Set<String>, patches: List<Pair<PatchInfo, Boolean>>) =
        universalArmedFor[bundleUid to patches.mapTo(mutableSetOf()) { it.first.name }] == selected

    /**
     * Deselect all given patches for a bundle.
     * Removes the bundle entry entirely if nothing remains selected. LOCKED_ON patches are kept.
     */
    fun deselectAll(bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>) {
        val kept = this.patches[bundleUid]?.toMutableSet() ?: mutableSetOf()
        patches.forEach { (patch, enabled) ->
            if (lockState(patch) == PatchLockState.LOCKED_ON) return@forEach
            if (enabled) kept.remove(patch.name)
        }
        this.patches = this.patches.withBundle(bundleUid, kept)
            .applyAvailability()
    }

    /**
     * Reset a bundle's selection to the default patches for the current install target.
     *
     * Defaults are read from the bundle itself rather than the dialog's list, so they cover the
     * complete set instead of just search results and reach only the patches the run would have
     * started with: the dialog lists patches declaring other app versions so they can be enabled
     * by hand, but they are no more part of the defaults here than they were on first open.
     */
    fun resetToDefault(bundleUid: Int, patchScope: Set<String>? = null) {
        val bundle = bundles.firstOrNull { it.uid == bundleUid } ?: return
        val defaults = bundle.patchSequence(allowIncompatible)
            .filter { it.defaultSelected(currentInstallerType, currentApkArchitecture) }
            .mapTo(mutableSetOf()) { it.name }
        patches = patches.withBundleScope(bundleUid, defaults, patchScope)
            .applyAvailability()
    }

    /**
     * Restores the DB-persisted patch selection for a bundle in expert mode.
     * No-op if there is no saved selection for the given bundle.
     */
    fun restoreSaved(bundleUid: Int, patchScope: Set<String>? = null) {
        val savedForBundle = savedPatches[bundleUid] ?: return
        patches = patches.withBundleScope(bundleUid, savedForBundle, patchScope)
            .applyAvailability()
    }

    /**
     * Update option in expert mode.
     */
    fun updateOption(
        bundleUid: Int,
        patchName: String,
        optionKey: String,
        value: Any?
    ) {
        options = options.updateOption(bundleUid, patchName, optionKey, value)
    }

    /**
     * Reset options for a patch in expert mode.
     */
    fun resetOptions(bundleUid: Int, patchName: String) {
        options = options.resetOptionsForPatch(bundleUid, patchName)
    }

    private var visibilityWrite: kotlinx.coroutines.Job? = null

    fun setSourceHidden(uid: Int, globally: Boolean, hidden: Boolean) {
        val packageName = app?.packageName ?: return
        if (globally) {
            globalHiddenSources = if (hidden) globalHiddenSources + uid else globalHiddenSources - uid
        } else {
            mutedSources = if (hidden) mutedSources + uid else mutedSources - uid
        }
        applyMutes()
        val previous = visibilityWrite
        visibilityWrite = scope.launch {
            previous?.join()
            withContext(Dispatchers.IO) { persistVisibility(packageName, uid, globally, hidden) }
        }
    }

    suspend fun awaitVisibilityWrites() { visibilityWrite?.join() }

    fun allowUnsupported() { allowIncompatible = true }

    private fun applyMutes() {
        bundles = allBundles.filterNot {
            it.uid in mutedSources || it.uid in globalHiddenSources
        }
        patches = patches.applyAvailability()
    }

    /** Opens the copy-from-another-bundle picker for [targetBundleUid]. */
    fun openCopy(targetBundleUid: Int, patchScope: Set<String>? = null) {
        val selectedApp = app ?: return
        copy.open(
            scope = scope,
            targetPackageName = selectedApp.packageName,
            targetBundleUid = targetBundleUid,
            targetPatchNames = targetBundlePatchNames(targetBundleUid).filterTo(mutableSetOf()) { patchScope == null || it in patchScope },
            patchScope = patchScope
        )
    }

    /**
     * Applies a picked [candidate] to the in-memory expert-mode selection. Changes reach the
     * database only when the user proceeds to patching.
     */
    fun applyCopy(candidate: CopySelectionCandidate) {
        val selectedApp = app ?: return
        val owner = session
        val targetBundleUid = copy.targetBundleUid ?: return
        val patchScope = copy.targetPatchScope

        scope.launch {
            val copied = copy.resolve(
                candidate = candidate,
                targetPatches = targetBundlePatchInfos(targetBundleUid), patchScope = patchScope
            ) ?: return@launch
            if (session !== owner || app !== selectedApp) return@launch

            // The copy comes from a run that may have targeted another installer
            patches = patches.withBundleScope(targetBundleUid, copied.patches, patchScope)
                .applyAvailability()
            options = options.mergeBundleOptions(targetBundleUid, copied.options)

            copy.finish(copied.patches.size)
        }
    }

    /**
     * Availability rules of the current install target, scoped to the bundles the dialog shows.
     *
     * A source being held back takes no part in the rules and is put back untouched afterward.
     * Whether a REQUIRED patch locks turns on the run drawing from one source or several, and a
     * source the run cannot reach is not one of them.
     */
    fun PatchSelection.applyAvailability(): PatchSelection {
        val shown = bundles.mapTo(mutableSetOf()) { it.uid }
        val heldBack = filterKeys { it !in shown }

        return filterKeys { it in shown }
            .applyAvailability(
                currentInstallerType,
                currentApkArchitecture,
                bundles.associate { it.uid to it.patches.associateBy { patch -> patch.name } }
            ).preserveKnownFailureChoices(this, knownFailures(failureContext, failureRecords.value).keys) + heldBack
    }

    private fun targetBundlePatchNames(bundleUid: Int): Set<String> =
        bundles.firstOrNull { it.uid == bundleUid }
            ?.patches
            ?.mapTo(mutableSetOf()) { it.name }
            ?: emptySet()

    private fun targetBundlePatchInfos(bundleUid: Int): Map<String, PatchInfo> =
        bundles.firstOrNull { it.uid == bundleUid }
            ?.patches
            ?.associateBy { it.name }
            ?: emptyMap()

    var unreadablePaths by mutableStateOf<List<PathValidationResult>>(emptyList())
        internal set

    /** Leaves the values alone and puts the user back in the selection to deal with them. */
    fun dismissUnreadablePaths() {
        unreadablePaths = emptyList()
    }

    var failureContext by mutableStateOf<PatchFailureContext?>(null)
        internal set


    private var session = Any()

    fun open(selectedApp: SelectedApp, sources: List<PatchBundleInfo.Scoped>,
        hidden: Set<Int>, globalHidden: Set<Int>, selected: PatchSelection, initialOptions: Options,
        added: Map<Int, Set<String>>, installerType: InstallerType, architecture: ApkArchitecture,
        allowOtherVersions: Boolean, context: PatchFailureContext?) {
        clear()
        app = selectedApp
        allBundles = sources
        mutedSources = hidden
        globalHiddenSources = globalHidden
        currentInstallerType = installerType
        currentApkArchitecture = architecture
        allowIncompatible = allowOtherVersions
        failureContext = context
        savedPatches = selected
        patches = selected
        options = initialOptions
        newPatches = added
        applyMutes()
    }

    fun clear() {
        session = Any()
        failureContext = null
        app = null
        bundles = emptyList()
        allBundles = emptyList()
        mutedSources = emptySet()
        globalHiddenSources = emptySet()
        patches = emptyMap()
        savedPatches = emptyMap()
        options = emptyMap()
        newPatches = emptyMap()
        universalArmedFor = emptyMap()
        allowIncompatible = false
        unreadablePaths = emptyList()
        copy.close()
    }

}
