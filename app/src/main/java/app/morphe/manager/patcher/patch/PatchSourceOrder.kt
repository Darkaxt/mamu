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
