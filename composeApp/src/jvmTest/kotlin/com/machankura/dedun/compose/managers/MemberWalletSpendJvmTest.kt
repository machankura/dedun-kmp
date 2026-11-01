package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MemberWallet
import com.machankura.dedun.compose.managers.GroupFixtures.fileKeyState
import com.machankura.dedun.compose.managers.GroupFixtures.guardedCertificate
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.GroupSpendBuilder
import com.machankura.dedun.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
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
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A member spending their own guarded wallet alone: the spend checked against
 * the account the wallet rederives to, signed with the member's key, accepted
 * by the script interpreter, and filed -- and refused for every wallet this
 * device cannot sign for.
 */
class MemberWalletSpendJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val parent = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val groupRoom = roomOf(group)

    private val member = privateKey(0x3e3be7)
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()

    private val destination = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"

    private suspend fun certifiedWallet(): MemberWallet {
        signingRoom(db, group, members = listOf(memberPublicKey), holdsShare = false)
        fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)
        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        val template = MemberWalletCertificateEvent.template(Event.fromJson(wallet.requestJson))
        val signed = signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signed, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))
        return wallet
    }

    /** [account]'s wallet with one coin of [sats] at receive address [index], and an unused change address. */
    private fun stateWith(account: GroupWalletDerivation.Account, index: Int = 0, sats: Long = 80_000): GroupWalletState {
        val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, index.toLong())
        val funding = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 7 })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(sats), derived.pubkeyScript)), 0)
        val meta = WalletState.AddressMeta.Derived(index)
        val receive = WalletState(mapOf(derived.address(chain) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(funding.txid, 0, 100, funding, meta)))))
        val change = WalletState(
            mapOf(GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0).address(chain) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList()))
        )
        return GroupWalletState(account, receive, change, tipHeight = 110)
    }

    @Test
    fun `a member signs alone, and the network would take it`(): Unit = runBlocking {
        val wallet = certifiedWallet()
        val account = assertNotNull(MemberWalletManager.walletOf(db, memberPublicKey, wallet.walletId)).account
        val built = assertIs<GroupSpendBuilder.Result.Built>(GroupSpendBuilder.send(stateWith(account), destination, Satoshi(30_000), 3L, emptySet()))

        val signed = MemberWalletManager.signSpend(db, memberPublicKey, wallet.walletId, built.psbt, at = Instant.fromEpochSeconds(1_700_000_000))

        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, account)).checked
        signed.correctlySpends(checked.inputs.associate { it.outPoint to it.txOut }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        signed.txIn.forEach { assertEquals(1, it.witness.stack.size, "the key path: the tree stays hidden") }

        val spend = assertNotNull(db.memberWalletDao().getSpend(signed.txid.toString()))
        assertEquals(wallet.walletId, spend.walletId)
        assertNull(spend.seenAt)

        MemberWalletManager.recordChain(db, spend.txid, confirmations = 0, at = Instant.fromEpochSeconds(1_700_000_100))
        assertEquals(Instant.fromEpochSeconds(1_700_000_100), db.memberWalletDao().getSpend(spend.txid)?.seenAt)
        assertNull(db.memberWalletDao().getSpend(spend.txid)?.confirmedAt)
        MemberWalletManager.recordChain(db, spend.txid, confirmations = 2, at = Instant.fromEpochSeconds(1_700_000_700))
        assertEquals(Instant.fromEpochSeconds(1_700_000_700), db.memberWalletDao().getSpend(spend.txid)?.confirmedAt)
        assertEquals(Instant.fromEpochSeconds(1_700_000_100), db.memberWalletDao().getSpend(spend.txid)?.seenAt, "first seen stays first seen")
    }

    @Test
    fun `a spend of another wallet's coins is refused`(): Unit = runBlocking {
        val wallet = certifiedWallet()
        val other = MemberWalletDerivation.account(privateKey(0xb0b).publicKey(), chain, emptyList())
        val built = assertIs<GroupSpendBuilder.Result.Built>(GroupSpendBuilder.sendAll(stateWith(other), destination, 3L, emptySet()))
        assertFailsWith<IllegalStateException> { MemberWalletManager.signSpend(db, memberPublicKey, wallet.walletId, built.psbt) }
        assertEquals(emptyList(), db.memberWalletDao().getSpends(wallet.walletId))
    }

    @Test
    fun `nothing is signed before a group certifies the wallet, or without its key`(): Unit = runBlocking {
        signingRoom(db, group, members = listOf(memberPublicKey), holdsShare = false)
        fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)
        val asked = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        val account = assertNotNull(MemberWalletManager.walletOf(db, memberPublicKey, asked.walletId)).account
        val built = assertIs<GroupSpendBuilder.Result.Built>(GroupSpendBuilder.sendAll(stateWith(account), destination, 3L, emptySet()))
        assertFailsWith<IllegalStateException> { MemberWalletManager.signSpend(db, memberPublicKey, asked.walletId, built.psbt) }

        // Certified, and the key on another device.
        val template = MemberWalletCertificateEvent.template(Event.fromJson(asked.requestJson))
        val signed = signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signed, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))
        db.memberWalletDao().delete(asked.walletId)
        assertFailsWith<IllegalStateException> { MemberWalletManager.signSpend(db, memberPublicKey, asked.walletId, built.psbt) }
    }
}
