/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import androidx.compose.runtime.mutableIntStateOf

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morphe.manager.R
import app.morphe.manager.downloaders.ApkPublications
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.domain.apk.InstalledApkInfo
import app.morphe.manager.domain.apk.LocalApkSources
import app.morphe.manager.domain.apk.SavedApkInfo
import app.morphe.manager.domain.batch.*
import app.morphe.manager.domain.bundles.AppVersionCatalog
import app.morphe.manager.domain.bundles.BundledAppTarget
import app.morphe.manager.domain.bundles.patchableBy
import app.morphe.manager.domain.bundles.canReuseOriginal
import app.morphe.manager.domain.bundles.offered
import app.morphe.manager.domain.bundles.installableOnDevice
import app.morphe.manager.domain.bundles.deviceIncompatible
import app.morphe.manager.domain.manager.DownloadUrlResolver
import app.morphe.manager.domain.installer.InstallerFileProvider
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.InstalledAppRepository
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.domain.repository.SourceMuteRepository
import app.morphe.manager.domain.repository.PatchSelectionRepository
import app.morphe.manager.patcher.patch.*
import app.morphe.manager.ui.model.renamesByDefault
import app.morphe.manager.ui.model.ApkDownloadHelperHost
import app.morphe.manager.ui.model.PostPatchPrompts
import app.morphe.manager.ui.model.createApkDownloadHelperRequest
import app.morphe.manager.ui.model.helperSignatureCheckAvailable
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
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.InstallerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * Live patch selection for one queued app while its editor is open.
 *
 * Mirrors the state the expert dialog drives in the single-app flow, so the batch screen can
 * reuse that dialog instead of growing a second patch list. Edits stay here until they are
 * applied, which keeps a canceled edit from touching the plan.
 */
