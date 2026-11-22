package app.morphe.manager.patcher

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallInputLifetimeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `leaving patcher cannot clean an install or its pending persistence input`() = runBlocking {
        val directory = newPatchWorkspace(temp.root)
        val input = directory.resolve("input.apk").also { it.writeText("original") }
        val workerUse = PatchInputUse().also { it.begin(); it.finish() }
        val installUse = PatchInputUse().also { it.begin() }
        var receiverReleased = false
        val lifetime = InstallInputLifetime { receiverReleased = true }
        lifetime.retain { installUse::finish }
        val installFinished = lifetime.operation()
        lifetime.externalPending(true)
        lifetime.close()
        val cleanup = launch {
            clearPatchWorkspaceAfterWorker(directory, input, null) {
                workerUse.awaitReleased(); installUse.awaitReleased()
            }
        }
        yield()
        installFinished()
        assertTrue(input.exists())
        assertFalse(receiverReleased)
        // A genuine package completion launches persistence before releasing the external wait.
        val persistenceFinished = lifetime.operation()
        lifetime.success()
        yield()
        assertTrue(input.exists())
        assertFalse(receiverReleased)
        persistenceFinished()
        cleanup.join()
        assertFalse(directory.exists())
        assertTrue(receiverReleased)
    }

    @Test fun `cancelled before launch releases exactly once while a retry prompt keeps its inputs`() {
        var released = 0
        val lifetime = InstallInputLifetime()
        lifetime.retain { { released++ } }
        val cancelled = lifetime.operation()
        cancelled(); cancelled()
        assertEquals(0, released)
        lifetime.close()
        assertEquals(1, released)
        lifetime.close()
        assertEquals(1, released)
    }

}
