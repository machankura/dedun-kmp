package com.machankura.dedun.compose.nostr.wallet

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.managers.GroupFixtures
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.MemberWalletDerivation
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.managers.SubgroupManager
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianTag
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A group's certificate that it guards a member's wallet: believed when it is
 * exactly the group's signature over the request it carries, and never mistaken
 * for a birth certificate by anything that reads those.
 */
class MemberWalletCertificateEventJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val group = keyMaterial("6a0b")
    private val stranger = keyMaterial("57a9")
    private val groupRoom = roomOf(group)

    private val member = privateKey(0x3e3be7)
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletKey = walletPrivateKey.publicKey()
    private val walletId = MemberWalletDerivation.walletId(walletKey)

    private fun request(guardians: List<GuardianDerivation.Guardian> = GuardianDerivation.grant(thresholdKeyOf(group), groupRoom, emptyList(), walletId)) =
        MemberWalletRequestEvent.build(
            memberPrivateKey = member,
            groupChatRoomId = groupRoom,
            walletPrivateKey = walletPrivateKey,
            account = MemberWalletDerivation.account(walletKey, chain, guardians),
            label = "Savings",
            createdAt = 1_700_000_000,
        )

    private fun certified(request: Event = request(), signer: KeyMaterial = group): Event {
        val template = MemberWalletCertificateEvent.template(request)
        return signedAsRoom(signer, MemberWalletCertificateEvent.KIND, template.tags, template.content, createdAt = template.createdAt)
    }

    private fun Event.withTags(tags: Array<Array<String>>, signer: KeyMaterial = group): Event =
        signedAsRoom(signer, kind, tags, content, createdAt)

    @Test
    fun `a certificate the group signed over the request is believed`() {
        val verified = assertNotNull(MemberWalletCertificateEvent.verify(certified(), groupRoom))
        assertEquals(member.xOnlyPublicKey().value.toHex(), verified.memberPublicKey)
        assertEquals(walletId, verified.walletId)
        assertEquals("Savings", verified.label)
        assertEquals(groupRoom, verified.groupChatRoomId)
        assertTrue(verified.account.isGuarded)
    }

    @Test
    fun `two sponsors propose one event`() {
        assertEquals(certified().id, certified().id)
    }

    @Test
    fun `a certificate another room signed is refused`() {
        assertNull(MemberWalletCertificateEvent.verify(certified(signer = stranger), groupRoom))
    }

    @Test
    fun `a certificate over a request to another group is refused`() {
        // The stranger signs a request that asked the group: the request is real, the group is not the one asked.
        val strangerRoom = roomOf(stranger)
        assertNull(MemberWalletCertificateEvent.verify(certified(signer = stranger), strangerRoom))
    }

    @Test
    fun `a certificate naming another member is refused`() {
        val certificate = certified()
        val other = privateKey(0xb0b).xOnlyPublicKey().value.toHex()
        val tags = certificate.tags.map { if (it[0] == "p") arrayOf("p", other) else it }.toTypedArray()
        assertNull(MemberWalletCertificateEvent.verify(certificate.withTags(tags), groupRoom))
    }

    @Test
    fun `a guardian swapped against its request is refused`() {
        val certificate = certified()
        val swapped = GuardianDerivation.grant(thresholdKeyOf(stranger), groupRoom, emptyList(), walletId).single()
        val tags = certificate.tags.map { if (it[0] == "guardian") GuardianTag.assemble(swapped) else it }.toTypedArray()
        assertNull(MemberWalletCertificateEvent.verify(certificate.withTags(tags), groupRoom))
    }

    @Test
    fun `a member wallet certificate is never a subgroup`(): Unit = runBlocking {
        val certificate = certified()
        assertFalse(SubgroupBirthCertificateEvent.certifies(certificate, walletId, groupRoom))

        GroupFixtures.seedRoom(db, groupRoom)
        db.groupSignedEventDao().upsert(
            GroupSignedEvent.fromEvent(
                event = certificate,
                chatRoomId = groupRoom,
                derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path),
                frostSigningSessionId = null,
            )
        )
        assertEquals(emptyList(), SubgroupManager.subgroupsOf(db, groupRoom), "a reader of 30329 never sees a 30334")
    }
}
