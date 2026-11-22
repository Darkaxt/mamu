package app.morphe.manager.ui.screen.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.downloaders.ByparrEndpoint
import app.morphe.manager.downloaders.ApkMirrorSearch
import app.morphe.manager.downloaders.DownloadActivityLog
import app.morphe.manager.downloaders.DownloadSource
import app.morphe.manager.patcher.logger.LogLevel
import app.morphe.manager.ui.viewmodel.NativeOriginalDownloadViewModel
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.theme.ThemeTraitsDefaults
import app.morphe.manager.util.MORPHE_API_URL
import app.morphe.manager.util.formatBytes
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** A floating download surface using the existing Material theme and system-size units. */
@Composable
internal fun NativeOriginalApkDialog(
    appName: String,
    packageName: String?,
    downloadUrl: String?,
    version: String?,
    onDismiss: () -> Unit,
    onOpenBrowser: () -> Unit,
    onReceived: (Uri) -> Unit,
    onManual: () -> Unit,
    onChooseApk: (() -> Unit)? = null,
    model: NativeOriginalDownloadViewModel = koinViewModel(),
    buildCodes: Set<Int>? = null
) {
    val context = LocalContext.current
    val openPage = rememberOpenUrl()
    val state by model.state.collectAsState()
    val log by model.activityLog.collectAsState()
    val hasDestination = downloadUrl != null && !downloadUrl.startsWith(MORPHE_API_URL)
    LaunchedEffect(downloadUrl, packageName, version, buildCodes) {
        if (hasDestination) model.begin(checkNotNull(downloadUrl), packageName, version, buildCodes)
        else if (packageName != null && version != null)
            model.begin(ApkMirrorSearch.nativeSearch(packageName, version), packageName, version, buildCodes)
    }
    LaunchedEffect(state) {
        if (state is NativeOriginalDownloadViewModel.State.Ready)
            model.takeReady()?.let(onReceived)
        if (state is NativeOriginalDownloadViewModel.State.Manual) onManual()
    }
    DisposableEffect(model, context) {
        onDispose {
            var owner: Context = context
            while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
            if ((owner as? Activity)?.isChangingConfigurations != true) model.cancel()
        }
    }
    val close = { model.cancel(); onDismiss() }
    val failure = state as? NativeOriginalDownloadViewModel.State.Failure
    CompositionLocalProvider(
        LocalDialogTextColor provides MaterialTheme.colorScheme.onBackground,
        LocalDialogSecondaryTextColor provides MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    ) {
        AlertDialog(
            onDismissRequest = close,
            modifier = Modifier.border(1.dp, Brush.linearGradient(listOf(
                LocalDialogTextColor.current.copy(alpha = 0.65f),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                LocalDialogTextColor.current.copy(alpha = 0.20f)
            )), AlertDialogDefaults.shape),
            containerColor = ThemeTraitsDefaults.surfaceColor(MaterialTheme.colorScheme.background),
            titleContentColor = LocalDialogTextColor.current,
            textContentColor = LocalDialogSecondaryTextColor.current,
            icon = {
                if (packageName != null) AppIcon(packageName = packageName, contentDescription = null,
                    modifier = Modifier.size(48.dp))
                else Icon(Icons.Outlined.Download, contentDescription = null)
            },
            title = {
                Text(stringResource(R.string.extended_downloading_app, appName),
                    style = MaterialTheme.typography.titleLarge)
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(listOfNotNull(packageName, version?.let { "v$it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium)
                    val transfer = state as? NativeOriginalDownloadViewModel.State.Transfer
                    if (failure == null) {
                        if (transfer != null && transfer.total > 0)
                            LinearProgressIndicator(progress = { (transfer.bytes.toDouble() / transfer.total).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val currentMessage = when (val current = state) {
                        is NativeOriginalDownloadViewModel.State.Resolving -> current.message
                        is NativeOriginalDownloadViewModel.State.Transfer ->
                            current.step ?: (current.shop?.let { "$it · " }.orEmpty() + stringResource(if (current.paused) R.string.extended_download_paused else R.string.extended_downloading))
                        is NativeOriginalDownloadViewModel.State.Failure -> current.message
                        is NativeOriginalDownloadViewModel.State.Choices -> current.page.content.source.label + " · " + stringResource(R.string.extended_choose_variant)
                        is NativeOriginalDownloadViewModel.State.StoreChoices -> current.source.label + " · Choose a matching store result"
                        is NativeOriginalDownloadViewModel.State.Ready -> stringResource(R.string.extended_checking_apk)
                        NativeOriginalDownloadViewModel.State.HandedOff -> stringResource(R.string.extended_opening_apk)
                        NativeOriginalDownloadViewModel.State.Manual,
                        NativeOriginalDownloadViewModel.State.Idle -> stringResource(R.string.extended_finding_apk)
                    }
                    val shop = (listOf("Google Play") + DownloadSource.entries.map { it.label })
                        .firstOrNull { currentMessage.startsWith("$it · ") }
                    DownloadActivityPanel(log, DownloadActivityLog.Entry("state", shop,
                        currentMessage.substringBefore('\n').removePrefix("$shop · "),
                        if (failure != null) LogLevel.ERROR else LogLevel.INFO,
                        currentMessage.substringAfter('\n', "").takeIf { it.isNotBlank() }), onOpenPage = openPage)
                    transfer?.let {
                        Text(context.formatBytes(it.bytes) + if (it.total > 0) " / ${context.formatBytes(it.total)}" else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    (state as? NativeOriginalDownloadViewModel.State.Choices)?.let { choices ->
                        choices.page.content.choices.forEach { choice ->
                            AppDialogOutlinedButton(text = choice.label,
                                onClick = { model.choose(choices.page, choice) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    (state as? NativeOriginalDownloadViewModel.State.StoreChoices)?.let { choices ->
                        choices.choices.forEach { choice ->
                            AppDialogOutlinedButton(text = choice.label,
                                onClick = { model.chooseStore(choices, choice) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                AppDialogActions(actions = buildList {
                    if (failure != null) {
                        add(DialogAction(stringResource(R.string.extended_download_retry), model::retry))
                        add(DialogAction(stringResource(R.string.extended_download_browser),
                            onClick = { model.cancel(); onOpenBrowser() }, enabled = hasDestination))
                    } else add(DialogAction(stringResource(android.R.string.cancel), close))
                    onChooseApk?.let { choose ->
                        add(DialogAction(stringResource(R.string.extended_choose_apk), onClick = { model.cancel(); choose() },
                            emphasis = DialogActionEmphasis.Outlined))
                    }
                    if (failure?.canTryShops == true) add(DialogAction("Try other download sites", model::tryOtherShops,
                        emphasis = DialogActionEmphasis.Outlined))
                })
            }
        )
    }
}

@Composable
fun ByparrSettingsDialog(prefs: PreferencesManager, onDismiss: () -> Unit) {
    val saved by prefs.nativeByparrEndpoint.getAsState()
    var endpoint by remember(saved) { mutableStateOf(saved) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(
        LocalDialogTextColor provides MaterialTheme.colorScheme.onBackground,
        LocalDialogSecondaryTextColor provides MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = ThemeTraitsDefaults.surfaceColor(MaterialTheme.colorScheme.background),
            titleContentColor = LocalDialogTextColor.current,
            textContentColor = LocalDialogSecondaryTextColor.current,
            title = { Text(stringResource(R.string.extended_byparr_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppDialogTextField(value = endpoint, onValueChange = { endpoint = it; error = false },
                        label = { Text(stringResource(R.string.extended_byparr_url)) }, singleLine = true,
                        isError = error, modifier = Modifier.fillMaxWidth())
                    if (error) Text(stringResource(R.string.extended_byparr_invalid_url),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                AppDialogButtonRow(primaryText = stringResource(R.string.save), onPrimaryClick = {
                    val normalized = ByparrEndpoint.normalize(endpoint, false)
                    if (normalized == null) error = true
                    else scope.launch { prefs.nativeByparrEndpoint.update(normalized); onDismiss() }
                }, secondaryText = stringResource(android.R.string.cancel), onSecondaryClick = onDismiss)
            }
        )
    }
}
