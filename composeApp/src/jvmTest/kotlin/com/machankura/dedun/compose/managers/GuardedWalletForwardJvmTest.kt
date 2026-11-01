package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.managers.GroupFixtures.birthCertificate
import com.machankura.dedun.compose.managers.GroupFixtures.fileKeyState
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Telling the groups above about a guarded wallet, as a function: upward only,
 * never a group that already holds it, never as another profile, and -- when
 * a wallet has only just arrived -- only by the device elected to send at once.
 * `docs/guarded-wallet-forwarding.md`, Phase 2.
 *
 * A root `R`, its guarded subgroup `M`, and `M`'s guarded subgroup `G`, which
 * guards a member's wallet: the wallet's guardians are `G`, `M` and `R`.
 */
class GuardedWalletForwardJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val root = keyMaterial("7007")
    private val middle = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val rootRoom = roomOf(root)
    private val middleRoom = roomOf(middle)
    private val groupRoom = roomOf(group)

    /** `M`'s certificate from `R`, and `G`'s from `M` with `R`'s key passed one step further. */
    private val middleCertificate = birthCertificate(root, middle, GuardianDerivation.grant(thresholdKeyOf(root), rootRoom, emptyList(), middleRoom))
    private val groupCertificate = birthCertificate(
        middle, group,
        GuardianDerivation.grant(thresholdKeyOf(middle), middleRoom, SubgroupBirthCertificateEvent.parseGuardians(middleCertificate.tags)!!, groupRoom),
    )

    private val member = privateKey(0x3e3be7)
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletId = MemberWalletDerivation.walletId(walletPrivateKey.publicKey())

    private val certificate: Event = run {
        val grant = GuardianDerivation.grant(thresholdKeyOf(group), groupRoom, SubgroupBirthCertificateEvent.parseGuardians(groupCertificate.tags)!!, walletId)
        val request = MemberWalletRequestEvent.build(
            memberPrivateKey = member,
            groupChatRoomId = groupRoom,
            walletPrivateKey = walletPrivateKey,
            account = MemberWalletDerivation.account(walletPrivateKey.publicKey(), chain, grant),
            label = null,
            createdAt = 1_700_000_000,
        )
        val template = MemberWalletCertificateEvent.template(request)
        signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
    }

    /** A member whose key sorts above this device's own, so this device stays the elected sender. */
    private val laterMember = "f".repeat(64)

    /** This device in [material]'s room, with its key state, and [members] beside it. */
    private suspend fun inRoom(material: KeyMaterial, certificate: Event?, members: List<String> = listOf(memberPublicKey, laterMember)) {
        signingRoom(db, material, members = members, subject = "#" + roomOf(material).take(4), holdsShare = false)
        fileKeyState(db, material, certificate)
    }

    private suspend fun inAllThree() {
        inRoom(group, groupCertificate)
        inRoom(middle, middleCertificate)
        inRoom(root, null)
    }

    private suspend fun signedInGroup() =
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(certificate, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))

    private suspend fun noticesIn(chatRoomId: String): List<Event> =
        db.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GuardedWalletNoticeEvent.KIND)).mapNotNull { Event.fromJsonOrNull(it.content) }

    @Test
    fun `from the group that signed it, every group above is told, and the group is not`(): Unit = runBlocking {
        inAllThree()
        signedInGroup()

        val told = GuardianManager.forward(db, certificate, groupRoom, whenElected = false).map { it.chatRoom.id }
        assertEquals(listOf(middleRoom, rootRoom), told)
        assertEquals(listOf(certificate.id), noticesIn(middleRoom).map { it.id })
        assertTrue(noticesIn(groupRoom).isEmpty(), "the group holds what it signed")
        assertTrue(GuardianManager.told(db, groupRoom, certificate.id), "a signed event counts as told")

        assertTrue(GuardianManager.forward(db, certificate, groupRoom, whenElected = false).isEmpty(), "told once")
    }

    @Test
    fun `from a group above, only the groups above it`(): Unit = runBlocking {
        inAllThree()
        signedInGroup()
        // As though the middle group had been told by somebody else, and the
        // root not yet.
        GuardianManager.share(db, certificate, listOfNotNull(db.chatRoomDao().findChatRoomById(middleRoom)), memberPublicKey)

        assertEquals(listOf(rootRoom), GuardianManager.forward(db, certificate, middleRoom, whenElected = false).map { it.chatRoom.id })
        assertTrue(GuardianManager.forward(db, certificate, rootRoom, whenElected = false).isEmpty(), "nothing goes back down")
    }

    @Test
    fun `the button offers only what goes up`(): Unit = runBlocking {
        inAllThree()
        signedInGroup()
        // Carrying the group's birth certificate, as every share from this build
        // does: a notice without it would be offered once more, so the name
        // reaches the groups above. docs/member-wallet-guarding-group.md, D5.
        GuardianManager.share(db, certificate, listOfNotNull(db.chatRoomDao().findChatRoomById(middleRoom), db.chatRoomDao().findChatRoomById(rootRoom)), memberPublicKey, certificate = groupCertificate)

        // The root holds it as a notice, the middle group too, and the group signed it.
        assertTrue(GuardianManager.shareableFrom(db, rootRoom, chain).isEmpty(), "the root has nobody above it to tell")
        assertTrue(GuardianManager.shareableFrom(db, middleRoom, chain).isEmpty())
        assertTrue(GuardianManager.shareableFrom(db, groupRoom, chain).isEmpty())
    }

    @Test
    fun `a group above that belongs to another profile on the device is never told`(): Unit = runBlocking {
        inRoom(group, groupCertificate)
        inRoom(middle, middleCertificate)
        signedInGroup()
        val other = "0f".repeat(32)
        GroupFixtures.seedProfile(db, other)
        val middleRow = db.chatRoomDao().findChatRoomById(middleRoom)!!.chatRoom
        db.chatRoomDao().upsert(middleRow.copy(userPublicKey = other))

        assertTrue(GuardianManager.forward(db, certificate, groupRoom, whenElected = false).isEmpty())
        assertTrue(noticesIn(middleRoom).isEmpty())
    }

    @Test
    fun `when a wallet has only just arrived, only the elected device sends`(): Unit = runBlocking {
        // A member whose key sorts below this device's sits in both rooms.
        val earlier = "00".repeat(31) + "01"
        inRoom(group, groupCertificate, members = listOf(memberPublicKey, earlier))
        inRoom(middle, middleCertificate, members = listOf(earlier))
        signedInGroup()

        assertTrue(GuardianManager.forward(db, certificate, groupRoom, whenElected = true).isEmpty(), "the earlier member sends")
        assertEquals(listOf(middleRoom), GuardianManager.forward(db, certificate, groupRoom, whenElected = false).map { it.chatRoom.id }, "and the catch-up sends what is left")
    }

    @Test
    fun `the elected device sends at once`(): Unit = runBlocking {
        inRoom(group, groupCertificate)
        inRoom(middle, middleCertificate)
        signedInGroup()

        assertEquals(listOf(middleRoom), GuardianManager.forward(db, certificate, groupRoom, whenElected = true).map { it.chatRoom.id })
    }

    @Test
    fun `nothing it does not believe is forwarded anywhere`(): Unit = runBlocking {
        inAllThree()
        val forged = Event(certificate.id, certificate.pubKey, certificate.createdAt, certificate.kind, certificate.tags, certificate.content, "0".repeat(128))
        assertTrue(GuardianManager.forward(db, forged, groupRoom, whenElected = false).isEmpty())
        assertFalse(noticesIn(middleRoom).isNotEmpty())
    }

    // ---- Phase 3: on arrival, with nobody calling share -------------------

    /** [event] arriving in [chatRoomId] as the inbound path and a completed signing session both deliver it. */
    private suspend fun arrives(event: Event, chatRoomId: String) {
        com.machankura.dedun.compose.database.model.ChatMessage.applyInnerEvent(
            database = db,
            groupId = chatRoomId,
            event = event,
            marmotGroupEventId = null,
            marmotInnerEventId = null,
            senderPublicKey = chatRoomId,
            isUserMessage = false,
            createdAt = kotlin.time.Instant.fromEpochSeconds(event.createdAt),
        )
    }

    @Test
    fun `a member's certificate signed in the group reaches the root two generations up`(): Unit = runBlocking {
        inAllThree()
        signedInGroup()

        arrives(certificate, groupRoom)
        assertEquals(listOf(certificate.id), noticesIn(middleRoom).map { it.id }, "the group's devices told the middle group")
        assertEquals(listOf(certificate.id), noticesIn(rootRoom).map { it.id }, "and, being in the root too, the root")

        // The notice arriving in the middle group goes on, and not back down.
        GuardianManager.forwardOnArrival(db, noticesIn(middleRoom).single(), middleRoom)
        assertEquals(1, noticesIn(rootRoom).size, "the root was told once")
        assertTrue(noticesIn(groupRoom).isEmpty())
    }

    @Test
    fun `a notice arriving in the middle group is carried to the root`(): Unit = runBlocking {
        // This device sits in the middle group and the root, not in the group below.
        inRoom(middle, middleCertificate)
        inRoom(root, null)
        GuardianManager.share(db, certificate, listOfNotNull(db.chatRoomDao().findChatRoomById(middleRoom)), memberPublicKey)

        GuardianManager.forwardOnArrival(db, certificate, middleRoom)
        assertEquals(listOf(certificate.id), noticesIn(rootRoom).map { it.id })
        assertEquals(1, GuardianManager.guardedWalletsOf(db, rootRoom).size, "and the root lists it")
    }

    @Test
    fun `a subgroup's own guarded wallet reaches every group above it`(): Unit = runBlocking {
        inAllThree()
        val guardians = SubgroupBirthCertificateEvent.parseGuardians(groupCertificate.tags)!!
        val account = GroupWalletDerivation.account(thresholdKeyOf(group), chain, 0, guardians)
        val template = com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent.template(account, groupCertificate)
        val statement = signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)

        arrives(statement, groupRoom)
        assertEquals(listOf(statement.id), noticesIn(middleRoom).map { it.id })
        assertEquals(listOf(statement.id), noticesIn(rootRoom).map { it.id })
    }

    @Test
    fun `a plain wallet statement is told to nobody`(): Unit = runBlocking {
        inAllThree()
        val template = com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent.template(GroupWalletDerivation.account(thresholdKeyOf(middle), chain))
        arrives(signedAsRoom(middle, template.kind, template.tags, template.content, template.createdAt), middleRoom)
        assertTrue(noticesIn(rootRoom).isEmpty())
    }

    // ---- Phase 4: the catch-up ---------------------------------------------

    @Test
    fun `a certificate filed before forwarding existed is told the first time its group is opened`(): Unit = runBlocking {
        // A lower-keyed member in both rooms: on arrival this device would have
        // waited for them, and nobody sent anything.
        val earlier = "00".repeat(31) + "01"
        inRoom(group, groupCertificate, members = listOf(memberPublicKey, earlier))
        inRoom(middle, middleCertificate, members = listOf(earlier))
        signedInGroup()
        assertTrue(noticesIn(middleRoom).isEmpty())

        assertEquals(1, GuardianManager.forwardAll(db, groupRoom))
        assertEquals(listOf(certificate.id), noticesIn(middleRoom).map { it.id })
        assertEquals(0, GuardianManager.forwardAll(db, groupRoom), "and not again")
    }

    @Test
    fun `the catch-up through the repository, as opening the room runs it`(): Unit = runBlocking {
        inAllThree()
        signedInGroup()
        val repository = com.machankura.dedun.compose.database.repository.DatabaseChatRepository(db, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))

        assertEquals(2, repository.forwardGuardedWallets(groupRoom), "the middle group and the root")
        assertEquals(0, repository.forwardGuardedWallets(middleRoom), "the root has heard, and the group signed it")
    }

    // ---- Phase 5: the transcript ------------------------------------------

    private suspend fun sharedLinesIn(chatRoomId: String) =
        db.chatMessageDao().getChatMessagesByChatRoomId(chatRoomId).map { it.chatMessage }
            .filter { it.messageType == com.machankura.dedun.compose.database.model.ChatMessage.TYPE_GUARDED_WALLET_SHARED }

    /** A notice of [statement] from [forwarder], filed in [chatRoomId] as the inbound path files one. */
    private suspend fun noticeFrom(forwarder: String, chatRoomId: String, statement: Event, at: Long): Event {
        val tags = arrayOf(arrayOf("h", chatRoomId))
        val id = com.vitorpamplona.quartz.nip01Core.crypto.EventHasher.hashId(forwarder, at, GuardedWalletNoticeEvent.KIND, tags, statement.toJson())
        db.marmotInnerEventDao().upsert(
            com.machankura.dedun.compose.database.model.MarmotInnerEvent(
                id = id, publicKey = forwarder, kind = GuardedWalletNoticeEvent.KIND,
                createdAt = kotlin.time.Instant.fromEpochSeconds(at), tags = tags, content = statement.toJson(), chatRoomId = chatRoomId,
            )
        )
        return Event(id, forwarder, at, GuardedWalletNoticeEvent.KIND, tags, statement.toJson(), "")
    }

    @Test
    fun `two forwarders' notices of one wallet give the group one line`(): Unit = runBlocking {
        GroupFixtures.seedProfile(db, memberPublicKey, name = "alice")
        inRoom(middle, middleCertificate)

        val first = noticeFrom("a1".repeat(32), middleRoom, certificate, 1_700_000_100)
        assertEquals("Shared @alice's guarded wallet with this group.", GuardianManager.describeNotice(db, middleRoom, first))

        val second = noticeFrom("b2".repeat(32), middleRoom, certificate, 1_700_000_200)
        assertEquals(null, GuardianManager.describeNotice(db, middleRoom, second), "the second is filed and silent")
        assertEquals(1, GuardianManager.guardedWalletsOf(db, middleRoom).size, "and the wallet listed once")
    }

    @Test
    fun `the forwarder sees its own line in the group it told`(): Unit = runBlocking {
        GroupFixtures.seedProfile(db, memberPublicKey, name = "alice")
        inRoom(group, groupCertificate)
        inRoom(middle, middleCertificate)
        signedInGroup()

        GuardianManager.forward(db, certificate, groupRoom, whenElected = false)
        val lines = sharedLinesIn(middleRoom)
        assertEquals(1, lines.size)
        assertTrue(lines.single().isUserMessage)
        assertEquals("Shared @alice's guarded wallet with this group.", lines.single().content)
        assertTrue(sharedLinesIn(groupRoom).isEmpty(), "nothing in the group that signed it")

        // Another member's notice of the same wallet, arriving afterwards, writes nothing more.
        assertEquals(null, GuardianManager.describeNotice(db, middleRoom, noticeFrom("b2".repeat(32), middleRoom, certificate, 1_800_000_000)))
    }
}
