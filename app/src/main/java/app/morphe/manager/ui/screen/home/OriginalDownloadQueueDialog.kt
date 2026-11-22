package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.downloaders.OriginalDownloadQueue
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.util.formatBytes
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import org.koin.compose.koinInject

/** Observes existing work only. No acquisition, parsing, retries or patching on opening. */
@Composable
internal fun OriginalDownloadQueueDialog(apps: List<HomeAppItem>, onDismiss: () -> Unit) {
    val queue: OriginalDownloadQueue = koinInject()
    val work by remember(queue) {
        queue.entries.map { entries -> entries.values.sortedWith(
            compareByDescending<app.morphe.manager.downloaders.OriginalDownloadEntry> { it.active }
                .thenBy { it.target.packageName }) }.flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val cached by queue.cached.collectAsStateWithLifecycle()
    val cancelled by queue.cancelled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val names by produceState<Map<String, String>>(emptyMap(), apps) {
        value = withContext(Dispatchers.Default) { apps.associate { it.packageName to it.displayName } }
    }
    AppDialog(onDismissRequest = onDismiss, title = stringResource(R.string.library_queue), scrollable = false,
        footer = { AppDialogOutlinedButton(stringResource(R.string.close), onDismiss, Modifier.fillMaxWidth()) }) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (work.isEmpty()) Text(stringResource(R.string.library_no_downloads))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(work, key = { it.id }) { entry ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(names[entry.target.packageName] ?: entry.target.packageName, style = MaterialTheme.typography.titleMedium)
                    Text("${entry.target.packageName} · ${entry.target.version}", style = MaterialTheme.typography.bodySmall)
                    Text(when {
                        entry.id.toString() in cancelled && entry.active -> stringResource(R.string.queue_cancel_pending)
                        entry.phase == "waiting" -> stringResource(R.string.queue_waiting)
                        entry.message.isNotBlank() -> entry.message
                        else -> entry.phase
                    })
                    if (entry.phase == "transfer") {
                        Text(context.formatBytes(entry.bytes) + if (entry.total > 0) " / ${context.formatBytes(entry.total)}" else "")
                        if (entry.total > 0) LinearProgressIndicator(progress = { (entry.bytes.toDouble() / entry.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else if (entry.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val retained = cached[entry.target.packageName]
                    Text(if (retained != null) stringResource(R.string.library_retained, retained.version)
                        else stringResource(R.string.library_not_retained), style = MaterialTheme.typography.bodySmall)
                    if (entry.active) TextButton(enabled = entry.id.toString() !in cancelled,
                        onClick = { scope.launch { error = queue.cancel(entry.id) } }) { Text(stringResource(android.R.string.cancel)) }
                    HorizontalDivider()
                }
            }
        }
    }
}