class BatchPatchEdit(
    val itemId: String,
    /** Key this item saves its patches and options under, and the one a copy excludes itself by. */
    val configurationKey: String,
    /** The app's own package, which a clone's [configurationKey] is not. */
    val packageName: String,
    val appName: String,
    val bundles: List<PatchBundleInfo.Scoped>,
    val savedSelection: PatchSelection,
    val newPatches: Map<Int, Set<String>>,
    initialOptions: Options,
    private val installerType: InstallerType,
    private val apkArchitecture: ApkArchitecture,
    // Whether the queue reaches patches that declare other app versions, so bulk actions offer
    // the same set the plan was resolved from
    val allowIncompatible: Boolean,
    private val recommendedVersion: String? = null,
    val version: String? = recommendedVersion,
    val versionCode: Long? = null,
    initialHidden: Set<Int> = emptySet(),
    initialGlobalHidden: Set<Int> = emptySet(),
    val failureContext: PatchFailureContext? = null,
    knownFailureRecords: List<KnownPatchFailure> = emptyList()
) {
    var hiddenSources by mutableStateOf(initialHidden)
        private set
    var globalHiddenSources by mutableStateOf(initialGlobalHidden)
        private set
    var allowUnsupported by mutableStateOf(false)
    val runSelection get() = selection.withoutHiddenSources(hiddenSources + globalHiddenSources)
    val visibleBundles get() = bundles.filterNot { it.uid in hiddenSources || it.uid in globalHiddenSources }
    fun setSourceHidden(uid: Int, globally: Boolean, hidden: Boolean) {
        if (globally) globalHiddenSources = if (hidden) globalHiddenSources + uid else globalHiddenSources - uid
        else hiddenSources = if (hidden) hiddenSources + uid else hiddenSources - uid
        selection = selection.applyItemAvailability()
    }

    private val failed = knownFailures(failureContext, knownFailureRecords)
    var selection by mutableStateOf(savedSelection.withoutKnownFailures(failureContext, knownFailureRecords))
        private set

    var options by mutableStateOf(initialOptions)
        private set

    // Bundle and selection left behind by the last "Enable all". Universal patches are applied
    // only while this still matches the live selection, so any other edit disarms them again
    private var universalArmedFor by mutableStateOf<Map<Pair<Int, Set<String>>, Set<String>>>(emptyMap())

    val allPatchesInfo: List<Pair<PatchBundleInfo.Scoped, List<Pair<PatchInfo, Boolean>>>>
        get() = bundles.map { bundle ->
            val selected = selection[bundle.uid].orEmpty()
            val patches = bundle.patchSequence(true)
                .map { patch -> patch to (patch.name in selected) }
                .sortedBy { (patch, _) -> patch.name }
                .toList()
            bundle to patches
        }.filter { it.second.isNotEmpty() }
            .sortedWith(compareBy(patchSourceDisplayOrder(packageName, recommendedVersion)) { it.first })

    val totalSelectedCount get() = runSelection.values.sumOf { it.size }

    val hasMultipleBundles get() = runSelection.spansMultipleBundles()

    private val patchesByName = bundles.associate { it.uid to it.patches.associateBy { patch -> patch.name } }

    /**
     * Lock state of [patch] for the install target this queue runs against.
     *
     * A REQUIRED patch only locks while the item draws its patches from one bundle, see
     * [PatchSelectionUtils.applyAvailability].
     */
    fun lockStateOf(patch: PatchInfo): PatchLockState {
        val lock = patch.lockState(installerType, apkArchitecture, !hasMultipleBundles)
        val isFailed = bundles.any { bundle -> "${bundle.uid}:${patch.name}" in failed && bundle.patches.any { it === patch } }
        return if (isFailed && lock == PatchLockState.LOCKED_ON) PatchLockState.NONE else lock
    }

    fun togglePatch(bundleUid: Int, patchName: String) {
        // Locked patches are toggled only through availability rules; no-op here
        val patch = bundles.firstOrNull { it.uid == bundleUid }
            ?.patches
            ?.firstOrNull { it.name == patchName }
        val selected = patchName in selection[bundleUid].orEmpty()
        if (patch != null && lockStateOf(patch).blocksToggle(selected)) return

        selection = selection.togglePatch(bundleUid, patchName).applyItemAvailability()
    }

    /**
     * Select all patches shown for a bundle, staging universal patches behind the regular ones
     * exactly like the single-app flow does, see [PatchSelectionUtils.bulkEnablePatches].
     */
    fun selectAll(bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>) {
        val selected = selection[bundleUid].orEmpty()
        val updated = bulkEnablePatches(patches, selected, universalArmed(bundleUid, selected, patches), ::lockStateOf)

        replaceBundle(bundleUid, updated)
        // Armed against what the availability rules left behind, so the next tap sees the
        // selection it is compared to
        universalArmedFor = universalArmedFor + ((bundleUid to patches.mapTo(mutableSetOf()) { it.first.name }) to selection[bundleUid].orEmpty())
    }

    /** True when the next [selectAll] holds universal patches back for another tap. */
    fun selectAllHoldsUniversal(bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>): Boolean {
        val selected = selection[bundleUid].orEmpty()
        return bulkEnableHoldsUniversal(patches, universalArmed(bundleUid, selected, patches), ::lockStateOf)
    }

    private fun universalArmed(bundleUid: Int, selected: Set<String>, patches: List<Pair<PatchInfo, Boolean>>) =
        universalArmedFor[bundleUid to patches.mapTo(mutableSetOf()) { it.first.name }] == selected

    fun deselectAll(bundleUid: Int, patches: List<Pair<PatchInfo, Boolean>>) {
        val removed = patches
            .filterNot { (patch, _) -> lockStateOf(patch) == PatchLockState.LOCKED_ON }
            .mapTo(mutableSetOf()) { (patch, _) -> patch.name }
        replaceBundle(bundleUid, selection[bundleUid].orEmpty() - removed)
    }

    /**
     * Reset a bundle's selection to what the plan would have resolved on its own.
     *
     * Defaults are read from the bundle instead of the dialog's list: the list also carries the
     * patches declaring other app versions so they can be enabled by hand, and those belong to
     * the defaults only where the run itself reaches them.
     */
    fun resetToDefault(bundleUid: Int, patchScope: Set<String>? = null) {
        val bundle = bundles.firstOrNull { it.uid == bundleUid } ?: return
        replaceBundle(
            bundleUid,
            bundle.patchSequence(allowIncompatible)
                .filter { it.defaultSelected(installerType, apkArchitecture) }
                .mapTo(mutableSetOf()) { it.name },
            patchScope
        )
    }

    fun restoreSaved(bundleUid: Int, patchScope: Set<String>? = null) {
        replaceBundle(bundleUid, savedSelection[bundleUid] ?: return, patchScope)
    }

    fun updateOption(bundleUid: Int, patchName: String, optionKey: String, value: Any?) {
        options = options.updateOption(bundleUid, patchName, optionKey, value)
    }

    fun resetOptions(bundleUid: Int, patchName: String) {
        options = options.resetOptionsForPatch(bundleUid, patchName)
    }

    /** Patch schema of [bundleUid], which a copied selection is filtered against. */
    fun patchesOf(bundleUid: Int) = patchesByName[bundleUid].orEmpty()

    /** Replaces the selection of [bundleUid] with one copied from another bundle. */
    fun applyCopy(bundleUid: Int, copied: CopiedSelection, patchScope: Set<String>? = null) {
        replaceBundle(bundleUid, copied.patches, patchScope)
        options = options.mergeBundleOptions(bundleUid, copied.options)
    }

    private fun replaceBundle(bundleUid: Int, patches: Set<String>, patchScope: Set<String>? = null) {
        selection = selection.withBundleScope(bundleUid, patches, patchScope).applyItemAvailability()
    }

    fun applySet(snapshot: app.morphe.manager.domain.repository.SelectionSnapshot) {
        val safe = snapshot.copy(patches = snapshot.patches.filterValues { it.isNotEmpty() }
            .mapValues { (uid, patches) -> patches.filterNotTo(mutableSetOf()) { "$uid:$it" in failed } })
        val result = app.morphe.manager.domain.repository.applyVisibleSelectionSet(
            app.morphe.manager.domain.repository.SelectionSnapshot(selection, options), safe,
            visibleBundles.mapTo(mutableSetOf()) { it.uid })
        selection = result.patches.applyItemAvailability()
        options = result.options
    }

    /** Availability rules of the install target, so an edit lands on the plan already settled. */
    private fun PatchSelection.applyItemAvailability(): PatchSelection {
        val visible = visibleBundles.mapTo(mutableSetOf()) { it.uid }
        return filterKeys { it in visible }.applyAvailability(installerType, apkArchitecture,
            patchesByName.filterKeys { it in visible }).preserveKnownFailureChoices(this, failed.keys) + filterKeys { it !in visible }
    }
}

