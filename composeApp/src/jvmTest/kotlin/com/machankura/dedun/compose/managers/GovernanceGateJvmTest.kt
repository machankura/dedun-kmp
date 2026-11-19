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
}
