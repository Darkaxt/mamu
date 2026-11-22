package app.morphe.manager.domain.batch

import kotlinx.serialization.json.Json
import kotlin.test.*

class SavedBatchQueueTest {
    private fun item(target: BatchTarget, state: BatchItemState = BatchItemState.READY) =
        BatchPatchItem(target, "App", null, mapOf(7 to setOf("Mod")), emptyMap(), bundles = emptyList(), state = state,
            forceVersionMismatch = true, knownFailureRetries = setOf("exact-old-release"))

    @Test fun `prepared queue round trips intent and clone identity without runtime permissions`() {
        val state = BatchRunState(listOf(item(BatchTarget("app", "clone")).copy(useMount = true),
            item(BatchTarget("app"), BatchItemState.EXCLUDED)), BatchPhase.PREFLIGHT,
            BatchInstallPolicy.INSTALL_AFTER)
        val saved = state.savedQueue!!
        val text = Json.encodeToString(saved)
        assertEquals(saved, Json.decodeFromString<SavedBatchQueue>(text))
        assertEquals(listOf("clone", "app"), saved.targets.map { it.id })
        assertEquals(setOf("app"), saved.excluded)
        assertEquals(1, saved.queuedCount)
        assertEquals(mapOf("clone" to true, "app" to false), saved.resolvedMountChoices)
        assertFalse(text.contains("exact-old-release"))
        assertFalse(text.contains("forceVersionMismatch"))
        assertFalse(text.contains("Mod"))
    }

    @Test fun `legacy mount intent migrates without losing clone-specific choices`() {
        val saved = Json.decodeFromString<SavedBatchQueue>("""{"targets":[{"packageName":"app","repatchedPackageName":"clone"},{"packageName":"app"}],"useMount":true}""")
        assertEquals(mapOf("clone" to true, "app" to true), saved.resolvedMountChoices)
        assertEquals(mapOf("clone" to false, "app" to true), saved.copy(mountChoices = mapOf("clone" to false)).resolvedMountChoices)
    }

    @Test fun `starting or finishing never saves an executable queue`() {
        val state = BatchRunState(listOf(item(BatchTarget("app"))), BatchPhase.PLANNING,
            BatchInstallPolicy.SAVE_ONLY)
        assertNotNull(state.savedQueue)
        assertNotNull(state.copy(phase = BatchPhase.PREFLIGHT).savedQueue)
        assertNull(state.copy(phase = BatchPhase.RUNNING).savedQueue)
        assertNull(state.copy(phase = BatchPhase.FINISHED).savedQueue)
    }
}
