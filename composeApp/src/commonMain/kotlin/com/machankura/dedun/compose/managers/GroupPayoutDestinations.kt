package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.managers.MemberWalletManager.asEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.PayoutStatement
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.wallet.PayoutAddress
import fr.acinq.bitcoin.ByteVector
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
     * The tag on an inner event that carries a statement again rather than for the first time
     * (Phase 6). It makes no line on any device: the member it is for was not in the room when
     * the statement was made, and a line now would date it to their arrival.
     */
    const val RESENT_TAG = "resent"

    /** Whether [inner] carries its statement again, for a member added since. */
    fun isResent(inner: Event): Boolean = inner.tags.any { it.isNotEmpty() && it[0] == RESENT_TAG }

    /**
     * Puts [signed] into [chatRoomId], whole, as an inner event's content, and writes this device's
     * own line for it: a device never reads its own message back, so the line every other member's
     * device writes on arrival is written here on the way out. A [resent] statement gets the
     * [RESENT_TAG] and no line anywhere. Returns the inner event's id.
     */
    suspend fun publish(database: DedunDatabase, chatRoomId: String, signed: Event, resent: Boolean = false): HexKey {
        require(signed.kind == GroupPayoutDestinationEvent.KIND) { "not a payout destination" }
        val createdAt = Clock.System.now()
        val tags = listOfNotNull(arrayOf("h", chatRoomId.lowercase()), if (resent) arrayOf(RESENT_TAG) else null).toTypedArray()
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
        if (resent) return id
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

    /**
     * Sends [member]'s own newest destination in [chatRoomId] again, on every chain they have one,
     * for the members a commit has just added: inner events are not chronicled, so a member added
     * after Alice set hers would otherwise never see it (D8). Only the member's own device sends
     * theirs, so nobody needs electing, and a removal is not sent -- a new member has nothing older
     * to hide. The same signed statement each time, so a member who already holds it reads the same
     * newest. Returns how many were sent.
     */
    suspend fun resendOwn(
        database: DedunDatabase,
        chatRoomId: String,
        member: HexKey,
        now: Long = Clock.System.now().epochSeconds,
    ): Int {
        val own = database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(GroupPayoutDestinationEvent.KIND))
            .filter { it.publicKey.equals(member, ignoreCase = true) }
            .mapNotNull { GroupPayoutDestinationEvent.carried(it.asEvent()) }
            .mapNotNull { GroupPayoutDestinationEvent.verified(it) }
            .filter { it.chatRoomId.equals(chatRoomId, ignoreCase = true) && it.createdAt <= now + GroupPayoutDestinationEvent.MAX_FUTURE_SECONDS }
        val newestPerChain = own.groupBy { it.chain }.values.map { it.maxWith(GroupPayoutDestinationEvent.ORDER) }
        val toSend = newestPerChain.filterIsInstance<GroupPayoutDestination>()
        toSend.forEach { publish(database, chatRoomId, it.event, resent = true) }
        return toSend.size
    }

    /**
     * Which of [payments] -- a spend's outputs that are not change -- pay a member's onchain payout
     * address among [destinations], by script as hex, named by [nameOf]. A byte-for-byte match
     * against the member's own signed statement, so a co-signer's device names the payee itself
     * rather than taking the proposer's word for it (Phase 7).
     */
    fun payeesOf(
        payments: List<ByteVector>,
        destinations: Collection<GroupPayoutDestination>,
        nameOf: (HexKey) -> String,
    ): Map<String, String> {
        val byScript = destinations.mapNotNull { destination ->
            val script = destination.onchainAddress?.let { PayoutAddress.parseOrNull(destination.chain, it) }?.script ?: return@mapNotNull null
            script.toHex() to destination.member
        }.toMap()
        return payments.mapNotNull { script -> byScript[script.toHex()]?.let { script.toHex() to nameOf(it) } }.toMap()
    }
}
