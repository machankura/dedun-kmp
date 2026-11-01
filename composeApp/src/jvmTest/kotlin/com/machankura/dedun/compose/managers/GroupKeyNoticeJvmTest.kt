package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.managers.GroupFixtures.fileKeyState
import com.machankura.dedun.compose.managers.GroupFixtures.guardedCertificate
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.managers.MemberWalletManager.asEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.GroupKeyNoticeEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A member who holds no key state asking what the group signs with, and a member
 * who holds one answering -- two devices, two databases, the inner-event rows
 * carried from one to the other as the MLS room would carry them.
 *
 * The negative cases carry the weight: an answer is believed only for what it
 * can prove about itself, and never filed as the room's key state.
 */
class GroupKeyNoticeJvmTest {
    private val asking: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val answering: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDbs() {
        asking.close()
        answering.close()
    }

    private val parent = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val stranger = keyMaterial("57a9")
    private val groupRoom = roomOf(group)
    private val certificate = guardedCertificate(parent, group)

    private val member = KeyPair().pubKey.toHexKey()
    private val signer = KeyPair().pubKey.toHexKey()

    /** Carries every row of [kind] in the group's room from [from] to [to], as the room would. */
    private suspend fun deliver(from: DedunDatabase, to: DedunDatabase) =
        from.marmotInnerEventDao().getByChatRoomAndKinds(groupRoom, listOf(GroupKeyNoticeEvent.KIND)).forEach { to.marmotInnerEventDao().upsert(it) }

    @Test
    fun `a member with no key state asks, is answered, and learns the key and the certificate`(): Unit = runBlocking {
        fileKeyState(answering, group, certificate)

        assertNull(MemberWalletManager.groupKeyFor(asking, groupRoom))
        assertTrue(MemberWalletManager.askForGroupKey(asking, groupRoom, member))
        assertFalse(MemberWalletManager.askForGroupKey(asking, groupRoom, member), "asked and not yet answered")

        deliver(asking, answering)
        val request = answering.marmotInnerEventDao().getByChatRoomAndKinds(groupRoom, listOf(GroupKeyNoticeEvent.KIND)).single().asEvent()
        assertTrue(MemberWalletManager.answerKeyRequest(answering, groupRoom, request, signer))
        assertFalse(MemberWalletManager.answerKeyRequest(answering, groupRoom, request, signer), "answered once")

        deliver(answering, asking)
        val groupKey = assertNotNull(MemberWalletManager.groupKeyFor(asking, groupRoom))
        assertEquals(thresholdKeyOf(group), groupKey.thresholdPublicKey)
        assertEquals(certificate.id, groupKey.certificate?.id)
        assertEquals(SubgroupBirthCertificateEvent.parseGuardians(certificate.tags), groupKey.guardians)
        assertNull(groupKey.policy, "the answering device held no member-wallet policy, and says nothing about one")

        assertNull(asking.groupKeyStateDao().getByChatRoomId(groupRoom), "an answer is never filed as key state")
        assertFalse(MemberWalletManager.askForGroupKey(asking, groupRoom, member), "nothing left to ask")
    }

    /** docs/member-wallet-delay.md, Phase 3: the late member's only way to the group's policy. */
    private suspend fun askedAndAnswered(policy: Event) {
        fileKeyState(answering, group, certificate)
        GroupFixtures.filePolicy(answering, group, policy)
        MemberWalletManager.askForGroupKey(asking, groupRoom, member)
        deliver(asking, answering)
        val request = answering.marmotInnerEventDao().getByChatRoomAndKinds(groupRoom, listOf(GroupKeyNoticeEvent.KIND)).single().asEvent()
        assertTrue(MemberWalletManager.answerKeyRequest(answering, groupRoom, request, signer))
        deliver(answering, asking)
    }

    @Test
    fun `an answer carries the group's open policy to a member who joined after it`(): Unit = runBlocking {
        askedAndAnswered(GroupFixtures.memberWalletPolicy(group, 144))

        val policy = MemberWalletManager.policyFor(asking, groupRoom)
        assertEquals(144L, (policy as MemberWalletPolicyEvent.Policy.Open).delay)
    }

