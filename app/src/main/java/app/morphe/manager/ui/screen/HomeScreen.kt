/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen

import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.data.room.apps.installed.supportsMount
import app.morphe.manager.data.room.apps.installed.trackingKey
import app.morphe.manager.domain.batch.BatchTarget
import app.morphe.manager.domain.manager.*
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.model.navigation.Patcher
import app.morphe.manager.ui.screen.home.*
import app.morphe.manager.ui.screen.settings.system.InstallerFlowDialogs
import app.morphe.manager.ui.screen.settings.system.PrePatchInstallerDialog
import app.morphe.manager.ui.screen.shared.InstallQueueRequest
import app.morphe.manager.ui.screen.shared.rememberInstallQueue
import app.morphe.manager.ui.screen.sources.LocalNetworkSourceAccess
import app.morphe.manager.ui.theme.SeasonalEvent
import app.morphe.manager.ui.viewmodel.*
import app.morphe.manager.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Home Screen with 5-section layout.
 */
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onStartQuickPatch: (Patcher.ViewModelParams) -> Unit,
    onStartBatchPatch: (List<BatchTarget>) -> Unit,
    homeViewModel: HomeViewModel = koinViewModel(),
    prefs: PreferencesManager = koinInject(),
    homeAppButtonPrefs: HomeAppButtonPreferences = koinInject(),
    usingMountInstallState: MutableState<Boolean>,
    bundleUpdateProgress: PatchBundleRepository.BundleUpdateProgress?,
    onboardingState: OnboardingState? = null,
    globalOnboardingState: GlobalOnboardingState? = null,
    appInfoTriggerPackage: String? = null,
    onAppInfoTriggerHandled: () -> Unit = {},
    installViewModel: InstallViewModel = koinViewModel()
) {
    LaunchedEffect(appInfoTriggerPackage) {
        appInfoTriggerPackage?.let { homeViewModel.openInstalledAppInfo(it); onAppInfoTriggerHandled() }
    }
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val sourcesLoadingText = stringResource(R.string.home_sources_are_loading)
    val otherAppsText = stringResource(R.string.home_other_apps)

    // Dialog states
    val showUpdateDetailsDialog = remember { mutableStateOf(false) }

    // Patches dialog state (swipe-right on app card)
    val patchesSheetItem = remember { mutableStateOf<HomeAppItem?>(null) }

    // Pull to refresh state
    val isRefreshing by homeViewModel.isRefreshing.collectAsStateWithLifecycle()

    // Reactively observe the preference so the greeting updates immediately
    val showGreetingPhrases by prefs.showGreetingPhrases.getAsState()
    val showRepatchNotice by prefs.showRepatchNotice.getAsState()
    val seasonalThemes by prefs.seasonalThemes.getAsState()
    val seasonalEvent = remember(seasonalThemes) { SeasonalEvent.on().takeIf { seasonalThemes } }

    // Re-evaluated whenever showPatchingPhrases or the seasonal event changes
    var greetingResId by remember(showGreetingPhrases, seasonalEvent) {
        mutableStateOf(
            if (showGreetingPhrases) HomeAndPatcherMessages.getHomeMessage(context, seasonalEvent) else null
        )
    }
    val greetingMessage = greetingResId?.let { stringResource(it) }

    // Handle refresh with haptic feedback.
    // showPatchingPhrases is read from the reactive state captured in the
    // outer scope so the lambda always uses the current value at invocation.
    val onRefresh: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        HomeAndPatcherMessages.resetHomeMessage()
        greetingResId = if (showGreetingPhrases) HomeAndPatcherMessages.getHomeMessage(context, seasonalEvent) else null
        homeViewModel.refresh()
    }

    // Collect state flows
    val availablePatches by homeViewModel.availablePatches.collectAsStateWithLifecycle(0)
    // Atomic home state - null means pipeline is still initializing (shimmer)
    val homeAppState by homeViewModel.apps.homeAppState.collectAsStateWithLifecycle()
    val library by homeViewModel.apps.library.collectAsStateWithLifecycle()
    val librarySelection by homeViewModel.apps.librarySelection.collectAsStateWithLifecycle()
    val storeListings by homeViewModel.apps.storeListings.collectAsStateWithLifecycle()
    val playVersions by homeViewModel.apps.playVersions.collectAsStateWithLifecycle()
    val homeAppItems = homeAppState?.visible ?: emptyList()
    val hiddenAppItems = homeAppState?.hidden ?: emptyList()
    val homeAppSortMode = homeAppState?.sortMode ?: HomeAppSortMode.MANUAL
    val homeAppCategoryState = homeAppState?.categoryState ?: HomeAppCategoryState(emptyList(), emptyMap())
    val homeAppCategoryViewMode = homeAppState?.categoryViewMode ?: HomeAppCategoryViewMode.ALL_APPS
    val showCategoryViewSwitcher = homeAppState?.showCategoryViewSwitcher == true
    val homeAppSourceGroups = homeAppState?.sourceGroups ?: emptyList()
    val bundlePipelineLoading = homeAppState == null
    val bundleState by homeViewModel.patchBundleRepository.bundleState.collectAsStateWithLifecycle()
    val sourcesLoading = when (val state = bundleState) {
        is app.morphe.manager.domain.repository.PatchBundleRepository.BundleState.Loading -> true
        is app.morphe.manager.domain.repository.PatchBundleRepository.BundleState.Ready ->
            availablePatches <= 0 && state.sources.values.any { it.enabled }
    }
    val installsChecking = homeAppItems.any { it.installedApp != null && it.isInstallStatePending } ||
            hiddenAppItems.any { it.installedApp != null && it.isInstallStatePending }
    val showOtherAppsButton by homeViewModel.apps.showOtherAppsButton.collectAsStateWithLifecycle()
    val showSearchButton by homeViewModel.apps.showSearchButton.collectAsStateWithLifecycle()
    val batchRun by homeViewModel.batchRun.collectAsStateWithLifecycle()
    val savedBatchQueue by homeViewModel.savedBatchQueue.collectAsStateWithLifecycle()
    val showSortButtonPref by homeAppButtonPrefs.showSortButton.collectAsStateWithLifecycle()
    val homeAppFilterModePref by homeAppButtonPrefs.filterMode.collectAsStateWithLifecycle()
    val useExpertMode by prefs.useExpertMode.getAsState()

    // Gesture hint: shown once per bundle addition, in-memory
    val showGestureHint by homeViewModel.apps.showSwipeGestureHint.collectAsStateWithLifecycle()

    val isDeviceRooted = homeViewModel.rootInstaller.isDeviceRooted()
    if (!isDeviceRooted) {
        // Non-root: always standard install, sync the state
        usingMountInstallState.value = false
        homeViewModel.patchFlow.usingMountInstall = false
    } else {
        // Root: the value is set by resolvePrePatchInstallerChoice() via the dialog,
        // just keep usingMountInstallState in sync for PatcherScreen to read
        usingMountInstallState.value = homeViewModel.patchFlow.usingMountInstall
    }

    // Set up HomeViewModel
    LaunchedEffect(Unit) {
        homeViewModel.patchFlow.onStartQuickPatch = onStartQuickPatch
    }

    val openApkPicker = rememberAdaptiveFilePicker(
        mimeTypes = APK_FILE_MIME_TYPES,
        onResult = { uri -> uri?.let { homeViewModel.patchFlow.handleApkSelection(it) } }
    )

    val openBundlePicker = rememberAdaptiveMultiFilePicker(
        mimeTypes = MPP_FILE_MIME_TYPES,
        onResult = homeViewModel::pickBundles
    )

    val installAppsPermissionLauncher = rememberLauncherForActivityResult(
        contract = RequestInstallAppsContract
    ) { homeViewModel.showAndroid11Dialog = false }

    val startInstallQueue = rememberInstallQueue(
        installViewModel = installViewModel,
        completedPluralRes = R.plurals.batch_reinstall_summary
    )

    val startBatchReinstall: (List<HomeAppItem>) -> Unit = { items ->
        val requests = items.mapNotNull { item ->
            val installed = item.installedApp ?: return@mapNotNull null
            val savedFile = item.savedApkFile ?: return@mapNotNull null
            InstallQueueRequest(
                file = savedFile,
                originalPackageName = installed.originalPackageName,
                mountPackageName = installed.currentPackageName.takeIf { installed.supportsMount },
                onPersistApp = { packageName, installType ->
                    homeViewModel.persistReinstalledApp(installed, packageName, installType)
                },
                onInstalled = { packageName ->
                    homeViewModel.apps.notifyAppStateChanged(packageName)
                }
            )
        }
        startInstallQueue(requests)
    }

    // Only the apps a rebuild actually moves on: one the sources still cover at its installed
    // version comes back from patching exactly as it went in, however its card is badged
    val repatchableApps = remember(homeAppItems) { homeAppItems.filter { it.showsUpdateBadge } }

    val batchInProgressText = stringResource(R.string.batch_patch_in_progress)
    val startBatchPatch: (List<HomeAppItem>) -> Unit = { items ->
        // A card that stands for an install queues that install, so cloned copies are each
        // rebuilt as themselves rather than collapsing into the app they came from
        val targets = items.map { item ->
            BatchTarget(
                packageName = item.packageName,
                repatchedPackageName = item.installedApp?.trackingKey
            )
        }
        when {
            availablePatches <= 0 -> context.toast(sourcesLoadingText)
            homeViewModel.android11BugActive -> homeViewModel.showAndroid11Dialog = true
            targets.isEmpty() -> Unit
            // A live queue keeps its own selection, so open it instead of swapping the apps
            homeViewModel.batchPatchRunning -> {
                context.toast(batchInProgressText)
                onStartBatchPatch(targets)
            }
            else -> onStartBatchPatch(targets)
        }
    }



    // Check for manager update
    val hasManagerUpdate = !homeViewModel.updatedManagerVersion.isNullOrEmpty()

    // Sources on a home server are out of reach on Android 17 until the local network is allowed
    LocalNetworkSourceAccess(homeViewModel.patchBundleRepository)

    val blockedSources by homeViewModel.patchBundleRepository.blockedSources.collectAsStateWithLifecycle(emptyMap())
    val hasBlockedSources = blockedSources.isNotEmpty()

    // Sources built for a newer patcher than this manager ships. They cannot be loaded or patched
    // with until the app is updated, so surface it instead of leaving the source silently broken
    val outdatedManagerSources by homeViewModel.patchBundleRepository.outdatedManagerSources.collectAsStateWithLifecycle()
    val hasOutdatedManagerSources = outdatedManagerSources.isNotEmpty()

    // Reading these took the process down, so they are skipped until the file changes. Nothing
    // else on this screen would explain why their patches are suddenly gone
    val heldBackSources by homeViewModel.patchBundleRepository.heldBackSources.collectAsStateWithLifecycle()
    val hasHeldBackSources = heldBackSources.isNotEmpty()

    // Manager update details dialog
    if (showUpdateDetailsDialog.value) {
        ManagerChangelogDialog(
            onDismiss = { showUpdateDetailsDialog.value = false },
            expectsUpdate = true
        )
    }

    // Android 11 Dialog
    if (homeViewModel.showAndroid11Dialog) {
        Android11Dialog(
            onDismissRequest = { homeViewModel.showAndroid11Dialog = false },
            onContinue = { installAppsPermissionLauncher.launch(context.packageName) }
        )
    }

    // All dialogs
    HomeDialogs(
        homeViewModel = homeViewModel,
        storagePickerLauncher = { openApkPicker() },
        openBundlePicker = { openBundlePicker() },
        patchesItem = patchesSheetItem,
        globalOnboardingState = globalOnboardingState
    )

    InstallerFlowDialogs(installViewModel = installViewModel)

    // Pre-patching mode selection dialog for root-capable devices.
    // This dialog must appear before patching starts because the patch mode determines
    // which patches are applied.
    if (homeViewModel.patchFlow.showPrePatchInstallerDialog) {
        PrePatchInstallerDialog(
            packageName = homeViewModel.patchFlow.pendingPackageName,
            onSelectMount = { homeViewModel.patchFlow.resolvePrePatchInstallerChoice(useMount = true) },
            onSelectStandard = { homeViewModel.patchFlow.resolvePrePatchInstallerChoice(useMount = false) },
            onDismiss = homeViewModel.patchFlow::dismissPrePatchInstallerDialog
        )
    }

    // Main content with pull-to-refresh
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            SectionsLayout(
                notifications = HomeNotificationsUi(
                    managerUpdate = AlertState(hasManagerUpdate) { showUpdateDetailsDialog.value = true },
                    outdatedManager = AlertState(hasOutdatedManagerSources) { homeViewModel.showBundleManagementSheet = true },
                    heldBackSources = AlertState(hasHeldBackSources) { homeViewModel.showBundleManagementSheet = true },
                    blockedSources = AlertState(hasBlockedSources) { homeViewModel.showBundleManagementSheet = true },
                    meteredSkipped = AlertState(homeViewModel.updatesSkippedDueToMetered, homeViewModel::downloadPendingUpdates),
                    repatchAvailable = RepatchAlertState(
                        count = repatchableApps.size,
                        visible = showRepatchNotice,
                        onShow = { startBatchPatch(repatchableApps) }
                    ),
                    // Reopened on its own apps, so the batch screen keeps the run instead of
                    // planning a new one
                    batchQueue = BatchQueueAlertState(batchRun,
                        preparedCount = batchRun?.takeIf { it.phase == app.morphe.manager.domain.batch.BatchPhase.PREFLIGHT }
                            ?.items?.size ?: savedBatchQueue?.targets?.size ?: 0) {
                        if (batchRun != null) batchRun?.let { onStartBatchPatch(it.targets) }
                        else savedBatchQueue?.let { onStartBatchPatch(it.targets) }
                    },
                    bundleUpdate = BundleUpdateState(
                        visible = homeViewModel.showBundleUpdateSnackbar,
                        status = homeViewModel.snackbarStatus,
                        progress = bundleUpdateProgress
                    )
                ),
                apps = HomeAppListUi(
                    visible = homeAppItems,
                    hidden = hiddenAppItems,
                    installedAppsLoading = bundlePipelineLoading || homeViewModel.installedAppsLoading,
                    backgroundLoading = when {
                        sourcesLoading -> R.string.home_loading_sources
                        installsChecking -> R.string.home_checking_installs
                        else -> null
                    },
                    showGestureHint = showGestureHint,
                    sortMode = homeAppSortMode,
                    filterMode = HomeAppFilterMode.fromPreference(homeAppFilterModePref),
                    categoryState = homeAppCategoryState,
                    categoryViewMode = homeAppCategoryViewMode,
                    showCategoryViewSwitcher = showCategoryViewSwitcher,
                    sourceGroups = homeAppSourceGroups,
                    library = library,
                    librarySelection = librarySelection,
                    storeListings = storeListings,
                    playVersions = playVersions
                ),
                appActions = HomeAppActions(
                    onAppClick = { item ->
                        homeViewModel.handleAppClick(
                            packageName = item.packageName,
                            availablePatches = availablePatches,
                            bundleUpdateInProgress = false,
                            android11BugActive = homeViewModel.android11BugActive,
                            installedApp = item.installedApp
                        )
                        item.installedApp?.let {
                            homeViewModel.openInstalledAppInfo(it.currentPackageName)
                        }
                    },
                    onHideApp = { packageName -> homeViewModel.apps.hideApp(packageName) },
                    onHideMultiple = { packageNames -> packageNames.forEach { homeViewModel.apps.hideApp(it) } },
                    onUninstallMultiple = { items -> homeViewModel.uninstallApps(items) },
                    onReinstallMultiple = { items -> startBatchReinstall(items) },
                    onPatchMultiple = { items -> startBatchPatch(items) },
                    onUnhideApp = { packageName -> homeViewModel.apps.unhideApp(packageName) },
                    onShowPatches = { item -> patchesSheetItem.value = item },
                    onGestureHintShown = {
                        homeViewModel.apps.markSwipeGestureHintShown()
                        if (onboardingState != null && onboardingState.swipeActive) {
                            scope.launch {
                                delay(600.milliseconds)
                                if (onboardingState.swipeActive) homeViewModel.apps.triggerSwipeGestureHint()
                            }
                        }
                    },
                    onSaveOrder = { packageNames -> homeViewModel.apps.saveAppOrder(packageNames) },
                    onSaveSourceOrder = { sourceUid, packageNames ->
                        homeViewModel.apps.saveAppSourceOrder(sourceUid, packageNames)
                    },
                    onResetOrder = { homeViewModel.apps.resetAppOrder() },
                    onResetSourceOrder = { sourceUid -> homeViewModel.apps.resetAppSourceOrder(sourceUid) },
                    onSaveSourceGroupOrder = { sourceUids ->
                        homeViewModel.apps.saveAppSourceGroupOrder(sourceUids)
                    },
                    onSortModeChange = { mode -> homeViewModel.apps.setAppSortMode(mode) },
                    onFilterModeChange = { mode ->
                        homeAppButtonPrefs.setFilterMode(mode.name.takeIf { mode.isActive })
                    },
                    onCategoryViewModeChange = { mode -> homeViewModel.apps.setAppCategoryViewMode(mode) },
                    onCreateCategory = { name -> homeViewModel.apps.createAppCategory(name) },
                    onRenameCategory = { categoryId, name ->
                        homeViewModel.apps.renameAppCategory(categoryId, name)
                    },
                    onDeleteCategory = { categoryId -> homeViewModel.apps.deleteAppCategory(categoryId) },
                    onSaveCategoryOrder = { categoryIds ->
                        homeViewModel.apps.saveAppCategoryOrder(categoryIds)
                    },
                    onToggleCategoryCollapsed = { categoryId ->
                        homeViewModel.apps.toggleAppCategoryCollapsed(categoryId)
                    },
                    onToggleSourceGroupCollapsed = { sourceUid ->
                        homeViewModel.apps.toggleAppSourceGroupCollapsed(sourceUid)
                    },
                    onAssignAppsToCategory = { packageNames, categoryId ->
                        homeViewModel.apps.assignAppsToCategory(packageNames, categoryId)
                    }
                ),
                chromeActions = HomeChromeActions(
                    onOtherAppsClick = {
                        if (availablePatches <= 0) {
                            context.toast(sourcesLoadingText)
                        } else {
                            homeViewModel.patchFlow.chooseAnyApp(otherAppsText)
                        }
                    },
                    onBundlesClick = { homeViewModel.showBundleManagementSheet = true },
                    onSettingsClick = onSettingsClick,
                    onRefreshGreeting = onRefresh,
                    onLibrarySelectionChange = homeViewModel.apps::setLibrarySelection
                ),
                chromeFlags = HomeChromeFlags(
                    showSearchButton = showSearchButton,
                    showSortButton = showSearchButton && showSortButtonPref,
                    showOtherAppsButton = showOtherAppsButton,
                    isExpertModeEnabled = useExpertMode
                ),
                greetingMessage = greetingMessage,
                onboardingState = onboardingState
            )
        }
    }
}
