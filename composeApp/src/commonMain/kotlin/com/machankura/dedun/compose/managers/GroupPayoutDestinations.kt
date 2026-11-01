package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.managers.MemberWalletManager.asEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.PayoutStatement
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * Where each member of a room wants it to pay them: `docs/group-payout-destination.md`.
 *
 * Read straight from the room's inner events. The `MarmotInnerEvent` row carries the
 * signed statement whole, as it does a guarded-wallet request, so there is no table of
 * its own to keep in step with it.
 */
object GroupPayoutDestinations {

    /**
     * The newest statement of each current member of [chatRoomId] on [chain], a removal
     * included. A member no longer in the room is skipped, not forgotten: added again, their
     * last statement counts again (D3).
     */
    suspend fun statementsIn(
        database: DedunDatabase,
        chatRoomId: String,
        chain: Chain,
        now: Long = Clock.System.now().epochSeconds,
    ): Map<HexKey, PayoutStatement> {
        val members = database.participantDao().findParticipantsByChatRoomId(chatRoomId)
            .map { it.participantPublicKey.lowercase() }
            .toSet()
        val signed = database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(GroupPayoutDestinationEvent.KIND))
            .mapNotNull { GroupPayoutDestinationEvent.carried(it.asEvent()) }
        return GroupPayoutDestinationEvent.newestPerMember(signed, chatRoomId, chain, now)
            .filterKeys { it in members }
    }

    /** Every current member of [chatRoomId] who has said where it pays them on [chain], and where. */
    suspend fun destinationsIn(
        database: DedunDatabase,
        chatRoomId: String,
        chain: Chain,
        now: Long = Clock.System.now().epochSeconds,
    ): Map<HexKey, GroupPayoutDestination> =
        statementsIn(database, chatRoomId, chain, now)
            .mapNotNull { (member, statement) -> (statement as? GroupPayoutDestination)?.let { member to it } }
            .toMap()

    /** Where [chatRoomId] pays [member] on [chain], or null when they have not said or have withdrawn it. */
    suspend fun destinationOf(
        database: DedunDatabase,
        chatRoomId: String,
        chain: Chain,
        member: HexKey,
        now: Long = Clock.System.now().epochSeconds,
    ): GroupPayoutDestination? = destinationsIn(database, chatRoomId, chain, now)[member.lowercase()]

    /**
     * Puts [signed] into [chatRoomId], whole, as an inner event's content, and writes this device's
     * own line for it: a device never reads its own message back, so the line every other member's
     * device writes on arrival is written here on the way out. Returns the inner event's id.
     */
    suspend fun publish(database: DedunDatabase, chatRoomId: String, signed: Event): HexKey {
        require(signed.kind == GroupPayoutDestinationEvent.KIND) { "not a payout destination" }
        val createdAt = Clock.System.now()
        val tags = arrayOf(arrayOf("h", chatRoomId.lowercase()))
        val content = signed.toJson()
        val id = MemberWalletManager.queue(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = signed.pubKey,
            tags = tags,
            content = content,
            kind = GroupPayoutDestinationEvent.KIND,
            createdAt = createdAt,
        )
        val inner = Event(id, signed.pubKey, createdAt.epochSeconds, GroupPayoutDestinationEvent.KIND, tags, content, "")
        ChatMessage.applyInnerEvent(
            database = database,
            groupId = chatRoomId,
            event = inner,
            marmotGroupEventId = null,
            marmotInnerEventId = id,
            senderPublicKey = signed.pubKey,
            isUserMessage = true,
            createdAt = createdAt,
        )?.let { database.chatMessageDao().upsert(it) }
        return id
    }
}
