package app.morphe.manager.patcher

import java.io.File
import java.util.UUID

/** Each screen owns a distinct directory, including when an older worker is still closing. */
internal fun newPatchWorkspace(root: File): File = root.resolve("installer-${UUID.randomUUID()}").also { it.mkdirs() }

internal fun isDisposablePatchInput(file: File, workspace: File, retained: File, temporary: Boolean): Boolean =
    !file.absolutePath.startsWith(retained.absolutePath + File.separator) &&
        (temporary || file.absolutePath.startsWith(workspace.absolutePath + File.separator))

/** The application scope holds this operation across screen destruction; no timed deletion. */
internal suspend fun clearPatchWorkspaceAfterWorker(workspace: File, disposableInput: File?, temporaryOriginal: File?,
    awaitWorker: suspend () -> Unit) {
    awaitWorker()
    disposableInput?.delete()
    temporaryOriginal?.delete()
    workspace.deleteRecursively()
}

/** WorkManager's CANCELLED state can precede the worker's finally blocks. */
internal class PatchInputUse {
    private enum class State { PENDING, RUNNING, RELEASED }
    private var state = State.PENDING
    private val released = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Synchronized fun begin(): Boolean {
        if (state != State.PENDING) return false
        state = State.RUNNING
        return true
    }
    @Synchronized fun finish() { state = State.RELEASED; released.complete(Unit) }
    @Synchronized fun abandonBeforeStart() {
        if (state == State.PENDING) finish()
    }
    suspend fun awaitReleased() = released.await()
}
