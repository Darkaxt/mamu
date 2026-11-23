package app.morphe.manager.downloaders

import app.morphe.manager.util.versionArchitecture
import app.morphe.manager.util.versionSupportsAbis
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ByparrEndpoint {
    fun normalize(value: String, debugFixtures: Boolean): String? = runCatching {
        val uri = URI(value.trim())
        val loopback = debugFixtures && uri.scheme == "http" && uri.host in listOf("10.0.2.2", "localhost", "127.0.0.1")
        require((uri.scheme == "https" || loopback) && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        uri.toString().trimEnd('/') + if (path.endsWith("/v1")) "" else "/v1"
    }.getOrNull()
}

data class RemoteCookie(val name: String, val value: String, val domain: String,
                        val path: String, val secure: Boolean, val expires: Double) {
    fun matches(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.lowercase()
        val scope = domain.removePrefix(".").lowercase()
        val requestPath = uri.path.ifEmpty { "/" }
        val cookiePath = path.ifEmpty { "/" }
        (host == scope || (domain.startsWith('.') && host.endsWith(".$scope"))) &&
            (requestPath == cookiePath || (requestPath.startsWith(cookiePath) &&
                (cookiePath.endsWith('/') || requestPath.getOrNull(cookiePath.length) == '/'))) &&
            (!secure || uri.scheme == "https") && (expires <= 0 || expires > System.currentTimeMillis() / 1000.0) &&
            name.matches(Regex("[A-Za-z0-9_!#$%&'*+.^`|~-]+")) && value.all { it.code in 0x21..0x7e && it != ';' }
    }.getOrDefault(false)
}

data class RemotePage(val url: String, val title: String, val next: String?, val choices: List<Choice>, val source: DownloadSource,
                      val appName: String = title.substringBefore(" - APKMirror").substringBefore(" APK Download"),
                      val publication: ApkPublication? = null) {
    data class Choice(val url: String, val label: String, val sendReferer: Boolean = true)
    fun isAttachment(value: String): Boolean {
        val path = URI(value).path
        return path.endsWith("/download.php") || path.endsWith(".apk") || path.endsWith(".apkm") ||
            (source == DownloadSource.APK_PURE && URI(value).host in listOf("d.apkpure.com", "d.apkpure.net"))
            || (source == DownloadSource.APK_COMBO && path == "/d")
            || (source == DownloadSource.UPTODOWN && DownloadPolicy(false).downloadUrl(value, source))
    }
    companion object {
        fun parse(url: String, html: String, debugFixtures: Boolean = false, requestedUrl: String = url,
                  expectedVersion: String? = null, supportedAbis: List<String> = emptyList()): RemotePage {
            val doc = Jsoup.parse(html, url)
            return parseContent(url, debugFixtures, requestedUrl, expectedVersion, supportedAbis, doc)
                .copy(publication = ApkPublication.parse(url, doc, expectedVersion))
        }
        private fun parseContent(url: String, debugFixtures: Boolean, requestedUrl: String,
                  expectedVersion: String?, supportedAbis: List<String>, doc: org.jsoup.nodes.Document): RemotePage {
            val policy = DownloadPolicy(debugFixtures)
            if (policy.pageUrl(url) == null) throw IOException("Byparr returned an unsupported page URL.")
            val source = policy.source(url)!!
            if (expectedVersion != null && supportedAbis.isNotEmpty() && !versionSupportsAbis(expectedVersion, supportedAbis))
                throw IOException("The requested version declares an architecture this device does not support: $expectedVersion.")
            val title = doc.title()
            val challengeSelector = if (source == DownloadSource.UPTODOWN) "#challenge-running, #challenge-stage"
                else "#challenge-running, #challenge-stage, iframe[src*=challenges.cloudflare.com]"
            if (doc.select(challengeSelector).isNotEmpty() ||
                title.startsWith("Just a moment", true) || title.startsWith("Checking your browser", true))
                throw IOException("Byparr returned an unresolved security check.")
            if (title.contains("Page Not Found", true)) throw NoMatchingApk(source, expectedVersion)
            if (source == DownloadSource.UPTODOWN) return Uptodown.page(url, requestedUrl, doc, expectedVersion)
            if (source == DownloadSource.APK_MIRROR && ApkMirrorSearch.terms(requestedUrl) != null) {
                val requested = expectedVersion ?: ApkMirrorSearch.requestedVersion(requestedUrl)
                if (doc.selectFirst("#content.search-area") == null)
                    throw IOException("APKMirror did not return readable search results. Use Retry or Open in Browser.")
                val choices = doc.select("#content.search-area .appRow").flatMap { row ->
                    row.select("a[href]").mapNotNull { anchor ->
                        val link = policy.pageUrl(anchor.absUrl("href").substringBefore('#')) ?: return@mapNotNull null
                        val path = URI(link).path
                        val label = row.text()
                        if (policy.source(link) != source || !path.startsWith("/apk/") ||
                            !(path.endsWith("-release/") || path.endsWith("-android-apk-download/")) ||
                            !ApkMirrorSearch.matchesRelease(anchor.text().ifBlank { label }, requested)) null
                        else Choice(link, label)
                    }
                }.distinctBy { it.url }
                if (choices.isEmpty()) throw NoMatchingApk(source, requested)
                return RemotePage(url, title, choices.singleOrNull()?.url, choices, source)
            }
            if (source == DownloadSource.APK_COMBO) {
                val requested = Regex("/download/phone-([0-9]+(?:\\.[0-9]+)+)-apk/?$")
                    .find(URI(requestedUrl).path)?.groupValues?.get(1)
                val choices = doc.select("a.variant[href]").mapNotNull { a ->
                    val link = a.absUrl("href")
                    val label = a.text()
                    if (requested != null && !Regex("(?:^|[^0-9.])${Regex.escape(requested)}(?:$|[^0-9.])").containsMatchIn(label))
                        return@mapNotNull null
                    if (policy.downloadUrl(link, source)) Choice(link, label, "noreferrer" !in a.attr("rel").split(' ')) else null
                }.distinctBy { it.url }
                if (requested != null && choices.isEmpty()) {
                    if (doc.select("a.variant").isNotEmpty()) throw NoMatchingApk(source, requested)
                    throw IOException("APKCombo did not return readable version choices. Use Retry or Open in Browser.")
                }
                return RemotePage(url, title, choices.singleOrNull()?.url, choices, source,
                    doc.selectFirst("h1")?.text()?.substringBefore(" APK") ?: title.substringBefore(" APK").removePrefix("Download "))
            }
            if (source == DownloadSource.APK_PURE) {
                val requested = URI(requestedUrl).path.substringAfter("/download/", "").takeIf {
                    it.matches(Regex("[0-9]+(?:\\.[0-9]+)+"))
                }
                if (requested != null && !Regex("(?:^|[^0-9.])${Regex.escape(requested)}(?:$|[^0-9.])").containsMatchIn(title)) {
                    if (Regex("(?i)^Download .+ [0-9]+(?:\\.[0-9]+)+(?:[-_][A-Za-z0-9.-]+)? (?:APK|XAPK)\\b").containsMatchIn(title))
                        throw NoMatchingApk(source, requested)
                    throw IOException("APKPure did not identify the requested version. Use Retry or Open in Browser.")
                }
                val anchors = doc.select("#download_link, a.download-start-btn").ifEmpty {
                    doc.select("a.da, a.download_apk, a.download-btn")
                }
                val packageName = URI(requestedUrl).path.split('/').firstOrNull {
                    it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))
                }
                val choices = anchors.mapNotNull { a ->
                    val link = a.absUrl("href").substringBefore('#')
                    if (packageName != null && packageName !in URI(link).path.split('/')) return@mapNotNull null
                    if (policy.downloadUrl(link, source) || policy.pageUrl(link) != null && policy.source(link) == source)
                        Choice(link, a.text().ifBlank { a.attr("title") }) else null
                }.distinctBy { it.url }
                return RemotePage(url, title, choices.singleOrNull()?.url, choices, source,
                    doc.selectFirst("h1.info-title")?.text()?.takeIf { it.isNotBlank() } ?: title.removePrefix("Download ").substringBefore(" Latest Version"))
            }
            val links = doc.select("a[href]").mapNotNull { anchor ->
                val raw = anchor.absUrl("href").substringBefore('#')
                // APKMirror styles its #downloads scroll anchor as a downloadButton.
                if (raw == url.substringBefore('#')) return@mapNotNull null
                val allowed = policy.pageUrl(raw)?.takeIf { policy.source(it) == source }
                    ?: raw.takeIf { policy.downloadUrl(it, source) } ?: return@mapNotNull null
                anchor to Choice(allowed, (anchor.closest(".table-row") ?: anchor).text().ifBlank { allowed })
            }
            val buttons = links.filter { (a, link) -> a.hasClass("downloadButton") || a.id() == "download-link" ||
                URI(link.url).path.endsWith("/download.php") }.map { it.second }.distinctBy { it.url }
            val basePath = URI(url).path.trimEnd('/') + "/"
            val variants = links.map { it.second }.filter {
                val path = URI(it.url).path
                path.endsWith("-android-apk-download/") && path.startsWith(basePath) && it.url != url.substringBefore('#')
            }.distinctBy { it.url }
            val matchedVariants = if (source == DownloadSource.APK_MIRROR && versionArchitecture(expectedVersion) != null) {
                variants.filter { ApkMirrorSearch.matchesVersion(it.label, expectedVersion) }.also {
                    if (variants.isNotEmpty() && it.isEmpty())
                        throw NoMatchingApk(source, expectedVersion)
                }
            } else variants
            val releases = links.map { it.second }.filter {
                val path = URI(it.url).path
                path.endsWith("-release/") && path.startsWith(basePath) && it.url != url.substringBefore('#')
            }.distinctBy { it.url }
            return RemotePage(url, title, buttons.singleOrNull()?.url ?: matchedVariants.singleOrNull()?.url,
                buttons.ifEmpty { matchedVariants.ifEmpty { releases } }, source)
        }
    }
}

