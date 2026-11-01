package com.machankura.dedun.compose.managers

import kotlin.test.assertFalse
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import com.machankura.dedun.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.secp256k1.Hex
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.managers.GroupFixtures.filePolicy
import com.machankura.dedun.compose.managers.GroupFixtures.keyMaterial
import com.machankura.dedun.compose.managers.GroupFixtures.memberWalletPolicy
import com.machankura.dedun.compose.managers.GroupFixtures.roomOf
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.text.ProposedEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A group's policy on its members' wallets, as the group's own devices read it
 * -- `docs/member-wallet-delay.md`.
 */
class MemberWalletPolicyJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val group = keyMaterial("6a0b")
    private val stranger = keyMaterial("5e7a")
    private val groupRoom = roomOf(group)

    // ---- Phase 2: the group files it ---------------------------------------

    @Test
    fun `a group that never signed a policy has none`(): Unit = runBlocking {
        assertNull(MemberWalletManager.policyFor(db, groupRoom))
    }

    @Test
    fun `the newest stored policy decides, open or closed, either way round`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, groupRoom)
        filePolicy(db, group, memberWalletPolicy(group, 144, createdAt = 1_700_000_000))
        filePolicy(db, group, memberWalletPolicy(group, null, createdAt = 1_700_000_100))
        assertIs<MemberWalletPolicyEvent.Policy.Closed>(MemberWalletManager.policyFor(db, groupRoom))

        filePolicy(db, group, memberWalletPolicy(group, 1_008, createdAt = 1_700_000_200))
        assertEquals(1_008L, assertIs<MemberWalletPolicyEvent.Policy.Open>(MemberWalletManager.policyFor(db, groupRoom)).delay)
    }

    @Test
    fun `a policy another room signed is not this group's`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, groupRoom)
        // Filed under this room, signed by another: nothing a device can act on.
        val forged = memberWalletPolicy(stranger, 6)
        db.groupSignedEventDao().upsert(
            com.machankura.dedun.compose.database.model.GroupSignedEvent.fromEvent(forged, chatRoomId = groupRoom, derivationPath = null, frostSigningSessionId = null)
        )
        assertNull(MemberWalletManager.policyFor(db, groupRoom))
    }

    @Test
    fun `a signer reads what a policy decides, and what closing leaves alone`() {
        val opened = memberWalletPolicy(group, 144)
        assertEquals("Members' guarded wallets", ProposedEvent.summarize(opened).label)
        assertEquals("this group after 144 blocks, about 1 day · for wallets asked for from now on", ProposedEvent.summarize(opened).detail)

        val closed = memberWalletPolicy(group, null)
        assertEquals("no new ones · wallets already guarded stay guarded", ProposedEvent.summarize(closed).detail)
    }

    // ---- Phase 4: the member builds under it -------------------------------

    private val parent = keyMaterial("9a9e")
    private val chain = Chain.Regtest
    private val member = PrivateKey(ByteVector32(Hex.decode("3e3be7".padStart(64, '0'))))
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()

    private suspend fun asMember() {
        GroupFixtures.signingRoom(db, group, members = listOf(memberPublicKey), holdsShare = false)
        GroupFixtures.fileKeyState(db, group, GroupFixtures.guardedCertificate(parent, group))
    }

    @Test
    fun `a member asks only a group that has opened`(): Unit = runBlocking {
        asMember()
        assertEquals(MemberWalletManager.Refusal.NOT_OFFERED, MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))
        assertFailsWith<IllegalStateException> { MemberWalletManager.request(db, groupRoom, member, chain, label = null) }
        assertNull(MemberWalletManager.delaysOf(db, groupRoom))

        filePolicy(db, group, memberWalletPolicy(group, null))
        assertEquals(MemberWalletManager.Refusal.CLOSED, MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))
        assertFailsWith<IllegalStateException> { MemberWalletManager.request(db, groupRoom, member, chain, label = null) }
        assertTrue(db.memberWalletDao().getByOwner(memberPublicKey).isEmpty(), "a refused request makes no key")
    }

    @Test
    fun `a request is built at the group's delay and carries the policy it was built under`(): Unit = runBlocking {
        asMember()
        val policy = memberWalletPolicy(group, 4_320)
        filePolicy(db, group, policy)

        assertNull(MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))
        assertEquals(listOf(4_320L, GuardianDerivation.DEFAULT_DELAY), MemberWalletManager.delaysOf(db, groupRoom), "the parent's keeps its own")

        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        val verified = assertNotNull(MemberWalletRequestEvent.verified(Event.fromJson(wallet.requestJson)))
        assertEquals(4_320L, verified.reading.guardians.first().delay)
        assertEquals(policy.id, verified.policy?.event?.id)
    }

    @Test
    fun `a request whose leaf disagrees with the policy it carries does not check out`(): Unit = runBlocking {
        asMember()
        val walletKey = MemberWalletDerivation.generate()
        val walletId = MemberWalletDerivation.walletId(walletKey.publicKey())
        val atDefault = GuardianDerivation.grant(thresholdKeyOf(group), groupRoom, emptyList(), walletId)
        val account = MemberWalletDerivation.account(walletKey.publicKey(), chain, atDefault)
        fun build(policy: Event?) = MemberWalletRequestEvent.build(member, groupRoom, walletKey, account, label = null, policy = policy)

        assertNull(MemberWalletRequestEvent.verified(build(memberWalletPolicy(group, 4_320))), "built at 12 under a policy of 4,320")
        assertNull(MemberWalletRequestEvent.verified(build(memberWalletPolicy(group, null))), "built under a closed policy")
        assertNull(MemberWalletRequestEvent.verified(build(memberWalletPolicy(stranger, 12))), "under another group's policy")
        assertNotNull(MemberWalletRequestEvent.verified(build(memberWalletPolicy(group, 12))))
        // A request from before policies still reads: old certificates embed one.
        assertNotNull(MemberWalletRequestEvent.verified(build(null)))
    }

    // ---- Phase 5: every signer checks it -----------------------------------

    /** This device as a signer of the group, which the member is in. */
    private suspend fun asSigner() {
        GroupFixtures.signingRoom(db, group, members = listOf(memberPublicKey))
        GroupFixtures.fileKeyState(db, group, GroupFixtures.guardedCertificate(parent, group))
    }

    /** What the signer is asked to sign: the certificate for a request built at [delay] under [carried]. */
    private fun proposedUnder(carried: Event?, delay: Long): Event {
        val walletKey = MemberWalletDerivation.generate()
        val walletId = MemberWalletDerivation.walletId(walletKey.publicKey())
        val parentsGrant = GuardianDerivation.grant(thresholdKeyOf(parent), roomOf(parent), emptyList(), groupRoom)
        val guardians = GuardianDerivation.grant(thresholdKeyOf(group), groupRoom, parentsGrant, walletId, delay)
        val account = MemberWalletDerivation.account(walletKey.publicKey(), chain, guardians)
        val request = MemberWalletRequestEvent.build(member, groupRoom, walletKey, account, label = null, createdAt = 1_700_000_500, policy = carried)
        val template = MemberWalletCertificateEvent.template(request)
        return Event(MemberWalletManager.certificateIdFor(groupRoom, request), groupRoom, template.createdAt, template.kind, template.tags, template.content, "")
    }

    private suspend fun refusal(certificate: Event) =
        MemberWalletManager.refuseCertificate(db, groupRoom, thresholdKeyOf(group), certificate)

    @Test
    fun `a signer judges a request by the policy it carries, unless it holds a newer one`(): Unit = runBlocking {
        asSigner()
        val older = 1_700_000_000L
        val carried = memberWalletPolicy(group, 4_320, createdAt = older + 100)

        // Held none: a signer that missed the opening is told what it missed.
        assertNull(refusal(proposedUnder(carried, 4_320)))
        // The leaf has to have the carried delay, as every reader checks.
        assertEquals("is not a certificate this group could have been asked for", refusal(proposedUnder(carried, 12)))

        // An older closing the group has since reversed is no reason to refuse.
        filePolicy(db, group, memberWalletPolicy(group, null, createdAt = older))
        assertNull(refusal(proposedUnder(carried, 4_320)))

        // A newer delay held: the request is stale.
        filePolicy(db, group, memberWalletPolicy(group, 144, createdAt = older + 200))
        assertEquals("was asked under a delay the group has since changed", refusal(proposedUnder(carried, 4_320)))

        // A newer closing held: the group has stopped.
        filePolicy(db, group, memberWalletPolicy(group, null, createdAt = older + 300))
        assertEquals("was asked of a group that has since stopped guarding members' wallets", refusal(proposedUnder(carried, 4_320)))
    }

    @Test
    fun `a request built under no policy, or under a closed one, is refused`(): Unit = runBlocking {
        asSigner()
        filePolicy(db, group, memberWalletPolicy(group, 12))

        assertEquals("does not say the group guards members' wallets", refusal(proposedUnder(null, 12)))
        assertNotNull(refusal(proposedUnder(memberWalletPolicy(group, null), 12)), "a closed policy opens nothing")
        assertNotNull(refusal(proposedUnder(memberWalletPolicy(stranger, 12), 12)), "nor does another group's")
    }

    @Test
    fun `a stale request says why, and asking again makes a new one at the new delay`(): Unit = runBlocking {
        asMember()
        filePolicy(db, group, memberWalletPolicy(group, 12, createdAt = 1_700_000_000))
        val asked = MemberWalletManager.request(db, groupRoom, member, chain, label = "Savings")
        assertNull(MemberWalletManager.walletsOf(db, memberPublicKey).single().refusedBecause)

        filePolicy(db, group, memberWalletPolicy(group, 144, createdAt = 1_700_000_100))
        assertEquals(MemberWalletManager.Why.DELAY_CHANGED, MemberWalletManager.walletsOf(db, memberPublicKey).single().refusedBecause)

        val replacement = MemberWalletManager.askAgain(db, db.memberWalletDao().getById(asked.walletId)!!, member)
        assertTrue(replacement.walletId != asked.walletId, "a new key")
        assertNull(db.memberWalletDao().getById(asked.walletId), "the old key, never offered, is gone")
        assertEquals("Savings", replacement.label)
        val rebuilt = assertNotNull(MemberWalletRequestEvent.verified(Event.fromJson(replacement.requestJson)))
        assertEquals(144L, rebuilt.reading.guardians.first().delay)
        assertNull(MemberWalletManager.walletsOf(db, memberPublicKey).single().refusedBecause)
    }

    @Test
    fun `a request to a group that has since closed can only be discarded`(): Unit = runBlocking {
        asMember()
        filePolicy(db, group, memberWalletPolicy(group, 12, createdAt = 1_700_000_000))
        val asked = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        filePolicy(db, group, memberWalletPolicy(group, null, createdAt = 1_700_000_100))

        assertEquals(MemberWalletManager.Why.CLOSED, MemberWalletManager.walletsOf(db, memberPublicKey).single().refusedBecause)
        assertFailsWith<IllegalStateException> { MemberWalletManager.askAgain(db, db.memberWalletDao().getById(asked.walletId)!!, member) }
        assertNotNull(db.memberWalletDao().getById(asked.walletId), "a refused ask leaves the old key for the member to discard")
    }

    // ---- the picker leaves out direct chats -------------------------------

    @Test
    fun `a direct chat is not a group that can guard a wallet, a two-member group with a known key is`(): Unit = runBlocking {
        val peer = "b".repeat(64)
        val direct = keyMaterial("d1ec7")
        val shrunk = keyMaterial("5412c")
        val waiting = keyMaterial("3a17")

        // A direct chat: two people, and no key anywhere.
        GroupFixtures.signingRoom(db, direct, members = listOf(peer), holdsShare = false)
        // A group down to two members after its ceremony, whose key this device holds.
        GroupFixtures.signingRoom(db, shrunk, members = listOf(peer), holdsShare = false)
        GroupFixtures.fileKeyState(db, shrunk)
        // A group of three whose key this device has not been told yet: it may be open.
        GroupFixtures.signingRoom(db, waiting, members = listOf(peer, "c".repeat(64)), holdsShare = false)

        val listed = MemberWalletManager.groupsOf(db, GroupFixtures.owner).map { it.chatRoom.id }.toSet()
        assertEquals(setOf(roomOf(shrunk), roomOf(waiting)), listed)
        // And a direct chat's own page offers nothing about members' wallets.
        assertFalse(MemberWalletManager.couldGuard(db, db.chatRoomDao().findChatRoomById(roomOf(direct))!!))
    }
}
