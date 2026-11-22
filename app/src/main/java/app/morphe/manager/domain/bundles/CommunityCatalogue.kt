package app.morphe.manager.domain.bundles

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.Locale

@Serializable
internal data class CommunityCatalogue(val bundles: List<CommunitySource>) {
    fun endpoints(): List<String> = bundles.mapNotNull { it.endpoint() }
        .distinctBy(::communitySourceKey)
}

@Serializable
internal data class CommunitySource(val source: String, val repo: String) {
    fun endpoint(): String? {
        val parts = repo.removeSuffix(".git").split('/')
        if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")) }) return null
        return when (source.lowercase(Locale.ROOT)) {
            "github" -> "https://raw.githubusercontent.com/${parts.joinToString("/")}/main/patches-bundle.json"
            "gitlab" -> "https://gitlab.com/${parts.joinToString("/")}/-/raw/main/patches-bundle.json"
            else -> null
        }
    }
}

internal fun communitySourceKey(endpoint: String): String? = runCatching {
    val uri = URI(endpoint)
    val parts = uri.path.orEmpty().trim('/').split('/')
    if (parts.size < 2 || parts.take(2).any(String::isBlank)) return null
    val host = when (uri.host?.lowercase(Locale.ROOT)) {
        "github.com", "raw.githubusercontent.com" -> "github"
        "gitlab.com" -> "gitlab"
        else -> return null
    }
    "$host=${parts[0]}/${parts[1].removeSuffix(".git")}".lowercase(Locale.ROOT)
}.getOrNull()

internal fun missingCommunitySources(
    incoming: List<String>,
    existing: Set<String>,
    excluded: Set<String>,
    blocked: Set<String>
): List<String> = incoming.distinctBy(::communitySourceKey).filter { endpoint ->
    val key = communitySourceKey(endpoint)
    key != null && key !in existing && key !in excluded && key !in blocked
}
