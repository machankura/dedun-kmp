package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.Flow

/**
 * A profile's Ark wallet, as the app sees it -- `docs/bark-wallet.md`, D3.
 *
 * Nothing outside the adapters names a `uniffi.bark` type: iOS has no Kotlin binding,
 * and the screens and the lifecycle are tested against a fake of this. Every call may
 * throw an [ArkError]; none of them may be made after [close].
 */
interface ArkWallet {
    val network: ArkNetwork

    /** Movements as they are created and updated, while the wallet is open. */
    val events: Flow<ArkEvent>

    /** How the mailbox scan went on the open that created this wallet. */
    fun recovery(): ArkRecovery

    suspend fun balance(): ArkBalance

    /** Newest first. */
    suspend fun history(): List<ArkMovement>

    suspend fun sync()

    suspend fun newArkAddress(): String

    /** A BIP321 URI with a new Ark address and, given an amount, a BOLT11 invoice. */
    suspend fun receive(amount: Satoshi?, description: String?): ArkReceiveRequest

    suspend fun lightningReceiveFee(amount: Satoshi): ArkFee

    /** Claims every Lightning payment that has arrived and not been claimed -- D12. */
    suspend fun claimLightningReceives()

    suspend fun parse(text: String): ArkPaymentRequest

    suspend fun estimateFee(method: ArkPaymentMethod, amount: Satoshi): ArkFee

    suspend fun send(method: ArkPaymentMethod, amount: Satoshi?, comment: String?): ArkSendResult

    /** Syncs, progresses rounds and hands coins due for renewal to the next round -- Phase 0, spike 4. */
    suspend fun maintenance()

    /** The height the earliest coin expires at, or null when none does. */
    suspend fun firstExpiryHeight(): Long?

    suspend fun tipHeight(): Long

    /** Stops Bark's daemon and waits for it, then releases the datadir. */
    suspend fun close()
}
