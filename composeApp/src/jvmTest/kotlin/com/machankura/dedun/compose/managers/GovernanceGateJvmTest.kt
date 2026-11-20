package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.marmot.groups.isLocalAdmin
import com.vitorpamplona.mls.messages.CommitResult
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlin.test.AfterTest
import com.machankura.dedun.compose.ui.view.state.GovernanceStatus
import com.machankura.dedun.compose.nostr.frost.GovernanceSupportEvent
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.nostr.frost.GovernanceCarrierEvent
import com.machankura.dedun.compose.nostr.frost.GovernanceChange
import com.machankura.dedun.compose.nostr.frost.GovernanceStatementEvent
import com.machankura.dedun.compose.nostr.frost.OwnCommitEvent

/**
 * The door a governed room's commits come through. docs/member-admin-promotion.md,
 * R2 and Phase 4a.
 *
 * Dana runs a keyed room whose state says its changes need the group's signature;
 * the app is the other member. The group's key is played by an ordinary key pair
 * whose public half is the room's id -- which is all a FROST signature by the room is,
 * once aggregated -- so these tests are about the door, not about the signing.
 */
class GovernanceGateJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))
    private val chatRepository = DatabaseChatRepository(db, CoroutineScope(Dispatchers.Default))
    private val nostrRepository = DatabaseNostrRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()
    private val dana = MarmotStandIn()
    private val roomKey = KeyPair()
    private val roomId = roomKey.pubKey.toHexKey()
    private lateinit var routingId: String

    /** Dana's keyed room, governed from its first epoch, with the app in it. */
    private suspend fun governedRoom(governed: Boolean = true) {
        MarmotStandIn.seedProfile(db, userHex, "us")
        MarmotStandIn.seedProfile(db, dana.hex, "Dana")
        val keyed = MarmotRoomIdentity.describe(SharedKeyDerivation.describe("Admins."), roomId)
        routingId = dana.createGroup("#keyed", if (governed) GovernanceMarker.describe(keyed, minimumAdmins = 1) else keyed)
        dana.inviteApp(db, marmotRepository, user, routingId, roomId = roomId)
        assertEquals(governed, GovernanceGate.isGoverned(appGroup()))
    }

    private suspend fun appGroup() = db.marmotEngine.groupFor(routingId)!!

    private suspend fun lines(type: String) =
        db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }.filter { it.messageType == type }

    /** Dana's commit making the app an admin, staged and not yet installed on her side. */
    private suspend fun stagedPromotion() =
        dana.manager.stageAppDataUpdate(routingId, AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(listOf(dana.hex, userHex)).encode())

    private suspend fun commitEvent(result: CommitResult): GroupEvent =
        dana.outbound.buildCommitEvent(routingId, result.framedCommitBytes, result.preCommitExporterSecret).signedEvent

    /** The statement [change] at [epoch], signed by [signer], carrying [commit]. */
    private fun statement(commit: GroupEvent, change: GovernanceChange, epoch: Long, signer: KeyPair = roomKey): Event =
        NostrSignerSync(signer).signNormal<Event>(
            createdAt = 1_700_000_000,
            kind = GovernanceStatementEvent.KIND,
            tags = GovernanceStatementEvent.assembleTags(roomId, epoch, change, proposer = dana.hex),
            content = commit.toJson(),
        )

    /** Dana sends [kind] with [content] into the room as an application message. */
    private suspend fun danaSays(kind: Int, content: String, tags: Array<Array<String>>) {
        val payload = MarmotAppEvent.build(pubKey = dana.hex, kind = kind, content = content, createdAt = 1_700_000_001, tags = tags).encodeToPayload()
        MarmotStandIn.storeInApp(db, user, dana.outbound.buildGroupEventFromBytes(routingId, payload).signedEvent)
    }

    private suspend fun danaCarries(statement: Event) =
        danaSays(GovernanceCarrierEvent.KIND, statement.toJson(), GovernanceCarrierEvent.assembleTags(roomId))

    @Test
    fun `a commit sent bare into a governed room is dropped, and the room says so`() = runBlocking {
        governedRoom()
        val epoch = appGroup().epoch

        val bare = dana.commitLocally(routingId) {
            val staged = stagedPromotion()
            dana.manager.installState(routingId, staged.pendingState)
            staged.result
        }
        MarmotStandIn.storeInApp(db, user, commitEvent(bare))

        assertEquals(epoch, appGroup().epoch, "the unsigned commit was applied")
        assertFalse(appGroup().isLocalAdmin())
        assertEquals(1, lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).size)
    }

    @Test
    fun `a commit inside the group's signature lands, as the very event the proposer built`() = runBlocking {
        governedRoom()
        val epoch = appGroup().epoch
        val staged = stagedPromotion()
        val commit = commitEvent(staged.result)

        danaCarries(statement(commit, GovernanceChange(admins = setOf(dana.hex, userHex)), epoch))
        dana.manager.installState(routingId, staged.pendingState)

        assertEquals(epoch + 1, appGroup().epoch, "the signed commit was not applied")
        assertTrue(appGroup().isLocalAdmin())
        assertNotNull(db.marmotGroupEventDao().getMarmotGroupEventById(commit.id), "applied as some other event than the proposer's")
        assertEquals(listOf("You are now an admin"), lines(ChatMessage.TYPE_ADMIN_ADDED).map { it.content })
        assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).isEmpty())

        // Carried again by somebody else, or repeated by a relay: not news, and not a refusal.
        danaCarries(statement(commit, GovernanceChange(admins = setOf(dana.hex, userHex)), epoch))
        assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).isEmpty())
    }

    @Test
    fun `a signature by anyone but the room is refused`() = runBlocking {
        governedRoom()
        val epoch = appGroup().epoch
        val commit = commitEvent(stagedPromotion().result)

        danaCarries(statement(commit, GovernanceChange(admins = setOf(dana.hex, userHex)), epoch, signer = dana.keyPair))

        assertEquals(epoch, appGroup().epoch)
        assertEquals(1, lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).size)
    }

    @Test
    fun `a statement that does not say what its commit does is refused`() = runBlocking {
        governedRoom()
        val epoch = appGroup().epoch
        val commit = commitEvent(stagedPromotion().result)

        // The group signed "Dana stays the only admin"; the commit makes the app one.
        danaCarries(statement(commit, GovernanceChange(admins = setOf(dana.hex)), epoch))

        assertEquals(epoch, appGroup().epoch)
        assertFalse(appGroup().isLocalAdmin())
    }

    @Test
    fun `a statement signed for another epoch is refused`() = runBlocking {
        governedRoom()
        val epoch = appGroup().epoch
        val commit = commitEvent(stagedPromotion().result)

        danaCarries(statement(commit, GovernanceChange(admins = setOf(dana.hex, userHex)), epoch + 1))

        assertEquals(epoch, appGroup().epoch)
    }

    @Test
    fun `a member's own key update needs nobody's signature, and lands in its own envelope`() = runBlocking {
        governedRoom()
        val epoch = appGroup().epoch
        val update = dana.manager.stageCommit(routingId)

        danaSays(OwnCommitEvent.KIND, commitEvent(update.result).toJson(), OwnCommitEvent.assembleTags(roomId, epoch))
        dana.manager.installState(routingId, update.pendingState)

        assertEquals(epoch + 1, appGroup().epoch, "an own key update was not applied")
        assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).isEmpty())
    }

    @Test
    fun `a change to the room cannot pass as a member's own commit`() = runBlocking {
        governedRoom()
        val epoch = appGroup().epoch

        danaSays(OwnCommitEvent.KIND, commitEvent(stagedPromotion().result).toJson(), OwnCommitEvent.assembleTags(roomId, epoch))

        assertEquals(epoch, appGroup().epoch)
        assertFalse(appGroup().isLocalAdmin())
        assertEquals(1, lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).size)
    }

    @Test
    fun `a keyed room whose state does not say so takes commits as it always did`() = runBlocking {
        governedRoom(governed = false)
        val epoch = appGroup().epoch

        val bare = dana.commitLocally(routingId) {
            val staged = stagedPromotion()
            dana.manager.installState(routingId, staged.pendingState)
            staged.result
        }
        MarmotStandIn.storeInApp(db, user, commitEvent(bare))

        assertEquals(epoch + 1, appGroup().epoch)
        assertEquals(setOf(dana.hex, userHex), appGroup().currentAdminIdentities())
    }

    // --- Phase 4b: who can follow, and the switch ---------------------------------

    private val erin = MarmotStandIn()

    /** Erin joins Dana's room, by Dana's bare commit; the app applies it. */
    private suspend fun erinJoins() {
        MarmotStandIn.seedProfile(db, erin.hex, "Erin")
        erin.makeBundle()
        val add = dana.commitLocally(routingId) { dana.manager.addMember(routingId, erin.bundle.keyPackage.toTlsBytes()) }
        erin.manager.processWelcome(assertNotNull(add.welcomeBytes), erin.bundle)
        erin.inbound.trackGroup(routingId)
        MarmotStandIn.storeInApp(db, user, commitEvent(add))
    }

    /** Dana makes [admins] the admin list by a bare commit, which Erin and the app apply. */
    private suspend fun danaSetsAdminsBare(admins: List<String>) {
        val commit = dana.commitLocally(routingId) {
            val staged = dana.manager.stageAppDataUpdate(routingId, AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(admins).encode())
            dana.manager.installState(routingId, staged.pendingState)
            staged.result
        }
        val event = commitEvent(commit)
        erin.inbound.processGroupEvent(event)
        MarmotStandIn.storeInApp(db, user, event)
    }

    /** [member] says, in the room, that their device follows signed commits. */
    private suspend fun says(member: MarmotStandIn) {
        val payload = MarmotAppEvent.build(
            pubKey = member.hex, kind = GovernanceSupportEvent.KIND, content = "", createdAt = 1_700_000_002,
            tags = GovernanceSupportEvent.assembleTags(roomId),
        ).encodeToPayload()
        MarmotStandIn.storeInApp(db, user, member.outbound.buildGroupEventFromBytes(routingId, payload).signedEvent)
    }

    private suspend fun room() = db.chatRoomDao().findChatRoomById(roomId)!!

    /** The relay's OK on the newest commit this device put on the wire. */
    private suspend fun okTheLatestCommit(): GroupEvent {
        val event = db.nostrEventDao().getMLSGroupEvents(since = kotlin.time.Instant.fromEpochSeconds(0), limit = 50)
            .filter { it.kind == GroupEvent.KIND }
            .filter { db.marmotCommitResultDao().getMarmotCommitRequestById(it.id) != null }
            .first { db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(it.id)?.status == "pending" }
        nostrRepository.broadcastProcessed(db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(event.id)!!, "published")
        return event.toGroupEvent(userHex)!!
    }

    @Test
    fun `hearing another member say it, this device says it back`() = runBlocking {
        governedRoom(governed = false)
        assertFalse(userHex in GovernanceSupport.supporters(db, roomId))

        says(dana)

        assertTrue(userHex in GovernanceSupport.supporters(db, roomId), "the app did not answer")
        assertTrue(dana.hex in GovernanceSupport.supporters(db, roomId))
    }

    @Test
    fun `the switch waits for every member, then turns the rule on for everyone`() = runBlocking {
        governedRoom(governed = false)
        erinJoins()
        danaSetsAdminsBare(listOf(dana.hex, userHex, erin.hex))
        says(dana)

        val waiting = assertIs<GovernanceStatus.Waiting>(chatRepository.governanceStatus(roomId))
        assertEquals(setOf(erin.hex), waiting.missing)
        val refusal = assertFailsWith<IllegalStateException> { chatRepository.requireSignatures(room()) }
        assertTrue("Erin" in refusal.message.orEmpty(), refusal.message)

        says(erin)
        assertEquals(GovernanceStatus.Ready(minimumAdmins = 3), chatRepository.governanceStatus(roomId))

        chatRepository.requireSignatures(room())
        val switch = okTheLatestCommit()

        assertEquals(3, GovernanceGate.minimumAdmins(appGroup()), "the rule is not on here")
        dana.inbound.processGroupEvent(switch)
        assertEquals(3, GovernanceMarker.minimumAdmins(dana.manager.getGroup(routingId)!!.roomDescription()), "the rule did not reach Dana")
        assertEquals(GovernanceStatus.Governed(3), chatRepository.governanceStatus(roomId))

        // And from here, Dana's bare commit is refused.
        val epoch = appGroup().epoch
        val bare = dana.commitLocally(routingId) {
            val staged = dana.manager.stageAppDataUpdate(routingId, AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(listOf(dana.hex, userHex)).encode())
            dana.manager.installState(routingId, staged.pendingState)
            staged.result
        }
        MarmotStandIn.storeInApp(db, user, commitEvent(bare))
        assertEquals(epoch, appGroup().epoch, "a bare commit landed after the switch")
    }

    // --- Phase 5: this device proposes, the group signs, this device publishes ------

    /** The app made an admin beside Dana, inside the group's signature. */
    private suspend fun appIsAdmin() {
        val epoch = appGroup().epoch
        val staged = stagedPromotion()
        danaCarries(statement(commitEvent(staged.result), GovernanceChange(admins = setOf(dana.hex, userHex)), epoch))
        dana.manager.installState(routingId, staged.pendingState)
        assertTrue(appGroup().isLocalAdmin())
    }

    /** The app's change, built and kept as `propose` keeps it, and the group's signature on it. */
    private suspend fun appProposes(propose: (com.vitorpamplona.mls.group.MlsGroup) -> Unit): Pair<com.machankura.dedun.compose.database.model.GovernanceProposal, Event> {
        val prepared = GovernanceManager.prepare(appGroup(), routingId, propose)
        val signed = statement(prepared.commitEvent, prepared.change, prepared.epoch)
        val proposal = GovernanceManager.record(db, room(), userHex, prepared, statementId = signed.id, sessionId = "session-" + signed.id.take(8))
        return proposal to signed
    }

    private fun demoteDana(group: com.vitorpamplona.mls.group.MlsGroup) {
        group.proposeAppDataUpdate(AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(listOf(userHex)).encode())
    }

    /** The carrier this device put on the wire last, as the room's other members receive it. */
    private suspend fun lastCarrier(): GroupEvent = okTheLatestCommit()

    @Test
    fun `this device proposes, the group signs, and the change lands here and at Dana's alike`() = runBlocking {
        governedRoom()
        appIsAdmin()
        val epoch = appGroup().epoch

        val (proposal, signed) = appProposes(::demoteDana)
        assertEquals(epoch, appGroup().epoch, "the commit was applied before it was signed")

        GovernanceManager.publish(db, proposal, signed)
        assertEquals(epoch, appGroup().epoch, "the commit was applied before a relay accepted it")
        val carrier = lastCarrier()

        assertEquals(epoch + 1, appGroup().epoch)
        assertEquals(setOf(userHex), appGroup().currentAdminIdentities())
        assertTrue(lines(ChatMessage.TYPE_ADMIN_REMOVED).any { it.content == "Dana is no longer an admin" })

        // Dana reads the carrier and applies the commit inside it, as the gate would.
        val carried = assertIs<com.vitorpamplona.marmot.GroupEventResult.ApplicationMessage>(dana.inbound.processGroupEvent(carrier))
        val statement = assertNotNull(GovernanceCarrierEvent.statementOf(Event.fromJson(carried.innerEventJson)))
        assertEquals(signed.id, statement.id)
        dana.inbound.processGroupEvent(assertNotNull(com.machankura.dedun.compose.nostr.frost.GovernanceEvents.innerCommitOf(statement)))
        assertEquals(setOf(userHex), dana.manager.getGroup(routingId)!!.currentAdminIdentities(), "Dana is in another group")

        // The carrier coming back to this device is not news.
        MarmotStandIn.storeInApp(db, user, carrier)
        assertEquals(epoch + 1, appGroup().epoch)
        assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).isEmpty())
    }

    @Test
    fun `a signed change the group moved past is marked stale, and says so`() = runBlocking {
        governedRoom()
        appIsAdmin()
        val (proposal, signed) = appProposes(::demoteDana)

        // Dana's own key update lands first.
        val update = dana.manager.stageCommit(routingId)
        danaSays(OwnCommitEvent.KIND, commitEvent(update.result).toJson(), OwnCommitEvent.assembleTags(roomId, appGroup().epoch))
        dana.manager.installState(routingId, update.pendingState)

        GovernanceManager.publish(db, proposal, signed)

        assertEquals(
            com.machankura.dedun.compose.database.model.GovernanceProposalStage.STALE,
            db.governanceProposalDao().getById(proposal.id)!!.stage,
        )
        assertEquals(1, lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).size)
        assertEquals(setOf(dana.hex, userHex), appGroup().currentAdminIdentities())
    }

    @Test
    fun `a member's device declines to sign a statement that does not say what its commit does`() = runBlocking<Unit> {
        governedRoom()
        val epoch = appGroup().epoch
        val commit = commitEvent(stagedPromotion().result)
        val unsigned = { change: GovernanceChange ->
            Event(
                id = "0".repeat(64), pubKey = roomId, createdAt = 1_700_000_000, kind = GovernanceStatementEvent.KIND,
                tags = GovernanceStatementEvent.assembleTags(roomId, epoch, change, proposer = dana.hex), content = commit.toJson(), sig = "",
            )
        }

        assertEquals(null, GovernanceManager.refusalOf(db, room(), unsigned(GovernanceChange(admins = setOf(dana.hex, userHex)))))
        assertNotNull(GovernanceManager.refusalOf(db, room(), unsigned(GovernanceChange(admins = setOf(dana.hex)))))
    }
}
