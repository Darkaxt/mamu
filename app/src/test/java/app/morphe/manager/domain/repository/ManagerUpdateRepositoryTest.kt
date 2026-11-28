package app.morphe.manager.domain.repository

import app.morphe.manager.network.dto.AppUpdateCheck
import app.morphe.manager.network.dto.ReleaseAsset
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagerUpdateRepositoryTest {
    private fun release(version: String) = ReleaseAsset("https://example.invalid/$version.apk",
        LocalDateTime.of(2026, 11, 28, 0, 0), description = "", version = version)

    @Test fun `a release published after the last check replaces the update it found`() = runBlocking {
        var answer: AppUpdateCheck = AppUpdateCheck.Available(release("2.8.0.1"))
        var clock = 0L
        val repository = ManagerUpdateRepository({ answer }, { clock })
        assertEquals("2.8.0.1", repository.refresh(forceRefresh = true)?.version)
        // 2.8.0.2 comes out while the manager stays open, and the update dialog is opened later
        answer = AppUpdateCheck.Available(release("2.8.0.2"))
        clock = 60_000L
        assertEquals("2.8.0.2", repository.getOrRefresh()?.version)
        assertEquals("2.8.0.2", repository.availableUpdate.value?.version)
    }

    @Test fun `opening the dialog checks again once the shared result is no longer fresh`() = runBlocking {
        var checks = 0
        val repository = ManagerUpdateRepository({ checks++; AppUpdateCheck.Available(release("2.8.0.$checks")) }, { 0L })
        repository.refresh(forceRefresh = true)
        // Reused while fresh, so the banner and the dialog it opens agree
        assertEquals("2.8.0.1", repository.getOrRefresh()?.version)
        assertEquals(1, checks)
    }

    @Test fun `a check that cannot reach the server keeps the update already known`() = runBlocking {
        var answer: AppUpdateCheck = AppUpdateCheck.Available(release("2.8.0.2"))
        val repository = ManagerUpdateRepository({ answer })
        repository.refresh(forceRefresh = true)
        answer = AppUpdateCheck.Failed
        assertEquals("2.8.0.2", repository.refresh(forceRefresh = true)?.version)
        answer = AppUpdateCheck.UpToDate
        assertEquals(null, repository.refresh(forceRefresh = true))
    }
}
