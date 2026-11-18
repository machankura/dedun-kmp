package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.wallet.TransferLedger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.first

/**
 * Members transferring part of their balance to each other: `docs/member-transfers.md`.
 *
 * What every device counts: the transfers the group has signed in a room, first per request, in the group's own order, and
 * what each moves on a rail. A transfer moves no money. Each rail's balance adds [netsIn] to what it already counts, and the
 * deposit ledger never sees one (T7).
 */
object MemberTransfers {

    /** Every transfer the group has signed in [chatRoomId] on [chain], first per request, in [MemberTransferEvent.ORDER]. */
    suspend fun signedIn(database: DedunDatabase, chatRoomId: String, chain: Chain): List<MemberTransferEvent.Transfer> =
        MemberTransferEvent.firstPerRequest(
            database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, MemberTransferEvent.KIND).map { it.toEvent() },
            chatRoomId,
            chain,
        )

    /** What each member has received and given on [rail] in [chatRoomId] on [chain], by what the group signed. */
    suspend fun netsIn(database: DedunDatabase, chatRoomId: String, chain: Chain, rail: Rail): Map<HexKey, TransferLedger.Net> =
        TransferLedger.netsOf(signedIn(database, chatRoomId, chain), chain, rail)

    /**
     * The transfers in a signing session in [chatRoomId] that hasn't completed or failed: proposed, not yet the group's. A
     * giver with one of these has an outflow open (T8), and neither a payout nor another transfer of theirs is signed on that
     * rail until it settles one way or the other.
     */
    suspend fun beingSigned(database: DedunDatabase, chatRoomId: String): List<MemberTransferEvent.Transfer> =
        database.frostSigningSessionDao().observeSessionsForChatRoom(chatRoomId).first()
            .filter { it.session.stage != FrostSigningStage.FAILED && it.session.stage != FrostSigningStage.COMPLETE }
            .flatMap { it.items }
            .mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }
            .filter { it.kind == MemberTransferEvent.KIND }
            .mapNotNull { MemberTransferEvent.read(it) }

    /** Whether [member] is giving something on [rail] and [chain] that the group is still signing, other than [except]. */
    suspend fun isGiving(database: DedunDatabase, chatRoomId: String, chain: Chain, rail: Rail, member: HexKey, except: String? = null): Boolean =
        beingSigned(database, chatRoomId).any {
            it.chain == chain && it.rail == rail && it.from.equals(member, ignoreCase = true) && it.requestId != except
        }
}
