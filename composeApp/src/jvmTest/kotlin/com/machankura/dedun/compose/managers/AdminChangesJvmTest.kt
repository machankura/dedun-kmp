package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.GroupEventResult
import com.vitorpamplona.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.marmot.groups.isLocalAdmin
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.exceptions.MarmotCommitPendingException
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.ui.view.state.AdminLeave
import com.machankura.dedun.compose.ui.view.state.MemberAction
import com.machankura.dedun.compose.ui.view.state.MemberActionBlocker
import com.machankura.dedun.compose.ui.view.state.MemberActionOption

/**
 * Promoting and demoting in a convenient room, which has no key to sign with and
 * so is governed by one admin's commit. docs/member-admin-promotion.md, Phase 6.
 *
 * The app creates the room the way the room-type screen does and administers it;
 * a stand-in is the other member. Every commit goes through the publish gate and
 * the relay's OK is played by hand, as in GroupLifecycleJvmTest.
 */
class AdminChangesJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Dispatchers.Default)
    private val chatRepository = DatabaseChatRepository(db, scope)
    private val nostrRepository = DatabaseNostrRepository(db, scope)

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()
    private val roomId = KeyPair().pubKey.toHexKey()
    private val member = MarmotStandIn()
    private lateinit var routingId: String

    private suspend fun seedProfile(publicKey: String, name: String) {
        val eventId = publicKey.take(32).padEnd(64, 'f')
        db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = name, nostrEventId = eventId))
    }

    private suspend fun room() = db.chatRoomDao().findChatRoomById(roomId)!!
    private suspend fun adminLines() = db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }
        .filter { it.messageType == ChatMessage.TYPE_ADMIN_ADDED || it.messageType == ChatMessage.TYPE_ADMIN_REMOVED }
    private suspend fun appGroup() = db.marmotEngine.groupManager.getGroup(routingId)!!

    /** A convenient room, made as `SelectChatRoomTypeViewModel` makes one, with the stand-in in it. */
    private suspend fun convenientRoomWithMember() {
        seedProfile(user, "us")
        seedProfile(member.hex, "Dana")
        val created = MarmotGroupFactory.create(
            creator = keyPair, roomId = roomId, name = "#room", description = null,
            adminPublicKeys = listOf(user), relays = Relays.DefaultDMRelayList.map { it.url },
            profile = MarmotProfile.CURRENT,
        )
        routingId = created.routingId
        assertNotNull(
            chatRepository.getOrCreateChatRoom(
                chatRoomId = roomId, activeUserPublicKey = user, relayHint = null, defaultSubject = "#room",
                description = null, mlsGroupState = created.group.saveState().encodeTls().toHex(),
            )
        )
        chatRepository.recordMarmotRouting(chatRoomId = roomId, routingId = routingId)
        chatRepository.reconcileAdmins(roomId)
        invite()
    }

    /** Invites the stand-in with a fresh key package and has it join from the Welcome. */
    private suspend fun invite() {
        val earlier = db.giftWrapPayloadDao().getAllGiftWrapUnsigned().map { it.id }.toSet()
        member.makeBundle()
        chatRepository.inviteMember(
            room(), member.hex,
            MarmotKeyPackage(
                id = member.hex + db.giftWrapPayloadDao().getAllGiftWrapUnsigned().size, publicKey = member.hex,
                tlsEncodedMarmotKeyPackage = member.bundle.keyPackage.toTlsBytes(), profile = MarmotProfile.CURRENT.wireName,
            ),
        )
        val welcome = db.giftWrapPayloadDao().getAllGiftWrapUnsigned().single { it.kind == WelcomeEvent.KIND && it.id !in earlier }
        val welcomeEvent = WelcomeEvent(id = welcome.id, pubKey = user, createdAt = welcome.createdAt.epochSeconds, tags = welcome.tags, content = welcome.content, sig = "")
        member.manager.processWelcome(Base64.decode(welcomeEvent.welcomeBase64()), member.bundle)
        member.inbound.trackGroup(routingId)
    }

    /** The relay's OK on the newest commit this device put on the wire, which the stand-in then applies. */
    private suspend fun okTheLatestCommit(): GroupEvent {
        val event = db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 50)
            .filter { it.kind == GroupEvent.KIND }
            .filter { db.marmotCommitResultDao().getMarmotCommitRequestById(it.id) != null }
            .first { db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(it.id)?.status == "pending" }
        val request = db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(event.id)!!
        nostrRepository.broadcastProcessed(request, "published")
        val groupEvent = event.toGroupEvent(user)!!
        if (member.manager.isMember(routingId)) member.inbound.processGroupEvent(groupEvent)
        return groupEvent
    }

    @Test
    fun `an admin makes a member an admin, every device agrees, and the room says it once`() = runBlocking {
        convenientRoomWithMember()

        chatRepository.promoteMember(room(), member.hex)
        assertFalse(member.hex in appGroup().currentAdminIdentities(), "applied before any relay accepted it")
        val commit = okTheLatestCommit()

        assertTrue(member.hex in appGroup().currentAdminIdentities())
        assertTrue(member.manager.getGroup(routingId)!!.isLocalAdmin(), "the promotion did not reach the member")
        assertEquals(listOf("Dana is now an admin"), adminLines().map { it.content })

        // The relay hands the app its own commit back: nothing more is said.
        MarmotStandIn.storeInApp(db, keyPair, commit)
        assertEquals(1, adminLines().size, "the echo of our own commit captioned it again")
    }

    @Test
    fun `a promoted member can govern, and a demoted one cannot`() = runBlocking {
        convenientRoomWithMember()
        chatRepository.promoteMember(room(), member.hex)
        okTheLatestCommit()

        chatRepository.demoteMember(room(), member.hex)
        okTheLatestCommit()

        assertEquals(setOf(user), appGroup().currentAdminIdentities())
        assertFalse(member.manager.getGroup(routingId)!!.isLocalAdmin())
        assertFailsWith<IllegalStateException>("a demoted member's own library let it change the admin list") {
            member.manager.stageAppDataUpdate(routingId, AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(listOf(member.hex)).encode())
        }
        assertTrue(adminLines().any { it.messageType == ChatMessage.TYPE_ADMIN_REMOVED && it.content == "Dana is no longer an admin" })
    }

    @Test
    fun `the only admin cannot step down, and is told what to do instead`() = runBlocking {
        convenientRoomWithMember()

        val refusal = assertFailsWith<IllegalStateException> { chatRepository.demoteMember(room(), user) }

        assertEquals("You're the only admin. Make someone else an admin first", refusal.message)
        assertTrue(db.marmotPublishObligationDao().getAll().isEmpty(), "a refused change still raised an obligation")
    }

    @Test
    fun `with another admin, an admin can step down`() = runBlocking {
        convenientRoomWithMember()
        chatRepository.promoteMember(room(), member.hex)
        okTheLatestCommit()

        chatRepository.demoteMember(room(), user)
        okTheLatestCommit()

        assertEquals(setOf(member.hex), appGroup().currentAdminIdentities())
        assertFalse(appGroup().isLocalAdmin())
        assertTrue(adminLines().any { it.content == "You are no longer an admin" })
    }

    @Test
    fun `a change while another is waiting for a relay is refused in words`() = runBlocking<Unit> {
        convenientRoomWithMember()
        chatRepository.promoteMember(room(), member.hex)

        // Asked again before the first is accepted: the gate holds one commit at a time.
        assertFailsWith<MarmotCommitPendingException> { chatRepository.promoteMember(room(), member.hex) }
    }

    /**
     * The hole this phase closes. A bare Remove left the admin's key on the list,
     * so inviting them back made them an admin again with nobody having said so.
     */
    @Test
    fun `a removed admin who is invited back is not an admin`() = runBlocking {
        convenientRoomWithMember()
        chatRepository.promoteMember(room(), member.hex)
        okTheLatestCommit()

        chatRepository.removeMember(room(), member.hex)
        okTheLatestCommit() // the demotion; its confirmation stages the removal
        assertEquals(setOf(user), appGroup().currentAdminIdentities(), "the admin list still names them")
        okTheLatestCommit() // the removal
        assertFalse(member.hex in appGroup().currentMemberIdentities(), "the removal did not follow the demotion")

        invite()
        assertTrue(member.hex in appGroup().currentMemberIdentities())
        assertEquals(setOf(user), appGroup().currentAdminIdentities(), "re-invited, and an admin again")
    }

    @Test
    fun `the member rows offer what the group would accept, and leaving says how it will go`() = runBlocking {
        convenientRoomWithMember()

        assertEquals(mapOf(member.hex to setOf(MemberAction.MAKE_ADMIN, MemberAction.REMOVE_FROM_GROUP)), AdminGovernance.memberActions(appGroup(), user))
        assertEquals(AdminLeave.PICK_HEIR, AdminGovernance.adminLeave(appGroup(), user))
        assertEquals(emptyMap(), AdminGovernance.memberActions(member.manager.getGroup(routingId)!!, member.hex), "a member who is not an admin was offered something")

        chatRepository.promoteMember(room(), member.hex)
        okTheLatestCommit()

        assertEquals(
            mapOf(user to setOf(MemberAction.STEP_DOWN), member.hex to setOf(MemberAction.REMOVE_AS_ADMIN, MemberAction.REMOVE_FROM_GROUP)),
            AdminGovernance.memberActions(appGroup(), user),
        )
        assertEquals(AdminLeave.STEP_DOWN_FIRST, AdminGovernance.adminLeave(appGroup(), user))
    }

    /**
     * The rows' options as the screen reads them, through the repository: every
     * item is listed, and one the group would refuse says why.
     * docs/member-row-actions.md, Phase 2.
     */
    @Test
    fun `the rows' options list every action, and say why one is not open`() = runBlocking {
        convenientRoomWithMember()

        assertEquals(
            mapOf(member.hex to listOf(MemberActionOption(MemberAction.MAKE_ADMIN), MemberActionOption(MemberAction.REMOVE_FROM_GROUP))),
            chatRepository.memberActionOptions(room()),
            "the only admin's own row offered to step down, or a member's row was missing",
        )

        chatRepository.promoteMember(room(), member.hex)
        okTheLatestCommit()

        val options = chatRepository.memberActionOptions(room())
        assertEquals(listOf(MemberActionOption(MemberAction.STEP_DOWN)), options[user])
        assertEquals(
            listOf(MemberActionOption(MemberAction.REMOVE_AS_ADMIN), MemberActionOption(MemberAction.REMOVE_FROM_GROUP)),
            options[member.hex],
        )

        // Having left, nothing is open, and every item says so first.
        db.chatRoomDao().upsert(room().chatRoom.copy(leftGroupAt = kotlin.time.Clock.System.now()))
        val afterLeaving = chatRepository.memberActionOptions(room()).getValue(member.hex)
        assertTrue(afterLeaving.all { it.blockers.firstOrNull() == MemberActionBlocker.YouLeft })
    }

    @Test
    fun `an admin with another admin beside them steps down, then leaves`() = runBlocking {
        convenientRoomWithMember()
        chatRepository.promoteMember(room(), member.hex)
        okTheLatestCommit()

        chatRepository.stepDownAndLeave(room(), heir = null)
        assertEquals(null, room().chatRoom.leftGroupAt, "left before the step-down was accepted")
        okTheLatestCommit() // the step-down; its acceptance sends the leave

        assertNotNull(room().chatRoom.leftGroupAt, "the leave did not follow the step-down")
        assertEquals(setOf(member.hex), member.manager.getGroup(routingId)!!.currentAdminIdentities())
    }

    @Test
    fun `the only admin hands over to the member they pick, then leaves`() = runBlocking {
        convenientRoomWithMember()

        chatRepository.stepDownAndLeave(room(), heir = member.hex)
        okTheLatestCommit() // the promotion
        assertNull(room().chatRoom.leftGroupAt)
        okTheLatestCommit() // the step-down

        assertNotNull(room().chatRoom.leftGroupAt, "the leave did not follow the hand-over")
        assertEquals(setOf(member.hex), member.manager.getGroup(routingId)!!.currentAdminIdentities())
        assertTrue(member.manager.getGroup(routingId)!!.isLocalAdmin())
    }

    @Test
    fun `the only admin who names nobody is refused, and nothing waits`() = runBlocking {
        convenientRoomWithMember()

        assertFailsWith<IllegalStateException> { chatRepository.stepDownAndLeave(room(), heir = null) }

        assertNull(room().chatRoom.leftGroupAt)
        assertTrue(db.marmotPublishObligationDao().getAll().isEmpty())
    }

    @Test
    fun `a robust group keeps at least three admins, and never fewer than its quorum`() {
        assertEquals(3, com.machankura.dedun.compose.managers.Quorum.minimumAdmins(threshold = null))
        assertEquals(3, com.machankura.dedun.compose.managers.Quorum.minimumAdmins(threshold = 2))
        assertEquals(4, com.machankura.dedun.compose.managers.Quorum.minimumAdmins(threshold = 4))
    }

    @Test
    fun `a keyed room's floor comes from its own ceremony, a convenient room's is one`() = runBlocking {
        val keyed = GroupFixtures.signingRoom(db, GroupFixtures.keyMaterial("f1001" + "0".repeat(57) + "f6"), members = listOf("a1".repeat(32), "b2".repeat(32)))
        assertEquals(3, AdminGovernance.adminFloor(db, keyed.chatRoom.id, keyed.chatRoom.toMlsGroup()!!))

        convenientRoomWithMember()
        assertEquals(1, AdminGovernance.adminFloor(db, roomId, appGroup()))
    }

    @Test
    fun `removing an admin is not offered, or allowed, where it would break the floor`() = runBlocking {
        convenientRoomWithMember()
        chatRepository.promoteMember(room(), member.hex)
        okTheLatestCommit()

        assertTrue(AdminGovernance.keepsFloor(appGroup(), member.hex, floor = 1))
        assertFalse(AdminGovernance.keepsFloor(appGroup(), member.hex, floor = 2), "two admins down to one kept a floor of two")
        assertFalse(
            MemberAction.REMOVE_FROM_GROUP in AdminGovernance.memberActions(appGroup(), user, floor = 2)[member.hex].orEmpty(),
            "the menu offered a removal the floor forbids",
        )
    }

    @Test
    fun `a keyed room's admins are not changed by one admin's commit`() = runBlocking<Unit> {
        seedProfile(user, "us")
        val keyedRoomId = SharedKeyDerivation.marmotGroupId("02" + KeyPair().pubKey.toHexKey())
        assertIs<MarmotGroupCreation.Outcome.Created>(
            MarmotGroupCreation.create(
                database = db, chatRepository = chatRepository, groupId = keyedRoomId, name = "#keyed",
                purpose = "Admins.", adminPublicKeys = setOf(user), userPublicKey = user, keyPair = keyPair,
                profile = MarmotProfile.CURRENT,
            )
        )
        val keyedRoom = db.chatRoomDao().findChatRoomById(keyedRoomId)!!

        val refusal = assertFailsWith<IllegalStateException> { chatRepository.demoteMember(keyedRoom, user) }
        assertEquals(com.machankura.dedun.compose.database.dao.MarmotOutboundDao.KEYED_ROOM_REFUSAL, refusal.message)
        assertTrue(db.marmotPublishObligationDao().getAll().isEmpty())
        val keyedGroup = db.marmotEngine.groupFor(keyedRoom.routingId)!!
        assertEquals(AdminLeave.CANNOT, AdminGovernance.adminLeave(keyedGroup, user))
    }
}
