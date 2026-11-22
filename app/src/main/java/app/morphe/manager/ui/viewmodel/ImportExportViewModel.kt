/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/ui/viewmodel/ImportExportViewModel.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.ui.viewmodel

import java.io.FileOutputStream

import java.io.OutputStream

import android.provider.MediaStore

import android.os.Environment

import android.content.ContentValues

import android.app.Application
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morphe.manager.domain.backup.AutoBackup
import app.morphe.manager.domain.backup.SettingsExporter
import app.morphe.manager.worker.ScheduleInterval
import app.morphe.manager.R
import app.morphe.manager.domain.manager.HomeAppButtonPreferences
import app.morphe.manager.domain.manager.KeystoreManager
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.manager.SettingsSection
import app.morphe.manager.domain.manager.SigningKeyInfo
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.domain.repository.PatchOptionsRepository
import app.morphe.manager.domain.repository.PatchSelectionRepository
import app.morphe.manager.domain.repository.SelectionSetRepository
import app.morphe.manager.domain.repository.SourceMuteRepository
import app.morphe.manager.domain.repository.StoredSelectionSet
import app.morphe.manager.ui.screen.shared.UserMessages
import app.morphe.manager.util.*
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.deleteExisting
import kotlin.io.path.inputStream

@Serializable
data class ManagerSettingsExportFile(
    val version: Int = 1,
    val settings: PreferencesManager.SettingsSnapshot,
    // Null in backups written before selections could travel with the settings, and in any that
    // leave them out
    val selections: AllSelectionsExportFile? = null
)

/**
 * Export file format for patch selections and options.
 * This format stores selections (which patches are enabled) and their options (patch configuration).
 */
@Serializable
data class PatchBundleDataExportFile(
    val version: Int = 1,
    val bundleUid: Int,
    val bundleName: String? = null,
    // Normalized endpoint URL - used to remap bundleUid when importing on a different device
    val bundleSource: String? = null,
    val exportDate: String,
    // Map<PackageName, List<PatchName>>
    val selections: Map<String, List<String>>,
    // Map<PackageName, Map<PatchName, Map<OptionKey, OptionValue>>>
    // Deliberately without a default: the field has always been written out, and giving it one
    // would drop it from the file for versions that still require it
    val options: Map<String, Map<String, Map<String, String>>>?,
    // Apps kept from this source. Null in backups written before an app could be, which reads the
    // same as a backup where no app was
    val mutedPackages: List<String>? = null,
    val knownApps: List<String>? = null
)

/**
 * Export file format for all selections across all bundles.
 * Wraps a list of [PatchBundleDataExportFile] entries - one per bundle.
 */
@Serializable
data class AllSelectionsExportFile(
    val version: Int = 1,
    val exportDate: String,
    val bundles: List<PatchBundleDataExportFile>,
    // Null in backups written before sets could be kept, which leaves the sets on the device alone
    val sets: List<SelectionSetExportFile>? = null
)

/** A set kept for one app, see [SelectionSetRepository], as a backup carries it. */
@Serializable
data class SelectionSetExportFile(
    val packageName: String,
    val name: String,
    val sources: List<SelectionSetSourceExport>
)

/** What a [SelectionSetExportFile] holds of one source. */
@Serializable
data class SelectionSetSourceExport(
    val bundleUid: Int,
    // Normalized endpoint URL, which the source is found by on another device, as with the
    // bundleSource of a PatchBundleDataExportFile
    val bundleSource: String? = null,
    val patches: List<String>,
    // Map<PatchName, Map<OptionKey, OptionValue>>
    val options: Map<String, Map<String, String>>? = null
)

/**
 * Serializable snapshot of remote patch bundle, used for export/import
 * as part of the manager settings file [ManagerSettingsExportFile].
 */
@Serializable
data class BundleSnapshot(
    val name: String,
    val displayName: String? = null,
    val source: String,
    // Every remote source updates on its own, so this is ignored on import. Still written, as
    // older manager versions require it to read the file
    val autoUpdate: Boolean,
    val enabled: Boolean = true,
    val sortOrder: Int,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    // Prerelease toggle. Null in backups written before per-source toggles were exported
    val prerelease: Boolean? = null,
    // Experimental versions toggle. Null in backups written before per-source toggles were exported
    val experimentalVersions: Boolean? = null
)

