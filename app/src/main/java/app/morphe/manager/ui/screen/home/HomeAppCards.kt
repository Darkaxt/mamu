/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import android.content.pm.PackageInfo
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.data.room.apps.installed.InstalledApp
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.viewmodel.HomePlayVersion
import app.morphe.manager.ui.viewmodel.PlayVersionVerdict
import app.morphe.manager.network.PlayStoreIcons
import org.koin.compose.koinInject
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.screen.shared.Animations
import app.morphe.manager.ui.theme.LocalAppCardColorResolver
import app.morphe.manager.ui.theme.LocalThemeTraits
import app.morphe.manager.ui.theme.ThemeTraitsDefaults
import app.morphe.manager.util.*
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Brands
import compose.icons.fontawesomeicons.brands.GooglePlay
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// A verdict answered from cache lands within a frame, so the badge waits rather than flashing
private const val INSTALL_VERIFICATION_BADGE_DELAY_MS = 400L

@Composable
private fun wrapHomeRowActions() = LocalConfiguration.current.screenWidthDp < 380 || LocalDensity.current.fontScale > 1.3f

private data class HomeAppCardStyle(
    val monochrome: Boolean,
    val outlines: Boolean,
    val colorResolver: AppCardColorResolver?,
    val iconSize: Dp,
    val titleColor: Color,
    val subtitleColor: Color,
    val titleStyle: TextStyle,
    val subtitleStyle: TextStyle,
    val chipContainerColor: Color,
    val chipContentColor: Color,
    val cardColor: Color,
    val cardRadius: Dp = 24.dp,
    val cardHeight: Dp = 80.dp,
    val contentPadding: Dp = 16.dp,
    val contentSpacing: Dp = 16.dp
) {
    /**
     * Applies the card colors chosen in the appearance settings to [bundleColors], the palette
     * declared by this card's own bundle. Stops bound to the bundle are derived from it, so the
     * result stays per-app even when the rest of the gradient is fixed.
     */
    fun cardColors(bundleColors: List<Color>): List<Color> =
        colorResolver?.resolve(bundleColors) ?: bundleColors

    /**
     * This style adjusted to the gradient it will be drawn on. White reads on the colors a bundle
     * declares for itself, but the appearance settings let a card be any color, including one
     * light enough to swallow it.
     */
    fun onCard(bundleColors: List<Color>): HomeAppCardStyle {
        if (monochrome) return this

        val fill = cardColors(bundleColors).blend()
        if (fill.requiresLightContent()) return this

        return copy(
            titleColor = Color.Black,
            subtitleColor = Color.Black.copy(alpha = subtitleColor.alpha),
            chipContainerColor = Color.Black.copy(alpha = chipContainerColor.alpha),
            chipContentColor = Color.Black
        )
    }
}

@Composable
private fun homeAppCardStyle(subtitleAlpha: Float = 0.75f): HomeAppCardStyle {
    // The cards draw a palette of their own rather than shared tokens, so they read the traits directly
    val traits = LocalThemeTraits.current
    val monochrome = traits.monochrome
    val titleShadow = ThemeTraitsDefaults.textShadow(
        Shadow(
            color = Color.Black.copy(alpha = 0.4f),
            offset = Offset(0f, 2f),
            blurRadius = 4f
        )
    )
    val subtitleShadow = ThemeTraitsDefaults.textShadow(
        Shadow(
            color = Color.Black.copy(alpha = 0.4f),
            offset = Offset(0f, 1f),
            blurRadius = 2f
        )
    )

    return HomeAppCardStyle(
        monochrome = monochrome,
        outlines = traits.outlines,
        colorResolver = LocalAppCardColorResolver.current,
        iconSize = 48.dp,
        titleColor = if (monochrome) MaterialTheme.colorScheme.onSurface else Color.White,
        subtitleColor = if (monochrome) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            Color.White.copy(alpha = subtitleAlpha)
        },
        titleStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold,
            shadow = titleShadow
        ),
        subtitleStyle = MaterialTheme.typography.bodyMedium.copy(shadow = subtitleShadow),
        chipContainerColor = if (monochrome) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            Color.White.copy(alpha = 0.20f)
        },
        chipContentColor = if (monochrome) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            Color.White
        },
        cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

