package app.morphe.manager.domain.bundles

import app.morphe.manager.domain.apk.SavedApkInfo

/** Selects an original already parsed from disk; safety warnings still run in the normal importer. */
internal fun canReuseOriginal(saved: SavedApkInfo?, requestedVersion: String?, targets: List<BundledAppTarget>): Boolean {
    if (saved == null || (requestedVersion != null && saved.version != requestedVersion)) return false
    if (targets.isEmpty()) return true
    val matching = targets.filter { it.target.version == saved.version }
        .ifEmpty { targets.filter { it.target.version == null } }
    return matching.any {
        it.buildCodes == null || saved.versionCode?.let { code ->
            it.buildCodes.any { allowed -> allowed.toLong() == code }
        } == true
    }
}
