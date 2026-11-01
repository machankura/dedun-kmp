package com.machankura.dedun.compose.nostr.frost

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.machankura.dedun.compose.nostr.frost.tags.FrostSilentMembersTag

/**
 * The tag a resent proposal carries, and what a reader makes of a bad one.
 *
 * It names the members a coordinator has heard nothing from, and a device reads
 * it to decide whether to republish its own messages. So the thing worth
 * testing is not the happy round trip but the failure: a list that named the
 * wrong people would have the wrong device speak, and a tag that is dropped
 * nudges nobody -- which is exactly what a build predating the tag does, and so
 * the safe way to fail.
 */
class FrostSilentMembersTagTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    @Test
    fun `a list of members round trips in order`() {
        val assembled = FrostSilentMembersTag.assemble(listOf(alice, bob))

        val parsed = FrostSilentMembersTag.parse(assembled)

        assertEquals(listOf(alice, bob), parsed?.members)
    }

    @Test
    fun `one member round trips`() {
        val parsed = FrostSilentMembersTag.parse(FrostSilentMembersTag.assemble(listOf(alice)))

        assertEquals(listOf(alice), parsed?.members)
    }

    @Test
    fun `an uppercase key is lowercased because it is compared by string equality`() {
        // userPublicKey on a session is lowercase hex, and `contains` on the
        // parsed list is how a device decides whether it is being asked to
        // speak. A tag that shouted would name nobody.
        val assembled = FrostSilentMembersTag.assemble(listOf(alice.uppercase()))

        assertEquals(listOf(alice), FrostSilentMembersTag.parse(assembled)?.members)
    }

    @Test
    fun `one unreadable entry drops the whole tag`() {
        // All or nothing, like FrostSignerIdsTag. Keeping the readable half
        // would be nudging a subset the coordinator never chose.
        assertNull(FrostSilentMembersTag.parse(arrayOf(FrostSilentMembersTag.TAG_NAME, "$alice,nonsense")))
    }

    @Test
    fun `a key of the wrong length is not a key`() {
        assertNull(FrostSilentMembersTag.parse(arrayOf(FrostSilentMembersTag.TAG_NAME, "ab")))
    }

    @Test
    fun `an empty list is not a tag`() {
        assertNull(FrostSilentMembersTag.parse(arrayOf(FrostSilentMembersTag.TAG_NAME, "")))
    }

    @Test
    fun `a tag of another name is not this one`() {
        assertNull(FrostSilentMembersTag.parse(arrayOf("p", alice)))
    }

    @Test
    fun `a tag with no value is not this one`() {
        assertNull(FrostSilentMembersTag.parse(arrayOf(FrostSilentMembersTag.TAG_NAME)))
    }

    @Test
    fun `assembleTags carries the silent members and parses them back`() {
        val tags = FrostSigningEvents.assembleTags(
            sessionId = "session".padEnd(64, '0'),
            silentMembers = listOf(bob)
        )

        assertEquals(listOf(bob), FrostSigningEvents.parseSilentMembers(tags))
    }

    @Test
    fun `assembleTags writes no tag when nobody is silent`() {
        // An empty list is not a resend that nudges nobody, it is a resend with
        // nothing to say -- and an empty tag would parse to null anyway, so
        // writing one would only put a lie on the wire.
        val tags = FrostSigningEvents.assembleTags(
            sessionId = "session".padEnd(64, '0'),
            silentMembers = emptyList()
        )

        assertTrue(tags.none { it[0] == FrostSilentMembersTag.TAG_NAME })
        assertNull(FrostSigningEvents.parseSilentMembers(tags))
    }

    @Test
    fun `an ordinary proposal's tags name nobody`() {
        val tags = FrostSigningEvents.assembleTags(
            sessionId = "session".padEnd(64, '0'),
            dkgSessionId = "ceremony".padEnd(64, '0')
        )

        assertNull(FrostSigningEvents.parseSilentMembers(tags))
    }
}
