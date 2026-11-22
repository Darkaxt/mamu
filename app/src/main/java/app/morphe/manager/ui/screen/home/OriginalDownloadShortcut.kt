package app.morphe.manager.ui.screen.home

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.apk.LocalApkSources
import app.morphe.manager.downloaders.*
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.theme.ThemeTraitsDefaults
import app.morphe.manager.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Only the tap queues work; opening this status surface never starts patching or installation. */
@Composable
internal fun OriginalDownloadShortcut(item: HomeAppItem, packageName: String, contentColor: Color) {
    val queue: OriginalDownloadQueue = koinInject()
    val local: LocalApkSources = koinInject()
    val targets by queue.targets.collectAsStateWithLifecycle()
    val cached by queue.cached.collectAsStateWithLifecycle()
    val entries by queue.entries.collectAsStateWithLifecycle()
    val cancelled by queue.cancelled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val browser = LocalUriHandler.current
    val offered = supportedDownloadTarget(packageName,
        item.version.takeIf { item.isInstalledOnDevice } ?: cached[packageName]?.version,
        targets[packageName].orEmpty(), Build.VERSION.SDK_INT)
    val entry = entries[packageName]?.takeIf { it.active || offered == null || it.target == offered }
    var show by remember(packageName) { mutableStateOf(false) }
    var submitting by remember(packageName) { mutableStateOf(false) }
    var error by remember(packageName) { mutableStateOf<String?>(null) }
    val target = if (entry?.active == true) entry.target else offered ?: entry?.target
    val savedIdentity by produceState<ArchiveIdentity?>(null, packageName, cached[packageName]) {
        value = withContext(Dispatchers.IO) { local.saved(packageName)?.let {
            ArchiveIdentity(packageName, packageName, it.version, it.versionCode ?: -1)
        } }
    }
    val ready = target?.acceptedByCurrent(savedIdentity, targets[packageName].orEmpty(), Build.VERSION.SDK_INT) == true
    if (offered == null && entry?.active != true && !show) return
    val active = entry?.active == true || submitting
    val cancelPending = entry?.id?.toString() in cancelled
    val phase = when {
        submitting -> "waiting"
        active -> entry?.phase ?: "waiting"
        ready -> "ready"
        entry?.phase == "ready" -> "available" // The saved copy may have been removed in storage settings.
        else -> entry?.phase ?: "available"
    }
    val message = when {
        error != null -> checkNotNull(error)
        cancelPending && active -> stringResource(R.string.queue_cancel_pending)
        phase == "waiting" -> stringResource(R.string.queue_waiting)
        phase == "ready" -> stringResource(R.string.queue_ready)
        phase == "cancelled" -> stringResource(R.string.queue_cancelled)
        phase == "checking" -> stringResource(R.string.queue_checking)
        phase == "retaining" -> stringResource(R.string.queue_saving)
        phase == "transfer" -> buildString {
            append(stringResource(R.string.extended_downloading))
            append(" · ").append(context.formatBytes(entry?.bytes ?: 0))
            if ((entry?.total ?: -1) > 0) append(" / ").append(context.formatBytes(checkNotNull(entry).total))
            entry?.message?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        phase != "available" && entry?.message?.isNotBlank() == true -> entry.message
        phase == "resolving" -> stringResource(R.string.queue_resolving)
        else -> stringResource(R.string.queue_action)
    }
    fun enqueue() {
        val next = offered ?: return
        submitting = true
        error = null
        scope.launch { try {
            error = queue.enqueue(next)
            if (error != null) show = true
        } finally { submitting = false } }
    }
    val description = stringResource(if (active || ready || entry != null) R.string.queue_status else R.string.queue_action) +
        ": ${item.displayName} · ${target?.version.orEmpty()} · $message"
    IconButton(onClick = { if (!active && !ready && entry == null) enqueue() else show = true },
        modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
        Box(contentAlignment = Alignment.Center) {
            if (active) {
                if (phase == "transfer" && (entry?.total ?: -1) > 0)
                    CircularProgressIndicator(progress = { (checkNotNull(entry).bytes.toDouble() / entry.total).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.size(30.dp), color = contentColor, strokeWidth = 2.dp)
                else CircularProgressIndicator(modifier = Modifier.size(30.dp), color = contentColor, strokeWidth = 2.dp)
            }
            Icon(when { ready && !active -> Icons.Outlined.Check
                phase == "failed" -> Icons.Outlined.ErrorOutline
                else -> Icons.Outlined.Download }, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
        }
    }
    if (show) {
        CompositionLocalProvider(
            LocalDialogTextColor provides MaterialTheme.colorScheme.onBackground,
            LocalDialogSecondaryTextColor provides MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        ) {
            AlertDialog(onDismissRequest = { show = false },
                containerColor = ThemeTraitsDefaults.surfaceColor(MaterialTheme.colorScheme.background),
                titleContentColor = LocalDialogTextColor.current, textContentColor = LocalDialogSecondaryTextColor.current,
                title = { Text(item.displayName) },
                text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$packageName · ${target?.version.orEmpty()}")
                    Text(message)
                    if (active) {
                        if (phase == "transfer" && (entry?.total ?: -1) > 0)
                            LinearProgressIndicator(progress = { (checkNotNull(entry).bytes.toDouble() / entry.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                } },
                confirmButton = { AppDialogActions(actions = buildList {
                    if (active && entry != null) add(DialogAction(stringResource(android.R.string.cancel),
                        onClick = { scope.launch { error = queue.cancel(entry.id) } }, enabled = !cancelPending))
                    if (!active && !ready && offered != null) add(DialogAction(stringResource(R.string.extended_download_retry), onClick = ::enqueue))
                    entry?.url?.let { url -> add(DialogAction(stringResource(R.string.extended_download_browser),
                        onClick = { browser.openUri(url) })) }
                    add(DialogAction(stringResource(android.R.string.ok), onClick = { show = false }))
                }) })
        }
    }
}
