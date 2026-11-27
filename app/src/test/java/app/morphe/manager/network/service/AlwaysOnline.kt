package app.morphe.manager.network.service

import kotlin.time.Duration

/** A network that is always there, for tests that build an [HttpService] without a device. */
object AlwaysOnline : ConnectionWatcher {
    override fun isOnline() = true
    override suspend fun awaitOnline(timeout: Duration) = true
}
