/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import android.app.Application
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.graphics.drawable.Drawable
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import app.morphe.manager.R
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.data.platform.NetworkInfo
import app.morphe.manager.domain.apk.*
import app.morphe.manager.downloaders.ApkPublications
import app.morphe.manager.domain.batch.BatchPatchCoordinator
import app.morphe.manager.domain.batch.mergeNewlyAdded
import app.morphe.manager.domain.bundles.*
import app.morphe.manager.domain.installer.InstallerManager
import app.morphe.manager.domain.installer.InstallerFileProvider
import app.morphe.manager.domain.installer.RootInstaller
import app.morphe.manager.domain.manager.*
import app.morphe.manager.domain.repository.*
import app.morphe.manager.patcher.patch.*
import app.morphe.manager.patcher.patch.PatchBundleInfo.Extensions.toPatchSelection
import app.morphe.manager.patcher.split.SplitApkInspector
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.ui.model.*
import app.morphe.manager.ui.model.navigation.Patcher
import app.morphe.manager.util.*
import app.morphe.manager.util.PatchSelectionUtils.applyAvailability
import app.morphe.manager.util.PatchSelectionUtils.restrictTo
import app.morphe.manager.util.PatchSelectionUtils.sanitizeForPatcher
import app.morphe.manager.util.PatchSelectionUtils.validatePatchOptions
import app.morphe.manager.util.PatchSelectionUtils.validatePatchSelection
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.InstallerType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileNotFoundException

/** * Dialog state for unsupported version warning. */
data class UnsupportedVersionDialogState(
    val packageName: String,
    val version: String,
    val versionCode: Long? = null,
    val recommendedVersion: AppTarget?,
    val compatibleVersionNames: List<String> = emptyList(),
    val compatibleVersionDescriptions: Map<String, String> = emptyMap(),
    val compatibleVersionCodes: Map<String, Set<Int>> = emptyMap(),
    /** True if the selected version is marked as experimental in the patch bundle. */
    val isExperimental: Boolean = false
)


/** Dialog state for wrong package warning. */
data class WrongPackageDialogState(
    val expectedPackage: String,
    val actualPackage: String
)

/**
 * Dialog state for APK signature mismatch warning.
 * Shown when the selected APK's signing certificate does not match
 * the expected signatures declared in the patch bundle.
 */
data class InvalidSignatureDialogState(
    val packageName: String,
    val appName: String,
)

/** An installed app entry shown in the universal-patch app picker. */
data class InstalledAppPickerItem(
    val packageName: String,
    val label: String,
    val packageInfo: PackageInfo,
    val isSystemApp: Boolean,
    val info: InstalledApkInfo
)

