package com.machankura.dedun.compose.nostr.frost

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a proposal's events go on the wire, and what an old build makes of them.
 *
 * The whole reason this encoding has two shapes rather than one is
 * compatibility, and compatibility is exactly the thing no other test in this
 * area can see: a build that predates batching is not here to be run against.
 * So the property is asserted from the other side -- a batch of one is byte for
 * byte the JSON object it always was, which is the only form such a build can
 * read.
 */
class FrostProposalCodecTest {
    private fun event(content: String, kind: Int = 1) = Event(
        id = "a".repeat(64),
        pubKey = "b".repeat(64),
        createdAt = 1_700_000_000L,
        kind = kind,
        tags = arrayOf(arrayOf("alt", "test")),
        content = content,
        sig = ""
    )

    @Test
    fun `a batch of one is the bare event exactly as it always was`() {
        val one = event("just this")

        // The load-bearing assertion. A device on a build that predates batching
        // parses this with Event.fromJsonOrNull; anything but the bare object
        // returns null there and the proposal is dropped, so every single-event
        // session in a mixed-version group would stop working.
        assertEquals(one.toJson(), FrostSigningEvents.encodeProposal(listOf(one)))
    }

    @Test
    fun `a real batch is an array`() {
        val encoded = FrostSigningEvents.encodeProposal(
            listOf(event("first"), event("second"))
        )

        assertTrue(encoded.startsWith("["), "a batch must be a JSON array")
        assertTrue(encoded.endsWith("]"))
    }

    @Test
    fun `what is encoded is what comes back in order`() {
        val events = listOf(event("first"), event("second"), event("third"))

        val decoded = FrostSigningEvents.decodeProposal(
            FrostSigningEvents.encodeProposal(events)
        )

        // Order is the batch's index, and every join of nonces and partial
        // signatures is positional against it.
        assertEquals(
            events.map { it.content },
            decoded?.map { it.content }
        )
    }

    @Test
    fun `a bare event decodes as a batch of one forever`() {
        // Proposals in the old shape do not stop arriving just because this build
        // stopped writing them.
        val decoded = FrostSigningEvents.decodeProposal(event("from an older build").toJson())

        assertEquals(1, decoded?.size)
        assertEquals("from an older build", decoded?.single()?.content)
    }

    @Test
    fun `an array with one unreadable element is refused whole`() {
        // Not shortened. The batch's length is what every later payload is checked
        // against, so a proposal that quietly lost an event would have every
        // signer's contribution rejected for being the wrong size.
        val encoded = """[${event("fine").toJson()},{"not":"an event"}]"""

        assertNull(FrostSigningEvents.decodeProposal(encoded))
    }

    @Test
    fun `an empty array is not a proposal`() {
        assertNull(FrostSigningEvents.decodeProposal("[]"))
    }

    @Test
    fun `nonsense is not a proposal`() {
        assertNull(FrostSigningEvents.decodeProposal("[this is not json"))
        assertNull(FrostSigningEvents.decodeProposal(""))
        assertNull(FrostSigningEvents.decodeProposal("null"))
    }
}
