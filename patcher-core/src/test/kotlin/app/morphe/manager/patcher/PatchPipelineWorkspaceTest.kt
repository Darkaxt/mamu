package app.morphe.manager.patcher

import app.morphe.manager.patcher.logger.LogLevel
import app.morphe.manager.patcher.logger.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PatchPipelineWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun patch_loading_uses_the_run_scratch_before_any_patch_code_executes() = runBlocking {
        val old = System.getProperty("java.io.tmpdir")
        val scratch = temp.newFolder("owned-run")
        val request = PatchRequest("example.app", "unused.apk", "unused-output.apk", "unused-merged.apk",
            scratch.path, scratch.resolve("workspace").path,
            listOf(PatchRequest.Bundle(42, "unused.jar", setOf("Patch"), emptyMap())),
            false, DeviceConfig(listOf("arm64-v8a"), listOf("en-US"), 420))
        val logger = object : Logger() { override fun log(level: LogLevel, message: String) {} }
        try {
            val failure = assertFailsWith<IOException> {
                PatchPipeline(request, logger, object : PatchingListener {
                    override suspend fun stepCompleted() {}
                    override suspend fun splitPreparation(event: app.morphe.manager.patcher.split.SplitPreparationEvent) {}
                    override suspend fun patchesStarted() {}
                    override suspend fun patchSucceeded(name: String) {}
                    override suspend fun patchFailed(name: String?, stackTrace: String) {}
                    override suspend fun patchesFinished(count: Int) {}
                }) {
                    assertEquals(scratch.path, System.getProperty("java.io.tmpdir"))
                    throw IOException("stop before patch execution")
                }.run()
            }
            assertEquals("stop before patch execution", failure.message)
        } finally {
            if (old == null) System.clearProperty("java.io.tmpdir") else System.setProperty("java.io.tmpdir", old)
        }
    }
}
