package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.patcher.patch.PatchOmission

/** The same signed-output omissions in single-app and batch results. Opening starts no work. */
@Composable
fun SkippedPatchesNotice(omissions: List<PatchOmission>) {
    if(omissions.isEmpty()) return
    var details by remember { mutableStateOf(false) }
    Column {
        Notice(stringResource(R.string.patch_retry_result,omissions.size),tone=SemanticTone.Warning)
        TextButton(onClick={details=true},modifier=Modifier.heightIn(min=48.dp)) {
            Text(stringResource(R.string.patch_retry_details))
        }
    }
    if(details) AppDialog(onDismissRequest={details=false},title=stringResource(R.string.patch_retry_details),footer={
        OutlinedButton(onClick={details=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(stringResource(R.string.close)) }
    }) {
        omissions.forEach { item ->
            Text("${item.sourceName ?: "#${item.patch.sourceUid}"} ${item.sourceVersion.orEmpty()} (#${item.patch.sourceUid})",
                style=MaterialTheme.typography.labelLarge)
            Text(item.patch.key,style=MaterialTheme.typography.titleMedium)
            Text(item.reason,style=MaterialTheme.typography.bodyMedium)
            if(item.failedPatch!=item.patch.key) Text(stringResource(R.string.patch_retry_dependency,item.failedPatch),
                style=MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical=8.dp))
        }
    }
}
