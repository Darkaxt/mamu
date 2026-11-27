/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/ui/viewmodel/PatcherViewModel.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Bundle
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.autoSaver
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.morphe.manager.BuildConfig
import app.morphe.manager.patcher.newPatchWorkspace
import app.morphe.manager.patcher.PatchInputUse
import app.morphe.manager.patcher.isDisposablePatchInput
import app.morphe.manager.patcher.clearPatchWorkspaceAfterWorker
import app.morphe.manager.R
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.domain.installer.InstallerManager
import app.morphe.manager.domain.manager.PatchOptionsPreferencesManager
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.*
import app.morphe.manager.domain.worker.WorkerRepository
import app.morphe.manager.patcher.patch.ApkArchitectureResolver
import app.morphe.manager.patcher.patch.PatchBundleInfo
import app.morphe.manager.patcher.patch.PatchLockState
import app.morphe.manager.patcher.patch.PatchSourceRef
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.patcher.worker.PatcherWorker
import app.morphe.manager.patcher.runtime.AppliedPatchRecipe
import app.morphe.manager.patcher.runtime.readAppliedPatchRecipe
import app.morphe.manager.patcher.patch.PatchOmission
import app.morphe.manager.patcher.patch.PatchKey
import kotlinx.serialization.json.Json
import app.morphe.manager.service.ForegroundWork
import app.morphe.manager.notification.OngoingContent
import app.morphe.manager.ui.model.*
import app.morphe.manager.ui.model.navigation.Patcher
import app.morphe.manager.ui.screen.patcher.PatcherErrorInfo
import app.morphe.manager.util.*
import app.morphe.manager.util.PatchSelectionUtils.restrictTo
import app.morphe.patcher.patch.ApkArchitecture
import app.morphe.patcher.patch.InstallerType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(SavedStateHandleSaveableApi::class)
class PatcherViewModel(
    private val input: Patcher.ViewModelParams
) : ViewModel(), KoinComponent, StepProgressProvider {
    private val app: Application by inject()
    private val foregroundWork: ForegroundWork by inject()
    private val fs: Filesystem by inject()
    private val pm: PM by inject()
    private val workerRepository: WorkerRepository by inject()
    private val patchBundleRepository: PatchBundleRepository by inject()
    private val patchSelectionRepository: PatchSelectionRepository by inject()
    private val patchOptionsRepository: PatchOptionsRepository by inject()
    private val installedAppRepository: InstalledAppRepository by inject()
    private val prefs: PreferencesManager by inject()
    private val patchOptionsPrefs: PatchOptionsPreferencesManager by inject()
    private val originalApkRepository: OriginalApkRepository by inject()
    private val installerManager: InstallerManager by inject()
    private val savedStateHandle: SavedStateHandle = get()

    private var savedPatchedApp by savedStateHandle.saveableVar { false }

    private val inputCleanupScope: app.morphe.manager.util.AppCoroutineScope by inject()
    private var inputHandedToEditor = false
    private var workerArgs: PatcherWorker.Args? = null
    private var originalRetentionJob: Job? = null
    private val installInputUses = mutableListOf<PatchInputUse>()
    private var inputCleanupStarted = false

    /** The installer acquires synchronously before launching any work that may outlive this screen. */
    fun retainInstallInputs(): () -> Unit = synchronized(installInputUses) {
        check(!inputCleanupStarted) { "The patch workspace has retired" }
        val use = PatchInputUse().also { it.begin(); installInputUses += it }
        use::finish
    }
    fun takeInputForReview(): Patcher.ViewModelParams {
        check(_patcherSucceeded.value == false && !isPatching) { "The patcher has not released its input" }
        inputHandedToEditor = true
        return input
    }

    private val saveOriginalApkMutex = Mutex()

    var exportMetadata by mutableStateOf<ApkExportData?>(null)
        private set

    /** The completed output, which may carry its own package, name and icon. */
    var patchedPackageInfo by mutableStateOf<PackageInfo?>(null)
        private set

    private suspend fun patchedApp(): PackageInfo? =
        patchedPackageInfo ?: withContext(Dispatchers.IO) { pm.getPackageInfo(outputFile) }
            ?.also { withContext(Dispatchers.Main) { patchedPackageInfo = it } }

    /** Formatted export file name derived from [exportMetadata]. */
    val exportFileName: String by derivedStateOf {
        val data = exportMetadata ?: ApkExportData(
            appName = packageName,
            packageName = packageName,
            appVersion = version ?: "unspecified"
        )
        ExportNameFormatter.format(data)
    }
    private var appliedRecipe by mutableStateOf<AppliedPatchRecipe?>(
        savedStateHandle.get<Bundle>(KEY_PROGRESS)?.getString(KEY_APPLIED_RECIPE)?.let {
            Json.decodeFromString(AppliedPatchRecipe.serializer(),it)
        })
    private var appliedSelection by mutableStateOf< PatchSelection>(appliedRecipe?.selection ?: input.selectedPatches.mapValues { it.value.toSet() })
    val skippedPatches: List<PatchOmission> get() = appliedRecipe?.omissions.orEmpty()
    private var appliedOptions: Options = input.options
    val patchedFromInstalledDevice: Boolean
        get() = (selectedApp as? SelectedApp.Local)?.fromInstalledDevice == true

    private var currentActivityRequest: Pair<CompletableDeferred<Boolean>, String>? by mutableStateOf(
        null
    )
    val activityPromptDialog by derivedStateOf { currentActivityRequest?.second }

    private var launchedActivity: CompletableDeferred<ActivityResult>? = null
    private val launchActivityChannel = Channel<Intent>()
    val launchActivityFlow = launchActivityChannel.receiveAsFlow()

    private val _autoInstallChannel = Channel<Unit>(Channel.CONFLATED)
    val autoInstallEvent: Flow<Unit> = _autoInstallChannel.receiveAsFlow()

    /** Whether this run installs on its own, set before the event so the screen shows it coming. */
    var autoInstallPending by mutableStateOf(false)
        private set

    var patchingCompletedAt: Long? = null
        private set

    var patchingCompletedInForeground: Boolean = false
        private set

    var showSuccessScreen: Boolean by mutableStateOf(false)
        private set

    fun showSuccess() {
        showSuccessScreen = true
    }

    fun hideSuccessScreen() { showSuccessScreen = false }

    var isPatching: Boolean by mutableStateOf(true)
        private set

    private val selectedApp = input.selectedApp
    val packageName = selectedApp.packageName
    val version = selectedApp.version

    /**
     * How the finished APK differs from the install this run was aimed at, or null when it lands
     * on that install after all. The name a patch builds under exists only once it has run, so
     * the output is the only place it can be read.
     */
    suspend fun renameWarning(): RenameWarning? = withContext(Dispatchers.IO) {
        val target = input.targetPackageName ?: packageName
        val result = patchedApp()?.packageName ?: return@withContext null
        if (result == target) return@withContext null

        // Patches rename an app for reasons of their own, and a build the user never asked to
        // clone is still the app's only install however it ended up named
        val bundles = scopedBundles()
        val clone = appliedRecipe?.let { recipe -> result != packageName && recipe.renamingPatches.any {
            it.key in recipe.selection[it.sourceUid].orEmpty()
        } } ?: producedClone(result, sanitizeSelection(appliedSelection, bundles), bundles)
        if (!clone) {
            return@withContext null
        }

        RenameWarning(
            targetPackageName = target,
            resultPackageName = result,
            // Asked of the device rather than the manager's records: what gets overwritten is
            // whatever holds the name, tracked here or not
            replacesExisting = pm.getPackageInfo(result) != null
        )
    }

    /** The sources this run drew from, scoped to the app it started from. */
    private suspend fun scopedBundles(): Map<Int, PatchBundleInfo.Scoped> =
        patchBundleRepository.scopedBundleInfoFlow(
            packageName,
            input.selectedApp.version,
            input.selectedApp.versionCode
        ).first().associateBy { it.uid }

    /**
     * Whether this run built a clone rather than the app's own install, judged the same way here
     * and where the result is recorded so the warning cannot contradict what gets stored.
     */
    private fun producedClone(
        resultPackageName: String,
        selection: PatchSelection,
        bundles: Map<Int, PatchBundleInfo.Scoped>
    ) = producesClone(
        originalPackageName = packageName,
        resultPackageName = resultPackageName,
        selection = selection,
        declaresPackageName = { bundleUid, patchName ->
            bundles[bundleUid]?.patches?.firstOrNull { it.name == patchName }?.declaresPackageName == true
        }
    )

    /** [producedClone] with the selection this run was built with. */
    private suspend fun producedClone(resultPackageName: String): Boolean {
        val bundles = scopedBundles()
        return producedClone(resultPackageName, sanitizeSelection(appliedSelection, bundles), bundles)
    }

    /**
     * Non-null when the saved selection names patches no enabled source offers anymore, which
     * the run is held on until the user says whether to go ahead without them.
     */
    data class MissingPatchWarningState(
        val patchNames: List<String>
    )
    var missingPatchWarning by mutableStateOf<MissingPatchWarningState?>(null)
        private set

    /** Set once the user has agreed to patch without them, so the same question is asked once. */
    private var missingPatchesAccepted = false

    /** Goes ahead with the patches that are still there, leaving out the ones that are gone. */
    fun continueWithoutMissingPatches() {
        missingPatchesAccepted = true
        missingPatchWarning = null

        viewModelScope.launch { runPreflightCheck() }
    }

    fun dismissMissingPatchWarning() {
        missingPatchWarning = null
    }

    var batteryOptimizationDialog by mutableStateOf(false)
        private set

    /** Non-null when one or more patch option paths cannot be read before patching starts. */
    var inaccessibleOptionPaths by mutableStateOf<List<PathValidationResult>?>(null)
        private set

    fun dismissInaccessibleOptionPathsError() {
        inaccessibleOptionPaths = null
    }

    /**
     * Drops the option values behind the failing paths and resumes the check, so a run whose
     * files are gone for good goes on with the defaults the patches declare.
     */
    fun clearInaccessibleOptionPaths() {
        val failures = inaccessibleOptionPaths.orEmpty()
        inaccessibleOptionPaths = null
        appliedOptions = appliedOptions.withoutFailingPaths(failures)

        viewModelScope.launch {
            // Options simple mode keeps in its settings would hand the same paths to its next run
            if (!input.selectedByHand) {
                failures.forEach { failure ->
                    patchOptionsPrefs.clearOptionValue(packageName, failure.patchName, failure.optionKey)
                }
            }

            runPreflightCheck()
        }
    }

    /**
     * Non-null when a bundle requires a newer morphe-patcher than the one bundled
     * in this version of Morphe.
     *
     * @param requiredVersion  The minimum patcher version declared in the bundle.
     * @param bundleName       The display name of the offending bundle.
     */
    data class IncompatiblePatcherVersionState(
        val requiredVersion: String,
        val bundleName: String,
    )
    var incompatiblePatcherVersion by mutableStateOf<IncompatiblePatcherVersionState?>(null)
        private set

    fun dismissIncompatiblePatcherVersion() {
        incompatiblePatcherVersion = null
    }

    /**
     * Called when the user acknowledges the storage permission warning and chooses
     * to proceed anyway (e.g. after granting MANAGE_EXTERNAL_STORAGE in settings
     * and returning to the app, or if they believe the path is accessible).
     * Re-validates paths on IO dispatcher before starting the worker - if the
     * user actually granted the permission, the paths will now be readable.
     */
    fun retryAfterPermission() {
        inaccessibleOptionPaths = null
        viewModelScope.launch {
            runPreflightCheck()
        }
    }

    /**
     * Called when the user dismisses the battery optimization pre-flight dialog.
     * Marks the preference so the dialog is never shown again and resumes the preflight check.
     */
    fun onBatteryOptimizationDialogResult() {
        viewModelScope.launch {
            prefs.batteryOptimizationRequested.update(true)
            batteryOptimizationDialog = false
            runPreflightCheck()
        }
    }

    private var apkArchitecture: ApkArchitecture? = null

    private suspend fun gatherScopedBundles(): Map<Int, PatchBundleInfo.Scoped> =
        patchBundleRepository.scopedBundleInfoFlow(
            packageName,
            input.selectedApp.version,
            input.selectedApp.versionCode
        ).first().associateBy { it.uid }

    /**
     * Patches the sources declare unavailable for [installerType], so the finished app can name
     * what it was built without instead of leaving the user to spot the missing patch.
     */
    suspend fun unavailablePatchNames(installerType: InstallerType): List<String> {
        // Kept once resolved, because the input a temporary APK was read from is gone by the time
        // the install target changes and the list is asked for again
        val architecture = apkArchitecture
            ?: ApkArchitectureResolver.resolve(selectedApp, pm).also { apkArchitecture = it }

        return gatherScopedBundles().values
            .asSequence()
            .flatMap { it.patches }
            .filter { it.lockState(installerType, architecture) == PatchLockState.LOCKED_OFF }
            .map { it.displayName }
            .distinct()
            .sorted()
            .toList()
    }

    suspend fun collectSelectedBundleMetadata(): List<PatchSourceRef> {
        appliedRecipe?.let { recipe -> return recipe.payload.bundles.filter { it.patches.isNotEmpty() }.map {
            PatchSourceRef(it.bundleName ?: it.bundleUid.toString(),it.bundleVersion,it.bundleUid)
        } }
        val globalBundles = patchBundleRepository.bundleInfoFlow.first()
        val scopedBundles = gatherScopedBundles()
        val sanitizedSelection = sanitizeSelection(appliedSelection, scopedBundles)
        val displayNames = patchBundleRepository.sources.first().associate { it.uid to it.displayTitle }

        return sanitizedSelection.keys.mapNotNull { uid ->
            val name = (displayNames[uid] ?: scopedBundles[uid]?.name ?: globalBundles[uid]?.name)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            PatchSourceRef(
                name = name,
                version = globalBundles[uid]?.version?.takeIf { it.isNotBlank() },
                uid = uid
            )
        }.distinctBy { it.uid }
    }

    // Parsing the patched APK stalls a frame, and export reaches here from the main thread
    private suspend fun buildExportMetadata(packageInfo: PackageInfo?): ApkExportData? = withContext(Dispatchers.IO) {
        val info = packageInfo ?: patchedApp() ?: return@withContext null
        pm.exportData(
            built = info,
            sources = collectSelectedBundleMetadata(),
            isClone = producedClone(info.packageName),
            fallbackVersion = version
        )
    }

    /**
     * Collects app and bundle metadata to populate [PatcherErrorInfo] in the error dialog.
     * Called as soon as patching fails so the dialog opens without an extra async wait, and
     * before the dialog opens on a failed install.
     */
    suspend fun buildErrorInfo(): PatcherErrorInfo {
        // Read from what the run started with, since a failed run leaves no output APK to name
        val label = runCatching {
            when (val selected = selectedApp) {
                is SelectedApp.Local -> pm.getPackageInfo(selected.file)
                else -> pm.getPackageInfo(packageName)
            }?.let { with(pm) { it.label() } }
        }.getOrNull()
        val bundles = collectSelectedBundleMetadata()
        return PatcherErrorInfo(
            appName = label ?: packageName,
            packageName = packageName,
            appVersion = version ?: "unspecified",
            patchCount = patchCount,
            failedPatch = patchRun.failedPatch,
            bundles = bundles,
            stripsNativeLibs = prefs.stripUnusedNativeLibs.get()
        )
    }

    private fun refreshExportMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            val metadata = buildExportMetadata(null)
            withContext(Dispatchers.Main) {
                exportMetadata = metadata
            }
        }
    }

    private suspend fun ensureExportMetadata() {
        if (exportMetadata != null) return
        val metadata = buildExportMetadata(null) ?: return
        withContext(Dispatchers.Main) {
            exportMetadata = metadata
        }
    }

    private val tempDir = savedStateHandle.saveable(key = "tempDir") {
        newPatchWorkspace(fs.uiTempDir)
    }

    private var _inputFile: File? = null
    var inputFile: File?
        get() = _inputFile
        set(value) { _inputFile = value }

    /**
     * True when [inputFile] is owned by this VM and safe to drop after install.
     */
    val inputFileIsDisposable: Boolean
        get() {
            val file = inputFile ?: return false
            return isDisposablePatchInput(file, tempDir, fs.originalApksDir,
                (selectedApp as? SelectedApp.Local)?.temporary == true)
        }

    val outputFile = tempDir.resolve("output.apk")

    val patchCount get() = appliedSelection.values.sumOf { it.size }

    private val restoredProgress: Bundle? = savedStateHandle[KEY_PROGRESS]

    /**
     * Step, log and progress state of this run. Shared with the patching screens through
     * [PatchProgressSource] so the batch queue can render the very same UI, and restored
     * from [SavedStateHandle] when the process was killed while the worker kept going.
     */
    val patchRun = PatchRunProgress(
        context = app,
        scope = viewModelScope,
        totalPatches = patchCount,
        splitStepActive = initialSplitRequirement(input.selectedApp),
        restoredSteps = restoredProgress?.let {
            BundleCompat.getParcelableArrayList(it, KEY_STEPS, Step::class.java)
        },
        restoredCompletedPatches = restoredProgress?.getInt(KEY_COMPLETED_PATCHES) ?: 0,
        restoredFailedPatch = restoredProgress?.getString(KEY_FAILED_PATCH)
    )

    val steps: List<Step> get() = patchRun.steps
    val progress: Float get() = patchRun.progress

    /** What the failed step reported, empty while the run has not failed or where it said nothing. */
    val failureMessage: String
        get() = steps.firstOrNull { it.state == State.FAILED }?.message.orEmpty()

    /**
     * The error as the error dialog spells it out. A failed step that says nothing falls back to
     * the patching log, so the user always has something to act on.
     */
    val failureDetails: String
        get() = failureMessage.ifBlank {
            patchRun.logs
                .joinToString("\n") { (level, message) -> "[$level] $message" }
        }
    val patchesProgress get() = patchRun.patchesProgress

    private val workManager = WorkManager.getInstance(app)
    private val _patcherSucceeded = MutableStateFlow<Boolean?>(null)
    val patcherSucceeded: StateFlow<Boolean?> = _patcherSucceeded.asStateFlow()
    private var observeWorkerJob: Job? = null
    private val handledFailureIds = mutableSetOf<UUID>()
    private var forceKeepLocalInput = false

    private var patcherWorkerId: ParcelUuid?
        get() = savedStateHandle["patcher_worker_id"]
        set(value) {
            if (value == null) {
                savedStateHandle.remove<ParcelUuid>("patcher_worker_id")
            } else {
                savedStateHandle["patcher_worker_id"] = value
            }
        }

    /** Patch sources collected during preflight, forwarded to the worker for logging. */
    private var patchSourcesForLog: List<PatchSourceRef> = emptyList()

    /** True when the current patching step has been running for over a minute. */
    val showLongStepWarning: StateFlow<Boolean> = patchRun.showLongStepWarning

    /** Notification and tour prompts raised after a successful install or export. */
    val postPatchPrompts = PostPatchPrompts(app, prefs, viewModelScope)

    init {
        restoreOutcome()

        // The pipeline and the outcome survive process death, so a run that continued in the
        // worker while the UI was gone comes back exactly where it was left
        savedStateHandle.setSavedStateProvider(KEY_PROGRESS) {
            Bundle().apply {
                putParcelableArrayList(KEY_STEPS, ArrayList(patchRun.steps))
                putInt(KEY_COMPLETED_PATCHES, patchRun.completedPatches)
                putString(KEY_FAILED_PATCH, patchRun.failedPatch)
                putBoolean(KEY_SUCCESS_SCREEN, showSuccessScreen)
                appliedRecipe?.let { putString(KEY_APPLIED_RECIPE,Json.encodeToString(AppliedPatchRecipe.serializer(),it)) }
                _patcherSucceeded.value?.let { putBoolean(KEY_SUCCEEDED, it) }
            }
        }

        val existingId = patcherWorkerId?.uuid
        if (existingId != null) {
            observeWorker(existingId)
        } else {
            viewModelScope.launch {
                // Resolve inputFile before preflight check to prevent race condition
                // where the worker could start before inputFile is set.
                if (inputFile == null && input.selectedApp is SelectedApp.Installed) {
                    withContext(Dispatchers.IO) {
                        val originalApk = originalApkRepository.get(packageName)
                        if (originalApk != null) {
                            val file = File(originalApk.filePath)
                            if (file.exists()) {
                                inputFile = file
                            }
                        }
                    }
                }
                runPreflightCheck()
            }
        }
        patchRun.startStallWatch()
    }

    /**
     * Puts back the outcome of a run that had already finished when the process was killed.
     * Without it the screen resumes in its in-progress state and only catches up once [WorkInfo]
     * arrives, which is what makes a long-finished run flick past the log screen on the way to
     * the finished one.
     */
    private fun restoreOutcome() {
        val progress = restoredProgress ?: return
        if (!progress.containsKey(KEY_SUCCEEDED)) return

        _patcherSucceeded.value = progress.getBoolean(KEY_SUCCEEDED)
        showSuccessScreen = progress.getBoolean(KEY_SUCCESS_SCREEN)
        isPatching = false
        if (_patcherSucceeded.value == true) viewModelScope.launch { patchedApp() }
    }

    /**
     * Runs the checks that stand between the screen and the worker, and starts the run when they
     * all pass. Nothing runs while the user answers one, so the screen is held back until it does.
     */
    private suspend fun runPreflightCheck() {
        isPatching = true
        patchRun.resumeBeforeStart()

        if (preflight()) return

        isPatching = false
        patchRun.holdBeforeStart()
    }

    /** The preflight checks themselves. False when one of them put a question on screen. */
    private suspend fun preflight(): Boolean {
        val scopedBundles = gatherScopedBundles()
        val sanitizedSelection = sanitizeSelection(appliedSelection, scopedBundles)
        val missing = mutableListOf<String>()
        appliedSelection.forEach { (uid, patches) ->
            val kept = sanitizedSelection[uid] ?: emptySet()
            patches.filterNot { it in kept }.forEach { missing += it }
        }
        if (missing.isNotEmpty() && !missingPatchesAccepted) {
            missingPatchWarning = MissingPatchWarningState(
                patchNames = missing.distinct().sorted()
            )
            return false
        }

        patchSourcesForLog = collectSelectedBundleMetadata()

        // Check that all selected bundles are compatible with the patcher bundled in this
        // version of the manager. If a bundle requires a newer patcher, block and show a dialog
        // asking the user to update the manager app
        val globalBundlesForCheck = patchBundleRepository.bundleInfoFlow.first()
        appliedSelection.keys.forEach { uid ->
            val bundle = globalBundlesForCheck[uid] ?: return@forEach
            val required = bundle.patcherVersion ?: return@forEach
            if (isPatcherOutdated(required, BuildConfig.PATCHER_VERSION)) {
                incompatiblePatcherVersion = IncompatiblePatcherVersionState(
                    requiredVersion = required,
                    bundleName = bundle.name,
                )
                return false
            }
        }

        // Validate any file-system paths supplied as patch options before handing off to the worker
        val pathFailures = withContext(Dispatchers.IO) {
            validateOptionPaths(appliedOptions.restrictTo(appliedSelection), globalBundlesForCheck.values, packageName)
        }
        if (pathFailures.isNotEmpty()) {
            inaccessibleOptionPaths = pathFailures
            return false
        }

        val powerManager = app.getSystemService(PowerManager::class.java)
        if (prefs.useExpertMode.get() && !powerManager.isIgnoringBatteryOptimizations(app.packageName) && !prefs.batteryOptimizationRequested.get()) {
            batteryOptimizationDialog = true
            return false
        }

        startWorker()
        return true
    }

    // Held from the start of the run, while the screen is still up, until the original is saved
    // and an auto-install has its own hold, since both follow the worker that ends its foreground
    private var foregroundHold: ForegroundWork.Hold? = null

    private fun releaseForeground() {
        foregroundHold?.close()
        foregroundHold = null
    }

    private fun startWorker() {
        releaseForeground()
        patchedPackageInfo = null
        foregroundHold = foregroundWork.hold(OngoingContent(
            app.getText(R.string.patcher_notification_title),
            app.getText(R.string.patcher_notification_text)
        ))
        val workId = launchWorker()
        patcherWorkerId = ParcelUuid(workId)
        observeWorker(workId)
    }

    /**
     * Save original APK file for future repatching.
     * Called after successful patching, independent of installation method.
     * For split APK archives: inputFile points to the worker's staged merged mono-APK.
     * This success-only observer promotes it according to the existing retention preference.
     * For regular APK files, saves the APK itself.
     *
     * Thread-safe: uses mutex to prevent concurrent saves from observeWorker and persistPatchedApp.
     */
    private suspend fun saveOriginalApkIfNeeded() = saveOriginalApkMutex.withLock {
        try {
            // Determine which file to save.
            // For SelectedApp.Local with a split archive: inputFile is updated to the merged
            // mono-APK via setInputFile(merged=true) after prepareIfNeeded() completes, so
            // we always use inputFile here - it already points to the correct file.
            val fileToSave = when (val selected = input.selectedApp) {
                is SelectedApp.Local -> if (input.useMount) selected.file else inputFile ?: selected.file
                else -> inputFile
            }

            if (fileToSave == null || !fileToSave.exists()) {
                Log.w(PATCHER_LOG_TAG, "File to save doesn't exist, skipping original APK save")
                return@withLock
            }

            // The retained original must keep its own identity; patches can rewrite the output version.
            val apkPackageInfo = pm.getPackageInfo(fileToSave)
            val splitArchive = SplitApkPreparer.isSplitArchive(fileToSave)
            if (apkPackageInfo == null && !splitArchive) {
                Log.w(PATCHER_LOG_TAG, "Cannot get package info from original APK, skipping save")
                return@withLock
            }

            val originalVersion = apkPackageInfo?.versionName?.takeUnless { it.isBlank() }
                ?: input.selectedApp.version
                ?: "unknown"

            // Does original already exist in repository?
            val existing = originalApkRepository.get(packageName)
            val retainedInfo = existing?.let { pm.getPackageInfo(File(it.filePath)) }
            if ((splitArchive && originalApkRepository.hasOriginal(packageName, originalVersion, fileToSave)) ||
                (!splitArchive && existing != null && existing.version == originalVersion && retainedInfo != null && apkPackageInfo != null &&
                pm.getVersionCode(retainedInfo) == pm.getVersionCode(apkPackageInfo))) {
                Log.d(PATCHER_LOG_TAG, "Original APK already exists in repository (version $originalVersion), skipping duplicate save")
                return@withLock
            }

            // If we got here, we need to save the original
            val moveSource = !input.useMount && (input.selectedApp as? SelectedApp.Local)?.temporary == true
            val savedFile = originalApkRepository.saveOriginalApk(
                packageName = packageName,
                version = originalVersion,
                sourceFile = fileToSave,
                forMount = input.useMount,
                moveSource = moveSource
            )

            if (savedFile != null) {
                if (moveSource && !fileToSave.exists()) {
                    inputFile = null
                }
                Log.i(PATCHER_LOG_TAG, "Original APK/archive saved: ${savedFile.name}")
            }
        } catch (e: Exception) {
            // Don't fail patching if save fails
            Log.w(PATCHER_LOG_TAG, "Failed to save original APK", e)
        }
    }

    suspend fun persistPatchedApp(
        currentPackageName: String?,
        installType: InstallType
    ): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        // NonCancellable: this body must run to completion even if the caller's scope is
        // canceled mid-way, so the DB row never diverges from the installed APK
        val installedPackageInfo = currentPackageName?.let(pm::getPackageInfo)
        val patchedPackageInfo = patchedApp()
        val packageInfo = patchedPackageInfo ?: installedPackageInfo
        if (packageInfo == null) {
            Log.e(PATCHER_LOG_TAG, "Failed to resolve package info for patched APK")
            return@withContext false
        }

        // This call is safe, it will skip if already saved
        saveOriginalApkIfNeeded()

        val finalPackageName = packageInfo.packageName
        val finalVersion = packageInfo.versionName?.takeUnless { it.isBlank() } ?: version ?: "unspecified"

        val savePatchedEnabled = prefs.savePatchedApks.get()

        // When patched APK retention is off and the user only exported the APK, treat the export
        // as terminal: skip both the internal copy and the DB entry so the app is not surfaced
        // as an installed patched app with no file to back it.
        if (!savePatchedEnabled && installType == InstallType.SAVED) {
            val metadata = buildExportMetadata(patchedPackageInfo ?: packageInfo)
            withContext(Dispatchers.Main) {
                exportMetadata = metadata
            }
            return@withContext true
        }

        // Delete old version file if it exists and is different
        val existingApp = installedAppRepository.get(finalPackageName)
        if (existingApp != null && existingApp.version != finalVersion) {
            val oldFile = fs.getPatchedAppFile(finalPackageName, existingApp.version)
            if (oldFile.exists()) {
                oldFile.delete()
                Log.d(PATCHER_LOG_TAG, "Deleted old patched app file: ${oldFile.name}")
            }
        }

        // Save new version
        val savedCopy = fs.getPatchedAppFile(finalPackageName, finalVersion)
        if (savePatchedEnabled) {
            try {
                savedCopy.parentFile?.mkdirs()
                // Staged, so a reader that refreshes while the copy runs never opens a half
                // written archive at the path the app already reports as the saved build
                copyThroughStaging(outputFile, savedCopy)
            } catch (error: IOException) {
                if (installType == InstallType.SAVED) {
                    Log.e(PATCHER_LOG_TAG, "Failed to copy patched APK for later", error)
                    return@withContext false
                } else {
                    Log.w(PATCHER_LOG_TAG, "Failed to update saved copy for $finalPackageName", error)
                }
            }
        }

        val metadata = buildExportMetadata(patchedPackageInfo ?: packageInfo)
        withContext(Dispatchers.Main) {
            exportMetadata = metadata
        }

        // Scoped to the original package name, so every applied patch is still recognized once
        // the run has built the app under a name of its own
        val scopedBundlesForSelection = scopedBundles()
        val sanitizedSelection = appliedRecipe?.selection ?: sanitizeSelection(appliedSelection, scopedBundlesForSelection)
        val sanitizedOptions = if(appliedRecipe!=null) appliedOptions else sanitizeOptions(appliedOptions, scopedBundlesForSelection)

        val selectionPayload = appliedRecipe?.payload ?: patchBundleRepository.snapshotSelection(sanitizedSelection)

        val isClone = appliedRecipe?.let { recipe -> finalPackageName!=packageName && recipe.renamingPatches.any {
            it.key in recipe.selection[it.sourceUid].orEmpty()
        } } ?: producedClone(finalPackageName, sanitizedSelection, scopedBundlesForSelection)

        installedAppRepository.addOrUpdate(
            finalPackageName,
            packageName,
            isClone,
            finalVersion,
            installType,
            sanitizedSelection,
            selectionPayload
        )

        persistConfiguration(
            // A copy keeps a configuration of its own, while the app's install reads and writes
            // the app's, whichever package name the patches ended up building it under
            configurationPackageName = if (isClone) finalPackageName else packageName,
            selection = sanitizedSelection,
            options = sanitizedOptions,
            bundles = scopedBundlesForSelection
        )
        appliedSelection = sanitizedSelection
        appliedOptions = sanitizedOptions

        savedPatchedApp = savedPatchedApp || installType == InstallType.SAVED || savedCopy.exists()
        true
    }

    /**
     * Stores the patches and options this run was built with under the install it produced.
     *
     * A configuration describes an install, and every install of an app keeps its own. A run that
     * produced a copy therefore leaves the one it started from untouched: what it writes is a
     * copy, taken at the moment the copy came into being.
     */
    private suspend fun persistConfiguration(
        configurationPackageName: String,
        selection: PatchSelection,
        options: Options,
        bundles: Map<Int, PatchBundleInfo.Scoped>
    ) {
        patchSelectionRepository.updateSelection(
            configurationPackageName,
            selection,
            scope = input.sourceScope
        )
        // Options simple mode keeps in its settings stay there, rather than replacing the ones
        // saved for the sources they name
        if (input.selectedByHand) patchOptionsRepository.saveOptions(configurationPackageName, options)

        // Taken here as well as before patching, because a copy starts out with no snapshot of
        // its own and would flag every patch it was just built with as new
        val seen=appliedRecipe?.seenPatches ?: bundles.mapValues { (_,bundle) -> bundle.patches.mapTo(mutableSetOf()) { it.name } }
        seen.forEach { (uid, names) ->
            patchSelectionRepository.saveSeenPatches(
                packageName = configurationPackageName,
                bundleUid = uid,
                patchNames = names
            )
        }
    }

    override var downloadProgress by savedStateHandle.saveable(
        key = "downloadProgress",
        stateSaver = autoSaver()
    ) {
        mutableStateOf<Pair<Long, Long?>?>(null)
    }
        private set

    /**
     * True while an APK export is in progress. Observed by UI to disable the save button.
     */
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    fun export(uri: Uri?) = viewModelScope.launch {
        uri?.let { targetUri ->
            if (_isSaving.value) return@let
            _isSaving.value = true
            try {
                ensureExportMetadata()
                finishExport(app.exportFileTo(outputFile, targetUri))
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Shared post-export logic: persists the patched app record, shows a toast,
     * and raises the post-patch prompts on success.
     */
    private suspend fun finishExport(exportSucceeded: Boolean) {
        if (!exportSucceeded) {
            app.toast(app.getString(R.string.saved_app_export_failed))
            return
        }

        val saved = persistPatchedApp(null, InstallType.SAVED)

        if (!saved) {
            app.toast(app.getString(R.string.patched_app_save_failed_toast))
        } else {
            app.toast(app.getString(R.string.save_apk_success))
            delay(2.seconds)
        }

        if (saved) postPatchPrompts.trigger()
    }

    fun rejectInteraction() {
        currentActivityRequest?.first?.complete(false)
    }

    fun allowInteraction() {
        currentActivityRequest?.first?.complete(true)
    }

    fun handleActivityResult(result: ActivityResult) {
        launchedActivity?.complete(result)
    }

    private fun launchWorker(): UUID {
        savedStateHandle["applied_recipe_required"]=true
        return workerRepository.launchExpedited<PatcherWorker, PatcherWorker.Args>(
            buildWorkerArgs().also { workerArgs = it }
        )
    }

    private fun buildWorkerArgs(): PatcherWorker.Args {
        val selectedForRun = when (val selected = input.selectedApp) {
            is SelectedApp.Local -> {
                val reuseFile = inputFile ?: selected.file
                val temporary = !forceKeepLocalInput && selected.temporary
                selected.copy(file = reuseFile, temporary = temporary)
            }

            else -> selected
        }

        val shouldPreserveInput =
            selectedForRun is SelectedApp.Local && (selectedForRun.temporary || forceKeepLocalInput)

        // The flow captured the correct source-specific or simple-mode options before navigation.
        return PatcherWorker.Args(
            selectedForRun,
            outputFile.path,
            appliedSelection,
            appliedOptions,
            patchRun.logger,
            onPatchCompleted = { patchRun.onPatchCompleted() },
            onPatchFailed = patchRun::onPatchFailed,
            onPatchingRestarted = { remaining -> patchRun.onRestart(remaining) },
            setInputFile = { file, needsSplit, merged ->
                val storedFile = if (merged) file else if (shouldPreserveInput) {
                    val existing = inputFile
                    if (existing?.exists() == true) {
                        // Reuse the already-copied file from a previous attempt (e.g. OOM retry).
                        // Do NOT delete it here - it is still the valid input for this run
                        existing
                    } else withContext(Dispatchers.IO) {
                        // Clean up a stale reference that no longer exists on disk before
                        // creating a new copy, so we don't accumulate input-*.apk files in
                        // tempDir across multiple OOM retries
                        inputFile?.takeIf { !it.exists() }?.let {
                            Log.d(PATCHER_LOG_TAG, "Stale inputFile reference cleared: ${it.name}")
                        }
                        val destination = File(tempDir, "input.apk")
                        file.copyTo(destination, overwrite = true)
                        destination
                    }
                } else file

                withContext(Dispatchers.Main) {
                    inputFile = storedFile
                    patchRun.updateSplitRequirement(storedFile, needsSplit, merged)
                }
            },
            onProgress = patchRun::onProgress,
            onTaskProgress = patchRun::onTaskProgress,
            patchSources = patchSourcesForLog,
            useMount = input.useMount,
            onRecipeApplied = { recipe -> withContext(Dispatchers.Main) {
                appliedRecipe=recipe
                appliedSelection=recipe.selection
            } },
        )
    }

    private fun observeWorker(id: UUID) {
        observeWorkerJob?.cancel()
        observeWorkerJob = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(id).collect { workInfo ->
                // WorkManager prunes finished work eventually, and a record that is simply gone
                // says nothing about a run whose outcome was restored after process death
                if (workInfo == null && _patcherSucceeded.value != null) return@collect

                when (workInfo?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        if(savedStateHandle.get<Boolean>("applied_recipe_required")==true) {
                            val recipe=try { workerArgs?.appliedRecipe ?: withContext(Dispatchers.IO) { readAppliedPatchRecipe(outputFile) } }
                            catch(error: Exception) {
                                if(error is kotlinx.coroutines.CancellationException) throw error
                                patchRun.logger.error("Could not restore the applied patch recipe: ${error.message}")
                                patchRun.stopStallWatch()
                                patchRun.onProgress(null,State.FAILED,"Applied patch recipe unavailable; the output cannot be recorded safely")
                                releaseForeground(); isPatching=false; _patcherSucceeded.value=false; showSuccessScreen=true
                                return@collect
                            }
                            appliedRecipe=recipe
                            appliedSelection=recipe.selection
                            patchRun.totalPatches=patchCount
                        }
                        forceKeepLocalInput = false
                        patchRun.stopStallWatch()
                        foregroundHold?.update(OngoingContent(app.getText(R.string.patcher_notification_title), app.getText(R.string.patcher_notification_text)))

                        // Every later run would lose the same attempt to the same kill

                        // Save original APK before deleting temporary file (blocking).
                        // Launched independently so cancelling observeWorkerJob (new patch run)
                        // does not interrupt cleanup that is already in progress.
                        originalRetentionJob = viewModelScope.launch(Dispatchers.IO) {
                            try {
                                saveOriginalApkIfNeeded()
                                patchedApp()
                            } finally {
                                withContext(Dispatchers.Main) {
                                    // Delete temporary input file after saving
                                    if (!input.useMount) cleanupTemporaryInput()
                                    refreshExportMetadata()
                                    patchingCompletedAt = System.currentTimeMillis()
                                    patchingCompletedInForeground = _patcherSucceeded.subscriptionCount.value > 0
                                    isPatching = false
                                    _patcherSucceeded.value = true
                                    scheduleSuccessScreen()
                                    scheduleAutoInstallIfNeeded()
                                }
                            }
                        }
                    }

                    WorkInfo.State.FAILED -> {
                        patchRun.stopStallWatch()
                        handleWorkerFailure(workInfo)
                        releaseForeground()
                        isPatching = false
                        _patcherSucceeded.value = false
                        showSuccessScreen = true
                    }

                    WorkInfo.State.RUNNING,
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED -> {
                        isPatching = true
                        _patcherSucceeded.value = null
                    }
                    WorkInfo.State.CANCELLED -> {
                        releaseForeground()
                        _patcherSucceeded.value = null
                    }
                    // Not on record yet, or pruned
                    null -> _patcherSucceeded.value = null
                }
            }
        }
    }

    /** What is left of the beat the progress screen holds a finished run for. */
    private val successScreenDelay: Duration
        get() {
            val elapsed = patchingCompletedAt?.let { System.currentTimeMillis() - it } ?: 0L
            return (2000L - elapsed).coerceAtLeast(0L).milliseconds
        }

    private fun scheduleSuccessScreen() = viewModelScope.launch {
        delay(successScreenDelay)
        showSuccess()
    }

    /** Called once the installer has taken the auto-install over. */
    fun autoInstallHandedOff() {
        autoInstallPending = false
        releaseForeground()
    }

    private fun scheduleAutoInstallIfNeeded() = viewModelScope.launch {
        // A patch is free to rename the app, and the name it built under is the one being replaced
        val target = withContext(Dispatchers.IO) { pm.getPackageInfo(outputFile)?.packageName }
            ?: packageName
        if (!installerManager.autoInstallAllowed(target, mount = input.useMount)) {
            releaseForeground()
            return@launch
        }
        autoInstallPending = true
        // Allowed only where no system dialog comes up, so it starts right away, a round in play
        // included. What it may still have to ask waits on the screen for the success screen
        _autoInstallChannel.trySend(Unit)
    }

    private fun handleWorkerFailure(workInfo: WorkInfo) {
        // Runtime failure details are supplied by the shared JVM/ART worker; no obsolete
        // process-memory limit is inferred or silently changed after an attempt.
        handledFailureIds.add(workInfo.id)
    }

    private fun initialSplitRequirement(selectedApp: SelectedApp): Boolean =
        when (selectedApp) {
            is SelectedApp.Local -> SplitApkPreparer.isSplitArchive(selectedApp.file)
            else -> false
        }

    private fun sanitizeSelection(
        selection: PatchSelection,
        bundles: Map<Int, PatchBundleInfo.Scoped>
    ): PatchSelection = buildMap {
        selection.forEach { (uid, patches) ->
            val bundle = bundles[uid]
            if (bundle == null) {
                // Keep unknown bundles so applied patches stay visible even if the source is missing.
                if (patches.isNotEmpty()) put(uid, patches.toSet())
                return@forEach
            }

            val valid = bundle.patches.map { it.name }.toSet()
            val kept = patches.filter { it in valid }.toSet()
            if (kept.isNotEmpty()) {
                put(uid, kept)
            } else if (patches.isNotEmpty()) {
                // If everything was filtered out by compatibility, still keep the original set so
                // the app info screen can show the applied bundle/patch names.
                put(uid, patches.toSet())
            }
        }
    }

    private fun sanitizeOptions(
        options: Options,
        bundles: Map<Int, PatchBundleInfo.Scoped>
    ): Options = buildMap {
        options.forEach { (uid, patchOptions) ->
            val bundle = bundles[uid] ?: return@forEach
            val patches = bundle.patches.associateBy { it.name }
            val filtered = buildMap {
                patchOptions.forEach { (patchName, values) ->
                    val patch = patches[patchName] ?: return@forEach
                    val validKeys = patch.options?.map { it.key }?.toSet() ?: emptySet()
                    val kept = if (validKeys.isEmpty()) values else values.filterKeys { it in validKeys }
                    if (kept.isNotEmpty()) put(patchName, kept)
                }
            }
            if (filtered.isNotEmpty()) put(uid, filtered)
        }
    }

    /**
     * Immediately cancels the patcher worker.
     * Called when the user confirms cancellation so the worker stops before
     * the ViewModel is cleared, preventing background CPU/RAM usage that causes UI jank.
     */
    fun cancelPatching() {
        patcherWorkerId?.uuid?.let(workManager::cancelWorkById)
    }

    private fun cleanupTemporaryInput() {
        if (inputFileIsDisposable || (input.selectedApp as? SelectedApp.Local)?.temporary == true) {
            inputFile?.takeIf { inputFileIsDisposable }?.delete()
            (input.selectedApp as? SelectedApp.Local)?.takeIf { it.temporary }?.file?.delete()
            inputFile = null
            patchRun.updateSplitRequirement(null)
        }
    }

    /**
     * Stops any patching-completion tone that might still be playing.
     * Called directly from the UI for an instant cutoff on Home tap, and again from
     * [onCleared] as a fallback for every other way of leaving the screen.
     */
    fun stopCompletionSound() {
        PatcherWorker.stopCompletionSound()
    }

    override fun onCleared() {
        releaseForeground()
        val installers = synchronized(installInputUses) { inputCleanupStarted = true; installInputUses.toList() }
        val worker = patcherWorkerId?.uuid
        val use = workerArgs?.inputUse
        val retention = originalRetentionJob
        use?.abandonBeforeStart()
        worker?.let { id ->
            workManager.cancelWorkById(id)
            workerArgs?.let { workerRepository.workerInputs.remove(id, it) }
        }
        val temporaryOriginal = (input.selectedApp as? SelectedApp.Local)?.takeIf { it.temporary && !inputHandedToEditor }?.file
        val disposableInput = inputFile?.takeIf { inputFileIsDisposable }
        stopCompletionSound()
        patchRun.finish()
        // Cancellation state alone does not establish release. The worker signals after runtime finally/close.
        inputCleanupScope.launch(Dispatchers.IO) {
            clearPatchWorkspaceAfterWorker(tempDir, disposableInput, temporaryOriginal) {
                if (use != null) use.awaitReleased()
                else worker?.let { id -> workManager.getWorkInfoByIdFlow(id).first { it == null || it.state.isFinished } }
                retention?.join()
                installers.forEach { it.awaitReleased() }
            }
        }
    }

    private companion object {
        private const val KEY_PROGRESS = "patch_progress"
        private const val KEY_STEPS = "steps"
        private const val KEY_COMPLETED_PATCHES = "completed_patches"
        private const val KEY_FAILED_PATCH = "failed_patch"
        private const val KEY_SUCCEEDED = "succeeded"
        private const val KEY_APPLIED_RECIPE = "applied_recipe"
        private const val KEY_SUCCESS_SCREEN = "success_screen"
    }
}
