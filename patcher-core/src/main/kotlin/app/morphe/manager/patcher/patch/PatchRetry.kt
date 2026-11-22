package app.morphe.manager.patcher.patch

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
data class PatchKey(val sourceUid: Int, val key: String)

/** Emitted only for attributable execute/finalize failures; never inferred from log text. */
@Serializable
data class PatchRejection(val affected: Set<PatchKey>, val reason: String,
    val failedPatch: String, val stackTrace: String)

@Serializable
data class PatchOmission(val patch: PatchKey, val reason: String, val failedPatch: String,
    val sourceName: String? = null, val sourceVersion: String? = null)

data class PatchRetryResult(val applied: Map<Int, Set<String>>, val omissions: List<PatchOmission>, val attempts: Int)

class NoPatchesRemainingException(val omissions: List<PatchOmission>) : IllegalStateException(
    "No patches remain after attributable execution failures; no APK was produced")

/** Normal attempts only. Unknown failures throw; every clean retry removes at least one root. */
suspend fun retryFailedPatches(initial: Map<Int, Set<String>>,
    onRetry: suspend (Map<Int, Set<String>>, List<PatchOmission>, Int) -> Unit = { _, _, _ -> },
    execute: suspend (Map<Int, Set<String>>, Int) -> PatchRejection?): PatchRetryResult {
    var selection = initial.mapValues { it.value.toSet() }.filterValues { it.isNotEmpty() }
    require(selection.isNotEmpty()) { "No patches were selected" }
    val omissions = linkedMapOf<PatchKey, PatchOmission>()
    var attempt = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val rejected = execute(selection, ++attempt)
            ?: return PatchRetryResult(selection, omissions.values.toList(), attempt)
        require(rejected.affected.isNotEmpty() && rejected.affected.all { it.key in selection[it.sourceUid].orEmpty() }) {
            "Patch rejection identities do not match the current recipe"
        }
        rejected.affected.sortedWith(compareBy<PatchKey> { it.sourceUid }.thenBy { it.key }).forEach {
            omissions[it] = PatchOmission(it, rejected.reason, rejected.failedPatch)
        }
        selection = selection.mapValues { (uid, keys) -> keys.filterTo(linkedSetOf()) {
            PatchKey(uid, it) !in rejected.affected
        } }.filterValues { it.isNotEmpty() }
        if (selection.isEmpty()) throw NoPatchesRemainingException(omissions.values.toList())
        onRetry(selection, omissions.values.toList(), attempt + 1)
    }
}
