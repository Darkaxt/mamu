package app.morphe.manager.patcher.patch

import android.app.Application
import app.morphe.manager.patcher.split.SplitApkPreparer
import android.util.AtomicFile
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.ui.model.SelectedApp
import app.morphe.manager.util.PM
import app.morphe.manager.util.PatchSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Identity of the actual input and installed bundle bytes, prepared on IO before selection/run. */
data class PatchFailureContext(val apk: String, val bundles: Map<Int, String>)
data class KnownPatchFailure(val apk: String, val uid: Int, val release: String, val patch: String, val reason: String, val resolved: Boolean = false)
data class PatchExecutionOutcome(val uid: Int, val patch: String, val failure: String?)

/** An aborted attempt cannot prove a parent's dependency finalizers succeeded. */
internal fun completedPatchOutcomes(outcomes: List<PatchExecutionOutcome>, succeeded: Boolean): List<PatchExecutionOutcome> =
    outcomes.filter { succeeded || it.failure != null }.distinct()

fun knownFailures(context: PatchFailureContext?, records: List<KnownPatchFailure>): Map<String, String> =
    records.filter { !it.resolved && context != null && it.apk == context.apk && it.release == context.bundles[it.uid] }
        .associate { "${it.uid}:${it.patch}" to it.reason }

fun PatchSelection.withoutKnownFailures(context: PatchFailureContext?, records: List<KnownPatchFailure>): PatchSelection {
    val failed = knownFailures(context, records)
    return mapValues { (uid, names) -> names.filterNotTo(linkedSetOf()) { "$uid:$it" in failed } }
}

/** Explicit retry permission never travels to a different APK or bundle revision. */
fun knownFailureRetryKey(context: PatchFailureContext?, patchKey: String): String =
    "${context?.apk}|${patchKey.substringBefore(':').toIntOrNull()?.let { context?.bundles?.get(it) }}|$patchKey"

/** Availability may add REQUIRED patches, but must not silently re-enable a known failure. */
fun PatchSelection.preserveKnownFailureChoices(requested: PatchSelection, failed: Set<String>): PatchSelection =
    mapValues { (uid, names) -> names.filterTo(linkedSetOf()) { "$uid:$it" !in failed || it in requested[uid].orEmpty() } }

fun sourceFailureCounts(records: List<KnownPatchFailure>, selected: PatchSelection): Map<Int, Int> =
    records.filter { selected[it.uid].orEmpty().isNotEmpty() }.groupingBy { it.uid }.eachCount()

internal fun updatedFailureRecords(records: List<KnownPatchFailure>, context: PatchFailureContext,
    outcomes: List<PatchExecutionOutcome>, currentReleases: Map<Int, String>): List<KnownPatchFailure> {
    var values = records
    outcomes.forEach { outcome ->
        val release = context.bundles[outcome.uid] ?: return@forEach
        if (currentReleases[outcome.uid] != release) return@forEach
        val previous = values.firstOrNull { it.apk == context.apk && it.uid == outcome.uid &&
            it.release == release && it.patch == outcome.patch }
        values = values - listOfNotNull(previous).toSet()
        if (outcome.failure != null) values = values + KnownPatchFailure(context.apk, outcome.uid, release, outcome.patch, outcome.failure)
        else if (previous != null) values = values + previous.copy(resolved = true)
    }
    return values
}

internal fun readFailureRecords(file: AtomicFile): List<KnownPatchFailure> {
    val input = try { file.openRead() } catch (error: java.io.FileNotFoundException) {
        if (file.baseFile.exists()) throw error
        return emptyList()
    }
    val values = JSONArray(input.bufferedReader().use { it.readText() })
    return (0 until values.length()).map { index ->
        val value = values.getJSONObject(index)
        KnownPatchFailure(value.getString("apk"), value.getInt("uid"), value.getString("release"),
            value.getString("patch"), value.getString("reason"), value.optBoolean("resolved"))
    }
}

