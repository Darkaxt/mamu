package app.morphe.manager.patcher.runtime

import app.morphe.manager.patcher.patch.*
import kotlin.test.*

class RetryFailureBoundaryTest {
    @Test fun only_the_exact_attributed_process_failure_allows_retry() {
        val rejected=PatchRejection(setOf(PatchKey(1,"Broken")),"Fingerprint missing","Broken","actual patch stack")
        assertTrue(PatcherFailureException(rejected.stackTrace).confirms(rejected))
        assertFalse(PatcherFailureException("unknown process failure").confirms(rejected))
        assertFalse(PatcherFailureException("wrapper\nCaused by: "+rejected.stackTrace).confirms(rejected))
        assertFalse(PatcherFailureException(rejected.stackTrace+"\nSuppressed: java.io.IOException: cleanup").confirms(rejected))
        assertFalse(PatcherFailureException(rejected.stackTrace).confirms(null))
    }
}