    @Test
    fun `an answer carries a closed policy as well`(): Unit = runBlocking {
        askedAndAnswered(GroupFixtures.memberWalletPolicy(group, null))

        assertTrue(MemberWalletManager.policyFor(asking, groupRoom) is MemberWalletPolicyEvent.Policy.Closed)
    }

    @Test
    fun `an answer carrying a policy another room signed does not read at all`() {
        val forged = GroupFixtures.memberWalletPolicy(stranger, 6)
        val tags = GroupKeyNoticeEvent.answeringTags("e".repeat(64), GroupKeyNoticeEvent.GroupKey(groupRoom, thresholdKeyOf(group), certificate), GroupFixtures.path) +
            arrayOf(arrayOf("member_wallet_policy", forged.toJson()))

        assertNull(GroupKeyNoticeEvent.read(answer(thresholdKeyOf(group), tags), groupRoom), "present and unreadable is not \"no policy\"")
    }

    @Test
    fun `a device that holds no key state answers nothing`(): Unit = runBlocking {
        MemberWalletManager.askForGroupKey(asking, groupRoom, member)
        deliver(asking, answering)
        val request = answering.marmotInnerEventDao().getByChatRoomAndKinds(groupRoom, listOf(GroupKeyNoticeEvent.KIND)).single().asEvent()

        assertFalse(MemberWalletManager.answerKeyRequest(answering, groupRoom, request, signer))
    }

    private fun answer(content: String, tags: Array<Array<String>>): Event {
        val pubKey = signer
        val id = EventHasher.hashId(pubKey, 1_700_000_000, GroupKeyNoticeEvent.KIND, tags, content)
        return Event(id, pubKey, 1_700_000_000, GroupKeyNoticeEvent.KIND, tags, content, "")
    }

    private fun answerFor(groupKey: GroupKeyNoticeEvent.GroupKey): Event =
        answer(groupKey.thresholdPublicKey, GroupKeyNoticeEvent.answeringTags("e".repeat(64), groupKey, GroupFixtures.path))

    @Test
    fun `an answer is believed only for what it proves`() {
        val honest = GroupKeyNoticeEvent.GroupKey(groupRoom, thresholdKeyOf(group), certificate)
        assertNotNull(GroupKeyNoticeEvent.read(answerFor(honest), groupRoom))

        // Another group's key does not derive this room.
        assertNull(GroupKeyNoticeEvent.read(answerFor(honest.copy(thresholdPublicKey = thresholdKeyOf(stranger))), groupRoom))
        // An answer about another room is not one about this room.
        assertNull(GroupKeyNoticeEvent.read(answerFor(honest), roomOf(stranger)))
        // A certificate for another child does not certify this one.
        val elsewhere = guardedCertificate(parent, stranger)
        assertNull(GroupKeyNoticeEvent.read(answerFor(honest.copy(certificate = elsewhere)), groupRoom))
        // A certificate whose signature is not the parent's.
        val forged = Event(certificate.id, certificate.pubKey, certificate.createdAt, certificate.kind, certificate.tags, certificate.content, "0".repeat(128))
        assertNull(GroupKeyNoticeEvent.read(answerFor(honest.copy(certificate = forged)), groupRoom))
        // The asking shape is not an answer.
        assertNull(GroupKeyNoticeEvent.read(answer("", GroupKeyNoticeEvent.askingTags(groupRoom)), groupRoom))
        // A root group's answer, with no certificate, reads with no guardians.
        val root = GroupKeyNoticeEvent.read(answerFor(GroupKeyNoticeEvent.GroupKey(roomOf(parent), thresholdKeyOf(parent), null)), roomOf(parent))
        assertEquals(emptyList(), assertNotNull(root).guardians)
    }

    @Test
    fun `an answer that does not check out leaves the member asking`(): Unit = runBlocking {
        val forged = answerFor(GroupKeyNoticeEvent.GroupKey(groupRoom, thresholdKeyOf(stranger), null))
        asking.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = forged.id,
                publicKey = forged.pubKey,
                kind = forged.kind,
                createdAt = Instant.fromEpochSeconds(forged.createdAt),
                tags = forged.tags,
                content = forged.content,
                chatRoomId = groupRoom,
            )
        )
        assertNull(MemberWalletManager.groupKeyFor(asking, groupRoom))
    }
}
