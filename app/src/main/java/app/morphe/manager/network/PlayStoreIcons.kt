package app.morphe.manager.network

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** On-demand lookup for visible missing icons. Coil owns image-byte memory/disk caching. */
data class PlayStoreListing(val packageName: String, val iconUrl: String?, val categoryId: String?,
    val name: String? = null, val developer: String? = null, val description: String? = null,
    val tags: List<String> = emptyList(), val version: String? = null,
    val versionSource: String? = null, val versionObservedAt: Long? = null,
    /** Disk observations are informational until this process verifies the listing again. */
    val versionVerified: Boolean = false)

class PlayStoreIcons(
    private val directory: File,
    private val fetchListing: suspend (String) -> String
) {
    // Serialize lookups and share their result; scrolling must not flood Play with requests.
    private val lock = Mutex()
    private val session = ConcurrentHashMap<String, Optional<PlayStoreListing>>()
    private val icons = ConcurrentHashMap<String, String>()
    private val primed = ConcurrentHashMap.newKeySet<String>()
    private val versionAttempts = ConcurrentHashMap.newKeySet<String>()
    private val _listings = MutableStateFlow<Map<String, PlayStoreListing>>(emptyMap())
    val listings = _listings.asStateFlow()

    /** Memory only: safe during composition. No file, network, parse or decode work. */
    fun peekIcon(packageName: String): String? = icons[packageName]

    private fun validPackage(value: String) = value.matches(PACKAGE)

    private fun readIcon(packageName: String): String? = icons[packageName] ?: try {
        directory.resolve("$packageName.url").takeIf { it.isFile && it.length() <= 2048 }?.readText()?.let(::playIconUrl)
            ?.also { icons[packageName] = it }
    } catch (_: IOException) { null }

    private fun readListing(packageName: String): PlayStoreListing? = try {
        val file = directory.resolve("$packageName.json")
        if (!file.isFile || file.length() > 32768) null else {
            val data = JSONObject(file.readText())
            if (data.optString("package") != packageName || data.optInt("schema") !in 1..2) null
            else PlayStoreListing(packageName, data.optString("icon").takeIf(String::isNotBlank)?.let(::playIconUrl),
                data.optString("category").takeIf(String::isNotBlank),
                data.optString("name").takeIf(String::isNotBlank)?.take(256),
                data.optString("developer").takeIf(String::isNotBlank)?.take(256),
                storeDescriptionPreview(data.optString("description")),
                data.optJSONArray("tags")?.let { a -> (0 until minOf(a.length(), 5)).map { a.optString(it).take(80) } }.orEmpty(),
                data.optString("version").takeIf { it.isNotBlank() && it.length <= 256 },
                data.optString("versionSource").takeIf(String::isNotBlank),
                data.optLong("versionObservedAt").takeIf { it > 0 })
        }
    } catch (_: IOException) { null } catch (_: org.json.JSONException) { null }

    private fun remember(listing: PlayStoreListing) {
        session[listing.packageName] = Optional.of(listing)
        listing.iconUrl?.let { icons[listing.packageName] = it }
        _listings.update { if (it[listing.packageName] == listing) it else it + (listing.packageName to listing) }
    }

    /** Restore all known disk metadata before the home-card snapshot is emitted; never fetch. */
    suspend fun prime(packages: Set<String>) = withContext(Dispatchers.IO) {
        val restored = mutableMapOf<String, PlayStoreListing>()
        for (pkg in packages) if (validPackage(pkg) && primed.add(pkg)) {
            val cached = session[pkg]
            val value = if (cached != null) cached.orElse(null) else readListing(pkg)
            if (value != null) {
                session[pkg] = Optional.of(value)
                value.iconUrl?.let { icons[pkg] = it }
                restored[pkg] = value
            }
            readIcon(pkg)
        }
        if (restored.isNotEmpty()) _listings.update { it + restored }
    }

    suspend fun resolve(packageName: String): String? = withContext(Dispatchers.IO) {
        if (!validPackage(packageName)) return@withContext null
        // Both memory and disk icon hits are independent of network admission.
        readIcon(packageName) ?: resolveListing(packageName)?.iconUrl
    }

    suspend fun resolveListing(packageName: String, refreshVersion: Boolean = false): PlayStoreListing? = withContext(Dispatchers.IO) {
        if (!validPackage(packageName)) return@withContext null
        if (!refreshVersion || packageName in versionAttempts) {
            session[packageName]?.let { return@withContext it.orElse(null) }
            readListing(packageName)?.let { remember(it); return@withContext it }
        }
        lock.withLock {
            if (!refreshVersion || packageName in versionAttempts) {
                session[packageName]?.let { return@withLock it.orElse(null) }
                readListing(packageName)?.let { remember(it); return@withLock it }
            }
            val listing = try {
                parsePlayStoreListing(packageName,
                    fetchListing("https://play.google.com/store/apps/details?id=$packageName&hl=en_US"))
                    ?.copy(versionObservedAt = System.currentTimeMillis(), versionVerified = true)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: IOException) { null }
            versionAttempts.add(packageName)
            // Misses last for this process only. Cancelled requests never poison the cache.
            if (listing == null) {
                val cached = session[packageName]?.orElse(null) ?: readListing(packageName)
                if (cached != null) remember(cached.copy(versionVerified = false))
                else session[packageName] = Optional.empty()
            }
            else {
                remember(listing)
                val file = directory.resolve("$packageName.json")
                val staging = directory.resolve("$packageName.json.tmp")
                try {
                    directory.mkdirs()
                    staging.writeText(JSONObject().put("schema", 2).put("package", packageName)
                        .put("icon", listing.iconUrl.orEmpty()).put("category", listing.categoryId.orEmpty())
                        .put("name", listing.name.orEmpty()).put("developer", listing.developer.orEmpty())
                        .put("description", listing.description.orEmpty()).put("tags", JSONArray(listing.tags))
                        .put("version", listing.version.orEmpty()).put("versionSource", listing.versionSource.orEmpty())
                        .put("versionObservedAt", listing.versionObservedAt ?: 0).toString())
                    Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: IOException) { /* A disk cache failure does not discard resolved metadata. */ }
                finally { staging.delete() }
            }
            listing ?: session[packageName]?.orElse(null)
        }
    }

    private companion object { val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+") }
}

internal fun parsePlayStoreIcon(packageName: String, html: String): String? = parsePlayStoreListing(packageName, html)?.iconUrl

internal fun parsePlayStoreListing(packageName: String, html: String): PlayStoreListing? {
    val document = Jsoup.parse(html)
    val listing = document.selectFirst("link[rel=canonical]")?.attr("href") ?: return null
    val uri = runCatching { URI(listing) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "play.google.com" || uri.rawUserInfo != null ||
        uri.port !in listOf(-1, 443) || uri.path != "/store/apps/details") return null
    val id = uri.rawQuery.orEmpty().split('&').singleOrNull { it.substringBefore('=') == "id" }
        ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    if (id != packageName) return null
    val icon = document.selectFirst("meta[property=og:image]")?.attr("content")?.let(::playIconUrl)
    val data = document.select("script[type=application/ld+json]").asSequence().mapNotNull {
        runCatching { JSONObject(it.data()) }.getOrNull()
    }.firstOrNull { it.optString("@type") in setOf("SoftwareApplication", "MobileApplication") }
    fun field(key: String, length: Int) = data?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }?.take(length)
    val category = field("applicationCategory", 80)?.takeIf { it.matches(Regex("[A-Z_]+")) }
    val name = field("name", 256)
    if (icon == null && category == null && name == null) return null
    val keywords = data?.opt("keywords")
    val tags = when (keywords) {
        is JSONArray -> (0 until minOf(5, keywords.length())).map { keywords.optString(it).take(80) }
        is String -> keywords.split(',').map(String::trim).filter(String::isNotBlank).take(5).map { it.take(80) }
        else -> emptyList()
    }
    val versions = mutableListOf<Pair<String, String>>()
    // Structured version evidence must identify this listing itself, not a related app.
    (data?.takeIf { it.optString("url") == listing }?.opt("softwareVersion") as? String)?.takeIf { it.isNotBlank() && it.length <= 256 }
        ?.let { versions += it to "softwareVersion" }
    // Observed Play application-record schema, captured 2026-10-18. Bind its own package,
    // not related-app records; never scan numbers (ratings, dates and SDKs are numbers too).
    val callback = Regex("""^AF_initDataCallback\(\{key:\s*['"]ds:5['"].*?\bdata:\s*""", RegexOption.DOT_MATCHES_ALL)
    val records = document.select("script").mapNotNull { script ->
        val raw = script.data().trim()
        callback.find(raw)?.let { match -> runCatching { JSONTokener(raw.substring(match.range.last + 1)).nextValue() as? JSONArray }.getOrNull() }
    }
    if (records.size == 1) {
        val app = records.single().optJSONArray(1)?.optJSONArray(2)
        if (app?.optJSONArray(77)?.opt(0) == packageName) {
            (app.optJSONArray(140)?.optJSONArray(0)?.optJSONArray(0)?.opt(0) as? String)
                ?.takeIf { it.isNotBlank() && it.length <= 256 }?.let { versions += it to "ds5.application.version" }
        }
    }
    val version = versions.takeIf { it.map { v -> v.first }.distinct().size == 1 }?.firstOrNull()
    return PlayStoreListing(packageName, icon, category, name,
        data?.optJSONObject("author")?.optString("name")?.takeIf(String::isNotBlank)?.take(256), storeDescriptionPreview(field("description", 2048)), tags,
        version?.first, version?.second)
}

