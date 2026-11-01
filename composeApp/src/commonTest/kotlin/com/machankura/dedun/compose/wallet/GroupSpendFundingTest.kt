package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A spend that funds a descendant's wallet: the payment carries its place under
 * the descendant's account, and that claim has to survive the wire and never
 * be taken for change. See `docs/guarded-wallet-funding.md`, Phase 2.
 */
class GroupSpendFundingTest {
    private fun accountFrom(seed: Int): GroupWalletDerivation.Account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString(),
        Chain.Regtest,
    )

    private val account = accountFrom(0x1c0ffee)

    /** The first descendant account, counting up, whose fingerprint has its top bit set: the one the wire reads back signed. */
    private val descendant: GroupWalletDerivation.Account = generateSequence(1) { it + 1 }
        .map { accountFrom(it) }
        .first { it.fingerprint >= 0x80000000L }

    private val funding = GroupSpendBuilder.Destination.Funding(
        descendant, GroupWalletDerivation.derive(descendant, GroupWalletDerivation.RECEIVE, 7),
    )
    private val typed = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
    private val feerate = 5L

    private fun coin(index: Int, sats: Long): Pair<String, WalletState.AddressState> {
        val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, index.toLong())
        val tx = Transaction(
            2,
            listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { index.toByte() })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(sats), derived.pubkeyScript)),
            0,
        )
        val meta = WalletState.AddressMeta.Derived(index)
        return derived.address(Chain.Regtest) to WalletState.AddressState(
            meta, alreadyUsed = true, utxos = listOf(WalletState.Utxo(tx.txid, 0, 100, tx, meta)),
        )
    }

    private fun state(vararg coins: Pair<String, WalletState.AddressState>): GroupWalletState {
        val change = WalletState(mapOf(
            GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0).address(Chain.Regtest) to
                WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList())
        ))
        return GroupWalletState(account, WalletState(coins.toMap()), change, tipHeight = 110)
    }

    @Test
    fun `a funding's claim survives the wire for a high fingerprint`() {
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.send(state(coin(0, 60_000)), funding, Satoshi(30_000), feerate, emptySet())
        )
        assertEquals(funding, built.funded)
        assertEquals(funding.derived.pubkeyScript, built.psbt.global.tx.txOut[0].publicKeyScript)

        assertTrue(descendant.fingerprint >= 0x80000000L)
        val received = GroupSpend.decode(GroupSpend.encode(built.psbt))!!
        assertEquals(setOf(7L), GroupSpend.claimedFundingIndices(received, descendant))
        assertEquals(setOf(0L), GroupSpend.claimedChangeIndices(received, account), "the change is still the room's own")
        assertEquals(emptySet(), GroupSpend.claimedFundingIndices(received, account), "the room's own account funds nothing")
    }

    @Test
    fun `a funding is a payment, not change`() {
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.send(state(coin(0, 60_000)), funding, Satoshi(30_000), feerate, emptySet())
        )
        val received = GroupSpend.decode(GroupSpend.encode(built.psbt))!!

        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(received, account)).checked
        assertEquals(listOf(false, true), checked.outputs.map { it.isChange })
        assertEquals(Satoshi(30_000), checked.amountSent)
    }

    @Test
    fun `sending everything can fund too`() {
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.sendAll(state(coin(0, 60_000), coin(1, 40_000)), funding, feerate, emptySet())
        )
        assertEquals(funding, built.funded)
        assertEquals(setOf(7L), GroupSpend.claimedFundingIndices(built.psbt, descendant))
        assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, account))
    }

    @Test
    fun `a send to a typed address claims nothing about its payment`() {
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.send(state(coin(0, 60_000)), typed, Satoshi(30_000), feerate, emptySet())
        )
        assertNull(built.funded)
        assertTrue(built.psbt.outputs[0].taprootDerivationPaths.isEmpty())
        assertEquals(1, built.psbt.outputs[1].taprootDerivationPaths.size, "change is described as it always was")
    }

    @Test
    fun `a wallet on another network is not one to fund`() {
        val elsewhere = GroupWalletDerivation.account(
            PrivateKey(ByteVector32(Hex.decode("decaf".padStart(64, '0')))).publicKey().toString(),
            Chain.Signet,
        )
        val across = GroupSpendBuilder.Destination.Funding(elsewhere, GroupWalletDerivation.derive(elsewhere, GroupWalletDerivation.RECEIVE, 0))
        assertEquals(
            GroupSpendBuilder.Result.NotAnAddress,
            GroupSpendBuilder.send(state(coin(0, 60_000)), across, Satoshi(30_000), feerate, emptySet()),
        )
    }
}
