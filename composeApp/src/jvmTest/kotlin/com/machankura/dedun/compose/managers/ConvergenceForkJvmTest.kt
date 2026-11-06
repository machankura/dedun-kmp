package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.marmot.protocolCore.ConvergenceDisposition
import com.vitorpamplona.quartz.marmot.protocolCore.ConvergenceStatus
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.sha256.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.cancelAndWait
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.nostr.MarmotProfile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The test that actually proves it, and the one that found the limit. docs/marmot-v2.md,
 * Phase 8.
 *
 * Convergence's whole property is that two clients resolving the same frozen batch
 * select the same branch. The first test holds it where the app uses it: two other
 * members fork the group, the app and a second observer are handed both commits --
 * in opposite orders -- and settle on the same state, byte for byte.
 *
 * The second test pins what quartz 1.15.2 cannot yet do. A pass rebuilds the
 * incumbent branch by replaying its commits against the retained parent, and a
 * member cannot replay a commit it authored itself -- `processFramedCommit` fails
 * with "UpdatePath at common ancestor carries no ciphertext for us", because the
 * committer holds no key for its own copath. So a fork this device authored is
 * scored with its own branch stale, the challenger wins, and two committers swap
 * branches rather than agree. That is the library's to fix -- the resulting state
 * is retained already and needs no replay -- and this test is what flips the day
 * it does.
 */
class ConvergenceForkJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Dispatchers.Default)
    private val marmotRepository = DatabaseMarmotRepository(db, scope)
    private val chatRepository = DatabaseChatRepository(db, scope)
    private val nostrRepository = DatabaseNostrRepository(db, scope)

    /** A fork arms `settleLater`, which keeps ticking on the engine's own scope after the test is done. */
    @AfterTest
    fun closeDb() {
        db.marmotEngine.scope.cancelAndWait()
        db.close()
    }

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()

    private fun MlsGroup.contextBytes() = saveState().groupContext.toTlsBytes()

    private suspend fun packageRow(peer: KeyPair): MarmotKeyPackage {
        MarmotStandIn.seedProfile(db, peer.pubKey.toHexKey())
        val keyPackage = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(peer)).keyPackage
        return MarmotKeyPackage(
            id = peer.pubKey.toHexKey(), publicKey = peer.pubKey.toHexKey(),
            tlsEncodedMarmotKeyPackage = keyPackage.toTlsBytes(), profile = MarmotProfile.CURRENT.wireName,
        )
    }

    /** A stand-in joins [owner]'s group from a Welcome, as a plain member. */
    private suspend fun join(owner: MarmotStandIn, routingId: String, joiner: MarmotStandIn) {
        val bundle = joiner.makeBundle()
        val commit = owner.commitLocally(routingId) { owner.manager.addMember(routingId, bundle.keyPackage.toTlsBytes()) }
        val (_, id) = joiner.manager.processWelcome(assertNotNull(commit.welcomeBytes), bundle)
        joiner.inbound.trackGroup(id)
    }

    /** Every member the owner added is told of it: [event] applied by [members]. */
    private suspend fun fanOut(event: GroupEvent, vararg members: MarmotStandIn) =
        members.forEach { assertIs<GroupEventResult.CommitProcessed>(it.inbound.processGroupEvent(event)) }

    @Test
    fun `observers of a fork between two other members settle on the same branch`() = runBlocking {
        // Alice's group. Bob is made an admin so he may add members too; Dana and the
        // app are plain members, and the two observers. Every commit Alice makes is
        // handed to everyone already in, so all four sit at one epoch before the fork.
        val alice = MarmotStandIn()
        val bob = MarmotStandIn()
        val dana = MarmotStandIn()
        MarmotStandIn.seedProfile(db, userHex, "us")
        val routingId = alice.createGroup("forked")
        join(alice, routingId, bob)
        alice.commitLocally(routingId) {
            val staged = alice.manager.stageAppDataUpdate(
                routingId, AdminPolicyV1.COMPONENT_ID,
                AdminPolicyV1.of(listOf(alice.keyPair.pubKey, bob.keyPair.pubKey)).encode(),
            )
            alice.manager.installState(routingId, staged.pendingState)
            staged.result
        }
        fanOut(alice.lastCommitEvent(routingId), bob)
        join(alice, routingId, dana)
        fanOut(alice.lastCommitEvent(routingId), bob)
        alice.inviteApp(db, marmotRepository, user, routingId)
        fanOut(alice.lastCommitEvent(routingId), bob, dana)
        val baseEpoch = alice.manager.getGroup(routingId)!!.epoch
        listOf(bob, dana).forEach { assertEquals(baseEpoch, it.manager.getGroup(routingId)!!.epoch) }
        assertEquals(baseEpoch, db.marmotEngine.groupFor(routingId)!!.epoch)

        // The fork: Alice and Bob each add a different member against the same epoch.
        val peerX = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(KeyPair())).keyPackage
        val peerY = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(KeyPair())).keyPackage
        alice.commitLocally(routingId) { alice.manager.addMember(routingId, peerX.toTlsBytes()) }
        bob.commitLocally(routingId) { bob.manager.addMember(routingId, peerY.toTlsBytes()) }
        val alicesCommit = alice.lastCommitEvent(routingId)
        val bobsCommit = bob.lastCommitEvent(routingId)

        // The observers see the two commits in opposite orders. Each applies the first
        // as a linear commit and admits the second as a fork, which opens a pass.
        MarmotStandIn.storeInApp(db, user, alicesCommit)
        MarmotStandIn.storeInApp(db, user, bobsCommit)
        assertTrue(db.marmotEngine.inbound.openConvergencePasses().containsKey(routingId), "no pass opened on the app's side")
        assertIs<GroupEventResult.CommitProcessed>(dana.inbound.processGroupEvent(bobsCommit))
        assertIs<GroupEventResult.CommitPending>(dana.inbound.processGroupEvent(alicesCommit)).also { assertTrue(it.forkDetected) }

        // The app's side settles the way its outbound path does before it sends:
        // `awaitSettled` ticks the pass through its quiet second and returns once
        // outbound work is allowed. Dana's is resolved outright -- the timers are
        // scheduling, not semantics.
        assertFalse(db.marmotEngine.inbound.convergenceStatus(routingId).allowsOutboundWork)
        db.marmotEngine.awaitSettled(routingId)
        assertTrue(db.marmotEngine.inbound.convergenceStatus(routingId).allowsOutboundWork)
        assertNotNull(dana.inbound.resolveConvergence(routingId))

        val ours = db.marmotEngine.groupManager.getGroup(routingId)!!
        val danas = dana.manager.getGroup(routingId)!!
        assertEquals(ConvergenceStatus.SETTLED, db.marmotEngine.inbound.convergenceStatus(routingId))
        assertEquals(ConvergenceStatus.SETTLED, dana.inbound.convergenceStatus(routingId))
        assertEquals(baseEpoch + 1, ours.epoch)
        assertEquals(danas.epoch, ours.epoch)
        assertEquals(danas.currentMemberIdentities(), ours.currentMemberIdentities())
        assertContentEquals(danas.contextBytes(), ours.contextBytes(), "the two observers settled on different states")
        // Exactly one of the two additions survived, on both.
        val members = ours.currentMemberIdentities()
        val xIn = (peerX.leafNode.credential as Credential.Basic).identity.toHexKey() in members
        val yIn = (peerY.leafNode.credential as Credential.Basic).identity.toHexKey() in members
        assertTrue(xIn xor yIn)
        // And the app's row carries what its engine holds.
        assertContentEquals(ours.contextBytes(), db.chatRoomDao().findChatRoomById(routingId)!!.chatRoom.toMlsGroup()!!.contextBytes())

        // What settling is for: a message the app sends now is one Dana reads.
        val hello = MarmotAppEvent.build(pubKey = userHex, kind = 9, content = "still here", createdAt = 1_700_000_000L).encodeToPayload()
        val sent = db.marmotEngine.outbound.buildGroupEventFromBytes(routingId, hello).signedEvent
        val read = assertIs<GroupEventResult.ApplicationMessage>(dana.inbound.processGroupEvent(sent))
        assertTrue("still here" in read.innerEventJson)
    }

    /**
     * Pins quartz 1.15.2: a fork this device authored is scored with its own branch
     * stale. Two committers therefore swap branches. When the library rebuilds the
     * incumbent from its retained state instead of replaying it, the assertions on
     * `rewound` and the members flip, and this test should be turned into the
     * agreement it was written to prove.
     */
    @Test
    fun `a fork this device authored is not yet rebuilt by the engine`() = runBlocking {
        val other = MarmotStandIn()
        MarmotStandIn.seedProfile(db, userHex, "us")
        val routingId = other.createGroup("forked")
        other.inviteApp(db, marmotRepository, user, routingId)
        // The app is made an admin, so it may add a member too.
        val promote = other.commitLocally(routingId) {
            val staged = other.manager.stageAppDataUpdate(
                routingId, AdminPolicyV1.COMPONENT_ID,
                AdminPolicyV1.of(listOf(other.keyPair.pubKey, userHex.hexToByteArray())).encode(),
            )
            other.manager.installState(routingId, staged.pendingState)
            staged.result
        }
        MarmotStandIn.storeInApp(db, user, other.outbound.buildCommitEvent(routingId, promote.framedCommitBytes, promote.preCommitExporterSecret).signedEvent)
        assertTrue(db.marmotEngine.groupFor(routingId)!!.isLocalAdmin(), "the promotion did not land")
        val baseEpoch = db.marmotEngine.groupManager.getGroup(routingId)!!.epoch

        // Each side adds a different member against the same epoch.
        val peerA = KeyPair()
        val peerB = KeyPair()
        chatRepository.inviteMember(db.chatRoomDao().findChatRoomById(routingId)!!, peerA.pubKey.toHexKey(), packageRow(peerA))
        val ourEvent = db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 20)
            .filter { it.kind == GroupEvent.KIND }
            .last { db.marmotCommitResultDao().getMarmotCommitRequestById(it.id) != null }
        nostrRepository.broadcastProcessed(db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(ourEvent.id)!!, "published")
        val ourCommit = ourEvent.toGroupEvent(userHex)!!
        val ourBytes = db.marmotCommitResultDao().getMarmotCommitRequestById(ourEvent.id)!!.framedCommitBytes
        val theirs = other.commitLocally(routingId) {
            other.manager.addMember(routingId, CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(peerB)).keyPackage.toTlsBytes())
        }
        val theirCommit = other.outbound.buildCommitEvent(routingId, theirs.framedCommitBytes, theirs.preCommitExporterSecret).signedEvent
        assertEquals(baseEpoch + 1, db.marmotEngine.groupManager.getGroup(routingId)!!.epoch)
        assertEquals(baseEpoch + 1, other.manager.getGroup(routingId)!!.epoch)

        // Each is handed the other's commit, and each pass is resolved.
        MarmotStandIn.storeInApp(db, user, theirCommit)
        assertTrue(db.marmotEngine.inbound.openConvergencePasses().containsKey(routingId), "no pass opened on the app's side")
        assertIs<GroupEventResult.CommitPending>(other.inbound.processGroupEvent(ourCommit)).also { assertTrue(it.forkDetected) }
        val ourResolution = assertNotNull(db.marmotEngine.inbound.resolveConvergence(routingId))
        val theirResolution = assertNotNull(other.inbound.resolveConvergence(routingId))

        // The defect, pinned: on each side the commit that side authored is stale, the
        // other's is accepted, and both sides rewind -- onto each other's branch.
        val ourId = sha256(ourBytes).toHexKey()
        val theirId = sha256(theirs.framedCommitBytes).toHexKey()
        assertEquals(ConvergenceDisposition.STALE, ourResolution.outcomes.single { it.commitId == ourId }.disposition)
        assertEquals(ConvergenceDisposition.ACCEPTED, ourResolution.outcomes.single { it.commitId == theirId }.disposition)
        assertEquals(ConvergenceDisposition.STALE, theirResolution.outcomes.single { it.commitId == theirId }.disposition)
        assertEquals(ConvergenceDisposition.ACCEPTED, theirResolution.outcomes.single { it.commitId == ourId }.disposition)
        assertTrue(ourResolution.rewound && theirResolution.rewound)
        assertEquals(ConvergenceStatus.SETTLED, db.marmotEngine.inbound.convergenceStatus(routingId))
        assertEquals(ConvergenceStatus.SETTLED, other.inbound.convergenceStatus(routingId))

        val ours = db.marmotEngine.groupManager.getGroup(routingId)!!
        val theirsAfter = other.manager.getGroup(routingId)!!
        assertTrue(peerB.pubKey.toHexKey() in ours.currentMemberIdentities(), "the app did not take the other side's branch")
        assertTrue(peerA.pubKey.toHexKey() in theirsAfter.currentMemberIdentities(), "the other side did not take the app's branch")
        assertNotEquals(ours.contextBytes().toHexKey(), theirsAfter.contextBytes().toHexKey(), "the two sides agree -- the library has been fixed; rewrite this test as the agreement")
        // What must hold regardless: the app's row carries what its engine holds.
        assertContentEquals(ours.contextBytes(), db.chatRoomDao().findChatRoomById(routingId)!!.chatRoom.toMlsGroup()!!.contextBytes())
    }
}