/**
 * Screen-level wrapper around [BatchPatchCoordinator].
 *
 * The run itself lives in the coordinator on the application scope, so leaving and reopening
 * the batch screen keeps a queue going. This ViewModel only owns screen state such as which
 * item a file picker was opened for.
 */
class BatchPatcherViewModel : ViewModel(), KoinComponent, ApkDownloadHelperHost {
    private val knownPatchFailures: KnownPatchFailures by inject()
    val knownFailureRecords get() = knownPatchFailures.records
    init { viewModelScope.launch { knownPatchFailures.loadRecords() } }
    private val app: Application by inject()
    private val fs: Filesystem by inject()
    private val pm: PM by inject()
    private val coordinator: BatchPatchCoordinator by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val patchSelectionRepository: PatchSelectionRepository by inject()
    private val downloadUrlResolver: DownloadUrlResolver by inject()
    private val versionCatalog: AppVersionCatalog by inject()
    private val localApkSources: LocalApkSources by inject()
    private val prefs: PreferencesManager by inject()
    private val sourceMuteRepository: SourceMuteRepository by inject()
    private val patchOptionsRepository: app.morphe.manager.domain.repository.PatchOptionsRepository by inject()

    val state = coordinator.state
    val patchReleaseAgeGate = PatchReleaseAgeGate()

    /** Notification and tour prompts raised once the queue installs or saves an app. */
    val postPatchPrompts = PostPatchPrompts(app, prefs, viewModelScope)

    /** Package the attach-APK picker was opened for, null when no picker is pending. */
    var attachTarget: String? by mutableStateOf(null)
        private set

    /**
     * Plans the run once. Re-entering the screen while a queue is alive keeps the existing
     * state instead of throwing away progress, and a run that already covers exactly these
     * apps is reused so rotation does not restart planning.
     */
    fun ensurePlan(targets: List<BatchTarget>) {
        // Two cards can stand for the same install, such as a mount and a renamed build of one
        // app, and the queue lists each install once
        val queued = targets.distinctBy { it.id }
        val current = state.value
        if (current?.phase == BatchPhase.RUNNING || current?.targets == queued) return
        coordinator.enqueue(queued)
    }

    private val rootInstaller: app.morphe.manager.domain.installer.RootInstaller by inject()
    val canMount = rootInstaller.isDeviceRooted()
    fun setInstallTarget(itemId: String, useMount: Boolean) = coordinator.setInstallTarget(itemId, useMount)

    fun requestAttach(packageName: String) {
        attachTarget = packageName
    }

