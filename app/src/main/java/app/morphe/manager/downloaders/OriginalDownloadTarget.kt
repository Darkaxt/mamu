package app.morphe.manager.downloaders

import app.morphe.manager.domain.bundles.BundledAppTarget
import android.os.Build
import app.morphe.manager.util.compareVersions
import app.morphe.manager.util.versionSupportsAbis

data class OriginalDownloadTarget(val packageName: String, val version: String, val buildCodes: Set<Int>?) {
    fun accepts(identity: ArchiveIdentity?): Boolean = identity != null && identity.packageName == packageName &&
        identity.versionName == version && (buildCodes == null || identity.versionCode in buildCodes.map(Int::toLong))
}

/** Keep an explicit source's build intent; unrestricted rows do not invent a build constraint. */
fun downloadBuildCodes(targets: List<BundledAppTarget>, version: String?, sourceUid: Int?): Set<Int>? {
    val matching = targets.filter { it.target.version == version && (sourceUid == null || it.bundleUid == sourceUid) }
    return if (matching.isEmpty() || matching.any { it.buildCodes == null }) null
        else matching.flatMap { it.buildCodes.orEmpty() }.toSet()
}

private fun List<BundledAppTarget>.downloadable(sdk: Int, abis: List<String>) = filter {
    it.target.version != null && versionSupportsAbis(it.target.version, abis) && (!it.target.isExperimental || it.experimentalEnabled) &&
        (it.target.minSdk == null || sdk >= checkNotNull(it.target.minSdk))
}

fun supportedDownloadTarget(packageName: String, baseline: String?, targets: List<BundledAppTarget>, sdk: Int, abis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()): OriginalDownloadTarget? {
    if (baseline.isNullOrBlank()) return null
    val visible = targets.downloadable(sdk, abis)
    val newest = visible.maxByOrNull { it.target }?.target?.version ?: return null
    if (compareVersions(newest, baseline) <= 0) return null
    val matching = visible.filter { it.target.version == newest }
    val codes = if (matching.any { it.buildCodes == null }) null else matching.flatMap { it.buildCodes.orEmpty() }.toSet()
    return OriginalDownloadTarget(packageName, newest, codes)
}

fun OriginalDownloadTarget.stillSupported(targets: List<BundledAppTarget>, sdk: Int, abis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()): Boolean =
    targets.downloadable(sdk, abis).any { it.target.version == version &&
        (it.buildCodes == null || buildCodes == null || it.buildCodes.any { code -> code in buildCodes }) }

fun OriginalDownloadTarget.acceptedByCurrent(identity: ArchiveIdentity?, targets: List<BundledAppTarget>, sdk: Int, abis: List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()): Boolean =
    accepts(identity) && targets.downloadable(sdk, abis).any { it.target.version == version &&
        (it.buildCodes == null || identity?.versionCode in it.buildCodes.map(Int::toLong)) }

enum class OriginalResume { CANCEL, READY, TRANSFER, RESOLVE, INTERRUPTED }
fun originalResume(phase: String?, cancelled: Boolean, hasTransfer: Boolean, validCache: Boolean): OriginalResume = when {
    cancelled -> OriginalResume.CANCEL
    validCache -> OriginalResume.READY
    hasTransfer -> OriginalResume.TRANSFER
    phase.isNullOrBlank() -> OriginalResume.RESOLVE
    else -> OriginalResume.INTERRUPTED // An uncertain browser action requires explicit user Retry.
}

/** Filter explicit incompatible ABIs; ambiguous remaining choices need the normal browser flow. */
fun backgroundDownloadChoice(choices: List<RemotePage.Choice>, abis: List<String>): RemotePage.Choice? {
    val known = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    val compatible = choices.filter { choice ->
        val declared = known.filter { Regex("(?<![A-Za-z0-9_])${Regex.escape(it)}(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE).containsMatchIn(choice.label) }
        declared.isEmpty() || declared.any { it in abis }
    }
    return compatible.singleOrNull()
}
