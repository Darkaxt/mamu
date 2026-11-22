package app.morphe.manager.patcher.patch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.morphe.manager.domain.bundles.PatchBundleSource
import app.morphe.manager.domain.bundles.RemotePatchBundle
import app.morphe.manager.util.PatchSelection
import java.time.LocalDate

data class PatchReleaseAgeSource(val uid: Int, val name: String, val version: String?, val day: String?)
data class PatchReleaseAgeWarning(
    val packageName: String, val apkVersion: String?, val apkDay: String,
    val bundleUid: Int, val sourceName: String, val sourceVersion: String?, val sourceDay: String,
    val patchName: String, val patchLabel: String, val apkVersionCode: Long? = null
) { val patchKey get() = "$bundleUid:$patchName" }

fun patchReleaseAgeSources(sources: List<PatchBundleSource>): List<PatchReleaseAgeSource> =
    sources.filterIsInstance<RemotePatchBundle>().map { source ->
        PatchReleaseAgeSource(source.uid, source.displayTitle, source.version,
            source.installedPublicationDay)
    }

fun patchReleaseAgeWarnings(packageName: String, version: String?, apkDay: String?, rows: List<PanelPatch>,
    sources: List<PatchReleaseAgeSource>, versionCode: Long? = null): List<PatchReleaseAgeWarning> {
    val published = runCatching { LocalDate.parse(apkDay) }.getOrNull() ?: return emptyList()
    val byUid = sources.associateBy { it.uid }
    return rows.mapNotNull { row ->
        if (!row.selected) return@mapNotNull null
        val targets = row.patch.compatiblePackages.orEmpty().filter { it.packageName == packageName }
        // A version listed on any declaration for this app excludes the age heuristic.
        if (targets.isEmpty() || targets.any { !it.versions.isNullOrEmpty() }) return@mapNotNull null
        val source = byUid[row.bundleUid] ?: return@mapNotNull null
        val released = runCatching { LocalDate.parse(source.day) }.getOrNull() ?: return@mapNotNull null
        if (!released.isBefore(published)) return@mapNotNull null
        PatchReleaseAgeWarning(packageName, version, published.toString(), row.bundleUid,
            source.name, source.version, released.toString(), row.patch.name, row.patch.displayName, versionCode)
    }
}

fun selectedPatchReleaseAgeWarnings(packageName: String, version: String?, versionCode: Long?, apkDay: String?,
    selection: PatchSelection, bundles: Map<Int, PatchBundleInfo.Global>, sources: List<PatchBundleSource>): List<PatchReleaseAgeWarning> {
    val rows = selection.flatMap { (uid, selected) ->
        bundles[uid]?.forPackage(packageName, version, versionCode)?.patches.orEmpty().map { patch ->
            PanelPatch(uid, patch, patch.name in selected, patch.versionScope(packageName, version, versionCode), 0)
        }
    }
    // Dates are only comparable when the metadata describes the installed bundle being selected.
    val installed = sources.filter { source -> source.enabled && source.version == bundles[source.uid]?.version }
    return patchReleaseAgeWarnings(packageName, version, apkDay, rows, patchReleaseAgeSources(installed), versionCode)
}

fun List<PatchReleaseAgeWarning>.forApk(packageName: String, version: String?, versionCode: Long?) =
    filter { it.packageName == packageName && it.apkVersion == version && it.apkVersionCode == versionCode }

fun PatchSelection.withoutAgeWarnings(warnings: List<PatchReleaseAgeWarning>): PatchSelection {
    val removed = warnings.groupBy { it.bundleUid }.mapValues { (_, rows) -> rows.map { it.patchName }.toSet() }
    return mapValues { (uid, names) -> names - removed[uid].orEmpty() }
}

/** One run request, refreshed against live selections before either proceeding or disabling. */
class PatchReleaseAgeGate {
    data class Pending(val warnings: List<PatchReleaseAgeWarning>, val current: () -> List<PatchReleaseAgeWarning>,
        val proceed: () -> Unit, val review: (List<PatchReleaseAgeWarning>) -> Unit, val home: () -> Unit)
    var pending by mutableStateOf<Pending?>(null)
        private set
    fun request(current: () -> List<PatchReleaseAgeWarning>, proceed: () -> Unit,
        review: (List<PatchReleaseAgeWarning>) -> Unit, home: () -> Unit) {
        val warnings = current()
        if (warnings.isEmpty()) { pending = null; proceed() }
        else pending = Pending(warnings, current, proceed, review, home)
    }
    fun proceed() {
        val request = pending ?: return
        val current = request.current()
        if (current.isNotEmpty() && current != request.warnings) { pending = request.copy(warnings = current); return }
        pending = null
        request.proceed()
    }
    fun review() {
        val request = pending ?: return
        val current = request.current()
        if (current.isNotEmpty() && current != request.warnings) { pending = request.copy(warnings = current); return }
        pending = null
        request.review(current)
    }
    fun home() { val request = pending ?: return; pending = null; request.home() }
    fun cancel() { pending = null }
}