    /**
     * Everything the APK availability dialog needs about one queued app: which versions the
     * sources cover, and what is already on the device to patch from.
     */
    data class ApkChoice(
        val item: BatchPatchItem,
        val recommended: AppTarget?,
        val compatible: List<BundledAppTarget>,
        val saved: SavedApkInfo?,
        val installed: InstalledApkInfo?,
        /** The unpatched version on the device, even where [installed] is no source to patch from. */
        val installedVersion: String?,
        val hasStockInstall: Boolean,
        val selectedVersion: AppTarget?,
        val selectedSourceUid: Int? = null
    )

    var apkChoice: ApkChoice? by mutableStateOf(null)
        private set

    fun beginApkChoice(item: BatchPatchItem, automatic: Boolean = true) {
        viewModelScope.launch {
            val recommended = versionCatalog.recommendedVersions.first()[item.packageName]
            val compatible = versionCatalog.compatibleVersions.first()[item.packageName].orEmpty()
            val expertMode = prefs.useExpertMode.get()
            val device = withContext(Dispatchers.IO) {
                localApkSources.installed(item.packageName)
            }

            apkChoice = ApkChoice(
                item = item,
                recommended = recommended,
                compatible = compatible,
                saved = withContext(Dispatchers.IO) { localApkSources.saved(item.packageName) },
                // The installed source is an expert-mode offer, and only for a version the
                // patches target: the two conditions the single-app flow puts on the button
                installed = device.apk.takeIf { expertMode }.patchableBy(compatible),
                installedVersion = device.version,
                hasStockInstall = device.hasStockInstall,
                selectedVersion = recommended
            )
            if (automatic && !compatible.offered().deviceIncompatible()) {
                val choice = checkNotNull(apkChoice)
                val eligible = compatible.offered().filter { it.installableOnDevice() }
                if ((compatible.isEmpty() || eligible.isNotEmpty()) &&
                    canReuseOriginal(choice.saved, recommended?.version, eligible))
                    useApkSource(preferInstalled = false)
                else beginApkSearch(item, recommended?.version)
            }
        }
    }

    fun selectApkVersion(entry: BundledAppTarget) {
        val choice = apkChoice ?: return
        if (entry !in choice.compatible.offered() || !entry.installableOnDevice()) return
        apkChoice = choice.copy(selectedVersion = entry.target, selectedSourceUid = entry.bundleUid)
        coordinator.setVersionSource(choice.item.id, entry.bundleUid, entry.target.version)
    }

    fun cancelApkChoice() {
        apkChoice = null
    }

    /** Keeps the APK already on hand, re-resolving in case the user switched between the two. */
    fun useApkSource(preferInstalled: Boolean) {
        val choice = apkChoice ?: return
        apkChoice = null
        coordinator.useSource(choice.item.id, preferInstalled)
    }

    /**
     * App the download instructions are open for, with the best URL known so far.
     *
     * The unfollowed search URL is published first and replaced once the redirect resolves,
     * which is what tells the dialog the destination is not known yet.
     */
    data class ApkSearch(
        val item: BatchPatchItem,
        val version: String?,
        val url: String,
        /**
         * Versions the sources cover, carried so a download helper can be told what else is
         * acceptable when the requested version is no longer offered anywhere.
         */
        val compatible: List<BundledAppTarget> = emptyList()
    )

    var apkSearch: ApkSearch? by mutableStateOf(null)
        private set
    private var downloadRedirectJob: Job? = null

