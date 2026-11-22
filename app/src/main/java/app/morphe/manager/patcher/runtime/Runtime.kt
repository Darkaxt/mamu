/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/patcher/runtime/Runtime.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.patcher.runtime

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.work.ForegroundInfo
import app.morphe.manager.R
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.patcher.DeviceConfig
import app.morphe.manager.patcher.PatchRequest
import app.morphe.manager.patcher.PatchingListener
import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.patch.optionValueToJson
import app.morphe.manager.patcher.patch.PatchRejection
import app.morphe.manager.patcher.patch.retryFailedPatches
import app.morphe.manager.patcher.worker.ProgressEventHandler
import app.morphe.manager.patcher.worker.TaskProgressHandler
import app.morphe.manager.util.Options
import app.morphe.manager.util.PatchSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.measureTime

data class RuntimeResult(val elapsed: Duration, val recipe: AppliedPatchRecipe)

/**
 * Patches in a process of its own on [vm], see [PatcherProcesses]. Everything around a run is the
 * same for each VM: the request it is given, what its reports turn into on screen, and the merged
 * APK it leaves behind.
 *
 * @param foreground The notification of the run, which its process holds as well.
 */
class Runtime(
    private val context: Context,
    private val fs: Filesystem,
    private val patchBundlesRepo: PatchBundleRepository,
    private val processes: PatcherProcesses,
    private val vm: PatcherVm,
    private val foreground: suspend () -> ForegroundInfo
) {
    /**
     * Patches [inputFile] into [outputFile].
     *
     * @param inputFile        Path of the APK or split archive to patch.
     * @param outputFile       Path the patched APK is written to.
     * @param packageName      Package of the app being patched.
     * @param selectedPatches  Patches to apply, per bundle.
     * @param options          Patch option values, per bundle.
     * @param logger           Sink for everything the run reports.
     * @param onPatchCompleted Called with the name of each patch that finished.
     * @param onPatchFailed    Called with the name of the patch the run failed on.
     * @param onProgress       Called as the run moves between steps.
     * @param onTaskProgress   Called as the patcher gets through the work it does besides executing patches.
     * @param stripUnusedNativeLibs Whether native libraries and split configurations the device
     *                         cannot use are dropped.
     * @param onMergedApkReady Called with the merged APK when a split archive patched successfully.
     *                         The runtime has no further use for the file, so it may be moved.
     * @return How long patching took.
     */
    suspend fun execute(
        inputFile: String,
        outputFile: String,
        packageName: String,
        selectedPatches: PatchSelection,
        options: Options,
        logger: Logger,
        onPatchCompleted: suspend (String) -> Unit,
        onPatchFailed: (String) -> Unit,
        onProgress: ProgressEventHandler,
        onTaskProgress: TaskProgressHandler,
        stripUnusedNativeLibs: Boolean,
        onMergedApkReady: (suspend (File) -> Unit)? = null,
        onPatchOutcome: (Int, String, String?) -> Unit = { _, _, _ -> },
        onRestart: suspend (Int) -> Unit = {},
        onArtFallback: suspend () -> Unit = {},
        expectedVersions: Map<Int,String?> = emptyMap()
    ): RuntimeResult = withContext(Dispatchers.IO) {
        val runDirectory = requireNotNull(File(outputFile).absoluteFile.parentFile)
        val root=runDirectory.resolve("runtime-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        var activeVm=vm
        var lastMerged: File?=null
        try {
            val original=root.resolve("original.${File(inputFile).extension}")
            copyFrozenInput(File(inputFile),original)
            val packs=freezePacks(patchBundlesRepo,root,packageName,selectedPatches,options,expectedVersions)
            val device=currentDeviceConfig()
            var attempts: app.morphe.manager.patcher.patch.PatchRetryResult?=null
            val elapsed=measureTime {
                attempts=retryFailedPatches(selectedPatches, onRetry={ selection, omitted, number ->
                    logger.warn(context.getString(R.string.patch_retry_start,number,selection.values.sumOf { it.size }))
                    onRestart(selection.values.sumOf { it.size })
                }) { selection, number ->
                    var rejection: PatchRejection?=null
                    var fallback=false
                    while(true) {
                        val directory=root.resolve("attempt-$number-${if(fallback) "art" else "initial"}").also { check(it.mkdirs()) }
                        val merged=directory.resolve("merged.apk")
                        val output=directory.resolve("patched.apk")
                        val request=PatchRequest(packageName,original.path,output.path,merged.path,directory.path,
                            directory.resolve("workspace").path,packs.mapNotNull { pack ->
                                selection[pack.request.uid]?.takeIf { it.isNotEmpty() }?.let { names ->
                                    pack.request.copy(patches=names,options=pack.request.options.filterKeys { it in names })
                                }
                            },stripUnusedNativeLibs,device)
                        val listener=ProgressReporter(context,logger,onProgress,onPatchCompleted,onPatchFailed,
                            onPatchOutcome=onPatchOutcome,onTaskProgress=onTaskProgress,onRejected={ rejected ->
                                rejection=rejected
                                val omitted=rejected.affected.map { key -> app.morphe.manager.patcher.patch.PatchOmission(key,rejected.reason,rejected.failedPatch) }
                                appliedRecipe(selection,omitted,packs).omissions.forEach { omission ->
                                    logger.warn(context.getString(R.string.patch_retry_omission,
                                        omission.sourceName ?: omission.patch.sourceUid.toString(),omission.sourceVersion ?: "?",
                                        omission.patch.key,omission.reason))
                                }
                            })
                        try {
                            patch(request,logger,listener,activeVm)
                            require(output.isFile) { "Patcher completed without an output APK" }
                            java.nio.file.Files.move(output.toPath(),File(outputFile).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            if(merged.isFile) {
                                lastMerged=root.resolve("successful-original.apk")
                                java.nio.file.Files.move(merged.toPath(),lastMerged!!.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            }
                            break
                        } catch (failure: JvmUnavailableException) {
                            if(activeVm!=PatcherVm.JVM) throw failure
                            logger.warn("${failure.message}; patching on ART instead")
                            activeVm=PatcherVm.ART; fallback=true; rejection=null
                            onRestart(selection.values.sumOf { it.size }); onArtFallback()
                        } catch (failure: PatcherFailureException) {
                            val reported=rejection
                            if(!failure.confirms(reported)) throw failure
                            break
                        } finally {
                            // processes.run has awaited native/binder death, including cancellation.
                            withContext(NonCancellable) { discardRuntimeAttempt(directory) }
                        }
                    }
                    rejection
                }
            }
            lastMerged?.let { onMergedApkReady?.invoke(it) }
            val result=requireNotNull(attempts)
            RuntimeResult(elapsed,appliedRecipe(result.applied,result.omissions,packs))
        } finally {
            withContext(NonCancellable) { discardRuntimeAttempt(root) }
        }
    }

    /** Runs [request], reporting through [logger] and [listener], and throws if it fails. */
    private suspend fun patch(request: PatchRequest, logger: Logger, listener: PatchingListener, vm: PatcherVm) {
        // A file of its own, as the process of a canceled run may still be reading its request
        val requestFile = withContext(Dispatchers.IO) {
            File.createTempFile("patch-request", ".json", File(request.temporaryDir)).apply {
                writeText(Json.encodeToString(PatchRequest.serializer(), request))
            }
        }
        try {
            processes.run(requestFile, vm, foreground(), logger, listener)
        } finally {
            requestFile.delete()
        }
    }

}
