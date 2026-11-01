package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningOperatorStatementEvent
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Who runs a group Lightning wallet's node, and how that changes hands (`docs/group-lightning-wallet.md`,
 * Phase 7).
 *
 * An operator is elected the only way this group decides anything: a statement a quorum signs. The
 * statement carries a term that only goes up, and [current] is the verified statement with the highest
 * term -- two elections completing with the same term are settled by the lower event id, so every device
 * picks the same one.
 */
object GroupLightningOperatorManager {
    /** How fresh this device's view of the room must be before it may start the group's node. */
    val START_GATE: Duration = 5.minutes

    data class Operator(val statement: GroupLightningOperatorStatementEvent.Statement, val signedEvent: GroupSignedEvent) {
        val publicKey: HexKey? get() = statement.operator
        val term: Long get() = statement.term
    }

    @Serializable
    data class Stopped(val term: Long, val commitmentNumbers: Map<String, Long>)

    /** The operator of [chain]'s wallet [wallet] in [chatRoomId], or null when none has been elected. */
    suspend fun current(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long = 0L): Operator? =
        terms(database, chatRoomId, chain, wallet).firstOrNull()

    /** The operator the group elected for [term], or null when it elected nobody for it. */
    suspend fun ofTerm(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long, term: Long): Operator? =
        terms(database, chatRoomId, chain, wallet).firstOrNull { it.term == term }

    /** Every term's statement, newest first; of two for one term, the lower event id wins, as in [current]. */
    private suspend fun terms(database: DedunDatabase, chatRoomId: String, chain: Chain, wallet: Long): List<Operator> =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, GroupLightningOperatorStatementEvent.KIND)
            .filter { it.verifies() }
            .mapNotNull { signed -> GroupLightningOperatorStatementEvent.read(signed.tags)?.let { Operator(it, signed) } }
            .filter { it.statement.chain == chain && it.statement.wallet == wallet }
            .sortedWith(compareByDescending<Operator> { it.term }.thenBy { it.signedEvent.id })

    /**
     * Proposes [userPublicKey] as the operator, for the term after the current one. A member can only
     * nominate themselves: the elected device has to run a node, and nobody is elected without agreeing.
     */
    suspend fun nominateSelf(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long = 0L): FrostSigningSession =
        propose(database, localChatRoom, userPublicKey, chain, wallet, operator = userPublicKey)

    /** Proposes that nobody runs the node: the wallet stops until somebody is elected. Only the operator may. */
    suspend fun stepDown(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long = 0L): FrostSigningSession {
        val current = current(database, localChatRoom.chatRoom.id, chain, wallet)
        require(current?.publicKey == userPublicKey) { "Only the operator can step down" }
        return propose(database, localChatRoom, userPublicKey, chain, wallet, operator = null)
    }

    private suspend fun propose(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long, operator: HexKey?): FrostSigningSession {
        requireNotNull(GroupLightningWalletManager.walletFor(database, localChatRoom.chatRoom.id, chain, wallet)) { "This group has no Lightning wallet on ${chain.name}" }
        val current = current(database, localChatRoom.chatRoom.id, chain, wallet)
        val template = GroupLightningOperatorStatementEvent.template(
            GroupLightningOperatorStatementEvent.Statement(chain, wallet, operator, term = (current?.term ?: 0L) + 1, previous = current?.publicKey)
        )
        return FrostSigningManager.proposeSigning(database, localChatRoom, userPublicKey, template.kind, template.tags, template.content)
    }

    /**
     * Whether this device may start the group's node: the newest operator statement it holds names
     * [userPublicKey], and the room has synced within [START_GATE] of [now]. A phone that comes back after
     * a week offline catches up on the room before its node can start. The co-signers are the real fence
     * (they refuse anything from an older term); this gate keeps an old node from becoming the griefing
     * case the plan's trust table describes.
     */
    suspend fun mayRunNode(database: DedunDatabase, chatRoomId: String, chain: Chain, userPublicKey: HexKey, lastSyncedAt: Instant?, now: Instant, wallet: Long = 0L): Boolean {
        if (lastSyncedAt == null || now - lastSyncedAt > START_GATE) return false
        return current(database, chatRoomId, chain, wallet)?.publicKey == userPublicKey
    }

    private const val STOPPED = "stopped running the group's Lightning node."

    /** Tells the room this device's node stopped, and where its channels were (kind 30366). */
    suspend fun announceStopped(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain, wallet: Long, term: Long, commitmentNumbers: Map<String, Long>) {
        val queued = GroupLightningWalletManager.queue(
            database, localChatRoom, userPublicKey, GroupLightningEvents.STOPPED,
            CommonJson.encodeToString(Stopped.serializer(), Stopped(term, commitmentNumbers)),
            tags = arrayOf(arrayOf("d", GroupLightningWalletStatementEvent.identifier(chain, wallet))),
        )
        GroupLightningLines.write(database, localChatRoom.chatRoom.id, queued.id, ChatMessage.TYPE_LIGHTNING_OPERATOR_STOPPED, userPublicKey, STOPPED, queued.createdAt)
    }

    /**
     * An operator's stop, as its line: only from whoever ran that term's node. Their commitment
     * numbers are read by the next operator's node from the stored event, not from here.
     */
    suspend fun receiveStopped(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        val identifier = innerEvent.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)?.split(':') ?: return
        val chain = identifier.getOrNull(0)?.let(GroupWalletStatementEvent::chainNamed) ?: return
        val wallet = identifier.getOrNull(1)?.toLongOrNull() ?: return
        val stopped = runCatching { CommonJson.decodeFromString(Stopped.serializer(), innerEvent.content) }.getOrNull() ?: return
        if (ofTerm(database, localChatRoom.chatRoom.id, chain, wallet, stopped.term)?.publicKey != innerEvent.pubKey) return
        GroupLightningLines.write(
            database, localChatRoom.chatRoom.id, innerEvent.id, ChatMessage.TYPE_LIGHTNING_OPERATOR_STOPPED,
            innerEvent.pubKey, STOPPED, Instant.fromEpochSeconds(innerEvent.createdAt),
        )
    }
}
