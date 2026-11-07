package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.IElectrumClient

/**
 * How deep [tx] is now: 0 in the mempool, null while disconnected or while Electrum hasn't heard of it.
 *
 * Ask this and never [IElectrumClient.getConfirmations], which counts from the height its connection began at: a block newer than the
 * connection never reaches any depth until the connection drops, and a signet run sat over an hour on a coin ten blocks deep.
 *
 * Disconnected is answered without asking, because the real client holds a request until it has a connection. The tip is read last, so
 * it is never behind the history it is measured against on one server; a reconnection between the two can land on a server that is
 * behind, and a transaction in a block is still at least one deep.
 */
suspend fun IElectrumClient.confirmationsOf(tx: Transaction): Int? {
    if (connectionStatus.value !is ElectrumConnectionStatus.Connected) return null
    val scriptHash = Crypto.sha256(tx.txOut.first().publicKeyScript).byteVector32().reversed()
    val item = getScriptHashHistory(scriptHash).find { it.txid == tx.txid } ?: return null
    return if (item.blockHeight > 0) (startHeaderSubscription().blockHeight - item.blockHeight + 1).coerceAtLeast(1) else 0
}
