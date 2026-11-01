package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.ChatMessage

/**
 * What has to be written once a queued inner event has been encrypted into a
 * group event: where it is sent, and which transcript row -- if any -- the send
 * belongs to.
 *
 * The two are independent, and this exists to say so. A group event is sent
 * because it was queued; a chat line is linked because a member said something.
 * Letting the second decide the first is what silenced FROST signing: the
 * broadcast rows were written inside `chatMessageOrNull?.let { }`, and protocol
 * traffic has no ChatMessage -- `ChatMessage.applyInnerEvent` returns null for
 * every [com.machankura.dedun.compose.nostr.frost.FrostSigningEvents] kind and for
 * [com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent], and
 * `FrostSigningManager` queues its messages without one. So every signing
 * proposal was MLS-encrypted, wrapped, persisted and marked processed, and then
 * never sent to a relay. Nothing failed; the other participants simply never saw
 * it.
 *
 * Pulled out of `MarmotOutboundDao.encryptAndSendMarmotInnerEvent` because that
 * function is Room-backed and cannot be unit tested, which is precisely how the
 * gate survived. The decision is separated from the filing of it for the same
 * reason [MarmotDirectMessage.classify] is.
 */
data class MarmotDelivery(
    /**
     * One request per relay, always. An empty list here means the event is on
     * disk and going nowhere.
     */
    val broadcasts: List<BroadcastNostrEventRequest>,
    /**
     * The chat line this send belongs to, or null when the row is protocol
     * traffic -- something the room did rather than something a member said.
     */
    val transcriptChatMessageId: Long?,
) {
    /** True when nothing in the chat points at this event, which is not a reason to withhold it. */
    val isProtocolTraffic: Boolean get() = transcriptChatMessageId == null

    companion object {
        fun plan(
            groupEventId: HexKey,
            relays: Collection<NormalizedRelayUrl>,
            chatMessage: ChatMessage?,
        ): MarmotDelivery = MarmotDelivery(
            broadcasts = relays.map { relay ->
                BroadcastNostrEventRequest(
                    nostrEventId = groupEventId,
                    relayURL = relay.url,
                )
            },
            transcriptChatMessageId = chatMessage?.id,
        )
    }
}
