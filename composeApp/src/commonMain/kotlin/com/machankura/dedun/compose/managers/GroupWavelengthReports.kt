package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkSnapshotRow
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthStopped
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant

/**
 * What an operator tells the group about a Wavelength wallet, and what a device makes of it (`docs/group-wavelength-ui.md`, Phase 5).
 * A snapshot is believed only as far as being what the operator of its term said: a member sets it beside its own ledger.
 */
object GroupWavelengthReports {
    private val logger = Logger.withTag("GroupWavelengthReports")

    /** How a snapshot sits beside what this device has itself signed. */
    enum class Agreement {
        /** The same coins for the same amounts. */
        MATCHES,

        /** This device has signed for something the operator doesn't report, or the other way round. */
        DIFFERS,

        /** This device has no record to compare, as on a restored phone, so it can't say the operator is right. */
        CANNOT_CONFIRM,
    }

    data class Comparison(
        val agreement: Agreement,
        /** Coins the operator reports that this device has no settled record of. */
        val onlyTheOperator: List<GroupWavelengthSnapshot.Coin> = emptyList(),
        /** Coins this device holds settled that the operator doesn't report. */
        val onlyThisDevice: List<GroupArkVtxo> = emptyList(),
        /** Coins both know of, for different amounts. */
        val amountsDiffer: List<String> = emptyList(),
    )

    fun compare(snapshot: GroupWavelengthSnapshot, ledger: List<GroupArkVtxo>): Comparison {
        val mine = ledger.filter { it.settled && !it.spent }.associateBy { it.outpoint }
        if (ledger.isEmpty()) return Comparison(if (snapshot.coins.isEmpty()) Agreement.MATCHES else Agreement.CANNOT_CONFIRM, onlyTheOperator = snapshot.coins)
        val theirs = snapshot.coins.associateBy { it.outpoint }
        val onlyTheOperator = snapshot.coins.filter { coin ->
            // A coin this device signed for and is still waiting on isn't one it doesn't know of.
            coin.outpoint !in mine && ledger.none { it.outpoint == coin.outpoint && !it.spent }
        }
        val onlyThisDevice = mine.values.filter { it.outpoint !in theirs }
        val amountsDiffer = mine.values.filter { theirs[it.outpoint]?.let { coin -> coin.amountSat != it.amountSat } == true }.map { it.outpoint }
        val agrees = onlyTheOperator.isEmpty() && onlyThisDevice.isEmpty() && amountsDiffer.isEmpty()
        return Comparison(if (agrees) Agreement.MATCHES else Agreement.DIFFERS, onlyTheOperator, onlyThisDevice, amountsDiffer)
    }

    /** Files one inbound snapshot or stopped message. A malformed one is nobody's business but the log's. */
    suspend fun process(database: DedunDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        runCatching {
            when (innerEvent.kind) {
                GroupWavelengthEvents.SNAPSHOT -> receive(database, localChatRoom.chatRoom.id, innerEvent.pubKey, CommonJson.decodeFromString(GroupWavelengthSnapshot.serializer(), innerEvent.content))
                GroupWavelengthEvents.STOPPED -> receiveStopped(database, localChatRoom.chatRoom.id, innerEvent)
            }
        }.onFailure { logger.w("Could not read wallet report ${innerEvent.id}: ${it.message}") }
    }

    /** Files [snapshot] if it's from the operator of its term and newer than what's held. Whoever sends one files their own too. */
    suspend fun receive(database: DedunDatabase, chatRoomId: String, sentBy: HexKey, snapshot: GroupWavelengthSnapshot) {
        val (chain, number) = GroupArkCoSigner.parseWalletKey(chatRoomId, snapshot.wallet) ?: return logger.w("A snapshot for a wallet that isn't this room's")
        if (snapshot.coins.size > GroupWavelengthSnapshot.MAX_COINS || snapshot.activity.size > GroupWavelengthSnapshot.MAX_ACTIVITY) return logger.w("A snapshot too big to be real")
        val operator = GroupWavelengthOperatorManager.ofTerm(database, chatRoomId, chain, number, snapshot.term)
        if (operator?.publicKey != sentBy.lowercase()) return logger.w("A snapshot from someone other than the operator of term ${snapshot.term}")
        val dao = database.groupArkDao()
        val held = dao.snapshot(snapshot.wallet)
        if (held != null && (snapshot.term < held.term || (snapshot.term == held.term && snapshot.sentAt < held.sentAt.epochSeconds))) return
        dao.upsertSnapshot(GroupArkSnapshotRow(snapshot.wallet, sentBy.lowercase(), snapshot.term, CommonJson.encodeToString(GroupWavelengthSnapshot.serializer(), snapshot), Instant.fromEpochSeconds(snapshot.sentAt)))
    }

    private suspend fun receiveStopped(database: DedunDatabase, chatRoomId: String, event: Event) {
        val stopped = CommonJson.decodeFromString(GroupWavelengthStopped.serializer(), event.content)
        val (chain, number) = GroupArkCoSigner.parseWalletKey(chatRoomId, stopped.wallet) ?: return
        if (GroupWavelengthOperatorManager.ofTerm(database, chatRoomId, chain, number, stopped.term)?.publicKey != event.pubKey.lowercase()) return
        // Once a term, however many times it's said.
        GroupLightningLines.writeFact(database, chatRoomId, ChatMessage.TYPE_WAVELENGTH_OPERATOR_STOPPED, "${stopped.wallet}:${stopped.term}", event.pubKey, linesOfStopped(stopped), Instant.fromEpochSeconds(event.createdAt))
    }

    /** What a stopped operator's line says, on the device that sent it and the ones that heard it. */
    fun linesOfStopped(stopped: GroupWavelengthStopped): String = buildString {
        append("stopped running the Wavelength wallet.")
        if (stopped.exitsUnderWay > 0) append(" ${stopped.exitsUnderWay} exits are under way, which the next operator can't pick up.")
        if (stopped.paymentsInFlight > 0) append(" ${stopped.paymentsInFlight} payments are in flight, which it can't pick up either.")
    }
}
