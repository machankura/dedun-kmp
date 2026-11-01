package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.StateFlow

/**
 * The profile's Ark wallet, offered where the groups ask a member for money or for an
 * address -- `docs/bark-wallet.md`, Phase 10. Only where the networks match: a deposit
 * invoice from a mainnet group cannot be paid from a signet wallet, and a signet address
 * is no payout destination for a mainnet one.
 */
class ArkGroupBridge(private val walletState: StateFlow<ArkWalletState>) {
    /** A group invoice priced from the wallet: what it pays, the fee, and whether the balance covers both. */
    data class Quote(val method: ArkPaymentMethod, val amount: Satoshi, val fee: Satoshi, val covered: Boolean)

    private fun walletOn(chain: Chain): ArkWallet? = openOn(walletState.value, chain)

    /** [bolt11] priced for paying from the wallet, or null if it cannot pay it at all. */
    suspend fun quote(bolt11: String, chain: Chain): Quote? {
        val wallet = walletOn(chain) ?: return null
        return try {
            val request = wallet.parse(bolt11)
            val option = request.chosen?.takeIf { it.method.rail == ArkRail.Lightning } ?: return null
            val amount = request.amount ?: return null
            if (ArkWalletRollout.sendExceedsCap(wallet.network, amount)) return null
            val fee = wallet.estimateFee(option.method, amount)
            Quote(option.method, amount, fee.fee, covered = fee.gross <= wallet.balance().spendable)
        } catch (e: ArkError) {
            null
        }
    }

    /** Pays a [quote]d invoice. The invoice names its amount, so none is passed. */
    suspend fun pay(quote: Quote, chain: Chain): ArkSendResult {
        val wallet = walletOn(chain) ?: throw ArkError.Other("the wallet is not open on $chain")
        return wallet.send(quote.method, amount = null, comment = null)
    }

    companion object {
        private fun openOn(state: ArkWalletState, chain: Chain): ArkWallet? =
            (state as? ArkWalletState.Open)?.wallet?.takeIf { it.network.matches(chain) }

        /** Whether the wallet is open on [chain], which is when either bridge is offered. */
        fun offered(state: ArkWalletState, chain: Chain): Boolean = openOn(state, chain) != null
    }

    /** A fresh address from the wallet's onchain side, for a payout destination on [chain]. */
    suspend fun onchainAddress(chain: Chain): String? = try {
        walletOn(chain)?.onchainAddress()
    } catch (e: ArkError) {
        null
    }
}
