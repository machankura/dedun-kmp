package com.machankura.dedun.compose.nostr.chronicle

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.machankura.dedun.compose.nostr.chronicle.tags.ChronicleIdTag
import com.machankura.dedun.compose.nostr.chronicle.tags.ChroniclePageTag
import com.machankura.dedun.compose.nostr.chronicle.tags.ChronicleRecipientTag
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent

/**
 * The envelope a chronicle travels in: what it carries, what it refuses, and the
 * order it puts things in.
 *
 * Nothing here verifies a signature -- that is `GroupKeyStateTest`'s half, and
 * `ChronicleManager`'s at apply time. What these cover is the framing, and the two
 * bounds a page has to hold whoever sent it.
 *
 * The app's own list of kinds is empty, so what a chronicle may carry and the
 * order it applies in are tested against [TEST_KINDS]; the framing and the caps
 * do not care what kind a payload is.
 */
class ChronicleEventTest {
    private val recipient = "d".repeat(64)
    private val chronicleId = "e".repeat(64)

    private fun payload(
        kind: Int,
        content: String = "payload",
        id: String = "a".repeat(63) + (kind % 10),
    ) = Event(
        id = id,
        pubKey = "b".repeat(64),
        createdAt = 1_700_000_000L,
        kind = kind,
        tags = arrayOf(arrayOf("alt", "test")),
        content = content,
        sig = "c".repeat(128)
    )

    // ---- The codec -------------------------------------------------------

    @Test
    fun `what is encoded is what comes back whole`() {
        val events = listOf(
            payload(MetadataEvent.KIND, "{\"name\":\"Ekklesia\"}"),
            payload(TextNoteEvent.KIND, "a post"),
        )

        val decoded = ChronicleEvent.decodePage(ChronicleEvent.encodePage(events))

        assertNotNull(decoded)
        assertEquals(events.map { it.content }, decoded.map { it.content })
        // The point of carrying whole events rather than rebuilt ones: the
        // signature is what the receiver checks, so losing it in transit would
        // leave them nothing to check.
        assertEquals(events.map { it.id }, decoded.map { it.id })
        assertEquals(events.map { it.pubKey }, decoded.map { it.pubKey })
        assertEquals(events.map { it.sig }, decoded.map { it.sig })
    }

    @Test
    fun `a page of one is still an array`() {
        // The contrast with FrostSigningEvents.encodeProposal, which keeps a bare
        // object for a batch of one so that builds predating batching can read
        // it. Both chronicle kinds are new, so there is no such build and no reason
        // to carry the second shape.
        val encoded = ChronicleEvent.encodePage(listOf(payload(TextNoteEvent.KIND)))

        assertTrue(encoded.startsWith("["), "a page is always an array")
        assertTrue(encoded.endsWith("]"))
        assertEquals(1, ChronicleEvent.decodePage(encoded)?.size)
    }

    @Test
    fun `a page that is not an array is not a page`() {
        assertNull(ChronicleEvent.decodePage(payload(TextNoteEvent.KIND).toJson()))
        assertNull(ChronicleEvent.decodePage(""))
        assertNull(ChronicleEvent.decodePage("not json"))
        assertNull(ChronicleEvent.decodePage("[unclosed"))
    }

    @Test
    fun `an empty page is refused rather than read as an empty chronicle`() {
        assertNull(ChronicleEvent.decodePage("[]"))
    }

    @Test
    fun `one unreadable payload refuses the whole page`() {
        // All-or-nothing here, and per-payload at verify time. They answer
        // different questions: a page that will not parse has lost its framing,
        // so a page quietly shortened by one element would report a complete
        // chronicle on its page count while holding less than it says. A payload
        // whose signature does not verify is a well-framed page with one bad
        // event in it, and dropping its honest neighbours would let one forgery
        // deny an entire chronicle.
        val good = payload(TextNoteEvent.KIND)

        assertNull(ChronicleEvent.decodePage("[${good.toJson()},\"not an event\"]"))
        assertNull(ChronicleEvent.decodePage("[\"not an event\",${good.toJson()}]"))
    }

    // ---- The two caps ----------------------------------------------------

