package com.machankura.dedun.compose.nostr

import com.machankura.dedun.compose.database.model.ChatMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether an encrypted group event actually leaves the device.
 *
 * The bug this pins did not throw, log, or fail a build. `MarmotOutboundDao`
 * wrote the broadcast rows inside `chatMessageOrNull?.let { }`, so an event with
 * no chat line pointing at it was MLS-encrypted, wrapped, persisted, its queue
 * row marked processed -- and never sent. FROST signing proposals and the room's
 * key announcement are exactly that: traffic the room generates, which
 * deliberately writes no ChatMessage of its own. Every proposal was silently
 * delivered to nobody.
 *
 * The DAO around this is Room-backed and cannot be stood up here, which is how
 * the gate survived unnoticed in the first place. So the decision is tested
 * where it can be seen, and the DAO does nothing with it but write what it says.
 */
class MarmotDeliveryTest {
    private val groupEventId = "a".repeat(64)
    private val relays = Relays.DefaultDMRelayList

    private val chatLine = ChatMessage(
        id = 42L,
        senderPublicKey = "b".repeat(64),
        isUserMessage = true,
        giftWrapPayloadId = null,
        marmotGroupEventId = null,
        marmotInnerEventId = "c".repeat(64),
        chatRoomId = "room",
        content = "the vote is at six",
    )

    @Test
    fun `a signing proposal goes out though nothing in the chat points at it`() {
        // The regression. A FROST message has no ChatMessage by design -- the manager
        // writes its own transcript lines from what arrives, so a row here would be a
        // second, worse account of the same thing -- and that must not be the reason the
        // group never hears about it.
        val delivery = MarmotDelivery.plan(groupEventId, relays, chatMessage = null)

        assertTrue(delivery.isProtocolTraffic)
        assertEquals(relays.size, delivery.broadcasts.size)
        assertTrue(delivery.broadcasts.isNotEmpty(), "an event on disk and going nowhere")
    }

    @Test
    fun `the send does not depend on the transcript`() {
        // Said as directly as it can be said: the two questions are independent. Anything
        // that makes a broadcast conditional on a chat line fails here.
        val protocol = MarmotDelivery.plan(groupEventId, relays, chatMessage = null)
        val spoken = MarmotDelivery.plan(groupEventId, relays, chatMessage = chatLine)

        assertContentEquals(
            protocol.broadcasts.map { it.relayURL },
            spoken.broadcasts.map { it.relayURL },
        )
        assertEquals(protocol.broadcasts.size, spoken.broadcasts.size)
    }

    @Test
    fun `every relay gets a request naming the event`() {
        val delivery = MarmotDelivery.plan(groupEventId, relays, chatMessage = chatLine)

        assertContentEquals(
            relays.map { it.url },
            delivery.broadcasts.map { it.relayURL },
        )
        assertTrue(delivery.broadcasts.all { it.nostrEventId == groupEventId })
    }

    @Test
    fun `requests are queued pending which is all the broadcaster looks at`() {
        // `observeBroadcastNostrEventRequestsByStatus("pending")` is the only thing that
        // picks these up. A request written in any other state is as unsent as no request.
        val delivery = MarmotDelivery.plan(groupEventId, relays, chatMessage = null)

        assertTrue(delivery.broadcasts.all { it.status == "pending" })
    }

    @Test
    fun `a member's message is linked to its chat line`() {
        // The bookkeeping that legitimately does depend on there being a chat line: the
        // transcript needs to know which event carried the words, so a sent message can
        // be shown as sent.
        val delivery = MarmotDelivery.plan(groupEventId, relays, chatMessage = chatLine)

        assertFalse(delivery.isProtocolTraffic)
        assertEquals(chatLine.id, delivery.transcriptChatMessageId)
    }

    @Test
    fun `no relays is the only way an event stays home`() {
        // Worth pinning as the single legitimate empty case, so an empty broadcast list
        // is always read as "nowhere to send it" and never as "nothing to send".
        val delivery = MarmotDelivery.plan(groupEventId, relays = emptyList(), chatMessage = chatLine)

        assertTrue(delivery.broadcasts.isEmpty())
        assertEquals(chatLine.id, delivery.transcriptChatMessageId)
    }
}
