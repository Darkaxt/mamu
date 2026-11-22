package app.morphe.manager.downloaders

import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import kotlin.test.*

class OriginalDownloadQueueStateTest {
    private fun work(order: Int, state: WorkInfo.State, version: String = "2", phase: String = "transfer",
                     packageName: String = "app", total: Long = -1) = WorkInfo(
        id = UUID.randomUUID(), state = state,
        tags = setOf("original:package:$packageName", "original:version:$version", "original:order:$order", "original:codes:20,21"),
        outputData = workDataOf("resultPhase" to phase, "resultMessage" to "terminal result"),
        progress = workDataOf("resultPhase" to phase, "bytes" to 120L, "total" to total))

    @Test fun `active requests deduplicate the original package across versions and clone rows`() {
        val rows = listOf(work(1,WorkInfo.State.RUNNING,"1"),work(2,WorkInfo.State.BLOCKED,packageName="other"))
        assertTrue(rows.hasActiveOriginal("app"))
        assertTrue(rows.hasActiveOriginal("other"))
        assertFalse(rows.hasActiveOriginal("unrelated"))
        assertFalse(listOf(work(1,WorkInfo.State.SUCCEEDED,phase="failed")).hasActiveOriginal("app"))
    }

    @Test fun `new waiting request supersedes completed row without inheriting its target or result`() {
        val old = work(1,WorkInfo.State.SUCCEEDED,"1",phase="ready")
        val next = work(2,WorkInfo.State.BLOCKED,"2")
        val row = checkNotNull(originalDownloadEntries(listOf(next,old))["app"])
        assertEquals(next.id,row.id)
        assertEquals(OriginalDownloadTarget("app","2",setOf(20,21)),row.target)
        assertEquals("waiting",row.phase)
        assertTrue(row.active)
    }

    @Test fun `failure and cooperative cancel are terminal row outcomes and preserve later work`() {
        for (phase in listOf("failed","cancelled")) {
            val done = work(1,WorkInfo.State.SUCCEEDED,phase=phase)
            val waiting = work(2,WorkInfo.State.BLOCKED,packageName="other")
            val rows = originalDownloadEntries(listOf(done,waiting))
            assertEquals(phase,rows["app"]?.phase)
            assertFalse(checkNotNull(rows["app"]).active)
            assertEquals("terminal result",rows["app"]?.message)
            assertEquals("waiting",rows["other"]?.phase)
            assertTrue(checkNotNull(rows["other"]).active)
        }
    }

    @Test fun `progress preserves actual bytes and unknown size instead of inventing a percentage`() {
        for (total in listOf(-1L,240L)) {
            val row = checkNotNull(originalDownloadEntries(listOf(work(1,WorkInfo.State.RUNNING,total=total)))["app"])
            assertEquals(120L,row.bytes)
            assertEquals(total,row.total)
            assertEquals("transfer",row.phase)
        }
    }
}
