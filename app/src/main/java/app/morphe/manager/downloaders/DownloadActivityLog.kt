package app.morphe.manager.downloaders

import app.morphe.manager.patcher.logger.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** Public HTML/listing pages only; delivery hosts and credentials never become row actions. */
internal fun downloadActivityPage(value: String?): String? = value?.takeIf { raw -> runCatching {
    val uri = URI(raw)
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return@runCatching false
    val host = uri.host?.lowercase() ?: return@runCatching false
    if (host == "play.google.com") return@runCatching uri.path == "/store/apps/details" &&
        uri.rawQuery.orEmpty().matches(Regex("id=[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))
    val webHost = host in setOf("apkmirror.com", "www.apkmirror.com", "apkpure.net", "www.apkpure.net", "m.apkpure.net",
        "apkpure.com", "www.apkpure.com", "m.apkpure.com", "apkcombo.com", "www.apkcombo.com", "en.uptodown.com") ||
        host.matches(Regex("[a-z0-9-]+\\.en\\.uptodown\\.com"))
    webHost && !uri.path.startsWith("/wp-content/") && !(host.endsWith("apkcombo.com") && uri.path == "/d") &&
        !uri.path.matches(Regex("(?i).*\\.(apk|apkm|apks|xapk)$"))
}.getOrDefault(false) }

internal fun playActivityPage(packageName: String): String? =
    downloadActivityPage("https://play.google.com/store/apps/details?id=$packageName")

/** Presentation history for one acquisition; it never controls acquisition or retries. */
class DownloadActivityLog {
    data class Entry(val key: String, val shop: String?, val message: String,
                     val level: LogLevel = LogLevel.INFO, val detail: String? = null, val pageUrl: String? = null) {
        val text get() = shop?.let { "$it · $message" } ?: message
    }
    private var generation = 0L
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries = _entries.asStateFlow()

    @Synchronized fun reset(owner: Long) { generation = owner; _entries.value = emptyList() }

    @Synchronized fun record(owner: Long, key: String, shop: String?, message: String,
                             level: LogLevel = LogLevel.INFO, detail: String? = null, pageUrl: String? = null): Boolean {
        if (owner != generation) return false
        val page = if (pageUrl != null) downloadActivityPage(pageUrl)
            else _entries.value.lastOrNull { it.shop == shop }?.pageUrl
        val entry = Entry(key, shop, message, level, detail, page)
        _entries.value = if (_entries.value.lastOrNull()?.key == key)
            _entries.value.dropLast(1) + entry
        else _entries.value.takeLast(79) + entry
        return true
    }

    @Synchronized fun update(owner: Long, key: String, detail: String?): Boolean {
        if (owner != generation || _entries.value.lastOrNull()?.key != key) return false
        _entries.value = _entries.value.dropLast(1) + _entries.value.last().copy(detail = detail)
        return true
    }
}
