package app.morphe.manager.patcher

import java.util.concurrent.atomic.AtomicBoolean

/** Holds the patcher's input lease until real install/persistence consumers have released it. */
internal class InstallInputLifetime(private val onClosed: () -> Unit = {}) {
    private var releaseInputs: (() -> Unit)? = null
    private var operations = 0
    private var externalPending = false
    private var closed = false
    private var succeeded = false
    private var notified = false

    @Synchronized fun retain(acquire: (() -> (() -> Unit))?) {
        check(!closed || (operations > 0 && acquire == null)) { "The install owner has closed" }
        succeeded = false
        if (releaseInputs == null) releaseInputs = acquire?.invoke()
    }

    /** Registered before launch, released from Job.invokeOnCompletion, including pre-start cancellation. */
    @Synchronized fun operation(): () -> Unit {
        operations++
        val released = AtomicBoolean()
        return { if (released.compareAndSet(false, true)) endOperation() }
    }

    @Synchronized private fun endOperation() { operations--; settle() }
    @Synchronized fun externalPending(value: Boolean) { externalPending = value; settle() }
    @Synchronized fun success() { succeeded = true; externalPending = false; settle() }
    @Synchronized fun close() { closed = true; settle() }

    private fun settle() {
        if (operations != 0 || externalPending) return
        if (closed || succeeded) { releaseInputs?.invoke(); releaseInputs = null }
        if (closed && !notified) { notified = true; onClosed() }
    }
}
