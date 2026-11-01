package com.machankura.dedun.compose.text

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupWalletState
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A spend with change, said the way the room's proposal list and the wallet's
 * spend rows say it: the payment, never the payment and the change together.
 * The change is the group paying itself, and a line that added it in would
 * tell every member the group sent more than it did.
 */
class ProposedSpendTest {
    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(),
        Chain.Regtest,
    )
    private val destination = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"

    private val state: GroupWalletState = run {
        val receive = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)
        val funding = Transaction(
            2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 1 })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(100_000), receive.pubkeyScript)), 0,
        )
        val meta = WalletState.AddressMeta.Derived(0)
        GroupWalletState(
            account,
            WalletState(mapOf(receive.address(Chain.Regtest) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(funding.txid, 0, 100, funding, meta))))),
            WalletState(mapOf(change.address(Chain.Regtest) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList()))),
            tipHeight = 110,
        )
    }

    @Test
    fun `a spend with change is described by its payment`() {
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.send(state, destination, Satoshi(30_000), 4, emptySet())
        )
        val spend = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, account)).checked

        assertEquals(Satoshi(30_000), spend.amountSent, "what the transcript lines report")
        assertEquals(
            ProposedEvent.Summary("Spend from the group's wallet", "Send 30,000 sats to $destination"),
            ProposedSpend.summarize(spend, Chain.Regtest),
        )
    }

    @Test
    fun `a send-all is described by everything less the fee`() {
        val built = assertIs<GroupSpendBuilder.Result.Built>(GroupSpendBuilder.sendAll(state, destination, 4, emptySet()))
        val spend = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, account)).checked

        assertEquals("Send ${(Satoshi(100_000) - spend.fee).grouped()} sats to $destination", ProposedSpend.summarize(spend, Chain.Regtest).detail)
    }
}
