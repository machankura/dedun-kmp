package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.DepositorStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.PayoutDestinationRemoved
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

/**
 * Who deposits with a group, and on which branch: `docs/member-deposits.md`, Phase 3.
 *
 * Read from the group's signed events, so a device that joined late has them from the
 * chronicle, and a former member's branch stays known after they leave -- their deposits are
 * still theirs.
 */
object Depositors {

    /** Every member the group has opened deposits for in [chatRoomId] on [chain], the first statement each. */
    suspend fun statementsIn(database: DedunDatabase, chatRoomId: String, chain: Chain): Map<HexKey, DepositorStatementEvent.Depositor> =
        DepositorStatementEvent.firstPerMember(
            database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, DepositorStatementEvent.KIND).map { it.toEvent() },
            chatRoomId,
            chain,
        )

    /** [member]'s statement in [chatRoomId] on [chain], or null while the group has not opened their deposits. */
    suspend fun statementOf(database: DedunDatabase, chatRoomId: String, chain: Chain, member: HexKey): DepositorStatementEvent.Depositor? =
        statementsIn(database, chatRoomId, chain)[member.lowercase()]

    /** Why [member] can't be opened in [chatRoomId] on [chain] now, or null when they can. */
    enum class Refusal { NO_DESTINATION, ALREADY_OPEN, NOT_A_MEMBER }

    suspend fun refusal(database: DedunDatabase, chatRoomId: String, chain: Chain, member: HexKey): Refusal? {
        val members = database.participantDao().findParticipantsByChatRoomId(chatRoomId).map { it.participantPublicKey.lowercase() }
        if (member.lowercase() !in members) return Refusal.NOT_A_MEMBER
        if (statementOf(database, chatRoomId, chain, member) != null) return Refusal.ALREADY_OPEN
        if (GroupPayoutDestinations.destinationOf(database, chatRoomId, chain, member) == null) return Refusal.NO_DESTINATION
        return null
    }

    /**
     * Asks the group to open [member]'s deposits on [chain]: a signing session over a depositor
     * statement carrying the member's newest destination, on the first branch that collides
     * with no other member's (D3). Proposed by [proposerPublicKey], whose device holds a share;
     * proposing counts as approving.
     */
    suspend fun propose(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        proposerPublicKey: HexKey,
        member: HexKey,
        chain: Chain,
    ): FrostSigningSession {
        val chatRoomId = localChatRoom.chatRoom.id
        refusal(database, chatRoomId, chain, member)?.let { throw IllegalStateException("Can't open deposits for ${member.take(8)}: $it") }
        val destination = GroupPayoutDestinations.destinationOf(database, chatRoomId, chain, member)!!
        val taken = statementsIn(database, chatRoomId, chain).values.map { it.branch }.toSet()
        val salt = generateSequence(0) { it + 1 }.first { DepositBranch.of(chatRoomId, member, it) !in taken }
        val template = DepositorStatementEvent.template(chatRoomId, destination, salt)
        return FrostSigningManager.proposeSigning(database, localChatRoom, proposerPublicKey, template.kind, template.tags, template.content)
    }

    /**
     * Why a signer won't sign [proposed], a depositor statement for [chatRoomId], or null when it
     * will (D2, D3):
     *
     *  - it reads, the branch recomputes, and the destination is the member's own;
     *  - the member is in the room now;
     *  - the group has not already opened their deposits on that network;
     *  - no other member's branch is that branch;
     *  - the destination it carries is not older than one this device holds for the member, and
     *    the member has not removed theirs since.
     */
    suspend fun refuse(database: DedunDatabase, chatRoomId: String, proposed: Event): String? {
        val depositor = DepositorStatementEvent.read(proposed, chatRoomId) ?: return "does not read as a depositor statement for this group"
        val members = database.participantDao().findParticipantsByChatRoomId(chatRoomId).map { it.participantPublicKey.lowercase() }
        if (depositor.member !in members) return "opens deposits for somebody who is not in the group"

        val held = statementsIn(database, chatRoomId, depositor.chain)
        if (depositor.member in held) return "opens deposits the group has already opened"
        if (held.values.any { it.branch == depositor.branch }) return "gives the member a branch another member already has"

        when (val newest = GroupPayoutDestinations.statementsIn(database, chatRoomId, depositor.chain)[depositor.member]) {
            null -> Unit
            is PayoutDestinationRemoved -> if (newest.createdAt >= depositor.destination.createdAt) return "carries a destination the member has since removed"
            is GroupPayoutDestination -> if (newest.createdAt > depositor.destination.createdAt) return "carries a destination older than the member's newest"
        }
        return null
    }
}
