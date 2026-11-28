/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.repository

import app.morphe.manager.network.api.ReleaseAPI
import app.morphe.manager.network.dto.AppUpdateCheck
import app.morphe.manager.network.dto.ReleaseAsset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the available Morphe Manager update.
 *
 * The home banner, the update dialog and the background worker resolve the same asset here,
 * so every surface announces and downloads exactly the release the others resolved.
 */
class ManagerUpdateRepository internal constructor(
    private val checkAppUpdate: suspend () -> AppUpdateCheck,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(releaseAPI: ReleaseAPI) : this(releaseAPI::checkAppUpdate)

    private val _availableUpdate = MutableStateFlow<ReleaseAsset?>(null)

    /** The most recently resolved update, or null while none is known to be available. */
    val availableUpdate: StateFlow<ReleaseAsset?> = _availableUpdate.asStateFlow()

    private val _lastCheck = MutableStateFlow<AppUpdateCheck?>(null)

    /**
     * What the last check came back with, or null before the first one. Unlike [availableUpdate]
     * it tells a manager that is up to date from one whose check failed.
     */
    val lastCheck: StateFlow<AppUpdateCheck?> = _lastCheck.asStateFlow()

    // Outlives the caller that started a check, so callers sharing it are not left waiting on
    // a request canceled by whichever of them went away first
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guards the in-flight check so a pull-to-refresh and an opening dialog share one network
    // round trip instead of racing each other
    private val refreshMutex = Mutex()
    private var inFlight: Deferred<ReleaseAsset?>? = null

    @Volatile
    private var lastResolvedAt = 0L

    /**
     * Re-checks for an update and publishes the result.
     *
     * Returns null only for a successful up-to-date check; errors propagate to the caller.
     * Explicit manual checks bypass the short launch-result cache and share in-flight work.
     */
    suspend fun refresh(forceRefresh: Boolean = false): ReleaseAsset? = awaitFetch(forceRefresh)

    /**
     * Resolves the update for a dialog about to offer it. It checks again rather than hand back
     * an update found earlier, which a release published since then would have superseded: the
     * dialog would list the new release as latest and still download the old one. A check that
     * just ran is reused, see [awaitFetch].
     */
    suspend fun getOrRefresh(): ReleaseAsset? = awaitFetch()

    /**
     * Resolves the update without repeating a check that just ran. Opening the manager asks for it
     * from more than one place at once - the home screen and the notification the app was launched
     * from - and every check is a round trip announcing the same release. Callers that arrive while
     * one is in flight await it; those arriving right after it resolved reuse its result.
     */
    private suspend fun awaitFetch(forceRefresh: Boolean = false): ReleaseAsset? {
        val pending = refreshMutex.withLock {
            inFlight?.let { return@withLock it }
            if (!forceRefresh && now() - lastResolvedAt < RESULT_REUSE_WINDOW_MILLIS) {
                return _availableUpdate.value
            }
            scope.async {
                try {
                    fetch()
                } finally {
                    refreshMutex.withLock { inFlight = null }
                }
            }.also { inFlight = it }
        }
        return pending.await()
    }

    private suspend fun fetch(): ReleaseAsset? {
        val check = checkAppUpdate()
        // A check that could not reach the server says nothing new, so the update already known stays
        val update = if (check is AppUpdateCheck.Failed) _availableUpdate.value
            else (check as? AppUpdateCheck.Available)?.asset
        _lastCheck.value = check
        _availableUpdate.value = update
        lastResolvedAt = now()
        return update
    }

    private companion object {
        // Long enough to cover the checks a single app launch fans out, short enough that a
        // pull-to-refresh moments later still reaches the network
        const val RESULT_REUSE_WINDOW_MILLIS = 15_000L
    }
}