/** Called by IO listing fetch/restore, never by composition. Old cached HTML works too. */
internal fun storeDescriptionPreview(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val input = value.take(2048)
    val plain = if ('<' in input || '&' in input) {
        val body = Jsoup.parseBodyFragment(input).body()
        body.select("p,div,li").forEach { it.appendText("\n\n") }
        body.wholeText()
    } else input
    return plain.replace("\r\n", "\n")
        .split(Regex("\n\\s*\n")).asSequence().map(String::trim).filter(String::isNotBlank)
        .take(2).joinToString(" ").replace(Regex("\\s+"), " ").take(400).takeIf(String::isNotBlank)
}

private fun playIconUrl(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host !in setOf("play-lh.googleusercontent.com", "lh3.googleusercontent.com") ||
        uri.rawUserInfo != null || uri.port !in listOf(-1, 443) || uri.path.isNullOrBlank() ||
        uri.rawQuery != null || uri.rawFragment != null) return null
    return value.substringBefore('=') + "=w512-h512-rw"
}

/** Uses the existing client's network policy; no new operation/cancellation deadline. */
internal suspend fun fetchPlayStoreListing(client: HttpClient, url: String): String =
    client.prepareGet(url).execute { response ->
        if (response.status != HttpStatusCode.OK) throw IOException("Play listing is unavailable")
        val output = ByteArrayOutputStream()
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (output.size() + count > 4 * 1024 * 1024) throw IOException("Play listing exceeds size limit")
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }
