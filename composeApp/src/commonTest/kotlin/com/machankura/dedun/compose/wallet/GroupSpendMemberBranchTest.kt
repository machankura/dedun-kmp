package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.DepositBranch
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

/**
 * Every signer spends a coin a member deposited on their own branch, and pays change only to
 * the change branch -- `docs/member-deposits.md`, Phase 2.
 */
class GroupSpendMemberBranchTest {
    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("6a0b".padStart(64, '0')))).publicKey().toString(), Chain.Regtest,
    )
    private val room = "9a17".repeat(16)
    private val alicesBranch = DepositBranch.of(room, "a1".repeat(32))
    private val elsewhere = ByteVector(Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey())))

    private fun fundingOf(derived: GroupWalletDerivation.Derived, seed: Byte) = Transaction(
        2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { seed })), 0), ByteVector.empty, 0xffffffffL)),
        listOf(TxOut(Satoshi(50_000), derived.pubkeyScript)), 0,
    )

    @Test
    fun `a coin on a member's branch is spent, after the wire, beside one on receive`() {
        val deposit = GroupWalletDerivation.derive(account, alicesBranch, 3)
        val legacy = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)
        val first = fundingOf(deposit, 0x51)
        val second = fundingOf(legacy, 0x52)
        val tx = Transaction(
            2,
            listOf(TxIn(OutPoint(first, 0), ByteVector.empty, GroupSpend.MAX_SEQUENCE), TxIn(OutPoint(second, 0), ByteVector.empty, GroupSpend.MAX_SEQUENCE)),
            listOf(TxOut(Satoshi(80_000), elsewhere), TxOut(Satoshi(19_000), change.pubkeyScript)), 0,
        )
        val proposed = GroupSpend.psbtOf(tx, listOf(first.txOut[0] to deposit, second.txOut[0] to legacy), mapOf(1 to change), account)
        val received = GroupSpend.decode(GroupSpend.encode(proposed))!!

        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(received, account)).checked
        assertEquals(listOf(alicesBranch, GroupWalletDerivation.RECEIVE), checked.inputs.map { it.derivation.change })
    }

    @Test
    fun `a coin claiming a branch past the unhardened range is refused`() {
        // A claim the wallet would never make: past 2^31 is a hardened step the group can't walk.
        val real = GroupWalletDerivation.derive(account, alicesBranch, 0)
        val funding = fundingOf(real, 0x53)
        val tx = Transaction(2, listOf(TxIn(OutPoint(funding, 0), ByteVector.empty, GroupSpend.MAX_SEQUENCE)), listOf(TxOut(Satoshi(49_000), elsewhere)), 0)
        val proposed = GroupSpend.psbtOf(tx, listOf(funding.txOut[0] to real), emptyMap(), account)
        val forged = proposed.copy(inputs = proposed.inputs.map { input ->
            val witness = input as fr.acinq.bitcoin.psbt.Input.WitnessInput.PartiallySignedWitnessInput
            witness.copy(taprootDerivationPaths = witness.taprootDerivationPaths.mapValues { (_, path) ->
                path.copy(keyPath = fr.acinq.bitcoin.KeyPath(listOf((1L shl 31) + 5, 0L)))
            })
        })
        assertIs<GroupSpend.Result.Refused>(GroupSpend.check(forged, account))
    }

    @Test
    fun `change to a member's branch is refused`() {
        val coin = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val notChange = GroupWalletDerivation.derive(account, alicesBranch, 0)
        val funding = fundingOf(coin, 0x54)
        val tx = Transaction(
            2, listOf(TxIn(OutPoint(funding, 0), ByteVector.empty, GroupSpend.MAX_SEQUENCE)),
            listOf(TxOut(Satoshi(30_000), elsewhere), TxOut(Satoshi(19_000), notChange.pubkeyScript)), 0,
        )
        val proposed = GroupSpend.psbtOf(tx, listOf(funding.txOut[0] to coin), mapOf(1 to notChange), account)
        val received = GroupSpend.decode(GroupSpend.encode(proposed))!!
        assertIs<GroupSpend.Result.Refused>(GroupSpend.check(received, account))
    }
}
