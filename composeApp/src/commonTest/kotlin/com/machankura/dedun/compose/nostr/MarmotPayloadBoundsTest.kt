package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotJson
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.chronicle.TEST_KINDS
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The app's largest payloads fit inside what a conformant Marmot decoder will read.
 *
 * `MarmotJson` bounds an app payload at 64 KiB, sixteen levels of nesting and 64
 * elements per collection, and refuses anything over before a parser sees it. The
 * app's own rooms would still read an oversized page through the legacy fallback; an
 * MDK peer drops it without a word. So the caps that decide the app's biggest
 * payloads are asserted here against the decoder itself, at their maximum, so that
 * raising one of them past the bound fails in a test rather than in a group.
 */
class MarmotPayloadBoundsTest {
    private val author = "a".repeat(64)
    private val recipient = "d".repeat(64)
    private val at = 1_700_000_000L

    private fun signedPayload(kind: Int, content: String, id: String) = Event(
        id = id,
        pubKey = "b".repeat(64),
        createdAt = at,
        kind = kind,
        tags = arrayOf(arrayOf("alt", "test"), arrayOf("d", "x".repeat(64))),
        content = content,
        sig = "c".repeat(128),
    )

    /** The app event the outbound path builds for a chronicle page: the rumor, canonical. */
    private fun chronicleAppEvent(payloads: List<Event>): MarmotAppEvent {
        val template = ChronicleEvent.build(
            payloads = payloads,
            chronicleId = "e".repeat(64),
            index = 0,
            count = 1,
            recipient = recipient,
            createdAt = at,
            // The app's own list carries nothing at the moment, so the page is
            // built over the chronicle tests' list: what is under test is the
            // envelope's size, not which kinds fill it.
            kinds = TEST_KINDS,
        )
        // From the template's fields, as the outbound queue assembles every rumor from
        // the row's: the typed template is not what goes through RumorAssembler.
        val rumor = RumorAssembler.assembleRumor<Event>(
            pubKey = author,
            ev = EventTemplate(createdAt = template.createdAt, kind = template.kind, tags = template.tags, content = template.content),
        )
        return MarmotAppEvent.fromEvent(rumor)
    }

    private fun assertConformant(appEvent: MarmotAppEvent) {
        val json = appEvent.toJson()
        assertTrue(
            MarmotJson.withinResourceBounds(json),
            "${json.encodeToByteArray().size} bytes is outside the decoder's bounds",
        )
        assertEquals(appEvent.id, MarmotAppEvent.decode(json).id)
    }

    @Test
    fun `a chronicle page of the most smallest payloads is a conformant app event`() {
        val payloads = (0 until ChronicleEvent.MAX_PAGE_EVENTS).map {
            signedPayload(TextNoteEvent.KIND, content = "d", id = it.toString().padStart(64, '0'))
        }

        assertConformant(chronicleAppEvent(payloads))
    }

    /**
     * The worst case for the byte bound: content that is itself dense JSON, so every
     * quote in the page doubles when the page becomes the app event's `content`.
     */
    @Test
    fun `a chronicle page at the byte cap is a conformant app event after escaping`() {
        val dense = buildString {
            while (length < 4_096) append("{\"k\":\"v\",\"n\":[\"a\",\"b\"]}")
        }
        val payloads = (0 until ChronicleEvent.MAX_PAGE_EVENTS).map {
            signedPayload(TextNoteEvent.KIND, content = dense, id = it.toString().padStart(64, '0'))
        }
        // As many dense payloads as the byte cap admits, measured the way the packer
        // measures them: by the bytes the page occupies once escaped into the app
        // event's content. Measured raw, this page is under the cap and the app
        // event is over the decoder's bound -- which is the bug the measure fixes.
        val page = (payloads.size downTo 1).firstNotNullOf { n ->
            payloads.take(n).takeIf {
                ChronicleEvent.escapedByteSize(ChronicleEvent.encodePage(it)) <= ChronicleEvent.MAX_PAGE_BYTES
            }
        }
        assertTrue(page.size < payloads.size, "the byte cap never bound; the page is not at its maximum")
        assertTrue(
            ChronicleEvent.encodePage(page + payloads[page.size]).encodeToByteArray().size <= ChronicleEvent.MAX_PAGE_BYTES,
            "measured raw, one more payload would still have fit -- the escaped measure is not the binding one here",
        )

        assertConformant(chronicleAppEvent(page))
    }

    @Test
    fun `a direct message envelope is a conformant app event`() {
        val sender = NostrSignerSync(KeyPair())
        val bob = KeyPair()
        val wrap = MarmotDirectMessage.wrap(
            signer = sender,
            recipientPublicKey = bob.pubKey.toHexKey(),
            kind = ChatMessageEvent.KIND,
            createdAt = at,
            tags = arrayOf(PTag.assemble(bob.pubKey.toHexKey(), null)),
            content = "x".repeat(8_192),
        )
        val envelope = MarmotDirectMessage.envelope(sender.pubKey, bob.pubKey.toHexKey(), wrap, at)

        assertConformant(envelope)
        val inside = assertNotNull(MarmotDirectMessage.wrapInside(Event.fromJsonOrNull(envelope.toJson())!!))
        assertEquals(wrap.id, inside.id)
    }
}
