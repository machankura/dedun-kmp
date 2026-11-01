package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two shapes an application payload takes on this app's wire, and what reads
 * which.
 *
 * Every build before docs/marmot-v2.md Phase 2 sent a NIP-59 rumor, which is the
 * canonical Marmot app event plus a `"sig":""` member. The strict decoder refuses
 * that member; `Event.fromJsonOrNull` accepts either shape, because it defaults a
 * missing signature rather than checking anything. The inbound path decodes
 * strictly first and falls back second, and the id is the same either way --
 * NIP-01 never hashed the signature -- which is what lets rows filed under the old
 * shape line up with payloads arriving in the new one.
 */
class MarmotAppPayloadTest {
    private val author = "a".repeat(64)

    private fun rumor(): Event = RumorAssembler.assembleRumor(
        pubKey = author,
        ev = EventTemplate(createdAt = 1_700_000_000L, kind = 9, tags = arrayOf(arrayOf("t", "x")), content = "hello \"there\"\n"),
    )

    @Test
    fun `the legacy rumor and its canonical form share an id`() {
        val rumor = rumor()
        val canonical = MarmotAppEvent.fromEvent(rumor)

        assertEquals(rumor.id, canonical.id)
        assertEquals(rumor.id, MarmotAppEvent.decode(canonical.toJson()).id)
    }

    @Test
    fun `the strict decoder refuses a payload carrying a sig member`() {
        val legacyJson = rumor().toJson()

        assertTrue(legacyJson.contains("\"sig\""), "the legacy shape has lost its sig member")
        assertNull(MarmotAppEvent.decodeOrNull(legacyJson))
    }

    @Test
    fun `the fallback reads both shapes to the same author`() {
        val rumor = rumor()
        val canonicalJson = MarmotAppEvent.fromEvent(rumor).toJson()

        assertEquals(author, assertNotNull(Event.fromJsonOrNull(rumor.toJson())).pubKey)
        assertEquals(author, assertNotNull(Event.fromJsonOrNull(canonicalJson)).pubKey)
        assertEquals(rumor.id, Event.fromJsonOrNull(canonicalJson)?.id)
    }

    @Test
    fun `the strict decoder refuses an id that does not rehash`() {
        val canonical = MarmotAppEvent.fromEvent(rumor())
        val forged = MarmotAppEvent(
            id = "0".repeat(64),
            pubKey = canonical.pubKey,
            createdAt = canonical.createdAt,
            kind = canonical.kind,
            tags = canonical.tags,
            content = canonical.content,
        )

        assertNull(MarmotAppEvent.decodeOrNull(forged.toJson()))
    }
}
