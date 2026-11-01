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
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.managers.MemberWalletManager.Refusal
import com.machankura.dedun.compose.managers.MemberWalletManager.asEvent
import com.machankura.dedun.compose.nostr.wallet.GroupKeyNoticeEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A member asking a group to guard a wallet: the key stored before anything
 * leaves the device, the request one every signer will accept, and each reason
 * the screen gives for not asking.
 */
class MemberWalletRequestJvmTest {
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

    /** The group as a member who holds no share sees it: a Marmot room, and nothing to sign with. */
    private suspend fun asMember(knowsKey: Boolean = true) {
        signingRoom(db, group, members = listOf(memberPublicKey), holdsShare = false)
        if (knowsKey) fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)
    }

    private suspend fun requestsSent(): List<Event> =
        db.marmotInnerEventDao().getByChatRoomAndKinds(groupRoom, listOf(MemberWalletRequestEvent.KIND))
            .mapNotNull { MemberWalletRequestEvent.carried(it.asEvent()) }

    @Test
    fun `the key is stored and the request one every signer will accept`(): Unit = runBlocking {
        asMember()
        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = "Savings")

        assertEquals(wallet, db.memberWalletDao().getById(wallet.walletId))
        assertEquals(memberPublicKey, wallet.ownerPublicKey)
        assertEquals(groupRoom, wallet.guardingChatRoomId)
        assertEquals("Savings", wallet.label)
        assertEquals(wallet.walletId, MemberWalletDerivation.walletId(PrivateKey.fromHex(wallet.walletPrivateKey).publicKey()))

        val sent = requestsSent().single()
        val verified = assertNotNull(MemberWalletRequestEvent.verified(sent))
        assertEquals(wallet.walletId, verified.reading.walletId)
        assertEquals(SubgroupManager.grantFor(db, groupRoom, thresholdKeyOf(group), wallet.walletId), verified.reading.guardians)
        assertEquals(2, verified.reading.guardians.size, "the group, and its parent one step further")

        // Exactly what a signer's device will be shown, and it is signable.
        val template = MemberWalletCertificateEvent.template(sent)
        val proposed = Event(MemberWalletManager.certificateIdFor(groupRoom, sent), groupRoom, template.createdAt, template.kind, template.tags, template.content, "")
        assertNull(MemberWalletManager.refuseCertificate(db, groupRoom, thresholdKeyOf(group), proposed))

        assertTrue(db.frostSigningSessionDao().getSessionsForChatRoom(groupRoom).isEmpty(), "a member without a share waits for a signer")
        assertEquals(listOf(wallet.walletId), MemberWalletManager.awaitingSponsor(db, groupRoom).map { it.reading.walletId })
    }

    @Test
    fun `two requests are two keys`(): Unit = runBlocking {
        asMember()
        val first = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        val second = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        assertTrue(first.walletId != second.walletId)
        assertTrue(first.walletPrivateKey != second.walletPrivateKey)
    }

    @Test
    fun `a member who holds a share puts it to the group at once`(): Unit = runBlocking {
        // A share-holding member: one of the ceremony's participants on this device.
        signingRoom(db, group, members = listOf(memberPublicKey))
        fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)

        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)

        val session = assertNotNull(MemberWalletManager.openSessionFor(db, groupRoom, Event.fromJson(wallet.requestJson)))
        assertNotNull(session.signApprovedAt, "proposing is approving")
        assertTrue(MemberWalletManager.awaitingSponsor(db, groupRoom).isEmpty())
    }

    @Test
    fun `each refusal holds`(): Unit = runBlocking {
        assertEquals(Refusal.CANNOT_SIGN, MemberWalletManager.refusal(db, groupRoom, canSign = false, chain))
        assertEquals(Refusal.NOT_A_GROUP, MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))

        GroupFixtures.seedRoom(db, groupRoom)
        assertEquals(Refusal.NOT_A_GROUP, MemberWalletManager.refusal(db, groupRoom, canSign = true, chain), "a room with no MLS state")

        db.chatRoomDao().delete(db.chatRoomDao().findChatRoomById(groupRoom)!!.chatRoom)
        asMember(knowsKey = false)
        assertEquals(Refusal.KEY_UNKNOWN, MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))
        assertFailsWith<IllegalStateException> { MemberWalletManager.request(db, groupRoom, member, chain, label = null) }
        assertTrue(db.memberWalletDao().getByOwner(memberPublicKey).isEmpty(), "a refused request makes no key")

        // Offered on mainnet since the gate lifted -- the refusal is the room's, not
        // the network's -- and a member's own spend there still held to the cap.
        assertTrue(com.machankura.dedun.compose.wallet.GroupWalletRollout.offersMemberWalletsOn(Chain.Mainnet))
        assertEquals(com.machankura.dedun.compose.wallet.GroupWalletRollout.MAINNET_CAP, com.machankura.dedun.compose.wallet.GroupWalletRollout.memberCapFor(Chain.Mainnet))
        assertNull(com.machankura.dedun.compose.wallet.GroupWalletRollout.memberCapFor(Chain.Signet))
        assertEquals(Refusal.KEY_UNKNOWN, MemberWalletManager.refusal(db, groupRoom, canSign = true, Chain.Mainnet))
    }

    @Test
    fun `a member with no key state can ask once an answer is in the room`(): Unit = runBlocking {
        asMember(knowsKey = false)
        val answer = GroupKeyNoticeEvent.GroupKey(groupRoom, thresholdKeyOf(group), guardedCertificate(parent, group))
        val tags = GroupKeyNoticeEvent.answeringTags("e".repeat(64), answer, GroupFixtures.path)
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(id = "f".repeat(64), publicKey = "5".repeat(64), kind = GroupKeyNoticeEvent.KIND, createdAt = Instant.fromEpochSeconds(1_700_000_000), tags = tags, content = answer.thresholdPublicKey, chatRoomId = groupRoom)
        )

        assertNull(MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))
        assertEquals(2, MemberWalletManager.reachOf(db, groupRoom))
        // The group's own leaf at the default, and the parent's at the delay it
        // chose for the group -- the default too, here. docs/guardian-delay.md.
        assertEquals(listOf(GuardianDerivation.DEFAULT_DELAY, GuardianDerivation.DEFAULT_DELAY), MemberWalletManager.delaysOf(db, groupRoom))
        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        assertNotNull(MemberWalletRequestEvent.verified(Event.fromJson(wallet.requestJson)))
    }

    @Test
    fun `a key is discarded only before a group guards its wallet`(): Unit = runBlocking {
        asMember()
        val unasked = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        MemberWalletManager.discard(db, unasked.walletId)
        assertNull(db.memberWalletDao().getById(unasked.walletId))

        val guarded = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        val template = MemberWalletCertificateEvent.template(Event.fromJson(guarded.requestJson))
        val signed = signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signed, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))

        assertFailsWith<IllegalStateException> { MemberWalletManager.discard(db, guarded.walletId) }
        assertNotNull(db.memberWalletDao().getById(guarded.walletId))
    }
}
