package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.SwapInOnChainKeys
import fr.acinq.lightning.utils.sum
import fr.acinq.phoenix.PhoenixBusiness
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Taking back swap-in coins past the refund delay, with the user's key alone
 * (docs/personal-swap-in-wallet.md, D7 and Phase 6).
 */
object SwapInRefund {
    /** Where a refund may go, read from what the user typed. */
    sealed interface Destination {
        data class Valid(val address: String, val script: ByteVector) : Destination

        /** Not an address, or an address on another network. */
        data object Unreadable : Destination

        /** One of this wallet's own swap-in addresses: the coins would be locked again for six months. */
        data object OwnDeposit : Destination
    }

    fun destination(chain: Chain, typed: String, ownAddresses: Set<String>): Destination {
        val address = typed.trim().removePrefix("bitcoin:").substringBefore('?')
        val script = Bitcoin.addressToPublicKeyScript(chain.chainHash, address).right ?: return Destination.Unreadable
        if (address in ownAddresses) return Destination.OwnDeposit
        return Destination.Valid(address, Script.write(script).byteVector())
    }

    /** A signed refund, not yet broadcast. */
    data class Preview(val tx: Transaction, val fee: Satoshi, val refunded: Satoshi, val coins: Int) {
        /** What reaches the destination: the refunded coins less the fee. */
        val received: Satoshi get() = refunded - fee
    }

    /**
     * Every refundable coin in [state] sent to [script] at [feerate], signed with [keys] -- or null when
     * nothing is refundable yet, or when the fee would take everything.
     */
    fun preview(state: WalletState, tip: Int, params: SwapInParams, keys: SwapInOnChainKeys, script: ByteVector, feerate: FeeratePerKw): Preview? {
        val wallet = WalletState.WalletWithConfirmations(params, tip, state.utxos)
        val refundable = wallet.readyForRefund
        if (refundable.isEmpty()) return null
        val (tx, fee) = wallet.spendExpiredSwapIn(keys, script, feerate) ?: return null
        return Preview(tx, fee, refundable.map { it.amount }.sum(), refundable.size)
    }
}

/** What a refund needs from the node: its network and keys, a feerate, an address check and a broadcast. */
interface SwapInRefunder {
    val chain: Chain
    suspend fun keys(): SwapInOnChainKeys
    suspend fun feerate(): FeeratePerKw
    suspend fun usage(script: ByteVector): UnusedAddressCheck.Usage
    suspend fun broadcast(tx: Transaction): TxId
}

/** The personal node's refunder: its Electrum client checks the address and carries the transaction. */
class NodeRefunder(private val business: PhoenixBusiness) : SwapInRefunder {
    override val chain: Chain get() = business.chain
    override suspend fun keys(): SwapInOnChainKeys = business.walletManager.keyManager.filterNotNull().first().swapInOnChainWallet
    // PeerManager's recommendation: mempool.space's half-hour estimate, else the LSP's, else 3 sat/vB.
    override suspend fun feerate(): FeeratePerKw = FeeratePerKw(business.peerManager.recommendedFeerateFlow.value)
    override suspend fun usage(script: ByteVector): UnusedAddressCheck.Usage = UnusedAddressCheck.check(business.electrumClient, script)
    override suspend fun broadcast(tx: Transaction): TxId = broadcastAndCheck(business.electrumClient, tx)

    companion object {
        /**
         * Sends [tx] and asks for it back. lightning-kmp's `broadcastTransaction` answers with the
         * transaction's id whether or not the server took it, so a refund the server refused would
         * otherwise read as sent; a server that took it can hand it back.
         */
        suspend fun broadcastAndCheck(electrum: IElectrumClient, tx: Transaction): TxId {
            electrum.broadcastTransaction(tx)
            checkNotNull(electrum.getTx(tx.txid)) { "the server does not have refund ${tx.txid}" }
            return tx.txid
        }
    }
}
