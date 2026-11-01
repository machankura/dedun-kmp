package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.managers.GroupFixtures.fileKeyState
import com.machankura.dedun.compose.managers.GroupFixtures.guardedCertificate
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signWith
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.managers.GuardianManager.GuardedSubject
import com.machankura.dedun.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
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
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The guardians' side of a member's wallet: the group that signed its
 * certificate lists it, a group above learns of it from a notice and lists it
 * too, each finds its own leaf in the wallet's addresses, and a spend through
 * that leaf is one the script interpreter takes once the coin is 12 blocks old.
 */
class MemberWalletGuardianJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val parent = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val stranger = keyMaterial("57a9")
    private val groupRoom = roomOf(group)
    private val parentRoom = roomOf(parent)

    private val member = privateKey(0x3e3be7)
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletId = MemberWalletDerivation.walletId(walletPrivateKey.publicKey())

    private val grant = GuardianDerivation.grant(
        thresholdKeyOf(group), groupRoom,
        GuardianDerivation.grant(thresholdKeyOf(parent), parentRoom, emptyList(), groupRoom),
        walletId,
    )

    private fun certificate(guardians: List<GuardianDerivation.Guardian> = grant): Event {
        val request = MemberWalletRequestEvent.build(
            memberPrivateKey = member,
            groupChatRoomId = groupRoom,
            walletPrivateKey = walletPrivateKey,
            account = MemberWalletDerivation.account(walletPrivateKey.publicKey(), chain, guardians),
            label = "Savings",
            createdAt = 1_700_000_000,
        )
        val template = MemberWalletCertificateEvent.template(request)
        return signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
    }

    /** This device in [material]'s room, holding its verified key state. */
    private suspend fun inRoom(material: fr.acinq.bitcoin.crypto.frost.KeyMaterial, certificate: Event? = null) {
        signingRoom(db, material, members = listOf(memberPublicKey), subject = "#" + roomOf(material).take(4))
        fileKeyState(db, material, certificate)
        GroupFixtures.openToMembers(db, material)
    }

    private suspend fun fileSigned(certificate: Event) =
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(certificate, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))

    private suspend fun noticeIn(room: String, statement: Event) =
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = statement.id.take(60) + "0000",
                publicKey = memberPublicKey,
                kind = GuardedWalletNoticeEvent.KIND,
                createdAt = Instant.fromEpochSeconds(1_700_000_100),
                tags = arrayOf(arrayOf("h", room)),
                content = statement.toJson(),
                chatRoomId = room,
            )
        )

    @Test
    fun `the group that signed it lists its member's wallet as theirs`(): Unit = runBlocking {
        GroupFixtures.seedProfile(db, memberPublicKey, name = "alice")
        inRoom(group, guardedCertificate(parent, group))
        fileSigned(certificate())

        val listed = GuardianManager.guardedWalletsOf(db, groupRoom).single()
        val subject = assertIs<GuardedSubject.Member>(listed.subject)
        assertEquals(walletId, subject.id)
        assertEquals(memberPublicKey, subject.ownerPublicKey)
        assertEquals("alice", subject.ownerName)
        assertEquals("Savings", subject.label)
        assertEquals(walletId, listed.descendantChatRoomId)
        assertEquals(listOf(walletId), listed.lineage)
        assertEquals(0, listed.position)
        assertNull(listed.name, "a member's wallet has no founding name")
        assertEquals("@alice", listed.spender().name)
    }

    @Test
    fun `a group above learns of it from a notice, and walks down to its own leaf`(): Unit = runBlocking {
        inRoom(parent)
        noticeIn(parentRoom, certificate())

        val listed = GuardianManager.guardedWalletsOf(db, parentRoom).single()
        assertEquals(listOf(groupRoom, walletId), listed.lineage)
        assertEquals(1, listed.position)
        // The notice as it was filed: a second of the same certificate would be silent.
        val filed = db.marmotInnerEventDao().getByChatRoomAndKinds(parentRoom, listOf(GuardedWalletNoticeEvent.KIND)).single()
        assertTrue(GuardianManager.describeNotice(db, parentRoom, Event(filed.id, filed.publicKey, filed.createdAt.epochSeconds, filed.kind, filed.tags, filed.content, ""))!!.startsWith("Shared @"))
    }

    @Test
    fun `a notice whose entry for this group is not the key it reaches is ignored`(): Unit = runBlocking {
        inRoom(parent)
        // The group signed a certificate naming the stranger's key under the parent's room.
        val forged = listOf(grant[0], GuardianDerivation.grant(thresholdKeyOf(stranger), parentRoom, emptyList(), groupRoom).single().grantedTo(walletId))
        noticeIn(parentRoom, certificate(forged))
        assertTrue(GuardianManager.guardedWalletsOf(db, parentRoom).isEmpty())
    }

    @Test
    fun `a member of both groups can carry it upward`(): Unit = runBlocking {
        inRoom(group, guardedCertificate(parent, group))
        inRoom(parent)
        fileSigned(certificate())

        val shareable = GuardianManager.shareableFrom(db, groupRoom, chain).single()
        assertEquals(memberPublicKey, shareable.ownerPublicKey)
        assertEquals(listOf(parentRoom), shareable.rooms.map { it.chatRoom.id })

        GuardianManager.share(db, shareable.statement, shareable.rooms, memberPublicKey, certificate = shareable.certificate)
        assertEquals(walletId, GuardianManager.guardedWalletsOf(db, parentRoom).single().descendantChatRoomId)
        assertTrue(GuardianManager.shareableFrom(db, groupRoom, chain).isEmpty(), "told once")
    }

    @Test
    fun `a guardian's spend through its leaf of a member's wallet`(): Unit = runBlocking {
        inRoom(parent)
        noticeIn(parentRoom, certificate())
        val wallet = GuardianManager.guardedWalletsOf(db, parentRoom).single()

        // One coin at receive address 2, confirmed long enough ago.
        val derived = GroupWalletDerivation.derive(wallet.account, GroupWalletDerivation.RECEIVE, 2)
        val funding = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 3 })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(60_000), derived.pubkeyScript)), 0)
        val meta = WalletState.AddressMeta.Derived(2)
        val receive = WalletState(mapOf(derived.address(chain) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(funding.txid, 0, 100, funding, meta)))))
        val change = WalletState(mapOf(GroupWalletDerivation.derive(wallet.account, GroupWalletDerivation.CHANGE, 0).address(chain) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList())))
        val state = GroupWalletState(wallet.account, receive, change, tipHeight = 200)

        val spender = wallet.spender(thresholdKeyOf(parent))
        val built = assertIs<GroupSpendBuilder.Result.Built>(GroupSpendBuilder.sendAll(state, "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080", 3L, emptySet(), spender))
        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, wallet.account, spender)).checked

        val signatures = checked.inputs.map { signWith(parent, it.cache, it.sighash) }
        val signed = checked.witnessed(signatures)
        signed.correctlySpends(checked.inputs.associate { it.outPoint to it.txOut }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assertEquals(3, signed.txIn.single().witness.stack.size, "signature, leaf, control block")

        // The member's own device names who took it.
        val taken = GuardianManager.takenIn(signed, wallet.account, mapOf(funding.let { OutPoint(it, 0) } to (funding.txOut[0] to derived)))
        assertEquals(parentRoom, taken.single().guardian.chatRoomId)

        // Eleven blocks is too soon: the same spend at sequence 11, signed properly
        // for that sequence, so the only thing wrong with it is the lock.
        val input = checked.inputs.single()
        val unsigned = checked.tx.let { tx -> Transaction(tx.version, tx.txIn.map { it.copy(sequence = 11, witness = fr.acinq.bitcoin.ScriptWitness.empty) }, tx.txOut, tx.lockTime) }
        val sighash = unsigned.hashForSigningTaprootScriptPath(0, checked.spentOutputs, fr.acinq.bitcoin.SigHash.SIGHASH_DEFAULT, input.leaf!!.hash())
        val early = unsigned.updateWitness(0, input.witness(signWith(parent, input.cache, sighash)))
        assertFails { early.correctlySpends(checked.inputs.associate { it.outPoint to it.txOut }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }
    }

    @Test
    fun `a group funds its member's wallet, and its signers read where the money goes`(): Unit = runBlocking {
        GroupFixtures.seedProfile(db, memberPublicKey, name = "alice")
        inRoom(group, guardedCertificate(parent, group))
        fileSigned(certificate())
        val wallet = GuardianManager.guardedWalletsOf(db, groupRoom).single()

        // The group's own wallet, with one coin to fund from.
        val own = GroupWalletDerivation.account(thresholdKeyOf(group), chain)
        val coin = GroupWalletDerivation.derive(own, GroupWalletDerivation.RECEIVE, 0)
        val funding = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 5 })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(90_000), coin.pubkeyScript)), 0)
        val meta = WalletState.AddressMeta.Derived(0)
        val ownState = GroupWalletState(
            own,
            WalletState(mapOf(coin.address(chain) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(funding.txid, 0, 100, funding, meta))))),
            WalletState(mapOf(GroupWalletDerivation.derive(own, GroupWalletDerivation.CHANGE, 0).address(chain) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList()))),
            tipHeight = 110,
        )

        // The member's address two past their current one.
        val target = GroupWalletDerivation.derive(wallet.account, GroupWalletDerivation.RECEIVE, 2)
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.send(ownState, GroupSpendBuilder.Destination.Funding(wallet.account, target), Satoshi(20_000), 3L, emptySet())
        )
        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, own)).checked
        val reading = assertIs<GroupSpend.FundingReading.Ok>(GroupSpend.fundingsOf(checked, listOf(wallet)))

        val funded = reading.fundings.single()
        assertEquals(walletId, funded.wallet.descendantChatRoomId)
        assertEquals(target.pubkeyScript, checked.tx.txOut[funded.outputIndex].publicKeyScript)
        val summary = com.machankura.dedun.compose.text.ProposedSpend.summarize(checked.copy(fundings = reading.fundings), chain)
        assertEquals("Fund @alice's guarded wallet “Savings”", summary.label)
    }
}
