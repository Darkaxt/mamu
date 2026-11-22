/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.batch

import android.app.Application
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.morphe.manager.ManagerApplication
import app.morphe.manager.R
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.domain.installer.InstallResult
import app.morphe.manager.domain.installer.InstallerManager
import app.morphe.manager.domain.installer.SessionInstaller
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.*
import app.morphe.manager.domain.worker.WorkerRepository
import app.morphe.manager.patcher.patch.KnownPatchFailures
import app.morphe.manager.patcher.patch.knownFailureRetryKey
import app.morphe.manager.patcher.patch.knownFailures
import app.morphe.manager.notification.OngoingContent
import app.morphe.manager.notification.PatchingNotifications
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.patcher.worker.PatcherWorker
import app.morphe.manager.patcher.runtime.readAppliedPatchRecipe
import app.morphe.manager.service.ForegroundHoldService
import app.morphe.manager.service.ForegroundWork
import app.morphe.manager.ui.model.PatchRunProgress
import app.morphe.manager.ui.model.SelectedApp
import app.morphe.manager.util.ApkExportData
import app.morphe.manager.util.AppCoroutineScope
import app.morphe.manager.util.CompletionSound
import app.morphe.manager.util.Options
import app.morphe.manager.util.PM
import app.morphe.manager.util.PatchSelection
import app.morphe.manager.util.PatchSelectionUtils.sanitizeForPatcher
import app.morphe.manager.util.exportData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.util.UUID

private const val TAG = "Morphe BatchPatcher"

/**
 * Drives a batch patch run: one app at a time, no user interaction between items.
 *
 * The coordinator lives on the application scope rather than in a ViewModel because a run
 * takes minutes per app and must survive navigating away from the batch screen. Only the
 * patcher itself runs in [PatcherWorker], which keeps the foreground service and wakelock
 * that the single-app flow already relies on.
 */