internal fun writeFailureRecords(file: AtomicFile, records: List<KnownPatchFailure>) {
    val json = JSONArray()
    records.forEach { json.put(JSONObject().put("apk", it.apk).put("uid", it.uid).put("release", it.release)
        .put("patch", it.patch).put("reason", it.reason).put("resolved", it.resolved)) }
    val stream = file.startWrite()
    try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
    catch (error: Throwable) { file.failWrite(stream); throw error }
}

/** Normalize an installed split set and a downloaded archive to the same APK content set. */
internal fun originalApkDigests(file: File): List<String> {
    fun sha(input: java.io.InputStream): String = input.buffered().use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
    if (!SplitApkPreparer.isSplitArchive(file)) return listOf(sha(file.inputStream()))
    return java.util.zip.ZipFile(file).use { zip -> zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
        .map { sha(zip.getInputStream(it)) }.toList() }
}

/** Compact local evidence, separate from downloaded APKs and author-supplied compatibility. */
class KnownPatchFailures(private val app: Application, private val bundles: PatchBundleRepository, private val pm: PM) {
    private val file = AtomicFile(app.noBackupFilesDir.resolve("known-patch-failures.json"))
    private val mutex = Mutex()
    private var loaded = false
    private val _records = MutableStateFlow<List<KnownPatchFailure>>(emptyList())
    val records = _records.asStateFlow()
    private val digests = mutableMapOf<String, Pair<Pair<Long, Long>, String>>()

    private fun digest(file: File): String {
        val stamp = file.length() to file.lastModified()
        digests[file.absolutePath]?.takeIf { it.first == stamp }?.let { return it.second }
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }.also { digests[file.absolutePath] = stamp to it }
    }
    private fun load() {
        if (loaded) return
        _records.value = runCatching { readFailureRecords(file) }.onFailure {
            android.util.Log.w("KnownPatchFailures", "Could not read patch failure evidence", it)
        }.getOrDefault(emptyList())
        loaded = true
    }
    suspend fun loadRecords() = withContext(Dispatchers.IO) { mutex.withLock { load() } }

    suspend fun prepare(selected: SelectedApp, sourceUids: Set<Int>? = null, forRun: Boolean = false): PatchFailureContext = withContext(Dispatchers.IO) { mutex.withLock {
        load()
        if (!forRun && _records.value.none { !it.resolved && it.apk.startsWith(selected.packageName + "|") })
            return@withLock PatchFailureContext("", emptyMap())
        val inputs = when (selected) {
            is SelectedApp.Local -> listOf(selected.file)
            is SelectedApp.Installed -> pm.getPackageInfo(selected.packageName)?.applicationInfo?.let {
                listOf(File(it.sourceDir)) + it.splitSourceDirs.orEmpty().sorted().map(::File)
            }.orEmpty()
        }
        require(inputs.isNotEmpty() && inputs.all { it.isFile }) { "Original APK is no longer available" }
        val architecture = ApkArchitectureResolver.resolve(selected, pm)
        val apk = listOf(selected.packageName, selected.version, selected.versionCode, architecture,
            inputs.flatMap(::originalApkDigests).sorted().joinToString(":")).joinToString("|")
        PatchFailureContext(apk, bundles.bundles.first().filterKeys { if (sourceUids != null) it in sourceUids else forRun || _records.value.any { record -> !record.resolved && record.uid == it && record.apk.startsWith(selected.packageName + "|") } }.mapValues { (_, bundle) -> digest(File(bundle.patchesJar)) })
    } }

    suspend fun apply(context: PatchFailureContext, outcomes: List<PatchExecutionOutcome>) = withContext(Dispatchers.IO) { mutex.withLock {
        load()
        val current = bundles.bundles.first()
        val currentReleases = context.bundles.keys.mapNotNull { uid ->
            current[uid]?.let { uid to digest(File(it.patchesJar)) }
        }.toMap()
        val values = updatedFailureRecords(_records.value, context, outcomes, currentReleases)
        if (values == _records.value) return@withLock
        writeFailureRecords(file, values)
        _records.value = values
    } }
}
