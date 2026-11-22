package app.morphe.manager.patcher.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import app.morphe.manager.patcher.patch.attributablePatchFailure
import java.io.IOException

class PatchOutcomeEventTest {
    @Test fun selected_source_key_and_nested_cause_survive_wire_round_trip() {
        val cause = IllegalStateException("selected parent", IllegalArgumentException("dependency fingerprint missing"))
        val event = HostEvent.PatchOutcome(42, "Patch@2", attributablePatchFailure(cause))
        assertEquals(event, HostEvent.decode(HostEvent.encode(event)))
        assertEquals("selected parent: dependency fingerprint missing", event.failure)
        assertEquals(HostEvent.PatchOutcome(43, "Patch@2", null),
            HostEvent.decode(HostEvent.encode(HostEvent.PatchOutcome(43, "Patch@2", null))))
    }
    @Test fun infrastructure_causes_cannot_be_reported_as_attributable_patch_failure() {
        assertNull(attributablePatchFailure(IllegalStateException("parent", IOException("storage"))))
        assertNull(attributablePatchFailure(IllegalStateException("parent", OutOfMemoryError())))
        assertNull(attributablePatchFailure(kotlinx.coroutines.CancellationException("cancelled")))
    }
}
