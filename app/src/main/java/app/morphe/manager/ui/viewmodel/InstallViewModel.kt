/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import kotlinx.coroutines.CoroutineStart

import android.app.Application
import android.content.*
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morphe.manager.R
import app.morphe.manager.patcher.InstallInputLifetime
import app.morphe.manager.data.room.apps.installed.InstallType
import app.morphe.manager.domain.installer.*
import app.morphe.manager.domain.repository.OriginalApkRepository
import app.morphe.manager.domain.manager.AfterPatching
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.service.ForegroundWork
import app.morphe.manager.notification.OngoingContent
import app.morphe.manager.ui.screen.shared.UserMessages
import app.morphe.manager.util.*
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a running mount install is doing, in the words its button shows. */
@get:StringRes
val MountStage.labelRes: Int
    get() = when (this) {
        MountStage.RESTORING_STOCK -> R.string.mount_stage_restoring_stock
        MountStage.COPYING -> R.string.mount_stage_copying
        MountStage.MOUNTING -> R.string.mounting_ellipsis
    }

/**
 * Centralized view model for all installation operations, mounting/unmounting and exporting.
 * Handles installation with support for multiple installers (Standard, Shizuku, Root, External).
 */
class InstallViewModel : ViewModel(), KoinComponent {
    private val messages: app.morphe.manager.ui.screen.shared.UserMessages by inject()
    private val app: Application by inject()
    private val foregroundWork: ForegroundWork by inject()
    private val pm: PM by inject()
    private val rootInstaller: RootInstaller by inject()
    private val sessionInstaller: SessionInstaller by inject()
    private val installerManager: InstallerManager by inject()
    private val prefs: PreferencesManager by inject()
    private val appDataResolver: AppDataResolver by inject()
    private val originalApkRepository: OriginalApkRepository by inject()
    private val applicationScope: AppCoroutineScope by inject()

    /**
     * Current installation state.
     */
    sealed class InstallState {
        /** Ready to install - shows Install button. */
        data object Ready : InstallState()

        /** Currently installing - shows progress indicator, named by [stage] for a mount install. */
        data class Installing(val stage: MountStage? = null) : InstallState()

        /** Successfully installed - shows Open button. */
        data class Installed(val packageName: String) : InstallState()

        /**
         * Signature conflict detected - shows Uninstall button.
         * [canIgnoreSignatureMismatch] is set on rooted devices, where a module patching the
         * platform signature verification can still install the update over the existing app.
         */
        data class Conflict(
            val packageName: String,
            val canIgnoreSignatureMismatch: Boolean = false
        ) : InstallState()

        /** Installation error - shows error message and retry. */
        data class Error(val message: String) : InstallState()
    }

    /**
     * State for installer unavailability dialog.
     */
    data class InstallerUnavailableState(
        val installerToken: InstallerManager.Token,
        val reason: Int?,
        val canOpenApp: Boolean
    )

    /**
     * Mount operation state.
     */
    enum class MountOperation { UNMOUNTING, MOUNTING }

    private data class MountStockCandidate(
        val file: File,
        val info: PackageInfo
    )

    private data class MountInstallInputs(
        val patchedInfo: PackageInfo,
        val installedInfo: PackageInfo?,
        val stockFiles: List<File>
    )

    var installState by mutableStateOf<InstallState>(InstallState.Ready)
        private set

    var installedPackageName by mutableStateOf<String?>(null)
        private set

    var installerUnavailableDialog by mutableStateOf<InstallerUnavailableState?>(null)
        private set

    var showInstallerSelectionDialog by mutableStateOf(false)
        private set

    private var oneTimeInstallerToken: InstallerManager.Token? = null
    private var selectedInstallerToken: InstallerManager.Token? = null
    private var pendingInstallToken: InstallerManager.Token? = null
    private var pendingAutoUninstallOnConflict: Boolean = false
    private var pendingAllowSignatureMismatch: Boolean = false

    var mountOperation: MountOperation? by mutableStateOf(null)
        private set

    // Pending external install, completed by an observed package broadcast.
    private var pendingExternalInstall: InstallerManager.InstallPlan.External? = null
    private val inputLifetime = InstallInputLifetime {
        try { app.unregisterReceiver(packageReceiver) }
        catch (e: Exception) { Log.e(TAG, "Failed to unregister receiver", e) }
    }
    private var externalInstallBaseline: Pair<Long?, Long?>? = null
    private var externalInstallStartTime: Long? = null
    private var externalPackageWasPresentAtStart: Boolean = false

    // For intent-based fallback monitoring (when session dies on OEM devices)
    private var pendingIntentFallbackPackage: String? = null
    private var pendingIntentFallbackInstallType: InstallType = InstallType.DEFAULT

    // Store pending install params for retry
    private var pendingInstallFile: File? = null
    private var pendingOriginalPackageName: String? = null
    private var pendingPersistCallback: (suspend (String, InstallType) -> Boolean)? = null
    private var installStart: Job? = null
    private var packageResultJob: Job? = null

