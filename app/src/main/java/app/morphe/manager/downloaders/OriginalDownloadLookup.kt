package app.morphe.manager.downloaders

import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import app.morphe.manager.patcher.logger.LogLevel

/** A definitive absence, distinct from an uncertain browser/transport/parser result. */
class NoMatchingApk(val source: DownloadSource, version: String?) : IOException(
    "${source.label} has no matching release${version?.let { " for $it" }.orEmpty()}.")

/** The lookup reached a page it already checked; another site would not change that. */
class LookupCycle(message: String) : IOException(message)

/** Native shop discovery. Google links are recognized only to translate incoming intent. */
internal object StoreSearch {
    private fun parameters(uri: URI) = uri.rawQuery.orEmpty().split('&').mapNotNull {
        val pair = it.split('=', limit = 2)
        if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
    }.toMap()
    fun google(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host in setOf("google.com", "www.google.com") &&
            uri.port == -1 && uri.rawUserInfo == null && uri.path == "/search"
    }.getOrDefault(false)
    fun hasIntent(url: String, target: OriginalDownloadTarget): Boolean = runCatching {
        val text = parameters(URI(url))["q"].orEmpty()
        Regex("(?<![A-Za-z0-9_.])${Regex.escape(target.packageName)}(?![A-Za-z0-9_.])").containsMatchIn(text) && text.contains("apkmirror", true) &&
            ApkMirrorSearch.matchesRelease(text, target.version)
    }.getOrDefault(false)
    fun query(target: OriginalDownloadTarget, source: DownloadSource): String {
        require(target.packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")))
        val name = URLEncoder.encode(target.packageName, "UTF-8")
        return when (source) {
            DownloadSource.APK_MIRROR -> mirror(target, emptyList())
            DownloadSource.APK_PURE -> "https://apkpure.net/p/$name"
            DownloadSource.UPTODOWN -> "https://en.uptodown.com/android/search?query=$name"
            DownloadSource.APK_COMBO -> "https://apkcombo.com/search?q=$name"
        }
    }
    fun mirror(target: OriginalDownloadTarget, abis: List<String>): String =
        ApkMirrorSearch.nativeSearch(target.packageName, target.version, abis.firstOrNull()?.let(::setOf).orEmpty())

    fun parse(url: String, html: String, target: OriginalDownloadTarget, source: DownloadSource): List<RemotePage.Choice> {
        val policy = DownloadPolicy(false)
        val uri = runCatching { URI(url) }.getOrNull()
        val webHosts = when (source) {
            DownloadSource.APK_PURE -> setOf("apkpure.net", "www.apkpure.net", "m.apkpure.net", "apkpure.com", "www.apkpure.com", "m.apkpure.com")
            DownloadSource.APK_COMBO -> setOf("apkcombo.com", "www.apkcombo.com")
            DownloadSource.UPTODOWN -> setOf("en.uptodown.com")
            DownloadSource.APK_MIRROR -> setOf("www.apkmirror.com", "apkmirror.com")
        }
        if (uri == null || policy.pageUrl(url) == null || uri.host !in webHosts || uri.scheme != "https" || uri.port != -1)
            throw IOException("${source.label} lookup redirected away from its requested shop.")
        val doc = Jsoup.parse(html, url)
        if (doc.select("#captcha-form, #challenge-stage, iframe[src*=captcha], form[action*='/sorry/']").isNotEmpty() ||
            doc.title().startsWith("Just a moment", true) || doc.text().contains("unusual traffic", true))
            throw IOException("${source.label} requires a browser security check. Use Open in Browser.")
        val results = when (source) {
            DownloadSource.APK_PURE -> doc.selectFirst("[data-package_name='${target.packageName}']")?.let { doc }
            DownloadSource.UPTODOWN -> doc.selectFirst("#content-list")?.takeIf {
                doc.selectFirst("#search-query-text")?.text() == target.packageName
            }
            DownloadSource.APK_COMBO -> doc.takeIf {
                doc.select("form[action*='/search'] input[name=q]").any { it.attr("value") == target.packageName }
            }
            DownloadSource.APK_MIRROR -> null // Uses the existing release/variant resolver.
        } ?: throw IOException("${source.label} did not return readable package lookup results. Use Retry or Open in Browser.")
        val version = ApkMirrorSearch.releaseVersion(target.version)
        if (!version.matches(Regex("[A-Za-z0-9._+-]+"))) throw IOException("Choose this version manually in the browser.")
        val choices = results.select(if (source == DownloadSource.UPTODOWN) ".item .name a[href]" else "a[href]").mapNotNull { anchor ->
            // Jsoup absUrl normalizes away userinfo; inspect the original URI components.
            var link = runCatching { URI(url).resolve(anchor.attr("href")).toString() }.getOrNull() ?: return@mapNotNull null
            link = policy.pageUrl(link)?.takeIf { policy.source(it) == source } ?: return@mapNotNull null
            val uri = URI(link)
            val parts = uri.path.split('/')
            when (source) {
                DownloadSource.APK_PURE, DownloadSource.APK_COMBO -> {
                    val hosts = if (source == DownloadSource.APK_PURE) setOf("apkpure.net", "www.apkpure.net", "m.apkpure.net", "apkpure.com", "www.apkpure.com", "m.apkpure.com")
                        else setOf("apkcombo.com", "www.apkcombo.com")
                    if (uri.host !in hosts) return@mapNotNull null
                    val index = parts.indexOf(target.packageName)
                    if (index < 1) return@mapNotNull null
                    if (source == DownloadSource.APK_PURE && parts.getOrNull(index + 1) !in setOf("download", "versions"))
                        return@mapNotNull null
                    if (source == DownloadSource.APK_COMBO && index != 2) return@mapNotNull null
                    val base = parts.take(index + 1).joinToString("/")
                    val path = base + if (source == DownloadSource.APK_PURE) "/download/$version" else "/download/phone-$version-apk"
                    link = URI("https", null, uri.host, -1, path, null, null).toASCIIString()
                }
                DownloadSource.UPTODOWN -> {
                    // Search candidates are not admitted originals. fetch() verifies their package table next.
                    if (!uri.host.matches(Regex("[a-z0-9-]+\\.en\\.uptodown\\.com")) || uri.path != "/android") return@mapNotNull null
                    link = URI("https", null, uri.host, -1, uri.path, null, null).toASCIIString()
                }
                DownloadSource.APK_MIRROR -> return@mapNotNull null // Its native search has stronger release filters.
            }
            RemotePage.Choice(link, "${source.label} · ${anchor.text().ifBlank { target.packageName }}")
        }.distinctBy { it.url }
        if (choices.isEmpty()) {
            if (source == DownloadSource.APK_PURE) throw IOException("APKPure did not return readable version navigation. Use Retry or Open in Browser.")
            throw NoMatchingApk(source, target.version)
        }
        return choices
    }
}

/** Owns one acquisition's site attempts; callers retain their existing page/transfer owners. */
class OriginalDownloadLookup(private val target: OriginalDownloadTarget) {
    sealed interface Result {
        data class Page(val page: ByparrClient.Page) : Result
        data class Choices(val source: DownloadSource, val choices: List<RemotePage.Choice>) : Result
    }
    data class Progress(val stepId: Long, val source: DownloadSource, val step: String, val queue: String? = null,
                        val level: LogLevel = LogLevel.INFO, val detail: String? = null,
                        val queueStatus: ByparrClient.QueueStatus? = null, val pageUrl: String? = null) {
        val message get() = "${source.label} · $step" + queue?.let { "\n$it" }.orEmpty()
    }
    private val attempted = linkedSetOf<DownloadSource>()
    private val fetched = mutableSetOf<String>()
    private val resolvedPages = java.util.concurrent.ConcurrentHashMap<DownloadSource, String>()
    @Volatile private var stepId = 0L
    @Volatile var currentSource: DownloadSource? = null
        private set
    val currentPageUrl: String? get() = currentSource?.let(resolvedPages::get)
    fun isCurrent(progress: Progress) = progress.stepId == stepId
    private fun step(source: DownloadSource, text: String, report: (Progress) -> Unit,
                     level: LogLevel = LogLevel.INFO, detail: String? = null): (ByparrClient.QueueStatus) -> Unit {
        currentSource = source
        val progress = Progress(++stepId, source, text, level = level, detail = detail, pageUrl = resolvedPages[source])
        report(progress)
        return { queue -> if (isCurrent(progress)) report(progress.copy(queue = queue.message, queueStatus = queue)) }
    }
    /**
     * Whether a site's failure should hand the lookup to the next site. A site that has no such
     * release, cannot be read or asks for a browser check says nothing about the others, so they are
     * tried. Cancellation, Byparr itself failing or being unreachable, and a page cycle stop it.
     */
    private fun advances(failure: IOException, client: ByparrClient) =
        !client.isCancelled && (failure is NoMatchingApk || failure.javaClass == IOException::class.java)

