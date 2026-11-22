/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.runtime

import android.content.Context
import app.morphe.manager.R
import app.morphe.manager.patcher.PatchingListener
import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.split.SplitPreparationEvent
import app.morphe.manager.patcher.worker.ProgressEventHandler
import app.morphe.manager.patcher.worker.TaskProgressHandler
import app.morphe.manager.ui.model.State
import app.morphe.patcher.PatcherTask

/**
 * Puts what a run reports into the steps the patcher screen shows, in the app language whichever
 * runtime the run happens in.
 */
internal class ProgressReporter(
    private val context: Context,
    private val logger: Logger,
    private val onProgress: ProgressEventHandler,
    private val onPatchCompleted: suspend (String) -> Unit,
    private val onPatchFailed: (String) -> Unit,
    private val onPatchOutcome: (Int, String, String?) -> Unit = { _, _, _ -> },
    private val onTaskProgress: TaskProgressHandler = { _, _, _ -> },
    private val onRejected: ((app.morphe.manager.patcher.patch.PatchRejection) -> Unit)? = null
) : PatchingListener {
    private var hasRejection = false
    private val reportedTasks = java.util.Collections.synchronizedSet(mutableSetOf<PatcherTask>())
    override suspend fun patchesRejected(rejection: app.morphe.manager.patcher.patch.PatchRejection) {
        hasRejection = onRejected != null
        onRejected?.invoke(rejection)
        rejection.affected.forEach { logger.warn("Patch omitted: ${it.sourceUid}:${it.key}: ${rejection.reason}") }
    }
    override suspend fun stepCompleted() = onProgress(null, State.COMPLETED, null)

    override suspend fun splitPreparation(event: SplitPreparationEvent) {
        val message = event.toLocalizedString(context)
        logger.info(message)
        onProgress(message, State.RUNNING, null)
    }

    override suspend fun patchesStarted() = onProgress(null, State.RUNNING, null)

    override suspend fun patchOutcome(bundleUid: Int, patchKey: String, failure: String?) =
        onPatchOutcome(bundleUid, patchKey, failure)

    override suspend fun patchSucceeded(name: String) = onPatchCompleted(name)

    override fun patchExecuting(name: String) { logger.info("Applying $name") }

    override fun taskProgress(task: PatcherTask, done: Long, total: Long) {
        if (reportedTasks.add(task)) logger.info(context.getString(when(task) {
            PatcherTask.DECODING_DEX -> R.string.patcher_task_decoding_dex
            PatcherTask.EXTRACTING_RESOURCES -> R.string.patcher_task_extracting_resources
            PatcherTask.DECODING_RESOURCES -> R.string.patcher_task_decoding_resources
            PatcherTask.INDEXING_CLASSES -> R.string.patcher_task_indexing_classes
            PatcherTask.COMPILING_DEX -> R.string.patcher_task_compiling_dex
            PatcherTask.COMPILING_RESOURCES -> R.string.patcher_task_compiling_resources
            PatcherTask.WRITING_APK -> R.string.patcher_task_writing_apk
        }))
        onTaskProgress(task, done, total)
    }

    override suspend fun patchFailed(name: String?, stackTrace: String) {
        if (hasRejection) return // The clean retry reports the omission and resets real counters.
        onProgress(context.getString(R.string.failed_to_execute_patch, name), State.FAILED, stackTrace)
        name?.let(onPatchFailed)
    }

    override suspend fun patchesFinished(count: Int) = onProgress(
        context.resources.getQuantityString(R.plurals.patches_executed, count, count.toString()),
        State.COMPLETED,
        null
    )
}
