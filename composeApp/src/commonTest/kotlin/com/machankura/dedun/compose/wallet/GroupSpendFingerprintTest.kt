package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A spend checked the way every member but the proposer checks it: after the
 * PSBT has been through the wire.
 *
 * Half of all accounts have a fingerprint with its top bit set, and bitcoin-kmp
 * reads a taproot derivation's fingerprint back from a serialised PSBT as a
 * signed int. Compared as written, every such wallet's spends were refused by
 * every member who did not build them.
 */
class GroupSpendFingerprintTest {
    /** The first account, counting up from a fixed seed, whose fingerprint has its top bit set. */
    private val account: GroupWalletDerivation.Account = generateSequence(1) { it + 1 }
        .map { seed ->
            val key = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString()
            GroupWalletDerivation.account(key, Chain.Regtest)
        }
        .first { it.fingerprint >= 0x80000000L }

    @Test
    fun `a spend from a wallet with a high fingerprint checks out after the wire`() {
        val coin = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)
        val funding = Transaction(
            2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 0x51 })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(50_000), coin.pubkeyScript)), 0,
        )
        val elsewhere = ByteVector(Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey())))
        val tx = Transaction(
            2, listOf(TxIn(OutPoint(funding, 0), ByteVector.empty, GroupSpend.MAX_SEQUENCE)),
            listOf(TxOut(Satoshi(30_000), elsewhere), TxOut(Satoshi(19_000), change.pubkeyScript)), 0,
        )
        val proposed = GroupSpend.psbtOf(tx, listOf(funding.txOut[0] to coin), mapOf(1 to change), account)

        assertTrue(account.fingerprint >= 0x80000000L)
        val received = GroupSpend.decode(GroupSpend.encode(proposed))!!

        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(received, account)).checked
        assertEquals(listOf(false, true), checked.outputs.map { it.isChange })
        assertEquals(setOf(0L), GroupSpend.claimedChangeIndices(received, account))
    }
}