    @Test
    fun `a page over the event cap is refused on the way in`() {
        val overCap = (0..ChronicleEvent.MAX_PAGE_EVENTS).map {
            payload(TextNoteEvent.KIND, id = it.toString().padStart(64, '0'))
        }
        assertEquals(ChronicleEvent.MAX_PAGE_EVENTS + 1, overCap.size)

        assertNull(ChronicleEvent.decodePage(ChronicleEvent.encodePage(overCap)))

        // And exactly at the cap is fine, so the bound is not off by one.
        assertNotNull(ChronicleEvent.decodePage(ChronicleEvent.encodePage(overCap.dropLast(1))))
    }

    @Test
    fun `the event cap can actually be reached under the byte cap`() {
        // The cap that cannot fire. An event is over 300 bytes before it says
        // anything -- 64 characters of id, 64 of pubkey, 128 of signature -- so a
        // count cap set above what MAX_PAGE_BYTES can hold is a check that never
        // runs, and the page-over-the-cap test above passes for the wrong reason.
        // Both caps have to be re-sized together; this is what says so.
        val atCap = (0 until ChronicleEvent.MAX_PAGE_EVENTS).map {
            payload(TextNoteEvent.KIND, id = it.toString().padStart(64, '0'))
        }
        val page = ChronicleEvent.encodePage(atCap)

        assertTrue(
            page.encodeToByteArray().size <= ChronicleEvent.MAX_PAGE_BYTES,
            "MAX_PAGE_EVENTS payloads take ${page.encodeToByteArray().size} bytes, " +
                "over the ${ChronicleEvent.MAX_PAGE_BYTES}-byte cap: the event cap can never fire"
        )
        assertEquals(ChronicleEvent.MAX_PAGE_EVENTS, ChronicleEvent.decodePage(page)?.size)
    }

    @Test
    fun `a page over the byte cap is refused on the way in`() {
        // Checked before the JSON is parsed, because the cap exists to bound the
        // work a remote party can ask this device to do. A chronicle is the second
        // place in this protocol where somebody else sets that size.
        val huge = ChronicleEvent.encodePage(
            listOf(payload(TextNoteEvent.KIND, content = "x".repeat(ChronicleEvent.MAX_PAGE_BYTES)))
        )
        assertTrue(huge.encodeToByteArray().size > ChronicleEvent.MAX_PAGE_BYTES)

        assertNull(ChronicleEvent.decodePage(huge))
    }

    @Test
    fun `the byte cap counts bytes not characters`() {
        // A multi-byte payload that fits as characters and does not as bytes. If
        // this were measured in characters the cap would let through up to four
        // times what it says.
        val wide = "世".repeat(ChronicleEvent.MAX_PAGE_BYTES / 2)
        val page = ChronicleEvent.encodePage(listOf(payload(TextNoteEvent.KIND, content = wide)))

        assertTrue(page.length < ChronicleEvent.MAX_PAGE_BYTES)
        assertTrue(page.encodeToByteArray().size > ChronicleEvent.MAX_PAGE_BYTES)
        assertNull(ChronicleEvent.decodePage(page))
    }

    // ---- What a chronicle may carry ---------------------------------------

    @Test
    fun `a kind set admits what it lists and nothing else`() {
        listOf(
            AdvertisedRelayListEvent.KIND,
            MetadataEvent.KIND,
            TextNoteEvent.KIND,
        ).forEach { assertTrue(TEST_KINDS.isChroniclable(it), "kind $it should be chroniclable") }

        assertEquals(3, TEST_KINDS.all.size)
        assertFalse(TEST_KINDS.isEmpty())

        // Group-signed, with an arm in applyInnerEvent, and still refused: being
        // signed is not what earns a kind its place, being on the list is.
        assertFalse(TEST_KINDS.isChroniclable(GroupWalletStatementEvent.KIND))
        assertNull(TEST_KINDS.applyRank(GroupWalletStatementEvent.KIND))

        // And the empty list admits nothing at all.
        assertTrue(ChronicleKinds.NONE.isEmpty())
        assertFalse(ChronicleKinds.NONE.isChroniclable(TextNoteEvent.KIND))
    }