/** v2 PatchFlow ownership, adapted to the released Extended acquisition and warning contracts. */
class PatchFlow(
    private val lifetimeScope: CoroutineScope,
    private val app: Application,
    private val patchBundleRepository: PatchBundleRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val originalApkRepository: OriginalApkRepository,
    private val patchSelectionRepository: PatchSelectionRepository,
    private val sourceMuteRepository: SourceMuteRepository,
    private val optionsRepository: PatchOptionsRepository,
    private val patchOptionsPrefs: PatchOptionsPreferencesManager,
    private val networkInfo: NetworkInfo,
    val prefs: PreferencesManager,
    private val pm: PM,
    val rootInstaller: RootInstaller,
    private val installerManager: InstallerManager,
    private val filesystem: Filesystem,
    private val appDataResolver: AppDataResolver,
    private val batchPatchCoordinator: BatchPatchCoordinator,
    private val downloadUrlResolver: DownloadUrlResolver,
    versionCatalog: AppVersionCatalog,
    private val localApkSources: LocalApkSources,
    private val knownPatchFailures: KnownPatchFailures,
    private val dismissInstalledAppInfo: () -> Unit,
    private val installedAppsReady: () -> Boolean
) : ApkDownloadHelperHost {
    private var flowJob = SupervisorJob(lifetimeScope.coroutineContext[Job])
    private var scope = CoroutineScope(lifetimeScope.coroutineContext + flowJob)

    private fun cancelPendingLookups() {
        flowJob.cancel()
        flowJob = SupervisorJob(lifetimeScope.coroutineContext[Job])
        scope = CoroutineScope(lifetimeScope.coroutineContext + flowJob)
        processingApkSelection = false
    }

    val selection = ExpertModeSelection(lifetimeScope, ::persistSourceVisibility, knownPatchFailures.records)
    private suspend fun persistSourceVisibility(packageName: String, uid: Int, globally: Boolean, hidden: Boolean) {
        if (globally) prefs.edit {
            prefs.patchPanelGlobalHiddenSources.value = if (hidden) prefs.patchPanelGlobalHiddenSources.value + uid.toString()
                else prefs.patchPanelGlobalHiddenSources.value - uid.toString()
        } else {
            prefs.edit {
                val key = "$packageName|$uid"
                prefs.patchPanelAppHiddenSources.value = if (hidden) prefs.patchPanelAppHiddenSources.value + key
                    else prefs.patchPanelAppHiddenSources.value - key
            }
            if (!hidden) sourceMuteRepository.unmute(packageName, uid)
        }
    }
    private val bundleAppMetadataFlow get() = patchBundleRepository.appMetadata
    private val tag = "Morphe PatchFlow"
    private val contentResolver get() = app.contentResolver
    private fun isOnMeteredWithUpdatesDisabled() = !prefs.autoUpdates.getBlocking().downloadsOnMobileData && networkInfo.isMetered()
    var showExpertModeDialog by mutableStateOf(false)

    private var pickedApk by mutableStateOf<ApkLoadResult.Success?>(null)

    /** Icon read from the file picked for expert mode, for an app no source has one for yet. */
    val expertModeAppIcon: Drawable?
        get() = pickedApk
            ?.takeIf { it.app.file == (selection.app as? SelectedApp.Local)?.file }
            ?.icon
    var showApkAvailabilityDialog by mutableStateOf(false)
    var showDownloadInstructionsDialog by mutableStateOf(false)
    var showFilePickerPromptDialog by mutableStateOf(false)
    var showInstalledAppPickerDialog by mutableStateOf(false)
    var loadingInstalledApps by mutableStateOf(false)
    var installedAppsForPicker by mutableStateOf<List<InstalledAppPickerItem>>(emptyList())
    // True while APK loading/processing runs in the background
    var processingApkSelection by mutableStateOf(false)

    // Error/warning dialogs
    var showUnsupportedVersionDialog by mutableStateOf<UnsupportedVersionDialogState?>(null)
    var showExperimentalVersionDialog by mutableStateOf<UnsupportedVersionDialogState?>(null)
    var showWrongPackageDialog by mutableStateOf<WrongPackageDialogState?>(null)
    var showSplitApkWarningDialog by mutableStateOf(false)
    var showInvalidSignatureDialog by mutableStateOf<InvalidSignatureDialogState?>(null)
    var showNoCompatibleVersionsDialog by mutableStateOf<String?>(null) // packageName

    // Pending data during APK selection
    var pendingPackageName by mutableStateOf<String?>(null)
    /**
     * The tracked install the pending flow rebuilds, or null when it produces a separate one.
     * Patching an app that is already installed keeps that install, so a run has to say which
     * of an app's installs it is aimed at before it can replace anything.
     */
    var pendingRepatchPackageName by mutableStateOf<String?>(null)
        private set
    var pendingAppName by mutableStateOf<String?>(null)
    var pendingRecommendedVersion by mutableStateOf<AppTarget?>(null)
    var pendingCompatibleVersions by mutableStateOf<List<BundledAppTarget>>(emptyList())
    // Version selected by the user in Dialog 1 for the APK search query. Defaults to pendingRecommendedVersion
    var pendingSelectedDownloadVersion by mutableStateOf<AppTarget?>(null)
    var showDownloadVersionDialog by mutableStateOf(false)
        private set
    private var pendingVersionChoiceConfirmed = false

    private var pendingVersionSourceUid by mutableStateOf<Int?>(null)
    private var pendingVersionSourceVersion: String? = null
    val selectedDownloadSourceUid: Int? get() = pendingVersionSourceUid ?: pendingSelectedBundleUid

    fun selectDownloadVersion(entry: BundledAppTarget) {
        if (entry !in pendingCompatibleVersions.offered() || !entry.installableOnDevice()) return
        pendingSelectedDownloadVersion = entry.target
        pendingVersionSourceUid = entry.bundleUid
        pendingVersionSourceVersion = entry.target.version
    }

    private fun chosenBundleUidFor(selected: SelectedApp): Int? = pendingSelectedBundleUid
        ?: pendingVersionSourceUid.takeIf { pendingVersionSourceVersion == selected.version }

    fun confirmDownloadVersion(target: AppTarget) {
        val packageName = pendingPackageName ?: return
        val offered = pendingCompatibleVersions.offered().filter { it.installableOnDevice() }
            .filter { pendingSelectedBundleUid == null || it.bundleUid == pendingSelectedBundleUid }
        if (offered.none { it.target == target }) return
        pendingSelectedDownloadVersion = target
        pendingVersionChoiceConfirmed = true
        showDownloadVersionDialog = false
        scope.launch { continueApkSelectionFlow(packageName) }
    }

    fun dismissDownloadVersionDialog() {
        showDownloadVersionDialog = false
        cleanupPendingData()
    }
    var pendingSelectedApp by mutableStateOf<SelectedApp?>(null)
    var resolvedDownloadUrl by mutableStateOf<String?>(null)
    private var downloadRedirectJob: Job? = null
    var pendingSavedApkInfo by mutableStateOf<SavedApkInfo?>(null)
    var pendingInstalledApkInfo by mutableStateOf<InstalledApkInfo?>(null)
    // The unpatched version the device has right now: [pendingInstalledApkInfo] is dropped for a
    // version the patches do not target, which is exactly when the version itself is worth showing
    var pendingInstalledAppVersion by mutableStateOf<String?>(null)
    // Whether a mount install would have the app itself to overlay, which a patched build under
    // the same package name is not. null = not yet loaded, true/false = loaded result
    var pendingStockAppInstalled by mutableStateOf<Boolean?>(null)

    // Bundle update snackbar state
    var showSimpleBundleSelectDialog by mutableStateOf(false)
    var simpleBundleSelectApp by mutableStateOf<SelectedApp?>(null)
    var simpleBundleSelectCandidates by mutableStateOf<List<Pair<PatchBundleInfo.Scoped, Set<String>>>>(emptyList())
    // Bundle pre-selected by the user before the APK selection flow (simple mode)
    var pendingSelectedBundleUid by mutableStateOf<Int?>(null)
        private set

    fun dismissSimpleBundleSelectDialog() {
        showSimpleBundleSelectDialog = false
        simpleBundleSelectApp = null
        simpleBundleSelectCandidates = emptyList()
        cleanupPendingData()
    }

    /**
     * Called when the user picks a bundle in [app.morphe.manager.ui.screen.home.SimpleBundleSelectDialog].
     * Instead of patching immediately, stores the chosen bundle uid and continues
     * to the APK selection flow so the correct recommended version is shown.
     */
    fun proceedWithSelectedBundle(bundleUid: Int, rememberChoice: Boolean = false) {
        val packageName = pendingPackageName ?: return
        val offeredUids = simpleBundleSelectCandidates.mapTo(mutableSetOf()) { (bundle, _) -> bundle.uid }
        showSimpleBundleSelectDialog = false
        simpleBundleSelectApp = null
        simpleBundleSelectCandidates = emptyList()

        pendingSelectedBundleUid = bundleUid

        // Settles the question for good rather than for this run: the sources turned down here
        // stop being offered for this app, so the dialog has nothing left to ask next time
        if (rememberChoice) {
            scope.launch(Dispatchers.IO) {
                sourceMuteRepository.keepOnly(packageName, bundleUid, offeredUids)
            }
        }

        // Update recommended version to the one the chosen bundle will be used at
        val bundleRecommended = compatibleVersions[packageName]
            .orEmpty()
            .filter { it.bundleUid == bundleUid }
            .recommended()
        if (bundleRecommended != null) {
            pendingRecommendedVersion = bundleRecommended
            pendingSelectedDownloadVersion = bundleRecommended
        }

        scope.launch {
            continueApkSelectionFlow(packageName)
        }
    }

    // Metered network dialog: shown when user tries to patch on mobile data with updates disabled
    var showMeteredPatchingDialog by mutableStateOf(false)
        private set

    // Low disk space warning dialog: shown when free storage is below the threshold before patching starts
    val lowDiskSpaceThresholdBytes = 1_000_000_000L // Minimum free storage required before patching
    var showLowDiskSpaceDialog by mutableStateOf(false)
        private set
    var lowDiskSpaceFreeBytes by mutableLongStateOf(0L)
        private set

    // Pending patching action captured when the guard dialog is shown
    private var pendingPatchAction: (suspend () -> Unit)? = null

    // Loading state for installed apps
    val compatibleVersionsFlow: StateFlow<Map<String, List<BundledAppTarget>>> =
        versionCatalog.compatibleVersions
            .stateIn(lifetimeScope, SharingStarted.Eagerly, emptyMap())

    val recommendedVersionsFlow: StateFlow<Map<String, AppTarget>> =
        versionCatalog.recommendedVersions
            .stateIn(lifetimeScope, SharingStarted.Eagerly, emptyMap())

    // Convenience accessors - read current value synchronously for non-reactive call sites
    val recommendedVersions: Map<String, AppTarget> get() = recommendedVersionsFlow.value
    val compatibleVersions: Map<String, List<BundledAppTarget>> get() = compatibleVersionsFlow.value

    /** Convenience accessor - reads expert mode preference without blocking. */
    private suspend fun isExpertMode() = prefs.useExpertMode.get()

    // Using mount install (set externally)
    var usingMountInstall: Boolean = false

    // Install target passed to patch availability resolvers
    val currentInstallerType: InstallerType
        get() = installerTypeFor(usingMountInstall)

    // Architecture of the APK the run starts from, read from it before patches are selected
    var currentApkArchitecture: ApkArchitecture = ApkArchitecture.UNIVERSAL
        private set

    // Controls the pre-patching mode selection dialog for root-capable devices.
    var showPrePatchInstallerDialog by mutableStateOf(false)

    // Stores the pending arguments while the pre-patching mode dialog is visible.
    private var pendingPatchApp: SelectedApp? = null
    private var pendingPatchAllowIncompatible: Boolean = false

    /**
     * Called when a root-capable device triggers patching. Instead of starting immediately,
     * opens the pre-patching mode dialog so the user can choose Root Mount vs Standard.
     */
    fun requestPrePatchInstallerSelection(
        selectedApp: SelectedApp,
        allowIncompatible: Boolean
    ) {
        pendingPatchApp = selectedApp
        pendingPatchAllowIncompatible = allowIncompatible
        showPrePatchInstallerDialog = true
    }

    /**
     * Called when the user selects a patch mode from the pre-patching dialog.
     * Sets [usingMountInstall] and starts patching with the correct patch configuration.
     */
    fun resolvePrePatchInstallerChoice(useMount: Boolean) {
        showPrePatchInstallerDialog = false
        usingMountInstall = useMount

        val selectedApp = pendingPatchApp ?: return
        val allowIncompatible = pendingPatchAllowIncompatible
        pendingPatchApp = null

        scope.launch {
            startPatchingWithApp(selectedApp, allowIncompatible)
        }
    }

    /**
     * Dismisses the pre-patching mode dialog without starting patching.
     */
    fun dismissPrePatchInstallerDialog() {
        showPrePatchInstallerDialog = false
        pendingPatchApp = null
    }

    /**
     * User chose to proceed with patching despite the split APK warning.
     * Resumes [processSelectedApp] with the split check skipped.
     */
    fun proceedWithSplitApk() {
        val app = pendingSelectedApp ?: return
        showSplitApkWarningDialog = false
        pendingSelectedApp = null
        scope.launch {
            processSelectedApp(app, skipSplitCheck = true)
        }
    }

    private fun clearPendingApp() {
        val app = pendingSelectedApp
        pendingSelectedApp = null
        if (app is SelectedApp.Local && app.temporary) {
            app.file.delete()
        }
    }

    /**
     * User dismissed the split APK warning without proceeding.
     * Cleans up the temporary file if needed.
     */
    fun dismissSplitApkWarning() {
        showSplitApkWarningDialog = false
        clearPendingApp()
    }

    /**
     * User dismissed the unsupported version dialog.
     * Discards the pending selection and cleans up the temporary file if needed.
     */
    fun dismissUnsupportedVersionDialog() {
        showUnsupportedVersionDialog = null
        clearPendingApp()
    }

    private fun proceedWithPendingApp(allowIncompatible: Boolean) {
        val app = pendingSelectedApp ?: return
        pendingSelectedApp = null
        scope.launch {
            if (installerManager.promptsBeforePatching()) {
                requestPrePatchInstallerSelection(app, allowIncompatible = allowIncompatible)
            } else {
                usingMountInstall = installerManager.mountsByDefault()
                startPatchingWithApp(app, allowIncompatible = allowIncompatible)
            }
        }
    }

    /**
     * User chose to proceed patching with an unsupported app version.
     * Starts patching with allowIncompatible=true so version-incompatible patches are included.
     */
    fun proceedWithUnsupportedVersion() {
        showUnsupportedVersionDialog = null
        proceedWithPendingApp(allowIncompatible = true)
    }

    /**
     * User dismissed the experimental version warning dialog.
     * Discards the pending selection and cleans up the temporary file if needed.
     */
    fun dismissExperimentalVersionDialog() {
        showExperimentalVersionDialog = null
        clearPendingApp()
    }

    /**
     * User acknowledged the experimental version warning and chose to proceed.
     * Starts patching with allowIncompatible=false - the version is supported,
     * just flagged as experimental in the patch bundle.
     */
    fun proceedWithExperimentalVersion() {
        showExperimentalVersionDialog = null
        val selected = pendingSelectedApp ?: return
        scope.launch {
            prefs.experimentalVersionsConfirmed.update(prefs.experimentalVersionsConfirmed.get() + selected.packageName)
            if (pendingSelectedApp === selected) proceedWithPendingApp(allowIncompatible = false)
        }
    }

    /**
     * User dismissed the wrong package dialog.
     */
    fun dismissWrongPackageDialog() {
        showWrongPackageDialog = null
    }

    /**
     * User dismissed the invalid signature dialog.
     * Discards the pending selection and cleans up the temporary file if needed.
     */
    fun dismissInvalidSignatureDialog() {
        showInvalidSignatureDialog = null
        clearPendingApp()
    }

    /**
     * User chose to proceed patching despite the signature mismatch warning.
     * Skips signature verification and resumes the patching flow.
     */
    fun proceedIgnoringSignature() {
        showInvalidSignatureDialog = null
        val app = pendingSelectedApp ?: return
        pendingSelectedApp = null
        scope.launch {
            processSelectedAppIgnoringSignature(app)
        }
    }

    // Callback for starting patch
    var onStartQuickPatch: ((Patcher.ViewModelParams) -> Unit)? = null
    fun guardPatching(action: suspend () -> Unit) {
        // A batch run owns the patcher worker for its whole duration, so starting a single
        // patch would replace the app the queue is currently working on
        if (batchPatchCoordinator.isRunning) {
            app.toast(app.getString(R.string.batch_patch_in_progress))
            return
        }

        // Check available storage first - low disk space is the most common cause of
        // cryptic "file not found" errors and corrupt output APKs during patching.
        val freeBytes = StatFs(app.filesDir.absolutePath).availableBytes
        if (freeBytes < lowDiskSpaceThresholdBytes) {
            pendingPatchAction = action
            lowDiskSpaceFreeBytes = freeBytes
            showLowDiskSpaceDialog = true
            return
        }
        if (isOnMeteredWithUpdatesDisabled()) {
            pendingPatchAction = action
            showMeteredPatchingDialog = true
        } else {
            scope.launch { action() }
        }
    }

    /**
     * User chose to update patches first, then automatically continue patching.
     */
    fun refreshBundlesAndContinuePatching() {
        showMeteredPatchingDialog = false
        val action = pendingPatchAction ?: return
        pendingPatchAction = null
        scope.launch {
            // User explicitly requested update - bypass metered check and wait for completion
            patchBundleRepository.updateCheckAndAwait(allowUnsafeNetwork = true)
            action()
        }
    }

    /**
     * User chose to patch with the currently cached patches despite being on metered network.
     */
    fun dismissMeteredPatchingDialogAndProceed() {
        showMeteredPatchingDialog = false
        val action = pendingPatchAction ?: return
        pendingPatchAction = null
        scope.launch { action() }
    }

    /**
     * User canceled patching from the metered network dialog.
     */
    fun dismissMeteredPatchingDialog() {
        showMeteredPatchingDialog = false
        pendingPatchAction = null
    }

    /**
     * User chose to proceed with patching despite low disk space.
     * Continues to the metered network check if applicable, then launches the action.
     */
    fun dismissLowDiskSpaceDialogAndProceed() {
        showLowDiskSpaceDialog = false
        val action = pendingPatchAction ?: return
        pendingPatchAction = null
        if (isOnMeteredWithUpdatesDisabled()) {
            pendingPatchAction = action
            showMeteredPatchingDialog = true
        } else {
            scope.launch { action() }
        }
    }

    /**
     * User canceled patching from the low disk space dialog.
     */
    fun dismissLowDiskSpaceDialog() {
        showLowDiskSpaceDialog = false
        pendingPatchAction = null
    }

    /**
     * Mirrors the resolved update into [updatedManagerVersion] so the banner also appears when
     * a background check finds the release. A failed check is ignored rather than hiding a
     * banner the user is already looking at.
     */
    fun showPatchDialog(packageName: String, repatchedPackageName: String? = null) {
        preparePendingFlow(packageName, repatchedPackageName)

        // Guard: if there is a pending bundle update on metered data, show the outdated-patches
        // dialog before proceeding with the actual APK selection flow.
        guardPatching { showPatchDialogInternal(packageName) }
    }

    /** Starts the universal-patch picker through the same cancellable acquisition owner. */
    fun chooseAnyApp(appName: String) {
        cleanupExpertModeData()
        cleanupPendingData()
        pendingAppName = appName
        showFilePickerPromptDialog = true
    }

    fun appNameFor(packageName: String): String = bundleAppMetadataFlow.value[packageName]?.displayName
        ?: pickedApk?.takeIf { it.app.packageName == packageName }?.label
        ?: KnownApps.getAppName(packageName)

    /** Seeds the pending APK selection state for [packageName] from the current bundle data. */
    private fun preparePendingFlow(packageName: String, repatchedPackageName: String? = null) {
        cleanupExpertModeData()
        cleanupPendingData()
        downloadRedirectJob?.cancel()
        pendingPackageName = packageName
        pendingRepatchPackageName = repatchedPackageName
        pendingAppName = appNameFor(packageName)
        pendingRecommendedVersion = recommendedVersions[packageName]
        pendingCompatibleVersions = compatibleVersions[packageName] ?: emptyList()
        pendingSelectedDownloadVersion = pendingRecommendedVersion
        pendingVersionChoiceConfirmed = false
        showDownloadVersionDialog = false
        // Reset per-package cached state so a new flow always loads fresh data
        pendingSavedApkInfo = null
        pendingInstalledApkInfo = null
        pendingInstalledAppVersion = null
        pendingStockAppInstalled = null
    }

    private suspend fun showPatchDialogInternal(packageName: String) {
        val savedInfo = withContext(Dispatchers.IO) {
            localApkSources.saved(packageName)
        }
        pendingSavedApkInfo = savedInfo

        // Check if every declared version is incompatible with the current device SDK
        val versions = compatibleVersions[packageName].orEmpty().offered()
        val allIncompatible = versions.deviceIncompatible()
        if (allIncompatible) {
            showNoCompatibleVersionsDialog = packageName
            return
        }

        // In simple mode: if multiple bundles cover this package, ask the user to pick one
        // before showing the APK selection dialog so the correct recommended version is used
        if (!isExpertMode() && pendingSelectedBundleUid == null) {
            val allBundlesForCheck = withContext(Dispatchers.IO) {
                patchBundleRepository
                    .offeredBundleInfoFlow(packageName, version = null)
                    .first()
            }
            val candidates = allBundlesForCheck
                .filter { it.enabled }
                .map { bundle ->
                    val patchNames = bundle.patchSequence(allowIncompatible = true)
                        .filter { it.include }
                        .mapTo(mutableSetOf()) { it.name }
                    bundle to patchNames
                }
                .filter { (_, patches) -> patches.isNotEmpty() }
                .sortedWith(compareBy(patchSourceDisplayOrder(packageName, pendingRecommendedVersion?.version)) { it.first })

            if (candidates.size > 1) {
                simpleBundleSelectCandidates = candidates
                showSimpleBundleSelectDialog = true
                return
            }
        }

        continueApkSelectionFlow(packageName)
    }

    /**
     * Second half of the patch-dialog flow: runs after bundle selection (or immediately when
     * no bundle disambiguation is needed). Reuses the retained original or starts acquisition.
     */
    private suspend fun continueApkSelectionFlow(packageName: String) {
        val sourceTargets = pendingCompatibleVersions.offered()
            .filter { pendingSelectedBundleUid == null || it.bundleUid == pendingSelectedBundleUid }
        if (sourceTargets.deviceIncompatible()) {
            showNoCompatibleVersionsDialog = packageName
            return
        }
        val offeredTargets = sourceTargets.filter { it.installableOnDevice() }
        if (!pendingVersionChoiceConfirmed && offeredTargets.mapNotNull { it.target.version }.distinct().size > 1) {
            showDownloadVersionDialog = true
            return
        }
        // Load saved APK (Room + AppDataResolver) and installed app (PackageManager) in
        // parallel. The device is asked about in both modes, because the version list marks the
        // installed version even where the installed APK itself is not offered as a source
        val expertMode = isExpertMode()
        val wantedVersion = (pendingSelectedDownloadVersion ?: pendingRecommendedVersion)?.version
        coroutineScope {
            val savedJob = if (pendingSavedApkInfo == null) {
                async(Dispatchers.IO) { localApkSources.saved(packageName) }
            } else null
            val installedJob = async(Dispatchers.IO) { localApkSources.installed(packageName) }
            savedJob?.await()?.let { pendingSavedApkInfo = it }
            val installed = installedJob.await()
            // Simple mode offers the installed app too when it is the very version a download
            // would fetch: reading it from the device beats any download site
            applyInstalledAppSource(installed, offersApk = expertMode ||
                    installed.apk?.let { wantedVersion == null || it.version == wantedVersion } == true)
        }

        val requestedVersion = (pendingSelectedDownloadVersion ?: pendingRecommendedVersion)?.version
        val targets = pendingCompatibleVersions.offered().filter { it.installableOnDevice() }
            .filter { pendingSelectedBundleUid == null || it.bundleUid == pendingSelectedBundleUid }
        if ((pendingCompatibleVersions.isEmpty() || targets.isNotEmpty()) &&
            canReuseOriginal(pendingSavedApkInfo, requestedVersion, targets)) {
            handleSavedApkSelection()
        } else if (pendingInstalledApkInfo?.let {
                !it.patchStateUnknown && (requestedVersion == null || it.version == requestedVersion)
            } == true) {
            // The device already has it: offer that before going to the download sites
            showApkAvailabilityDialog = true
        } else {
            beginNativeApkDownload()
        }
    }

    /** Acquisition starts directly; the progress surface keeps manual source/version selection available. */
    fun beginNativeApkDownload() {
        showApkAvailabilityDialog = false
        showDownloadInstructionsDialog = true
        resolveDownloadRedirect()
    }

    fun chooseOriginalApkManually() {
        downloadRedirectJob?.cancel()
        showDownloadInstructionsDialog = false
        showApkAvailabilityDialog = true
    }

    /**
     * Stores the installed-app lookup, keeping the APK only when its version can be patched
     * so every caller offers the installed source under the same condition. [offersApk] is what
     * the flow itself allows: simple mode hides the button rather than the app behind it.
     */
    private fun applyInstalledAppSource(source: InstalledAppSource, offersApk: Boolean = true) {
        pendingStockAppInstalled = source.hasStockInstall
        pendingInstalledAppVersion = source.version
        pendingInstalledApkInfo = source.apk
            .takeIf { offersApk }
            .patchableBy(pendingCompatibleVersions)
    }

    /**
     * Handle selection of the currently installed APK from the APK availability dialog.
     * For single APKs: copies to a temp file. For split APKs: packs into a temp .apks archive.
     * The source is NOT saved to the original-APK repository.
     */
    fun handleInstalledApkSelection() {
        val installedInfo = pendingInstalledApkInfo
        val packageName = pendingPackageName

        if (installedInfo == null || packageName == null) {
            cleanupPendingData()
            return
        }

        scope.launch {
            showApkAvailabilityDialog = false
            prepareInstalledSelection(packageName, installedInfo) { selected ->
                processSelectedAppIgnoringSignature(selected)
            }
        }
    }

    /**
     * Handles a helper result where the user installed the target app outside Morphe.
     *
     * Reached in simple mode too, where the installed source is otherwise hidden: the helper is
     * opt-in and the hand-off was confirmed, so the app store is the whole point of the request.
     */
    fun handleHelperInstalledAppSelection(packageName: String) {
        val pending = pendingPackageName
        // Rebuild before launching: resetting the flow cancels its previous lookups,
        // so doing this inside the new lookup would cancel that lookup itself.
        if (pending == null && packageName in compatibleVersions) preparePendingFlow(packageName)
        scope.launch {
            if (pending != null && pending != packageName) {
                stopHelperHandoff(pending, R.string.home_apk_helper_wrong_package)
                return@launch
            }
            if (pending == null && packageName !in compatibleVersions) {
                app.toast(app.getString(R.string.home_apk_helper_installed_unavailable))
                cleanupPendingData()
                return@launch
            }

            processingApkSelection = true
            val source = withContext(Dispatchers.IO) {
                localApkSources.installed(packageName)
            }
            applyInstalledAppSource(source)

            val installedInfo = pendingInstalledApkInfo
            if (installedInfo == null) {
                // An app store hands out the newest build, which is regularly one no bundle covers
                stopHelperHandoff(
                    packageName,
                    if (source.apk == null) {
                        R.string.home_apk_helper_installed_unavailable
                    } else {
                        R.string.home_apk_helper_installed_incompatible
                    }
                )
                return@launch
            }

            // Continuing here would swallow the warning the availability dialog carries when the
            // certificate could not be read, so that case goes back to the user
            if (installedInfo.patchStateUnknown) {
                stopHelperHandoff(packageName)
                return@launch
            }

            handleInstalledApkSelection()
        }
    }

    /**
     * Ends a helper hand-off at the APK availability dialog, so the remaining sources stay one tap
     * away instead of the user landing back on the home screen with nothing to act on.
     */
    private suspend fun stopHelperHandoff(packageName: String, @StringRes message: Int? = null) {
        processingApkSelection = false
        message?.let { app.toast(app.getString(it)) }

        if (pendingSavedApkInfo == null) {
            pendingSavedApkInfo = withContext(Dispatchers.IO) { localApkSources.saved(packageName) }
        }
        showApkAvailabilityDialog = true
    }

    /**
     * Loads all user-installed apps and opens the picker dialog.
     * Called from the "Other apps" file-picker prompt when the user taps "Use installed app".
     */
    fun loadInstalledAppsForPicker() {
        if (loadingInstalledApps) return
        scope.launch {
            installedAppsForPicker = emptyList()
            loadingInstalledApps = true
            showFilePickerPromptDialog = false
            showInstalledAppPickerDialog = true
            try {
                val items = withContext(Dispatchers.IO) {
                    try {
                        val candidates = pm.getInstalledPackages()
                            .mapNotNull { pkgInfo ->
                                if (pkgInfo.packageName == app.packageName) return@mapNotNull null
                                val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                                val sourceDir = appInfo.sourceDir ?: return@mapNotNull null
                                if (!File(sourceDir).exists()) return@mapNotNull null
                                val version = pkgInfo.versionName?.takeUnless { it.isBlank() }
                                    ?: return@mapNotNull null
                                val label = with(pm) { pkgInfo.label() }
                                val splitPaths = appInfo.splitSourceDirs
                                    ?.filter { File(it).exists() }
                                    .orEmpty()
                                InstalledAppPickerItem(
                                    packageName = pkgInfo.packageName,
                                    label = label,
                                    packageInfo = pkgInfo,
                                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                    info = InstalledApkInfo(
                                        version = version,
                                        versionCode = pm.getVersionCode(pkgInfo),
                                        apkPath = sourceDir,
                                        splitPaths = splitPaths
                                    )
                                )
                            }
                        // A patched build is no app to patch, it is the app's own install
                        val patched = localApkSources.patchedInstalls(candidates.map { it.packageInfo })
                        candidates
                            .filterNot { it.packageName in patched }
                            .sortedBy { it.label.lowercase() }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to load installed apps for picker", e)
                        emptyList()
                    }
                }
                installedAppsForPicker = items
            } finally {
                loadingInstalledApps = false
            }
        }
    }

    /**
     * Handles selection of an app from the universal-patch installed-app picker.
     * Extracts the APK (or packs splits into an archive) and sends it through the patch flow.
     */
    fun handleInstalledAppPickerSelection(item: InstalledAppPickerItem) {
        showInstalledAppPickerDialog = false
        pendingAppName = item.label
        scope.launch {
            prepareInstalledSelection(item.packageName, item.info) { selected ->
                processSelectedApp(selected, skipSplitCheck = true)
            }
        }
    }

    private suspend fun prepareInstalledSelection(packageName: String, info: InstalledApkInfo,
        action: suspend (SelectedApp.Local) -> Unit) {
        processingApkSelection = true
        try {
            withInstalledInput(packageName, info, action)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(tag, "Failed to prepare installed APK", error)
            app.toast(app.getString(R.string.home_invalid_apk_io_error))
            cleanupPendingData()
        } finally {
            if (currentCoroutineContext().isActive) processingApkSelection = false
        }
    }

    /** One acquisition owns one copy, even when two flows target the same installed app. */
    internal suspend fun withInstalledInput(packageName: String, info: InstalledApkInfo,
        action: suspend (SelectedApp.Local) -> Unit) {
        var owned: File? = null
        var admitted = false
        try {
            val selected = withContext(Dispatchers.IO) {
                val extension = if (info.isSplit) "apks" else "apk"
                val file = filesystem.uiTempDir.resolve("installed-${java.util.UUID.randomUUID()}.$extension")
                owned = file // Bind ownership before any partial bytes or cancellable delivery.
                if (info.isSplit) SplitApkPreparer.writeApksArchive(info.apkPath, info.splitPaths, file)
                else File(info.apkPath).copyTo(file)
                ensureActive()
                SelectedApp.Local(packageName, info.version, info.versionCode, file,
                    temporary = true, fromInstalledDevice = true)
            }
            action(selected)
            admitted = true
        } finally {
            if (!admitted || !currentCoroutineContext().isActive) {
                withContext(NonCancellable + Dispatchers.IO) { owned?.delete() }
            }
        }
    }

    /**
     * Called when an APK is shared to Morphe via the system share sheet.
     * Shows a toast and returns early if expert mode is disabled.
     * Otherwise, waits until [installedAppsLoading] is false before triggering [handleApkSelection].
     */
    fun handleExternalApkUri(uri: Uri) {
        cleanupExpertModeData()
        cleanupPendingData()
        scope.launch {
            if (!isExpertMode()) {
                app.toast(app.getString(R.string.home_external_apk_expert_mode_required))
                return@launch
            }
            // Readiness is an observable transition. A closed/replaced flow cancels this wait.
            snapshotFlow { installedAppsReady() }.first { it }
            handleApkSelection(uri)
        }
    }

    /**
     * Handle APK file selection.
     */
    fun handleApkSelection(uri: Uri?) {
        if (uri == null) {
            cleanupPendingData()
            return
        }

        scope.launch {
            processingApkSelection = true
            var imported: SelectedApp.Local? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    try { loadLocalApk(app, uri).also { result ->
                        if (result is ApkLoadResult.Success) imported = result.app
                    } }
                    finally { InstallerFileProvider.discardNativeOriginal(app, uri) }
                }

                when (result) {
                    is ApkLoadResult.Success -> {
                        imported = result.app
                        pickedApk = result
                        processSelectedApp(result.app)
                    }
                    is ApkLoadResult.Unreadable -> app.toast(app.getString(R.string.home_invalid_apk_unreadable))
                    is ApkLoadResult.NotAnApk -> app.toast(app.getString(R.string.home_invalid_apk_not_an_apk))
                    is ApkLoadResult.AlreadyPatched -> app.toast(app.getString(R.string.home_invalid_apk_already_patched))
                    is ApkLoadResult.IoError -> app.toast(app.getString(R.string.home_invalid_apk_io_error))
                }
            } finally {
                if (!isActive) withContext(NonCancellable + Dispatchers.IO) { imported?.file?.delete() }
                else processingApkSelection = false
            }
        }
    }

    /**
     * Handle selection of saved APK from APK availability dialog.
     */
    fun handleSavedApkSelection() {
        val savedInfo = pendingSavedApkInfo
        val packageName = pendingPackageName

        if (savedInfo == null || packageName == null) {
            app.toast(app.getString(R.string.home_app_info_repatch_no_original_apk))
            cleanupPendingData()
            return
        }

        scope.launch {
            showApkAvailabilityDialog = false
            processingApkSelection = true
            try {
                // Create SelectedApp from saved APK file
                val selectedApp = withContext(Dispatchers.IO) {
                    try {
                        val file = File(savedInfo.filePath)
                        if (!file.exists()) {
                            app.toast(app.getString(R.string.home_app_info_repatch_no_original_apk))
                            return@withContext null
                        }

                        // Mark as used
                        originalApkRepository.markUsed(packageName)

                        SelectedApp.Local(
                            packageName = packageName,
                            version = savedInfo.version,
                            versionCode = savedInfo.versionCode,
                            file = file,
                            temporary = false // Don't delete saved APK files
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to load saved APK", e)
                        null
                    }
                }

                if (selectedApp != null) {
                    // Saved file may be signed with our keystore - skip signature check.
                    // Version/versionCode check still runs via processSelectedApp.
                    processSelectedApp(selectedApp, skipSplitCheck = true)
                } else {
                    cleanupPendingData()
                }
            } finally {
                if (isActive) processingApkSelection = false
            }
        }
    }

    /**
     * Process selected APK file.
     *
     * This function only answers: "do any patches EXIST for this APK?"
     * The include/selection logic is handled in [startPatchingWithApp].
     */
    private suspend fun processSelectedApp(
        selectedApp: SelectedApp,
        skipSplitCheck: Boolean = false
    ) {
        // Validate package name if expected (known-app flow sets pendingPackageName)
        if (pendingPackageName != null && selectedApp.packageName != pendingPackageName) {
            showWrongPackageDialog = WrongPackageDialogState(
                expectedPackage = pendingPackageName!!,
                actualPackage = selectedApp.packageName
            )
            if (selectedApp is SelectedApp.Local && selectedApp.temporary) {
                selectedApp.file.delete()
            }
            cleanupPendingData()
            return
        }

        // Warn when the selected file is a split APK while the bundle requires a full APK.
        // This must happen BEFORE signature verification - split archives (.apkm/.apks/.xapk)
        // are not valid APKs so PackageManager cannot read their signature, which would cause
        // a false "invalid signature" dialog instead of the correct "split APK" warning.
        if (selectedApp is SelectedApp.Local && !skipSplitCheck) {
            val requiredApkFileType = bundleAppMetadataFlow.value[selectedApp.packageName]?.apkFileType

            val isSplitFile = SplitApkPreparer.isSplitArchive(selectedApp.file)

            if (isSplitFile && requiredApkFileType?.isApk == true && requiredApkFileType.isRequired) {
                pendingSelectedApp = selectedApp
                showSplitApkWarningDialog = true
                cleanupPendingData(keepSelectedApp = true, keepBundleUid = true)
                return
            }

            // Verify APK signature against the expected signatures declared in the patch bundle.
            // GET_SIGNING_CERTIFICATES (API 28+) is required for reliable archive signature reads.
            // On Android 8–10 the legacy GET_SIGNATURES path cannot read signatures from
            // archive files correctly, so we skip verification there to avoid false-blocking users.
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
                val expectedSignatures = bundleAppMetadataFlow.value[selectedApp.packageName]?.signatures
                if (!expectedSignatures.isNullOrEmpty()) {
                    val signatureMatch = withContext(Dispatchers.IO) {
                        SplitApkInspector.withRepresentativeApk(
                            source = selectedApp.file,
                            workspace = filesystem.uiTempDir
                        ) { apk ->
                            pm.getApkFileSignatureHashes(apk).any { it in expectedSignatures }
                        }
                    }
                    if (!signatureMatch) {
                        pendingSelectedApp = selectedApp
                        showInvalidSignatureDialog = InvalidSignatureDialogState(
                            packageName = selectedApp.packageName,
                            appName = pendingAppName ?: appNameFor(selectedApp.packageName)
                        )
                        cleanupPendingData(keepSelectedApp = true, keepBundleUid = true)
                        return
                    }
                }
            }
        }

        val allowIncompatible = prefs.disablePatchVersionCompatCheck.getBlocking()

        // Get scoped bundles for this APK (package + version).
        // Scoped.patches contains every patch that is compatible with this packageName
        // (including universal patches where compatiblePackages == null).
        // Scoped.compatible = version matches, Scoped.incompatible = package matches but wrong version,
        // Scoped.universal = compatiblePackages == null (applies to any package/version).
        val bundles = withContext(Dispatchers.IO) {
            patchBundleRepository.resolvingBundleInfo(selectedApp.packageName, selectedApp.version,
                selectedApp.versionCode, chosenBundleUidFor(selectedApp))
        }

        val enabledBundles = bundles.filter { it.enabled }

        // Categorize what exists across all enabled bundles for this APK:
        val hasCompatible   = enabledBundles.any { it.compatible.isNotEmpty() }   // right pkg + right version
        val hasIncompatible = enabledBundles.any { it.incompatible.isNotEmpty() } // right pkg, wrong version
        val hasUniversal    = enabledBundles.any { it.universal.isNotEmpty() }    // no pkg restriction
        val hasAnything     = hasCompatible || hasIncompatible || hasUniversal

        if (!hasAnything) {
            // Truly no patches exist for this package in any enabled bundle
            app.toast(app.getString(R.string.home_no_patches_available))
            if (selectedApp is SelectedApp.Local && selectedApp.temporary) {
                selectedApp.file.delete()
            }
            cleanupPendingData()
            return
        }

        // Show the unsupported-version warning when:
        //   - version-specific patches exist for this package (incompatible list is non-empty)
        //   - BUT none of them match this APK version (compatible list is empty)
        //   - AND the user has NOT disabled the version compat check
        // Universal patches do not suppress this warning - the user should still be informed
        // that the APK version is not officially supported.
        // Note: experimental versions are compatible (they pass the version check) but show an
        // additional "Experimental" badge in the warning dialog.
        val support = VersionSupport(enabledBundles)
        val versionMismatch = support.isMismatch
        // Experimental check is independent - a version can be experimental AND compatible
        val isVersionExperimental = support.isExperimental

        // Check if the user has enabled experimental-version mode for this package's bundle
        val experimentalEnabledUids = prefs.bundleExperimentalVersionsEnabled.getBlocking()
        val isExperimentalModeEnabled = support.experimentalUids.any { uid ->
            uid.toString() in experimentalEnabledUids
        }

        if (versionMismatch && !allowIncompatible) {
            pendingSelectedApp = selectedApp
            showUnsupportedVersionDialog = unsupportedVersionState(selectedApp, isVersionExperimental)
            cleanupPendingData(keepSelectedApp = true, keepBundleUid = true)
            return
        }

        // If the version is experimental, show the appropriate warning:
        // - Experimental mode ON → ExperimentalVersionWarningDialog
        // - Experimental mode OFF → UnsupportedVersionWarningDialog
        if (isVersionExperimental && !allowIncompatible &&
            !(isExperimentalModeEnabled && selectedApp.packageName in prefs.experimentalVersionsConfirmed.get())) {
            pendingSelectedApp = selectedApp
            val state = unsupportedVersionState(selectedApp, isExperimental = true)
            if (isExperimentalModeEnabled) {
                showExperimentalVersionDialog = state
            } else {
                showUnsupportedVersionDialog = state
            }
            cleanupPendingData(keepSelectedApp = true, keepBundleUid = true)
            return
        }

        // Patches exist and are applicable → proceed.
        // For root-capable devices, we must know the patch mode BEFORE patching
        // because it is the install target patches declare their availability against.
        // Show the pre-patching mode dialog so the user can choose.
        // For non-root devices, just proceed - installer selection happens after patching.
        processSelectedAppIgnoringSignature(selectedApp)
    }

    /**
     * The way out of a version the sources do not stand behind: what the user picked, and the
     * versions they can go for instead. Shared by both warnings, which differ only in tone.
     */
    private fun unsupportedVersionState(
        selectedApp: SelectedApp,
        isExperimental: Boolean
    ): UnsupportedVersionDialogState {
        val chosen = chosenBundleUidFor(selectedApp)
        val offered = compatibleVersions[selectedApp.packageName].orEmpty().offered()
            .let { all -> all.filter { it.bundleUid == chosen }.ifEmpty { all } }

        return UnsupportedVersionDialogState(
            packageName = selectedApp.packageName,
            version = selectedApp.version ?: "unknown",
            versionCode = selectedApp.versionCode,
            recommendedVersion = offered.recommended(),
            compatibleVersionNames = offered.mapNotNull { it.target.version },
            compatibleVersionDescriptions = offered.mapNotNull { b ->
                val v = b.target.version ?: return@mapNotNull null
                val d = b.target.description ?: return@mapNotNull null
                v to d
            }.toMap(),
            compatibleVersionCodes = offered.mapNotNull { b ->
                val v = b.target.version ?: return@mapNotNull null
                val codes = b.buildCodes ?: return@mapNotNull null
                v to codes
            }.toMap(),
            isExperimental = isExperimental
        )
    }

    /**
     * Skips all preliminary checks (signature, version, bundle) and routes directly to patching.
     * Used when the user confirms proceeding despite a signature mismatch, or when patching
     * from the installed app where checks are not applicable.
     */
    suspend fun processSelectedAppIgnoringSignature(selectedApp: SelectedApp) {
        val allowIncompatible = prefs.disablePatchVersionCompatCheck.getBlocking()
        if (installerManager.promptsBeforePatching()) {
            requestPrePatchInstallerSelection(selectedApp, allowIncompatible)
        } else {
            usingMountInstall = installerManager.mountsByDefault()
            startPatchingWithApp(selectedApp, allowIncompatible)
        }
    }

    /**
     * Start patching flow.
     */
    suspend fun startPatchingWithApp(
        selectedApp: SelectedApp,
        allowIncompatible: Boolean,
        forceExpertMode: Boolean = false
    ) {
        // Read before the first selection is resolved, so every rule below and every later edit
        // in the expert dialog is answered for the APK this run actually starts from
        pendingSelectedApp = selectedApp
        val failureContext = knownPatchFailures.prepare(selectedApp)
        if (pendingSelectedApp !== selectedApp) return
        currentApkArchitecture = ApkArchitectureResolver.resolve(selectedApp, pm)

        // Everything this app has patches in, the sources it is kept from included: the expert
        // dialog offers those back, so it has to be told what it is hiding
        val availableBundles = patchBundleRepository
            .scopedBundleInfoFlow(selectedApp.packageName, selectedApp.version, selectedApp.versionCode)
            .first()

        if (availableBundles.isEmpty()) {
            app.toast(app.getString(R.string.home_no_patches_available))
            cleanupPendingData()
            return
        }

        val mutedSources = withContext(Dispatchers.IO) {
            sourceMuteRepository.getMutedFor(selectedApp.packageName) +
                panelHiddenFor(selectedApp.packageName, prefs.patchPanelAppHiddenSources.get())
        }
        // Every rule below reads this rather than the full list, so a source the app is kept from
        // contributes no defaults, no new-patch badges and no tab
        val globalHidden = prefs.patchPanelGlobalHiddenSources.get().mapNotNull { it.toIntOrNull() }.toSet()
        val allBundles = availableBundles.filterNot { it.uid in mutedSources || it.uid in globalHidden }


        // Spans every source, hidden ones included: what the user saved for a hidden one has to
        // survive validation. Offering the source back would otherwise hand the run an empty
        // selection, and then write that emptiness over what was saved
        val bundlesMap = availableBundles.associate { it.uid to it.patches.associateBy { patch -> patch.name } }

        val configurationKey = configurationKeyFor(selectedApp.packageName)

        // Whatever selection is reached below, the patches' own availability for the install
        // target has the final say on what the run starts with. A hidden source sits it out and
        // is put back untouched: it takes no part in a run, so it does not get to decide whether
        // the run draws from one source or several
        fun PatchSelection.applyInstallerRules(): PatchSelection {
            val hidden = mutedSources + globalHidden
            val heldBack = filterKeys { it in hidden }
            return filterKeys { it !in hidden }
                .applyAvailability(currentInstallerType, currentApkArchitecture, bundlesMap) + heldBack
        }

        if (forceExpertMode || isExpertMode() || allBundles.any { bundle ->
                knownPatchFailures.records.value.any { it.uid == bundle.uid }
            }) {
            // Expert Mode: Load saved selections and options only for current bundles
            val currentBundleUids = availableBundles.map { it.uid }.toSet()

            // Load selections
            val savedSelections = withContext(Dispatchers.IO) {
                patchSelectionRepository.getAllSelectionsForPackage(configurationKey)
                    .filterKeys { it in currentBundleUids }
            }

            // Load options
            val savedOptions = withContext(Dispatchers.IO) {
                optionsRepository.getAllOptionsForPackage(configurationKey, bundlesMap)
                    .filterKeys { it in currentBundleUids }
            }

            // Use saved selections or create new ones
            val patches = if (savedSelections.isNotEmpty()) {
                // Count patches before validation
                val patchesBeforeValidation = savedSelections.values.sumOf { it.size }

                // Validate saved selections against available patches
                val validatedPatches = validatePatchSelection(savedSelections, bundlesMap)

                // Count patches after validation
                val patchesAfterValidation = validatedPatches.values.sumOf { it.size }

                // Show toast if patches were removed
                val removedCount = patchesBeforeValidation - patchesAfterValidation
                if (removedCount > 0) {
                    app.toast(app.resources.getQuantityString(
                        R.plurals.home_app_info_repatch_cleaned_invalid_data,
                        removedCount,
                        removedCount.toString()
                    ))
                }

                // The seen-patch snapshot is what tells a genuinely new patch from one the
                // user deselected, and the saved selection stands in for it on the first run,
                // before any snapshot exists. Read in one go rather than per bundle inside the
                // merge, which would cross to the IO dispatcher once per source
                val seenByBundle = withContext(Dispatchers.IO) {
                    allBundles.associate {
                        it.uid to patchSelectionRepository.getSeenPatches(configurationKey, it.uid)
                    }
                }

                // Runs after validation, so patches the sources dropped never sneak back in
                mergeNewlyAdded(
                    bundles = allBundles,
                    validated = validatedPatches,
                    known = { uid -> seenByBundle[uid] ?: savedSelections[uid] },
                    installerType = currentInstallerType,
                    apkArchitecture = currentApkArchitecture
                )
            } else {
                // No saved selections - use default for all current bundles
                allBundles.toPatchSelection(allowIncompatible) { _, patch ->
                    patch.defaultSelected(currentInstallerType, currentApkArchitecture)
                }
            }.applyInstallerRules().withoutKnownFailures(failureContext, knownPatchFailures.records.value)

            // Compute new patches map for the dialog to highlight.
            // Only populated when a previous selection exists - on first run there is nothing
            // to compare against so we keep it empty to avoid false "New" badges
            // A patch is genuinely "new" if it was absent from the seen-patches snapshot
            // saved at the end of the previous patching session. Comparing against savedSelections
            // (which contains only *selected* patches) would incorrectly flag deselected patches
            // as new on every subsequent open.
            val newPatchesMap: Map<Int, Set<String>> = if (savedSelections.isNotEmpty()) {
                buildMap {
                    allBundles.forEach { bundle ->
                        val seenForBundle = withContext(Dispatchers.IO) {
                            patchSelectionRepository.getSeenPatches(configurationKey, bundle.uid)
                        }
                        // No snapshot yet → first time opening expert mode for this package,
                        // nothing to flag as new.
                        val seen = seenForBundle ?: return@forEach
                        val currentPatchNames = bundle.patches.map { it.name }.toSet()
                        val newForBundle = currentPatchNames - seen
                        if (newForBundle.isNotEmpty()) put(bundle.uid, newForBundle)
                    }
                }
            } else {
                emptyMap()
            }

            // Validate options
            val validatedOptions = validatePatchOptions(savedOptions, bundlesMap)

            // Save validated options if anything changed
            if (validatedOptions != savedOptions) {
                withContext(Dispatchers.IO) {
                    optionsRepository.saveOptions(configurationKey, validatedOptions)
                }
            }

            selection.configurationKey = configurationKey
            selection.open(selectedApp, availableBundles, mutedSources, globalHidden,
                patches, validatedOptions, newPatchesMap, currentInstallerType, currentApkArchitecture,
                allowIncompatible, failureContext)
            showExpertModeDialog = true
        } else {
            // Simple Mode: collect patches from all enabled bundles, then either use the sole result directly
            // or ask the user to pick one if multiple bundles have applicable patches.
            // A patch is applicable if:
            //   - compatiblePackages == null (universal), OR
            //   - compatiblePackages contains this packageName
            val bundleWithPatches = allBundles
                .filter { it.enabled }
                .map { bundle ->
                    val patchNames = bundle.patchSequence(allowIncompatible)
                        .filter { it.defaultSelected(currentInstallerType, currentApkArchitecture) }
                        .mapTo(mutableSetOf()) { it.name }
                    bundle to patchNames
                }
                .filter { (_, patches) -> patches.isNotEmpty() }
                .sortedWith(compareBy(patchSourceDisplayOrder(selectedApp.packageName, selectedApp.version)) { it.first })

            if (bundleWithPatches.isEmpty()) {
                // No patches have include=true (use=true in the bundle JSON).
                // This is the case for third-party bundles where all universal patches
                // ship with use=false and require explicit user configuration.
                // Fall through to expert mode so the user can select and configure patches.
                // Hidden sources are read here too, for the same reason the expert branch reads
                // them: the dialog can offer one back, and it has to have something to offer
                val currentBundleUids = availableBundles.map { it.uid }.toSet()

                val savedSelections = withContext(Dispatchers.IO) {
                    patchSelectionRepository.getAllSelectionsForPackage(configurationKey)
                        .filterKeys { it in currentBundleUids }
                }
                val savedOptions = withContext(Dispatchers.IO) {
                    optionsRepository.getAllOptionsForPackage(configurationKey, bundlesMap)
                        .filterKeys { it in currentBundleUids }
                }

                selection.configurationKey = configurationKey
            selection.open(selectedApp, availableBundles, mutedSources, globalHidden,
                    savedSelections.applyInstallerRules().withoutKnownFailures(failureContext, knownPatchFailures.records.value),
                    savedOptions, emptyMap(), currentInstallerType, currentApkArchitecture,
                    allowIncompatible, failureContext)
                showExpertModeDialog = true
                return
            }

            // If the user pre-selected a bundle via SimpleBundleSelectDialog, use it directly.
            // Use allowIncompatible=true unconditionally: the user explicitly picked this bundle
            // AND explicitly picked the APK version, so we must respect both choices regardless
            // of whether the version is in the bundle's supported list
            val preSelectedUid = pendingSelectedBundleUid
            if (preSelectedUid != null) {
                pendingSelectedBundleUid = null
                val bundle = allBundles.find { it.uid == preSelectedUid }
                if (bundle != null) {
                    val patchNames = bundle.patchSequence(allowIncompatible = true)
                        .filter { it.defaultSelected(currentInstallerType, currentApkArchitecture) }
                        .mapTo(mutableSetOf()) { it.name }
                    if (patchNames.isNotEmpty()) {
                        val patches = mapOf(bundle.uid to patchNames).applyInstallerRules()
                        val options = withContext(Dispatchers.IO) { patchOptionsPrefs.exportPatchOptions(selectedApp.packageName) }
                        proceedWithPatching(selectedApp, patches, options, allowIncompatible = true, selectedByHand = false)
                        return
                    }
                }
                // Pre-selected bundle has no patches at all - fall through
            }

            // Simple mode: if more than one bundle has applicable patches, ask the user which single bundle to use
            if (bundleWithPatches.size > 1) {
                simpleBundleSelectApp = selectedApp
                simpleBundleSelectCandidates = bundleWithPatches
                showSimpleBundleSelectDialog = true
                return
            }

            // Only one bundle has patches - use it directly (no prompt needed)
            val patches = bundleWithPatches
                .associate { (bundle, patches) -> bundle.uid to patches }
                .applyInstallerRules()

            val options = withContext(Dispatchers.IO) { patchOptionsPrefs.exportPatchOptions(selectedApp.packageName) }
            proceedWithPatching(selectedApp, patches, options, allowIncompatible, selectedByHand = false)
        }
    }

    /**
     * The package the pending run reads its saved patches and options from.
     *
     * Patches belong to the install they were applied to, so rebuilding one reads what that
     * install was built with. Anything else reads the app itself: a run producing a separate
     * install starts from what the app was last patched with, and so does an install that
     * predates configurations of their own.
     */
    private suspend fun configurationKeyFor(originalPackageName: String): String {
        val repatched = pendingRepatchPackageName
        val hasOwnConfiguration = repatched != null &&
                repatched != originalPackageName &&
                withContext(Dispatchers.IO) {
                    patchSelectionRepository.getAllSelectionsForPackage(repatched).isNotEmpty()
                }

        return configurationKey(
            originalPackageName = originalPackageName,
            repatchedPackageName = repatched,
            repatchedHasOwnConfiguration = hasOwnConfiguration
        )
    }

    /**
     * Save options to repository.
     */
    fun saveOptions(packageName: String, options: Options) {
        scope.launch(Dispatchers.IO) {
            optionsRepository.saveOptions(packageName , options)
        }
    }

    /**
     * Proceed with patching.
     */
    fun proceedWithPatching(
        selectedApp: SelectedApp,
        patches: PatchSelection,
        options: Options,
        allowIncompatible: Boolean,
        ageAcknowledged: Boolean = false,
        selectedByHand: Boolean = true
    ) {
        if (!ageAcknowledged) {
            patchReleaseAgeGate.request(
                current = { releaseAgeWarnings(selectedApp, patches) },
                proceed = { proceedWithPatching(selectedApp, patches, options, allowIncompatible, ageAcknowledged = true, selectedByHand = selectedByHand) },
                review = { warnings -> scope.launch {
                    startPatchingWithApp(selectedApp, allowIncompatible, forceExpertMode = true)
                    if (selection.app === selectedApp) {
                        selection.patches = patches.withoutAgeWarnings(warnings).let { with(selection) { it.applyAvailability() } }
                        selection.options = options
                    }
                } },
                home = ::leaveAgeWarningForHome)
            return
        }
        // Dismiss InstalledAppInfoDialog here, right before navigating to PatcherScreen.
        // This ensures there is never a gap between the info dialog closing and the next screen appearing
        dismissInstalledAppInfo()

        onStartQuickPatch?.invoke(
            Patcher.ViewModelParams(
                selectedApp = selectedApp,
                selectedPatches = patches,
                sourceScope = selection.bundles.mapTo(mutableSetOf()) { it.uid }.ifEmpty { patches.keys },
                options = options,
                selectedByHand = selectedByHand,
                // Handed over before the state below is cleared, since the run has no other way
                // to learn which install it was started for
                targetPackageName = pendingRepatchPackageName,
                allowIncompatible = allowIncompatible,
                useMount = usingMountInstall
            )
        )

        // Transfer temporary original ownership to the patcher before closing editor state.
        pendingSelectedApp = null
        // Clean only UI state
        pendingPackageName = null
        pendingRepatchPackageName = null
        pendingAppName = null
        pendingRecommendedVersion = null
        pendingCompatibleVersions = emptyList()
        pendingSelectedDownloadVersion = null
        pendingSelectedBundleUid = null
        resolvedDownloadUrl = null
        showDownloadInstructionsDialog = false
        showFilePickerPromptDialog = false
    }

    /**
     * Reopens the selection a failed [run] was started from, so the patch that failed it can be
     * dropped without picking the APK again. The selection itself was saved before the run.
     */
    fun reopenPatchSelection(run: Patcher.ViewModelParams) {
        // A failed run leaves its input in place, but storage cleanup may have taken it since
        if ((run.selectedApp as? SelectedApp.Local)?.file?.exists() == false) {
            app.toast(app.getString(R.string.home_invalid_apk_io_error))
            return
        }
        cleanupExpertModeData()
        cleanupPendingData()
        pendingRepatchPackageName = run.targetPackageName
        scope.launch {
            startPatchingWithApp(run.selectedApp, run.allowIncompatible, forceExpertMode = true)
        }
    }

    /**
     * All patches per bundle with their enabled state, sorted for display.
     * Recomputed whenever [selection.bundles] or [selection.patches] change.
     * Each bundle entry contains (PatchInfo, isEnabled) pairs sorted alphabetically -
     * the final per-section ordering (new patches first) is applied in the UI layer.
     */
    /**
     * Clean up expert mode data.
     */
    fun cleanupExpertModeData() {
        // Closing review is abandonment; after navigation pendingSelectedApp was already handed off.
        if (pendingSelectedApp === selection.app && pendingSelectedApp != null) cleanupPendingData()
        patchReleaseAgeGate.cancel()
        showExpertModeDialog = false
        selection.clear()
    }

    private suspend fun saveSeenPatchesForBundles(packageName: String) {
        selection.bundles.forEach { bundle ->
            patchSelectionRepository.saveSeenPatches(
                packageName = packageName,
                bundleUid = bundle.uid,
                patchNames = bundle.patches.map { it.name }.toSet()
            )
        }
    }

    /**
     * Called when the user confirms the ExpertModeDialog. Persists the final selection
     * and options, then navigates to the patcher.
     */
    val patchReleaseAgeGate = PatchReleaseAgeGate()

    private fun releaseAgeWarnings(selectedApp: SelectedApp, patches: PatchSelection): List<PatchReleaseAgeWarning> {
        val current = patchBundleRepository.bundleState.value as? PatchBundleRepository.BundleState.Ready ?: return emptyList()
        return selectedPatchReleaseAgeWarnings(selectedApp.packageName, selectedApp.version, selectedApp.versionCode,
            ApkPublications(app).get(selectedApp.packageName, selectedApp.version, selectedApp.versionCode)?.day,
            patches, current.info, current.sources.values.toList())
    }

    private fun leaveAgeWarningForHome() {
        cleanupExpertModeData()
        dismissSimpleBundleSelectDialog()
        dismissInstalledAppInfo()
        cleanupPendingData()
    }

    fun proceedExpertMode() {
        val selectedApp = selection.app ?: return
        patchReleaseAgeGate.request(
            current = { if (selection.app === selectedApp && showExpertModeDialog)
                releaseAgeWarnings(selectedApp, selection.runSelection) else emptyList() },
            proceed = { if (selection.app === selectedApp && showExpertModeDialog) proceedExpertModeConfirmed() },
            review = { warnings -> if (selection.app === selectedApp) {
                selection.patches = selection.patches.withoutAgeWarnings(warnings).let { with(selection) { it.applyAvailability() } }
            } },
            home = ::leaveAgeWarningForHome)
    }

    private fun proceedExpertModeConfirmed() {
        val selectedApp = selection.app ?: return
        // Only the bundles offered by the dialog are covered by this selection, so bundles that
        // were disabled at patch time keep whatever the user saved for them earlier.
        val bundleScope = selection.bundles.mapTo(mutableSetOf()) { it.uid }
        val finalPatches = selection.runSelection
        val finalOptions = selection.options
        // Strip UI-only empty strings (fields cleared via ✕) so the patcher engine
        // receives null / no key for those options and falls back to its own default,
        // rather than receiving a literal empty string.
        val patcherOptions = finalOptions.sanitizeForPatcher()

        scope.launch {
            selection.awaitVisibilityWrites()
            if (selection.app !== selectedApp || !showExpertModeDialog) return@launch
            // A path that leads nowhere fails the run, and by then this dialog is gone and the
            // option it belongs to is out of reach. So it is asked about while the selection is
            // still open and the value can be fixed or dropped on the spot
            val failures = withContext(Dispatchers.IO) {
                validateOptionPaths(patcherOptions.restrictTo(finalPatches), patchBundleRepository.allBundlesInfoFlow.first().values, selectedApp.packageName)
            }

            if (failures.isNotEmpty()) {
                selection.unreadablePaths = failures
                return@launch
            }

            showExpertModeDialog = false

            withContext(Dispatchers.IO) {
                // Saved before patching runs so a long selection survives a failed run. It lands
                // on the install being rebuilt, and follows it from there if patching renames it
                val configurationKey = configurationKeyFor(selectedApp.packageName)
                patchSelectionRepository.updateSelection(
                    packageName = configurationKey,
                    selection = finalPatches,
                    scope = bundleScope
                )
                optionsRepository.saveOptions(configurationKey, finalOptions)
                // Snapshot all bundle patch names so next open can detect genuinely new patches.
                saveSeenPatchesForBundles(configurationKey)
            }

            proceedWithPatching(selectedApp, finalPatches, patcherOptions, selection.allowIncompatible, ageAcknowledged = true)
            cleanupExpertModeData()
        }
    }

    /**
     * Option paths of the expert selection that cannot be read, raised over the dialog rather
     * than left for the run to fail on. Empty while there is nothing to answer.
     */
    /**
     * Drops the options behind the unreadable paths and goes on with the run, which then uses
     * the defaults the patches declare. The selection is saved without them as well.
     */
    fun clearExpertModeUnreadablePaths() {
        val failures = selection.unreadablePaths
        selection.unreadablePaths = emptyList()
        selection.options = selection.options.withoutFailingPaths(failures)

        proceedExpertMode()
    }

    /**
     * Resolve download redirect.
     */
    fun resolveDownloadRedirect() {
        val packageName = pendingPackageName ?: return
        // Use the version selected by the user in Dialog 1; fall back to recommended
        val version = (pendingSelectedDownloadVersion ?: pendingRecommendedVersion)?.version
        val target = pendingCompatibleVersions.filter {
            it.target.version == version && (selectedDownloadSourceUid == null || it.bundleUid == selectedDownloadSourceUid)
        }.distinctBy { it.abiBuildCodes to it.formFactor }.singleOrNull()

        // Marks the destination as unknown, which is what the dialog waits on before it can
        // say anything about the download or let the user leave for it
        downloadRedirectJob?.cancel()
        resolvedDownloadUrl = downloadUrlResolver.apiSearchUrl(packageName, version, target)

        downloadRedirectJob = scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val patchSources = patchBundleRepository.patchSourceHeaders(packageName, downloadSourceUids())
                downloadUrlResolver.resolve(packageName, version, patchSources, target)
            }
            ensureActive()
            if (pendingPackageName == packageName && showDownloadInstructionsDialog)
                resolvedDownloadUrl = resolved
        }
    }

    /**
     * Sources the requested version is patched from: the one picked, or else every source that
     * lists the version. Empty when none lists it, which leaves it to every source of the app.
     */
    private fun downloadSourceUids(): List<Int> {
        pendingSelectedBundleUid?.let { return listOf(it) }
        return pendingCompatibleVersions
            .filter { it.target.version == (pendingSelectedDownloadVersion ?: pendingRecommendedVersion)?.version }
            .map { it.bundleUid }
    }


    override val helperSignatureCheckAvailable: Boolean
        get() = pendingPackageName?.let { helperSignatureCheckAvailable(bundleAppMetadataFlow.value[it]) } == true

    /**
     * Build the request for an APK download helper, describing the original APK of the pending app.
     */
    override fun createApkDownloadHelperIntent(component: ComponentName): Intent? {
        val packageName = pendingPackageName ?: return null
        // Use the version selected by the user in Dialog 1; fall back to recommended
        val requestedVersion = (pendingSelectedDownloadVersion ?: pendingRecommendedVersion)?.version

        return createApkDownloadHelperRequest(
            component = component,
            callerPackage = app.packageName,
            packageName = packageName,
            appName = pendingAppName ?: appNameFor(packageName),
            versionName = requestedVersion,
            compatible = pendingCompatibleVersions,
            metadata = bundleAppMetadataFlow.value[packageName],
            stockInstallRequired = usingMountInstall && pendingStockAppInstalled != true,
            fallbackWebUrl = downloadUrlResolver.webSearchUrl(packageName, requestedVersion)
        )
    }

    /**
     * The download dialogs are closed before the APK is taken on, because processing it puts up
     * an overlay of its own and two of them on screen at once reads as a stuck flow.
     */
    override fun onHelperApkReceived(uri: Uri) {
        showDownloadInstructionsDialog = false
        showFilePickerPromptDialog = false
        handleApkSelection(uri)
    }

    override fun onHelperInstalledAppChosen(packageName: String) {
        showDownloadInstructionsDialog = false
        showFilePickerPromptDialog = false
        handleHelperInstalledAppSelection(packageName)
    }

    /**
     * Handle download instructions continue.
     */
    fun handleDownloadInstructionsContinue(handOff: (String) -> Boolean) {
        val urlToOpen = resolvedDownloadUrl!!

        if (handOff(urlToOpen)) {
            showDownloadInstructionsDialog = false
            showFilePickerPromptDialog = true
        } else {
            Log.w(tag, "Failed to open URL")
            app.toast(app.getString(R.string.sources_management_failed_to_open_url))
            showDownloadInstructionsDialog = false
            cleanupPendingData()
        }
    }

    /**
     * Clean up pending data.
     */
    fun cleanupPendingData(keepSelectedApp: Boolean = false, keepBundleUid: Boolean = false) {
        showDownloadVersionDialog = false
        pendingVersionChoiceConfirmed = false
        downloadRedirectJob?.cancel()
        pendingPackageName = null
        pendingAppName = null
        pendingRecommendedVersion = null
        pendingCompatibleVersions = emptyList()
        pendingSelectedDownloadVersion = null
        if (!keepBundleUid) {
            pendingSelectedBundleUid = null
            pendingVersionSourceUid = null
            pendingVersionSourceVersion = null
        }
        resolvedDownloadUrl = null
        pendingSavedApkInfo = null
        pendingInstalledApkInfo = null
        pendingInstalledAppVersion = null
        pendingStockAppInstalled = null
        if (!keepSelectedApp) {
            cancelPendingLookups()
            pendingSelectedApp?.let { app ->
                if (app is SelectedApp.Local && app.temporary) {
                    app.file.delete()
                }
            }
            pendingSelectedApp = null
            // Kept while the app is, because the dialogs that pause the flow resume into the
            // same run, and it must still know which install it was aimed at
            pendingRepatchPackageName = null
        }
        showApkAvailabilityDialog = false
        showDownloadInstructionsDialog = false
        showFilePickerPromptDialog = false
        showInstalledAppPickerDialog = false
    }

    /**
     * Clean up any pending temporary APK when the ViewModel is destroyed.
     * This handles the edge case where the user navigates away or the system destroys
     * the ViewModel while a temporary APK file is still held in pendingSelectedApp.
     */
    fun close() {
        cleanupExpertModeData()
        cleanupPendingData()
        flowJob.cancel()
    }

    /**
     * Load local APK and extract package info.
     * Supports both single APK and split APK archives (`apkm`, `apks`, `xapk`).
     *
     * The file is stored in [Filesystem.uiTempDir] (app_ui_ephemeral).
     * CacheDir can be cleared by Android at any time - even while the app is running and
     * patching is in progress - which would cause a FileNotFoundException mid-patch.
     * uiTempDir uses getDir() which is part of the app's private files and is never
     * cleared by the system automatically.
     */
    private suspend fun loadLocalApk(
        context: Context,
        uri: Uri
    ): ApkLoadResult = withContext(Dispatchers.IO) {
        var owned: File? = null
        var admitted = false
        try {
            // Copy file to uiTempDir with original extension detection
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) cursor.getString(nameIndex) else null
            } ?: "temp_${System.currentTimeMillis()}"

            val extension = fileName.substringAfterLast('.', "apk").lowercase()
            val tempFile = filesystem.uiTempDir.resolve("temp_apk_${java.util.UUID.randomUUID()}.$extension").also { owned = it }

            // openInputStream can return null when the provider is unavailable
            // e.g. Samsung External Storage restricted by Battery Optimization
            val bytesCopied = context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (bytesCopied == null || bytesCopied == 0L) {
                tempFile.delete()
                return@withContext ApkLoadResult.Unreadable
            }

            // A split archive is read through its base module, deleted after the call, so the icon is read in it
            val (packageInfo, icon, patched, label) = SplitApkInspector.withRepresentativeApk(
                source = tempFile,
                workspace = filesystem.uiTempDir
            ) { apk ->
                val info = pm.getPackageInfo(apk)
                ArchiveDetails(info, info?.let(appDataResolver::detachedArchiveIcon),
                    info != null && localApkSources.isPatchedApk(apk, info.packageName),
                    info?.applicationInfo?.loadLabel(app.packageManager)?.toString())
            }

            if (packageInfo == null) {
                Log.w(tag, "Picked file $fileName could not be parsed as an APK")
                tempFile.delete()
                return@withContext ApkLoadResult.NotAnApk
            }

            if (patched) {
                tempFile.delete()
                return@withContext ApkLoadResult.AlreadyPatched
            }

            ensureActive()
            admitted = true
            ApkLoadResult.Success(
                SelectedApp.Local(
                    packageName = packageInfo.packageName,
                    version = packageInfo.versionName ?: "unknown",
                    versionCode = pm.getVersionCode(packageInfo),
                    file = tempFile,
                    temporary = true
                ),
                icon, label
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to load APK", e)
            ApkLoadResult.IoError
        } finally {
            if (!admitted || !isActive) owned?.delete()
        }
    }
}

/** Result of attempting to load a local APK file. */
private data class ArchiveDetails(val info: android.content.pm.PackageInfo?, val icon: Drawable?,
    val patched: Boolean, val label: String?)

private sealed interface ApkLoadResult {
    /** File was read and parsed successfully, with the app's icon where it carries one. */
    data class Success(val app: SelectedApp.Local, val icon: Drawable?, val label: String?) : ApkLoadResult
    /** File could not be read - provider returned null stream or zero bytes. */
    data object Unreadable : ApkLoadResult
    /** File was read but is not a valid APK/split archive. */
    data object NotAnApk : ApkLoadResult
    /** File is a patched build, which is never patched again. */
    data object AlreadyPatched : ApkLoadResult
    /** An unexpected IO or system exception occurred while copying or parsing. */
    data object IoError : ApkLoadResult
}
