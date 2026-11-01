package com.machankura.dedun.compose.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

/**
 * The decision behind keeping an MLS group alive between messages.
 *
 * This cache exists because quartz drops a secret tree's skipped-generation keys
 * on save, so rebuilding a group between two messages loses any message that
 * arrives late -- permanently, and silently. See `docs/mls-skipped-keys.md`.
 *
 * Every one of these failures is invisible at runtime. Reuse too eagerly and a
 * group carries on from a ratchet another writer has already moved, which
 * corrupts decryption rather than failing it. Reuse too rarely and the cache
 * does nothing at all, and the bug it was written for comes straight back with
 * no symptom to notice. So the rule is asserted rather than reasoned about.
 */
class LiveInstanceCacheTest {
    /** Stands in for an MlsGroup: mutable, and its persisted form is its content. */
    private class Group(var state: String) {
        /** How many times this particular instance was handed to a caller. */
        var uses: Int = 0
    }

    private fun cache() = LiveInstanceCache<Group> { it.state }

    @Test
    fun `reuses the instance while nothing else has written`() = runBlocking {
        val cache = cache()
        var stored: String? = "start"
        var built = 0

        val first = cache.withInstance(
            key = "room",
            storedState = stored,
            build = { built++; Group("start") },
            save = { stored = it },
            block = { it.uses++; it }
        )

        val second = cache.withInstance(
            key = "room",
            storedState = stored,
            build = { built++; Group("start") },
            save = { stored = it },
            block = { it.uses++; it }
        )

        // The same object, not merely an equal one: what has to survive is the
        // in-memory skipped-key map, which no amount of rebuilding recovers.
        assertSame(first, second)
        assertEquals(1, built)
        assertEquals(2, second?.uses)
    }

    @Test
    fun `rebuilds when something else wrote the stored state`() = runBlocking {
        val cache = cache()
        var stored: String? = "start"
        var built = 0

        val first = cache.withInstance(
            key = "room",
            storedState = stored,
            build = { built++; Group("start") },
            save = { stored = it },
            block = { it }
        )

        // Sending a message advances the sender ratchet and saves; adding a
        // member does too. Carrying on from an instance that has been overtaken
        // would diverge the ratchet, which is worse than not caching at all.
        stored = "written by someone else"

        val second = cache.withInstance(
            key = "room",
            storedState = stored,
            build = { built++; Group("written by someone else") },
            save = { stored = it },
            block = { it }
        )

        assertEquals(2, built)
        assertEquals(false, first === second)
    }

    @Test
    fun `persists whatever the block left behind`() = runBlocking {
        val cache = cache()
        var stored: String? = "start"

        cache.withInstance(
            key = "room",
            storedState = stored,
            build = { Group("start") },
            save = { stored = it },
            block = { it.state = "advanced" }
        )

        assertEquals("advanced", stored)
    }

    @Test
    fun `the state it records is the one it compares against next time`() = runBlocking {
        val cache = cache()
        var stored: String? = "start"
        var built = 0

        repeat(3) {
            cache.withInstance(
                key = "room",
                storedState = stored,
                build = { built++; Group("start") },
                save = { stored = it },
                // Every use moves the instance on, as decrypting a message does.
                block = { group -> group.state = "advanced ${group.uses++}" }
            )
        }

        // Recording the pre-block state instead would make every call look like
        // somebody else had written, quietly turning the cache off.
        assertEquals(1, built)
    }

    @Test
    fun `does not run the block or cache anything when there is nothing to build`() = runBlocking {
        val cache = cache()
        var ran = false

        val result = cache.withInstance<Unit>(
            key = "room",
            storedState = null,
            build = { null },
            save = { },
            block = { ran = true }
        )

        assertNull(result)
        assertEquals(false, ran)
        assertEquals(0, cache.size())
    }

    @Test
    fun `an instance whose use threw is not handed to the next caller`() = runBlocking {
        val cache = cache()
        var stored: String? = "start"
        var built = 0

        assertFailsWith<IllegalStateException> {
            cache.withInstance<Unit>(
                key = "room",
                storedState = stored,
                build = { built++; Group("start") },
                save = { stored = it },
                block = { error("decryption blew up half way") }
            )
        }

        cache.withInstance(
            key = "room",
            storedState = stored,
            build = { built++; Group("start") },
            save = { stored = it },
            block = { it }
        )

        // Half-advanced and never persisted: the next caller has to start from
        // what is actually on disk, not from whatever the failure left in memory.
        assertEquals(2, built)
    }

    @Test
    fun `rooms are cached independently`() = runBlocking {
        val cache = cache()
        var storedA: String? = "a"
        var storedB: String? = "b"

        val a = cache.withInstance(
            key = "roomA",
            storedState = storedA,
            build = { Group("a") },
            save = { storedA = it },
            block = { it }
        )
        val b = cache.withInstance(
            key = "roomB",
            storedState = storedB,
            build = { Group("b") },
            save = { storedB = it },
            block = { it }
        )

        assertEquals(2, cache.size())
        assertEquals(false, a === b)
    }
}