/** One explicitly configured browser operation at a time; feedback never replays it. */
class ByparrClient(private val debugFixtures: Boolean) {
    data class QueueStatus(val position: Int, val total: Int) {
        val compactMessage get() = if (position == 0) "Byparr is resolving the page…" else "Byparr queue: $position of $total"
        val message: String get() {
            if (position == 0) return "Byparr is resolving the download…"
            val ahead = position - 1
            val noun = if (ahead == 1) "request" else "requests"
            return "Waiting for Byparr…\nQueue position $position of $total\n$ahead $noun queued ahead of you"
        }
        companion object {
            fun parse(reply: JSONObject, requestId: String): QueueStatus? {
                if (reply.optString("requestId") != requestId) return null
                val position = reply.opt("position") as? Int ?: return null
                val total = reply.opt("total") as? Int ?: return null
                val limit = reply.opt("queueLimit") as? Int ?: return null
                if (limit <= 0 || total !in 0..limit) return null
                return when (reply.optString("state")) {
                    "queued" -> if (position in 1..total) QueueStatus(position, total) else null
                    "active" -> if (position == 0) QueueStatus(position, total) else null
                    else -> null
                }
            }
        }
    }

    /** Browser-free GET sampling; never retries or imposes a deadline on /v1. */
    private class QueueMonitor(api: String, val requestId: String, private val update: (QueueStatus) -> Unit) {
        private val url = api.removeSuffix("/v1") + "/queue/$requestId"
        private val stopped = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "byparr-feedback-$requestId").apply { isDaemon = true }
        }
        @Volatile private var connection: HttpURLConnection? = null
        val running get() = !stopped.get()
        private var previous: QueueStatus? = null
        @Synchronized fun start() {
            // The API is sampled once per second for display only. Operation
            // completion/cancellation, rather than elapsed time, stops sampling.
            if (!stopped.get()) executor.scheduleWithFixedDelay({ sample() }, 0, 1, TimeUnit.SECONDS)
        }
        @Synchronized fun stop() {
            if (stopped.compareAndSet(false, true)) {
                connection?.disconnect()
                executor.shutdownNow()
            }
        }
        private fun sample() {
            if (stopped.get()) return
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            connection = conn
            try {
                if (stopped.get()) return
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                // Diagnostic HTTP budgets affect this optional feedback GET
                // only; a stalled/unsupported endpoint cannot cancel /v1.
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                if (conn.responseCode != 200) return
                val reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val status = QueueStatus.parse(reply, requestId) ?: return
                if (!stopped.get() && status != previous) {
                    previous = status
                    update(status)
                }
            } catch (_: IOException) {
                // /v1 remains authoritative for results and errors.
            } catch (_: org.json.JSONException) {
                // Ignore malformed feedback rather than inventing a position.
            } finally {
                conn.disconnect()
                connection = null
            }
        }
    }
    data class Page(val content: RemotePage, val userAgent: String, val cookies: List<RemoteCookie>,
                    val attachmentHeaders: Map<String, String> = emptyMap()) {
        fun cookieHeader(url: String) = cookies.filter { it.matches(url) }.joinToString("; ") { "${it.name}=${it.value}" }
    }
    data class Attachment(val url: String, val disposition: String?, val mime: String?, val cookieHeader: String,
                          val sendReferer: Boolean, val referer: String? = null, val accept: String? = null)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var queueMonitor: QueueMonitor? = null
    val receivingQueueFeedback get() = !cancelled.get() && queueMonitor?.running == true
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() { cancelled.set(true); queueMonitor?.stop(); connection?.disconnect() }
    private fun connect(url: String): HttpURLConnection {
        if (cancelled.get()) throw IOException("Page request cancelled.")
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        connection = conn
        if (cancelled.get()) { conn.disconnect(); throw IOException("Page request cancelled.") }
        return conn
    }

    fun fetch(endpoint: String, url: String, expectedVersion: String? = null, supportedAbis: List<String> = emptyList(),
              expectedPackage: String? = null, onStep: ((String) -> Unit)? = null,
              onResolvedPage: ((String) -> Unit)? = null,
              onQueueStatus: ((QueueStatus) -> Unit)? = null): Page {
        val policy = DownloadPolicy(debugFixtures)
        val target = policy.pageUrl(ApkMirrorSearch.translate(url)) ?: throw IOException("Open a supported download-site link.")
        if (expectedVersion != null && supportedAbis.isNotEmpty() && !versionSupportsAbis(expectedVersion, supportedAbis))
            throw IOException("The requested version declares an architecture this device does not support: $expectedVersion.")
        val uptodown = policy.source(target) == DownloadSource.UPTODOWN
        val scriptedId = if (uptodown) Uptodown.scriptedId(target) else null
        val reply = request(endpoint, target, uptodown, scriptedId, onQueueStatus)
        val source = policy.source(target)
        if (policy.source(reply.url) != source) throw IOException("The server redirected to a different download site.")
        if (uptodown && URI(reply.url).host != URI(target).host)
            throw IOException("Uptodown returned a different app page.")
        downloadActivityPage(reply.url)?.let { onResolvedPage?.invoke(it) }
        if (reply.status == 404) throw NoMatchingApk(checkNotNull(source), expectedVersion)
        if (reply.status !in 200..299 || uptodown && reply.status != 200)
            throw IOException("Byparr reported an unsuccessful target response.")
        if (uptodown && expectedPackage != null && URI(target).path.trimEnd('/') in setOf("/android", "/android/download") &&
            !Regex("(?<![A-Za-z0-9_.])${Regex.escape(expectedPackage)}(?![A-Za-z0-9_.])")
                .containsMatchIn(Jsoup.parse(reply.html).select("td").joinToString("\n") { it.text() }))
            throw IOException("Uptodown did not identify the requested package. Choose its build manually.")
        onStep?.invoke("Matching version and APK variant…")
        val page = RemotePage.parse(reply.url, reply.html, debugFixtures, target, expectedVersion, supportedAbis)
        if (scriptedId != null) {
            val (resolved, headers) = Uptodown.resolved(reply.json, page, target, reply.agent)
            return Page(resolved, reply.agent, reply.cookies, headers)
        }
        return Page(page, reply.agent, reply.cookies)
    }

    fun discover(endpoint: String, target: OriginalDownloadTarget, source: DownloadSource,
                 onStep: ((String) -> Unit)? = null,
                 onResolvedPage: ((String) -> Unit)? = null,
                 onQueueStatus: ((QueueStatus) -> Unit)? = null): List<RemotePage.Choice> {
        val query = StoreSearch.query(target, source)
        val reply = request(endpoint, query, false, null, onQueueStatus)
        downloadActivityPage(reply.url)?.takeIf { DownloadPolicy(false).source(it) == source }
            ?.let { onResolvedPage?.invoke(it) }
        if (reply.status == 404 && source == DownloadSource.APK_PURE &&
            DownloadPolicy(false).source(reply.url) == source) throw NoMatchingApk(source, target.version)
        if (reply.status != 200) throw IOException("${source.label} lookup returned HTTP ${reply.status}. Use Retry or Open in Browser.")
        onStep?.invoke("Matching package…")
        return StoreSearch.parse(reply.url, reply.html, target, source)
    }

    private data class Reply(val json: JSONObject, val status: Int, val url: String, val html: String,
                             val agent: String, val cookies: List<RemoteCookie>)
    private fun request(endpoint: String, target: String, uptodown: Boolean, scriptedId: String?,
                        onQueueStatus: ((QueueStatus) -> Unit)?): Reply {
        val api = ByparrEndpoint.normalize(endpoint, debugFixtures) ?: throw ByparrUnavailable("Enter a valid HTTPS Byparr endpoint.")
        val conn = connect(api)
        val requestId = UUID.randomUUID().toString()
        val monitor = onQueueStatus?.let { QueueMonitor(api, requestId, it) }
        queueMonitor = monitor
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            // maxTimeout is Byparr's execution budget after admission. Waiting
            // stays on this cancellable connection without a client deadline.
            val request = JSONObject().put("cmd", "request.get").put("url", target).put("maxTimeout", 60000)
                .put("requestId", requestId)
            if (scriptedId != null) request.put("maxTimeout", 120000).put("blockMedia", false)
                .put("script", Uptodown.script()).put("scriptArgs", JSONObject().put("fileId", scriptedId))
            val body = request.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            if (cancelled.get()) throw IOException("Page request cancelled.")
            monitor?.start()
            val code = conn.responseCode
            if (code !in 200..299 || uptodown && code != 200) {
                val queueFull = code == 503 && conn.contentType.orEmpty().startsWith("application/json") && runCatching {
                    conn.errorStream?.bufferedReader()?.use { reader ->
                        val buffer = CharArray(513)
                        var length = 0
                        while (length < buffer.size) {
                            val count = reader.read(buffer, length, buffer.size - length)
                            if (count < 0) break
                            length += count
                        }
                        length <= 512 && JSONObject(String(buffer, 0, length)).optString("detail") ==
                            "Browser queue full; no request was queued or submitted"
                    } == true
                }.getOrDefault(false)
                throw ByparrUnavailable(if (queueFull)
                    "Byparr's browser queue is full (HTTP 503). No page request was submitted. Use Retry to try again."
                    else "Byparr API failed (HTTP $code).")
            }
            val reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (reply.optString("status") != "ok") throw IOException("Byparr did not resolve the page.")
            val solution = reply.getJSONObject("solution")
            val targetStatus = solution.optInt("status", 0)
            val contentType = solution.optString("contentType", "text/html")
            if (targetStatus != 404 && !contentType.startsWith("text/html")) throw IOException("Byparr did not return a download page.")
            val html = solution.getString("response")
            if (targetStatus != 404 && html.isBlank()) throw IOException("Byparr returned an empty page.")
            val agent = solution.getString("userAgent")
            if (agent.isBlank() || agent.contains('\r') || agent.contains('\n')) throw IOException("Byparr returned an invalid user agent.")
            val cookies = solution.optJSONArray("cookies")
            val parsed = (0 until (cookies?.length() ?: 0)).map { index ->
                val cookie = cookies!!.getJSONObject(index)
                RemoteCookie(cookie.getString("name"), cookie.getString("value"), cookie.getString("domain"),
                    cookie.optString("path", "/"), cookie.optBoolean("secure"), cookie.optDouble("expires", -1.0))
            }
            return Reply(reply, solution.optInt("status", 0), solution.getString("url"), html, agent, parsed)
        } finally {
            monitor?.stop()
            queueMonitor = null
            conn.disconnect(); connection = null
        }
    }

    fun attachment(page: Page, start: String): Attachment {
        val policy = DownloadPolicy(debugFixtures)
        val visited = mutableSetOf<String>()
        val sendReferer = if (page.content.source == DownloadSource.UPTODOWN) page.attachmentHeaders.containsKey("referer")
            else page.content.choices.firstOrNull { it.url == start }?.sendReferer ?: true
        val referer = page.attachmentHeaders["referer"] ?: page.content.url
        val accept = page.attachmentHeaders["accept"]
        var url = start
        while (visited.add(url)) {
            if (!policy.downloadUrl(url, page.content.source)) throw IOException("${page.content.source.label} redirected to an unsupported attachment host.")
            val conn = connect(url)
            try {
                conn.setRequestProperty("User-Agent", page.userAgent)
                if (sendReferer) conn.setRequestProperty("Referer", referer)
                accept?.let { conn.setRequestProperty("Accept", it) }
                page.cookieHeader(url).takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("Cookie", it) }
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("The download site returned an empty redirect.")
                    url = URI(url).resolve(location).toString()
                    continue
                }
                if (code !in 200..299) throw IOException("Attachment request failed (HTTP $code).")
                val mime = conn.contentType
                val disposition = conn.getHeaderField("Content-Disposition")
                if (mime.orEmpty().startsWith("text/") || mime.orEmpty().contains("html", true))
                    throw IOException("The download site returned a page instead of the original file. The server session may not work from this network.")
                return Attachment(url, disposition, mime, page.cookieHeader(url), sendReferer, referer, accept)
            } finally { conn.disconnect(); connection = null }
        }
        throw IOException("The download site returned a redirect cycle.")
    }
}

/** Byparr itself failed, so no download site can be reached through it either. */
class ByparrUnavailable(message: String) : IOException(message)
