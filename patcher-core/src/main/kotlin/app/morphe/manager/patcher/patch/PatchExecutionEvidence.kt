package app.morphe.manager.patcher.patch

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Only execution exceptions identify a patch. Infrastructure errors must never quarantine it. */
fun attributablePatchFailure(error: Throwable): String? {
    val chain = generateSequence(error) { it.cause }.take(32).toList()
    if (chain.any { it is CancellationException || it is IOException || it is Error || it is InterruptedException }) return null
    return chain.map { it.message ?: it.javaClass.simpleName }.distinct().joinToString(": ").take(512)
}

