package app.morphe.manager.patcher.patch

import app.morphe.manager.util.PatchSelection
import java.util.Locale

enum class PatchVersionScope { VERIFIED, COMPATIBLE, UNRESTRICTED, UNSUPPORTED }

/** UI classification uses the selected APK, never a catalogue's recommended version. */
fun PatchInfo.versionScope(packageName: String, version: String?, versionCode: Long?): PatchVersionScope {
    val targets = compatiblePackages.orEmpty()
    val named = targets.filter { it.packageName == packageName }
    if (named.any { target ->
            version != null && version in target.versions.orEmpty() &&
                (target.versionCodes?.get(version)?.let { codes ->
                    versionCode == null || codes.any { it.toLong() == versionCode }
                } != false)
        }) return PatchVersionScope.VERIFIED
    if (named.any { it.versions.isNullOrEmpty() }) return PatchVersionScope.COMPATIBLE
    if (targets.isEmpty() || targets.any { it.packageName == null }) return PatchVersionScope.UNRESTRICTED
    return PatchVersionScope.UNSUPPORTED
}

data class PanelPatch(
    val bundleUid: Int,
    val patch: PatchInfo,
    val selected: Boolean,
    val scope: PatchVersionScope,
    val releasePublishedAt: Long
) {
    val key get() = "$bundleUid:${patch.name}"
}

/** Selected other-version patches cannot disappear behind their availability toggle. */
fun PanelPatch.matchesVersionScopes(scopes: Set<PatchVersionScope>): Boolean =
    scope in scopes || (selected && scope == PatchVersionScope.UNSUPPORTED)

/** Never deduplicate labels: source UID + actual selection key are the identity. */
fun orderPanelPatches(rows: List<PanelPatch>): List<PanelPatch> = rows.sortedWith(
    compareBy<PanelPatch> { when (it.scope) {
        PatchVersionScope.VERIFIED -> 0
        PatchVersionScope.COMPATIBLE -> 1
        PatchVersionScope.UNRESTRICTED -> 2
        PatchVersionScope.UNSUPPORTED -> 3
    } }.thenByDescending { if (it.scope == PatchVersionScope.UNRESTRICTED) 0L else it.releasePublishedAt }
        .thenBy { it.patch.displayName.lowercase(Locale.ROOT) }
        .thenBy { it.bundleUid }.thenBy { it.patch.name }
)

/** A section action cannot reach another category or an unchosen source with the same label. */
fun sectionActionTargets(rows: List<PanelPatch>, selectedSources: Set<Int>): Map<Int, List<Pair<PatchInfo, Boolean>>> =
    rows.filter { it.bundleUid in selectedSources }.groupBy { it.bundleUid }
        .mapValues { (_, rows) -> rows.map { it.patch to it.selected } }


fun unsupportedAcknowledgementKey(
    packageName: String, version: String?, versionCode: Long?, bundleUid: Int,
    release: String?, publishedAt: Long
) = listOf(packageName, version.orEmpty(), versionCode?.toString().orEmpty(),
    bundleUid.toString(), release.orEmpty(), publishedAt.toString()).joinToString("|")

/** Empty visibility means an empty run; never fall back to all sources. */
fun PatchSelection.withoutHiddenSources(hidden: Set<Int>): PatchSelection = filterKeys { it !in hidden }

/** Package-separated local source IDs cannot hide a different app with a shared prefix. */
fun panelHiddenFor(packageName: String, stored: Set<String>): Set<Int> = stored.mapNotNullTo(mutableSetOf()) {
    it.removePrefix("$packageName|").takeIf { token -> token != it }?.toIntOrNull()
}