/**
 * Shared icon + text content for [AppCardLayout] rows.
 *
 * @param packageName    Package name used for icon lookup when [packageInfo] is null;
 *   null renders the glass placeholder without resolving an icon.
 * @param packageInfo    Resolved [PackageInfo]; when non-null [packageName] is ignored for the icon.
 * @param displayName    Primary label shown in bold.
 * @param subtitle       Secondary line shown below [displayName]; null → not rendered.
 * @param gradientColors Gradient palette forwarded to [AppIcon] placeholder, unless the user
 *   picked fixed card colors in the appearance settings.
 */
@Composable
internal fun RowScope.AppCardContent(
    packageName: String?,
    packageInfo: PackageInfo?,
    displayName: String,
    subtitle: String?,
    gradientColors: List<Color>,
    storeCategory: String? = null,
    actions: (@Composable () -> Unit)? = null,
    versionFeedback: (@Composable () -> Unit)? = null
) {
    val cardStyle = homeAppCardStyle().onCard(gradientColors)
    val wrapActions = wrapHomeRowActions()

    AppIcon(
        packageInfo = packageInfo,
        packageName = if (packageInfo == null) packageName else null,
        contentDescription = null,
        modifier = Modifier.size(cardStyle.iconSize),
        preferredSource = AppDataSource.PATCHED_APK,
        placeholderGradientColors = cardStyle.cardColors(gradientColors)
    )

    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = displayName,
            style = cardStyle.titleStyle,
            color = cardStyle.titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (versionFeedback != null) {
            versionFeedback()
        } else if (subtitle != null) {
            Text(
                // Matches the badge-height subtitle row of the installed cards, so names line up
                // across every card in the list
                modifier = Modifier
                    .heightIn(min = statusBadgeHeight)
                    .wrapContentHeight(Alignment.CenterVertically),
                text = subtitle,
                style = cardStyle.subtitleStyle,
                color = cardStyle.subtitleColor,
                // The row is one badge tall, so a subtitle that wraps would be cut in half
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        storeCategory?.let { Text(it, style = MaterialTheme.typography.labelMedium,
            color = cardStyle.subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (wrapActions && actions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
    }
    if (!wrapActions) actions?.invoke()
}

/**
 * One home screen card for [item]: the build Morphe keeps a record of, or the not-patched-yet
 * button when there is no record to show.
 *
 * @param showStatusBadges Whether the badges pinned to the end of the card may show.
 */
@Composable
internal fun HomeAppCard(
    item: HomeAppItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showStatusBadges: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onShowPatches: (() -> Unit)? = null
) {
    val supported = LocalHomePlayVersions.current[item.packageName]?.supported
    if (supported != null) {
        val store: PlayStoreIcons = koinInject()
        // Only mounted supported rows refresh, once per process. The resolver owns serial IO;
        // cached icons/metadata remain immediately readable while another lookup runs.
        LaunchedEffect(item.packageName, supported) { store.resolveListing(item.packageName, refreshVersion = true) }
    }
    if (LocalHomeTileLayout.current) {
        HomeAppTile(item, onClick, modifier, showStatusBadges, onLongClick, onShowPatches)
        return
    }
    val installedApp = item.installedApp
    if (installedApp != null) {
        InstalledAppCard(
            item = item,
            installedApp = installedApp,
            showStatusBadges = showStatusBadges,
            onClick = onClick,
            onLongClick = onLongClick,
            onShowPatches = onShowPatches,
            modifier = modifier
        )
    } else {
        NotPatchedAppCard(
            item = item,
            onClick = onClick,
            onLongClick = onLongClick,
            onShowPatches = onShowPatches,
            modifier = modifier
        )
    }
}

/**
 * Installed app card with gradient background.
 *
 * [installedApp] is [HomeAppItem.installedApp] handed over separately, so the card is written
 * against a record that is there rather than around one that might not be.
 */
@Composable
private fun InstalledAppCard(
    item: HomeAppItem,
    installedApp: InstalledApp,
    showStatusBadges: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onShowPatches: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val cardStyle = homeAppCardStyle(subtitleAlpha = 0.85f).onCard(item.gradientColors)
    val category = app.morphe.manager.ui.viewmodel.StoreCategory.of(LocalStoreListings.current[item.packageName])
    val wrapActions = wrapHomeRowActions()

    val versionLabel = stringResource(R.string.version)
    val cloneLabel = stringResource(R.string.clone)
    val installedLabel = stringResource(R.string.installed)
    val updateLabel = stringResource(R.string.update)
    val updateAvailableLabel = stringResource(R.string.update_available)
    val supportedVersionLabel = stringResource(R.string.home_app_info_newest_supported_version)
    val deletedLabel = stringResource(R.string.uninstalled)
    val replacementLabel = stringResource(R.string.home_unpatched_version_installed)
    val replacementBadgeLabel = stringResource(R.string.home_unpatched_badge)
    val unverifiedLabel = stringResource(R.string.home_unverified)
    val pendingLabel = stringResource(R.string.home_install_verification_pending)

    val showsPendingBadge = remember { mutableStateOf(false) }
    LaunchedEffect(item.isInstallStatePending) {
        if (!item.isInstallStatePending) {
            showsPendingBadge.value = false
            return@LaunchedEffect
        }
        delay(INSTALL_VERIFICATION_BADGE_DELAY_MS.milliseconds)
        showsPendingBadge.value = true
    }

    val version = remember(item) { item.version.withVersionPrefix() }
    // What tells the app from a namesake leads the row, ahead of the version
    val subtitle = remember(item) {
        listOfNotNull(item.nameSuffix, version.ifEmpty { null }).joinToString(" • ")
    }

    // The states the badges stand for are read out whether the badges themselves are showing or
    // not, so reordering does not quietly drop them from the card's description
    val contentDesc = remember(
        item,
        version,
        versionLabel,
        cloneLabel,
        installedLabel,
        updateAvailableLabel,
        supportedVersionLabel,
        deletedLabel,
        replacementLabel,
        unverifiedLabel,
        showsPendingBadge.value,
        pendingLabel
    ) {
        buildString {
            append(item.displayName)
            item.nameSuffix?.let { append(", $it") }
            if (item.isClone) append(", $cloneLabel")
            if (version.isNotEmpty()) {
                append(", $versionLabel $version")
            }
            append(", ")
            append(
                when {
                    showsPendingBadge.value -> pendingLabel
                    item.isInstallStateNotPatched -> replacementLabel
                    item.isDeleted -> deletedLabel
                    item.isInstallStateUnknown -> unverifiedLabel
                    else -> installedLabel
                }
            )
            if (item.showsUpdateBadge) append(", $updateAvailableLabel")
            // Read out in full even when the badge had no room to print it
            item.versionStatus?.takeIf { item.showsVersionBadge }?.let {
                append(", $supportedVersionLabel ${it.supportedVersion}")
            }
        }
    }

    AppCardLayout(
        gradientColors = item.gradientColors,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.semantics {
            role = Role.Button
            this.contentDescription = contentDesc
        }
    ) {
        // App icon
        AppIcon(
            packageInfo = item.packageInfo,
            // Named after the package this record was installed under: a clone carries an icon of
            // its own, which is regularly the whole point of keeping several copies of an app apart
            packageName = installedApp.currentPackageName,
            contentDescription = null,
            modifier = Modifier.size(cardStyle.iconSize),
            preferredSource = AppDataSource.INSTALLED,
            // A record can outlive every artifact carrying its icon, and the glass placeholder is
            // what the rest of the list shows in that case
            placeholderGradientColors = cardStyle.cardColors(item.gradientColors)
        )

        // App info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // App name
            Text(
                text = item.displayName,
                style = cardStyle.titleStyle,
                color = cardStyle.titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Version + deleted status + update chip, both pinned to the card edge
            Row(
                // Badge height is reserved whether a badge is showing, otherwise the row
                // grows around it and nudges the app name above out of place
                modifier = Modifier.heightIn(min = statusBadgeHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Says what the card is rather than how its install is doing, so it leads the
                // row and stays put while the state badges at the end come and go. Wordless
                // because the badges it shares the row with need the width for their own labels
                if (item.isClone) {
                    StatusBadge(
                        text = null,
                        icon = Icons.Outlined.ContentCopy,
                        containerColor = cardStyle.chipContainerColor,
                        contentColor = cardStyle.chipContentColor
                    )
                }

                // Fills the row so the chip is pinned to the card edge rather than trailing
                // a version string of whatever length
                HomePlayVersionText(
                    LocalHomePlayVersions.current[item.packageName], fallback = subtitle,
                    prefix = item.nameSuffix, modifier = Modifier.weight(1f))

                // The drag handle rides over this edge of the card while reordering, so the
                // badges pinned here stand down rather than crowd it
                if (showStatusBadges) {
                    // Frosted-glass colors: a white semi-transparent fill reads on any accent
                    // color the card's bundle brings, and on the user's dynamic theme
                    if (item.isDeleted && !item.isInstallStateNotPatched) {
                        StatusBadge(
                            text = deletedLabel,
                            icon = Icons.Outlined.DeleteOutline,
                            containerColor = cardStyle.chipContainerColor,
                            contentColor = cardStyle.chipContentColor
                        )
                    }

                    if (item.isInstallStateNotPatched) {
                        StatusBadge(
                            text = replacementBadgeLabel,
                            icon = Icons.Outlined.AutoFixHigh,
                            containerColor = cardStyle.chipContainerColor,
                            contentColor = cardStyle.chipContentColor
                        )
                    }

                    if (item.isInstallStateUnknown) {
                        StatusBadge(
                            text = unverifiedLabel,
                            icon = Icons.AutoMirrored.Outlined.HelpOutline,
                            containerColor = cardStyle.chipContainerColor,
                            contentColor = cardStyle.chipContentColor
                        )
                    }

                    if (showsPendingBadge.value) {
                        StatusBadge(
                            text = pendingLabel,
                            icon = Icons.Outlined.HourglassEmpty,
                            containerColor = cardStyle.chipContainerColor,
                            contentColor = cardStyle.chipContentColor
                        )
                    }

                    // Newer patches and a newer supported app version are both answered by
                    // rebuilding the app, so one badge stands for either rather than two of
                    // them stacking into a row that already carries the installed version
                    AnimatedVisibility(
                        visible = item.showsRebuildBadge,
                        enter = Animations.expandHorizFadeIn,
                        exit = Animations.shrinkHorizFadeOut
                    ) {
                        StatusBadge(
                            text = updateLabel,
                            icon = Icons.Outlined.ArrowUpward,
                            containerColor = cardStyle.chipContainerColor,
                            contentColor = cardStyle.chipContentColor
                        )
                    }
                }
            }
            category?.let { Text(stringResource(it.label), style = MaterialTheme.typography.labelMedium,
                color = cardStyle.subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (wrapActions) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                HomeAppRowShortcuts(item, cardStyle.titleColor, onShowPatches)
            }
        }
        if (!wrapActions) HomeAppRowShortcuts(item, cardStyle.titleColor, onShowPatches)
    }
}

/**
 * Card for an app Morphe has no build of yet.
 */
@Composable
private fun NotPatchedAppCard(
    item: HomeAppItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onShowPatches: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val notPatchedText = stringResource(R.string.home_not_patched_yet)
    val category = app.morphe.manager.ui.viewmodel.StoreCategory.of(LocalStoreListings.current[item.packageName])

    // Only for an app the device actually has: other cards are described by an APK Morphe kept
    // rather than by an install, and that version answers a different question
    val subtitle = remember(item, notPatchedText) {
        val version = item.version.takeIf { item.isInstalledOnDevice && it.isNotEmpty() }
        listOfNotNull(item.nameSuffix, version?.withVersionPrefix(), notPatchedText).joinToString(" • ")
    }

    val contentDesc = remember(item.displayName, subtitle) {
        "${item.displayName}, $subtitle"
    }

    AppCardLayout(
        gradientColors = item.gradientColors,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.semantics {
            role = Role.Button
            this.contentDescription = contentDesc
        }
    ) {
        AppCardContent(
            packageName = item.packageName,
            packageInfo = item.packageInfo,
            displayName = item.displayName,
            subtitle = subtitle,
            gradientColors = item.gradientColors,
            storeCategory = category?.let { stringResource(it.label) },
            actions = { HomeAppRowShortcuts(item, homeAppCardStyle().onCard(item.gradientColors).titleColor, onShowPatches) },
            versionFeedback = { HomePlayVersionText(LocalHomePlayVersions.current[item.packageName],
                fallback = subtitle, prefix = item.nameSuffix) },
        )
    }
}

/** Independent row action: listing identity exists before an APK is acquired. */
@Composable
internal fun HomeAppRowShortcuts(item: HomeAppItem, contentColor: Color, onShowPatches: (() -> Unit)?) {
    val uriHandler = LocalUriHandler.current
    val originalPackage = item.installedApp?.originalPackageName ?: item.packageName
    val listing = LocalStoreListings.current[originalPackage]?.takeIf { it.packageName == originalPackage }
    Row(verticalAlignment = Alignment.CenterVertically) {
        onShowPatches?.let { show ->
            IconButton(onClick = show, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.extended_show_patch_info),
                    tint = contentColor, modifier = Modifier.size(20.dp))
            }
        }
        if (listing != null) IconButton(
            onClick = { uriHandler.openUri(officialPlayStoreUrl(originalPackage)) },
            modifier = Modifier.size(48.dp).testTag("library.play-link.$originalPackage")
        ) {
            Icon(FontAwesomeIcons.Brands.GooglePlay,
                contentDescription = stringResource(R.string.extended_open_official_play_store),
                tint = contentColor, modifier = Modifier.size(20.dp))
        }
        if (onShowPatches != null) OriginalDownloadShortcut(item, originalPackage, contentColor)
    }
}

/** Tablet tile uses the same card surface, source identity and live shortcut implementations. */
@Composable
private fun HomeAppTile(item: HomeAppItem, onClick: () -> Unit, modifier: Modifier,
    showStatusBadges: Boolean, onLongClick: (() -> Unit)?, onShowPatches: (() -> Unit)?) {
    val style = homeAppCardStyle().onCard(item.gradientColors)
    val description = LocalStoreListings.current[item.packageName]?.description
    val patchStateLabel = stringResource(when {
        item.isInstallStatePending -> R.string.home_install_verification_pending
        item.isInstallStateUnknown -> R.string.home_unverified
        item.isCurrentPatchedInstall -> R.string.patched
        else -> R.string.home_not_patched_yet
    })
    val status = listOfNotNull(
        when {
            item.isInstallStatePending -> stringResource(R.string.home_install_verification_pending)
            item.isInstallStateNotPatched -> stringResource(R.string.home_unpatched_version_installed)
            item.isDeleted -> stringResource(R.string.uninstalled)
            item.isInstallStateUnknown -> stringResource(R.string.home_unverified)
            else -> null
        },
        stringResource(R.string.clone).takeIf { item.isClone },
        stringResource(R.string.update_available).takeIf { showStatusBadges && item.showsRebuildBadge }
    ).joinToString(" · ")
    val subtitle = homeTileSubtitle(item)
    AppCardLayout(modifier.semantics {
        role = Role.Button
        contentDescription = "${item.displayName}, $subtitle, $patchStateLabel, $status"
    }, item.gradientColors, onClick, onLongClick) {
        HomeTileContent(item.displayName, description, subtitle, status,
            style.titleColor, style.subtitleColor,
            icon = {
                HomeTileIcon(if (item.isInstallStatePending || item.isInstallStateUnknown) null
                    else item.isCurrentPatchedInstall, patchStateLabel) {
                AppIcon(packageInfo = item.packageInfo, packageName = homeTileIconPackage(item),
                    preferredSource = if (item.installedApp != null) AppDataSource.INSTALLED else AppDataSource.PATCHED_APK,
                    contentDescription = null, modifier = Modifier.size(40.dp),
                    placeholderGradientColors = style.cardColors(item.gradientColors))
                }
            }, actions = { HomeAppRowShortcuts(item, style.titleColor, onShowPatches) },
            versionFeedback = { HomePlayVersionText(LocalHomePlayVersions.current[item.packageName],
                fallback = subtitle, prefix = item.nameSuffix, suffix = status) })
    }
}

@Composable
internal fun RowScope.HomeTileContent(name: String, description: String?, subtitle: String, status: String,
    titleColor: Color, subtitleColor: Color, icon: @Composable () -> Unit, actions: @Composable () -> Unit,
    versionFeedback: (@Composable () -> Unit)? = null) {
    val titleLines = if (LocalDensity.current.fontScale > 1.3f) 2 else 1
    val identityHeight = with(LocalDensity.current) {
        MaterialTheme.typography.titleMedium.lineHeight.toDp() * titleLines + MaterialTheme.typography.labelMedium.lineHeight.toDp() + 4.dp
    }.coerceAtLeast(48.dp)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = identityHeight), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = titleColor, minLines = titleLines, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                if (versionFeedback != null) versionFeedback() else {
                    Text(listOf(subtitle, status).filter(String::isNotBlank).joinToString(" · "),
                        color = subtitleColor, style = MaterialTheme.typography.labelMedium,
                        minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row { actions() }
        }
        Text(description.orEmpty(), color = subtitleColor, style = MaterialTheme.typography.bodyMedium,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

internal val LocalHomePlayVersions = staticCompositionLocalOf<Map<String, HomePlayVersion>> { emptyMap() }

@Composable
internal fun HomePlayVersionText(value: HomePlayVersion?, fallback: String = "", prefix: String? = null,
    suffix: String? = null, modifier: Modifier = Modifier) {
    val classification = value?.verdict ?: PlayVersionVerdict.UNKNOWN
    val verdict = stringResource(when (classification) {
        PlayVersionVerdict.BEHIND -> R.string.library_play_behind
        PlayVersionVerdict.MATCHED -> R.string.library_play_matched
        PlayVersionVerdict.UNKNOWN -> R.string.library_play_unknown
    })
    val version = value?.supported?.withVersionPrefix()?.let {
        listOfNotNull(prefix?.takeIf(String::isNotBlank), it).joinToString(" · ")
    } ?: fallback
    val label = listOfNotNull(version.takeIf(String::isNotBlank), suffix?.takeIf(String::isNotBlank)).joinToString(" · ")
    Text(label,
        color = when (classification) {
            PlayVersionVerdict.BEHIND -> SemanticTone.Warning.accent
            PlayVersionVerdict.MATCHED -> SemanticTone.Success.accent
            PlayVersionVerdict.UNKNOWN -> Color.White
        }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag("library.play-version.$classification").semantics {
            stateDescription = verdict
        })
}

/** Informational badge only; the parent tile retains its original click/long-click actions. */
@Composable
internal fun HomeTileIcon(patched: Boolean?, label: String, icon: @Composable () -> Unit) {
    Box(Modifier.size(40.dp).semantics { contentDescription = label; role = Role.Image }) {
        icon()
        if (patched != false) Box(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp)
            .size(18.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
            .testTag(if (patched == true) "library.patch-badge.patched" else "library.patch-badge.uncertain"),
            contentAlignment = Alignment.Center) {
            Icon(if (patched == null) Icons.AutoMirrored.Outlined.HelpOutline else Icons.Outlined.Healing,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * Shared content layout for app cards and buttons.
 *
 * Uses a frosted glass effect built from two passes:
 * - a diagonal sweep carrying the card colors from the bottom-start tint to the top-end accent
 * - a gradient border
 */
@Composable
internal fun AppCardLayout(
    modifier: Modifier = Modifier,
    gradientColors: List<Color>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val cardStyle = homeAppCardStyle().onCard(gradientColors)
    val shape = RoundedCornerShape(cardStyle.cardRadius)
    val view = LocalView.current

    // Long-pressing a card always means the same thing here, so the gesture is announced
    // rather than left as an unlabeled action the screen reader cannot describe
    val longClickLabel = stringResource(R.string.accessibility_select_app)
        .takeIf { onLongClick != null }

    val colors = cardStyle.cardColors(gradientColors)
    val baseColor = colors.firstOrNull() ?: Color.White
    val midColor = colors.getOrElse(1) { baseColor }
    val endColor = colors.lastOrNull() ?: baseColor

    // Press scale animation
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = cardStyle.cardHeight)
            .pressScale(
                interactionSource = interactionSource,
                label = "card_press_scale"
            )
            .clip(shape)
            // Brushes are rebuilt only when the size or the palette changes, so scrolling a list
            // of cards does not reallocate them on every frame
            .drawWithCache {
                val w  = size.width
                val h  = size.height
                val cr = CornerRadius(cardStyle.cardRadius.toPx())
                val rtl = layoutDirection.isRtl

                if (cardStyle.monochrome) {
                    return@drawWithCache onDrawWithContent {
                        drawRoundRect(
                            color = cardStyle.cardColor,
                            cornerRadius = cr
                        )

                        drawContent()
                    }
                }

                // One sweep from the bottom-start tint through to the top-end accent. Every
                // translucent layer costs the GPU a full blend pass over the card, and a list of
                // them scrolling is what pushed the frame past its budget
                val glass = Brush.linearGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0.70f),
                        midColor.copy(alpha = 0.58f),
                        endColor.copy(alpha = 0.64f)
                    ),
                    start = Offset(startEdgeX(w, rtl), h),
                    end   = Offset(endEdgeX(w, rtl), 0f)
                )

                // Border: bright top-start → faded bottom-end. The highlight borrows its middle from
                // the card colors, which on a single dark color fades into the background and leaves
                // only the corners lit, so a flat card gets an even edge instead
                val border = if (colors.all { it == baseColor }) {
                    SolidColor(cardStyle.titleColor.copy(alpha = 0.30f))
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.65f),
                            midColor.copy(alpha = 0.30f),
                            endColor.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.20f)
                        ),
                        start = Offset(startEdgeX(w, rtl), 0f),
                        end   = Offset(endEdgeX(w, rtl), h)
                    )
                }
                val borderStroke = Stroke(width = 1.5.dp.toPx())

                onDrawWithContent {
                    drawRoundRect(brush = glass, cornerRadius = cr)

                    drawContent()

                    if (cardStyle.outlines) {
                        drawRoundRect(
                            brush = border,
                            cornerRadius = cr,
                            style = borderStroke
                        )
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
                onLongClickLabel = longClickLabel,
                onLongClick = if (onLongClick != null) {
                    {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongClick()
                    }
                } else null
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = cardStyle.cardHeight)
                .padding(horizontal = cardStyle.contentPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(cardStyle.contentSpacing),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * Shimmer loading animation for app cards.
 */
@Composable
fun AppLoadingCard(
    gradientColors: List<Color>,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")

    // Pulse animation for gradient background
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Shimmer animation
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val cardStyle = homeAppCardStyle().onCard(gradientColors)
    val shape = RoundedCornerShape(cardStyle.cardRadius)
    val rtl = isRtl()

    // Skeleton rows carry the height of the text they stand in for, so the card does not
    // re-lay-out its content the moment the real app resolves
    val titleRowHeight = with(LocalDensity.current) { cardStyle.titleStyle.lineHeight.toDp() }

    // Follows the content the card settled on, so the skeleton stays visible on a light gradient
    val skeletonColor = cardStyle.titleColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(cardStyle.cardHeight)
    ) {
        // Base gradient background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .then(
                    if (cardStyle.monochrome) {
                        Modifier.background(cardStyle.cardColor)
                    } else {
                        Modifier.background(
                            brush = Brush.linearGradient(
                                colors = cardStyle.cardColors(gradientColors)
                                    .map { it.copy(alpha = pulseAlpha) },
                                start = Offset(startEdgeX(1000f, rtl), 0f),
                                end = Offset(endEdgeX(1000f, rtl), 0f)
                            )
                        )
                    }
                )
        )

        // Shimmer overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .drawBehind {
                    drawDiagonalShimmer(
                        progress = (shimmerOffset + 1f) / 3f,
                        color = skeletonColor.copy(alpha = 0.3f)
                    )
                }
        )

        // Content skeleton
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = cardStyle.contentPadding),
            horizontalArrangement = Arrangement.spacedBy(cardStyle.contentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon skeleton, sized and shaped like the glass placeholder
            ShimmerBox(
                modifier = Modifier
                    .size(cardStyle.iconSize)
                    .wrapContentSize()
                    .fillMaxSize(AppIconContentFraction),
                shape = AppIconShape,
                baseColor = skeletonColor.copy(alpha = 0.2f)
            )

            // Text skeleton
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ShimmerLine(
                    height = titleRowHeight,
                    widthFraction = 0.6f,
                    baseColor = skeletonColor.copy(alpha = 0.25f)
                )
                ShimmerLine(
                    height = statusBadgeHeight,
                    widthFraction = 0.4f,
                    baseColor = skeletonColor.copy(alpha = 0.15f)
                )
            }
        }
    }
}
