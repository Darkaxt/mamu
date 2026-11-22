package app.morphe.manager.ui.viewmodel

import app.morphe.manager.domain.bundles.BundledAppTarget
import app.morphe.manager.domain.bundles.offered
import app.morphe.manager.domain.bundles.installableOnDevice
import app.morphe.manager.network.PlayStoreListing

enum class PlayVersionVerdict { UNKNOWN, BEHIND, MATCHED }
data class HomePlayVersion(val supported: String?, val official: String?, val observedAt: Long?,
    val verdict: PlayVersionVerdict)

private val ComparablePlayVersion = Regex("""([0-9]+(?:\.[0-9]+)*)(?:[- ](alpha|beta|rc)([0-9]+))?""", RegexOption.IGNORE_CASE)

private fun compareVersionNumber(a: String, b: String): Int {
    val left = a.trimStart('0').ifEmpty { "0" }
    val right = b.trimStart('0').ifEmpty { "0" }
    return left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
}

/** Version-name feedback, not proof that a particular Play APK/build can be patched. */
internal fun comparePlayVersion(supported: String?, listing: PlayStoreListing?): PlayVersionVerdict {
    val official = listing?.version
    if (supported.isNullOrBlank() || official.isNullOrBlank() || listing.versionVerified != true ||
        listing.versionObservedAt == null || listing.versionSource == null) return PlayVersionVerdict.UNKNOWN
    val supportedMatch = ComparablePlayVersion.matchEntire(supported) ?: return PlayVersionVerdict.UNKNOWN
    val officialMatch = ComparablePlayVersion.matchEntire(official) ?: return PlayVersionVerdict.UNKNOWN
    // Compare only the same explicit channel; never strip opaque flavour/architecture suffixes.
    if (!supportedMatch.groupValues[2].equals(officialMatch.groupValues[2], ignoreCase = true))
        return PlayVersionVerdict.UNKNOWN
    val left = supportedMatch.groupValues[1].split('.')
    val right = officialMatch.groupValues[1].split('.')
    for (index in 0 until maxOf(left.size, right.size)) {
        val order = compareVersionNumber(left.getOrElse(index) { "0" }, right.getOrElse(index) { "0" })
        if (order < 0) return PlayVersionVerdict.BEHIND
        if (order > 0) return PlayVersionVerdict.UNKNOWN
    }
    val channelOrder = compareVersionNumber(supportedMatch.groupValues[3], officialMatch.groupValues[3])
    return when {
        channelOrder < 0 -> PlayVersionVerdict.BEHIND
        channelOrder > 0 -> PlayVersionVerdict.UNKNOWN
        else -> PlayVersionVerdict.MATCHED
    }
}

/** Default-dispatcher projection; use the existing source gates and strictly installable targets. */
internal fun projectHomePlayVersions(targets: Map<String, List<BundledAppTarget>>,
    listings: Map<String, PlayStoreListing>): Map<String, HomePlayVersion> = targets.mapValues { (pkg, entries) ->
    val selected = entries.offered().filter { it.target.version != null && it.installableOnDevice() }.maxByOrNull { it.target }
    val supported = selected?.target?.version
    val listing = listings[pkg]?.takeIf { it.packageName == pkg }
    HomePlayVersion(supported, listing?.version, listing?.versionObservedAt, comparePlayVersion(supported, listing))
}
