package com.machankura.dedun.compose.wallet.lightning

import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.crypto.SwapInKeys
import fr.acinq.lightning.crypto.SwapInSigner
import fr.acinq.lightning.transactions.SwapInProtocol

/**
 * The swap-in keys a group's node runs on (`docs/group-lightning-swap-in.md`, D1, D10): the group's Iceberg
 * key as the user, signing through [signer], and the depositors' addresses as [wallet] numbers them. There is
 * no legacy address and the node never serves a swap-in, so both of those refuse by name.
 */
class GroupSwapInKeys(
    private val wallet: GroupSwapInWallet,
    override val signer: SwapInSigner,
) : SwapInKeys {
    override val userPublicKey: PublicKey get() = wallet.keys.icebergKey
    override val remoteServerPublicKey: PublicKey get() = wallet.keys.serverKey
    override val refundDelay: Int get() = wallet.keys.refundDelay

    override fun getSwapInProtocol(addressIndex: Int): SwapInProtocol =
        wallet.latest.value?.protocolAt(addressIndex) ?: throw IllegalStateException("no depositor's address is numbered $addressIndex")

    override fun signSwapInputUserLegacy(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>): ByteVector64 =
        throw UnsupportedOperationException("a group has no legacy swap-in address")

    override fun localServerPrivateKey(remoteNodeId: PublicKey): PrivateKey =
        throw UnsupportedOperationException("a group's node never serves a swap-in")
}

/**
 * A group node's swap-in keys where it has no swap-in wallet: everything refuses. In place of the node
 * seed's own, whose addresses are a 2-of-2 between a key every member holds and the LSP (D10) -- with these,
 * `Peer` builds no swap-in wallet from the seed at all.
 */
class NoSwapInKeys(override val userPublicKey: PublicKey, override val remoteServerPublicKey: PublicKey, override val refundDelay: Int) : SwapInKeys {
    override val signer: SwapInSigner get() = throw UnsupportedOperationException("this group's node has no swap-in wallet")
    override fun getSwapInProtocol(addressIndex: Int): SwapInProtocol = throw UnsupportedOperationException("this group's node has no swap-in wallet")
    override fun signSwapInputUserLegacy(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>): ByteVector64 = throw UnsupportedOperationException("a group has no legacy swap-in address")
    override fun localServerPrivateKey(remoteNodeId: PublicKey): PrivateKey = throw UnsupportedOperationException("a group's node never serves a swap-in")
}
