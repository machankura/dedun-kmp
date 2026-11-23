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
import com.machankura.dedun.compose.extensions.toHex
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
import com.machankura.dedun.compose.nostr.frost.PromotionEvents
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
        routingId = dana.createGroup("&keyed", if (governed) GovernanceMarker.describe(keyed, minimumAdmins = 1) else keyed)
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

    /** Takes back what this device said, as a device that joined before joining said it. */
    private suspend fun forgetOurSupport() {
        db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(GovernanceSupportEvent.KIND))
            .filter { it.publicKey == userHex }
            .forEach { db.marmotInnerEventDao().deleteById(it.id) }
        assertFalse(userHex in GovernanceSupport.supporters(db, roomId))
    }

    @Test
    fun `a member welcomed into a keyed room says it on joining`() = runBlocking {
        // Nobody else says anything after the Welcome: what the others said before it
        // is at an epoch this device cannot read. docs/remove-convenient-groups.md.
        governedRoom(governed = false)

        assertTrue(userHex in GovernanceSupport.supporters(db, roomId), "joining did not say it")
        assertEquals(setOf(dana.hex), GovernanceSupport.missing(db, roomId, appGroup()))
    }

    @Test
    fun `a device that joined before joining said it says it when it starts`() = runBlocking {
        governedRoom(governed = false)
        forgetOurSupport()

        assertEquals(1, chatRepository.announceGovernanceSupport())
        assertTrue(userHex in GovernanceSupport.supporters(db, roomId))
        assertEquals(0, chatRepository.announceGovernanceSupport(), "said twice")
    }

    private val frank = MarmotStandIn()

    /** Frank joins Dana's room after Erin, by Dana's bare commit; Erin and the app apply it. */
    private suspend fun frankJoins() {
        MarmotStandIn.seedProfile(db, frank.hex, "Frank")
        frank.makeBundle()
        val add = dana.commitLocally(routingId) { dana.manager.addMember(routingId, frank.bundle.keyPackage.toTlsBytes()) }
        frank.manager.processWelcome(assertNotNull(add.welcomeBytes), frank.bundle)
        frank.inbound.trackGroup(routingId)
        val event = commitEvent(add)
        erin.inbound.processGroupEvent(event)
        MarmotStandIn.storeInApp(db, user, event)
    }

    @Test
    fun `the first admin's device switches the rule on by itself once everyone has said it`() = runBlocking {
        // Dana made the room and holds its first leaf; once she is no admin, the app's
        // leaf is the lowest an admin holds, and the switch is the app's to make.
        governedRoom(governed = false)
        erinJoins()
        frankJoins()
        val admins = listOf(userHex, erin.hex, frank.hex)
        val commit = dana.commitLocally(routingId) {
            val staged = dana.manager.stageAppDataUpdate(routingId, AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(admins).encode())
            dana.manager.installState(routingId, staged.pendingState)
            staged.result
        }
        val event = commitEvent(commit)
        erin.inbound.processGroupEvent(event)
        frank.inbound.processGroupEvent(event)
        MarmotStandIn.storeInApp(db, user, event)
        assertEquals(userHex, GovernanceSupport.switcherOf(appGroup()))

        says(dana)
        says(erin)
        GovernanceFollowThrough.pass(db, user)
        assertFalse(GovernanceGate.isGoverned(appGroup()), "switched on before Frank said it")

        says(frank)
        GovernanceFollowThrough.pass(db, user)
        okTheLatestCommit()

        assertTrue(GovernanceGate.isGoverned(appGroup()), "nobody pressed anything, and nothing happened")
        assertEquals(0, GovernanceSupport.switchWhereOwed(db, userHex), "switched twice")
    }

    @Test
    fun `a device that is not the first admin leaves the switch to it`() = runBlocking {
        // Every admin's device switching the moment the last member said it would be two
        // commits at one epoch, which the library cannot settle for their authors.
        governedRoom(governed = false)
        erinJoins()
        danaSetsAdminsBare(listOf(dana.hex, userHex, erin.hex))
        says(dana)
        says(erin)
        assertEquals(GovernanceStatus.Ready(minimumAdmins = 3, switcher = dana.hex), chatRepository.governanceStatus(roomId))

        GovernanceFollowThrough.pass(db, user)

        assertEquals(0, GovernanceSupport.switchWhereOwed(db, userHex))
        assertFalse(GovernanceGate.isGoverned(appGroup()))
        assertTrue(db.marmotCommitResultDao().let { dao ->
            db.nostrEventDao().getMLSGroupEvents(since = kotlin.time.Instant.fromEpochSeconds(0), limit = 50)
                .none { it.kind == GroupEvent.KIND && dao.getMarmotCommitRequestById(it.id) != null }
        }, "the app sent a commit of its own")
    }

    @Test
    fun `hearing another member say it, this device says it back`() = runBlocking {
        governedRoom(governed = false)
        forgetOurSupport()

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
        assertEquals(GovernanceStatus.Ready(minimumAdmins = 3, switcher = dana.hex), chatRepository.governanceStatus(roomId))

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

    // --- Phase 5b: the room's ordinary changes, governed --------------------------

    /** Erin added by Dana inside the group's signature, joined from the Welcome. */
    private suspend fun erinAddedSigned() {
        MarmotStandIn.seedProfile(db, erin.hex, "Erin")
        erin.makeBundle()
        val epoch = appGroup().epoch
        val staged = dana.manager.stageAddMember(routingId, erin.bundle.keyPackage.toTlsBytes())
        val added = com.machankura.dedun.compose.nostr.frost.AddedMember(erin.hex, erin.bundle.keyPackage.reference().toHex())
        danaCarries(statement(commitEvent(staged.result), GovernanceChange(adds = setOf(added)), epoch))
        dana.manager.installState(routingId, staged.pendingState)
        erin.manager.processWelcome(assertNotNull(staged.result.welcomeBytes), erin.bundle)
        erin.inbound.trackGroup(routingId)
        assertTrue(erin.hex in appGroup().currentMemberIdentities(), "Erin's signed add did not land")
    }

    /** Dana applies the commit inside the carrier this device sent. */
    private suspend fun danaFollows(carrier: GroupEvent) {
        val carried = assertIs<com.vitorpamplona.marmot.GroupEventResult.ApplicationMessage>(dana.inbound.processGroupEvent(carrier))
        val envelope = Event.fromJson(carried.innerEventJson)
        val statement = GovernanceCarrierEvent.statementOf(envelope) ?: envelope
        dana.inbound.processGroupEvent(assertNotNull(com.machankura.dedun.compose.nostr.frost.GovernanceEvents.innerCommitOf(statement)))
    }

    @Test
    fun `a member's leave is committed by an admin's device inside its own envelope`() = runBlocking {
        governedRoom()
        appIsAdmin()
        erinAddedSigned()
        val epoch = appGroup().epoch

        val (framed, exporter) = erin.manager.leaveGroup(routingId)
        val leave = com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal(KeyPair()).sign(
            GroupEvent.build(encryptedContentBase64 = com.vitorpamplona.marmot.mip03GroupMessages.GroupEventEncryption.encrypt(framed, exporter), nostrGroupId = routingId)
        )
        // Every member pools the proposal; the commit only refers to it.
        dana.inbound.processGroupEvent(leave)
        MarmotStandIn.storeInApp(db, user, leave)
        val carrier = okTheLatestCommit()

        assertEquals(epoch + 1, appGroup().epoch)
        assertFalse(erin.hex in appGroup().currentMemberIdentities(), "Erin is still in the tree")
        danaFollows(carrier)
        assertFalse(erin.hex in dana.manager.getGroup(routingId)!!.currentMemberIdentities(), "Dana is in another group")
    }

    @Test
    fun `removing an admin is one signed commit that takes their admin entry with them`() = runBlocking {
        governedRoom()
        appIsAdmin()
        val epoch = appGroup().epoch
        val danaLeaf = appGroup().members().first { (_, leaf) ->
            (leaf.credential as com.vitorpamplona.mls.tree.Credential.Basic).identity.toHexKey() == dana.hex
        }.first

        val prepared = GovernanceManager.prepare(appGroup(), routingId, GovernanceManager.removal(danaLeaf, dana.hex, appGroup().currentAdminIdentities()))
        val signed = statement(prepared.commitEvent, prepared.change, prepared.epoch)
        assertEquals(GovernanceChange(removes = setOf(dana.hex), admins = setOf(userHex)), prepared.change)
        GovernanceManager.publish(db, GovernanceManager.record(db, room(), userHex, prepared, signed.id, "s"), signed)
        okTheLatestCommit()

        assertEquals(epoch + 1, appGroup().epoch, "the removal took more than one commit")
        assertEquals(setOf(userHex), appGroup().currentAdminIdentities())
        assertFalse(dana.hex in appGroup().currentMemberIdentities())
        assertTrue(lines(ChatMessage.TYPE_ADMIN_REMOVED).isEmpty(), "a departure was captioned twice")
    }

    @Test
    fun `an invite signed by the group sends its Welcome once the carrier is accepted`() = runBlocking {
        governedRoom()
        appIsAdmin()
        MarmotStandIn.seedProfile(db, erin.hex, "Erin")
        erin.makeBundle()
        val welcomesBefore = db.giftWrapPayloadDao().getAllGiftWrapUnsigned().count { it.kind == com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent.KIND }
        val packageRow = com.machankura.dedun.compose.database.model.MarmotKeyPackage(
            id = erin.hex, publicKey = erin.hex,
            tlsEncodedMarmotKeyPackage = erin.bundle.keyPackage.toTlsBytes(), profile = com.machankura.dedun.compose.nostr.MarmotProfile.CURRENT.wireName,
        )
        db.participantDao().upsert(listOf(com.machankura.dedun.compose.database.model.Participant(participantPublicKey = erin.hex, chatRoomId = roomId, relayHint = null)))

        val prepared = GovernanceManager.prepare(appGroup(), routingId, GovernanceManager.addition(packageRow.tlsEncodedMarmotKeyPackage))
        val signed = statement(prepared.commitEvent, prepared.change, prepared.epoch)
        GovernanceManager.publish(db, GovernanceManager.record(db, room(), userHex, prepared, signed.id, "s", peerKeyPackageEventId = packageRow.id), signed)
        assertEquals(welcomesBefore, db.giftWrapPayloadDao().getAllGiftWrapUnsigned().count { it.kind == com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent.KIND }, "the Welcome went before the commit was accepted")

        okTheLatestCommit()

        assertTrue(erin.hex in appGroup().currentMemberIdentities())
        assertEquals(welcomesBefore + 1, db.giftWrapPayloadDao().getAllGiftWrapUnsigned().count { it.kind == com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent.KIND }, "no Welcome for Erin")
    }

    @Test
    fun `a removal signed by the group, from another member, lands here`() = runBlocking {
        governedRoom()
        erinAddedSigned()
        val epoch = appGroup().epoch
        val erinLeaf = dana.manager.getGroup(routingId)!!.members().first { (_, leaf) ->
            (leaf.credential as com.vitorpamplona.mls.tree.Credential.Basic).identity.toHexKey() == erin.hex
        }.first

        val staged = dana.manager.stageRemoveMember(routingId, erinLeaf)
        danaCarries(statement(commitEvent(staged.result), GovernanceChange(removes = setOf(erin.hex)), epoch))

        assertEquals(epoch + 1, appGroup().epoch, "a signed removal was refused at the door")
        assertFalse(erin.hex in appGroup().currentMemberIdentities())
        assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).isEmpty())
    }

    // --- Phase 9: demotion and removal in a keyed room, then a rotation ------------

    @Test
    fun `in a governed keyed room an admin is offered demoting another, and stepping down`() = runBlocking {
        governedRoom()
        appIsAdmin()
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        try {
            val actions = AdminGovernance.memberActions(appGroup(), userHex, floor = 1)
            assertTrue(com.machankura.dedun.compose.ui.view.state.MemberAction.REMOVE_AS_ADMIN in actions[dana.hex].orEmpty())
            assertTrue(com.machankura.dedun.compose.ui.view.state.MemberAction.STEP_DOWN in actions[userHex].orEmpty(), "an admin's own row offered no step-down")
            val atTheFloor = AdminGovernance.memberActions(appGroup(), userHex, floor = 2)
            assertFalse(com.machankura.dedun.compose.ui.view.state.MemberAction.REMOVE_AS_ADMIN in atTheFloor[dana.hex].orEmpty(), "the floor was ignored")
            assertFalse(com.machankura.dedun.compose.ui.view.state.MemberAction.STEP_DOWN in atTheFloor[userHex].orEmpty(), "the floor was ignored for a step-down")
        } finally {
            com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = null
        }
    }

    @Test
    fun `a step-down is proposed by the admin in the lowest leaf, never the one stepping down`() = runBlocking {
        governedRoom()
        appIsAdmin()
        // Dana made the room and holds leaf 0; the app holds leaf 1.
        assertEquals(dana.hex, AdminGovernance.stepDownProposer(appGroup(), userHex))
        assertEquals(userHex, AdminGovernance.stepDownProposer(appGroup(), dana.hex))
    }

    @Test
    fun `stepping down from a keyed room asks the other admins, and commits nothing`() = runBlocking {
        governedRoom()
        appIsAdmin()

        PromotionManager.requestStepDown(db, room(), userHex)

        val requests = db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.STEP_DOWN))
        assertEquals(listOf(userHex), requests.map { it.publicKey })
        assertTrue(db.marmotPublishObligationDao().getAll().isEmpty(), "a commit went out")
        assertTrue(db.governanceProposalDao().getAll().isEmpty(), "the one stepping down proposed it")
        assertTrue(userHex in appGroup().currentAdminIdentities())
        // Not this device's to take up: Dana's is the one that proposes it.
        assertEquals(0, PromotionManager.followStepDowns(db, userHex))
        assertEquals(1, db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.STEP_DOWN)).size)
    }

    @Test
    fun `the admin whose it is to propose a step-down takes the request up`() = runBlocking {
        governedRoom()
        appIsAdmin()
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        try {
            danaSays(PromotionEvents.STEP_DOWN, "", PromotionEvents.stepDownTags(roomId))

            // Dana's request reaches `demote` on this device, which is the one to
            // propose it. This room has two admins under a floor of three, so the
            // demotion is refused there and the room says why; the request is used.
            assertEquals(0, PromotionManager.followStepDowns(db, userHex))
            assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).any { "as they asked" in it.content && "at least 3 admins" in it.content })
            assertTrue(db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.STEP_DOWN)).isEmpty(), "the request was kept")
        } finally {
            com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = null
        }
    }

    @Test
    fun `a request from someone who is no longer an admin is dropped`() = runBlocking {
        governedRoom()
        // A request the app sent while it was an admin, filed after it stopped being one.
        db.marmotInnerEventDao().upsert(
            com.machankura.dedun.compose.database.model.MarmotInnerEvent(
                id = "ab".repeat(32), publicKey = userHex, kind = PromotionEvents.STEP_DOWN,
                createdAt = kotlin.time.Instant.fromEpochSeconds(1_700_000_000),
                tags = PromotionEvents.stepDownTags(roomId), content = "", chatRoomId = roomId,
            )
        )

        PromotionManager.followStepDowns(db, dana.hex)

        assertTrue(db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.STEP_DOWN)).isEmpty())
    }

    @Test
    fun `a signed demotion that lands owes a rotation without the member, and asks for one`() = runBlocking {
        governedRoom()
        appIsAdmin()
        val prepared = GovernanceManager.prepare(appGroup(), routingId, ::demoteDana)
        val signed = statement(prepared.commitEvent, prepared.change, prepared.epoch)
        val proposal = GovernanceManager.record(db, room(), userHex, prepared, signed.id, "s", thenRotateWithout = dana.hex)

        GovernanceManager.publish(db, proposal, signed)
        assertTrue(GovernanceFollowThrough.rotationsOwed(db).isEmpty(), "a rotation was owed before the change landed")
        okTheLatestCommit()

        assertEquals(com.machankura.dedun.compose.database.model.GovernanceProposalStage.APPLIED, db.governanceProposalDao().getById(proposal.id)!!.stage)
        assertEquals(listOf(proposal.id), GovernanceFollowThrough.rotationsOwed(db).map { it.id })

        // This room has no ceremony behind it, so the rotation cannot start: the
        // room says so, and the change is not asked about again.
        GovernanceFollowThrough.pass(db, user)
        assertEquals(com.machankura.dedun.compose.database.model.GovernanceProposalStage.DONE, db.governanceProposalDao().getById(proposal.id)!!.stage)
        assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).any { "Shares section" in it.content })
        assertTrue(GovernanceFollowThrough.rotationsOwed(db).isEmpty())
    }

    @Test
    fun `a demotion that landed while nobody watched still owes its rotation`() = runBlocking {
        governedRoom()
        appIsAdmin()
        val prepared = GovernanceManager.prepare(appGroup(), routingId, ::demoteDana)
        val signed = statement(prepared.commitEvent, prepared.change, prepared.epoch)
        val proposal = GovernanceManager.record(db, room(), userHex, prepared, signed.id, "s", thenRotateWithout = dana.hex)
        GovernanceManager.publish(db, proposal, signed)
        okTheLatestCommit()
        // A restart between the OK and the row being marked.
        db.governanceProposalDao().upsert(db.governanceProposalDao().getById(proposal.id)!!.copy(stage = com.machankura.dedun.compose.database.model.GovernanceProposalStage.PUBLISHED))

        assertEquals(listOf(proposal.id), GovernanceFollowThrough.rotationsOwed(db).map { it.id })
    }

    // --- docs/admin-change-rotation.md --------------------------------------------

    private val ceremonyId = "c".repeat(64)

    /**
     * The room's key as this device holds it after its ceremony: [holders] dealt a
     * share each by a trusted dealer, in host-key order, [t] of them to sign, and a
     * key state naming it. Enough for the rules that read the key -- what this
     * device holds, the floor, a rotation -- without a ceremony or a signature.
     */
    private suspend fun roomKeyHeldBy(holders: List<String>, t: Int = 2) {
        // This device's own as a ceremony derives it, so a rotation it proposes can deal.
        val hosts = holders.map { holder ->
            if (holder == userHex) ChillDkgRitualManager.deriveHostPublicKey(user.privKey!!).value.toHex()
            else "02" + KeyPair().pubKey.toHexKey()
        }
        val ids = hosts.map { host -> hosts.sorted().indexOf(host) }
        val material = fr.acinq.bitcoin.crypto.frost.Frost.trustedDealerKeygen(
            fr.acinq.bitcoin.PrivateKey(fr.acinq.bitcoin.ByteVector32("ab".repeat(32))), holders.size, t,
        )
        val me = holders.indexOf(userHex)
        db.dkgSessionDao().upsert(
            com.machankura.dedun.compose.database.model.DkgSession(
                id = ceremonyId, chatRoomId = roomId, coordinatorPublicKey = holders.first(), userPublicKey = userHex,
                threshold = t, participantCount = holders.size, stage = com.machankura.dedun.compose.database.model.types.DkgRitualStage.COMPLETE,
                hostPublicKey = hosts[me], round1Random = "1".repeat(64), round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = material.thresholdPublicKey.value.toHex(),
                secretShare = material.secretShares[ids[me]].value.toHex(),
                publicShares = material.publicShares.joinToString(",") { it.value.toHex() },
            )
        )
        holders.forEachIndexed { i, holder ->
            db.dkgSessionDao().upsert(
                com.machankura.dedun.compose.database.model.DkgParticipantMessage(
                    sessionId = ceremonyId, participantPublicKey = holder,
                    kind = com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents.HOST_KEY, payload = hosts[i],
                )
            )
        }
        db.groupKeyStateDao().upsert(
            com.machankura.dedun.compose.database.model.GroupKeyState(
                chatRoomId = roomId, dkgSessionId = ceremonyId, thresholdPublicKey = material.thresholdPublicKey.value.toHex(),
                derivationPath = SharedKeyDerivation.formatPath(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH),
                announcedBy = roomId, announcedAt = kotlin.time.Instant.fromEpochSeconds(1_700_000_000),
            )
        )
    }

    /** A rotation of the room's key, proposed by Dana and still dealing. */
    private suspend fun rotationRunning(): com.machankura.dedun.compose.database.model.ShareRefreshSession =
        com.machankura.dedun.compose.database.model.ShareRefreshSession(
            id = "r".repeat(64), chatRoomId = roomId, dkgSessionId = ceremonyId, coordinatorPublicKey = dana.hex,
            userPublicKey = userHex, baseGeneration = 0, recipientIds = "0,1",
            round1Random = "3".repeat(64), auxRandom = "4".repeat(64),
        ).also { db.shareRefreshDao().upsert(it) }

    /**
     * Phase 6: the first promotion in a keyed room starts the rotation the
     * ceremony's generation needs, instead of sending the admin to rotate by hand.
     * Without the key to propose it with, it is refused as before.
     */
    @Test
    fun `making someone an admin at the ceremony's generation rotates the shares first`() = runBlocking {
        governedRoom()
        appIsAdmin()
        erinAddedSigned()
        roomKeyHeldBy(listOf(dana.hex, userHex))
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        try {
            val refused = assertFailsWith<IllegalStateException> { PromotionManager.offer(db, room(), userHex, erin.hex) }
            assertTrue("Rotate the group's shares once" in refused.message.orEmpty(), refused.message)
            assertTrue(db.shareRefreshDao().getSessionsForKey(ceremonyId).isEmpty())

            PromotionManager.offer(db, room(), userHex, erin.hex, user.privKey!!)
            val rotation = db.shareRefreshDao().getSessionsForKey(ceremonyId).single()
            assertEquals(0, rotation.baseGeneration)
            assertEquals(emptyMap(), rotation.withheldStandings(), "the rotation before a promotion left someone out")
            assertEquals(listOf(erin.hex), db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.OFFER))
                .map { PromotionEvents.value(it.tags, PromotionEvents.TARGET_TAG) })

            // Another offer meanwhile waits on that rotation like any other change.
            assertEquals(
                AdminGovernance.ROTATION_PENDING_REFUSAL,
                assertFailsWith<IllegalStateException> { PromotionManager.offer(db, room(), userHex, erin.hex, user.privKey!!) }.message,
            )
        } finally {
            com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = null
        }
    }

    /** Phase 6: an accepted first promotion waits for its rotation, and ends out loud, once, if the rotation does not finish. */
    @Test
    fun `an accepted first promotion waits for its rotation, and ends if the rotation is abandoned`() = runBlocking {
        governedRoom()
        appIsAdmin()
        erinAddedSigned()
        roomKeyHeldBy(listOf(dana.hex, userHex))
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        try {
            PromotionManager.offer(db, room(), userHex, erin.hex, user.privKey!!)
            val offer = db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.OFFER)).single()
            assertFalse(PromotionManager.readyToPropose(db, room(), userHex, offer), "proposed while its rotation ran")
            assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).isEmpty())

            val rotation = db.shareRefreshDao().getSessionsForKey(ceremonyId).single()
            ShareRefreshManager.abandon(db, room(), rotation.id)
            assertFalse(PromotionManager.readyToPropose(db, room(), userHex, offer))
            assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).single().content.startsWith("Couldn't make Erin an admin"))
            assertTrue(db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.OFFER)).isEmpty(), "the offer stayed")
            assertTrue(PromotionManager.acceptedOffers(db, userHex).isEmpty())
        } finally {
            com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = null
        }
    }

    /** H5: a step-down request that arrives during a rotation waits for it, and is taken up after. */
    @Test
    fun `a step-down request waits through a rotation and is taken up once it ends`() = runBlocking {
        governedRoom()
        appIsAdmin()
        roomKeyHeldBy(listOf(dana.hex, userHex))
        val rotation = rotationRunning()
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        try {
            danaSays(PromotionEvents.STEP_DOWN, "", PromotionEvents.stepDownTags(roomId))
            assertEquals(0, PromotionManager.followStepDowns(db, userHex))
            assertEquals(1, db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.STEP_DOWN)).size, "the request was used up")
            assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).isEmpty())

            // The rotation ends; the next pass takes the request up. This room is at its
            // floor, so the demotion is refused there, out loud, and the request is used.
            db.shareRefreshDao().upsert(rotation.copy(stage = com.machankura.dedun.compose.database.model.types.ShareRefreshStage.FAILED))
            PromotionManager.followStepDowns(db, userHex)
            assertTrue(db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.STEP_DOWN)).isEmpty())
            assertTrue(lines(ChatMessage.TYPE_CHANGE_NOT_APPLIED).any { "as they asked" in it.content })
        } finally {
            com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = null
        }
    }

    /** H1, from the admin side: a demotion is not proposed while the shares are being rotated. */
    @Test
    fun `a demotion proposed while the shares are being rotated is refused, and nothing is signed`() = runBlocking {
        governedRoom()
        appIsAdmin()
        roomKeyHeldBy(listOf(dana.hex, userHex))
        rotationRunning()

        val refused = assertFailsWith<IllegalStateException> {
            GovernanceManager.propose(db, room(), userHex, thenRotateWithout = dana.hex, propose = ::demoteDana)
        }
        assertEquals(AdminGovernance.ROTATION_PENDING_REFUSAL, refused.message)
        assertTrue(db.governanceProposalDao().getAll().isEmpty())
        assertTrue(db.frostSigningSessionDao().getSessionsForChatRoom(roomId).isEmpty(), "a signature was asked for")
    }

    @Test
    fun `nobody is offered admin, and nobody asks to step down, while the shares are being rotated`() = runBlocking {
        governedRoom()
        appIsAdmin()
        erinAddedSigned()
        roomKeyHeldBy(listOf(dana.hex, userHex))
        rotationRunning()
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        try {
            assertEquals(AdminGovernance.ROTATION_PENDING_REFUSAL, assertFailsWith<IllegalStateException> { PromotionManager.offer(db, room(), userHex, erin.hex) }.message)
            assertEquals(AdminGovernance.ROTATION_PENDING_REFUSAL, assertFailsWith<IllegalStateException> { PromotionManager.requestStepDown(db, room(), userHex) }.message)
            assertTrue(db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(PromotionEvents.OFFER, PromotionEvents.STEP_DOWN)).isEmpty())

            // The member rows say why, with the share holders' removal held and nobody else's.
            val options = chatRepository.memberActionOptions(room())
            val rotating = options.getValue(dana.hex).first { it.action == com.machankura.dedun.compose.ui.view.state.MemberAction.REMOVE_AS_ADMIN }.blockers
            assertTrue(rotating.any { it is com.machankura.dedun.compose.ui.view.state.MemberActionBlocker.RotationPending }, rotating.toString())
            val erinRemoval = options.getValue(erin.hex).first { it.action == com.machankura.dedun.compose.ui.view.state.MemberAction.REMOVE_FROM_GROUP }.blockers
            assertTrue(erinRemoval.none { it is com.machankura.dedun.compose.ui.view.state.MemberActionBlocker.RotationPending }, erinRemoval.toString())
        } finally {
            com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = null
        }
    }

    /** A6: only a change that moves who holds a share waits on a rotation. */
    @Test
    fun `inviting or removing a member with no share does not touch the shares`() {
        val holders = setOf(dana.hex, userHex)
        assertFalse(AdminGovernance.touchesShares(GovernanceChange(removes = setOf(erin.hex)), holders))
        assertFalse(AdminGovernance.touchesShares(GovernanceChange(adds = setOf(com.machankura.dedun.compose.nostr.frost.AddedMember(erin.hex, "r"))), holders))
        assertTrue(AdminGovernance.touchesShares(GovernanceChange(removes = setOf(dana.hex)), holders))
        assertTrue(AdminGovernance.touchesShares(GovernanceChange(admins = setOf(userHex)), holders))
    }
}
