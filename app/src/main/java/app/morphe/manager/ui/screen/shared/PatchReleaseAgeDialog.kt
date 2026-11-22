package app.morphe.manager.ui.screen.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.patcher.patch.PatchReleaseAgeGate

@Composable
fun PatchReleaseAgeDialog(gate: PatchReleaseAgeGate, onHome: () -> Unit = { gate.home() }) {
    val pending = gate.pending ?: return
    AppDialog(onDismissRequest = { gate.cancel() }, title = stringResource(R.string.patch_age_title),
        description = stringResource(R.string.patch_age_warning),
        footer = { AppDialogActions(layout = DialogButtonLayout.Vertical, actions = listOf(
            DialogAction(stringResource(R.string.patch_age_disable_review), { gate.review() }, Icons.Outlined.Edit),
            DialogAction(stringResource(R.string.home), onHome, Icons.Outlined.Home),
            DialogAction(stringResource(R.string.home_dialog_unsupported_version_dialog_proceed),
                { gate.proceed() }, Icons.Outlined.WarningAmber, emphasis = DialogActionEmphasis.Outlined)
        )) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pending.warnings.groupBy { listOf(it.packageName, it.apkVersion, it.apkDay) }.forEach { (_, appWarnings) ->
                val app = appWarnings.first()
                Text("${app.packageName} · ${app.apkVersion.orEmpty()}", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.patch_age_apk_date, app.apkDay), style = MaterialTheme.typography.bodySmall)
                appWarnings.groupBy { it.bundleUid }.values.forEach { warnings ->
                    val source = warnings.first()
                    Text(stringResource(R.string.patch_age_source_date,
                        listOfNotNull(source.sourceName, source.sourceVersion).joinToString(" "), source.sourceDay),
                        color = SemanticTone.Warning.accent, style = MaterialTheme.typography.bodyMedium)
                    warnings.forEach { Text("• ${it.patchLabel}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