    /** [version] is what the user picked in the availability dialog, not just the recommended one. */
    fun beginApkSearch(item: BatchPatchItem, version: String?) {
        downloadRedirectJob?.cancel()
        // Read before the choice is dropped, which is where the covered versions were resolved
        val compatible = apkChoice?.takeIf { it.item.id == item.id }?.compatible.orEmpty()
        if (compatible.offered().deviceIncompatible()) return
        val sourceTarget = compatible.filter {
            it.target.version == version && (item.target.versionSourceUid == null || it.bundleUid == item.target.versionSourceUid)
        }.distinctBy { it.abiBuildCodes to it.formFactor }.singleOrNull()
        apkChoice = null
        apkSearch = ApkSearch(
            item = item,
            version = version,
            url = downloadUrlResolver.apiSearchUrl(item.packageName, version, sourceTarget),
            compatible = compatible
        )
        val request = checkNotNull(apkSearch)
        downloadRedirectJob = viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val uids = item.target.versionSourceUid?.let(::listOf) ?: item.bundles.map { it.uid }
                val patchSources = patchBundleRepository.patchSourceHeaders(item.packageName, uids)
                downloadUrlResolver.resolve(item.packageName, version, patchSources, sourceTarget)
            }
            ensureActive()
            apkSearch = apkSearch?.takeIf { it === request }?.copy(url = resolved)
        }
    }

    fun cancelApkSearch() {
        downloadRedirectJob?.cancel()
        apkSearch = null
    }

    fun chooseOriginalApkManually() {
        val item = apkSearch?.item ?: return
        cancelApkSearch()
        beginApkChoice(item, automatic = false)
    }

    /** App waiting for its downloaded file, null when nothing was sent to the browser. */
    var attachPrompt: BatchPatchItem? by mutableStateOf(null)
        private set

    /**
     * Hands the download page to the browser or the clipboard and leaves a prompt behind.
     *
     * The file picker deliberately waits for that prompt rather than opening straight away:
     * the browser is coming to the front at this moment, and Android does not let a
     * backgrounded app reliably start anything on top of it.
     */
    fun confirmApkSearch(handOff: (String) -> Boolean) {
        val search = apkSearch ?: return
        cancelApkSearch()
        if (handOff(search.url)) {
            attachPrompt = search.item
        } else {
            app.toast(app.getString(R.string.sources_management_failed_to_open_url))
        }
    }

    fun dismissAttachPrompt() {
        attachPrompt = null
    }

    override val helperSignatureCheckAvailable: Boolean
        get() = apkSearch?.let {
            helperSignatureCheckAvailable(patchBundleRepository.appMetadata.value[it.item.packageName])
        } == true

    /**
     * Build the request for an APK download helper, describing the original APK of the queued app
     * the download instructions are open for.
     */
    override fun createApkDownloadHelperIntent(component: ComponentName): Intent? {
        val search = apkSearch ?: return null
        val packageName = search.item.packageName

        return createApkDownloadHelperRequest(
            component = component,
            callerPackage = app.packageName,
            packageName = packageName,
            appName = search.item.appName,
            versionName = search.version,
            compatible = search.compatible,
            metadata = patchBundleRepository.appMetadata.value[packageName],
            stockInstallRequired = search.item.useMount &&
                    search.item.source !is BatchApkSource.Installed,
            fallbackWebUrl = downloadUrlResolver.webSearchUrl(packageName, search.version)
        )
    }

    /**
     * Takes the archive a helper downloaded for the queued app, which then goes through the same
     * package, version and signature checks an attached file does.
     */
    override fun onHelperApkReceived(uri: Uri) {
        val item = apkSearch?.item ?: return
        cancelApkSearch()
        attachPrompt = null
        requestAttach(item.id)
        onApkPicked(uri)
    }

    override fun onHelperInstalledAppChosen(packageName: String) {
        val item = apkSearch?.item ?: return
        if (packageName != item.packageName) {
            // The helper answered about an app the queue never asked to patch
            app.toast(app.getString(R.string.home_apk_helper_wrong_package))
            return
        }

        cancelApkSearch()
        attachPrompt = null
        // Re-resolved rather than taken on the helper's word: an installed app is only accepted
        // while it still looks like the stock one, see BatchPlanResolver
        val resolution = coordinator.useSource(item.id, preferInstalled = true)
        viewModelScope.launch { settleGivenApk(item.id, resolution) }
    }

    /**
     * True while the APKs on hand for a queued app are looked into, or one given to it is read and
     * the app resolved against it.
     */
    val processingApk get() = apkProcessingCount > 0

    // Counted rather than flagged, so APKs given one over another keep it up until the last is in
    private var apkProcessingCount by mutableIntStateOf(0)

    private suspend fun <T> whileProcessingApk(block: suspend () -> T): T {
        apkProcessingCount++
        try {
            return block()
        } finally {
            apkProcessingCount--
        }
    }

    /**
     * Waits for [itemId] to be resolved against the APK just given to it, and then opens the
     * editor when none of its patches is selected, as the single-app flow opens the selection
     * after the APK, so the app is not left for the user to find the editor for. The APK question
     * is closed otherwise, having waited for the answer.
     */
    private suspend fun settleGivenApk(itemId: String, resolution: Job?) = whileProcessingApk {
        resolution?.join()
        // Another APK given to the app meanwhile settles all this itself
        if (resolution?.isCancelled == true) return@whileProcessingApk
        val item = state.value?.items?.firstOrNull { it.id == itemId }?.takeIf { resolution != null }
        // The card changes little for an APK of the version it already had, so the choice is confirmed
        if (item?.source != null) app.toast(app.getString(R.string.batch_patch_apk_selected))
        if (item?.state == BatchItemState.NO_PATCHES && item.source != null && item.selectedByHand) {
            beginEdit(item)
        }
    }

    /** Patch selection editor for one queued app, null when none is open. */
    var edit: BatchPatchEdit? by mutableStateOf(null)
        private set

    /** Picker behind the copy-from-another-bundle action of the editor. */
    val editCopy = CopySelectionController()

    /**
     * Opens the editor for [item], scoping the patch list to the exact APK version the queue
     * resolved so the user never sees patches that could not run against it anyway.
     */
    fun beginEdit(item: BatchPatchItem) {
        val source = item.source ?: return
        viewModelScope.launch {
            val bundles = patchBundleRepository
                .scopedBundleInfoFlow(item.packageName, source.version, source.versionCode)
                .first()
                .filter { it.enabled }

            // A patch counts as new when it was absent from the snapshot taken at the last
            // run. Without a snapshot there is no "last run" to compare against, so nothing
            // is badged rather than everything
            val newPatches = bundles.associate { bundle ->
                val seen = patchSelectionRepository.getSeenPatches(item.configurationKey, bundle.uid)
                bundle.uid to bundle.patches
                    .filter { seen != null && it.name !in seen }
                    .mapTo(mutableSetOf()) { it.name }
            }.filterValues { it.isNotEmpty() }

            edit = BatchPatchEdit(
                failureContext = knownPatchFailures.prepare(source.failureInput(item.packageName)),
                knownFailureRecords = knownPatchFailures.records.value,
                itemId = item.id,
                configurationKey = item.configurationKey,
                packageName = item.packageName,
                appName = item.appName,
                bundles = bundles,
                savedSelection = patchSelectionRepository.getAllSelectionsForPackage(item.configurationKey) +
                    (item.resolvedSelection ?: emptyMap()) + item.selection,
                newPatches = newPatches,
                initialOptions = patchOptionsRepository.getAllOptionsForPackage(item.configurationKey,
                    bundles.associate { it.uid to it.patches.associateBy { patch -> patch.name } }) + item.options,
                // The queue resolved its plan against one install target, so the editor has to
                // lock patches by the same rules the run will be executed with
                installerType = installerTypeFor(item.useMount),
                // Read from the same APK the plan was resolved against, so an edit answers the
                // architecture rules with what the run will hand the patcher
                apkArchitecture = source.apkArchitecture(),
                // Same rule the plan was resolved with, see BatchPlanResolver
                allowIncompatible = item.forceVersionMismatch || prefs.disablePatchVersionCompatCheck.get(),
                recommendedVersion = source.version,
                version = source.version,
                versionCode = source.versionCode,
                initialHidden = sourceMuteRepository.getMutedFor(item.packageName) +
                    panelHiddenFor(item.packageName, prefs.patchPanelAppHiddenSources.get()),
                initialGlobalHidden = prefs.patchPanelGlobalHiddenSources.get().mapNotNull { it.toIntOrNull() }.toSet()
            )
        }
    }

    /** Opens the copy-from-another-bundle picker for [targetBundleUid] of the open editor. */
    fun openEditCopyDialog(targetBundleUid: Int, patchScope: Set<String>? = null) {
        val current = edit ?: return
        editCopy.open(
            scope = viewModelScope,
            targetPackageName = current.configurationKey,
            targetBundleUid = targetBundleUid,
            targetPatchNames = current.patchesOf(targetBundleUid).keys.filterTo(mutableSetOf()) { patchScope == null || it in patchScope },
            patchScope = patchScope
        )
    }

    /**
     * Applies a picked [candidate] to the open editor. Changes reach the plan only when the
     * user confirms the editor, and the database only after the run itself.
     */
    fun applyEditCopy(candidate: CopySelectionCandidate) {
        val current = edit ?: return
        val targetBundleUid = editCopy.targetBundleUid ?: return
        val patchScope = editCopy.targetPatchScope

        viewModelScope.launch {
            val copied = editCopy.resolve(
                candidate = candidate,
                targetPatches = current.patchesOf(targetBundleUid), patchScope = patchScope
            ) ?: return@launch
            if (edit !== current) return@launch

            current.applyCopy(targetBundleUid, copied, patchScope)
            editCopy.finish(copied.patches.size)
        }
    }

    fun cancelEdit() {
        edit = null
        editCopy.close()
    }

    /**
     * App whose patch source is being chosen, null when no picker is open.
     *
     * Simple mode is asked which source to use in the single-app flow too. The queue cannot
     * ask mid-run, so the same question is answered here instead.
     */
    var sourcePick: BatchPatchItem? by mutableStateOf(null)
        private set

    fun beginSourcePick(item: BatchPatchItem) {
        sourcePick = item
    }

    fun cancelSourcePick() {
        sourcePick = null
    }

    fun pickSource(bundleUid: Int) {
        val item = sourcePick ?: return
        sourcePick = null
        coordinator.narrowToSource(item.id, bundleUid)
    }

    private var editVisibilityWrite: Job? = null

    fun setEditSourceHidden(uid: Int, globally: Boolean, hidden: Boolean) {
        val current = edit ?: return
        current.setSourceHidden(uid, globally, hidden)
        val previous = editVisibilityWrite
        editVisibilityWrite = viewModelScope.launch {
            previous?.join()
            if (globally) prefs.edit {
                prefs.patchPanelGlobalHiddenSources.value = if (hidden) prefs.patchPanelGlobalHiddenSources.value + uid.toString()
                    else prefs.patchPanelGlobalHiddenSources.value - uid.toString()
            } else {
                prefs.edit {
                    val key = "${current.packageName}|$uid"
                    prefs.patchPanelAppHiddenSources.value = if (hidden) prefs.patchPanelAppHiddenSources.value + key
                        else prefs.patchPanelAppHiddenSources.value - key
                }
                if (!hidden) sourceMuteRepository.unmute(current.packageName, uid)
            }
        }
    }

    fun applyEdit() {
        val current = edit ?: return
        viewModelScope.launch {
            editVisibilityWrite?.join()
            if (edit !== current) return@launch
            coordinator.updateSelection(current.itemId, current.runSelection, current.options, current.allowUnsupported,
                rememberedSelection = current.selection,
                knownFailureRetries = if (current.allowUnsupported) knownFailures(current.failureContext, knownPatchFailures.records.value).keys.intersect(current.runSelection.flatMap { (uid, names) -> names.map { "$uid:$it" } }.toSet()).mapTo(mutableSetOf()) { knownFailureRetryKey(current.failureContext, it) } else emptySet(),
                sourceBundles = current.bundles.map { bundle -> BatchBundleRef(bundle.uid, bundle.name, bundle.version,
                    bundle.patches.mapTo(mutableSetOf()) { it.name },
                    bundle.patches.filter { it.renamesByDefault }.mapTo(mutableSetOf()) { it.name }) })
            edit = null
            editCopy.close()
        }
    }

    /**
     * Copies the picked APK into the manager's private storage before handing it to the
     * resolver, because the content URI is not readable once the picker session ends.
     */
    fun onApkPicked(uri: Uri?) {
        val itemId = attachTarget
        attachTarget = null
        if (uri == null || itemId == null) return

        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                try { copyToWorkspace(uri) }
                finally { InstallerFileProvider.discardNativeOriginal(app, uri) }
            }
            if (file == null) {
                app.toast(app.getString(R.string.home_invalid_apk_io_error))
                return@launch
            }
            coordinator.attachApk(itemId, file)
        }
    }

    fun toggleExcluded(itemId: String) = coordinator.toggleExcluded(itemId)

    /**
     * Accepts an unsupported version. Confirmed with a toast because the card only swaps a
     * badge, which does not say what was just taken on.
     */
    fun forceVersion(itemId: String) {
        coordinator.forceVersion(itemId)
        app.toast(app.getString(R.string.batch_patch_force_version_done))
    }

    /**
     * Accepts an APK signed by someone the sources do not vouch for. Confirmed with a toast for
     * the same reason as [forceVersion]: the card only swaps a badge.
     */
    fun acceptUnverifiedSignature(itemId: String) {
        coordinator.acceptUnverifiedSignature(itemId)
        app.toast(app.getString(R.string.batch_patch_accept_signature_done))
    }

    fun setPolicy(policy: BatchInstallPolicy) = coordinator.setPolicy(policy)

    fun markInstalled(itemId: String, installedPackageName: String) =
        coordinator.markInstallResult(
            itemId = itemId,
            outcome = BatchInstallOutcome.INSTALLED,
            installedPackageName = installedPackageName
        )

    fun markInstallFailed(itemId: String, message: String?) =
        coordinator.markInstallResult(itemId, BatchInstallOutcome.FAILED, message)

    /** Launches an app the summary just installed. */
    fun openApp(packageName: String) {
        pm.launch(packageName)
    }

    private fun releaseAgeWarnings(): List<PatchReleaseAgeWarning> {
        val current = patchBundleRepository.bundleState.value as? PatchBundleRepository.BundleState.Ready ?: return emptyList()
        return state.value?.runnable.orEmpty().flatMap { item ->
            selectedPatchReleaseAgeWarnings(item.packageName, item.version, item.source?.versionCode,
                ApkPublications(app).get(item.packageName, item.version, item.source?.versionCode)?.day,
                item.selection, current.info, current.sources.values.toList())
        }
    }

    private var startReviewJob: Job? = null
    fun start() {
        if (startReviewJob?.isActive == true) return
        startReviewJob = viewModelScope.launch {
            val current = state.value ?: return@launch
            for (item in current.runnable) {
                val context = item.source?.let { knownPatchFailures.prepare(it.failureInput(item.packageName)) }
                if (state.value != current) return@launch
                val failed = knownFailures(context, knownPatchFailures.records.value)
                val filtered = item.selection.mapValues { (uid, names) -> names.filterTo(linkedSetOf()) {
                    "$uid:$it" !in failed || knownFailureRetryKey(context, "$uid:$it") in item.knownFailureRetries
                } }
                if (filtered != item.selection) {
                    coordinator.updateSelection(item.id, filtered, item.options)
                    state.value?.items?.firstOrNull { it.id == item.id }?.let(::beginEdit)
                    return@launch
                }
            }
            startReviewedQueue()
        }
    }

    private fun startReviewedQueue() {
        patchReleaseAgeGate.request(::releaseAgeWarnings, { coordinator.start() }, { warnings ->
            val current = state.value ?: return@request
            val affected = current.runnable.filter { item -> warnings.forApk(item.packageName, item.version, item.source?.versionCode).isNotEmpty() }
            affected.forEach { item ->
                val own = warnings.forApk(item.packageName, item.version, item.source?.versionCode)
                coordinator.updateSelection(item.id, item.selection.withoutAgeWarnings(own), item.options,
                    rememberedSelection = item.resolvedSelection?.withoutAgeWarnings(own))
            }
            affected.firstOrNull()?.id?.let { id -> state.value?.items?.firstOrNull { it.id == id }?.let(::beginEdit) }
        }, { cancelEdit() })
    }

    fun cancel() = coordinator.cancel()

    fun keep() = coordinator.keep()

    fun clear() = coordinator.clear()

    /**
     * Re-plans the apps that failed or were canceled so the user can retry without
     * rebuilding the selection from the home screen.
     */
    fun retryUnfinished() = coordinator.retryUnfinished()

    /**
     * Records the [InstallType] of a patched app once it is installed from the summary,
     * replacing the SAVED record the queue wrote after patching.
     */
    suspend fun persistInstalled(
        item: BatchPatchItem,
        installedPackageName: String,
        installType: InstallType
    ): Boolean = withContext(Dispatchers.IO) {
        val selectionPayload = item.appliedRecipe?.payload ?: patchBundleRepository.snapshotSelection(item.selection)
        val version = item.patchedBuild
            ?.let { pm.getPackageInfo(it.file)?.versionName?.takeUnless { name -> name.isBlank() } }
            ?: item.version
            ?: return@withContext false

        installedAppRepository.addOrUpdate(
            currentPackageName = installedPackageName,
            originalPackageName = item.packageName,
            isClone = item.producesCloneAs(installedPackageName),
            version = version,
            installType = installType,
            patchSelection = item.selection,
            selectionPayload = selectionPayload
        )
        true
    }

    private fun copyToWorkspace(uri: Uri): File? = try {
        val displayName = app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index != -1) cursor.getString(index) else null
        }
        val extension = displayName?.substringAfterLast('.', "apk")?.lowercase() ?: "apk"
        val (target, use) = coordinator.reserveImport(extension)
        try {
            target.parentFile?.mkdirs()
            val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (copied == null || copied == 0L) { target.delete(); null } else target
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally { use.finish() }
    } catch (e: Exception) {
        Log.e(tag, "Failed to copy attached APK", e)
        null
    }
}
