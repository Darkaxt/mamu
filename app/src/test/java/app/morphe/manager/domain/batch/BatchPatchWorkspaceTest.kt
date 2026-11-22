package app.morphe.manager.domain.batch

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchPatchWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `retiring queue waits for import and parser release while a new queue uses distinct paths`() = runBlocking {
        val workspaces = BatchPatchWorkspace(temp.root)
        val (file, copy) = workspaces.reserveImport("apkm")
        file.parentFile.mkdirs(); file.writeText("partial")
        val read = workspaces.useImport(file)!!
        val cleanup = workspaces.retire()
        assertNull(workspaces.useImport(file)) // a late copy cannot attach to a newer plan
        val (next, nextCopy) = workspaces.reserveImport("apk")
        next.parentFile.mkdirs(); next.writeText("new input"); nextCopy.finish()
        val deletion = launch(Dispatchers.Default) { cleanup() }
        yield()
        assertTrue(file.exists())
        copy.finish()
        yield()
        assertTrue(file.exists())
        read.finish(); deletion.join()
        assertFalse(file.exists())
        assertTrue(next.exists())
        workspaces.retire().invoke()
        assertFalse(next.exists())
    }
    @Test fun `retry keeps exact attached original until queue abandonment without deleting user file`() = runBlocking {
        val userFile = temp.newFile("user.apk").also { it.writeText("original user bytes") }
        val workspaces = BatchPatchWorkspace(temp.newFolder("private"))
        val (copy, use) = workspaces.reserveImport("apk")
        copy.parentFile.mkdirs(); userFile.copyTo(copy); use.finish()
        val item = BatchPatchItem(BatchTarget("app"), "App", BatchApkSource.UserFile(copy, "2", 20),
            emptyMap(), emptyMap(), bundles = emptyList(), state = BatchItemState.FAILED)
        assertEquals(copy, item.retryOriginalFile())
        assertEquals("original user bytes", item.retryOriginalFile()!!.readText())
        workspaces.retire().invoke()
        assertNull(item.retryOriginalFile())
        assertTrue(userFile.exists())
    }
}
