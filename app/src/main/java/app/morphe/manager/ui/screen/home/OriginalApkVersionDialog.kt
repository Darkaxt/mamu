package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.domain.bundles.BundledAppTarget
import app.morphe.manager.patcher.patch.PatchInfo
import app.morphe.manager.patcher.patch.customPatchCount
import app.morphe.manager.ui.screen.shared.*
import app.morphe.patcher.patch.AppTarget

/** The version choice precedes both APK network acquisition and retained-original reuse. */
@Composable
internal fun OriginalApkVersionDialog(
    packageName: String,
    appName: String,
    targets: List<BundledAppTarget>,
    patches: Map<Int, List<PatchInfo>>,
    sourceName: (Int) -> String?,
    initial: AppTarget?,
    onSelect: (AppTarget) -> Unit,
    onDismiss: () -> Unit
) {
    val choices = targets.sortedByDescending { it.target }.distinctBy { it.target.version }
    var selected by remember(packageName, choices) {
        mutableStateOf(choices.firstOrNull { it.target.version == initial?.version }?.target ?: choices.firstOrNull()?.target)
    }
    AppDialog(
        title = stringResource(R.string.extended_choose_patch_version),
        description = appName + "\n" + stringResource(R.string.extended_patch_version_hint),
        onDismissRequest = onDismiss,
        accentColor = rememberAppColor(packageName),
        padding = DialogPadding.Compact,
        footer = {
            AppDialogButtonRow(
                primaryText = stringResource(R.string.continue_),
                primaryEnabled = selected != null,
                onPrimaryClick = { selected?.let(onSelect) },
                secondaryText = stringResource(android.R.string.cancel),
                onSecondaryClick = onDismiss
            )
        }
    ) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                val version = choice.target.version
                val counts = patches.mapValues { customPatchCount(it.value, packageName, version) }.filterValues { it > 0 }
                val countText = stringResource(R.string.extended_custom_patch_count, counts.values.sum())
                val heading = "v$version · $countText"
                val sources = counts.entries.sortedByDescending { it.value }
                    .joinToString(" · ") { "${sourceName(it.key) ?: it.key}: ${it.value}" }
                RadioSelectionCard(
                    selected = selected?.version == version,
                    onSelect = { selected = choice.target },
                    contentDescription = "$heading, $sources"
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(heading, style = MaterialTheme.typography.bodyLarge, color = LocalDialogTextColor.current)
                        if (version == initial?.version) Text(stringResource(R.string.home_recommended_version),
                            style = MaterialTheme.typography.bodySmall, color = LocalDialogSecondaryTextColor.current)
                        Text(sources, style = MaterialTheme.typography.bodySmall, color = LocalDialogSecondaryTextColor.current)
                    }
                }
            }
        }
    }
}
