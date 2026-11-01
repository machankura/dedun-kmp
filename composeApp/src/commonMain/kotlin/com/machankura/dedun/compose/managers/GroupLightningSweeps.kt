package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Which of what closed channels left the group is still to be swept, and the sweep that moves it
 * (`docs/group-lightning-wallet.md`, Phase 13). The outputs come from the operator's snapshot; the
 * sweep is checked by every member as [GroupSweep] says, so a snapshot that lies about an output only
 * makes a sweep nobody can mine.
 */
object GroupLightningSweeps {
    /**
     * The feerate a sweep pays, in sats per virtual byte. A sweep is never urgent -- what it spends is
     * the group's alone -- so it pays little, and the watcher keeps sending it until it confirms.
     */
    const val FEERATE_SAT_PER_VBYTE: Long = 2

    /** The held outputs [report] lists that no open or finished sweep in the room already spends. */
    suspend fun unswept(database: DedunDatabase, chatRoomId: String, report: GroupLightningReports.Report?): List<GroupSweep.Input> {
        val outputs = report?.snapshot?.heldOutputs.orEmpty()
        if (outputs.isEmpty()) return outputs
        val taken = database.groupTransactionDao().getByChatRoomId(chatRoomId)
            .filter { row ->
                val session = database.frostSigningSessionDao().getSessionById(row.frostSigningSessionId)
                session?.subject == FrostSigningSubject.SWEEP && session.stage != FrostSigningStage.FAILED
            }
            .mapNotNull { runCatching { CommonJson.decodeFromString(GroupSweep.Sweep.serializer(), it.psbt) }.getOrNull() }
            .flatMap { sweep -> sweep.inputs.map { it.txid to it.index } }
            .toSet()
        return outputs.filter { (it.txid to it.index) !in taken }
    }

    /** Proposes sweeping [inputs] into the onchain wallet [wallet] closes to, at its first receive address. */
    suspend fun propose(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, inputs: List<GroupSweep.Input>): FrostSigningSession {
        val statement = wallet.statement
        val onchain = GroupWalletManager.walletFor(database, localChatRoom.chatRoom.id, statement.chain, statement.onchainAccount)
            ?: throw IllegalStateException("The onchain wallet this Lightning wallet closes to is not open")
        val sweep = GroupSweep.build(statement.wallet, inputs, onchain.account, receiveIndex = 0, feeratePerVbyte = FEERATE_SAT_PER_VBYTE)
            ?: throw IllegalArgumentException("There is too little to sweep to pay its fee")
        return FrostSigningManager.proposeSweep(database, localChatRoom, userPublicKey, sweep, statement.chain, statement.onchainAccount)
    }
}