    @Test
    fun `a chronicle may not carry a key state however well signed`() {
        // The attack the allowlist exists for, and the reason it is an allowlist
        // rather than a denylist. A GroupKeyStateEvent is signed by the room and
        // passes verification perfectly, so a chronicle carrying an old one is a
        // validly signed statement about what the room signs with, replayed by
        // whoever kept a copy. Nothing but this stops it.
        assertFalse(ChronicleEvent.isChroniclable(GroupKeyStateEvent.KIND))
        assertFalse(TEST_KINDS.isChroniclable(GroupKeyStateEvent.KIND))
        assertNull(TEST_KINDS.applyRank(GroupKeyStateEvent.KIND))
    }

    @Test
    fun `a chronicle may not carry a birth certificate either`() {
        // The same shape as the key state above: signed by the room, passes
        // verification perfectly, and says something standing about groups rather
        // than carrying the group's work.
        //
        // The argument for letting it through is better than it is for a key
        // state -- "P certified C" is a fixed historical fact, where a key state
        // decides what a live room signs with -- and the cost of leaving it out
        // is real: a member added to a parent after a subgroup was made sees an
        // empty subgroup list. It is still left out here, because admitting it
        // needs an apply-order slot and a decision about whether a room's
        // chronicle may carry an event its own key did not sign, which no
        // chroniclable kind does. See docs/subgroups.md.
        assertFalse(ChronicleEvent.isChroniclable(SubgroupBirthCertificateEvent.KIND))
        assertFalse(TEST_KINDS.isChroniclable(SubgroupBirthCertificateEvent.KIND))
        assertNull(TEST_KINDS.applyRank(SubgroupBirthCertificateEvent.KIND))
    }

    @Test
    fun `a chronicle carries the group's work not envelopes or protocol`() {
        // Against the app's own list, which is the one that will grow: the day a
        // kind is added back, none of these may come with it.
        assertFalse(ChronicleEvent.isChroniclable(ChronicleEvent.KIND))
        assertFalse(ChronicleEvent.isChroniclable(ChronicleRequestEvent.KIND))
        FrostSigningEvents.ALL.forEach {
            assertFalse(ChronicleEvent.isChroniclable(it), "frost kind $it")
        }
    }

    // ---- Dependency order ------------------------------------------------

    @Test
    fun `the order is the list's not the kind numbers'`() {
        fun rank(kind: Int) = assertNotNull(TEST_KINDS.applyRank(kind), "kind $kind has no rank")

        // In the app the list is the database's foreign keys, so a chronicle
        // applied the other way round is a constraint violation rather than a
        // wrong answer. Here it is only the test's own order, which is enough to
        // show the machinery follows the list.
        assertTrue(rank(AdvertisedRelayListEvent.KIND) < rank(MetadataEvent.KIND))
        assertTrue(rank(MetadataEvent.KIND) < rank(TextNoteEvent.KIND))

        // And the numbers really do disagree with the order, which is why this is
        // a list rather than a sortedBy { kind }.
        assertTrue(AdvertisedRelayListEvent.KIND > MetadataEvent.KIND)
        assertTrue(rank(AdvertisedRelayListEvent.KIND) < rank(MetadataEvent.KIND))
    }

    @Test
    fun `sorting a page puts it in the list's order`() {
        val jumbled = listOf(
            payload(TextNoteEvent.KIND),
            payload(MetadataEvent.KIND),
            payload(AdvertisedRelayListEvent.KIND),
        )

        assertEquals(
            listOf(
                AdvertisedRelayListEvent.KIND,
                MetadataEvent.KIND,
                TextNoteEvent.KIND,
            ),
            TEST_KINDS.inApplyOrder(jumbled).map { it.kind }
        )
    }

    @Test
    fun `two of a kind keep the order they were handed in`() {
        // Nothing orders two posts by the group, and keeping the caller's order
        // is one less thing that differs between two members assembling the same
        // chronicle.
        val posts = listOf(
            payload(TextNoteEvent.KIND, "one", id = "1".repeat(64)),
            payload(TextNoteEvent.KIND, "two", id = "2".repeat(64)),
            payload(TextNoteEvent.KIND, "three", id = "3".repeat(64)),
        )

        assertEquals(
            listOf("one", "two", "three"),
            TEST_KINDS.inApplyOrder(posts).map { it.content }
        )
    }

    // ---- The tags --------------------------------------------------------

