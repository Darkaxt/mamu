package app.morphe.manager.downloaders

import android.app.Application
import android.content.Context
import androidx.work.*
import app.morphe.manager.domain.bundles.AppVersionCatalog
import app.morphe.manager.domain.repository.OriginalApkRepository
import app.morphe.manager.util.AppCoroutineScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

data class OriginalDownloadEntry(val id: UUID, val target: OriginalDownloadTarget, val phase: String,
    val message: String, val url: String?, val bytes: Long, val total: Long, val active: Boolean)

internal fun WorkInfo.originalTag(name: String) = tags.firstOrNull { it.startsWith("original:$name:") }?.removePrefix("original:$name:")
internal fun List<WorkInfo>.hasActiveOriginal(packageName: String) =
    any { !it.state.isFinished && it.originalTag("package") == packageName }

/** Pins come from this request's tags, never a predecessor's merged output Data. */
internal fun originalDownloadEntries(works: List<WorkInfo>): Map<String, OriginalDownloadEntry> =
    works.groupBy { it.originalTag("package") }.filterKeys { it != null }.mapValues { (_, requests) ->
        val work = requests.maxBy { it.originalTag("order")?.toLongOrNull() ?: 0L }
        val data = if (work.state.isFinished) work.outputData else work.progress
        val phase = when {
            work.state == WorkInfo.State.CANCELLED -> "cancelled"
            work.state == WorkInfo.State.FAILED -> "failed"
            work.state == WorkInfo.State.BLOCKED || work.state == WorkInfo.State.ENQUEUED -> "waiting"
            else -> data.getString("resultPhase") ?: "resolving"
        }
        val target = OriginalDownloadTarget(checkNotNull(work.originalTag("package")), checkNotNull(work.originalTag("version")),
            work.originalTag("codes")?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet())
        OriginalDownloadEntry(work.id, target, phase, data.getString("resultMessage").orEmpty(),
            data.getString("resultUrl"), data.getLong("bytes", 0), data.getLong("total", -1), !work.state.isFinished)
    }.mapKeys { checkNotNull(it.key) }

/** WorkManager is the durable queue; only ephemeral UI snapshots live in this singleton. */
class OriginalDownloadQueue(app: Application, catalog: AppVersionCatalog, originals: OriginalApkRepository,
                            scope: AppCoroutineScope) {
    val manager = WorkManager.getInstance(app)
    private val storage = app.getSharedPreferences("original-download-queue", Context.MODE_PRIVATE)
    private val admission = Mutex()
    private val _cancelled = MutableStateFlow(storage.getStringSet("cancelled", emptySet()).orEmpty().toSet())
    val cancelled = _cancelled.asStateFlow()
    val targets = catalog.compatibleVersions.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val cached = originals.getAll().map { items -> items.filter { File(it.filePath).isFile }.associateBy { it.packageName } }
        .flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val entries = manager.getWorkInfosByTagFlow(TAG).map(::originalDownloadEntries)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    suspend fun enqueue(target: OriginalDownloadTarget): String? = withContext(Dispatchers.IO) {
        try { admission.withLock {
            val existing = manager.getWorkInfosByTag(TAG).get()
            if (existing.hasActiveOriginal(target.packageName)) return@withLock null
            val ordinal = storage.getLong("ordinal", 0) + 1
            check(storage.edit().putLong("ordinal", ordinal).commit())
            val input = workDataOf("package" to target.packageName, "version" to target.version,
                "buildCodes" to target.buildCodes?.toIntArray())
            val request = OneTimeWorkRequestBuilder<OriginalDownloadWorker>()
                .setInputData(input).addTag(TAG).addTag("original:package:${target.packageName}")
                .addTag("original:version:${target.version}").addTag("original:order:$ordinal")
                .apply { target.buildCodes?.let { addTag("original:codes:${it.sorted().joinToString(",")}") } }
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            // Never cancel the chain for one row: the cooperative flag lets successors continue.
            manager.beginUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue().result.get()
            null
        } } catch (error: CancellationException) { throw error }
        catch (error: Exception) { error.message ?: "Could not queue the download." }
    }

    suspend fun cancel(id: UUID): String? = withContext(Dispatchers.IO) {
        try { admission.withLock {
            val value = _cancelled.value + id.toString()
            check(storage.edit().putStringSet("cancelled", value).commit())
            _cancelled.value = value
            null
        } } catch (error: CancellationException) { throw error }
        catch (error: Exception) { error.message ?: "Could not cancel the download." }
    }

    companion object {
        const val TAG = "original-apk-downloads"
    }
}
