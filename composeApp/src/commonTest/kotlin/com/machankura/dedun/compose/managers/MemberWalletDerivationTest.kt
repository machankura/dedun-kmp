package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupWalletState
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Descriptor
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A member's guarded wallet, from the member's side: the private key reaches
 * every address the public derivation does, and a spend signed with it is one
 * the script interpreter accepts. The guardians' side -- that an ancestor signs
 * every leaf of a member's wallet -- is the last test.
 */
class MemberWalletDerivationTest {
    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletKey = walletPrivateKey.publicKey()
    private val walletId = MemberWalletDerivation.walletId(walletKey)

    private val groupKey = privateKey(0x6a0b).publicKey().toString()
    private val groupRoom = SharedKeyDerivation.marmotGroupId(groupKey)
    private val parentKey = privateKey(0x9a9e).publicKey().toString()
    private val parentRoom = SharedKeyDerivation.marmotGroupId(parentKey)

    /** The group's own grant from its parent, then the group's grant to the member's wallet: two guardians. */
    private val grant: List<GuardianDerivation.Guardian> = run {
        val groupsOwn = GuardianDerivation.grant(parentKey, parentRoom, emptyList(), groupRoom)
        GuardianDerivation.grant(groupKey, groupRoom, groupsOwn, walletId)
    }

    private val account = MemberWalletDerivation.account(walletKey, chain, grant)

    @Test
    fun `the wallet id is a hash of the key and not the key`() {
        assertEquals(64, walletId.length)
        assertNotEquals(walletKey.xOnly().value.toHex(), walletId)
        assertEquals(walletId, MemberWalletDerivation.walletId(walletKey.toString()))
        assertNotEquals(walletId, MemberWalletDerivation.walletId(privateKey(0xb0b).publicKey()))
        // A wallet id is a valid child index, as a room id is.
        GuardianDerivation.childIndex(walletId)
    }

    @Test
    fun `the private key reaches every address the public derivation does`() {
        for (change in listOf(GroupWalletDerivation.RECEIVE, GroupWalletDerivation.CHANGE)) {
            for (index in listOf(0L, 1L, 2L, 7L, 1_000L)) {
                val derived = GroupWalletDerivation.derive(account, change, index)
                val privateKey = MemberWalletDerivation.privateKeyAt(walletPrivateKey, chain, change, index)
                assertEquals(derived.internalKey, privateKey.xOnlyPublicKey(), "at $change/$index")
            }
        }
    }

    @Test
    fun `the account private key extends to the account xpub`() {
        val extended = MemberWalletDerivation.accountPrivateKey(walletPrivateKey, chain)
        assertEquals(account.extendedPublicKey, extended.extendedPublicKey)
        assertNotEquals(walletKey, extended.publicKey, "the wallet key itself is never the account key")
    }

    @Test
    fun `the descriptors carry the tree and check out`() {
        assertTrue(account.isGuarded)
        val descriptor = account.receiveDescriptor
        val (body, checksum) = descriptor.split('#')
        assertEquals(Descriptor.checksum(body), checksum)
        assertTrue(body.contains("and_v(v:pk("), "a leaf per guardian")
        assertEquals(2, Regex("older\\(12\\)").findAll(body).count())
    }

    private fun coin(index: Int, sats: Long): Pair<String, WalletState.AddressState> {
        val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, index.toLong())
        val tx = Transaction(
            2,
            listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { (index + 1).toByte() })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(sats), derived.pubkeyScript)),
            0,
        )
        val meta = WalletState.AddressMeta.Derived(index)
        return derived.address(chain) to WalletState.AddressState(meta, alreadyUsed = true, utxos = listOf(WalletState.Utxo(tx.txid, 0, 100, tx, meta)))
    }

    private fun state(vararg coins: Pair<String, WalletState.AddressState>): GroupWalletState {
        val change = WalletState(
            mapOf(
                GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0).address(chain) to
                    WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList())
            )
        )
        return GroupWalletState(account, WalletState(coins.toMap()), change, tipHeight = 110)
    }

    private fun checked(result: GroupSpendBuilder.Result): GroupSpend.Checked {
        val built = assertIs<GroupSpendBuilder.Result.Built>(result)
        return assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, account)).checked
    }

    @Test
    fun `a spend the member signs alone is one the network accepts`() {
        val spend = checked(
            GroupSpendBuilder.sendAll(state(coin(0, 60_000), coin(3, 40_000)), "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080", 5L, emptySet())
        )
        val signatures = spend.inputs.map { MemberWalletDerivation.sign(walletPrivateKey, chain, it) }
        val signed = spend.witnessed(signatures)

        assertEquals(2, signed.txIn.size)
        signed.txIn.forEach { assertEquals(1, it.witness.stack.size, "a key-path spend reveals no tree") }
        signed.correctlySpends(spend.inputs.associate { it.outPoint to it.txOut }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    @Test
    fun `another key does not sign the member's coins`() {
        val spend = checked(
            GroupSpendBuilder.sendAll(state(coin(0, 60_000)), "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080", 5L, emptySet())
        )
        assertFails { MemberWalletDerivation.sign(privateKey(0xb0b), chain, spend.inputs.single()) }
    }

    @Test
    fun `every guardian reaches its leaf in the member's wallet`() {
        // The group itself, then its parent two generations up: the walk each
        // signs a leaf with ends at the wallet id, as it would end at a room.
        val groupLineage = GuardianDerivation.lineage(grant, walletId, groupRoom)!!
        assertEquals(listOf(walletId), groupLineage)
        val parentLineage = GuardianDerivation.lineage(grant, walletId, parentRoom)!!
        assertEquals(listOf(groupRoom, walletId), parentLineage)

        listOf(groupKey to groupLineage, parentKey to parentLineage).forEachIndexed { position, (key, lineage) ->
            val signing = GuardianDerivation.signingCache(key, lineage, chain, MemberWalletDerivation.ACCOUNT, 0, 4)
            assertEquals(GuardianDerivation.leafKey(grant[position], chain, MemberWalletDerivation.ACCOUNT, 0, 4), signing.leafKey)
        }
    }

    @Test
    fun `the exported descriptors carry the private key and nothing else changes`() {
        val (receive, change) = MemberWalletDerivation.privateDescriptors(walletPrivateKey, account)
        for ((exported, public) in listOf(receive to account.receiveDescriptor, change to account.changeDescriptor)) {
            val (body, checksum) = exported.split('#')
            assertEquals(Descriptor.checksum(body), checksum)
            assertTrue(body.contains("tprv"), "a test network's private key")
            assertTrue(!body.contains(account.xpub), "the account xpub is gone")
            assertEquals(public.substringBefore('#').replace(account.xpub, ""), body.replace(Regex("tprv[1-9A-HJ-NP-Za-km-z]+"), ""))
        }
        assertFails { MemberWalletDerivation.privateDescriptors(privateKey(0xb0b), account) }
    }
}