@OptIn(ExperimentalSerializationApi::class)
class ImportExportViewModel(
    private val app: Application,
    private val keystoreManager: KeystoreManager,
    private val preferencesManager: PreferencesManager,
    private val homeAppButtonPreferences: HomeAppButtonPreferences,
    private val patchSelectionRepository: PatchSelectionRepository,
    private val sourceMuteRepository: SourceMuteRepository,
    private val patchOptionsRepository: PatchOptionsRepository,
    private val selectionSetRepository: SelectionSetRepository,
    private val patchBundleRepository: PatchBundleRepository,
    private val settingsExporter: SettingsExporter,
    private val autoBackup: AutoBackup
) : ViewModel() {
    private val contentResolver = app.contentResolver

    private var keystoreImportPath by mutableStateOf<Path?>(null)
    private var keystoreImportFormat by mutableStateOf(KeystoreInputFormat.KEYSTORE)
    val showCredentialsDialog by derivedStateOf { keystoreImportPath != null }
    val detectedKeystoreFormat: KeystoreInputFormat get() = keystoreImportFormat

    /** The key patching signs with, null until the first patch creates one. */
    var signingKey by mutableStateOf<SigningKeyInfo?>(null)
        private set

    /**
     * Parts of the manager settings a backup moves. One choice serves both directions, so what the
     * user ticked before exporting is what an import on the other device offers to bring back.
     */
    var settingsSections by mutableStateOf(SettingsSection.entries.toSet())
        private set

    fun toggleSettingsSection(section: SettingsSection) {
        settingsSections = if (section in settingsSections) settingsSections - section
        else settingsSections + section
    }

    fun enableAutoBackup(folder: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_auto_backup_folder_failed, "Failed to use the backup folder") {
            reportBackup(autoBackup.enable(folder))
        }
    }

    fun disableAutoBackup() = viewModelScope.launch { autoBackup.disable() }
    fun backUpNow() = viewModelScope.launch { reportBackup(autoBackup.backUpNow()) }
    private fun reportBackup(saved: Boolean) = app.toast(
        app.getString(if (saved) R.string.settings_system_auto_backup_saved else R.string.settings_system_auto_backup_failed)
    )
    fun setAutoBackupInterval(interval: ScheduleInterval) = viewModelScope.launch { autoBackup.setInterval(interval) }
    fun setAutoBackupSigningKey(include: Boolean) = viewModelScope.launch { autoBackup.setIncludesSigningKey(include) }

    /** Re-reads [signingKey], which patching and an import both change behind the screen's back. */
    fun refreshSigningKey() = viewModelScope.launch {
        signingKey = keystoreManager.signingKeyInfo()
    }

    fun startKeystoreImport(content: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_import_keystore_failed, "Failed to import keystore") {
            val path = withContext(Dispatchers.IO) {
                File.createTempFile("signing", "ks", app.cacheDir).toPath().also {
                    Files.copy(
                        contentResolver.openInputStream(content)!!,
                        it,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }

            // Detect format: magic bytes first (reliable), extension as fallback
            val detectedFormat = withContext(Dispatchers.IO) {
                val header = path.inputStream().use { stream ->
                    val buf = ByteArray(4)
                    stream.read(buf)
                    buf
                }
                KeystoreInputFormat.detectFromBytes(header)
            } ?: run {
                val ext = content.lastPathSegment?.substringAfterLast('.')?.lowercase() ?: ""
                KeystoreInputFormat.fromExtension(ext) ?: KeystoreInputFormat.PKCS12
            }
            keystoreImportFormat = detectedFormat

            // Try known aliases/passwords with all supported formats (detected format first)
            val autoFormats = listOf(detectedFormat) + (KeystoreInputFormat.entries - detectedFormat)
            for (format in autoFormats) {
                for (alias in aliases) {
                    for (pass in knownPasswords) {
                        if (tryKeystoreImport(alias, pass, "", format, path)) return@launch
                    }
                }
            }

            // Auto-import failed - ask the user for credentials
            keystoreImportPath = path
        }
    }

    fun cancelKeystoreImport() {
        keystoreImportPath?.deleteExisting()
        keystoreImportPath = null
    }

    suspend fun tryKeystoreImport(alias: String, pass: String, keystorePw: String = "", format: KeystoreInputFormat): Boolean =
        tryKeystoreImport(alias, pass, keystorePw, format, keystoreImportPath!!)

    private suspend fun tryKeystoreImport(alias: String, pass: String, keystorePw: String, format: KeystoreInputFormat, path: Path): Boolean {
        // BKS is passed through as-is; converting it would re-encrypt the private key
        // in a way that ApkSigner cannot read back
        val bksBytes = if (format == KeystoreInputFormat.BKS || format == KeystoreInputFormat.KEYSTORE) {
            withContext(Dispatchers.IO) { path.toFile().readBytes() }
        } else {
            val result = withContext(Dispatchers.IO) {
                path.inputStream().use { KeystoreConversionUtils.convert(it, format, alias, pass) }
            }
            when (result) {
                is KeystoreConversionResult.Error -> return false
                is KeystoreConversionResult.Success -> result.data.toByteArray()
            }
        }

        return try {
            val imported = withContext(Dispatchers.IO) {
                Files.write(path, bksBytes)
                keystoreManager.import(alias, pass, path.toFile())
            }
            if (imported) {
                app.toast(app.getString(R.string.settings_system_import_keystore_success))
                cancelKeystoreImport()
                refreshSigningKey()
            }
            imported
        } catch (_: IOException) {
            false
        }
    }

    fun exportKeystore(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_export_keystore_failed, "Failed to export keystore") {
            withContext(Dispatchers.IO) {
                keystoreManager.export(contentResolver.openOutputStream(target)!!)
            }
            app.toast(app.getString(R.string.settings_system_export_keystore_success))
        }
    }

    fun importManagerSettings(
        source: Uri,
        mode: PatchBundleRepository.ImportMode = PatchBundleRepository.ImportMode.Merge,
    ) = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_import_manager_settings_fail, "Failed to import manager settings") {
            val exportFile = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(source)!!.use {
                    settingsJson.decodeFromStream<ManagerSettingsExportFile>(it)
                }
            }

            val sections = settingsSections
            val settings = exportFile.settings.restrictedTo(sections)
            preferencesManager.importSettings(settings)

            // The app language and the home app buttons (categories, hidden apps, sort mode, etc.)
            // live outside PreferencesManager, so they're applied here rather than in importSettings
            settings.appLanguage?.let { AppLocale.select(app, it) }
            settings.homeAppButtons?.let(homeAppButtonPreferences::importState)

            // Called whenever sources are taken, so Replace can clear existing custom sources even
            // when the backup has none, and never otherwise, so declining them leaves them alone
            if (SettingsSection.SOURCES in sections) {
                patchBundleRepository.importCustomBundles(settings.customBundles.orEmpty(), mode)
            }

            // After the sources, so selections made against a source the backup brought back
            // find it in place
            if (SettingsSection.PATCH_SELECTIONS in sections) {
                exportFile.selections?.let { importSelectionBundles(it.bundles, it.sets, mode) }
            }

            app.toast(app.getString(R.string.settings_system_import_manager_settings_success))
        }
    }

    /** Everything a settings backup holds, cut down to [settingsSections]. */
    private suspend fun managerSettingsExportFile() = settingsExporter.managerSettings(settingsSections)

    fun exportManagerSettings(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_export_manager_settings_fail, "Failed to export manager settings") {
            val exportFile = managerSettingsExportFile()
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    settingsJson.encodeToStream(exportFile, output)
                }
            }

            app.toast(app.getString(R.string.settings_system_export_manager_settings_success))
        }
    }

    /**
     * Export patch selections and options for a specific package+bundle combination.
     */
    fun exportPackageBundleData(
        packageName: String,
        bundleUid: Int,
        bundleName: String?,
        target: Uri
    ) = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_export_source_data_fail, "Failed to export source data") {
            val (selections, optionsData) = withContext(Dispatchers.IO) {
                val patchList = patchSelectionRepository.exportForPackageAndBundle(
                    packageName,
                    bundleUid
                )

                val rawOptions = patchOptionsRepository.exportOptionsForBundle(
                    packageName = packageName,
                    bundleUid = bundleUid
                )

                val optionsData = if (rawOptions.isNotEmpty()) {
                    mapOf(packageName to rawOptions)
                } else {
                    emptyMap()
                }

                mapOf(packageName to patchList) to optionsData
            }

            val muted = withContext(Dispatchers.IO) {
                bundleUid in sourceMuteRepository.getMutedFor(packageName)
            }

            val exportFile = PatchBundleDataExportFile(
                bundleUid = bundleUid,
                bundleName = bundleName,
                bundleSource = patchBundleRepository.getEndpointForUid(bundleUid),
                exportDate = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime.now()),
                selections = selections,
                options = optionsData,
                mutedPackages = listOf(packageName).takeIf { muted }
            )

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    json.encodeToStream(exportFile, output)
                }
            }

            app.toast(app.getString(R.string.settings_system_export_source_data_success))
        }
    }

    /**
     * Export all patch selections and options across all bundles into a single file.
     */
    fun exportAllSelections(target: Uri) = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_export_source_data_fail, "Failed to export selections") {
            val exportFile = allSelectionsExportFile()

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target, "wt")!!.use { output ->
                    json.encodeToStream(exportFile, output)
                }
            }

            app.toast(app.getString(R.string.settings_system_export_source_data_success))
        }
    }

    /** Every source's patch selections, options and kept apps, and every set, as one export. */
    private suspend fun allSelectionsExportFile() = settingsExporter.allSelections()

    /**
     * Filename for the all-selections export file.
     */
    fun getAllSelectionsExportFileName(): String =
        FilenameUtils.timestamped("morphe_all_selections.json")

    /**
     * Import patch selections and options from a file.
     * Supports both [PatchBundleDataExportFile] (single bundle) and
     * [AllSelectionsExportFile] (all bundles) formats, detected automatically.
     *
     * In [PatchBundleRepository.ImportMode.Merge] mode existing selections are preserved
     * and new ones are added on top. In [PatchBundleRepository.ImportMode.Replace] mode the
     * imported state fully overwrites the current state: bundles absent from the backup have
     * their selections and options cleared.
     */
    fun importAllSelections(
        source: Uri,
        mode: PatchBundleRepository.ImportMode = PatchBundleRepository.ImportMode.Merge,
    ) = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_import_source_data_fail, "Failed to import selections") {
            val bytes = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(source)!!.use { it.readBytes() }
            }

            // Detect format by checking for "bundles" key
            val isAllSelections = withContext(Dispatchers.IO) {
                val element = json.decodeFromString<kotlinx.serialization.json.JsonObject>(bytes.decodeToString())
                element.containsKey("bundles")
            }

            // A file of one source's selections carries no sets
            val (bundleFiles, sets) = withContext(Dispatchers.IO) {
                if (isAllSelections) {
                    json.decodeFromString<AllSelectionsExportFile>(bytes.decodeToString()).let { it.bundles to it.sets }
                } else {
                    listOf(json.decodeFromString<PatchBundleDataExportFile>(bytes.decodeToString())) to null
                }
            }

            importSelectionBundles(bundleFiles, sets, mode)

            app.toast(app.getString(R.string.settings_system_import_source_data_success))
        }
    }

    /**
     * Applies exported selections and [sets] onto this device's sources, the way
     * [importAllSelections] describes for [mode]. Null [sets], from a backup older than sets, leave
     * the sets on the device alone.
     */
    private suspend fun importSelectionBundles(
        bundleFiles: List<PatchBundleDataExportFile>,
        sets: List<SelectionSetExportFile>?,
        mode: PatchBundleRepository.ImportMode
    ) {
        withContext(Dispatchers.IO) {
            // Remap exported UIDs to current-device UIDs. Bundle UIDs are randomly generated
            // per device so the raw exported value only matches on the origin device
            val remapped = bundleFiles.map { file -> file to localUid(file.bundleUid, file.bundleSource) }
            val incomingUids = remapped
                .map { it.second }
                .filter { patchBundleRepository.isUidLoaded(it) }
                .toSet()

            // Replace mode: clear selections/options for bundles that currently have data
            // but are not represented in the backup
            if (mode == PatchBundleRepository.ImportMode.Replace) {
                val existingUids = (
                        patchSelectionRepository.getAllBundleUids() +
                                sourceMuteRepository.getAllBundleUids()
                        ).toSet()
                (existingUids - incomingUids).forEach { uid ->
                    patchSelectionRepository.import(uid, emptyMap())
                    patchOptionsRepository.resetOptionsForPatchBundle(uid)
                    sourceMuteRepository.resetForBundle(uid)
                }
            }

            remapped.forEach { (exportFile, bundleUid) ->
                // Skip if the bundle is not loaded - inserting with an unknown UID would
                // violate the patch_selections FK constraint on patch_bundles.uid
                if (!patchBundleRepository.isUidLoaded(bundleUid)) return@forEach

                when (mode) {
                    PatchBundleRepository.ImportMode.Replace -> {
                        // Full per-bundle replace: reset first, then load from backup
                        patchSelectionRepository.import(bundleUid, exportFile.selections)
                        patchOptionsRepository.resetOptionsForPatchBundle(bundleUid)
                        exportFile.options?.forEach { (packageName, packageOptions) ->
                            patchOptionsRepository.importOptionsForBundle(
                                packageName = packageName,
                                bundleUid = bundleUid,
                                options = packageOptions
                            )
                        }
                        sourceMuteRepository.importForBundle(
                            bundleUid,
                            exportFile.mutedPackages.orEmpty(),
                            knownApps = exportFile.knownApps
                        )
                    }
                    PatchBundleRepository.ImportMode.Merge -> {
                        exportFile.selections.forEach { (packageName, patchList) ->
                            patchSelectionRepository.importForPackageAndBundle(
                                packageName = packageName,
                                bundleUid = bundleUid,
                                patches = patchList
                            )
                        }
                        exportFile.options?.forEach { (packageName, packageOptions) ->
                            patchOptionsRepository.importOptionsForBundle(
                                packageName = packageName,
                                bundleUid = bundleUid,
                                options = packageOptions
                            )
                        }
                        sourceMuteRepository.mergeForBundle(bundleUid, exportFile.mutedPackages.orEmpty(), exportFile.knownApps)
                    }
                }
            }

            if (sets != null) importSets(sets, mode)
        }
    }

    /**
     * Restores [sets], each on the sources this device has of it. In Replace mode the sets on the
     * device go first. A set none of whose sources are here is left out rather than kept empty.
     */
    private suspend fun importSets(sets: List<SelectionSetExportFile>, mode: PatchBundleRepository.ImportMode) {
        if (mode == PatchBundleRepository.ImportMode.Replace) selectionSetRepository.reset()

        sets.forEach { set ->
            // A set refers to its sources, so one this device does not have cannot be kept
            val sources = set.sources.mapNotNull { source ->
                val uid = localUid(source.bundleUid, source.bundleSource)
                if (patchBundleRepository.isUidLoaded(uid)) uid to source else null
            }
            if (sources.isEmpty() && set.sources.isNotEmpty()) return@forEach

            selectionSetRepository.import(
                StoredSelectionSet(
                    packageName = set.packageName,
                    name = set.name,
                    patches = sources.associate { (uid, source) -> uid to source.patches },
                    options = sources.mapNotNull { (uid, source) -> source.options?.let { uid to it } }.toMap()
                )
            )
        }
    }

    /** The uid on this device of a source exported as [bundleUid] from [bundleSource]. */
    private fun localUid(bundleUid: Int, bundleSource: String?): Int =
        bundleSource?.let { patchBundleRepository.resolveUidForEndpoint(it) } ?: bundleUid

    /**
     * Get filename for package+bundle data export.
     */
    fun getPackageBundleDataExportFileName(packageName: String, bundleUid: Int, bundleName: String?): String {
        val bundle = bundleName?.replace(" ", "_")?.take(20) ?: "bundle_$bundleUid"
        val pkg = packageName.substringAfterLast('.').take(15)
        return FilenameUtils.timestamped("morphe_${bundle}_${pkg}.json")
    }

    val debugLogFileName: String
        get() = FilenameUtils.timestamped("morphe_logcat.log")

    /**
     * Writes the debug log content to [writer]. Returns the logcat exit code.
     * Must be called from within a [Dispatchers.IO] context.
     * Shared by [exportDebugLogs].
     */
    private suspend fun writeDebugLogContent(writer: BufferedWriter): Int = withContext(Dispatchers.IO) {
        val versionName = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrDefault("unknown")

        writer.write("=== Morphe Manager Debug Log ===\n")
        writer.write("Date       : ${LocalDateTime.now()}\n")
        writer.write("Version    : $versionName\n")

        writer.write("\n--- Device ---\n")
        writer.write("Model      : ${Build.MANUFACTURER} ${Build.MODEL}\n")
        writer.write("Android    : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        writer.write("ABI        : ${Build.SUPPORTED_ABIS.joinToString()}\n")
        writer.write("Locale     : ${Locale.getDefault().toLanguageTag()}\n")

        val stats = app.deviceStats()
        val toMb = ::bytesToMebibytes

        writer.write("\n--- Memory ---\n")
        if (stats == null) {
            writer.write("Unavailable\n")
        } else {
            writer.write("RAM avail  : ${toMb(stats.ramAvailable)} MB / ${toMb(stats.ramTotal)} MB\n")
            writer.write("Low memory : ${stats.lowMemory}\n")
            writer.write("Low mem thr: ${toMb(stats.lowMemoryThreshold)} MB\n")
        }

        writer.write("\n--- Storage ---\n")
        if (stats != null) {
            writer.write("Internal   : ${formatBytesForReport(stats.storageAvailable)} free / ${formatBytesForReport(stats.storageTotal)} total\n")
        }
        var sdCardIndex = 1
        app.externalStorageVolumes().forEach { (isPrimary, root) ->
            if (isPrimary) {
                writer.write("External   : ${formatBytesForReport(root.freeSpace)} free / ${formatBytesForReport(root.totalSpace)} total\n")
            } else {
                writer.write("SD Card $sdCardIndex  : ${formatBytesForReport(root.freeSpace)} free / ${formatBytesForReport(root.totalSpace)} total\n")
                sdCardIndex++
            }
        }

        writer.write("\n--- Environment ---\n")
        val hasRoot = runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
        }.getOrDefault(false)
        writer.write("Root access: $hasRoot\n")

        writer.write("\n=== Logcat ===\n\n")

        val consumer = Redirect.Consume { flow ->
            flow
                .onEach { line -> writer.write("$line\n") }
                .flowOn(Dispatchers.IO)
                .collect { }
        }

        // Filter logs by current process UID to include only Morphe Manager logs
        process("logcat", "-d", "--uid=${app.applicationInfo.uid}", stdout = consumer).resultCode
    }

    fun exportDebugLogs(target: Uri) = viewModelScope.launch {
        val exitCode = try {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(target)!!.bufferedWriter().use { writer ->
                    writeDebugLogContent(writer)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Got exception while exporting logs", e)
            app.toast(app.getString(R.string.settings_system_export_debug_logs_export_failed))
            return@launch
        }

        if (exitCode == 0)
            app.toast(app.getString(R.string.settings_system_export_debug_logs_export_success))
        else {
            app.toast(app.getString(R.string.settings_system_export_debug_logs_export_read_failed).format(exitCode))
        }
    }

    override fun onCleared() {
        cancelKeystoreImport()
    }

    companion object {
        // Reusable JSON instances to avoid redundant creation. Both are internal so the export
        // format stays under test instead of the tests rebuilding their own configuration
        internal val json = SettingsExporter.json
        internal val settingsJson = SettingsExporter.settingsJson

        private val knownPasswords = arrayOf("Morphe", "s3cur3p@ssw0rd")
        private val aliases = arrayOf(KeystoreManager.DEFAULT, "alias", "Morphe Key")
    }
    private fun openDownloadsOutputStream(fileName: String, mimeType: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            contentResolver.openOutputStream(uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            FileOutputStream(File(dir, fileName))
        }
    }

    /**
     * Exports the keystore to the Downloads folder.
     */
    fun exportKeystoreToDownloads() = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_export_keystore_failed, "Failed to export keystore to Downloads") {
            withContext(Dispatchers.IO) {
                val stream = openDownloadsOutputStream("Morphe.keystore", BIN_MIMETYPE)
                    ?: throw IllegalStateException("Cannot open Downloads output stream")
                stream.use { keystoreManager.export(it) }
            }
            app.toast(app.getString(R.string.settings_system_export_keystore_success))
        }
    }

    /**
     * Exports manager settings to the Downloads folder.
     */
    fun exportManagerSettingsToDownloads() = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_export_manager_settings_fail, "Failed to export settings to Downloads") {
            val exportFile = managerSettingsExportFile()
            withContext(Dispatchers.IO) {
                val stream = openDownloadsOutputStream("morphe_manager_settings.json", JSON_MIMETYPE)
                    ?: throw IllegalStateException("Cannot open Downloads output stream")
                stream.use { settingsJson.encodeToStream(exportFile, it) }
            }
            app.toast(app.getString(R.string.settings_system_export_manager_settings_success))
        }
    }

    /**
     * Exports debug logs to the Downloads folder.
     */
    fun exportDebugLogsToDownloads() = viewModelScope.launch {
        uiSafe(app, R.string.settings_system_export_debug_logs_export_failed, "Failed to export debug logs to Downloads") {
            val stream = openDownloadsOutputStream(debugLogFileName, TEXT_MIMETYPE)
                ?: throw IllegalStateException("Cannot open Downloads output stream")
            stream.bufferedWriter().use { writer -> writeDebugLogContent(writer) }
            app.toast(app.getString(R.string.settings_system_export_debug_logs_export_success))
        }
    }



}
