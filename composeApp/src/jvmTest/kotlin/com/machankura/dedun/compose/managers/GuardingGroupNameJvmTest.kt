package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.managers.GroupFixtures.birthCertificate
import com.machankura.dedun.compose.managers.GroupFixtures.fileKeyState
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.signingRoom
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.managers.GuardianManager.GuardedSubject
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The names a member's wallet is shown under in a group above it: the group
 * that directly guards it, and the branch of the holder's tree it sits under --
 * each from a birth certificate this device can check, whoever handed it over,
 * and never from one it cannot. `docs/member-wallet-guarding-group.md`, Phase 2.
 *
 * The tree is R ⊃ P ⊃ M, and M guards Alice's Savings.
 */
class GuardingGroupNameJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val r = keyMaterial("7007")
    private val p = keyMaterial("9a9e")
    private val m = keyMaterial("6a0b")
    private val stranger = keyMaterial("57a9")
    private val rRoom = roomOf(r)
    private val pRoom = roomOf(p)
    private val mRoom = roomOf(m)

    private val member = privateKey(0x3e3be7)
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletId = MemberWalletDerivation.walletId(walletPrivateKey.publicKey())

    private val pGrant = GuardianDerivation.grant(thresholdKeyOf(r), rRoom, emptyList(), pRoom)
    private val mGrant = GuardianDerivation.grant(thresholdKeyOf(p), pRoom, pGrant, mRoom)
    private val savingsGrant = GuardianDerivation.grant(thresholdKeyOf(m), mRoom, mGrant, walletId)

    /** R's certificate for P, and P's for M, as their founding named them. */
    private val pCertificate = birthCertificate(r, p, pGrant, name = "#P")
    private val mCertificate = birthCertificate(p, m, mGrant, name = "#M")

    /** M's certificate for Alice's Savings, guarded by `[M, P, R]`. */
    private val savings: Event = run {
        val request = MemberWalletRequestEvent.build(
            memberPrivateKey = member,
            groupChatRoomId = mRoom,
            walletPrivateKey = walletPrivateKey,
            account = MemberWalletDerivation.account(walletPrivateKey.publicKey(), chain, savingsGrant),
            label = "Savings",
            createdAt = 1_700_000_000,
        )
        val template = MemberWalletCertificateEvent.template(request)
        signedAsRoom(m, template.kind, template.tags, template.content, template.createdAt)
    }

    /** This device in [material]'s room, holding its key state. */
    private suspend fun inRoom(material: KeyMaterial, certificate: Event? = null, subject: String = "#" + roomOf(material).take(4)) {
        signingRoom(db, material, members = listOf(memberPublicKey), subject = subject)
        fileKeyState(db, material, certificate)
    }

    /** [certificate] as one of [signer]'s own signed events here. */
    private suspend fun fileSigned(certificate: Event, signer: String) =
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(certificate, chatRoomId = signer, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))

    /** A notice of [statement] told to [room], carrying [certificate] as today's build does, or not as an older one did. */
    private suspend fun noticeIn(room: String, statement: Event, certificate: Event? = null) =
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = statement.id.take(60) + if (certificate == null) "0000" else "0001",
                publicKey = memberPublicKey,
                kind = GuardedWalletNoticeEvent.KIND,
                createdAt = Instant.fromEpochSeconds(1_700_000_100),
                tags = listOfNotNull(arrayOf("h", room), certificate?.let(GuardedWalletNoticeEvent::certificateTag)).toTypedArray(),
                content = statement.toJson(),
                chatRoomId = room,
            )
        )

    private suspend fun heldByR(): GuardedSubject.Member {
        val listed = GuardianManager.guardedWalletsOf(db, rRoom).single()
        assertEquals(mRoom, listed.directGuardianChatRoomId)
        return assertIs<GuardedSubject.Member>(listed.subject)
    }

    @Test
    fun `a device in R and P names M from the certificate P signed`(): Unit = runBlocking {
        inRoom(r)
        inRoom(p, pCertificate)
        fileSigned(pCertificate, signer = rRoom)
        fileSigned(mCertificate, signer = pRoom)
        noticeIn(rRoom, savings)

        val subject = heldByR()
        assertEquals("#M", subject.guardianName)
        assertEquals("#P", subject.branchName)
    }

    @Test
    fun `a device in R only knows its own branch, and not the group below it`(): Unit = runBlocking {
        inRoom(r)
        fileSigned(pCertificate, signer = rRoom)
        noticeIn(rRoom, savings)

        val subject = heldByR()
        assertNull(subject.guardianName)
        assertEquals("#P", subject.branchName)
    }

    @Test
    fun `a certificate for M that P did not sign names nothing`(): Unit = runBlocking {
        inRoom(r)
        inRoom(p, pCertificate)
        fileSigned(pCertificate, signer = rRoom)
        // Filed under P, signed by a stranger: it does not certify M as P's child.
        fileSigned(birthCertificate(stranger, m, mGrant, name = "#Impostor"), signer = pRoom)
        noticeIn(rRoom, savings)

        assertNull(heldByR().guardianName)
    }

    @Test
    fun `a renamed M is still shown by its founding name`(): Unit = runBlocking {
        inRoom(r)
        inRoom(m, mCertificate, subject = "#Renamed")
        fileSigned(pCertificate, signer = rRoom)
        noticeIn(rRoom, savings)

        assertEquals("#M", heldByR().guardianName)
    }

    @Test
    fun `with no certificate to check, the room's name here is used`(): Unit = runBlocking {
        inRoom(r)
        inRoom(m, subject = "#Here")
        noticeIn(rRoom, savings)

        assertEquals("#Here", heldByR().guardianName)
    }

    // ---- Phase 3: the notice carries the guardian's certificate ----------

    @Test
    fun `a notice carrying M's certificate lets a device in R only name M`(): Unit = runBlocking {
        inRoom(r)
        noticeIn(rRoom, savings, certificate = mCertificate)

        val listed = GuardianManager.guardedWalletsOf(db, rRoom).single()
        assertEquals("#M", assertIs<GuardedSubject.Member>(listed.subject).guardianName)
        assertEquals(mCertificate.id, listed.guardianCertificate?.id)
    }

    @Test
    fun `a carried certificate that does not certify M is ignored, and the wallet is still listed`(): Unit = runBlocking {
        inRoom(r)
        // P's own certificate, naming P rather than M; and one for M a stranger signed.
        noticeIn(rRoom, savings, certificate = pCertificate)
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = "ab".repeat(32),
                publicKey = memberPublicKey,
                kind = GuardedWalletNoticeEvent.KIND,
                createdAt = Instant.fromEpochSeconds(1_700_000_200),
                tags = arrayOf(arrayOf("h", rRoom), GuardedWalletNoticeEvent.certificateTag(birthCertificate(stranger, m, mGrant, name = "#Impostor"))),
                content = savings.toJson(),
                chatRoomId = rRoom,
            )
        )

        val listed = GuardianManager.guardedWalletsOf(db, rRoom).single()
        assertNull(assertIs<GuardedSubject.Member>(listed.subject).guardianName)
        assertNull(listed.guardianCertificate)
    }

    @Test
    fun `a room told before notices carried the certificate is offered again, once`(): Unit = runBlocking {
        inRoom(m, mCertificate)
        inRoom(p)
        fileSigned(savings, signer = mRoom)
        noticeIn(pRoom, savings)

        val again = GuardianManager.shareableFrom(db, mRoom, chain).single()
        assertEquals(listOf(pRoom), again.rooms.map { it.chatRoom.id })
        assertEquals(mCertificate.id, again.certificate?.id)

        GuardianManager.share(db, again.statement, again.rooms, memberPublicKey, certificate = again.certificate)
        assertEquals(emptyList(), GuardianManager.shareableFrom(db, mRoom, chain))
    }

    @Test
    fun `with no certificate to add, a room already told is not offered again`(): Unit = runBlocking {
        inRoom(m)
        inRoom(p)
        fileSigned(savings, signer = mRoom)
        noticeIn(pRoom, savings)

        assertEquals(emptyList(), GuardianManager.shareableFrom(db, mRoom, chain))
    }

    @Test
    fun `forwarding from M to P and on to R carries the certificate at both hops`(): Unit = runBlocking {
        inRoom(m, mCertificate)
        inRoom(p)
        fileSigned(savings, signer = mRoom)

        val first = GuardianManager.shareableFrom(db, mRoom, chain).single()
        GuardianManager.share(db, first.statement, first.rooms, memberPublicKey, certificate = first.certificate)

        // R joins this device later, so the second hop is P's to make.
        inRoom(r)
        val second = GuardianManager.shareableFrom(db, pRoom, chain).single()
        assertEquals(listOf(rRoom), second.rooms.map { it.chatRoom.id })
        GuardianManager.share(db, second.statement, second.rooms, memberPublicKey, certificate = second.certificate)

        listOf(pRoom, rRoom).forEach { room ->
            val notice = db.marmotInnerEventDao().getByChatRoomAndKinds(room, listOf(GuardedWalletNoticeEvent.KIND)).single()
            assertEquals(mCertificate.id, GuardedWalletNoticeEvent.certificateIn(notice.tags)?.id, "the notice in $room")
        }
    }

    @Test
    fun `forwarding on its own carries the certificate too, and tells a room told without it once more`(): Unit = runBlocking {
        inRoom(m, mCertificate)
        inRoom(p)
        inRoom(r)
        fileSigned(savings, signer = mRoom)
        // R was told before notices carried the certificate.
        noticeIn(rRoom, savings)

        val told = GuardianManager.forward(db, savings, mRoom, whenElected = false)
        assertEquals(setOf(pRoom, rRoom), told.map { it.chatRoom.id }.toSet())
        listOf(pRoom, rRoom).forEach { room ->
            val carried = db.marmotInnerEventDao().getByChatRoomAndKinds(room, listOf(GuardedWalletNoticeEvent.KIND))
                .mapNotNull { GuardedWalletNoticeEvent.certificateIn(it.tags) }
            assertEquals(listOf(mCertificate.id), carried.map { it.id }, "the notice in $room")
        }
        assertEquals(emptyList(), GuardianManager.forward(db, savings, mRoom, whenElected = false), "told once with it")
    }
}
