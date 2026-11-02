package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.utils.sum

/**
 * One of a depositor's swap-in addresses, as the group's swap-in screen lists it
 * (docs/group-lightning-swap-in.md, Phase 6).
 */
data class SwapInAddress(
    val address: String,
    /** The derivation index, or null for an address with none (lightning-kmp's legacy one: a group has none). */
    val index: Int?,
    val status: Status,
    /** What it holds now: zero for an address never paid, and for one paid and swapped in. */
    val balance: Satoshi,
) {
    enum class Status {
        /** The address being handed out: the lowest unused one. */
        CURRENT,

        /** Paid at least once, whether or not the coins have moved since. */
        USED,
    }
}

/** One coin on a swap-in address, and where it is on its way into a channel. */
data class SwapInCoin(
    val outPoint: OutPoint,
    val amount: Satoshi,
    /** The address it was paid to, and that address's index, or null for the legacy one. */
    val index: Int?,
    val confirmations: Int,
    val status: Status,
) {
    val txId: TxId get() = outPoint.txid

    sealed interface Status {
        /** In the mempool. */
        data object Unconfirmed : Status

        /** In a block, but short of the [needed] confirmations a swap waits for. */
        data class Confirming(val confirmations: Int, val needed: Int) : Status

        /** Deep enough, and the node may use it in the next splice or channel it asks for. */
        data object Ready : Status

        /** Ready, but with fewer than [SwapInWalletView.EXPIRY_WARNING_BLOCKS] blocks left to be swapped in. */
        data class ExpiringSoon(val blocksLeft: Int) : Status

        /** Held by a funding attempt that has not confirmed: being moved into Lightning. */
        data object Swapping : Status

        /** Too old to swap and not old enough to refund: the refund opens in [blocksUntilRefund]. */
        data class Locked(val blocksUntilRefund: Int) : Status

        /** Past the refund delay: the user alone can spend it. */
        data object Refundable : Status
    }
}

/**
 * A swap-in wallet as the swap-in screen shows it, from the wallet state a watcher already keeps. Pure,
 * so the rules can be tested over a hand-built [WalletState]. Written for the profile's own node, which
 * was reverted; the group's screen uses it unchanged (docs/group-lightning-swap-in.md, Phase 3).
 */
object SwapInWalletView {
    /**
     * Two weeks of blocks: a coin this close to [SwapInParams.maxConfirmations] is flagged, so that a
     * user who opens the app once a week sees the warning at least twice before it locks.
     */
    const val EXPIRY_WARNING_BLOCKS: Int = 144 * 14

    /**
     * The addresses worth listing, newest first: every derived one that has been paid, and the
     * current one; then the [legacy] address, if it has ever been paid. Null until Electrum has said
     * which are used -- before that every address reads as unused, and the current one would be
     * index 0 for a wallet paid fifty times.
     *
     * Nothing past the current address: a swap-in wallet is one person's, so there is nobody to hand
     * a different address to (D3).
     */
    fun addresses(state: WalletState, legacy: String): List<SwapInAddress>? {
        val (_, current) = state.firstUnusedDerivedAddress ?: return null
        val derived = state.addresses.mapNotNull { (address, addressState) ->
            val index = (addressState.meta as? WalletState.AddressMeta.Derived)?.index ?: return@mapNotNull null
            when {
                addressState.alreadyUsed -> SwapInAddress(address, index, SwapInAddress.Status.USED, addressState.balance)
                index == current.index -> SwapInAddress(address, index, SwapInAddress.Status.CURRENT, addressState.balance)
                else -> null
            }
        }.sortedByDescending { it.index }
        val legacyRow = state.addresses[legacy]
            ?.takeIf { it.alreadyUsed }
            ?.let { SwapInAddress(legacy, null, SwapInAddress.Status.USED, it.balance) }
        return derived + listOfNotNull(legacyRow)
    }

    /**
     * Every coin on the wallet's addresses with its status, the mempool first and then newest first.
     *
     * [reserved] is what `SwapInManager.reservedWalletInputs` returns for the node's channels: the
     * coins an unconfirmed funding transaction spends. It is read from the channels rather than from
     * the node's `SwapInEvents.Requested`, because the channels are read back after a restart and the
     * event is not.
     */
    fun coins(state: WalletState, currentBlockHeight: Int, swapInParams: SwapInParams, reserved: Set<OutPoint>): List<SwapInCoin> {
        val wallet = WalletState.WalletWithConfirmations(swapInParams, currentBlockHeight, state.utxos)
        return state.utxos
            .map { utxo ->
                val confirmations = wallet.confirmations(utxo)
                SwapInCoin(
                    outPoint = utxo.outPoint,
                    amount = utxo.amount,
                    index = utxo.addressMeta.indexOrNull,
                    confirmations = confirmations,
                    status = statusOf(utxo, confirmations, swapInParams, reserved),
                )
            }
            .sortedWith(compareBy<SwapInCoin> { it.confirmations != 0 }.thenBy { it.confirmations })
    }

    private fun statusOf(utxo: WalletState.Utxo, confirmations: Int, params: SwapInParams, reserved: Set<OutPoint>): SwapInCoin.Status = when {
        utxo.outPoint in reserved -> SwapInCoin.Status.Swapping
        utxo.blockHeight == 0L -> SwapInCoin.Status.Unconfirmed
        confirmations < params.minConfirmations -> SwapInCoin.Status.Confirming(confirmations, params.minConfirmations)
        confirmations < params.maxConfirmations -> {
            val blocksLeft = params.maxConfirmations - confirmations
            if (blocksLeft < EXPIRY_WARNING_BLOCKS) SwapInCoin.Status.ExpiringSoon(blocksLeft) else SwapInCoin.Status.Ready
        }
        confirmations < params.refundDelay -> SwapInCoin.Status.Locked(params.refundDelay - confirmations)
        else -> SwapInCoin.Status.Refundable
    }

    private val WalletState.AddressState.balance: Satoshi get() = utxos.map { it.amount }.sum()
}
