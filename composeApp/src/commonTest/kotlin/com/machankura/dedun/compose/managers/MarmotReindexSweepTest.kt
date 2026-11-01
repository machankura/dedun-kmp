package com.machankura.dedun.compose.managers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stopping rule a reindex sweep runs on.
 *
 * A replay is worth doing at all because the events depend on each other: the
 * commit nobody could apply is what the room's later messages were waiting on.
 * That is also what makes one ordered pass insufficient, and what makes "keep
 * going" dangerous — a room full of events whose keys are gone would be swept
 * forever. Both halves of that are pinned here.
 *
 * The sweep never sees a database. What counts as recovered is whatever the
 * `isUnresolved` probe says afterwards, so these fakes are the real contract and
 * not a stand-in for one.
 */
class MarmotReindexSweepTest {

    /**
     * A backlog where each event is unlocked by the one before it, which is the
     * shape a chain of out-of-order commits arrives in.
     *
     * [unlockedBy] is the event that has to be replayed successfully first;
     * null means it can be read straight away.
     */
    private class Backlog(unlocks: Map<String, String?>) {
        private val unlockedBy = unlocks
        private val resolved = mutableSetOf<String>()

        /** Every event replayed, in order, across every pass. */
        val replayed = mutableListOf<String>()

        suspend fun replay(id: String) {
            replayed.add(id)
            val blocker = unlockedBy.getValue(id)
            if (blocker == null || blocker in resolved) {
                resolved.add(id)
            }
        }

        suspend fun isUnresolved(id: String): Boolean = id !in resolved
    }

    @Test
    fun `a chain of events unlocking each other is read in as many passes as it takes`() = runTest {
        // c waits on b, b waits on a. One pass in this order recovers only a.
        val backlog = Backlog(mapOf("c" to "b", "b" to "a", "a" to null))

        val report = MarmotReindexSweep.run(
            stored = 10,
            unresolved = listOf("c", "b", "a"),
            replay = backlog::replay,
            isUnresolved = backlog::isUnresolved,
        )

        assertEquals(3, report.recovered, "every event in the chain should have been recovered")
        assertEquals(0, report.failed)
        assertEquals(3, report.unresolved, "unresolved is what the sweep started with")
        assertEquals(10, report.stored, "stored is reported as handed in")
    }

    @Test
    fun `an event that can never be read stops the sweep after one pass`() = runTest {
        // Nothing unlocks these: the keys are gone, replaying achieves nothing.
        val backlog = Backlog(mapOf("a" to "never", "b" to "never"))

        val report = MarmotReindexSweep.run(
            stored = 2,
            unresolved = listOf("a", "b"),
            replay = backlog::replay,
            isUnresolved = backlog::isUnresolved,
        )

        assertEquals(listOf("a", "b"), backlog.replayed, "a pass that recovers nothing must not repeat")
        assertEquals(0, report.recovered)
        assertEquals(2, report.failed)
    }

    @Test
    fun `events that can be read are still recovered alongside ones that cannot`() = runTest {
        val backlog = Backlog(mapOf("stuck" to "never", "b" to "a", "a" to null))

        val report = MarmotReindexSweep.run(
            stored = 3,
            unresolved = listOf("stuck", "b", "a"),
            replay = backlog::replay,
            isUnresolved = backlog::isUnresolved,
        )

        assertEquals(2, report.recovered)
        assertEquals(1, report.failed, "the unreadable one is reported, not retried forever")
        assertEquals(report.unresolved, report.recovered + report.failed, "every event is accounted for")
    }

    /**
     * The pass cap only bites on the shape where each pass recovers exactly one
     * event, which is the only way the sweep can go quadratic. Six events in a
     * chain replayed worst-first need six passes and get five.
     */
    @Test
    fun `the pass cap bounds a chain longer than it`() = runTest {
        val chain = listOf("f", "e", "d", "c", "b", "a")
        val backlog = Backlog(
            mapOf("f" to "e", "e" to "d", "d" to "c", "c" to "b", "b" to "a", "a" to null)
        )

        val report = MarmotReindexSweep.run(
            stored = chain.size,
            unresolved = chain,
            replay = backlog::replay,
            isUnresolved = backlog::isUnresolved,
        )

        assertEquals(MarmotReindexSweep.DEFAULT_MAX_PASSES, report.recovered)
        assertEquals(1, report.failed, "what the cap cut short is reported as still unread")
    }

    @Test
    fun `a replay that throws is survived by the rest of the sweep`() = runTest {
        val resolved = mutableSetOf<String>()

        val report = MarmotReindexSweep.run(
            stored = 3,
            unresolved = listOf("throws", "a", "b"),
            replay = { id ->
                if (id == "throws") error("no key for this epoch")
                resolved.add(id)
            },
            isUnresolved = { id -> id !in resolved },
        )

        assertEquals(2, report.recovered, "a throw must not abandon the events behind it")
        assertEquals(1, report.failed)
    }

    @Test
    fun `nothing unresolved does no work at all`() = runTest {
        var replays = 0

        val report = MarmotReindexSweep.run(
            stored = 40,
            unresolved = emptyList<String>(),
            replay = { replays++ },
            isUnresolved = { true },
        )

        assertEquals(0, replays, "a room where nothing went wrong must be left alone")
        assertTrue(report.isNoOp)
        assertEquals(40, report.stored)
    }

    /**
     * The probe, not the replay call, is what counts a recovery: a group event can
     * be replayed without error and still leave the room with nothing to show for
     * it -- a commit rejected as a duplicate does exactly that.
     */
    @Test
    fun `a replay that raises nothing but resolves nothing is not counted as recovered`() = runTest {
        val report = MarmotReindexSweep.run(
            stored = 1,
            unresolved = listOf("duplicateCommit"),
            replay = { /* applies cleanly, writes no line */ },
            isUnresolved = { true },
        )

        assertEquals(0, report.recovered)
        assertEquals(1, report.failed)
    }
}
