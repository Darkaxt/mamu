/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.patcher.runtime

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.ForegroundInfo
import app.morphe.manager.BuildConfig
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.patcher.PatchingListener
import app.morphe.manager.patcher.host.HostCommand
import app.morphe.manager.patcher.host.HostEvent
import app.morphe.manager.patcher.logger.LogLevel
import app.morphe.manager.patcher.logger.Logger
import app.morphe.manager.patcher.runtime.jvm.JvmInstallation
import app.morphe.manager.patcher.runtime.jvm.JvmLaunch
import app.morphe.manager.util.AppCoroutineScope
import app.morphe.manager.util.bytesToMebibytes
import app.morphe.manager.util.tag
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Where a run happens, both in a process of its own, see [PatcherService]. */
enum class PatcherVm {
    /** The JVM the app embeds, whose heap is bound by what the device spares. */
    JVM,

    /** The runtime of Android, within the heap it gives the app. */
    ART
}

/**
 * Owns one isolated process for each patch run. A new run waits for the previous process to
 * release its inputs, rather than sharing a preloaded process or a time-based lease.
 *
 * A large run can take more memory than the device has to spare, and the system then ends the
 * process that holds it, or the heap of the run fills up wherever it happens to allocate. Kept
 * apart from the app, either ends the run alone, and the app lives on to say so.
 */
