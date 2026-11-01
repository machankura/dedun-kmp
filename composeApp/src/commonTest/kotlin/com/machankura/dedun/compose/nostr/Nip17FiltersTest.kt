package com.machankura.dedun.compose.nostr

import com.machankura.dedun.compose.database.query.NostrEventFilterQuery
import com.machankura.dedun.compose.network.serialization.encodeToJsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the only gift wrap filter that can come back with something we can read.
 *
 * Every clause here is one that was got wrong in production. Two call sites asked
 * for `authors = [our pubkey]`, which cannot match a wrap signed by a throwaway
 * key and so returned nothing at all, silently, for as long as it existed. A third
 * asked for `p = [the peer]`, which returned other people's mail and crashed the
 * save that tried to unseal it. Neither failure was visible from reading the
 * filter, so the shape is asserted instead of trusted.
 */
class Nip17FiltersTest {

    private val us = "a".repeat(64)

    @Test
    fun `it asks for wraps addressed to us`() {
        assertEquals(mapOf("p" to listOf(us)), Nip17Filters.inbox(us).tags)
    }

    @Test
    fun `it constrains no authors`() {
        // GiftWrapEvent.create signs with a key it generates and drops, so the author
        // of a wrap is a value nobody can predict -- least of all the sender's own
        // pubkey. Any authors clause here silently matches zero events on every relay.
        assertNull(Nip17Filters.inbox(us).authors)
    }

    @Test
    fun `it carries no since cursor`() {
        // A wrap is stamped up to two days earlier than it was sent, so a high-water
        // mark taken from the newest wrap we hold skips mail that arrives behind it.
        // Anything reintroducing `since` has to back-date by at least two days first.
        assertNull(Nip17Filters.inbox(us).since)
        assertNull(Nip17Filters.inbox(us).until)
    }

    @Test
    fun `it asks for gift wraps and nothing else`() {
        assertEquals(listOf(1059), Nip17Filters.inbox(us).kinds?.toList())
    }

    @Test
    fun `two callers asking for the same inbox make one request`() {
        // computeId hashes the encoded filter, so the chat room list and the chat
        // message screen collapse into a single negentropy request only while both
        // encode identically. Building the filter once is what holds that true; the
        // wire shape is asserted so an added default cannot quietly split them.
        assertEquals(Nip17Filters.inbox(us), Nip17Filters.inbox(us))
        assertEquals(
            """{"kinds":[1059],"tags":{"p":["$us"]},"limit":50}""",
            Nip17Filters.inbox(us).encodeToJsonString(),
        )
    }

    @Test
    fun `the local set it builds is the same set the relay is asked for`() {
        // Negentropy reconciles our local set against the relay's: this filter goes out
        // in NEG-OPEN, and the local side is built by running the same filter through
        // NostrEventFilterQuery. A clause that survives one trip and not the other
        // reports differences that are not real -- events re-downloaded forever, or
        // pushed at a relay that excluded them on purpose. What matters here is that
        // the local query reads the p tag and, like the wire filter, bounds no author:
        // an authors clause would show up as `pubKey IN (?)`.
        val query = NostrEventFilterQuery.build(Nip17Filters.inbox(us))

        assertEquals(
            "SELECT * FROM NostrEvent WHERE kind IN (?) AND (tags LIKE ? ESCAPE '\\') " +
                "ORDER BY createdAt DESC, id DESC",
            query.sql,
        )
    }
}
