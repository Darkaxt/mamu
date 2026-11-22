package app.morphe.manager.downloaders

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.*
import app.morphe.manager.MainActivity
import app.morphe.manager.R
import app.morphe.manager.domain.apk.LocalApkSources
import app.morphe.manager.domain.bundles.AppVersionCatalog
import app.morphe.manager.domain.manager.DownloadUrlResolver
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.OriginalApkRepository
import app.morphe.manager.patcher.split.SplitApkPreparer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.IOException

/** One pinned request. Failure/cancel are row outcomes, not failures of its queue successors. */
class OriginalDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val queue: OriginalDownloadQueue by inject()
    private val catalog: AppVersionCatalog by inject()
    private val originals: OriginalApkRepository by inject()
    private val local: LocalApkSources by inject()
    private val resolver: DownloadUrlResolver by inject()
    private val prefs: PreferencesManager by inject()
    private val downloads = Downloads(context, "original-download-$id")
    private val session = context.getSharedPreferences("original-session-$id", Context.MODE_PRIVATE)
    private val client = ByparrClient(false)
    private val target get() = OriginalDownloadTarget(checkNotNull(inputData.getString("package")),
        checkNotNull(inputData.getString("version")), inputData.getIntArray("buildCodes")?.toSet())
    private val cancelled get() = id.toString() in queue.cancelled.value
    private var url: String? = session.getString("url", null)
    @Volatile private var shop: String? = session.getString("shop", null)
    private val lookup by lazy { OriginalDownloadLookup(target) }
    private fun message(step: String) = shop?.let { "$it · $step" } ?: step

    private fun notification(message: String) : Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.queue_downloads), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${target.packageName} · ${target.version}").setContentText(message)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override suspend fun getForegroundInfo() = ForegroundInfo(NOTIFICATION, notification(applicationContext.getString(R.string.queue_waiting)),
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)

    private fun data(phase: String, message: String = "", bytes: Long = 0, total: Long = -1) =
        workDataOf("resultPhase" to phase, "resultMessage" to message.take(2000), "resultUrl" to url,
            "bytes" to bytes, "total" to total)
    private suspend fun update(phase: String, message: String = "", bytes: Long = 0, total: Long = -1) {
        check(session.edit().putString("phase", phase).putString("shop", shop).commit())
        setProgress(data(phase, message, bytes, total))
    }
    private suspend fun support() = catalog.compatibleVersions.first()[target.packageName].orEmpty()
    private suspend fun currentSupport() = support().also {
        if (!target.stillSupported(it, Build.VERSION.SDK_INT)) throw IOException("This version is no longer supported by the visible patch sources.")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workspace = File(applicationContext.filesDir, "original-download-work/$id")
        try {
            if (cancelled) { downloads.discardOwned(); return@withContext Result.success(data("cancelled")) }
            setForeground(getForegroundInfo())
            coroutineScope {
                // Disconnect HTTP on OS stop as well as user cancellation, even during blocking IO.
                val disconnect = launch { try { awaitCancellation() } finally { client.cancel() } }
                val operation = async {
                    val visible = currentSupport()
                    val cached = local.saved(target.packageName)
                    val cachedIdentity = cached?.let { ArchiveIdentity(target.packageName, target.packageName, it.version, it.versionCode ?: -1) }
                    var playOriginal: File? = null
                    when (originalResume(session.getString("phase", null), cancelled, downloads.id >= 0,
                        target.acceptedByCurrent(cachedIdentity, visible, Build.VERSION.SDK_INT))) {
                        OriginalResume.CANCEL -> throw CancellationException("Cancelled")
                        OriginalResume.READY -> {
                            downloads.discardOwned()
                            return@async data("ready", applicationContext.getString(R.string.queue_ready))
                        }
                        OriginalResume.INTERRUPTED -> throw IOException("Acquisition was interrupted. Retry explicitly to start another request.")
                        OriginalResume.RESOLVE -> {
                            shop = "Google Play"
                            update("resolving", message("Checking the requested original APK…"))
                            try {
                                playOriginal = resolvePlay(File(workspace, "play"))
                            } catch (missing: PlayOriginalUnavailable) { resolve() }
                        }
                        OriginalResume.TRANSFER -> Unit
                    }
                    if (playOriginal == null) awaitTransfer()
                    update("checking", message("Checking the APK package, version and archive…"))
                    if (playOriginal == null) {
                        downloads.validate()
                        if (!downloads.ready) throw IOException(downloads.error ?: "The archive could not be validated.")
                    }
                    val source = playOriginal ?: downloads.file ?: throw IOException("The downloaded file is missing.")
                    val identity = if (playOriginal == null) downloads.identity else
                        ArchiveIdentity.read(applicationContext, source, ArchiveFormat.detect(source))
                    if (!target.acceptedByCurrent(identity, currentSupport(), Build.VERSION.SDK_INT))
                        throw IOException("The downloaded package, version or build does not match the patch-supported target.")
                    workspace.mkdirs()
                    val prepared = SplitApkPreparer.prepareIfNeeded(source, workspace,
                        app.morphe.manager.patcher.runtime.currentDeviceConfig(),
                        object : app.morphe.manager.patcher.logger.Logger() {
                            override fun log(level: app.morphe.manager.patcher.logger.LogLevel, message: String) {
                                android.util.Log.d("OriginalDownload", message)
                            }
                        })
                    try {
                        val actual = ArchiveIdentity.read(applicationContext, prepared.file, ArchiveFormat.APK)
                        if (!target.acceptedByCurrent(actual, currentSupport(), Build.VERSION.SDK_INT))
                            throw IOException("The prepared APK does not match the supported target.")
                        actual?.let { ApkPublications(applicationContext).remember(it, downloads.publication) }
                        ensureActive()
                        if (cancelled) throw CancellationException("Cancelled")
                        update("retaining", applicationContext.getString(R.string.queue_saving))
                        val saved = originals.saveOriginalApk(target.packageName, target.version, prepared.file, forceRetention = true)
                            ?: throw IOException("The validated original could not be saved.")
                        val result = local.saved(target.packageName)
                        if (result?.filePath != saved.absolutePath || result.version != target.version)
                            throw IOException("The saved original could not be read for later patching.")
                        downloads.discardOwned()
                        data("ready", applicationContext.getString(R.string.queue_ready))
                    } finally { prepared.cleanup() }
                }
                val cancelWatch = launch {
                    queue.cancelled.first { id.toString() in it }
                    client.cancel()
                    operation.cancel()
                }
                try { Result.success(operation.await()) }
                finally { cancelWatch.cancelAndJoin(); disconnect.cancelAndJoin() }
            }
        } catch (error: CancellationException) {
            if (cancelled) {
                withContext(NonCancellable) { downloads.discardOwned() }
                Result.success(data("cancelled", applicationContext.getString(R.string.queue_cancelled)))
            } else throw error // WorkManager resumes a known Android transfer, never an uncertain browser action.
        } catch (error: Exception) {
            downloads.discardOwned()
            shop = lookup.currentSource?.label ?: shop
            Result.success(data("failed", message(error.message ?: "Download failed.")))
        } finally { if (workspace.exists()) workspace.deleteRecursively() }
    }

    private suspend fun resolvePlay(workspace: File): File = coroutineScope {
        val feedback = Channel<GooglePlayOriginals.Progress>(Channel.CONFLATED)
        val display = launch {
            for (progress in feedback) {
                update(if (progress.transferring) "transfer" else "resolving", progress.step, progress.bytes, progress.total)
                applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(progress.step))
            }
        }
        try {
            GooglePlayOriginals.create(applicationContext).use { play ->
                play.acquire(target, workspace) { feedback.trySend(it) }
            }
        } finally { display.cancelAndJoin(); feedback.close() }
    }

    private suspend fun resolve() = coroutineScope {
        val feedback = Channel<OriginalDownloadLookup.Progress>(Channel.CONFLATED)
        val display = launch { for (progress in feedback) {
            if (!lookup.isCurrent(progress)) continue
            shop = progress.source.label
            update("resolving", progress.message)
            applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(progress.message))
        } }
        try {
            update("resolving", "Finding download sites for ${target.version}…")
            url = resolver.resolve(target.packageName, target.version)
            check(session.edit().putString("url", url).commit())
            val endpoint = prefs.nativeByparrEndpoint.get()
            if (ByparrEndpoint.normalize(endpoint, false) == null) throw IOException("Google Play cannot supply this version. Open the app to download it in your browser, or set a Byparr server in Settings > Downloader.")
            val visited = mutableSetOf<String>()
            var nextUrl = checkNotNull(url)
            var previous: ByparrClient.Page? = null
            var publication: ApkPublication? = null
            while (visited.add(nextUrl)) {
                currentCoroutineContext().ensureActive()
                val current = nextUrl
                val result = previous?.takeIf { it.content.isAttachment(current) }?.let { OriginalDownloadLookup.Result.Page(it) }
                    ?: lookup.fetch(client, endpoint, current, Build.SUPPORTED_ABIS.toList()) {
                    // Feedback is display only; it never resubmits the browser request.
                    feedback.trySend(it)
                }
                if (result is OriginalDownloadLookup.Result.Choices) {
                    nextUrl = backgroundDownloadChoice(result.choices, Build.SUPPORTED_ABIS.toList())?.url
                        ?: throw IOException("${result.source.label} has several matching results. Open in Browser to choose the requested build.")
                    continue
                }
                val page = (result as OriginalDownloadLookup.Result.Page).page
                shop = page.content.source.label
                if (publication?.let { DownloadPolicy(false).source(it.pageUrl) != page.content.source } == true) publication = null
                if (ApkPublication.isBuildPage(page.content.url)) publication = page.content.publication
                if (page.content.isAttachment(current)) {
                    val attachment = client.attachment(page, current)
                    currentSupport()
                    currentCoroutineContext().ensureActive()
                    if (cancelled) throw CancellationException("Cancelled before enqueue")
                    downloads.enqueue(attachment.url, page.userAgent, attachment.disposition, attachment.mime,
                        attachment.referer ?: page.content.url, attachment.cookieHeader, attachment.sendReferer, attachment.accept, publication)
                    display.cancelAndJoin()
                    update("transfer", message("Downloading the original APK…"))
                    return@coroutineScope
                }
                nextUrl = page.content.next ?: backgroundDownloadChoice(page.content.choices, Build.SUPPORTED_ABIS.toList())?.url
                    ?: throw IOException("The download site needs a variant choice. Open in Browser to choose the supported build.")
                previous = page
            }
            throw IOException("The download site returned a page cycle.")
        } finally { display.cancelAndJoin(); feedback.close() }
    }

    private suspend fun awaitTransfer() = coroutineScope {
        val events = Channel<Unit>(Channel.CONFLATED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloads.id) events.trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(applicationContext, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        val sampler = launch { while (isActive) { events.send(Unit); delay(500) } } // Display sampling, no completion deadline.
        try {
            for (ignored in events) {
                val state = downloads.query() ?: throw IOException("Android no longer has this transfer. Retry explicitly.")
                when (state.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> return@coroutineScope
                    DownloadManager.STATUS_FAILED -> throw IOException("Android download failed (${state.reason}).")
                    else -> update("transfer", message(if (state.status == DownloadManager.STATUS_PAUSED) "Paused by Android" else "Downloading the original APK…"),
                        state.downloaded, state.total)
                }
            }
        } finally { sampler.cancel(); applicationContext.unregisterReceiver(receiver); events.close() }
    }
    companion object { private const val CHANNEL = "original-downloads"; private const val NOTIFICATION = 701 }
}