class BatchPatchCoordinator(
    private val app: Application,
    private val fs: Filesystem,
    private val pm: PM,
    private val prefs: PreferencesManager,
    private val resolver: BatchPlanResolver,
    private val workerRepository: WorkerRepository,
    private val patchBundleRepository: PatchBundleRepository,
    private val patchSelectionRepository: PatchSelectionRepository,
    private val patchOptionsRepository: PatchOptionsRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val originalApkRepository: OriginalApkRepository,
    private val installerManager: InstallerManager,
    private val sessionInstaller: SessionInstaller,
    private val json: Json,
    private val scope: AppCoroutineScope,
    private val knownPatchFailures: KnownPatchFailures,
    private val foregroundWork: ForegroundWork
) {
    private val workManager = WorkManager.getInstance(app)

    private val _state = MutableStateFlow<BatchRunState?>(null)
    val state: StateFlow<BatchRunState?> = _state.asStateFlow()

    private var runJob: Job? = null
    private var activeWorkId: UUID? = null

    private val queueJson = Json
    private val preparationWrites = Mutex()
    @Volatile private var hasPlanned = false

    // Keeps the queue in the foreground between the workers of its apps
    @Volatile
    private var foregroundHold: ForegroundWork.Hold? = null

    // The latest re-resolve per item, so an older one finishing last cannot undo the user's newer pick
    private val resolveJobs = ConcurrentHashMap<String, Job>()

    /** Prepared intent only; a restored queue is always resolved against current sources. */
    val savedQueue: StateFlow<SavedBatchQueue?> = prefs.batchQueue.flow
        .map(::decodeQueue).flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(Dispatchers.IO) {
            _state.collect { if (hasPlanned) persistPreparation() }
        }
    }

    private suspend fun persistPreparation() = preparationWrites.withLock {
        // Read live state under the writer lock: an older emission cannot resurrect a
        // preparation already cleared or started while its disk write was waiting.
        val prepared = _state.value?.savedQueue
        prefs.batchQueue.update(prepared?.let { queueJson.encodeToString(it) }.orEmpty())
    }

    /** Workspace for APKs copied out of installed apps and for patcher output. */
    private val inputWorkspace = BatchPatchWorkspace(fs.uiTempDir)
    private val workspace get() = inputWorkspace.directory
    internal fun reserveImport(extension: String) = inputWorkspace.reserveImport(extension)

    /** True while the queue is patching, used to block a competing single-app run. */
    val isRunning: Boolean get() = _state.value?.isActive == true || runJob?.isCompleted == false

    /**
     * Builds a plan for [targets] and parks it in the preflight phase so the user can
     * attach missing APKs or drop items before anything is patched.
     */
    fun plan(
        targets: List<BatchTarget>,
        useMount: Boolean? = null,
        policy: BatchInstallPolicy
    ) {
        if (_state.value?.isActive == true) return
        runJob?.cancel()
        runJob = scope.launch {
            planFresh(targets, policy, excludedIds = emptySet(),
                mountChoices = useMount?.let { mount -> targets.associate { it.id to mount } }.orEmpty())
        }
    }

    /**
     * Adds [targets] to the queue being prepared, or plans a new one around them. A queue stored
     * before the process died is planned again first, so the apps added to it earlier stay.
     */
    fun enqueue(targets: List<BatchTarget>) {
        if (_state.value?.isActive == true) return
        val pending = runJob
        runJob = scope.launch {
            // Planning still under way finishes first, so its apps are there to add to
            pending?.join()
            val current = _state.value
            when (current?.phase) {
                BatchPhase.PREFLIGHT -> append(current, targets)
                BatchPhase.PLANNING, BatchPhase.RUNNING -> Unit
                null, BatchPhase.FINISHED -> {
                    val saved = if (hasPlanned) null else withContext(Dispatchers.IO) { decodeQueue(prefs.batchQueue.get()) }
                    planFresh(
                        targets = (saved?.targets.orEmpty() + targets).distinctBy { it.id },
                        policy = saved?.policy ?: prefs.batchInstallPolicy.get(),
                        excludedIds = saved?.excluded.orEmpty(),
                        mountChoices = saved?.resolvedMountChoices.orEmpty(),
                        kept = saved != null
                    )
                }
            }
        }
    }

    private suspend fun planFresh(
        targets: List<BatchTarget>,
        policy: BatchInstallPolicy,
        excludedIds: Set<String>,
        mountChoices: Map<String, Boolean> = emptyMap(),
        kept: Boolean = false
    ) {
        fun BatchPatchItem.withExclusion() = if (id in excludedIds) copy(state = BatchItemState.EXCLUDED, restoreState = state) else this

        hasPlanned = true
        resolveJobs.values.forEach { it.cancel() }
        resolveJobs.clear()
        if (_state.value != null) {
            // A fresh preparation abandons the previous queue, whereas append/retry
            // keep its exact inputs. Retire only after outstanding copy/read consumers.
            val cleanup = inputWorkspace.retire()
            scope.launch(Dispatchers.IO) { cleanup() }
        }
        _state.value = BatchRunState(
            items = targets.map { placeholder(it).copy(useMount = mountChoices[it.id] ?: false).withExclusion() },
            phase = BatchPhase.PLANNING,
            policy = policy,
            kept = kept
        )
        val items = resolver.resolve(targets, mountChoices).map { it.withExclusion() }
        _state.update { it.copy(items = items, phase = BatchPhase.PREFLIGHT) }
    }

    /** Resolves only the apps [current] does not hold yet, leaving what the user set up on the rest. */
    private suspend fun append(current: BatchRunState, targets: List<BatchTarget>) {
        val queued = current.items.mapTo(mutableSetOf()) { it.id }
        val added = targets.distinctBy { it.id }.filter { it.id !in queued }
        if (added.isEmpty()) return

        _state.update { it.copy(items = it.items + added.map(::placeholder), phase = BatchPhase.PLANNING) }
        val resolved = resolver.resolve(added).associateBy { it.id }
        _state.update { state ->
            state.copy(items = state.items.map { resolved[it.id] ?: it }, phase = BatchPhase.PREFLIGHT)
        }
    }

    private fun decodeQueue(stored: String): SavedBatchQueue? {
        if (stored.isBlank()) return null
        return runCatching { queueJson.decodeFromString<SavedBatchQueue>(stored) }
            .onFailure { Log.w(TAG, "Failed to decode the saved batch queue", it) }
            .getOrNull()
            ?.takeIf { it.targets.isNotEmpty() }
    }

    /** Re-resolves a single item against a manually attached APK. */
    fun attachApk(itemId: String, file: File) {
        val current = _state.value ?: return
        if (current.phase != BatchPhase.PREFLIGHT) return
        val item = current.items.firstOrNull { it.id == itemId } ?: return

        val owner = workspace
        val use = inputWorkspace.useImport(file) ?: return
        scope.launch {
            try {
                val resolved = resolver.reattach(item, file)
                _state.update { state ->
                    if (workspace != owner || state.phase != BatchPhase.PREFLIGHT) state
                    else state.copy(items = state.items.map { if (it === item) resolved else it })
                }
            } finally { use.finish() }
        }
    }

    private fun reresolveItem(itemId: String, resolve: suspend (BatchPatchItem) -> BatchPatchItem): Job? {
        val current = _state.value ?: return null
        if (current.phase != BatchPhase.PREFLIGHT) return null
        val item = current.items.firstOrNull { it.id == itemId } ?: return null
        resolveJobs.remove(itemId)?.cancel()
        // Replanning a mount target reads the attached original again. Keep its queue
        // workspace alive until the parser actually exits, including cancellation.
        val inputUse = (item.source as? BatchApkSource.UserFile)?.file?.let(inputWorkspace::useImport)
        val job = scope.launch {
            val resolved = resolve(item)
            _state.update { state ->
                if (state.phase != BatchPhase.PREFLIGHT) state
                else state.copy(items = state.items.map { if (it === item) resolved else it })
            }
        }
        job.invokeOnCompletion { inputUse?.finish() }
        resolveJobs[itemId] = job
        return job
    }

    /** Re-resolves one item against the APK the user picked, saved original or installed app. */
    fun useSource(itemId: String, preferInstalled: Boolean) = reresolveItem(itemId) { item ->
        resolver.useSource(item, preferInstalled)
    }

    /** Retain the explicit version/source identity, then classify the actual input afresh. */
    fun setVersionSource(itemId: String, uid: Int, version: String?) {
        resolveJobs.remove(itemId)?.cancel()
        _state.update { state ->
            if (state.phase != BatchPhase.PREFLIGHT) state else state.copy(
                items = state.items.map { item ->
                    if (item.id != itemId) item else item.copy(target = item.target.copy(
                        versionSourceUid = uid, versionSourceVersion = version))
                })
        }
        reresolveItem(itemId) { item ->
            val refreshed = resolver.refresh(item)
            if (item.state == BatchItemState.EXCLUDED) refreshed.copy(
                state = BatchItemState.EXCLUDED, restoreState = refreshed.state) else refreshed
        }
    }

    /** Replan a queued app against its deliberately selected install target. */
    fun setInstallTarget(itemId: String, useMount: Boolean) = reresolveItem(itemId) { item ->
        val refreshed = resolver.refresh(item.copy(useMount = useMount))
        if (item.state == BatchItemState.EXCLUDED) refreshed.copy(
            state = BatchItemState.EXCLUDED, restoreState = refreshed.state
        ) else refreshed
    }

    /** Toggles an item between excluded and its resolved state. */
    fun toggleExcluded(itemId: String) {
        _state.update { state ->
            if (state.phase != BatchPhase.PREFLIGHT) return@update state
            state.copy(
                items = state.items.map { item ->
                    if (item.id != itemId) return@map item
                    when (item.state) {
                        BatchItemState.EXCLUDED -> item.copy(
                            state = item.restoreState ?: BatchItemState.READY,
                            restoreState = null
                        )

                        else -> item.copy(
                            state = BatchItemState.EXCLUDED,
                            restoreState = item.state
                        )
                    }
                }
            )
        }
    }

    /**
     * Accepts an unsupported version for one item, mirroring the single-app warning dialog.
     *
     * The item is resolved again rather than just flipped to runnable, because accepting the
     * version is what brings in the patches that declare a different one. Without that the
     * app would be patched with nothing but the universal patches.
     */
    fun forceVersion(itemId: String) = reresolveItem(itemId) { item ->
        if (item.state == BatchItemState.VERSION_MISMATCH) resolver.forceVersion(item) else item
    }

    /**
     * Accepts an APK the bundles do not vouch for, mirroring the single-app warning dialog.
     *
     * The item is resolved again rather than just flipped to runnable, because everything the
     * run needs was left unresolved while the file was in question.
     */
    fun acceptUnverifiedSignature(itemId: String) = reresolveItem(itemId) { item ->
        if (item.state == BatchItemState.UNVERIFIED_SIGNATURE) resolver.acceptUnverifiedSignature(item) else item
    }

    /**
     * Replaces what one queued app will be patched with, after the user edited it on the
     * preflight screen. Deselecting everything leaves nothing to run, which is the same
     * situation as a source that contributes no patches.
     */

    fun updateSelection(itemId: String, selection: PatchSelection, options: Options, allowUnsupported: Boolean = false,
        rememberedSelection: PatchSelection? = null, sourceBundles: List<BatchBundleRef>? = null, knownFailureRetries: Set<String>? = null) {
        _state.update { state ->
            if (state.phase != BatchPhase.PREFLIGHT) return@update state
            state.copy(
                items = state.items.map { item ->
                    if (item.id != itemId) return@map item

                    val empty = selection.values.sumOf { it.size } == 0
                    item.copy(
                        selection = selection,
                        selectedByHand = true,
                        selectionEdited = true,
                        knownFailureRetries = knownFailureRetries ?: item.knownFailureRetries,
                        options = options,
                        forceVersionMismatch = item.forceVersionMismatch || allowUnsupported,
                        resolvedSelection = rememberedSelection ?: item.resolvedSelection,
                        bundles = sourceBundles ?: item.bundles,
                        state = when {
                            empty -> BatchItemState.NO_PATCHES
                            item.state == BatchItemState.NO_PATCHES -> BatchItemState.READY
                            else -> item.state
                        }
                    )
                }
            )
        }
    }

    /**
     * Restricts one queued app to a single patch source, the question simple mode answers
     * before a single-app patch. The full plan is kept so another source can still be picked.
     *
     * Options are left alone: they are keyed by source, and the patcher ignores those it has
     * no selected patch for.
     */
    fun narrowToSource(itemId: String, bundleUid: Int) {
        _state.update { state ->
            if (state.phase != BatchPhase.PREFLIGHT) return@update state
            state.copy(
                items = state.items.map { item ->
                    if (item.id != itemId) return@map item

                    val resolved = item.resolvedSelection ?: item.selection
                    item.copy(
                        resolvedSelection = resolved,
                        selection = resolved.filterKeys { it == bundleUid }
                    )
                }
            )
        }
    }

    /** Sets what this queue does once patched, remembered as the starting point of the next one. */
    fun setPolicy(policy: BatchInstallPolicy) {
        _state.update { it.copy(policy = policy) }
        scope.launch { prefs.batchInstallPolicy.update(policy) }
    }

    /** Keeps the queue that has not started yet for later, stored until it is started or cleared. */
    fun keep() {
        _state.update { if (it.isPending) it.copy(kept = true) else it }
    }

    fun markAutoInstallStarted() {
        _state.update { it.copy(autoInstallStarted = true) }
    }

    /** Records what the installer did with one patched app, for the summary to show. */
    fun markInstallResult(
        itemId: String,
        outcome: BatchInstallOutcome,
        message: String? = null,
        installedPackageName: String? = null
    ) {
        _state.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id == itemId) {
                        item.copy(
                            installOutcome = outcome,
                            installMessage = message,
                            installedPackageName = installedPackageName ?: item.installedPackageName
                        )
                    } else {
                        item
                    }
                }
            )
        }
    }

    fun retryUnfinished() {
        if (isRunning) return
        val current = _state.value ?: return
        hasPlanned = true
        val targets = current.items.filter { it.state == BatchItemState.FAILED || it.state == BatchItemState.CANCELLED }
        if (targets.isEmpty()) return
        runJob = scope.launch {
            _state.value = current.copy(items = targets, phase = BatchPhase.PLANNING, activeIndex = null, activeRun = null)
            val refreshed = targets.map { item ->
                val resolved = resolver.resolve(item.target, item.useMount, attachedFile = item.retryOriginalFile(),
                    allowIncompatible = item.forceVersionMismatch,
                    allowUnverifiedSignature = item.forceUnverifiedSignature)
                // Reopen only the editor intent. Fresh source/actual APK validation still owns admission.
                if (!item.selectionEdited || resolved.source == null) resolved else {
                    val allowed = resolved.bundles.associate { it.uid to it.patchNames }
                    val kept = item.selection.mapValues { (uid, names) -> names.intersect(allowed[uid].orEmpty()) }
                        .filterValues { it.isNotEmpty() }
                    resolved.copy(selection = kept, options = item.options,
                        selectedByHand = true, selectionEdited = true,
                        state = when {
                            kept.isEmpty() -> BatchItemState.NO_PATCHES
                            resolved.state == BatchItemState.NO_PATCHES -> BatchItemState.READY
                            else -> resolved.state
                        })
                }
            }
            _state.update { it.copy(items = refreshed, phase = BatchPhase.PREFLIGHT) }
        }
    }

    /** Starts the queue. Items that are not [BatchItemState.READY] are left untouched. */
    fun start() {
        val current = _state.value ?: return
        if (current.phase != BatchPhase.PREFLIGHT) return
        if (current.runnable.isEmpty()) return

        runJob?.cancel()
        resolveJobs.values.forEach { it.cancel() }
        resolveJobs.clear()
        _state.update { it.copy(phase = BatchPhase.RUNNING) }
        // A run canceled before it started never gets to close its own
        foregroundHold?.close()
        // Taken while the app is still on screen, the last moment the system grants it
        val hold = foregroundWork.hold(
            OngoingContent(app.getText(R.string.batch_patch_title), app.getText(R.string.patcher_notification_text))
        ).also { foregroundHold = it }
        runJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { persistPreparation() }
                runQueue()
            } catch (e: CancellationException) {
                markRemainingCancelled()
                throw e
            } finally {
                withContext(NonCancellable) {
                    activeWorkId = null
                    installUnattendedIfRequested()
                    hold.close()
                    _state.update {
                        it.copy(
                            phase = BatchPhase.FINISHED,
                            activeIndex = null,
                            activeRun = null,
                            finishedInForeground = ManagerApplication.isInForeground
                        )
                    }
                    announceCompletion()
                }
            }
        }
    }

    /** Cancels the active item and marks everything still queued as canceled. */
    fun cancel() {
        activeWorkId?.let(workManager::cancelWorkById)
        runJob?.cancel()
        markRemainingCancelled()
        // A cancelled coroutine is still closing its runtime and input. Its finally owns FINISHED.
        if (runJob == null) _state.update { it.copy(phase = BatchPhase.FINISHED, activeIndex = null, activeRun = null) }
    }

    /**
     * Drops a finished run. The workspace is kept until the next run starts so patched APKs
     * stay installable while the summary is still open.
     */
    fun clear() {
        if (_state.value?.phase == BatchPhase.RUNNING) return
        runJob?.cancel()
        runJob = null
        hasPlanned = true
        resolveJobs.values.forEach { it.cancel() }
        resolveJobs.clear()
        val cleanup = inputWorkspace.retire()
        scope.launch(Dispatchers.IO) { cleanup() }
        _state.value = null
        scope.launch(Dispatchers.IO) { persistPreparation() }
    }

    /**
     * Installs what the run produced before the queue reports itself finished, for a run the
     * user left. The batch screen installs from its own summary, but only while it is on
     * screen: it stops collecting the run once the activity is stopped, so a queue that ended
     * in the background would sit there until the manager came back.
     *
     * Installing here rather than there is only possible without a confirmation dialog, so a
     * run the user is watching keeps going through the summary, which shows its progress.
     */
    private suspend fun installUnattendedIfRequested() {
        val state = _state.value ?: return
        if (state.policy != BatchInstallPolicy.INSTALL_AFTER) return
        if (ManagerApplication.isInForeground) return

        installPatchedUnattended(state.patchedItems)
    }

    /**
     * Installs [items] through Shizuku, the only installer that never asks, and records what
     * happened on the run. Whatever does not go in stays on the summary, where the user
     * installs it through the interactive installer.
     */
    private suspend fun installPatchedUnattended(items: List<BatchPatchItem>) {
        val token = installerManager.getPreferredToken()
        val asPlayStore = token == InstallerManager.Token.ShizukuPlayStore
        if (token != InstallerManager.Token.Shizuku && !asPlayStore) {
            Log.d(TAG, "Preferred installer cannot install unattended")
            return
        }

        for (item in items) {
            if (item.installOutcome == BatchInstallOutcome.INSTALLED) continue
            // A mount build is only of use mounted over its app, which the summary does
            if (item.useMount) continue
            val file = item.patchedBuild?.file?.takeIf { it.exists() } ?: continue
            val info = pm.getPackageInfo(file)
            val targetPackage = info?.packageName ?: item.packageName

            val result = runCatching {
                if (asPlayStore) {
                    sessionInstaller.installShizukuAsPlayStore(file, targetPackage)
                } else {
                    sessionInstaller.installShizuku(file, targetPackage)
                }
            }.getOrElse { error ->
                Log.w(TAG, "Unattended install failed for $targetPackage", error)
                null
            }

            if (result !is InstallResult.Success) continue

            val version = info?.versionName?.takeUnless { it.isBlank() } ?: item.version ?: continue
            installedAppRepository.addOrUpdate(
                currentPackageName = targetPackage,
                originalPackageName = item.packageName,
                isClone = item.producesCloneAs(targetPackage),
                version = version,
                installType = if (asPlayStore) InstallType.SHIZUKU_PLAY_STORE else InstallType.SHIZUKU,
                patchSelection = item.selection,
                selectionPayload = item.appliedRecipe?.payload ?: patchBundleRepository.snapshotSelection(item.selection)
            )
            markInstallResult(
                itemId = item.id,
                outcome = BatchInstallOutcome.INSTALLED,
                installedPackageName = targetPackage
            )
        }
    }

    /**
     * Reports a finished queue once, instead of once per app: a queue of eight would otherwise
     * ping eight times, and no single app finishing is something the user can act on.
     */
    private suspend fun announceCompletion() {
        val state = _state.value ?: return
        if (!state.hasOutcome) return

        // A queue stopped before its end closes quietly rather than sounding like a result
        val playTone = prefs.patcherCompletionSound.get() && !state.wasStopped

        // The summary screen already says all of this when the user is watching it, the same
        // rule a single run follows. The tone still plays, it is what draws them back, and
        // stands in for the channel sound rather than playing beside it
        if (!ManagerApplication.isInForeground) {
            PatchingNotifications.showBatchResult(
                context = app,
                patched = state.succeeded,
                failed = state.failed,
                skipped = state.skipped,
                silent = playTone
            )
        }

        if (playTone) {
            CompletionSound.play(
                context = app,
                succeeded = state.succeeded > 0,
                successSoundUri = prefs.patcherSuccessSoundUri.get(),
                errorSoundUri = prefs.patcherErrorSoundUri.get()
            )
        }
    }

    private suspend fun runQueue() {
        // Imports in this queue are also retry inputs. Only a dismissed queue retires them.
        withContext(Dispatchers.IO) { workspace.mkdirs() }

        while (true) {
            val current = _state.value ?: return
            val index = current.items.indexOfFirst { it.state.isRunnable }
            if (index < 0) return

            val planned = current.items[index]
            val source = planned.source
            val visible = if (source == null) emptySet() else patchBundleRepository
                .offeredBundleInfoFlow(planned.packageName, source.version, source.versionCode).first()
                .mapTo(mutableSetOf()) { it.uid }
            val context = source?.let { knownPatchFailures.prepare(it.failureInput(planned.packageName)) }
            val failed = knownFailures(context, knownPatchFailures.records.value)
            val selection = planned.selection.filterKeys { it in visible }.mapValues { (uid, names) ->
                names.filterTo(linkedSetOf()) { "$uid:$it" !in failed || knownFailureRetryKey(context, "$uid:$it") in planned.knownFailureRetries }
            }
            if (selection.values.all { it.isEmpty() }) {
                updateItem(index) { it.copy(state = BatchItemState.NO_PATCHES) }
                continue
            }
            // The persistence scope must also exclude hidden sources, preserving their choices.
            val item = planned.copy(selection = selection, bundles = planned.bundles.filter { it.uid in visible })
            updateItem(index) { item.copy(state = BatchItemState.RUNNING) }
            runItem(index, item)
        }
    }

    private suspend fun runItem(index: Int, item: BatchPatchItem) {
        // Published before the APK is prepared: copying one takes seconds, and until a run of
        // its own exists the screen would still be showing the previous app's finished steps
        val runProgress = withContext(Dispatchers.IO) {
            PatchRunProgress(
                context = app,
                scope = scope,
                totalPatches = item.patchCount,
                splitStepActive = item.source?.isSplitArchive() == true
            )
        }
        runProgress.startStallWatch()
        _state.update { it.copy(activeIndex = index, activeRun = runProgress) }

        val selectedApp = withContext(Dispatchers.IO) { materialize(item) }
        if (selectedApp == null) {
            runProgress.finish()
            updateItem(index) {
                it.copy(
                    state = BatchItemState.FAILED,
                    message = app.getString(R.string.batch_patch_source_unavailable)
                )
            }
            return
        }

        // Named after the item rather than the app, so two clones of it never share a path
        val outputFile = workspace.resolve("${item.id}.apk")

        // Only the sources actually contributing patches, so narrowing an app to one source
        // is reflected in the log as well as on the card
        val args = PatcherWorker.Args(
            input = selectedApp,
            output = outputFile.path,
            selectedPatches = item.selection,
            options = item.options.sanitizeForPatcher(),
            logger = runProgress.logger,
            onPatchCompleted = { runProgress.onPatchCompleted() },
            onPatchFailed = runProgress::onPatchFailed,
            onPatchingRestarted = { remaining -> runProgress.onRestart(remaining) },
            setInputFile = { file, needsSplit, merged ->
                runProgress.updateSplitRequirement(file, needsSplit, merged)
            },
            onProgress = runProgress::onProgress,
            onTaskProgress = runProgress::onTaskProgress,
            patchSources = item.patchSources,
            announceCompletion = false,
            queuePosition = _state.value?.let { it.processed to it.total },
            useMount = item.useMount
        )

        val workId = workerRepository.launchExpedited<PatcherWorker, PatcherWorker.Args>(args)
        activeWorkId = workId

        try {
            val finished = workManager.getWorkInfoByIdFlow(workId).first { it?.state?.isFinished == true }
            // The notification is the queue's again now that the worker is done, saying what comes next
            val preparingNext = _state.value?.runnable?.isNotEmpty() == true
            foregroundHold?.update(
                OngoingContent(
                    app.getText(R.string.batch_patch_title),
                    app.getText(if (preparingNext) R.string.batch_patch_preparing_next else R.string.patcher_notification_text)
                )
            )

            when (finished?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val recipe = try {
                        args.appliedRecipe ?: withContext(Dispatchers.IO) {
                            readAppliedPatchRecipe(outputFile)
                        }
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        Log.e(TAG, "Cannot recover the signed output's actual patch recipe", failure)
                        updateItem(index) { it.copy(state = BatchItemState.FAILED, message = failure.message) }
                        return
                    }
                    val completedItem=item.withAppliedRecipe(recipe)
                    val retained = withContext(NonCancellable) {
                        finishSuccessfulItem(completedItem, selectedApp, outputFile)
                    }
                    updateItem(index) {
                        completedItem.copy(state = BatchItemState.SUCCEEDED, patchedBuild = retained, message = null)
                    }
                }

                WorkInfo.State.CANCELLED -> updateItem(index) { it.copy(state = BatchItemState.CANCELLED) }

                else -> {
                    val failure = finished?.outputData
                        ?.getString(PatcherWorker.FAILURE_MESSAGE_KEY)
                        ?.lineSequence()
                        ?.firstOrNull { it.isNotBlank() }
                    updateItem(index) {
                        it.copy(state = BatchItemState.FAILED, message = failure, failedPatch = runProgress.failedPatch)
                    }
                }
            }
        } finally {
            // Cancelling the queue cancels this coroutine mid-wait, and the stall watch runs on
            // the application scope, so it would otherwise poll forever
            withContext(NonCancellable) {
                args.inputUse.abandonBeforeStart()
                workerRepository.workerInputs.remove(workId, args)
                args.inputUse.awaitReleased()
                activeWorkId = null
                runProgress.finish()
                // Retaining the APKs takes seconds, and this run has nothing left to show but
                // completed steps. The screen switches to its between-apps state instead
                _state.update { it.copy(activeIndex = null, activeRun = null) }
                withContext(Dispatchers.IO) {
                    cleanupInput(selectedApp)
                    workspace.resolve("${item.id}-original.apk").delete()
                }
            }
        }
    }

    /**
     * Copies the source APK into the workspace when it lives outside it. Installed apps are
     * materialized here rather than during planning so excluded items never pay the cost.
     */
    private fun materialize(item: BatchPatchItem): SelectedApp? {
        val source = item.source ?: return null
        workspace.mkdirs()

        return when (source) {
            is BatchApkSource.SavedOriginal -> SelectedApp.Local(
                packageName = item.packageName,
                version = source.version,
                versionCode = source.versionCode,
                file = source.file,
                temporary = false
            )

            is BatchApkSource.UserFile -> SelectedApp.Local(
                packageName = item.packageName,
                version = source.version,
                versionCode = source.versionCode,
                file = source.file,
                temporary = false
            )

            is BatchApkSource.Installed -> try {
                val target = if (source.isSplit) {
                    workspace.resolve("${item.packageName}_installed.apks")
                        .also { SplitApkPreparer.writeApksArchive(source.apkPath, source.splitPaths, it) }
                } else {
                    workspace.resolve("${item.packageName}_installed.apk")
                        .also { File(source.apkPath).copyTo(it, overwrite = true) }
                }
                SelectedApp.Local(
                    packageName = item.packageName,
                    version = source.version,
                    versionCode = source.versionCode,
                    file = target,
                    temporary = true,
                    fromInstalledDevice = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to materialize installed APK for ${item.packageName}", e)
                null
            }
        }
    }

    /** Whether the source needs merging, known before it is copied into the workspace. */
    private fun BatchApkSource.isSplitArchive(): Boolean = when (this) {
        is BatchApkSource.Installed -> isSplit
        is BatchApkSource.SavedOriginal -> SplitApkPreparer.isSplitArchive(file)
        is BatchApkSource.UserFile -> SplitApkPreparer.isSplitArchive(file)
    }

    private fun cleanupInput(selectedApp: SelectedApp) {
        val local = selectedApp as? SelectedApp.Local ?: return
        if (local.temporary) local.file.delete()
    }

    /**
     * Retains the original and patched APKs and records the patched app, so the summary can
     * install it and the app shows up on the home screen exactly like a single-app run.
     *
     * @return the build the summary installs from, or null when the patched APK is gone.
     */
    private suspend fun finishSuccessfulItem(
        item: BatchPatchItem,
        selectedApp: SelectedApp,
        outputFile: File
    ): PatchedBuild? = withContext(Dispatchers.IO) {
        if (!outputFile.exists()) return@withContext null

        val patchedInfo = pm.getPackageInfo(outputFile)
        val version = patchedInfo?.versionName?.takeUnless { it.isBlank() }
            ?: item.version
            ?: "unspecified"
        val currentPackageName = patchedInfo?.packageName ?: item.packageName

        val mergedOriginal = workspace.resolve("${item.id}-original.apk").takeIf { !item.useMount && it.isFile }
        val original = mergedOriginal?.let { SelectedApp.Local(item.packageName,
            selectedApp.version ?: item.version ?: "unknown", selectedApp.versionCode, it, temporary = true) } ?: selectedApp
        saveOriginalApk(item, original)

        val isClone = item.producesCloneAs(currentPackageName)
        // A configuration belongs to the card it was built from, which is the app itself unless
        // the run produced a copy, whatever package name the patches settled on
        val configurationKey = if (isClone) currentPackageName else item.configurationKey

        // Only an APK that cannot be read leaves the build to be named after the queued app
        val exportData = patchedInfo?.let { pm.exportData(it, item.patchSources, isClone, item.version) }
            ?: ApkExportData(item.appName, currentPackageName, version, item.patchSources, isClone)

        // What the user chose to patch with is recorded whether the APK is kept: the
        // two are separate settings, and the summary can still install from the workspace
        patchSelectionRepository.updateSelection(
            configurationKey,
            item.selection,
            // Scoped to every source the plan considered, so a source the user ended up taking
            // nothing from has its stale selection cleared rather than left behind
            scope = item.bundles.mapTo(mutableSetOf()) { it.uid }.ifEmpty { item.selection.keys }
        )
        // Without this the next plan cannot tell a patch the user deselected from one that was
        // added since, and would keep re-enabling every deselected default
        item.bundles.forEach { bundle ->
            patchSelectionRepository.saveSeenPatches(
                packageName = configurationKey,
                bundleUid = bundle.uid,
                patchNames = bundle.patchNames
            )
        }
        // Options simple mode keeps on its preference screen stay there, rather than replacing
        // the ones saved for the sources they name
        if (item.selectedByHand) patchOptionsRepository.saveOptions(configurationKey, item.options)

        if (!prefs.savePatchedApks.get()) {
            // Retention is off, so the patched APK only lives long enough for the summary
            // to install it and is dropped together with the workspace
            return@withContext PatchedBuild(outputFile, exportData)
        }

        val retained = fs.getPatchedAppFile(currentPackageName, version)
        val stored = runCatching {
            retained.parentFile?.mkdirs()
            // Staged, so a reader that refreshes while the copy runs never opens a half
            // written archive at the path the app already reports as the saved build
            copyThroughStaging(outputFile, retained)
            retained
        }.getOrElse {
            Log.w(TAG, "Failed to retain patched APK for ${item.packageName}", it)
            outputFile
        }

        val selectionPayload = item.appliedRecipe?.payload ?: patchBundleRepository.snapshotSelection(item.selection)
        installedAppRepository.addOrUpdate(
            currentPackageName,
            item.packageName,
            isClone,
            version,
            InstallType.SAVED,
            item.selection,
            selectionPayload
        )

        PatchedBuild(stored, exportData)
    }

    /**
     * Keeps the unpatched APK after a successful run. The caller prefers the staged merged
     * mono-APK over its split archive; failed and abandoned runs never reach this method.
     */
    private suspend fun saveOriginalApk(
        item: BatchPatchItem,
        selectedApp: SelectedApp
    ) {
        val file = (selectedApp as? SelectedApp.Local)?.file?.takeIf { it.exists() } ?: return

        val existing = originalApkRepository.get(item.packageName)
        val retainedInfo = existing?.let { pm.getPackageInfo(File(it.filePath)) }
        val splitArchive = SplitApkPreparer.isSplitArchive(file)
        val sourceInfo = pm.getPackageInfo(file)
        if (sourceInfo == null && !splitArchive) return
        val version = sourceInfo?.versionName?.takeUnless { it.isBlank() } ?: selectedApp.version
        if (splitArchive) {
            if (originalApkRepository.hasOriginal(item.packageName, version, file)) return
        } else if (existing != null && existing.version == version && retainedInfo != null && sourceInfo != null &&
            pm.getVersionCode(retainedInfo) == pm.getVersionCode(sourceInfo)) return

        runCatching {
            originalApkRepository.saveOriginalApk(
                packageName = item.packageName,
                version = version,
                sourceFile = file,
                moveSource = selectedApp.temporary && !item.useMount,
                forMount = item.useMount
            )
        }.onFailure { Log.w(TAG, "Failed to save original APK for ${item.packageName}", it) }
    }

    private fun markRemainingCancelled() {
        _state.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.state == BatchItemState.READY || item.state == BatchItemState.RUNNING) {
                        item.copy(state = BatchItemState.CANCELLED)
                    } else {
                        item
                    }
                }
            )
        }
    }

    private fun placeholder(target: BatchTarget) = BatchPatchItem(
        target = target,
        appName = target.packageName,
        source = null,
        selection = emptyMap(),
        options = emptyMap(),
        bundles = emptyList(),
        state = BatchItemState.NEEDS_APK
    )

    private fun updateItem(index: Int, transform: (BatchPatchItem) -> BatchPatchItem) {
        _state.update { state ->
            state.copy(
                items = state.items.mapIndexed { i, item -> if (i == index) transform(item) else item }
            )
        }
    }

    /**
     * Applies [transform] only when a run exists. Progress callbacks arrive from the patcher
     * worker's own coroutines, so the compare-and-set loop below keeps concurrent updates
     * from overwriting each other.
     */
    private inline fun MutableStateFlow<BatchRunState?>.update(
        transform: (BatchRunState) -> BatchRunState
    ) {
        while (true) {
            val current = value ?: return
            val updated = transform(current)
            if (compareAndSet(current, updated)) return
        }
    }

}
