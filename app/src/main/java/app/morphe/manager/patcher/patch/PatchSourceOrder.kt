package app.morphe.manager.patcher.patch

/** The information preview needs only source metadata, including targets for older APKs. */
internal fun Map<Int, List<PatchInfo>>.appSpecificPatches(packageName: String) =
    mapValues { (_, patches) -> patches.filter { patch ->
        patch.compatiblePackages.orEmpty().any { it.packageName == packageName }
    } }.filterValues { it.isNotEmpty() }

/** Display priority only: universal patches never increase a source's app-specific rank. */
internal fun patchSourceDisplayOrder(
    packageName: String,
    recommendedVersion: String?
): Comparator<PatchBundleInfo.Scoped> =
    patchSourceDisplayOrder(packageName, recommendedVersion) { it.patches }

internal fun customPatchCount(patches: List<PatchInfo>, packageName: String, version: String? = null) =
    patches.count { patch -> patch.compatiblePackages.orEmpty().any {
        it.packageName == packageName &&
            (version == null || it.versions == null || version in it.versions)
    } }

/** Shared by the pre-download preview/chooser and the post-download editors. */
internal fun <T> patchSourceDisplayOrder(
    packageName: String,
    recommendedVersion: String?,
    patchesOf: (T) -> List<PatchInfo>
): Comparator<T> = compareByDescending<T> {
    customPatchCount(patchesOf(it), packageName, recommendedVersion)
}.thenByDescending {
    customPatchCount(patchesOf(it), packageName)
}

/**
 * Keeps each patch name selected in one of [sources] only. Sources often carry copies of the same
 * patches, and running two copies of a patch makes them collide in the APK, so the copy of
 * [keepFrom] stays, the one the user just picked, or else the copy of the source ranked first for
 * the app by [patchSourceDisplayOrder]. Sources outside [sources] are left as they are.
 */
internal fun Map<Int, Set<String>>.oneSourcePerPatch(
    sources: List<PatchBundleInfo.Scoped>,
    packageName: String,
    version: String?,
    keepFrom: Int? = null
): Map<Int, Set<String>> {
    val ranked = sources.sortedWith(patchSourceDisplayOrder(packageName, version)).map { it.uid }
    val order = keepFrom?.takeIf { it in ranked }?.let { listOf(it) + (ranked - it) } ?: ranked
    val taken = mutableSetOf<String>()
    val kept = order.associateWith { uid -> this[uid].orEmpty().filterTo(mutableSetOf()) { taken.add(it) } }
    return mapValues { (uid, names) -> kept[uid] ?: names }.filterValues { it.isNotEmpty() }
}
