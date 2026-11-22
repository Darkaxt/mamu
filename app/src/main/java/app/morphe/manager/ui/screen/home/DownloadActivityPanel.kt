package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.downloaders.DownloadActivityLog
import app.morphe.manager.downloaders.downloadActivityPage
import app.morphe.manager.patcher.logger.LogLevel
import app.morphe.manager.ui.screen.patcher.LogEntryRow
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.GooglePlay

/** One current row; older acquisition events unfold without changing the acquisition. */
@Composable
internal fun DownloadActivityPanel(entries: List<DownloadActivityLog.Entry>, fallback: DownloadActivityLog.Entry,
                                   onOpenPage: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = entries.lastOrNull() ?: fallback
    val expandLabel = stringResource(if (expanded) R.string.download_log_hide else R.string.download_log_show)
    val status = stringResource(when (current.level) {
        LogLevel.ERROR -> R.string.download_log_error
        LogLevel.WARN -> R.string.download_log_warning
        else -> R.string.download_log_current
    })
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = status },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    DownloadActivityRow(current, onOpenPage = onOpenPage)
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp)
                    .testTag("download-log-toggle").semantics { stateDescription = "$status. $expandLabel" }) {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = expandLabel, modifier = Modifier.size(20.dp))
                }
            }
            current.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()).testTag("download-log-history")) {
                    entries.dropLast(1).forEach { item ->
                        DownloadActivityRow(item, subdued = true, onOpenPage = onOpenPage)
                        item.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                    if (entries.size < 2) Text(stringResource(R.string.download_log_no_history),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadActivityRow(entry: DownloadActivityLog.Entry, subdued: Boolean = false, onOpenPage: (String) -> Unit) {
    val page = downloadActivityPage(entry.pageUrl)
    val openLabel = stringResource(R.string.sources_management_open_in_browser)
    val link: (@Composable RowScope.() -> Unit)? = if (page == null) null else { {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = openLabel,
            modifier = Modifier.size(16.dp).align(Alignment.CenterVertically).testTag("download-log-link.${entry.key}"))
    } }
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("download-log-row.${entry.key}")
        .then(if (page == null) Modifier else Modifier.clickable(role = Role.Button,
            onClickLabel = openLabel) { onOpenPage(page) })) {
        LogEntryRow(entry.level, entry.text, subdued = subdued, leading = { DownloadShopIcon(entry.shop) },
            modifier = Modifier.heightIn(min = 48.dp), trailing = link)
    }
}

@Composable
private fun DownloadShopIcon(shop: String?) {
    val resource = when (shop) {
        "APKMirror" -> R.drawable.download_shop_apkmirror
        "APKPure" -> R.drawable.download_shop_apkpure
        "APKCombo" -> R.drawable.download_shop_apkcombo
        "Uptodown" -> R.drawable.download_shop_uptodown
        else -> null
    }
    if (resource != null) Image(painterResource(resource), contentDescription = null, modifier = Modifier.size(20.dp))
    else Icon(if (shop == "Google Play") FontAwesomeIcons.Brands.GooglePlay else Icons.Outlined.Download,
        contentDescription = null, modifier = Modifier.size(20.dp))
}
