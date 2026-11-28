package app.morphe.manager.downloaders

import app.morphe.manager.util.versionArchitecture
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Converts store-directed search links without admitting Google as a download host. */
internal object ApkMirrorSearch {
    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size != 2) null else URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }.toMap()

    fun translate(value: String, packageName: String? = null, version: String? = null): String = runCatching {
        val uri = URI(value)
        if (uri.host?.lowercase() !in setOf("google.com", "www.google.com") ||
            uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null || uri.port !in setOf(-1, 80, 443)) return value
        val params = query(uri)
        if (uri.path == "/url") {
            val destination = params["url"] ?: params["q"] ?: return value
            return DownloadPolicy(false).pageUrl(destination) ?: value
        }
        if (uri.path != "/search") return value
        val search = params["q"] ?: return value
        // Keep multi-store intent for the request-owned discovery flow and browser escape.
        if (Regex("(?i)\\b(?:uptodown|apkpure|apkcombo|apkcube)(?:\\.com|\\.net)?\\b").containsMatchIn(search)) return value
        val mirror = Regex("(?i)(?:^|[\\s(])(?:site:)?(?:www\\.)?apkmirror(?:\\.com)?(?=[\\s)]|$)")
        if (!mirror.containsMatchIn(search)) return value
        val cleaned = search.replace(Regex("(?i)site:[^\\s()]+"), " ")
            .replace(Regex("(?i)\\b(?:OR|AND|APKMirror)\\b(?:\\.com)?"), " ")
            .replace(Regex("[()]"), " ").replace(Regex("\\s+"), " ").trim()
        val constraint = Regex("(?i)\\b(?:nodpi|arm64-v8a|armeabi-v7a|x86_64|x86)\\b")
        val requested = version ?: Regex("""[0-9]+(?:\.[0-9]+)+(?:-[A-Za-z0-9_.-]+)?""").find(cleaned)?.value
        val foundConstraints = constraint.findAll(cleaned).map { it.value.lowercase() }.toSet()
        val constraints = versionArchitecture(requested)?.let { setOf(it) + (foundConstraints intersect setOf("nodpi")) }
            ?: foundConstraints
        val terms = if (packageName != null) {
            listOfNotNull("\"$packageName\"", requested?.let { "\"${releaseVersion(it)}\"" }).joinToString(" ")
        } else (requested?.let { cleaned.replace(it, releaseVersion(it)) } ?: cleaned)
            .replace(constraint, " ").replace(Regex("\\s+"), " ").trim()
        if (terms.isBlank()) return value
        url(terms, constraints)
    }.getOrDefault(value)

    fun nativeSearch(packageName: String, version: String?, constraints: Set<String> = emptySet()): String {
        val terms = listOfNotNull("\"$packageName\"", version?.let { "\"${releaseVersion(it)}\"" }).joinToString(" ")
        val filters = versionArchitecture(version)?.let { setOf(it) + (constraints intersect setOf("nodpi")) } ?: constraints
        return url(terms, filters)
    }

    private fun url(terms: String, constraints: Set<String>): String {
        val filters = buildString {
            if (constraints.isNotEmpty()) append("&filter=true")
            if ("nodpi" in constraints) append("&dpi%5B%5D=nodpi")
            val abis = constraints - "nodpi"
            if (abis.isNotEmpty()) {
                abis.forEach { append("&arch%5B%5D=").append(it) }
                append("&arch%5B%5D=universal")
            }
        }
        return "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=" + URLEncoder.encode(terms, "UTF-8") + filters
    }

    /** Packaging/channel/ABI suffixes are only removed for release discovery, never APK identity. */
    fun releaseVersion(version: String): String = if (versionArchitecture(version) != null) {
        Regex("""^[0-9]+(?:\.[0-9]+)+""").find(version)?.value ?: version
    } else version

    fun matchesRelease(label: String, requested: String?): Boolean {
        if (requested == null || releaseVersion(requested) == requested) return matchesVersion(label, requested)
        return Regex("(?<![A-Za-z0-9.])${Regex.escape(releaseVersion(requested))}(?![0-9.])").containsMatchIn(label)
    }

    fun terms(value: String): String? = runCatching {
        val uri = URI(value)
        if (DownloadPolicy(false).source(value) != DownloadSource.APK_MIRROR || uri.path !in setOf("", "/")) null
        else query(uri)["s"]?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun requestedVersion(value: String): String? {
        val terms = terms(value) ?: return null
        val quoted = Regex("\"([^\"]+)\"").findAll(terms).map { it.groupValues[1] }.toList()
        return quoted.getOrNull(1) ?: Regex("(?<![A-Za-z0-9.])[0-9]+(?:\\.[0-9A-Za-z]+)+(?:[-_][A-Za-z0-9.]+)?(?![A-Za-z0-9.])")
            .find(terms)?.value
    }

    private val abiTokens = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "universal", "noarch")

    /**
     * The variant this device should get from a release page's table, so nobody has to know what
     * an ABI or a DPI is. Ranks by the device's own ABI order, then a full APK over a bundle, then
     * nodpi, then the lowest minimum Android. Variants for another architecture never qualify.
     * Null when no variant fits or two fit equally well (for example differently signed builds),
     * leaving the choice to the user.
     */
    fun bestVariant(choices: List<RemotePage.Choice>, supportedAbis: List<String>): RemotePage.Choice? {
        if (supportedAbis.isEmpty()) return null
        val ranked = choices.mapNotNull { choice ->
            val label = choice.label.lowercase()
            val abis = abiTokens.filter { Regex("(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9_-])").containsMatchIn(label) }
            val abiRank = when {
                abis.isEmpty() -> supportedAbis.size + 1
                abis.any { it in supportedAbis } -> abis.filter { it in supportedAbis }.minOf { supportedAbis.indexOf(it) }
                "universal" in abis || "noarch" in abis -> supportedAbis.size
                else -> return@mapNotNull null
            }
            val bundle = if (Regex("\\bbundle\\b").containsMatchIn(label)) 1 else 0
            val dpi = if (Regex("\\bnodpi\\b").containsMatchIn(label)) 0 else 1
            val minAndroid = Regex("android ([0-9]+)(?:\\.([0-9]+))?(l)?\\+").find(label)?.let {
                it.groupValues[1].toInt() * 100 + (it.groupValues[2].toIntOrNull() ?: 0) * 10 + if (it.groupValues[3].isNotEmpty()) 5 else 0
            } ?: Int.MAX_VALUE
            choice to listOf(abiRank, bundle, dpi, minAndroid)
        }
        val comparator = Comparator<List<Int>> { a, b -> a.zip(b).map { (x, y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0 }
        val best = ranked.minWithOrNull(Comparator { a, b -> comparator.compare(a.second, b.second) }) ?: return null
        return best.first.takeIf { ranked.count { comparator.compare(it.second, best.second) == 0 } == 1 }
    }

    fun matchesVersion(label: String, requested: String?): Boolean = requested == null ||
        Regex("(?<![A-Za-z0-9._-])${Regex.escape(requested)}(?![A-Za-z0-9._-])").containsMatchIn(label)
}
