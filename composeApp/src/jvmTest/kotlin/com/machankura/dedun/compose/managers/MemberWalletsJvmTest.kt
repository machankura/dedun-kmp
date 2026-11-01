package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.managers.GroupFixtures.fileKeyState
import com.machankura.dedun.compose.managers.GroupFixtures.guardedCertificate
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.managers.MemberWalletManager.WalletState
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A member's guarded wallets as their profile lists them: one state per wallet,
 * read off the key here, the sessions in the group's room and the group's
 * certificates -- and a wallet made on another device, which this one can
 * only watch.
 */
class MemberWalletsJvmTest {
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

    private suspend fun setUp(signer: Boolean = false) {
        signingRoom(db, group, members = listOf(memberPublicKey), holdsShare = signer)
        fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)
    }

    private suspend fun certify(request: Event) {
        val template = MemberWalletCertificateEvent.template(request)
        val signed = signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signed, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))
    }

    private suspend fun stateOf(walletId: String): WalletState? =
        MemberWalletManager.walletsOf(db, memberPublicKey).firstOrNull { it.walletId == walletId }?.state

    @Test
    fun `asked, then certified`(): Unit = runBlocking {
        setUp()
        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = "Savings")
        assertEquals(WalletState.ASKED, stateOf(wallet.walletId))

        certify(Event.fromJson(wallet.requestJson))
        val row = assertNotNull(MemberWalletManager.walletOf(db, memberPublicKey, wallet.walletId))
        assertEquals(WalletState.OPEN, row.state)
        assertTrue(row.isCertified)
        assertTrue(row.isMember)
        assertEquals("Savings", row.label)
        assertEquals(groupRoom, row.groupRoom?.chatRoom?.id)
        assertEquals(2, row.account.guardians.size)
        assertNotNull(MemberWalletManager.exportKey(db, memberPublicKey, wallet.walletId))
    }

    @Test
    fun `signing, then not signed`(): Unit = runBlocking {
        setUp(signer = true)
        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        assertEquals(WalletState.SIGNING, stateOf(wallet.walletId), "a signer puts it to the group at once")

        val session = assertNotNull(MemberWalletManager.openSessionFor(db, groupRoom, Event.fromJson(wallet.requestJson)))
        db.frostSigningSessionDao().upsert(session.copy(stage = FrostSigningStage.FAILED))
        assertEquals(WalletState.NOT_SIGNED, stateOf(wallet.walletId))
    }

    @Test
    fun `a wallet made on another device is listed to watch`(): Unit = runBlocking {
        setUp()
        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        certify(Event.fromJson(wallet.requestJson))
        // As the other device sees it: the certificate, and no key.
        db.memberWalletDao().delete(wallet.walletId)

        val row = assertNotNull(MemberWalletManager.walletOf(db, memberPublicKey, wallet.walletId))
        assertEquals(WalletState.WATCH_ONLY, row.state)
        assertNull(MemberWalletManager.exportKey(db, memberPublicKey, wallet.walletId), "no key to export here")
    }

    @Test
    fun `somebody else's wallets are not listed`(): Unit = runBlocking {
        setUp()
        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        certify(Event.fromJson(wallet.requestJson))
        assertTrue(MemberWalletManager.walletsOf(db, privateKey(0xb0b).xOnlyPublicKey().value.toHex()).isEmpty())
    }

    @Test
    fun `a member who left keeps the wallet, and is told`(): Unit = runBlocking {
        setUp()
        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        certify(Event.fromJson(wallet.requestJson))
        db.participantDao().delete(db.participantDao().findParticipantsByChatRoomId(groupRoom).filter { it.participantPublicKey == memberPublicKey })

        val row = assertNotNull(MemberWalletManager.walletOf(db, memberPublicKey, wallet.walletId))
        assertFalse(row.isMember)
        assertEquals(WalletState.OPEN, row.state)
    }
}