class PatcherProcesses(
    private val app: Application,
    private val fs: Filesystem,
    private val installation: JvmInstallation,
    private val prefs: PreferencesManager
) {
    private val runLock = Mutex()

    /** The heap a JVM is started with: a third of the RAM within bounds, or half of it in a 32-bit process. */
    private val jvmHeapLimitMb by lazy {
        val memoryInfo = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        val ramMb = bytesToMebibytes(memoryInfo.totalMem).toInt()
        if (Process.is64Bit()) {
            (ramMb / 3).coerceIn(HEAP_LIMIT_MINIMUM_MB, HEAP_LIMIT_MAXIMUM_MB)
        } else {
            (ramMb / 2).coerceIn(HEAP_LIMIT_32_BIT_MINIMUM_MB, HEAP_LIMIT_32_BIT_MAXIMUM_MB)
        }
    }

    /** The heap a JVM that found no room for [jvmHeapLimitMb] is started with again. */
    private val jvmMinimumHeapLimitMb
        get() = if (Process.is64Bit()) HEAP_LIMIT_MINIMUM_MB else HEAP_LIMIT_32_BIT_MINIMUM_MB

    /** The heap a run on [vm] gets. ART gives the process of the run what it gives the app's. */
    fun heapLimitMb(vm: PatcherVm) = when (vm) {
        PatcherVm.JVM -> jvmHeapLimitMb
        PatcherVm.ART -> heapLimitMebibytes()
    }

    /** The JVM where the user left it on and the device can run it, ART otherwise. */
    suspend fun preferredVm() =
        if (installation.isAvailable && prefs.useJvmRuntime.get()) PatcherVm.JVM else PatcherVm.ART

    /** Each run owns a fresh process until its binder confirms death, including cancellation. */
    suspend fun run(
        requestFile: File,
        vm: PatcherVm,
        foreground: ForegroundInfo,
        logger: Logger,
        listener: PatchingListener
    ) = withContext(Dispatchers.IO) {
        runLock.withLock { start(vm, requireNotNull(requestFile.parentFile)).use { it.run(requestFile, foreground, logger, listener) } }
    }

    private suspend fun start(vm: PatcherVm, scratch: File): PatcherProcess {
        val tuning = if (vm == PatcherVm.JVM) jvmTuning() else emptyList()
        return try {
            try {
                startAndAwait(vm, tuning, scratch, jvmHeapLimitMb)
            } catch (e: HostNotStartedException) {
                // The address space of a 32-bit process may have no room for the heap in one piece.
                // The smallest one still patches large apps, which ART, the next resort, does not
                if (vm != PatcherVm.JVM || !e.heapNotReserved || jvmHeapLimitMb <= jvmMinimumHeapLimitMb) throw e
                Log.w(tag, "No room for a ${jvmHeapLimitMb}MB heap, starting the patcher JVM with ${jvmMinimumHeapLimitMb}MB")
                startAndAwait(vm, tuning, scratch, jvmMinimumHeapLimitMb)
            }
        } catch (e: Exception) {
            // What the run falls back to ART on, which a canceled start is not
            throw if (vm == PatcherVm.JVM && e !is CancellationException && (e is IllegalStateException || e is RemoteException)) {
                JvmUnavailableException(e.message ?: "The patcher JVM did not start", e)
            } else e
        }
    }

    /** Starts a host on [vm], with a heap of [heapLimitMb] in the JVM, and waits for it to run. */
    private suspend fun startAndAwait(vm: PatcherVm, tuning: List<String>, scratch: File, heapLimitMb: Int): PatcherProcess {
        val process = startHost(vm, tuning, scratch, heapLimitMb)
        try {
            process.awaitStarted()
            return process
        } catch (e: Exception) {
            // Waits for the binder to die, so a retry never binds to the process on its way out
            withContext(NonCancellable) { process.close() }
            throw e
        }
    }

    private suspend fun startHost(vm: PatcherVm, tuning: List<String>, scratch: File, heapLimitMb: Int): PatcherProcess {
        val launch = if (vm == PatcherVm.JVM) jvmLaunch(tuning, scratch, heapLimitMb) else null
        val connection = PatcherConnection.connect(app)
        val (commands, commandsSink) = ParcelFileDescriptor.createPipe()
        val (events, eventsSink) = ParcelFileDescriptor.createPipe()
        val started = try {
            // Ends the process gets copies of, closed here so that its end closes the pipes
            commands.use {
                eventsSink.use {
                    withContext(Dispatchers.IO) {
                        if (launch != null) connection.service.startJvm(launch, commands, eventsSink)
                        else connection.service.startArt(commands, eventsSink)
                    }
                }
            }
        } catch (e: Exception) {
            commandsSink.close()
            events.close()
            connection.close()
            connection.awaitTermination()
            throw e
        }
        return PatcherProcess(vm, connection, started, commandsSink, events, launch, tuning.orEmpty())
    }

    private suspend fun jvmLaunch(tuning: List<String>, scratch: File, heapLimitMb: Int): JvmLaunch {
        val installed = installation.install()
        return JvmLaunch(
            command = installed.command,
            options = jvmOptions(installed, scratch) + tuning,
            mainClass = HOST_MAIN_CLASS,
            heapLimitMb = heapLimitMb
        )
    }

    private fun jvmOptions(installed: JvmInstallation.Installed, scratch: File) = listOf(
        "-Djava.class.path=${installed.hostJar}",
        // The JVM falls back to /tmp, which an app cannot write to
        "-Djava.io.tmpdir=$scratch",
        // Patches that ship a JNI library load it from an unnamed module
        "--enable-native-access=ALL-UNNAMED",
        // Nothing reads the performance counters, which would otherwise be kept in a file of their own
        "-XX:-UsePerfData",
        // The working directory of an app is not writable, which is where the report would go
        "-XX:ErrorFile=${errorFile("%p")}"
    )

    /**
     * The options that tune the JVM to the process, and last those of a debug build, so a debug
     * build can override any flag by giving it again. Every process runs the Liberica JDK 25 on
     * glibc, as a process of its own, see embeddedJvms of the app.
     */
    private suspend fun jvmTuning() = (if (Process.is64Bit()) listOf(
        // C1 only. A run is too short for C2 to pay off, and its compiles take cores the run needs
        "-XX:TieredStopAtLevel=1",
        // 8 byte object headers instead of 12. A run keeps millions of small objects alive, and
        // smaller ones leave the heap and the collector less to do
        "-XX:+UseCompactObjectHeaders"
    ) else listOf(
        // The simplest collector, with no threads of its own, on a port that runs few others. C2
        // stays on, as the runs that take long on 32-bit phones gain the most from it
        "-XX:+UseSerialGC"
    )) + debugOptions()

    /** Where the JVM in process [pid] reports a crash, %p standing for the pid of whichever does. */
    private fun errorFile(pid: Any) = fs.tempDir.resolve("patcher-jvm-error-$pid.log")

    /**
     * Options a debug build adds to the JVM, read from [DEBUG_OPTIONS_FILE] in the app's files: one
     * per line, # starts a comment. Lets flags be tried and runs be profiled without rebuilding,
     * see patcher-core/README.md.
     */
    private suspend fun debugOptions(): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        val file = app.filesDir.resolve(DEBUG_OPTIONS_FILE)
        return withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            file.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }

    /**
     * A host that ended before it ran or did not start in time, see [PatcherProcess.awaitStarted].
     *
     * @param heapNotReserved Whether the JVM found no room for its heap in the address space.
     */
    private class HostNotStartedException(message: String, val heapNotReserved: Boolean) : IllegalStateException(message)

    /**
     * A started host waiting for its run, which takes [HostCommand]s through [commands] and reports
     * on [events]. Closing it ends its process.
     *
     * @param launch How its JVM was started, or null on ART.
     * @param tuning The options of [launch] that differ between devices and builds, for the run to log.
     */
    private inner class PatcherProcess(
        val vm: PatcherVm,
        private val connection: PatcherConnection,
        private val started: HostStarted,
        commands: ParcelFileDescriptor,
        events: ParcelFileDescriptor,
        private val launch: JvmLaunch?,
        private val tuning: List<String>
    ) : Closeable {
        private val startedAt = System.currentTimeMillis()

        private val writer = ParcelFileDescriptor.AutoCloseOutputStream(commands).bufferedWriter()

        // Read from the start, as patches loaded ahead of the run may already print
        private val events = readEvents(events)

        // How the process of the JVM ended, as its service reports it
        private var jvmEnded: HostEvent.JvmEnded? = null

        val isAlive get() = connection.service.asBinder().isBinderAlive

        /**
         * Waits for the host to report that it runs. A JVM that could not start, for an option, a
         * library or a heap the device has no room for, ends before that, which the service of the
         * process reports with what the JVM wrote.
         *
         * @throws HostNotStartedException If the process ended first or did not start in time.
         */
        suspend fun awaitStarted() {
            val logged = mutableListOf<String>()
            val started = withTimeoutOrNull(HOST_START_TIMEOUT) {
                for (event in events) when (event) {
                    HostEvent.Started -> return@withTimeoutOrNull true
                    is HostEvent.JvmEnded -> jvmEnded = event
                    is HostEvent.Log -> logged += event.message
                    // Nothing else comes before the host runs
                    else -> Unit
                }
                false
            }
            if (started == true) return

            val cause = when {
                started == null -> "did not start within $HOST_START_TIMEOUT"
                else -> jvmEnded?.let { "ended before it started, its process ${it.describe()}" } ?: "ended before it started"
            }
            val details = logged + jvmEnded?.output.orEmpty()
            throw HostNotStartedException(
                "The patcher $vm $cause" + if (details.isNotEmpty()) ":\n${details.joinToString("\n")}" else "",
                heapNotReserved = details.any { HEAP_NOT_RESERVED in it }
            )
        }

        @Synchronized
        fun send(command: HostCommand) {
            writer.write(HostCommand.encode(command))
            writer.newLine()
            writer.flush()
        }

        suspend fun run(requestFile: File, foreground: ForegroundInfo, logger: Logger, listener: PatchingListener) {
            logDescription(logger)
            holdForeground(foreground)
            try {
                send(HostCommand.Run(requestFile.path))
            } catch (_: IOException) {
                // The process is gone, which the events end on as well
            }

            val finished = relay(logger, listener)
            if (finished == null) {
                // The system records the end of the service, which outlives a JVM that crashed or was killed
                val end = jvmEnded
                val killed = end?.let { PatcherKilledException("the JVM ${it.describe()}", it.crashed) } ?: killedException()
                val errorFile = errorFile(started.jvmPid)
                if (errorFile.exists()) {
                    logger.error("Patcher JVM error report:\n${errorFile.readText()}")
                    errorFile.delete()
                } else if (end != null && end.output.isNotEmpty()) {
                    // The report opens with what a crashing JVM prints, which is all there is without one
                    logger.error("Patcher JVM output:\n${end.output.joinToString("\n")}")
                }
                throw killed
            }

            val failure = finished.failure ?: return
            throw if (failure.outOfMemory) HeapExhaustedException(started.heapLimitMb, failure.stackTrace)
            else PatcherFailureException(failure.stackTrace)
        }

        /**
         * Has the process hold [foreground], the notification the app already shows for the run.
         * Bound by the app alone, it would count as idle on systems that end such a process once
         * the app leaves the screen, as MIUI and HyperOS do.
         */
        private fun holdForeground(foreground: ForegroundInfo) {
            try {
                app.startForegroundService(PatcherService.foregroundIntent(app, foreground))
                heldForeground = true
            } catch (e: IllegalStateException) {
                // Refused to a run started from the background, which goes on bound alone
                Log.w(tag, "Could not hold the patcher process in the foreground", e)
            }
        }

        // Set once the run started its service in the foreground, which only the app stops
        @Volatile private var heldForeground = false

        /**
         * Stops what [holdForeground] started (upstream 3a551b9e2). A service started in the
         * foreground and unbound from a process that already ended stays on record with the
         * system and keeps its notification up, which the app cannot cancel, until the app ends.
         */
        private fun releaseForeground() {
            if (heldForeground) runCatching { app.stopService(Intent(app, PatcherService::class.java)) }
        }

        /**
         * What the run happens in, as the process and the device report it. A run that fails on
         * some devices alone is mostly told apart by these.
         */
        private fun logDescription(logger: Logger) {
            val bits = if (Process.is64Bit()) 64 else 32
            val age = (System.currentTimeMillis() - startedAt) / 1000
            logger.info(
                "Patcher process: pid=${started.pid} vm=$vm heap=${started.heapLimitMb}MB bits=$bits " +
                        "abis=${Build.SUPPORTED_ABIS.joinToString(",")} age=${age}s"
            )
            if (launch != null) logger.info("Patcher JVM: pid=${started.jvmPid} options=${tuning.joinToString(" ")}")
        }

        /**
         * Hands the events of the run over as they arrive, up to its end, or null if the process
         * ended first. A run that ends in the middle of a patch names it, as it reported nothing
         * of that patch yet.
         */
        private suspend fun relay(logger: Logger, listener: PatchingListener) = coroutineScope {
            val runningPatch = RunningPatchWatch(this, logger)
            try {
                for (event in events) when (event) {
                    // Taken by awaitStarted already
                    HostEvent.Started -> Unit
                    is HostEvent.JvmEnded -> jvmEnded = event
                    is HostEvent.PatchesRejected -> listener.patchesRejected(event.rejection)
                    is HostEvent.Log -> logger.log(event.level, event.message)
                    HostEvent.StepCompleted -> listener.stepCompleted()
                    is HostEvent.SplitPreparation -> listener.splitPreparation(event.event)
                    HostEvent.PatchesStarted -> listener.patchesStarted()
                    is HostEvent.PatchExecuting -> runningPatch.started(event.name)
                    is HostEvent.PatchOutcome -> listener.patchOutcome(event.bundleUid, event.patchKey, event.failure)
                    is HostEvent.PatchSucceeded -> listener.patchSucceeded(event.name)
                    is HostEvent.TaskProgress -> listener.taskProgress(event.task, event.done, event.total)
                    is HostEvent.PatchFailed -> {
                        runningPatch.stopped()
                        listener.patchFailed(event.name, event.stackTrace)
                    }
                    is HostEvent.PatchesFinished -> {
                        runningPatch.stopped()
                        listener.patchesFinished(event.count)
                    }
                    is HostEvent.Finished -> {
                        if (event.failure?.outOfMemory == true) {
                            runningPatch.current?.let { logger.error("$it was running when the heap ran out") }
                        }
                        return@coroutineScope event
                    }
                }
                runningPatch.current?.let { logger.error("$it was running when the patcher process ended") }
                null
            } finally {
                runningPatch.stopped()
            }
        }

        /**
         * The end of the process as the system recorded it. Asked a few times, as the record is only
         * written once the system has dealt with the death, which can lag behind the pipe closing.
         */
        private suspend fun killedException(): PatcherKilledException {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return PatcherKilledException(NO_EXIT_RECORD, crashed = false)

            val activityManager = app.getSystemService(ActivityManager::class.java)
            return withTimeoutOrNull(EXIT_RECORD_TIMEOUT) {
                var info: ApplicationExitInfo?
                do {
                    info = activityManager.getHistoricalProcessExitReasons(app.packageName, started.pid, 1).firstOrNull()
                    if (info == null) delay(EXIT_RECORD_POLL)
                } while (info == null)
                val crashed = info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        info.reason == ApplicationExitInfo.REASON_CRASH
                PatcherKilledException(info.describe(), crashed)
            } ?: PatcherKilledException(NO_EXIT_RECORD, crashed = false)
        }

        // The run is over or was given up on, and nothing but the end of its process stops a JVM
        override fun close() {
            // Unbinding asks the service to terminate its own process, never a potentially reused PID.
            runCatching { writer.close() }
            releaseForeground()
            connection.close()
            connection.awaitTermination()
        }
    }

    /** The events written to [source], read on a thread of their own, as a blocking read cannot be canceled. */
    private fun readEvents(source: ParcelFileDescriptor): ReceiveChannel<HostEvent> {
        val channel = Channel<HostEvent>(Channel.UNLIMITED)
        thread(isDaemon = true, name = "PatcherEvents") {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(source).bufferedReader().useLines { lines ->
                    lines.filter(String::isNotBlank).forEach { channel.trySend(decodeEvent(it)) }
                }
                channel.close()
            } catch (e: Exception) {
                channel.close(e)
            }
        }
        return channel
    }

    /**
     * The event of a line, or the line itself as a warning if it holds none. A host that ran out of
     * heap halfway through a send leaves half a line, and the events after it still count.
     */
    private fun decodeEvent(line: String) = try {
        HostEvent.decode(line)
    } catch (_: SerializationException) {
        HostEvent.Log(LogLevel.WARN, "Unreadable event of the patcher process: $line")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun ApplicationExitInfo.describe(): String {
        val cause = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by signal $status"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource usage"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited with $status"
            else -> "ended for reason $reason"
        }
        // Both are reported in kilobytes, and left at 0 for a process killed before they were read
        val memory = "pss=${bytesToMebibytes(pss * 1024)}MB rss=${bytesToMebibytes(rss * 1024)}MB".takeIf { rss > 0 }
        return listOfNotNull(cause, description?.takeIf { it.isNotBlank() }, memory).joinToString(", ")
    }

    /** A binding to [PatcherService] for a single run, which ends the service's process once closed. */
    internal class PatcherConnection private constructor(private val context: Context) : ServiceConnection, Closeable {
        private var continuation: CancellableContinuation<IPatcherService>? = null
        private val terminated = java.util.concurrent.CountDownLatch(1)
        @Volatile private var deathLinked = false
        private var bound = false
        private var closed = false
        fun awaitTermination() { if (deathLinked) terminated.await() }

        lateinit var service: IPatcherService
            private set

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            deathLinked = true
            try { binder.linkToDeath({ terminated.countDown() }, 0) }
            catch (_: RemoteException) { terminated.countDown() }

            continuation?.resume(IPatcherService.Stub.asInterface(binder))
            continuation = null
        }

        // A process that dies is noticed through the events of the run instead
        override fun onServiceDisconnected(name: ComponentName) = Unit

        override fun onNullBinding(name: ComponentName) = fail("bound to nothing")

        override fun onBindingDied(name: ComponentName) = fail("binding died")

        private fun fail(reason: String) {
            continuation?.resumeWithException(IllegalStateException("Patcher service $reason"))
            continuation = null
        }

        override fun close() {
            synchronized(this) {
                if (!closed && bound) {
                    closed = true
                    context.unbindService(this)
                }
            }
            awaitTermination()
        }

        companion object {
            suspend fun connect(context: Context): PatcherConnection {
                val connection = PatcherConnection(context)
                val service = try {
                    suspendCancellableCoroutine<IPatcherService> { continuation ->
                            connection.continuation = continuation
                            connection.bound = context.bindService(
                                Intent(context, PatcherService::class.java),
                                connection,
                                // Keeps the process off the slow background cores
                                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                            )
                            if (!connection.bound) connection.fail("could not be bound")
                    }
                } catch (e: Throwable) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { connection.close() }
                    throw e
                }

                return connection.apply { this.service = service }
            }
        }
    }

    private companion object {
        const val HOST_MAIN_CLASS = "app.morphe.manager.patcher.host.HostMainKt"
        const val DEBUG_OPTIONS_FILE = "jvm-options.txt"
        const val NO_EXIT_RECORD = "no exit record"

        // The patcher keeps close to a gigabyte alive for the largest apps. The JVM only takes what
        // it uses, so the limit is a ceiling that keeps a runaway patch from taking the device
        const val HEAP_LIMIT_MINIMUM_MB = 2048
        const val HEAP_LIMIT_MAXIMUM_MB = 3072

        // TikTok with Hushfeed peaks at about 950MB on 32-bit ARM. The JVM starts in a process of its
        // own, whose address space still has room for the heap in one piece at the maximum
        const val HEAP_LIMIT_32_BIT_MINIMUM_MB = 1024
        const val HEAP_LIMIT_32_BIT_MAXIMUM_MB = 1536

        // A JVM starts in well under a second on a recent phone, and in a few on a slow one
        val HOST_START_TIMEOUT = 1.minutes

        // What HotSpot reports for a heap it found no room for, such as "Could not reserve enough space for 1433600KB object heap"
        const val HEAP_NOT_RESERVED = "Could not reserve enough space"

        val EXIT_RECORD_TIMEOUT = 2.seconds
        val EXIT_RECORD_POLL = 200.milliseconds

    }
}
