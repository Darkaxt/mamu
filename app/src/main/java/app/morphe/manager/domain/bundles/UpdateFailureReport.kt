package app.morphe.manager.domain.bundles

/**
 * What an update says about the sources that failed in it.
 *
 * A source that was already failing at an earlier update fails quietly: source management still
 * marks it, but the home screen reports a source only when it starts failing. Otherwise every
 * source that stays broken upstream, such as a repository that was deleted, would raise the
 * alert again at each update.
 *
 * @property reported The sources the home screen names, the ones that were not failing before.
 * @property stillFailing The sources to remember as failing until an update of theirs succeeds.
 */
internal data class UpdateFailureReport(val reported: Set<Int>, val stillFailing: Set<Int>)

/**
 * @param failed The sources that failed in this update.
 * @param checked The sources this update reached, whether or not they had anything newer.
 * @param knownFailing The sources failing as of the update before.
 * @param existing Every source there is, so a deleted one is not remembered.
 */
internal fun updateFailureReport(
    failed: Set<Int>,
    checked: Set<Int>,
    knownFailing: Set<Int>,
    existing: Set<Int>
) = UpdateFailureReport(
    reported = failed - knownFailing,
    stillFailing = ((knownFailing - checked) + failed).intersect(existing)
)
