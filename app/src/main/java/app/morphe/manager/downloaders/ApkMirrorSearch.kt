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

    fun matchesVersion(label: String, requested: String?): Boolean = requested == null ||
        Regex("(?<![A-Za-z0-9._-])${Regex.escape(requested)}(?![A-Za-z0-9._-])").containsMatchIn(label)
}
