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

    @Test fun a_selected_patch_the_source_code_lacks_is_rejected_for_the_retry_not_fatal() = runBlocking {
        val scratch = temp.newFolder("missing-run")
        val request = PatchRequest("example.app", "unused.apk", "unused-output.apk", "unused-merged.apk",
            scratch.path, scratch.resolve("workspace").path,
            listOf(PatchRequest.Bundle(42, "unused.jar", setOf("Present", "Gone"), emptyMap())),
            false, DeviceConfig(listOf("arm64-v8a"), listOf("en-US"), 420))
        val logger = object : Logger() { override fun log(level: LogLevel, message: String) {} }
        val rejections = mutableListOf<app.morphe.manager.patcher.patch.PatchRejection>()
        val old = System.getProperty("java.io.tmpdir")
        try {
            val failure = assertFailsWith<MissingSelectedPatchesException> {
                PatchPipeline(request, logger, object : PatchingListener {
                    override suspend fun patchesRejected(rejection: app.morphe.manager.patcher.patch.PatchRejection) { rejections += rejection }
                    override suspend fun stepCompleted() {}
                    override suspend fun splitPreparation(event: app.morphe.manager.patcher.split.SplitPreparationEvent) {}
                    override suspend fun patchesStarted() {}
                    override suspend fun patchSucceeded(name: String) {}
                    override suspend fun patchFailed(name: String?, stackTrace: String) {}
                    override suspend fun patchesFinished(count: Int) {}
                }) { listOf(app.morphe.patcher.patch.bytecodePatch("Present") {}) }.run()
            }
            val rejection = rejections.single()
            // Only the missing patch is omitted, and the host failure confirms exactly this rejection
            assertEquals(setOf(app.morphe.manager.patcher.patch.PatchKey(42, "Gone")), rejection.affected)
            assertEquals(failure.stackTraceToString(), rejection.stackTrace)
            val retried = app.morphe.manager.patcher.patch.retryFailedPatches(mapOf(42 to setOf("Present", "Gone"))) { selection, attempt ->
                if (attempt == 1) rejection else { assertEquals(mapOf(42 to setOf("Present")), selection); null }
            }
            assertEquals(mapOf(42 to setOf("Present")), retried.applied)
        } finally {
            if (old == null) System.clearProperty("java.io.tmpdir") else System.setProperty("java.io.tmpdir", old)
        }
    }
}
