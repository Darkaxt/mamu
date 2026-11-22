package app.morphe.manager.downloaders

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Publication evidence belongs to the chosen shop build, never the download/file clock. */
data class ApkPublication(val day: String, val pageUrl: String) {
    companion object {
        fun isBuildPage(url: String): Boolean = runCatching {
            val path = URI(url).path
            when (DownloadPolicy(false).source(url)) {
                DownloadSource.APK_MIRROR -> path.endsWith("-android-apk-download/")
                DownloadSource.UPTODOWN -> Uptodown.fileId(url) != null
                DownloadSource.APK_PURE -> path.contains("/download/") && !path.endsWith("/download/")
                DownloadSource.APK_COMBO -> Regex("/download/phone-[^/]+-apk/?$").containsMatchIn(path)
                else -> false
            }
        }.getOrDefault(false)

        fun parse(url: String, doc: Document, expectedVersion: String?): ApkPublication? {
            if (!isBuildPage(url)) return null
            val days = mutableSetOf<String>()
            fun application(value: Any?) {
                when (value) {
                    is JSONArray -> (0 until value.length()).forEach { application(value.opt(it)) }
                    is JSONObject -> {
                        value.optJSONArray("@graph")?.let(::application)
                        val types = when (val type = value.opt("@type")) {
                            is JSONArray -> (0 until type.length()).map { type.optString(it) }
                            is String -> listOf(type)
                            else -> emptyList()
                        }
                        if (types.none { it in setOf("SoftwareApplication", "MobileApplication") }) return
                        val version = value.optString("softwareVersion").takeIf { it.isNotBlank() } ?: return
                        if (expectedVersion != null && version != expectedVersion &&
                            version != ApkMirrorSearch.releaseVersion(expectedVersion)) return
                        publicationDay(value.optString("datePublished"))?.let(days::add)
                    }
                }
            }
            doc.select("script[type=application/ld+json]").forEach { script ->
                runCatching { application(JSONTokener(script.data()).nextValue()) }
            }
            if (DownloadPolicy(false).source(url) == DownloadSource.APK_MIRROR) {
                // #file is the selected variant's metadata tab; sidebar/release-list dates are excluded.
                doc.select("#file [data-utcdate]").forEach { element ->
                    val raw = element.attr("data-utcdate")
                    runCatching { LocalDateTime.parse(raw,
                        DateTimeFormatter.ofPattern("MM/dd/uuuu HH:mm 'UTC'", Locale.US)
                            .withResolverStyle(ResolverStyle.STRICT)).toLocalDate().toString() }
                        .getOrNull()?.let(days::add)
                }
                doc.select("#file [itemprop=datePublished]").forEach { element ->
                    publicationDay(element.attr("content").ifBlank { element.attr("datetime") })?.let(days::add)
                }
            }
            return days.singleOrNull()?.let { ApkPublication(it, url.substringBefore('#')) }
        }
    }
}

internal fun publicationDay(raw: String?): String? = raw?.takeIf { it.isNotBlank() }?.let {
    runCatching { LocalDate.parse(it).toString() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString() }.getOrNull()
}

/** Small durable metadata adjunct, keyed by admitted manifest identity. No APK/selection ownership. */
class ApkPublications(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("apk-publications", Context.MODE_PRIVATE))
    fun get(packageName: String, version: String?, versionCode: Long?): ApkPublication? = runCatching {
        val key = key(packageName, version, versionCode) ?: return null
        val data = JSONObject(prefs.getString(key, null) ?: return null)
        val day = publicationDay(data.getString("day")) ?: return null
        val page = data.getString("pageUrl")
        if (!ApkPublication.isBuildPage(page)) return null
        ApkPublication(day, page)
    }.getOrNull()
    fun remember(identity: ArchiveIdentity, publication: ApkPublication?) {
        val key = key(identity.packageName, identity.versionName, identity.versionCode) ?: return
        if (publication == null || publicationDay(publication.day) == null || !ApkPublication.isBuildPage(publication.pageUrl)) return
        if (!prefs.edit().putString(key, JSONObject().put("day", publication.day)
            .put("pageUrl", publication.pageUrl).toString()).commit())
            android.util.Log.w("Morphe ApkPublication", "APK publication metadata could not be retained.")
    }
    companion object {
        internal fun key(packageName: String, version: String?, versionCode: Long?): String? =
            if (packageName.isBlank() || version.isNullOrBlank() || versionCode == null || versionCode < 0) null
            else JSONArray(listOf(packageName, version, versionCode)).toString()
    }
}
