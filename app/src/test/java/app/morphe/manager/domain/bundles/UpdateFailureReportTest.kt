package app.morphe.manager.domain.bundles

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateFailureReportTest {
    @Test fun a_source_is_reported_only_when_it_starts_failing() {
        val report = updateFailureReport(failed = setOf(1, 2), checked = setOf(3), knownFailing = setOf(2), existing = setOf(1, 2, 3))
        assertEquals(setOf(1), report.reported)
        assertEquals(setOf(1, 2), report.stillFailing)
    }

    @Test fun a_success_clears_the_failure_so_the_next_one_is_reported() {
        val cleared = updateFailureReport(failed = emptySet(), checked = setOf(2), knownFailing = setOf(2), existing = setOf(2))
        assertEquals(emptySet(), cleared.stillFailing)
        val again = updateFailureReport(failed = setOf(2), checked = emptySet(), knownFailing = cleared.stillFailing, existing = setOf(2))
        assertEquals(setOf(2), again.reported)
    }

    @Test fun sources_outside_the_update_keep_their_state_and_deleted_ones_are_forgotten() {
        val report = updateFailureReport(failed = setOf(1), checked = emptySet(), knownFailing = setOf(4, 5), existing = setOf(1, 4))
        assertEquals(setOf(1), report.reported)
        assertEquals(setOf(1, 4), report.stillFailing)
    }
}
