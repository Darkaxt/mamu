/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/patcher/Session.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.patcher

import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.util.toLogString
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherProgress
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchResult
import app.morphe.patcher.resource.CpuArchitecture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.measureTime

internal typealias PatchList = List<Patch>

/**
 * @param obbFiles The OBB files of the archive [input] was merged from, keyed by their path inside it.
 * @param keepArchitectures The native libraries the output keeps, every one of them when empty.
 */
class Session(
    temporaryDir: File,
    workspaceDir: File,
    private val logger: Logger,
    input: File,
    obbFiles: Map<String, File>,
    keepArchitectures: Set<CpuArchitecture>,
    private val listener: PatchingListener,
    private val onPatchOutcome: suspend (Patch, Throwable?) -> Unit = { _, _ -> },
    private val onRejected: suspend (Patch, Throwable) -> Unit = { _, _ -> }
) : Closeable {
    private val tempDir = temporaryDir.resolve("patcher").also { it.mkdirs() }

    // Scratch space for patches. Not under tempDir because the patcher wipes that directory
    // when it starts, and not the cache root to avoid colliding with the other caches there
    private val fileWorkspace = workspaceDir.also { it.mkdirs() }

    private val config = PatcherConfig(
            apkFile = input,
            temporaryFilesPath = tempDir,
            // The patcher drops every other ABI while it writes the output, sparing a second pass over it
            keepArchitectures = keepArchitectures,
            fileWorkspacePath = fileWorkspace,
            progress = PatcherProgress(listener::taskProgress),
            obbFiles = obbFiles
    )
    private val patcher = Patcher(config)

    private suspend fun Patcher.applyPatchesVerbose(selectedPatches: PatchList) {
        listener.patchesStarted()

        executeInPackOrder(config, selectedPatches,
            executing = { listener.patchExecuting(it.toString()) },
            succeeded = { patch ->
                onPatchOutcome(patch, null)
                listener.patchSucceeded(patch.name.orEmpty())
                logger.info("${patch.name} succeeded")
            }, rejected = { patch, failure ->
                onRejected(patch, failure)
                if (patch in selectedPatches) onPatchOutcome(patch, failure)
                logger.error("${patch.name} failed:")
                logger.error(failure.stackTraceToString())
                listener.patchFailed(patch.name, failure.stackTraceToString())
            })

        listener.patchesFinished(selectedPatches.size)
    }

    suspend fun run(output: File, selectedPatches: PatchList) {
        listener.stepCompleted() // Unpacking

        java.util.logging.Logger.getLogger("").apply {
            handlers.forEach {
                it.close()
                removeHandler(it)
            }

            addHandler(logger.handler)
        }

        val patchTime = measureTime {
            with(patcher) {
                this += selectedPatches.toSet()

                logger.info("Applying patches...")
                applyPatchesVerbose(selectedPatches)
            }
        }

        logger.info("Writing patched files...")
        val patched = tempDir.resolve("patched.apk")
        val compileTime = withContext(Dispatchers.Default) {
            // patcher.writeApk() compiles dex files and resources and writes the aligned, unsigned apk
            // in one pass, so run on default pool instead of main thread.
            measureTime { patcher.writeApk(patched) }
        }

        val writeTime = measureTime {
            withContext(Dispatchers.IO) {
                Files.move(patched.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        logger.info(
            "Patched apk saved to $output (patch=${patchTime.toLogString()} " +
                "compile=${compileTime.toLogString()} write=${writeTime.toLogString()})"
        )
        listener.stepCompleted() // Saving
    }

    override fun close() {
        try { patcher.close() } finally {
            for (directory in listOf(tempDir, fileWorkspace)) {
                val root = directory.toPath()
                if (Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.walk(root).use { paths -> paths.forEach { if (!Files.isSymbolicLink(it)) it.toFile().setWritable(true) } }
                    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            }
        }
    }

    companion object {
        operator fun PatchResult.component1() = patch
        operator fun PatchResult.component2() = exception
    }
}
