package app.morphe.manager.patcher

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TemporaryPatchInputsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `abandonment waits for actual worker exit and cannot delete next screen workspace`() = runBlocking {
        val root = temp.newFolder("ui")
        val first = newPatchWorkspace(root)
        val second = newPatchWorkspace(root)
        val input = first.resolve("input.apk").also { it.writeText("original") }
        val output = first.resolve("output.apk").also { it.writeText("partial") }
        val runtimeScratch = first.resolve("runtime/patcher/extracted/split.apk").also {
            it.parentFile.mkdirs(); it.writeText("owned runtime scratch")
        }
        val nextInput = second.resolve("input.apk").also { it.writeText("next") }
        val workerExit = CompletableDeferred<Unit>()
        val cleanup = launch { clearPatchWorkspaceAfterWorker(first, input, null) { workerExit.await() } }
        yield()
        assertTrue(input.exists())
        assertTrue(output.exists())
        assertTrue(runtimeScratch.exists())
        workerExit.complete(Unit)
        cleanup.join()
        assertFalse(first.exists())
        assertFalse(runtimeScratch.exists())
        assertTrue(nextInput.exists())
    }

    @Test fun `review transfers raw original while clearing failed run staging and protects imports and retained files`() = runBlocking {
        val first = newPatchWorkspace(temp.root)
        val downloaded = temp.newFile("download.apkm").also { it.writeText("validated") }
        val imported = temp.newFile("user.apk")
        val retainedRoot = temp.newFolder("retained")
        val retained = retainedRoot.resolve("original.apk").also { it.writeText("retained") }
        val staging = first.resolve("input.apk").also { it.writeText("run copy") }
        assertFalse(isDisposablePatchInput(imported, first, retainedRoot, false))
        assertFalse(isDisposablePatchInput(retained, first, retainedRoot, true))
        assertTrue(isDisposablePatchInput(staging, first, retainedRoot, false))
        clearPatchWorkspaceAfterWorker(first, staging, null) { }
        assertTrue(downloaded.exists()) // ownership transferred back to editor
        val review = newPatchWorkspace(temp.root)
        clearPatchWorkspaceAfterWorker(review, null, downloaded) { } // abandoning the editor flow
        assertFalse(downloaded.exists())
        assertTrue(imported.exists())
        assertTrue(retained.exists())
    }
    @Test fun `cancellation state does not release a running consumer and queued cancellation prevents start`() = runBlocking {
        val running = PatchInputUse()
        assertTrue(running.begin())
        running.abandonBeforeStart()
        var released = false
        val waiter = launch { running.awaitReleased(); released = true }
        yield()
        assertFalse(released)
        running.finish()
        waiter.join()
        assertTrue(released)
        val queued = PatchInputUse()
        queued.abandonBeforeStart()
        assertFalse(queued.begin())
        queued.awaitReleased()
    }

}
