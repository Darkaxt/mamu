package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.domain.repository.SelectionSetRepository
import app.morphe.manager.domain.repository.SelectionSnapshot
import app.morphe.manager.patcher.patch.PatchBundleInfo
import app.morphe.manager.util.Options
import app.morphe.manager.util.PatchSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** Named snapshots use the same repository as settings/imports, while existing section tools stay scoped. */
@Composable
fun NamedPatchSetsDialog(
    configurationKey: String,
    bundles: List<PatchBundleInfo.Scoped>,
    selection: PatchSelection,
    options: Options,
    onApply: (SelectionSnapshot) -> Unit,
    onDismiss: () -> Unit
) {
    val repository: SelectionSetRepository = koinInject()
    val sets by remember(configurationKey) { repository.summariesFor(configurationKey) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(app.morphe.manager.R.string.extended_named_sets)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(app.morphe.manager.R.string.extended_set_name)) })
                Button(enabled = !busy && name.isNotBlank(), onClick = {
                    val chosen = name.trim()
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { repository.save(configurationKey, chosen, selection, options) }
                            name = ""
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failure = e.message } finally { busy = false }
                    }
                }) { Text(stringResource(app.morphe.manager.R.string.save)) }
                sets.forEach { set ->
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(modifier = Modifier.weight(1f), enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val snapshot = withContext(Dispatchers.IO) {
                                        repository.load(set.uid, bundles.associate { it.uid to it.patches.associateBy { patch -> patch.name } })
                                    }
                                    onApply(snapshot)
                                    onDismiss()
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failure = e.message } finally { busy = false }
                            }
                        }) { Text("${set.name} (${set.patchCount})") }
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                try { withContext(Dispatchers.IO) { repository.delete(set.uid) } }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failure = e.message } finally { busy = false }
                            }
                        }) { Text(stringResource(app.morphe.manager.R.string.delete)) }
                    }
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } })
}
