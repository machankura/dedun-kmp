package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi

/** A coin as Bark holds it: a VTXO -- `docs/bark-wallet.md`, Phase 9. */
data class ArkVtxo(val id: String, val amount: Satoshi, val expiryHeight: Long, val state: State) {
    enum class State { Spendable, Locked, Spent, Exited }
}

/** Where one coin's emergency exit has got to. */
data class ArkExitStatus(val vtxoId: String, val state: State, val error: String?) {
    enum class State { Starting, Broadcasting, AwaitingDelay, Claimable, Claiming, Claimed, Canceled, AlreadySpent, Swept }
}

/**
 * What an emergency exit of every coin would cost onchain, and whether the onchain wallet
 * holds enough to pay it: each step is fee-bumped from there (D8).
 */
data class ArkExitFee(val total: Satoshi, val fundable: Boolean)

/**
 * Getting out of Ark -- Phase 9. A cooperative offboard for everyday use, and an emergency
 * exit, which needs no server, for when it is gone. Kept apart from [ArkWallet] so the
 * screens that never leave Ark do not see it.
 */
interface ArkExits {
    suspend fun vtxos(): List<ArkVtxo>

    suspend fun onchainAddress(): String

    suspend fun offboardAllFee(address: String): ArkFee

    /** Moves every coin to [address] in the server's next round. Returns the transaction id. */
    suspend fun offboardAll(address: String): String

    suspend fun emergencyExitFee(): ArkExitFee

    suspend fun startEmergencyExit()

    /** Broadcasts what can be broadcast next, and says where each exit stands. */
    suspend fun progressExits(): List<ArkExitStatus>

    /** The height from which every exit can be claimed, or null while none is under way. */
    suspend fun exitsClaimableAt(): Long?

    /** Claims every exited coin to [address], signed and broadcast. Returns the transaction id. */
    suspend fun claimExits(address: String): String
}
