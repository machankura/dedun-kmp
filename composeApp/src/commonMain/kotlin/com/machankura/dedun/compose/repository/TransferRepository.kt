package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.MemberTransfers
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

/**
 * Members transferring part of their balance to each other, for the screens: what the active profile can give, to whom, and
 * asking the group to record it. `docs/member-transfers.md`. The key is the repository's, never the screen's.
 */
interface TransferRepository {

    /**
     * What the active profile can give on [rail] in [chatRoomId] on [chain], by this device's count: [givableSat] in the rail's
     * ledger sats, and [heldSat] of it the wallet holds for them today. The two differ only on the Wavelength rail, when the
     * wallet holds less than the ledger promises (T5).
     */
    data class Givable(val givableSat: Long, val heldSat: Long) {
        /** What [ledgerSat] given arrives as, by this device's count today. */
        fun arrivesAs(ledgerSat: Long): Long = if (givableSat == 0L) 0 else ledgerSat * heldSat / givableSat

        /** The ledger sats that arrive as [sat] today: what a typed amount becomes (T5). */
        fun ledgerFor(sat: Long): Long = if (heldSat == 0L || heldSat >= givableSat) sat else (sat * givableSat + heldSat - 1) / heldSat
    }

    /** A member the active profile could transfer to: [canReceive] when the group has opened deposits for them (T3). */
    data class Receiver(val member: HexKey, val canReceive: Boolean)

    /** Whether the active profile can sign a request at all: an npub profile can't (S4). */
    fun canAsk(): Boolean

    suspend fun givable(chatRoomId: String, chain: Chain, rail: Rail): Givable

    /** Every current member of [chatRoomId] but the active profile, and whether each can receive on [chain]. */
    suspend fun receivers(chatRoomId: String, chain: Chain): List<Receiver>

    /** Signs and carries a request, and puts it to the group where this device can. The session, if one was opened. */
    suspend fun request(localChatRoom: LocalChatRoom, chain: Chain, rail: Rail, to: HexKey, amountSat: Long, note: String?): FrostSigningSession?

    /** Every request carried in [chatRoomId], newest first, with where it has got to. */
    suspend fun requests(chatRoomId: String): List<MemberTransfers.Asked>

    /** Puts another member's [request] to the group from this device, which has to hold a share (T10). */
    suspend fun putToGroup(localChatRoom: LocalChatRoom, request: TransferRequestEvent.Request): FrostSigningSession

    /** Takes back the active profile's own [request] (T9). */
    suspend fun withdraw(localChatRoom: LocalChatRoom, request: TransferRequestEvent.Request)

    companion object {
        val NO_OP = object : TransferRepository {
            override fun canAsk() = false
            override suspend fun givable(chatRoomId: String, chain: Chain, rail: Rail) = Givable(0, 0)
            override suspend fun receivers(chatRoomId: String, chain: Chain): List<Receiver> = emptyList()
            override suspend fun request(localChatRoom: LocalChatRoom, chain: Chain, rail: Rail, to: HexKey, amountSat: Long, note: String?): FrostSigningSession? =
                throw UnsupportedOperationException("no repository")
            override suspend fun requests(chatRoomId: String): List<MemberTransfers.Asked> = emptyList()
            override suspend fun putToGroup(localChatRoom: LocalChatRoom, request: TransferRequestEvent.Request): FrostSigningSession =
                throw UnsupportedOperationException("no repository")
            override suspend fun withdraw(localChatRoom: LocalChatRoom, request: TransferRequestEvent.Request) = throw UnsupportedOperationException("no repository")
        }
    }
}
