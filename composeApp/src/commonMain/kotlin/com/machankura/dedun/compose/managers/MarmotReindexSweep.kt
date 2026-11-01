package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import com.machankura.dedun.compose.database.model.types.MarmotReindexReport

/**
 * Reads a backlog of group events again, repeatedly, until repeating stops helping.
 *
 * The whole reason a replay is worth anything is that the events depend on each
 * other: a commit nobody could apply is what a room's later messages were waiting
 * on, and applying it makes them readable. So one pass in order is not enough --
 * a pass that recovers something has changed what the next pass can do.
 *
 * Ordering is the caller's business; this only decides how many times to go round.
 * A pass that recovers nothing ends it, because everything left is waiting on
 * something no later pass will produce either.
 *
 * Kept apart from the database so the stopping rule can be read, and tested, on
 * its own -- it is the part with something to get wrong.
 */
object MarmotReindexSweep {
    private const val TAG = "MarmotReindexSweep"

    private val logger = Logger.withTag(TAG)

    /**
     * How many times a sweep will go round.
     *
     * A pass that recovers nothing already ends the sweep, so this only bounds the
     * pathological shape where every pass recovers exactly one event and the sweep
     * turns quadratic. A chain of five events each waiting on the last is already
     * far past what relay reordering produces.
     */
    const val DEFAULT_MAX_PASSES = 5

    /**
     * @param stored how many group events the room holds in total, for the report.
     * @param predatingMembership how many of [stored] the caller held back because
     *  they were published before this device joined, for the report. Carried
     *  through rather than worked out here for the same reason [stored] is: the
     *  sweep decides how many times to go round, not what is worth going round
     *  for.
     * @param unresolved those with nothing to show for them, in the order to replay
     *  them. Anything already read must be left out: a replay is only ever allowed
     *  to touch events it cannot make worse.
     * @param replay reads one event again. Its failures are expected -- a message
     *  whose key is gone throws every time -- so they are logged, not raised.
     * @param isUnresolved asked again after [replay] to see whether it achieved
     *  anything. This, rather than [replay]'s return, is what counts a recovery:
     *  an event is recovered when the room has something to show for it, which is
     *  a fact about the database and not about the call that just ran.
     */
    suspend fun <T> run(
        stored: Int,
        predatingMembership: Int = 0,
        unresolved: List<T>,
        maxPasses: Int = DEFAULT_MAX_PASSES,
        replay: suspend (T) -> Unit,
        isUnresolved: suspend (T) -> Boolean,
    ): MarmotReindexReport {
        var remaining = unresolved
        var recovered = 0
        var pass = 0

        while (remaining.isNotEmpty() && pass < maxPasses) {
            pass++
            val stillUnresolved = mutableListOf<T>()

            for (candidate in remaining) {
                try {
                    replay(candidate)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.w("Replay of $candidate failed", e)
                }

                if (isUnresolved(candidate)) {
                    stillUnresolved.add(candidate)
                } else {
                    recovered++
                }
            }

            val madeProgress = stillUnresolved.size < remaining.size
            remaining = stillUnresolved

            if (!madeProgress) break
        }

        return MarmotReindexReport(
            stored = stored,
            predatingMembership = predatingMembership,
            unresolved = unresolved.size,
            recovered = recovered,
            failed = remaining.size,
        ).also {
            logger.i("Swept ${unresolved.size} unresolved event(s) in $pass pass(es): $it")
        }
    }
}