    // Track current installation type for proper persistence
    var currentInstallType: InstallType = InstallType.DEFAULT
        private set

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    observePackageCompletion(pkg)
                }
            }
        }
    }

    private fun observePackageCompletion(packageName: String) {
        val external = pendingExternalInstall
        val fallback = pendingIntentFallbackPackage
        if (packageName != external?.expectedPackage && packageName != fallback) return
        if (packageResultJob?.isActive == true) return
        packageResultJob = launchDetached {
            // External installers may re-sign or repackage the APK. Their matching package
            // event completes the handoff, as before; intent fallback retains its baseline check.
            val matches = external != null || withContext(Dispatchers.IO) {
                val info = pm.getPackageInfo(packageName) ?: return@withContext false
                isUpdatedSinceBaseline(info)
            }
            if (!matches || pendingExternalInstall !== external || pendingIntentFallbackPackage != fallback) return@launchDetached
            if (fallback == packageName) handleIntentFallbackSuccess(packageName)
            else handleExternalInstallSuccess(packageName)
        }
    }

    init {
        ContextCompat.registerReceiver(
            app,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onCleared() {
        // The package receiver and input lease survive until actual install/persistence completion.
        inputLifetime.close()
    }

    /**
     * Launches install work that must outlive the screen. The system keeps installing after the
     * user walks away, and a result left unrecorded shows the app as never patched.
     */
    private fun launchDetached(block: suspend CoroutineScope.() -> Unit): Job {
        val released = inputLifetime.operation()
        return applicationScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY, block = block).also { job ->
            job.invokeOnCompletion { released() }
            job.start()
        }
    }

    /**
     * [launchDetached] for the install itself, held in the foreground as [title] for as long as
     * it runs, since the system ends a process left without one once the user walks away.
     */
    private fun launchInstall(@StringRes title: Int, block: suspend CoroutineScope.() -> Unit): Job {
        val hold = foregroundWork.hold(OngoingContent(app.getText(title)))
        return launchDetached {
            try {
                block()
            } finally {
                hold.close()
            }
        }
    }

    /**
     * Start installation process using user's preferred installer or prompt for selection.
     *
     * @param allowSignatureMismatch Skips the signature pre-check and lets the platform decide,
     * for rooted devices where a module removes the signature verification.
     */
    fun install(
        outputFile: File,
        originalPackageName: String,
        onPersistApp: suspend (String, InstallType) -> Boolean,
        autoUninstallOnConflict: Boolean = false,
        allowSignatureMismatch: Boolean = false,
        askInstaller: Boolean = true,
        retainInputs: (() -> (() -> Unit))? = null
    ) {
        if (installState is InstallState.Installing || installStart?.isActive == true) return
        inputLifetime.retain(retainInputs)

        // Store for potential retry
        pendingInstallFile = outputFile
        pendingOriginalPackageName = originalPackageName
        pendingPersistCallback = onPersistApp
        pendingInstallToken = oneTimeInstallerToken ?: installerManager.getPreferredToken()
        pendingAutoUninstallOnConflict = autoUninstallOnConflict
        pendingAllowSignatureMismatch = allowSignatureMismatch

        installStart = launchInstall(R.string.installing_ellipsis) {
            if (askInstaller && prefs.afterPatching.get() == AfterPatching.ASK_INSTALLER && oneTimeInstallerToken == null) {
                // Show installer selection dialog
                showInstallerSelectionDialog = true
                return@launchInstall
            }

            installState = InstallState.Installing()

            try {
                // APK metadata reads parse the archive on disk; keep them off the main thread
                val (packageInfo, existingInfo) = withContext(Dispatchers.IO) {
                    val pi = pm.getPackageInfo(outputFile)
                        ?: throw Exception("Failed to load application info")
                    pi to pm.getPackageInfo(pi.packageName)
                }

                val targetPackageName = packageInfo.packageName

                if (existingInfo != null) {
                    // Check version - can't downgrade
                    if (pm.getVersionCode(packageInfo) < pm.getVersionCode(existingInfo)) {
                        Log.i(TAG, "Version downgrade detected - showing conflict")
                        installState = InstallState.Conflict(targetPackageName)
                        return@launchInstall
                    }
                    // Check signature mismatch before launching the installer - avoids
                    // INSTALL_FAILED_UPDATE_INCOMPATIBLE from the system PackageInstaller
                    if (!allowSignatureMismatch) {
                        val mismatch = withContext(Dispatchers.IO) {
                            pm.hasSignatureMismatch(targetPackageName, outputFile)
                        }
                        if (mismatch) {
                            if (!tryAutoUninstallSignatureConflict(targetPackageName)) {
                                Log.i(TAG, "Signature mismatch detected for $targetPackageName - showing conflict")
                                installState = signatureConflictState(targetPackageName)
                                return@launchInstall
                            }
                        }
                    }
                }

                // Plan resolution probes installer availability on disk; keep off main
                val resolved = withContext(Dispatchers.IO) {
                    selectedInstallerToken = oneTimeInstallerToken
                    oneTimeInstallerToken = null
                    installerManager.resolvePlanWithStatus(
                        outputFile,
                        targetPackageName,
                        null,
                        tokenOverride = selectedInstallerToken
                    )
                }

                Log.d(TAG, "Resolved plan: ${resolved.plan::class.java.simpleName}")

                // Check if installer is unavailable
                if (resolved.preferredUnavailable) {
                    val actualToken = selectedInstallerToken ?: resolved.preferredToken
                    when (actualToken) {
                        InstallerManager.Token.Shizuku,
                        InstallerManager.Token.ShizukuPlayStore -> {
                            Log.d(TAG, "Shizuku unavailable, showing dialog")
                            installerUnavailableDialog = InstallerUnavailableState(
                                installerToken = actualToken,
                                reason = resolved.unavailabilityReason,
                                canOpenApp = true
                            )
                            installState = InstallState.Ready
                            selectedInstallerToken = null
                            return@launchInstall
                        }
                        InstallerManager.Token.Root,
                        InstallerManager.Token.RootPlayStore -> {
                            Log.d(TAG, "Root unavailable, showing dialog")
                            installerUnavailableDialog = InstallerUnavailableState(
                                installerToken = actualToken,
                                reason = resolved.unavailabilityReason,
                                canOpenApp = false
                            )
                            installState = InstallState.Ready
                            selectedInstallerToken = null
                            return@launchInstall
                        }
                        else -> {
                            // For other installers, proceed with fallback
                            selectedInstallerToken = null
                        }
                    }
                }

                // Execute the installation plan
                executeInstallPlan(resolved.plan, outputFile, originalPackageName, onPersistApp)

            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                handleInstallError(
                    app.getString(
                        R.string.install_app_fail,
                        e.simpleMessage() ?: e.javaClass.simpleName
                    )
                )
            }
        }
    }

    /**
     * Execute the resolved installation plan.
     */
    private suspend fun executeInstallPlan(
        plan: InstallerManager.InstallPlan,
        outputFile: File,
        originalPackageName: String,
        onPersistApp: suspend (String, InstallType) -> Boolean
    ) {
        currentInstallType = plan.installType()
        pendingInstallToken = plan.installerToken()

        when (plan) {
            InstallerManager.InstallPlan.Internal -> {
                Log.d(TAG, "Using internal (standard) installer")
                performStandardInstall(outputFile, originalPackageName, onPersistApp)
            }

            InstallerManager.InstallPlan.PlayStore -> {
                Log.d(TAG, "Using Play Store installer")
                performPlayStoreInstall(outputFile, onPersistApp)
            }

            InstallerManager.InstallPlan.Root -> {
                Log.d(TAG, "Using root installer")
                performRootInstall(outputFile, asPlayStore = false, onPersistApp)
            }

            InstallerManager.InstallPlan.RootPlayStore -> {
                Log.d(TAG, "Using root Play Store installer")
                performRootInstall(outputFile, asPlayStore = true, onPersistApp)
            }

            InstallerManager.InstallPlan.Shizuku -> {
                Log.d(TAG, "Using Shizuku installer")
                performShizukuInstall(outputFile, onPersistApp)
            }

            InstallerManager.InstallPlan.ShizukuPlayStore -> {
                Log.d(TAG, "Using Shizuku Play Store installer")
                performShizukuPlayStoreInstall(outputFile, onPersistApp)
            }

            is InstallerManager.InstallPlan.External -> {
                Log.d(TAG, "Using external installer: ${plan.installerLabel}")
                launchExternalInstaller(plan)
            }
        }
    }

    private fun InstallerManager.InstallPlan.installerToken(): InstallerManager.Token = when (this) {
        InstallerManager.InstallPlan.Internal -> InstallerManager.Token.Internal
        InstallerManager.InstallPlan.PlayStore -> InstallerManager.Token.PlayStore
        InstallerManager.InstallPlan.Root -> InstallerManager.Token.Root
        InstallerManager.InstallPlan.RootPlayStore -> InstallerManager.Token.RootPlayStore
        InstallerManager.InstallPlan.Shizuku -> InstallerManager.Token.Shizuku
        InstallerManager.InstallPlan.ShizukuPlayStore -> InstallerManager.Token.ShizukuPlayStore
        is InstallerManager.InstallPlan.External -> token
    }

    private fun InstallerManager.InstallPlan.installType(): InstallType = when (this) {
        InstallerManager.InstallPlan.Internal -> InstallType.DEFAULT
        InstallerManager.InstallPlan.PlayStore -> InstallType.PLAY_STORE
        InstallerManager.InstallPlan.Root -> InstallType.ROOT
        InstallerManager.InstallPlan.RootPlayStore -> InstallType.ROOT_PLAY_STORE
        InstallerManager.InstallPlan.Shizuku -> InstallType.SHIZUKU
        InstallerManager.InstallPlan.ShizukuPlayStore -> InstallType.SHIZUKU_PLAY_STORE
        is InstallerManager.InstallPlan.External ->
            if (token is InstallerManager.Token.Component) InstallType.CUSTOM else InstallType.DEFAULT
    }

    /**
     * Internal (native PackageInstaller session) installation.
     * Suspends until the user confirms or cancels.
     * If the OEM kills the session, falls back to [Intent.ACTION_INSTALL_PACKAGE] via
     * the existing external installation monitor.
     */
    private suspend fun performStandardInstall(
        outputFile: File,
        originalPackageName: String,
        onPersistApp: suspend (String, InstallType) -> Boolean
    ) {
        val packageInfo = withContext(Dispatchers.IO) {
            pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")
        }
        val targetPackageName = packageInfo.packageName

        // Unmount if mounted as root
        withContext(Dispatchers.IO) {
            if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(originalPackageName)) {
                rootInstaller.unmount(originalPackageName, recompile = false)
            }
        }

        val result = try {
            sessionInstaller.installInternal(outputFile)
        } catch (_: InstallCancelledException) {
            if (confirmInstallCompleted(outputFile, targetPackageName)) {
                Log.w(TAG, "Install callback reported cancelled but APK SHA-256 verification succeeded for $targetPackageName")
                InstallResult.Success
            } else {
                // User dismissed the dialog and the installed APK does not match the patched APK.
                installState = InstallState.Ready
                return
            }
        } catch (_: SessionDeadException) {
            Log.w(TAG, "Session dead, falling back to intent-based install")
            launchIntentBasedFallback(outputFile, targetPackageName, onPersistApp)
            return
        }

        when (result) {
            InstallResult.Success -> {
                onPersistApp(targetPackageName, InstallType.DEFAULT)
                handleInstallSuccess(targetPackageName)
            }
            is InstallResult.Conflict -> handleConflict(targetPackageName, result.message)
            is InstallResult.Failure -> handleInstallError(
                app.getString(R.string.install_app_fail, result.message ?: "Unknown error")
            )
        }
    }

    /**
     * Intent-based install that asks Android to record Google Play Store as the installer.
     */
    private suspend fun performPlayStoreInstall(
        outputFile: File,
        onPersistApp: suspend (String, InstallType) -> Boolean
    ) {
        val packageInfo = withContext(Dispatchers.IO) {
            pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")
        }
        val targetPackageName = packageInfo.packageName

        launchMonitoredIntentInstall(
            targetPackageName = targetPackageName,
            installType = InstallType.PLAY_STORE,
            installerLabel = app.getString(R.string.home_app_info_install_type_play_store),
            onPersistApp = onPersistApp
        ) {
            sessionInstaller.launchPlayStoreInstall(outputFile)
        }
    }

    /** Silent root install, which records Google Play Store as the installer when [asPlayStore]. */
    private suspend fun performRootInstall(
        outputFile: File,
        asPlayStore: Boolean,
        onPersistApp: suspend (String, InstallType) -> Boolean
    ) {
        val packageInfo = withContext(Dispatchers.IO) {
            pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")
        }
        val targetPackageName = packageInfo.packageName

        try {
            withContext(Dispatchers.IO) {
                if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(targetPackageName)) {
                    rootInstaller.unmount(targetPackageName, recompile = false)
                }
            }
            rootInstaller.installPackage(outputFile, asPlayStore)
        } catch (e: Exception) {
            if (!e.isSignatureRejection()) throw e
            handleConflict(targetPackageName, e.simpleMessage())
            return
        }

        onPersistApp(targetPackageName, if (asPlayStore) InstallType.ROOT_PLAY_STORE else InstallType.ROOT)
        handleInstallSuccess(targetPackageName)
        app.toast(app.getString(R.string.install_app_success))
    }

    private suspend fun confirmInstallCompleted(
        outputFile: File,
        targetPackageName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val installedFile = pm.getApplicationInfo(targetPackageName)?.sourceDir
            ?.takeIf { it.isNotEmpty() }
            ?.let(::File) ?: return@withContext false
        val installedHash = installedFile.sha256OrNull() ?: return@withContext false
        val expectedHash = outputFile.sha256OrNull() ?: return@withContext false
        installedHash == expectedHash
    }

    /**
     * Silent install via Shizuku/Sui.
     */
    private suspend fun performShizukuPlayStoreInstall(
        outputFile: File,
        onPersistApp: suspend (String, InstallType) -> Boolean
    ) {
        val packageInfo = withContext(Dispatchers.IO) {
            pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")
        }
        val targetPackageName = packageInfo.packageName

        withContext(Dispatchers.IO) {
            if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(targetPackageName)) {
                rootInstaller.unmount(targetPackageName, recompile = false)
            }
        }

        Log.d(TAG, "Starting Shizuku Play Store install for $targetPackageName")

        val result = try {
            sessionInstaller.installShizukuAsPlayStore(outputFile, targetPackageName)
        } catch (_: InstallCancelledException) {
            installState = InstallState.Ready
            return
        }

        when (result) {
            InstallResult.Success -> {
                Log.d(TAG, "Shizuku Play Store install successful")
                onPersistApp(targetPackageName, InstallType.SHIZUKU_PLAY_STORE)
                installedPackageName = targetPackageName
                installState = InstallState.Installed(targetPackageName)
                app.toast(app.getString(R.string.install_app_success))
            }
            is InstallResult.Conflict -> handleConflict(targetPackageName, result.message)
            is InstallResult.Failure -> {
                if (confirmInstallCompleted(outputFile, targetPackageName)) {
                    Log.w(TAG, "Shizuku Play Store result failed but APK SHA-256 verification succeeded for $targetPackageName")
                    onPersistApp(targetPackageName, InstallType.SHIZUKU_PLAY_STORE)
                    handleInstallSuccess(targetPackageName)
                    app.toast(app.getString(R.string.install_app_success))
                } else if (result.message.isSignatureRejection()) {
                    handleConflict(targetPackageName, result.message)
                } else {
                    handleInstallError(formatShizukuInstallError(result.message))
                }
            }
        }
    }

    private suspend fun performShizukuInstall(
        outputFile: File,
        onPersistApp: suspend (String, InstallType) -> Boolean
    ) {
        val packageInfo = withContext(Dispatchers.IO) {
            pm.getPackageInfo(outputFile)
                ?: throw Exception("Failed to load application info")
        }
        val targetPackageName = packageInfo.packageName

        withContext(Dispatchers.IO) {
            if (rootInstaller.hasRootAccess() && rootInstaller.isAppMounted(targetPackageName)) {
                rootInstaller.unmount(targetPackageName, recompile = false)
            }
        }

        Log.d(TAG, "Starting Shizuku install for $targetPackageName")

        val result = try {
            sessionInstaller.installShizuku(outputFile, targetPackageName)
        } catch (_: InstallCancelledException) {
            installState = InstallState.Ready
            return
        }

        when (result) {
            InstallResult.Success -> {
                Log.d(TAG, "Shizuku install successful")
                onPersistApp(targetPackageName, InstallType.SHIZUKU)
                installedPackageName = targetPackageName
                installState = InstallState.Installed(targetPackageName)
                app.toast(app.getString(R.string.install_app_success))
            }
            is InstallResult.Conflict -> handleConflict(targetPackageName, result.message)
            is InstallResult.Failure -> {
                if (confirmInstallCompleted(outputFile, targetPackageName)) {
                    Log.w(TAG, "Shizuku result failed but APK SHA-256 verification succeeded for $targetPackageName")
                    onPersistApp(targetPackageName, InstallType.SHIZUKU)
                    handleInstallSuccess(targetPackageName)
                    app.toast(app.getString(R.string.install_app_success))
                } else if (result.message.isSignatureRejection()) {
                    handleConflict(targetPackageName, result.message)
                } else {
                    handleInstallError(formatShizukuInstallError(result.message))
                }
            }
        }
    }

    /**
     * Launch external installer app.
     */
    private fun launchExternalInstaller(plan: InstallerManager.InstallPlan.External) {
        pendingExternalInstall?.let(installerManager::cleanup)

        pendingExternalInstall = plan
        inputLifetime.externalPending(true)
        externalInstallStartTime = System.currentTimeMillis()

        val baselineInfo = pm.getPackageInfo(plan.expectedPackage)
        externalPackageWasPresentAtStart = baselineInfo != null
        externalInstallBaseline = baselineInfo?.let { info ->
            pm.getVersionCode(info) to info.lastUpdateTime
        }

        try {
            // Add FLAG_ACTIVITY_NEW_TASK since we're starting from Application context
            plan.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(plan.intent)
            app.toast(app.getString(R.string.installer_external_launched, plan.installerLabel))
        } catch (e: ActivityNotFoundException) {
            installerManager.cleanup(plan)
            pendingExternalInstall = null
            handleInstallError(app.getString(R.string.install_app_fail, e.simpleMessage()))
            return
        }


    }

    /**
     * Fallback when [SessionDeadException] is caught from [performStandardInstall].
     * Launches [Intent.ACTION_INSTALL_PACKAGE] and monitors for completion via the
     * existing package broadcast receiver. Completion remains pending until a real package event.
     */
    private fun launchIntentBasedFallback(
        outputFile: File,
        targetPackageName: String,
        onPersistApp: suspend (String, InstallType) -> Boolean
    ) {
        launchMonitoredIntentInstall(
            targetPackageName = targetPackageName,
            installType = InstallType.DEFAULT,
            installerLabel = app.getString(R.string.home_app_info_install_type_system_installer),
            onPersistApp = onPersistApp
        ) {
            sessionInstaller.launchIntentInstall(outputFile)
        }
    }

    private fun launchMonitoredIntentInstall(
        targetPackageName: String,
        installType: InstallType,
        installerLabel: String,
        onPersistApp: suspend (String, InstallType) -> Boolean,
        launchInstaller: () -> Unit
    ) {
        pendingPersistCallback = onPersistApp
        currentInstallType = installType

        val baselineInfo = pm.getPackageInfo(targetPackageName)
        externalPackageWasPresentAtStart = baselineInfo != null
        externalInstallBaseline = baselineInfo?.let { pm.getVersionCode(it) to it.lastUpdateTime }
        externalInstallStartTime = System.currentTimeMillis()

        // Use a lightweight sentinel so package broadcasts can be matched without a full
        // External installer plan.
        pendingIntentFallbackPackage = targetPackageName
        inputLifetime.externalPending(true)
        pendingIntentFallbackInstallType = installType

        try {
            launchInstaller()
        } catch (error: Exception) {
            // No activity claims the install intent, or the APK went away before it could be
            // handed over. Either way nothing was launched, so there is nothing to monitor
            if (error !is ActivityNotFoundException && error !is MissingApkException) throw error
            pendingIntentFallbackPackage = null
            pendingIntentFallbackInstallType = InstallType.DEFAULT
            handleInstallError(app.getString(R.string.install_app_fail, error.simpleMessage()))
            return
        }


    }

    private fun handleIntentFallbackSuccess(packageName: String) {
        val installType = pendingIntentFallbackInstallType
        pendingIntentFallbackPackage = null
        pendingIntentFallbackInstallType = InstallType.DEFAULT
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        persistInstall(packageName, installType)
        handleInstallSuccess(packageName)
    }

    /** Records an install that a package broadcast or a monitor saw finish. */
    private fun persistInstall(packageName: String, installType: InstallType) {
        val callback = pendingPersistCallback ?: return
        launchDetached {
            runCatching { callback(packageName, installType) }
                .onFailure { Log.e(TAG, "Failed to persist app data", it) }
        }
    }

    private suspend fun isUpdatedSinceBaseline(packageName: String): Boolean {
        val info = withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) } ?: return false
        return isUpdatedSinceBaseline(info)
    }

    private fun isUpdatedSinceBaseline(info: PackageInfo): Boolean {
        val baseline = externalInstallBaseline
        val startTime = externalInstallStartTime ?: 0L

        val vc = pm.getVersionCode(info)
        val updated = info.lastUpdateTime

        val baseVc = baseline?.first
        val baseUpdated = baseline?.second

        val versionChanged = baseVc != null && vc != baseVc
        val timestampChanged = baseUpdated != null && updated > baseUpdated
        val updatedSinceStart = startTime in 1..updated

        return versionChanged || timestampChanged || updatedSinceStart
    }

    private fun handleExternalInstallSuccess(packageName: String): Boolean {
        val plan = pendingExternalInstall ?: return false
        if (plan.expectedPackage != packageName) return false

        pendingExternalInstall = null
        externalInstallBaseline = null
        externalInstallStartTime = null
        externalPackageWasPresentAtStart = false
        installerManager.cleanup(plan)

        val installType = if (plan.token is InstallerManager.Token.Component) {
            InstallType.CUSTOM
        } else {
            InstallType.DEFAULT
        }

        persistInstall(packageName, installType)
        handleInstallSuccess(packageName)
        messages.show(R.string.installer_external_success, plan.installerLabel)
        return true
    }

    /**
     * Install with root/mount.
     */
    fun installMount(
        outputFile: File,
        inputFile: File?,
        inputIsTemporary: Boolean,
        packageName: String,
        onPersistApp: suspend (String, InstallType) -> Boolean,
        waitForStockInstall: Boolean = false,
        retainInputs: (() -> (() -> Unit))? = null
    ) {
        if (installState is InstallState.Installing) return

        // Leaving the screen must not stop the install between writing the module and
        // recording it, or the app runs a build the record does not know
        inputLifetime.retain(retainInputs)
        launchInstall(R.string.mounting_ellipsis) {
            currentInstallType = InstallType.MOUNT
            installState = InstallState.Installing()
            // Until the new module is staged, a failure leaves the one already there untouched
            val hadModule = runCatching { rootInstaller.hasModuleCopies(packageName) }.getOrDefault(false)
            var moduleStaged = false

            try {
                val inputs = withContext(Dispatchers.IO) {
                    val patchedInfo = pm.getPackageInfo(outputFile)
                        ?: throw Exception("Failed to load application info")
                    val savedOriginal = originalApkRepository.get(packageName)?.filePath?.let(::File)

                    MountInstallInputs(
                        patchedInfo = patchedInfo,
                        installedInfo = pm.getPackageInfo(packageName),
                        stockFiles = listOfNotNull(inputFile, savedOriginal).filter { it.exists() }.distinct()
                    )
                }

                val packageInfo = inputs.patchedInfo
                var stockInfo = inputs.installedInfo
                val label = with(pm) { packageInfo.label() }
                val patchedVersion = packageInfo.versionName?.takeUnless { it.isBlank() } ?: "unknown"
                // Bind mount only replaces the APK file, so matching versionName is enough;
                // versionCodes routinely differ across split-APK variants of the same release.
                fun PackageInfo.matchesPatched() =
                    packageName == this.packageName &&
                            versionName == patchedVersion

                // Either file can be a split archive, read through its base module, so they are
                // only read when the installed app does not already match or does not read as stock
                suspend fun findRestorableStock() = withContext(Dispatchers.IO) {
                    inputs.stockFiles.firstNotNullOfOrNull { file ->
                        rootInstaller.readStockInfo(file)
                            ?.takeIf { it.matchesPatched() }
                            ?.let { MountStockCandidate(file, it) }
                    }
                }
                val restorableStock = if (stockInfo?.matchesPatched() == true) null else findRestorableStock()

                // Only worth waiting for when nothing here can restore the stock app, since the
                // install below reinstalls a matching one and waits for it on its own
                if (waitForStockInstall && restorableStock == null && stockInfo != null && !stockInfo.matchesPatched()) {
                    stockInfo = waitForMatchingInstalledStock(
                        packageName = packageName,
                        versionName = patchedVersion
                    ) ?: stockInfo
                }

                val stockMatchesPatched = stockInfo?.matchesPatched() == true

                val stockCandidate = restorableStock.takeUnless { stockMatchesPatched }

                // Check version mismatch for mount
                val stockVersion = stockInfo?.versionName
                if (stockInfo != null && !stockMatchesPatched && stockCandidate == null) {
                    handleInstallError(
                        app.getString(
                            R.string.mount_version_mismatch_message,
                            patchedVersion,
                            stockVersion ?: "unknown"
                        )
                    )
                    return@launchInstall
                }

                // Check for base APK - app must be installed for mount
                if (stockInfo == null) {
                    if (stockCandidate == null || packageInfo.splitNames.isNotEmpty()) {
                        handleInstallError(app.getString(R.string.installer_hint_generic))
                        return@launchInstall
                    }
                }

                // Install as root
                rootInstaller.install(
                    outputFile,
                    stockCandidate?.file,
                    packageName,
                    patchedVersion,
                    label,
                    repairStock = { findRestorableStock()?.file },
                    onStage = { installState = InstallState.Installing(it) },
                    onStaged = { moduleStaged = true }
                )

                // Mount
                installState = InstallState.Installing(MountStage.MOUNTING)
                rootInstaller.mount(packageName)

                // Persist app data once the mount is in place, since the saved record makes
                // the home screen inspect the app and read whether it is mounted
                onPersistApp(packageInfo.packageName, InstallType.MOUNT)

                // Drop only caller-owned temporary inputs; persistent saved originals must survive
                // for future root mount updates.
                if (inputIsTemporary) inputFile?.delete()

                // Success
                handleInstallSuccess(packageName)

            } catch (e: Exception) {
                Log.e(TAG, "Mount install failed", e)
                handleInstallError(mountFailureMessage(e))

                // The previous module still mounts its own build at the next boot, or stays unmounted
                // when the installed app no longer matches it
                if (moduleStaged || !hadModule) {
                    try {
                        rootInstaller.uninstall(packageName)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Mount a saved patched APK, restoring the matching original APK first when Morphe has it.
     */
    /**
     * Puts a mount another version replaced back from the copies its root module keeps, for an app
     * whose saved builds are gone, see [RootInstaller.restoreFromModule].
     */
    fun restoreMountFromModule(packageName: String, version: String) {
        if (installState is InstallState.Installing) return

        launchInstall(R.string.mounting_ellipsis) {
            currentInstallType = InstallType.MOUNT
            installState = InstallState.Installing()
            try {
                rootInstaller.restoreFromModule(packageName, version) { installState = InstallState.Installing(it) }
                installState = InstallState.Installing(MountStage.MOUNTING)
                rootInstaller.mount(packageName)
                handleInstallSuccess(packageName)
            } catch (e: Exception) {
                // The module is kept, as it holds the only copies to try again from
                Log.e(TAG, "Mount restore failed", e)
                handleInstallError(mountFailureMessage(e))
            }
        }
    }

    private fun mountFailureMessage(e: Exception): String = when (e) {
        is StockAppInstallException -> app.getString(R.string.mount_stock_restore_failed_message)
        is StockApkMismatchException -> app.getString(R.string.mount_stock_mismatch_message)
        else -> app.getString(R.string.install_app_fail, e.simpleMessage() ?: e.javaClass.simpleName)
    }

    fun installSavedMount(
        outputFile: File,
        packageName: String,
        onPersistApp: suspend (String, InstallType) -> Boolean
    ) = installMount(
        outputFile = outputFile,
        inputFile = null,
        inputIsTemporary = false,
        packageName = packageName,
        onPersistApp = onPersistApp,
        waitForStockInstall = true
    )

    /**
     * Mount app (for root installer).
     */
    fun mount(packageName: String, version: String) = viewModelScope.launch {
        val stockVersion = getInstalledStockVersion(packageName, version)
        if (stockVersion != null && stockVersion != version) {
            handleInstallError(
                app.getString(
                    R.string.mount_version_mismatch_message,
                    version,
                    stockVersion
                )
            )
            return@launch
        }

        try {
            mountOperation = MountOperation.MOUNTING
            app.toast(app.getString(R.string.mounting_ellipsis))
            rootInstaller.mount(packageName)
            app.toast(app.getString(R.string.mounted))
        } catch (e: Exception) {
            app.toast(app.getString(R.string.failed_to_mount, e.simpleMessage()))
            Log.e(TAG, "Failed to mount", e)
        } finally {
            mountOperation = null
        }
    }

    /**
     * Unmount app (for root installer).
     */
    fun unmount(packageName: String) = viewModelScope.launch {
        try {
            mountOperation = MountOperation.UNMOUNTING
            app.toast(app.getString(R.string.unmounting_ellipsis))
            rootInstaller.unmount(packageName)
            app.toast(app.getString(R.string.unmounted))
        } catch (e: Exception) {
            app.toast(app.getString(R.string.failed_to_unmount, e.simpleMessage()))
            Log.e(TAG, "Failed to unmount", e)
        } finally {
            mountOperation = null
        }
    }

    /**
     * Remount app (unmount then mount).
     */
    fun remount(packageName: String, version: String) = viewModelScope.launch {
        val stockVersion = getInstalledStockVersion(packageName, version)
        if (stockVersion != null && stockVersion != version) {
            handleInstallError(
                app.getString(
                    R.string.mount_version_mismatch_message,
                    version,
                    stockVersion
                )
            )
            return@launch
        }

        try {
            mountOperation = MountOperation.UNMOUNTING
            app.toast(app.getString(R.string.unmounting_ellipsis))
            rootInstaller.unmount(packageName)
            app.toast(app.getString(R.string.unmounted))

            mountOperation = MountOperation.MOUNTING
            app.toast(app.getString(R.string.mounting_ellipsis))
            rootInstaller.mount(packageName)
            app.toast(app.getString(R.string.mounted))
        } catch (e: Exception) {
            app.toast(app.getString(R.string.failed_to_mount, e.simpleMessage()))
            Log.e(TAG, "Failed to remount", e)
        } finally {
            mountOperation = null
        }
    }

    /**
     * Export patched app to URI.
     */
    fun export(outputFile: File, uri: Uri?, onComplete: (Boolean) -> Unit = {}) = viewModelScope.launch {
        if (uri == null) {
            onComplete(false)
            return@launch
        }

        onComplete(app.exportFileTo(outputFile, uri))
    }

    /**
     * Dismiss the installer unavailable dialog.
     */
    fun dismissInstallerUnavailableDialog() {
        installerUnavailableDialog = null
    }

    /**
     * Open the installer app (Shizuku).
     */
    fun openInstallerApp() {
        val dialog = installerUnavailableDialog ?: return
        when (dialog.installerToken) {
            InstallerManager.Token.Shizuku,
            InstallerManager.Token.ShizukuPlayStore -> {
                val opened = installerManager.openShizukuApp()
                if (!opened) {
                    app.toast(app.getString(R.string.installer_status_shizuku_not_installed))
                }
            }
            else -> {}
        }
    }

    /**
     * Retry installation with preferred installer.
     */
    fun retryWithPreferredInstaller() {
        installerUnavailableDialog = null

        val file = pendingInstallFile ?: return
        val originalPkg = pendingOriginalPackageName ?: return
        val callback = pendingPersistCallback ?: return
        val autoUninstallOnConflict = pendingAutoUninstallOnConflict
        val allowSignatureMismatch = pendingAllowSignatureMismatch

        install(file, originalPkg, callback, autoUninstallOnConflict, allowSignatureMismatch)
    }

    /**
     * Proceed with standard installer instead.
     */
    fun proceedWithFallbackInstaller() {
        installerUnavailableDialog = null

        val file = pendingInstallFile ?: return
        val originalPkg = pendingOriginalPackageName ?: return
        val callback = pendingPersistCallback ?: return

        launchDetached {
            installState = InstallState.Installing()
            currentInstallType = InstallType.DEFAULT
            try {
                performStandardInstall(file, originalPkg, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Fallback install failed", e)
                handleInstallError(
                    app.getString(
                        R.string.install_app_fail,
                        e.simpleMessage() ?: e.javaClass.simpleName
                    )
                )
            }
        }
    }

    /**
     * Dismiss installer selection dialog.
     */
    fun dismissInstallerSelectionDialog() {
        showInstallerSelectionDialog = false
        oneTimeInstallerToken = null
        selectedInstallerToken = null
    }

    /**
     * Proceed with selected installer from dialog.
     */
    fun proceedWithSelectedInstaller(token: InstallerManager.Token) {
        oneTimeInstallerToken = token
        showInstallerSelectionDialog = false

        val file = pendingInstallFile ?: return
        val originalPkg = pendingOriginalPackageName ?: return
        val callback = pendingPersistCallback ?: return
        val autoUninstallOnConflict = pendingAutoUninstallOnConflict
        val allowSignatureMismatch = pendingAllowSignatureMismatch

        install(file, originalPkg, callback, autoUninstallOnConflict, allowSignatureMismatch)
    }

    /**
     * Launches system uninstall UI for [packageName] and suspends until the user confirms.
     * Resets [installState] to [InstallState.Ready] after successful uninstall, or retries
     * the pending install request when [installAfterUninstall] is true.
     */
    fun requestUninstall(packageName: String, installAfterUninstall: Boolean = false) {
        launchDetached {
            try {
                uninstallForPendingInstall(packageName, installAfterUninstall)
                if (installAfterUninstall) {
                    val file = pendingInstallFile
                    val originalPkg = pendingOriginalPackageName
                    val callback = pendingPersistCallback
                    if (file != null && originalPkg != null && callback != null) {
                        val autoUninstallOnConflict = pendingAutoUninstallOnConflict
                        pendingInstallToken?.let { oneTimeInstallerToken = it }
                        installState = InstallState.Ready
                        install(file, originalPkg, callback, autoUninstallOnConflict)
                    } else {
                        Log.w(TAG, "Cannot restart install after uninstall: pending install data missing")
                        installState = InstallState.Ready
                    }
                } else {
                    installState = InstallState.Ready
                }
            } catch (_: UninstallCancelledException) {
                // User dismissed the dialog - keep current state
                if (installAfterUninstall) {
                    installState = signatureConflictState(packageName)
                }
            }
        }
    }

    /**
     * Retry the pending install with the signature check skipped, keeping the app data in place.
     * This only completes on devices where a root module patches the platform signature
     * verification; anywhere else the platform rejects the install and the conflict comes back.
     */
    fun installIgnoringSignatureMismatch() {
        val file = pendingInstallFile ?: return
        val originalPkg = pendingOriginalPackageName ?: return
        val callback = pendingPersistCallback ?: return

        Log.i(TAG, "Retrying install of $originalPkg with the signature check skipped")
        pendingInstallToken?.let { oneTimeInstallerToken = it }
        installState = InstallState.Ready
        install(
            outputFile = file,
            originalPackageName = originalPkg,
            onPersistApp = callback,
            autoUninstallOnConflict = pendingAutoUninstallOnConflict,
            allowSignatureMismatch = true
        )
    }

    private suspend fun uninstallForPendingInstall(
        packageName: String,
        installAfterUninstall: Boolean
    ) {
        if (installAfterUninstall && shouldUseShizukuUninstallForPendingInstall()) {
            installState = InstallState.Installing()
            when (val result = sessionInstaller.uninstallShizuku(packageName)) {
                UninstallResult.Success -> {
                    if (waitUntilPackageRemoved(packageName)) {
                        return
                    }
                    Log.w(TAG, "Shizuku uninstall reported success but $packageName is still installed")
                    installState = InstallState.Conflict(packageName)
                }
                is UninstallResult.Failure -> {
                    if (withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) == null }) {
                        return
                    }
                    Log.w(TAG, "Shizuku uninstall failed for $packageName: ${result.message}")
                    installState = InstallState.Conflict(packageName)
                }
            }
        }

        sessionInstaller.uninstall(packageName)
    }

    private suspend fun tryAutoUninstallSignatureConflict(packageName: String): Boolean {
        if (!pendingAutoUninstallOnConflict) return false
        if (!prefs.autoUninstallWithShizuku.get()) return false
        if (!shouldUseShizukuUninstallForPendingInstall()) return false

        Log.i(TAG, "Auto-uninstalling $packageName before Shizuku auto-install")
        return when (val result = sessionInstaller.uninstallShizuku(packageName)) {
            UninstallResult.Success -> {
                val removed = waitUntilPackageRemoved(packageName)
                if (!removed) {
                    Log.w(TAG, "Shizuku auto-uninstall reported success but $packageName is still installed")
                }
                removed
            }
            is UninstallResult.Failure -> {
                Log.w(TAG, "Shizuku auto-uninstall failed for $packageName: ${result.message}")
                false
            }
        }
    }

    private suspend fun waitUntilPackageRemoved(packageName: String): Boolean {
        val timeoutAt = SystemClock.uptimeMillis() + UNINSTALL_VERIFY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < timeoutAt) {
            if (withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) == null }) {
                return true
            }
            delay(UNINSTALL_VERIFY_POLL_MS.milliseconds)
        }
        return withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) == null }
    }

    private suspend fun shouldUseShizukuUninstallForPendingInstall(): Boolean {
        val token = pendingInstallToken
            ?: oneTimeInstallerToken
            ?: selectedInstallerToken
            ?: installerManager.getPreferredToken()

        val isShizukuInstall = token == InstallerManager.Token.Shizuku ||
                token == InstallerManager.Token.ShizukuPlayStore

        return isShizukuInstall && withContext(Dispatchers.IO) {
            sessionInstaller.shizukuAvailability().available
        }
    }

    /**
     * Resets install state back to [InstallState.Ready].
     * Used when the user dismisses a conflict or error dialog without taking action.
     */
    fun resetInstallState() {
        installState = InstallState.Ready
    }

    fun openApp() {
        installedPackageName?.let { pm.launch(it) }
    }

    fun getPrimaryInstallerToken(): InstallerManager.Token =
        installerManager.getPreferredToken()

    /** Installer entries for the one-time selection dialog shown during install. */
    fun getInstallerOptions(): List<InstallerManager.Entry> {
        return installerManager.listEntries(getPrimaryInstallerToken())
    }

    fun mountsByDefault(): Boolean = installerManager.mountsByDefault()

    fun openShizukuApp(): Boolean = installerManager.openShizukuApp()

    fun getShizukuStatus(): SessionInstaller.ShizukuStatus =
        installerManager.shizukuStatus()

    fun requestShizukuPermission(): Boolean = installerManager.requestShizukuPermission()

    private suspend fun getInstalledStockVersion(packageName: String, expectedVersion: String): String? {
        val currentVersion = withContext(Dispatchers.IO) {
            pm.getPackageInfo(packageName)?.versionName
        }
        if (currentVersion == null || currentVersion == expectedVersion) return currentVersion

        return waitForInstalledStockVersion(packageName, expectedVersion)?.versionName ?: currentVersion
    }

    private suspend fun waitForInstalledStockVersion(
        packageName: String,
        versionName: String
    ): PackageInfo? {
        var matchingInfo: PackageInfo? = null
        withTimeoutOrNull(STOCK_INSTALL_SETTLE_TIMEOUT) {
            while (matchingInfo == null) {
                val info = withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) }
                if (info?.versionName == versionName) {
                    matchingInfo = info
                } else {
                    delay(STOCK_INSTALL_SETTLE_POLL)
                }
            }
        }
        return matchingInfo
    }

    private suspend fun waitForMatchingInstalledStock(
        packageName: String,
        versionName: String
    ): PackageInfo? {
        var matchingInfo: PackageInfo? = null
        withTimeoutOrNull(STOCK_INSTALL_SETTLE_TIMEOUT) {
            while (matchingInfo == null) {
                val info = withContext(Dispatchers.IO) { pm.getPackageInfo(packageName) }
                if (info != null &&
                    info.packageName == packageName &&
                    info.versionName == versionName
                ) {
                    matchingInfo = info
                } else {
                    delay(STOCK_INSTALL_SETTLE_POLL)
                }
            }
        }
        return matchingInfo
    }

    private fun handleInstallSuccess(packageName: String) {
        selectedInstallerToken = null
        installedPackageName = packageName
        appDataResolver.invalidate(packageName)
        installState = InstallState.Installed(packageName)
        inputLifetime.success()
    }

    private fun handleInstallError(message: String) {
        inputLifetime.externalPending(pendingExternalInstall != null || pendingIntentFallbackPackage != null)
        selectedInstallerToken = null
        installState = InstallState.Error(message)
    }

    private fun formatShizukuInstallError(message: String?): String {
        val raw = message?.takeIf { it.isNotBlank() }
        val lower = raw.orEmpty().lowercase()
        val summary = when {
            "permission" in lower || "denied" in lower ->
                app.getString(R.string.installer_shizuku_error_permission)
            "timed out" in lower || "timeout" in lower ->
                app.getString(R.string.installer_shizuku_error_timeout)
            "downgrade" in lower ->
                app.getString(R.string.installer_shizuku_error_downgrade)
            "update_incompatible" in lower ||
                    "signatures do not match" in lower ||
                    "signature" in lower ->
                app.getString(R.string.installer_shizuku_error_signature)
            "invalid_apk" in lower ||
                    "parse_error" in lower ||
                    "failed to parse" in lower ->
                app.getString(R.string.installer_shizuku_error_invalid_apk)
            "user_restricted" in lower ||
                    "failed_user" in lower ||
                    "profile" in lower ->
                app.getString(R.string.installer_shizuku_error_user_profile)
            else -> raw ?: "Unknown error"
        }

        return if (raw != null && raw != summary) {
            app.getString(R.string.installer_shizuku_install_fail_with_details, summary, raw)
        } else {
            app.getString(R.string.installer_shizuku_install_fail, summary)
        }
    }

    private suspend fun handleConflict(targetPackageName: String, conflictMessage: String?) {
        Log.i(TAG, "Signature conflict for $targetPackageName")
        if (pm.getPackageInfo(targetPackageName) != null) {
            installState = signatureConflictState(targetPackageName)
        } else {
            // Target not installed - not a real signature conflict (e.g. renamed package)
            handleInstallError(app.getString(R.string.install_app_fail, conflictMessage ?: "Unknown error"))
        }
    }

    /**
     * Conflict state for [packageName], offering the signature bypass only when the certificates
     * are the actual blocker, the device has root, and the current attempt did not already skip
     * the check. Reading the signatures beats parsing installer output, because the platform
     * reports downgrades and certificate mismatches through the same conflict status.
     */
    private suspend fun signatureConflictState(packageName: String): InstallState.Conflict {
        val outputFile = pendingInstallFile
        val canIgnore = !pendingAllowSignatureMismatch && outputFile != null &&
                withContext(Dispatchers.IO) {
                    rootInstaller.hasRootAccess() && pm.hasSignatureMismatch(packageName, outputFile)
                }

        return InstallState.Conflict(packageName, canIgnore)
    }

    /** True for package manager output meaning the install was rejected over differing certificates. */
    private fun String?.isSignatureRejection(): Boolean {
        val text = this?.lowercase() ?: return false
        return "update_incompatible" in text || "signatures do not match" in text
    }

    private fun Throwable.isSignatureRejection() = simpleMessage().isSignatureRejection()

    companion object {
        private const val TAG = "Morphe Install"
        private const val UNINSTALL_VERIFY_TIMEOUT_MS = 10_000L
        private const val UNINSTALL_VERIFY_POLL_MS = 250L
        private val STOCK_INSTALL_SETTLE_TIMEOUT = 30.seconds
        private val STOCK_INSTALL_SETTLE_POLL = 1.seconds
    }
}
