package app.morphe.manager.patcher

import app.morphe.manager.patcher.patch.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PatchRetryTest {
    @Test fun successes_survive_and_each_retry_strictly_removes_attributed_roots() = runBlocking {
        val requests = mutableListOf<Map<Int, Set<String>>>()
        val result = retryFailedPatches(mapOf(1 to setOf("Good", "Broken", "Dependent"), 2 to setOf("Good"))) { selected, number ->
            requests += selected
            if (number == 1) PatchRejection(setOf(PatchKey(1,"Broken"),PatchKey(1,"Dependent")), "Fingerprint missing", "Broken", "stack") else null
        }
        assertEquals(listOf(3,1), requests.map { it.getValue(1).size })
        assertEquals(mapOf(1 to setOf("Good"), 2 to setOf("Good")), result.applied)
        assertEquals(2, result.attempts)
        assertEquals(2, result.omissions.size)
    }
    @Test fun clean_run_is_once_and_all_failed_cannot_claim_success() = runBlocking {
        var calls = 0
        val clean = retryFailedPatches(mapOf(1 to setOf("Good"))) { _, _ -> calls++; null }
        assertEquals(1, clean.attempts); assertEquals(1, calls)
        val failure = assertFailsWith<NoPatchesRemainingException> {
            retryFailedPatches(mapOf(1 to setOf("Bad"))) { _, _ -> PatchRejection(setOf(PatchKey(1,"Bad")),"Missing","Bad","stack") }
        }
        assertEquals("Bad", failure.omissions.single().patch.key)
    }
    @Test fun unknown_io_memory_cancel_and_forged_identity_never_retry() = runBlocking {
        for (error in listOf(java.io.IOException("IO"), OutOfMemoryError("heap"), CancellationException("cancel"), IllegalStateException("unknown"))) {
            var calls=0
            try { retryFailedPatches(mapOf(1 to setOf("Good"))) { _, _ -> calls++; throw error }; fail("Expected failure") }
            catch (actual: Throwable) { assertSame(error, actual) }
            assertEquals(1,calls)
        }
        assertFailsWith<IllegalArgumentException> {
            retryFailedPatches(mapOf(1 to setOf("Good"))) { _, _ -> PatchRejection(setOf(PatchKey(2,"Good")),"wrong UID","Good","stack") }
        }
        Unit
    }
}
