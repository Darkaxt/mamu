package app.morphe.manager.ui.viewmodel

import android.app.Application
import android.app.DownloadManager
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.morphe.manager.domain.installer.InstallerFileProvider
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.downloaders.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.File
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import app.morphe.manager.patcher.logger.LogLevel

/** Owns one original download across recomposition/rotation; only Retry starts another attempt. */
class NativeOriginalDownloadViewModel(
    private val app: Application,
    val prefs: PreferencesManager
) : ViewModel() {
    sealed interface State {
        data object Idle : State
        data class Resolving(val message: String) : State
        data class Choices(val page: ByparrClient.Page) : State
        data class StoreChoices(val source: DownloadSource, val choices: List<RemotePage.Choice>) : State
        data class Transfer(val bytes: Long, val total: Long, val paused: Boolean, val shop: String? = null, val step: String? = null) : State
        data class Ready(val uri: Uri, val identity: ArchiveIdentity?) : State
        data class Failure(val message: String, val canTryShops: Boolean = false) : State
        data object HandedOff : State
        /**
         * Google Play could not supply the original and no Byparr server is set, so the download
         * sites are left to the browser, through upstream's download instructions.
         */
        data object Manual : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()
    internal val activity = DownloadActivityLog()
    val activityLog = activity.entries
    private val downloads = Downloads(app)
    private var requestUrl: String? = null
    private var expectedTarget: OriginalDownloadTarget? = null
    @Volatile private var generation = 0L
    private val transferLock = Any()
    private var client: ByparrClient? = null
    private var resolution: Job? = null
    private var progress: Job? = null
    private var validation: Job? = null
    private var stagingUri: Uri? = null
    private val visited = mutableSetOf<String>()
    private var publication: ApkPublication? = null
    private var lookup: OriginalDownloadLookup? = null
    private var tryPlay = true

    private val completion = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (downloads.id >= 0 && intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloads.id &&
                _state.value is State.Transfer) checkCompletion(generation)
        }
    }

    init {
        ContextCompat.registerReceiver(app, completion,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    fun begin(url: String, packageName: String?, version: String?, buildCodes: Set<Int>? = null) {
        val target = if (packageName != null && version != null) OriginalDownloadTarget(packageName, version, buildCodes) else null
        if (target != null && expectedTarget == target && _state.value != State.Idle) {
            // The caller's redirect may finish while Play is running. Keep it for an eventual
            // shop handoff without replacing or replaying the owned Play acquisition.
            requestUrl = url
            return
        }
        if (requestUrl == url && expectedTarget == target && _state.value != State.Idle) return
        cancel()
        requestUrl = url
        expectedTarget = target
        lookup = target?.let(::OriginalDownloadLookup)
        visited.clear()
        tryPlay = true
        resolveOriginal(url)
    }

    fun retry() {
        if (_state.value !is State.Failure) return
        val url = requestUrl ?: return
        val target = expectedTarget
        val play = tryPlay
        cancel()
        requestUrl = url
        expectedTarget = target
        lookup = target?.let(::OriginalDownloadLookup)
        visited.clear()
        tryPlay = play
        resolveOriginal(url)
    }

    fun tryOtherShops() {
        if ((_state.value as? State.Failure)?.canTryShops != true) return
        val url = requestUrl ?: return
        tryPlay = false
        resolve(url)
    }

    private fun resolveOriginal(url: String) {
        val target = expectedTarget
        if (!tryPlay || target == null) { resolve(url); return }
        val operation = generation
        publish(State.Resolving("Google Play · Checking requested version…"))
        resolution = viewModelScope.launch {
            val feedback = Channel<GooglePlayOriginals.Progress>(Channel.CONFLATED)
            val display = launch {
                for (value in feedback) if (operation == generation) publish(if (value.transferring)
                    State.Transfer(value.bytes, value.total, false, "Google Play", value.step)
                else State.Resolving(value.step), record = false)
            }
            // Existing startup-cleared ephemeral domain; the provider handoff owns a separate copy.
            val workspace = File(app.getDir("ephemeral", Context.MODE_PRIVATE), "play-original-${UUID.randomUUID()}")
            try {
                val file = withContext(Dispatchers.IO) {
                    GooglePlayOriginals.create(app).use { play ->
                        play.acquire(target, workspace) {
                            recordText(operation, it.step, pageUrl = it.pageUrl)
                            feedback.trySend(it)
                        }
                    }
                }
                display.cancelAndJoin()
                ensureActive()
                publish(State.Resolving("Google Play · Checking APK package, version and archive…"))
                val ready = withContext(Dispatchers.IO) {
                    val format = ArchiveFormat.detect(file)
                    val identity = ArchiveIdentity.read(app, file, format)
                    if (!target.accepts(identity)) throw IOException("Google Play APK does not match the requested package, version and build.")
                    val name = "native-original-play-${UUID.randomUUID()}.${format.extension}"
                    val uri = InstallerFileProvider.buildUri(app, name)
                    var published = false
                    try {
                        InstallerFileProvider.getUriForFile(app, file, name)
                        synchronized(transferLock) {
                            if (operation != generation) throw CancellationException("Google Play acquisition replaced")
                            stagingUri = uri
                            published = true
                        }
                    } finally { if (!published) InstallerFileProvider.discardNativeOriginal(app, uri) }
                    uri to identity
                }
                ensureActive()
                if (operation == generation) publish(State.Ready(ready.first, ready.second))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (missing: PlayOriginalUnavailable) {
                display.cancelAndJoin()
                if (operation == generation) {
                    activity.record(operation, "play-unavailable", "Google Play",
                        if (missing.versionMismatch) "Requested version unavailable" else "Requested original unavailable",
                        LogLevel.WARN, missing.message.takeUnless { missing.versionMismatch }, pageUrl = missing.pageUrl)
                    tryPlay = false; resolve(requestUrl ?: url)
                }
            } catch (error: Exception) {
                display.cancelAndJoin()
                if (operation == generation) publish(State.Failure("Google Play · ${error.message ?: "The original APK could not be acquired."}", canTryShops = true))
            } finally {
                withContext(NonCancellable) {
                    display.cancelAndJoin()
                    feedback.close()
                    withContext(Dispatchers.IO) { workspace.deleteRecursively() }
                }
            }
        }
    }

    fun choose(page: ByparrClient.Page, choice: RemotePage.Choice) {
        if ((_state.value as? State.Choices)?.page !== page) return
        resolve(choice.url, page.takeIf { it.content.isAttachment(choice.url) })
    }

    fun chooseStore(choices: State.StoreChoices, choice: RemotePage.Choice) {
        if (_state.value !== choices || choice !in choices.choices) return
        resolve(choice.url)
    }

    private fun resolve(url: String, attachmentPage: ByparrClient.Page? = null) {
        if (!visited.add(url)) {
            publish(State.Failure("The download site returned a page cycle. Use Retry or Open in Browser."))
            return
        }
        val operation = generation
        val activeClient = ByparrClient(false)
        val activeLookup = lookup
        client = activeClient
        publish(State.Resolving("Resolving package…"), record = false)
        resolution = viewModelScope.launch {
            try {
                val endpoint = prefs.nativeByparrEndpoint.get()
                if (ByparrEndpoint.normalize(endpoint, false) == null) {
                    if (operation == generation) publish(State.Manual, record = false)
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    if (attachmentPage != null) OriginalDownloadLookup.Result.Page(attachmentPage)
                    else if (activeLookup != null) activeLookup.fetch(activeClient, endpoint, url, Build.SUPPORTED_ABIS.toList()) { feedback ->
                        val key = "lookup:${feedback.stepId}"
                        if (generation == operation && client === activeClient && activeLookup.isCurrent(feedback)) {
                            if (feedback.queue == null) activity.record(operation, key, feedback.source.label,
                                feedback.step, feedback.level, feedback.detail, feedback.pageUrl)
                            else activity.update(operation, key, feedback.queueStatus?.compactMessage ?: feedback.queue)
                        }
                        viewModelScope.launch {
                            if (generation == operation && client === activeClient && activeLookup.isCurrent(feedback))
                                publish(State.Resolving(feedback.message), record = false)
                        }
                    }
                    else {
                        val shop = DownloadPolicy(false).source(url)?.label
                        recordText(operation, "${shop?.let { "$it · " }.orEmpty()}Requesting download link…")
                        OriginalDownloadLookup.Result.Page(activeClient.fetch(endpoint, url, expectedTarget?.version, Build.SUPPORTED_ABIS.toList(),
                            onStep = { recordText(operation, "${shop?.let { "$it · " }.orEmpty()}$it") },
                            onResolvedPage = { recordText(operation, "${shop?.let { "$it · " }.orEmpty()}Requesting download link…", pageUrl = it) }))
                    }
                }
                ensureActive()
                if (operation != generation) return@launch
                if (result is OriginalDownloadLookup.Result.Choices) {
                    client = null
                    publish(State.StoreChoices(result.source, result.choices))
                    return@launch
                }
                val page = (result as OriginalDownloadLookup.Result.Page).page
                if (publication?.let { DownloadPolicy(false).source(it.pageUrl) != page.content.source } == true) publication = null
                if (ApkPublication.isBuildPage(page.content.url)) publication = page.content.publication
                publish(State.Resolving("${page.content.source.label} · Checking download choices…"))
                val next = if (attachmentPage != null) url else page.content.next
                if (next != null && page.content.isAttachment(next)) {
                    publish(State.Resolving("${page.content.source.label} · Requesting APK transfer…"))
                    val attachment = withContext(Dispatchers.IO) { activeClient.attachment(page, next) }
                    ensureActive()
                    if (operation != generation) return@launch
                    withContext(Dispatchers.IO) {
                        synchronized(transferLock) {
                            if (operation != generation) throw CancellationException("Download cancelled before enqueue")
                            downloads.enqueue(attachment.url, page.userAgent, attachment.disposition, attachment.mime,
                                attachment.referer ?: page.content.url, attachment.cookieHeader,
                                attachment.sendReferer, attachment.accept, publication)
                        }
                    }
                    if (operation != generation) return@launch
                    client = null
                    publish(State.Transfer(0, -1, false, page.content.source.label))
                    monitorTransfer(operation)
                } else if (next != null) {
                    resolve(next)
                } else if (page.content.choices.isNotEmpty()) {
                    client = null
                    publish(State.Choices(page))
                } else throw IOException("${page.content.source.label} returned no usable download choices.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == operation) {
                    client = null
                    publish(State.Failure(activeLookup?.currentSource?.label?.let { "$it · " }.orEmpty() +
                        (error.message ?: "The original APK could not be downloaded.")))
                }
            }
        }
    }

    private fun monitorTransfer(operation: Long) {
        // Check once after enqueue in case Android completed before the broadcast saw the new ID.
        checkCompletion(operation)
        progress = viewModelScope.launch {
            while (isActive && operation == generation && _state.value is State.Transfer) {
                val reading = withContext(Dispatchers.IO) { downloads.query() }
                if (operation != generation) return@launch
                if (reading != null && _state.value is State.Transfer) {
                    if (reading.status == DownloadManager.STATUS_FAILED || reading.status == DownloadManager.STATUS_SUCCESSFUL)
                        checkCompletion(operation)
                    else publish(State.Transfer(reading.downloaded, reading.total,
                        reading.status == DownloadManager.STATUS_PAUSED, (_state.value as? State.Transfer)?.shop))
                }
                // Display sampling only; completion uses Android's authoritative status/event.
                delay(500)
            }
        }
    }

    private fun checkCompletion(operation: Long) {
        if (validation?.isActive == true || operation != generation) return
        val downloadId = downloads.id
        validation = viewModelScope.launch {
            try {
                val completed = withContext(Dispatchers.IO) { downloads.query(downloadId) }
                    ?: throw IOException("Android no longer has the download record. Use Retry.")
                if (operation != generation) return@launch
                when (completed.status) {
                    DownloadManager.STATUS_FAILED -> throw IOException("Android download failed (${completed.reason}). Use Retry or Open in Browser.")
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        publish(State.Resolving("${(_state.value as? State.Transfer)?.shop?.let { "$it · " }.orEmpty()}Checking APK package, version and archive…"))
                        val ready = withContext(Dispatchers.IO) {
                            downloads.validate(downloadId)
                            val snapshot = synchronized(transferLock) {
                                if (operation != generation || downloads.id != downloadId) throw CancellationException("Download replaced")
                                if (!downloads.ready) throw IOException(downloads.error ?: "The archive could not be validated.")
                                if (expectedTarget?.accepts(downloads.identity) == false)
                                    throw IOException("The downloaded APK does not match the requested package and version. Choose another APK or version.")
                                downloads.identity?.let { ApkPublications(app).remember(it, downloads.publication) }
                                Triple(downloads.file ?: throw IOException("The original APK is missing."),
                                    downloads.format, downloads.identity)
                            }
                            val uri = InstallerFileProvider.getUriForFile(app, snapshot.first,
                                "native-original-$downloadId.${snapshot.second.extension}")
                            synchronized(transferLock) {
                                if (operation != generation) {
                                    InstallerFileProvider.discardNativeOriginal(app, uri)
                                    throw CancellationException("Download cancelled during handoff")
                                }
                                stagingUri = uri
                                downloads.discardHandedOff(downloadId)
                            }
                            uri to snapshot.third
                        }
                        ensureActive()
                        if (operation == generation) publish(State.Ready(ready.first, ready.second))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (operation == generation) {
                    downloads.fail(error.message ?: "Download failed.")
                    publish(State.Failure(error.message ?: "Download failed."))
                }
            }
        }
    }

    fun takeReady(): Uri? {
        val ready = _state.value as? State.Ready ?: return null
        synchronized(transferLock) { stagingUri = null } // The normal importer owns this copy now.
        publish(State.HandedOff)
        return ready.uri
    }

    fun cancel() {
        synchronized(transferLock) {
            generation++
            activity.reset(generation)
            downloads.cancel()
            stagingUri?.let { InstallerFileProvider.discardNativeOriginal(app, it) }
            stagingUri = null
        }
        client?.cancel()
        client = null
        resolution?.cancel()
        progress?.cancel()
        validation?.cancel()
        requestUrl = null
        expectedTarget = null
        publication = null
        lookup = null
        _state.value = State.Idle
    }

    private fun recordText(owner: Long, text: String, level: LogLevel = LogLevel.INFO, pageUrl: String? = null) {
        val shop = (listOf("Google Play") + DownloadSource.entries.map { it.label })
            .firstOrNull { text.startsWith("$it · ") } ?: activity.entries.value.lastOrNull()?.shop
        var message = text.substringBefore('\n')
        if (shop != null) while (message.startsWith("$shop · ")) message = message.removePrefix("$shop · ")
        activity.record(owner, "$shop:$message", shop, message, level,
            text.substringAfter('\n', "").takeIf { it.isNotBlank() },
            pageUrl ?: lookup?.takeIf { it.currentSource?.label == shop }?.currentPageUrl)
    }

    private fun publish(value: State, record: Boolean = true) {
        if (record) when (value) {
            is State.Resolving -> recordText(generation, value.message)
            is State.Transfer -> recordText(generation, value.step ?: (value.shop?.let { "$it · " }.orEmpty() +
                if (value.paused) "Download paused" else "Downloading APK…"))
            is State.Choices -> recordText(generation, "${value.page.content.source.label} · Choose an APK variant")
            is State.StoreChoices -> recordText(generation, "${value.source.label} · Choose a matching store result")
            is State.Failure -> recordText(generation, value.message, LogLevel.ERROR)
            is State.Ready -> recordText(generation, "APK validated")
            State.HandedOff -> recordText(generation, "Opening APK…")
            State.Manual, State.Idle -> Unit
        }
        _state.value = value
    }

    override fun onCleared() {
        cancel()
        app.unregisterReceiver(completion)
    }
}