    private fun noteFailure(failure: IOException, source: DownloadSource, failures: MutableMap<DownloadSource, String>,
                            report: (Progress) -> Unit) {
        if (failure is NoMatchingApk) {
            failures[source] = "no ${target.version ?: "matching"} release"
            step(source, "Requested version not found", report, LogLevel.WARN)
        } else {
            failures[source] = failure.message ?: "failed"
            step(source, "Could not be checked, trying the next site", report, LogLevel.WARN, detail = failure.message)
        }
    }

    private fun exhausted(failures: Map<DownloadSource, String>): IOException =
        if (failures.values.all { it.startsWith("no ") })
            IOException("No matching ${target.version} release was found in ${attempted.joinToString { it.label }}. Choose another APK/version or Open in Browser.")
        else IOException("No download site provided ${target.version ?: "this app"}. " +
            failures.entries.joinToString(" ") { (source, why) -> "${source.label}: $why" } +
            " Choose another APK/version or Open in Browser.")

    fun fetch(client: ByparrClient, endpoint: String, url: String, abis: List<String>, report: (Progress) -> Unit): Result {
        val start = ApkMirrorSearch.translate(url, target.packageName, target.version)
        var next = if (StoreSearch.google(start) && StoreSearch.hasIntent(start, target)) StoreSearch.mirror(target, abis) else start
        var source = DownloadPolicy(false).source(next) ?: throw IOException("Open a supported download-site link.")
        val failures = linkedMapOf<DownloadSource, String>()
        try {
        while (true) {
            attempted.add(source)
            try {
                if (!fetched.add(next)) throw LookupCycle("${source.label} returned a page cycle. Use Retry or Open in Browser.")
                val feedback = step(source, if (ApkMirrorSearch.terms(next) != null)
                    "Resolving package…" else "Requesting download link…", report)
                val page = client.fetch(endpoint, next, target.version, abis, target.packageName,
                    onStep = { step(source, it, report) },
                    onResolvedPage = { resolvedPages[source] = it }, onQueueStatus = feedback)
                if (page.content.url != next && !fetched.add(page.content.url))
                    throw LookupCycle("${source.label} redirected to an already checked page. Use Retry or Open in Browser.")
                return Result.Page(page)
            } catch (failure: IOException) {
                if (!advances(failure, client)) throw failure
                noteFailure(failure, (failure as? NoMatchingApk)?.source ?: source, failures, report)
                while (true) {
                    source = DownloadSource.entries.firstOrNull { it !in attempted } ?: throw exhausted(failures)
                    attempted.add(source)
                    val feedback = step(source, "Resolving package…", report)
                    try {
                        if (source == DownloadSource.APK_MIRROR) {
                            next = StoreSearch.mirror(target, abis)
                        } else {
                            val choices = client.discover(endpoint, target, source,
                                onStep = { step(source, it, report) },
                                onResolvedPage = { resolvedPages[source] = it }, onQueueStatus = feedback)
                            if (choices.size != 1) return Result.Choices(source, choices)
                            next = choices.single().url
                        }
                        break
                    } catch (nextFailure: IOException) {
                        if (!advances(nextFailure, client)) throw nextFailure
                        noteFailure(nextFailure, source, failures, report)
                    }
                }
            }
        }
        } finally { ++stepId } // Queue feedback may be queued on a caller's UI dispatcher after completion.
    }
}
