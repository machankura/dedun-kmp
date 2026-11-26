package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two features ask the group to sign kind 13 seals: the group inbox's replies
 * (docs/group-inbox.md, D10) and messages by threshold ECDH (docs/group-threshold-ecdh.md, D10).
 * Every seal belongs to exactly one refusal, split on whether it names an inbox key, so none is
 * read by both and none is asked blind.
 */
class GroupSealRoutingJvmTest {
    private fun seal(tags: Array<Array<String>>) = Event("a".repeat(64), "b".repeat(64), 1_700_000_000, 13, tags, "x", "")

    @Test
    fun `an inbox reply is the inbox's, and everything else is threshold ECDH's`() {
        val reply = seal(arrayOf(arrayOf("n", "c".repeat(64))))
        val bare = seal(emptyArray())
        val malformed = seal(arrayOf(arrayOf("n", "not a key")))
        val twoKeys = seal(arrayOf(arrayOf("n", "c".repeat(64)), arrayOf("n", "d".repeat(64))))

        assertTrue(InboxRefusal.involves(listOf(reply)))
        assertFalse(GroupDmRefusal.involves(listOf(reply)))

        listOf(bare, malformed, twoKeys).forEach { other ->
            assertTrue(GroupDmRefusal.involves(listOf(other)), "claimed by threshold ECDH: ${other.tags.toList().map { it.toList() }}")
            assertFalse(InboxRefusal.involves(listOf(other)))
        }
    }
}
