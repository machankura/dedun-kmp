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
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import com.machankura.dedun.compose.text.ProposedEvent
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
 * A group's signers checking a member wallet certificate before they are asked
 * to approve it, and a signer putting a member's request to the group.
 *
 * The group is itself a guarded subgroup, so a member's wallet under it carries
 * two leaves -- the group's and its parent's, one step further -- and every
 * refusal below is one way of getting that list wrong.
 */
class MemberWalletSigningJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val parent = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val stranger = keyMaterial("57a9")
    private val groupRoom = roomOf(group)

    private val member = privateKey(0x3e3be7)
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()
    private val outsider = privateKey(0x07575)
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletId = MemberWalletDerivation.walletId(walletPrivateKey.publicKey())

    /** What the group grants the member's wallet: its own key, then its parent's, one step further. */
    private val grant: List<GuardianDerivation.Guardian> = GuardianDerivation.grant(
        thresholdKeyOf(group), groupRoom,
        GuardianDerivation.grant(thresholdKeyOf(parent), roomOf(parent), emptyList(), groupRoom),
        walletId,
    )

    private suspend fun groupRoomHere() = signingRoom(db, group, members = listOf(memberPublicKey)).also {
        fileKeyState(db, group, guardedCertificate(parent, group))
        GroupFixtures.openToMembers(db, group)
    }

    private fun request(guardians: List<GuardianDerivation.Guardian> = grant, by: PrivateKey = member, asking: String = groupRoom) =
        MemberWalletRequestEvent.build(
            memberPrivateKey = by,
            groupChatRoomId = asking,
            walletPrivateKey = walletPrivateKey,
            account = MemberWalletDerivation.account(walletPrivateKey.publicKey(), chain, guardians),
            label = "Savings",
            createdAt = 1_700_000_000,
            // Built under the group's open policy, as a member's device builds
            // it -- the one groupRoomHere() files. docs/member-wallet-delay.md.
            policy = GroupFixtures.memberWalletPolicy(group, GuardianDerivation.DEFAULT_DELAY, createdAt = 1_600_000_000).takeIf { asking == groupRoom },
        )

    /** The certificate [request] asks for, as a session would carry it -- unsigned, authored by the room. */
    private fun proposed(request: Event, room: String = groupRoom): Event {
        val template = MemberWalletCertificateEvent.template(request)
        return Event(MemberWalletManager.certificateIdFor(room, request), room, template.createdAt, template.kind, template.tags, template.content, "")
    }

    private suspend fun refusal(request: Event): String? =
        MemberWalletManager.refuseCertificate(db, groupRoom, thresholdKeyOf(group), proposed(request))

    @Test
    fun `the honest certificate is signable`(): Unit = runBlocking {
        groupRoomHere()
        assertNull(refusal(request()))
    }

    @Test
    fun `a certificate that drops the group above is refused`(): Unit = runBlocking {
        groupRoomHere()
        assertEquals("grants guardian keys this group does not", refusal(request(guardians = grant.take(1))))
    }

    @Test
    fun `a certificate whose leaves are reordered is refused`(): Unit = runBlocking {
        groupRoomHere()
        // Reordered, the group is no longer the nearest guardian, so the request itself is refused.
        assertNotNull(refusal(request(guardians = grant.reversed())))
    }

    @Test
    fun `a certificate naming another group's key is refused`(): Unit = runBlocking {
        groupRoomHere()
        val swapped = listOf(grant[0], GuardianDerivation.grant(thresholdKeyOf(stranger), roomOf(stranger), emptyList(), walletId).single())
        assertEquals("grants guardian keys this group does not", refusal(request(guardians = swapped)))
    }

    @Test
    fun `a certificate asked by somebody who is not a member is refused`(): Unit = runBlocking {
        groupRoomHere()
        assertEquals("was asked by somebody who is not a member", refusal(request(by = outsider)))
    }

    @Test
    fun `a request made of another group is refused here`(): Unit = runBlocking {
        groupRoomHere()
        val elsewhere = request(asking = roomOf(stranger), guardians = GuardianDerivation.grant(thresholdKeyOf(stranger), roomOf(stranger), emptyList(), walletId))
        assertNotNull(MemberWalletManager.refuseCertificate(db, groupRoom, thresholdKeyOf(group), proposed(elsewhere)))
    }

    @Test
    fun `an unguarded group guards its members with its own key alone`(): Unit = runBlocking {
        // A root group: no certificate, so nothing to pass down.
        signingRoom(db, group, members = listOf(memberPublicKey))
        fileKeyState(db, group, certificate = null)
        GroupFixtures.openToMembers(db, group)
        assertNull(refusal(request(guardians = grant.take(1))))
        assertEquals("grants guardian keys this group does not", refusal(request()))
    }

    private suspend fun deliverRequest(request: Event) {
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = "1".repeat(63) + "0",
                publicKey = request.pubKey,
                kind = MemberWalletRequestEvent.KIND,
                createdAt = Instant.fromEpochSeconds(request.createdAt),
                tags = arrayOf(arrayOf("h", groupRoom)),
                content = request.toJson(),
                chatRoomId = groupRoom,
            )
        )
    }

    @Test
    fun `a signer puts a request to the group, and a second sponsor finds the same session`(): Unit = runBlocking {
        val room = groupRoomHere()
        val request = request()
        deliverRequest(request)
        assertEquals(listOf(walletId), MemberWalletManager.awaitingSponsor(db, groupRoom).map { it.reading.walletId })

        val session = MemberWalletManager.putToGroup(db, room, GroupFixtures.owner, request)
        val items = db.frostSigningSessionDao().getItems(session.id)
        assertEquals(listOf(MemberWalletManager.certificateIdFor(groupRoom, request)), items.map { it.eventId })
        assertTrue(MemberWalletManager.awaitingSponsor(db, groupRoom).isEmpty(), "no longer waiting for a sponsor")

        assertEquals(session.id, MemberWalletManager.putToGroup(db, room, GroupFixtures.owner, request).id)
    }

    @Test
    fun `a sponsor refuses to put what the group would refuse`(): Unit = runBlocking {
        val room = groupRoomHere()
        assertFailsWith<IllegalStateException> {
            MemberWalletManager.putToGroup(db, room, GroupFixtures.owner, request(guardians = grant.take(1)))
        }
        assertTrue(db.frostSigningSessionDao().getSessionsForChatRoom(groupRoom).isEmpty())
    }

    @Test
    fun `a wallet the group already guards is not put to it again`(): Unit = runBlocking {
        val room = groupRoomHere()
        val request = request()
        val template = MemberWalletCertificateEvent.template(request)
        val signed = signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signed, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))

        assertEquals(listOf(walletId), MemberWalletManager.certificatesOf(db, groupRoom).map { it.walletId })
        assertFailsWith<IllegalStateException> { MemberWalletManager.putToGroup(db, room, GroupFixtures.owner, request) }
    }

    @Test
    fun `the signers read who it is for and what the group can do`() {
        val summary = ProposedEvent.summarize(proposed(request())) { if (it == memberPublicKey) "alice" else null }
        assertEquals("Guard a member's wallet", summary.label)
        assertTrue(summary.detail.startsWith("For @alice · Savings · on regtest"), summary.detail)
        // Each guardian's own delay, nearest first: both are the default here.
        assertTrue(
            summary.detail.contains("this group can take its coins after 12 blocks, about 2 hours, and the one above it after 12 blocks, about 2 hours"),
            summary.detail,
        )
    }
}
