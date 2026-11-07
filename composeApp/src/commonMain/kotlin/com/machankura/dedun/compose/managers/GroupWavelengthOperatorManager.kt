package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOperatorStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Who runs a group's Wavelength wallet -- `docs/group-wavelength-wallet.md`, Phase 5. As in the Lightning wallet, the operator
 * is trusted for liveness only: a member can only nominate themselves, the highest term wins and a tie goes to the lower event
 * id, so every device decides alike.
 */
object GroupWavelengthOperatorManager {
    data class Operator(val publicKey: HexKey?, val term: Long, val eventId: String)

    private val SYNC_WINDOW = 5.minutes

    private suspend fun terms(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long): List<Operator> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupWavelengthOperatorStatementEvent.KIND)
            .filter(GroupSignedEvent::verifies)
            .mapNotNull { signed ->
                GroupWavelengthOperatorStatementEvent.read(signed.tags)?.takeIf { it.chain == chain && it.wallet == wallet }
                    ?.let { Operator(it.operator?.lowercase(), it.term, signed.id) }
            }
            .sortedWith(compareByDescending<Operator> { it.term }.thenBy { it.eventId })

    suspend fun current(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long = 0L): Operator? =
        terms(database, chatRoomId, chain, wallet).firstOrNull()

    /** Who ran the wallet in [term], whether or not that's still the current one. */
    suspend fun ofTerm(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long, term: Long): Operator? =
        terms(database, chatRoomId, chain, wallet).firstOrNull { it.term == term }

    /** Asks the group to let this device run the wallet from the next term on. */
    suspend fun nominateSelf(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long = 0L): FrostSigningSession =
        propose(database, localChatRoom, userPublicKey, chain, wallet, userPublicKey.lowercase())

    /** Only the current operator can stop: the wallet then waits until somebody is chosen. */
    suspend fun stepDown(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long = 0L): FrostSigningSession {
        val current = current(database, localChatRoom.chatRoom.id, chain, wallet)
        require(current?.publicKey == userPublicKey.lowercase()) { "Only the operator can step down" }
        return propose(database, localChatRoom, userPublicKey, chain, wallet, null)
    }

    private suspend fun propose(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long, operator: HexKey?): FrostSigningSession {
        val next = (current(database, localChatRoom.chatRoom.id, chain, wallet)?.term ?: 0L) + 1
        val template = GroupWavelengthOperatorStatementEvent.template(GroupWavelengthOperatorStatementEvent.Statement(chain, wallet, operator, next))
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /**
     * Whether this device may start the daemon: the newest statement it holds names its owner, and it has heard from the room
     * lately, so a phone back after a week catches up before it starts. The co-signers are the real fence; this saves an old
     * device from running a daemon nobody will answer.
     */
    suspend fun mayRunDaemon(database: DedunDatabase, chatRoomId: String, chain: Chain, userPublicKey: HexKey, lastSyncedAt: Instant?, now: Instant, wallet: Long = 0L): Boolean {
        val operator = current(database, chatRoomId, chain, wallet) ?: return false
        if (operator.publicKey != userPublicKey.lowercase() || lastSyncedAt == null) return false
        return now - lastSyncedAt <= SYNC_WINDOW
    }
}
