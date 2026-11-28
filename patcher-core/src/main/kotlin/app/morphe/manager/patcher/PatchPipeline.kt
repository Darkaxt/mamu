/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher

import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.patch.applyPatchOptions
import app.morphe.manager.patcher.patch.keyedFor
import app.morphe.manager.patcher.patch.optionValueFromJson
import app.morphe.manager.patcher.runtime.ResourceMonitor
import app.morphe.manager.patcher.runtime.ResourceMonitor.LOG_HEAP_LIMIT_PREFIX
import app.morphe.manager.patcher.runtime.heapLimitMebibytes
import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.patcher.split.SplitPreparationEvent
import app.morphe.manager.patcher.util.NativeLibs
import app.morphe.patcher.patch.Patch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One run from the patch bundles to the patched APK, the same in whichever VM it happens.
 *
 * @param loadPatches Reads the patches of a bundle, which is the one step that depends on the VM:
 *                    ART loads them from dex, the JVM from class files.
 */
class PatchPipeline(
    private val request: PatchRequest,
    private val logger: Logger,
    private val listener: PatchingListener,
    private val loadPatches: suspend (PatchRequest.Bundle) -> Collection<Patch>
) {
    private val identities = java.util.IdentityHashMap<Patch, Pair<Int, String>>()

    suspend fun run() {
        // A host owns one run. Set this before loading any patch code, for both ART and JVM.
        System.setProperty("java.io.tmpdir", request.temporaryDir)
        ResourceMonitor.startPolling(logger)
        try {
            logger.info("$LOG_HEAP_LIMIT_PREFIX ${heapLimitMebibytes()}MB")
            patch(selectedPatches())
        } finally {
            ResourceMonitor.stopPolling(logger)
        }
    }

    private suspend fun selectedPatches(): List<Patch> {
        val missing = linkedSetOf<app.morphe.manager.patcher.patch.PatchKey>()
        val selected = request.bundles.sortedWith(compareByDescending<PatchRequest.Bundle> {
            it.publishedAt ?: Long.MIN_VALUE
        }.thenBy { it.uid }).flatMap { bundle ->
            val patches = loadPatches(bundle).keyedFor(request.packageName)
            bundle.patches.filterNot { it in patches.keys }
                .mapTo(missing) { app.morphe.manager.patcher.patch.PatchKey(bundle.uid, it) }
            patches.applyPatchOptions(
                bundle.options.mapValues { (_, options) -> options.mapValues { (_, value) -> optionValueFromJson(value) } },
                logger
            )
            patches.filterKeys { it in bundle.patches }.toSortedMap().onEach { (key, patch) ->
                require(!identities.containsKey(patch)) { "Patch object shared by independent source identities" }
                identities[patch] = bundle.uid to key
            }.values
        }
        if (missing.isNotEmpty()) {
            // The selection names a patch the source's code does not load here. Rather than
            // refusing the whole run, it is omitted like a failed patch, and the retry patches the rest
            val failure = MissingSelectedPatchesException(missing)
            logger.warn(failure.message!!)
            listener.patchesRejected(app.morphe.manager.patcher.patch.PatchRejection(missing,
                "Not in the patch source's code on this device", missing.first().key, failure.stackTraceToString()))
            throw failure
        }
        listener.stepCompleted() // Loading patches
        return selected
    }

    private suspend fun patch(patches: List<Patch>) {
        val preparation = prepareInput()
        try {
            val input = if (preparation.merged) {
                val kept = if (preparation.obbFiles.isEmpty()) {
                    // Moved rather than patched where it was merged, so it outlives the cleanup below
                    // and the app can keep it for repatching
                    withContext(Dispatchers.IO) {
                        Files.move(
                            preparation.file.toPath(),
                            File(request.mergedApkFile).toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        ).toFile()
                    }
                } else {
                    // The merged APK lacks the OBB files patches may read, so the app keeps the
                    // archive for repatching instead and the merged APK is left to the cleanup
                    logger.info("Not keeping the merged APK, as the archive has OBB files")
                    preparation.file
                }
                // Its own statement: chained onto the if-else above, it ran for the OBB branch only
                listener.stepCompleted()
                kept
            } else {
                preparation.file
            }

            val keepArchitectures =
                if (request.stripUnusedNativeLibs) withContext(Dispatchers.IO) {
                    NativeLibs.keptArchitectures(input, request.device.supportedAbis)
                } else {
                    emptySet()
                }

            Session(
                temporaryDir = File(request.temporaryDir),
                workspaceDir = File(request.workspaceDir),
                logger = logger,
                input = input,
                obbFiles = preparation.obbFiles,
                keepArchitectures = keepArchitectures,
                listener = listener,
                onRejected = { patch, failure ->
                    app.morphe.manager.patcher.patch.attributablePatchFailure(failure)?.let { reason ->
                        fun needs(root: Patch, visited: MutableSet<Patch>): Boolean =
                            root === patch || (visited.add(root) && root.dependencies.any { needs(it, visited) })
                        val affected = patches.filter { needs(it, mutableSetOf()) }.mapTo(linkedSetOf()) {
                            val (uid, key) = identities.getValue(it)
                            app.morphe.manager.patcher.patch.PatchKey(uid, key)
                        }
                        if (affected.isNotEmpty()) {
                            // A dependency failure belongs to each selected parent that requires it.
                            affected.forEach { listener.patchOutcome(it.sourceUid, it.key, reason) }
                            listener.patchesRejected(app.morphe.manager.patcher.patch.PatchRejection(
                                affected, reason, patch.toString(), failure.stackTraceToString()))
                        }
                    }
                },
                onPatchOutcome = { patch, failure ->
                    identities[patch]?.let { (uid, key) ->
                        if (failure == null) listener.patchOutcome(uid, key, null)
                        else app.morphe.manager.patcher.patch.attributablePatchFailure(failure)?.let {
                            listener.patchOutcome(uid, key, it)
                        }
                    }
                }
            ).use { it.run(File(request.outputFile), patches) }
        } finally {
            preparation.cleanup()
        }
    }

    // The preparation reports from the threads it blocks on, the listener is only called from here
    private suspend fun prepareInput() = coroutineScope {
        val events = Channel<SplitPreparationEvent>(Channel.UNLIMITED)
        val forwarding = launch { for (event in events) listener.splitPreparation(event) }

        try {
            SplitApkPreparer.prepareIfNeeded(
                source = File(request.inputFile),
                workspace = File(request.temporaryDir),
                device = request.device,
                logger = logger,
                skipUnneededSplits = request.stripUnusedNativeLibs,
                onEvent = { events.trySend(it) }
            )
        } finally {
            events.close()
            forwarding.join()
        }
    }
}

/** Selected patches the loaded source code does not contain, by source and key. */
class MissingSelectedPatchesException(val missing: Set<app.morphe.manager.patcher.patch.PatchKey>) : IllegalStateException(
    "Selected patches not found in their source's code, omitting them: " +
        missing.joinToString { "${it.sourceUid}:${it.key}" })