    @Test
    fun `a page says which chronicle it is where in it and who for`() {
        val template = ChronicleEvent.build(
            payloads = listOf(payload(TextNoteEvent.KIND)),
            chronicleId = chronicleId,
            index = 2,
            count = 5,
            recipient = recipient,
            createdAt = 1_700_000_100L,
            kinds = TEST_KINDS,
        )

        val readBack = ChronicleEvent(
            id = "f".repeat(64),
            pubKey = "b".repeat(64),
            createdAt = template.createdAt,
            tags = template.tags,
            content = template.content,
            sig = ""
        )

        assertEquals(chronicleId, readBack.chronicleId())
        assertEquals(2, readBack.page()?.index)
        assertEquals(5, readBack.page()?.count)
        assertEquals(recipient, readBack.recipient())
        assertEquals(1, readBack.payloads()?.size)
    }

    @Test
    fun `a page outside its own chronicle is not a page`() {
        // Refused rather than clamped: the pair is how a receiver decides it has
        // everything, so a repaired one would let a truncated chronicle read as
        // complete.
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "5", "3")))
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "-1", "3")))
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "0", "0")))
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "0")))
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "one", "3")))

        assertEquals(0, ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "0", "1"))?.index)
        assertTrue(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "0", "1"))!!.isOnlyPage())
    }

    @Test
    fun `a chronicle id and a recipient have to be there to be read`() {
        assertNull(ChronicleIdTag.parse(arrayOf(ChronicleIdTag.TAG_NAME)))
        assertNull(ChronicleIdTag.parse(arrayOf(ChronicleIdTag.TAG_NAME, " ")))
        assertNull(ChronicleIdTag.parse(arrayOf("other", chronicleId)))
        assertEquals(chronicleId, ChronicleIdTag.parse(arrayOf(ChronicleIdTag.TAG_NAME, chronicleId))?.chronicleId)

        assertNull(ChronicleRecipientTag.parse(arrayOf(ChronicleRecipientTag.TAG_NAME, "short")))
        assertEquals(
            recipient,
            ChronicleRecipientTag.parse(arrayOf(ChronicleRecipientTag.TAG_NAME, recipient))?.pubKey
        )
    }

    // ---- What build refuses to make --------------------------------------

    @Test
    fun `a page is not built over either cap or out of what it may not carry`() {
        // The outbound half of both checks. It is politeness rather than
        // security -- the inbound one is the boundary -- but a page nobody can
        // read back is better failed where it was made.
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(emptyList(), chronicleId, 0, 1, recipient, kinds = TEST_KINDS)
        }
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(
                payloads = (0..ChronicleEvent.MAX_PAGE_EVENTS).map {
                    payload(TextNoteEvent.KIND, id = it.toString().padStart(64, '0'))
                },
                chronicleId = chronicleId,
                index = 0,
                count = 1,
                recipient = recipient,
                kinds = TEST_KINDS,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(
                payloads = listOf(payload(GroupKeyStateEvent.KIND)),
                chronicleId = chronicleId,
                index = 0,
                count = 1,
                recipient = recipient,
                kinds = TEST_KINDS,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(
                payloads = listOf(
                    payload(TextNoteEvent.KIND, content = "x".repeat(ChronicleEvent.MAX_PAGE_BYTES))
                ),
                chronicleId = chronicleId,
                index = 0,
                count = 1,
                recipient = recipient,
                kinds = TEST_KINDS,
            )
        }
    }

    @Test
    fun `the app's own list builds no page at all`() {
        // The outbound half of the app carrying nothing: a payload the test list
        // admits is refused under the default, so nothing that assembles a page
        // without naming its kinds can put one on the wire.
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(
                payloads = listOf(payload(TextNoteEvent.KIND)),
                chronicleId = chronicleId,
                index = 0,
                count = 1,
                recipient = recipient,
            )
        }
    }

    @Test
    fun `a request carries nothing but what it is`() {
        val template = ChronicleRequestEvent.build(createdAt = 1_700_000_100L)

        assertEquals(ChronicleRequestEvent.KIND, template.kind)
        assertEquals("", template.content)
        // The room is the envelope and the asker is the MLS sender, so there is
        // nothing left for the event itself to say.
        assertEquals(1, template.tags.size)
        assertEquals("alt", template.tags[0][0])
    }
}
