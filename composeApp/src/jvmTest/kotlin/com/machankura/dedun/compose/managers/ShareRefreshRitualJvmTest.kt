package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.ShareRefreshConfirmation
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.types.ShareGenerationState
import com.machankura.dedun.compose.database.model.types.ShareRefreshApprovalStep
import com.machankura.dedun.compose.database.model.types.ShareRefreshStage
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.frost.ShareRefreshEvents
import com.machankura.dedun.compose.ui.view.state.SharesSummary

/**
 * A share refresh between devices (docs/frost-share-refresh.md, Phase 7).
 *
 * Every member has a real nostr key, so the ChillDKG host keys derive the way the
 * app derives them, and the shares are dealt in the order those host keys sort
 * in -- the order a ceremony would have given. Each device is its own database,
 * and nothing passes between them but the inner events ferried by [pump]. The
 * refresh is the real one, through bitcoin-kmp's FrostRefresh and the zkp module
 * beneath it; when it is done the group signs with the new shares, through the
 * real signing manager, as the room it always was.
 */
class ShareRefreshRitualJvmTest {
    private val devices = mutableListOf<Device>()

    @AfterTest
    fun closeDatabases() {
        devices.forEach { it.db.close() }
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = null
    }

    private val ceremonyId = "ceremony".padEnd(64, '0')

    private inner class Group(n: Int, val t: Int) {
        val keyPairs: List<KeyPair> = List(n) { KeyPair() }
        val members: List<HexKey> = keyPairs.map { it.pubKey.toHexKey() }
        val hostKeys: List<HexKey> = keyPairs.map { ChillDkgRitualManager.deriveHostPublicKey(it.privKey!!).value.toHex() }

        /** Participant id of member i: its host key's place in the bytewise sort. */
        val idOf: List<Int> = hostKeys.map { hostKeys.sorted().indexOf(it) }

        val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(PrivateKey(ByteVector32("7e57ab1e".padEnd(64, '1'))), n, t)
        val thresholdPublicKey: HexKey = keyMaterial.thresholdPublicKey.value.toHex()
        val roomId: HexKey = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

        fun memberWithId(id: Int): HexKey = members[idOf.indexOf(id)]
    }

    private inner class Device(val group: Group, val index: Int, val db: DedunDatabase, val room: LocalChatRoom, val keyPair: KeyPair = group.keyPairs[index]) {
        val publicKey: HexKey get() = keyPair.pubKey.toHexKey()
        val privateKey: ByteArray get() = keyPair.privKey!!
        val received = mutableSetOf<String>()
        suspend fun key(): DkgSession = db.dkgSessionDao().getSessionById(ceremonyId)!!
        suspend fun refresh(id: String) = db.shareRefreshDao().getSessionById(id)
        suspend fun active() = ShareGenerations.active(db, key())
    }

    private suspend fun device(group: Group, index: Int): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        group.members.forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }
        val publicKey = group.members[index]
        val chatRoom = ChatRoom(id = group.roomId, userPublicKey = publicKey, subject = "#Ekklesia", description = SharedKeyDerivation.describe("Admins."), mlsGroupState = "00")
        db.chatRoomDao().upsert(chatRoom)
        val id = group.idOf[index]
        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = group.roomId,
                coordinatorPublicKey = group.members.first(),
                userPublicKey = publicKey,
                threshold = group.t,
                participantCount = group.members.size,
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = group.hostKeys[index],
                round1Random = "1".repeat(64),
                round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = group.thresholdPublicKey,
                secretShare = group.keyMaterial.secretShares[id].value.toHex(),
                publicShares = group.keyMaterial.publicShares.joinToString(",") { it.value.toHex() },
            )
        )
        group.members.forEachIndexed { i, member ->
            db.dkgSessionDao().upsert(DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = group.hostKeys[i]))
        }
        return Device(group, index, db, LocalChatRoom(chatRoom = chatRoom)).also { devices += it }
    }

    private suspend fun devicesFor(group: Group): List<Device> = group.members.indices.map { device(group, it) }

    // ---- the wire ----------------------------------------------------------------------------------------------

    private val ferried = (ShareRefreshEvents.ALL + FrostSigningEvents.ALL + com.machankura.dedun.compose.nostr.frost.ShareRepairEvents.ALL).toList()

    private suspend fun outbox(device: Device): List<MarmotInnerEvent> =
        device.db.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = device.group.roomId, kinds = ferried)
            .filter { it.publicKey == device.publicKey }
            .sortedWith(compareBy({ it.createdAt }, { it.kind }))

    private suspend fun pump(
        between: List<Device>,
        hold: (MarmotInnerEvent) -> Boolean = { false },
        tamper: (MarmotInnerEvent) -> MarmotInnerEvent = { it },
    ) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                outbox(from).forEach { queued ->
                    if (queued.id in to.received || hold(queued)) return@forEach
                    to.received += queued.id
                    moved = true
                    val delivered = tamper(queued)
                    to.db.marmotInnerEventDao().upsert(delivered.copy(chatRoomId = to.group.roomId))
                    val event = Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, delivered.content, "")
                    if (ShareRefreshEvents.isShareRefreshKind(event.kind)) {
                        ShareRefreshManager.processRefreshPayload(to.db, to.room, event, to.publicKey, to.privateKey)
                    } else if (com.machankura.dedun.compose.nostr.frost.ShareRepairEvents.isShareRepairKind(event.kind)) {
                        ShareRepairManager.processRepairPayload(to.db, to.room, event, to.publicKey, to.privateKey)
                    } else {
                        FrostSigningManager.processSigningPayload(to.db, to.room, event, to.publicKey)
                    }
                }
            }
        }
    }

    /** Every owner approves whatever they are asked, until nobody is asked anything. */
    private suspend fun approveAll(between: List<Device>, refreshId: String) {
        var asked = true
        while (asked) {
            asked = false
            for (device in between) {
                val session = device.refresh(refreshId) ?: continue
                if (ShareRefreshManager.pendingApproval(device.db, session) != null) {
                    ShareRefreshManager.approve(device.db, device.room, refreshId, device.privateKey)
                    asked = true
                }
            }
            pump(between)
        }
    }

    private suspend fun propose(by: Device, withheld: Map<Int, ShareStanding> = emptyMap()) =
        ShareRefreshManager.proposeRefresh(by.db, by.room, by.publicKey, by.privateKey, by.key(), withheld)

    /** One thing a device's owner was asked during a rotation. */
    private sealed interface Ask {
        data class Rotation(val step: com.machankura.dedun.compose.database.model.types.ShareRefreshApprovalStep) : Ask
        data object KeyState : Ask
        data class Signature(val kind: Int) : Ask
    }

    /**
     * Every owner says yes to whatever they are asked, about the rotation or any
     * signature, until nobody is asked anything -- and the list of what each was
     * asked, in order. The count is what docs/rotate-shares-single-approval.md
     * takes from three to one.
     */
    private suspend fun answerEverything(between: List<Device>, refreshId: String): Map<Int, List<Ask>> {
        val asks = between.associate { it.index to mutableListOf<Ask>() }
        var asked = true
        while (asked) {
            asked = false
            for (device in between) {
                device.refresh(refreshId)?.let { session ->
                    ShareRefreshManager.pendingApproval(device.db, session)?.let { step ->
                        asks.getValue(device.index) += Ask.Rotation(step)
                        ShareRefreshManager.approve(device.db, device.room, refreshId, device.privateKey)
                        asked = true
                    }
                }
                val signing = device.db.frostSigningSessionDao()
                signing.getSessionsForChatRoom(device.group.roomId)
                    .filter { FrostSigningManager.isAwaitingApproval(it, signing.getItems(it.id)) }
                    .forEach { session ->
                        val kind = Event.fromJsonOrNull(signing.getItems(session.id).single().unsignedEventJson)!!.kind
                        asks.getValue(device.index) += if (kind == com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent.KIND) Ask.KeyState else Ask.Signature(kind)
                        FrostSigningManager.approve(device.db, device.room, session.id)
                        asked = true
                    }
            }
            pump(between)
        }
        return asks
    }

    // ---- the tests ---------------------------------------------------------------------------------------------

    @Test
    fun `a refresh rotates every share, and the group signs with the new ones as the same room`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        approveAll(all, refresh.id)

        all.forEach { device ->
            assertEquals(ShareRefreshStage.COMPLETE, device.refresh(refresh.id)?.stage, "on device ${device.index}")
            val active = device.active()
            assertEquals(1, active.generation)
            val id = group.idOf[device.index]
            val newShare = assertNotNull(active.secretShare)
            assertNotEquals(group.keyMaterial.secretShares[id], newShare, "the share must have moved")
            assertEquals(active.publicShares!![id], newShare.publicKey())
        }
        val tables = all.map { it.active().publicShares }
        assertTrue(tables.all { it == tables.first() }, "every device must arrive at the same table")
        val shares = (0 until 3).map { id -> all.first { group.idOf[it.index] == id }.active().secretShare!! }
        assertTrue(KeyMaterial(group.keyMaterial.thresholdPublicKey, shares, tables.first()!!, 2).isValid(), "the new shares are of the same key")

        // And the group signs with them, through the signing manager, as the room.
        val (alice, bob) = all
        val session = FrostSigningManager.proposeSigning(alice.db, alice.room, alice.publicKey, kind = 1, tags = arrayOf(), content = "After the rotation")
        assertEquals(1, session.generation)
        pump(all)
        FrostSigningManager.approve(bob.db, bob.room, session.id)
        pump(all)
        val item = alice.db.frostSigningSessionDao().getItems(session.id).single()
        assertEquals(FrostSigningStage.COMPLETE, alice.db.frostSigningSessionDao().getSessionById(session.id)?.stage)
        assertTrue(Nip01Crypto.verify(assertNotNull(item.signature).hexToByteArray(), item.eventId.hexToByteArray(), group.roomId.hexToByteArray()))
    }

    @Test
    fun `nothing of a member's own goes out before they approve, but the proposer is not asked`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)

        all.drop(1).forEach { device ->
            assertEquals(emptyList(), outbox(device).map { it.kind }, "device ${device.index}")
            assertNotNull(ShareRefreshManager.pendingApproval(device.db, device.refresh(refresh.id)!!), "device ${device.index} must be asked")
        }
        // Proposing is the proposer's agreement, made from a dialog that named
        // everyone left out: its device deals at once, and its owner is not asked.
        val proposer = all[0]
        assertEquals(listOf(ShareRefreshEvents.PROPOSAL, ShareRefreshEvents.ROUND_1), outbox(proposer).map { it.kind })
        assertNull(ShareRefreshManager.pendingApproval(proposer.db, proposer.refresh(refresh.id)!!))
        assertTrue(proposer.db.chatMessageDao().getChatMessagesByChatRoomId(group.roomId).none { it.chatMessage.messageType == ChatMessage.TYPE_SHARE_REFRESH_APPROVAL_NEEDED })
    }

    // ---- Phase 4 of rotate-shares-single-approval: what the proposer is shown ----------------------------------------

    /** The dialog shows what the proposal will say: the same recipients, the same members left out, as the same standing. */
    @Test
    fun `the preview is the proposal`() = runBlocking<Unit> {
        suspend fun agrees(by: Device, without: HexKey? = null) {
            val preview = assertNotNull(ShareRefreshManager.preview(by.db, by.room, by.publicKey, without))
            assertNull(preview.refusal, preview.refusal)
            val refresh = if (without == null) propose(by) else ShareRefreshManager.proposeWithout(by.db, by.room, by.publicKey, by.privateKey, without)
            val roster = by.active().roster.associate { it.participantId to it.memberPublicKey }
            assertEquals(preview.takingPart, refresh.recipientIdList().map { roster.getValue(it) })
            assertEquals(preview.withheld, refresh.withheldStandings().mapKeys { roster.getValue(it.key) })
        }

        // Nobody stale, nobody removed.
        val fresh = devicesFor(Group(3, 2))
        agrees(fresh[0])

        // One stale, withheld again without being asked for.
        val (_, rotated) = rotatedWithout(2)
        val stale = ShareRefreshManager.preview(rotated[0].db, rotated[0].room, rotated[0].publicKey)!!
        assertEquals(mapOf(rotated[2].publicKey to ShareStanding.STALE), stale.withheld)
        agrees(rotated[0])
    }

    @Test
    fun `the preview of a removal names the removed for good and the stale until they are back`() = runBlocking<Unit> {
        val group = Group(5, 2)
        val all = devicesFor(group)
        val first = propose(all[0], mapOf(group.idOf[4] to ShareStanding.STALE))
        pump(all)
        approveAll(all, first.id)
        approveSigning(all)

        val preview = assertNotNull(ShareRefreshManager.preview(all[0].db, all[0].room, all[0].publicKey, without = all[3].publicKey))
        assertNull(preview.refusal, preview.refusal)
        assertEquals(mapOf(all[3].publicKey to ShareStanding.RETIRED, all[4].publicKey to ShareStanding.STALE), preview.withheld)
        assertEquals(setOf(all[0], all[1], all[2]).map { it.publicKey }.toSet(), preview.takingPart.toSet())
    }

    @Test
    fun `the preview of a rotation that would be refused says why and sends nothing`() = runBlocking<Unit> {
        val group = Group(2, 2)
        val (alice, bob) = devicesFor(group)
        val preview = assertNotNull(ShareRefreshManager.preview(alice.db, alice.room, alice.publicKey, without = bob.publicKey))
        assertTrue(preview.refusal!!.contains("fewer than the 2 it takes to sign"), preview.refusal)
        assertTrue(outbox(alice).isEmpty())
        assertNull(alice.db.shareRefreshDao().getSessionsForKey(ceremonyId).firstOrNull())
    }

    // ---- the approvals, counted (docs/rotate-shares-single-approval.md) ---------------------------------------------

    /**
     * Taking part is the only approval. With every recipient's owner agreeing to
     * take part and nothing more, every device stores its new share, confirms it,
     * and arrives at the new generation; and nothing ever asks to store it.
     */
    @Test
    fun `after taking part, every device stores its new share without being asked again`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all)

        all.forEach { device ->
            val session = device.refresh(refresh.id)!!
            assertEquals(ShareRefreshStage.COMPLETE, session.stage, "device ${device.index}")
            assertNull(ShareRefreshManager.pendingApproval(device.db, session), "device ${device.index}")
            assertNull(session.confirmApprovedAt, "nothing writes the old second approval")
            assertEquals(ShareGenerationState.ACTIVE, device.db.keyShareDao().getGeneration(ceremonyId, 1)?.state, "device ${device.index}")
            assertTrue(outbox(device).any { it.kind == ShareRefreshEvents.ROUND_2 }, "device ${device.index} did not confirm")
            val lines = device.db.chatMessageDao().getChatMessagesByChatRoomId(group.roomId)
                .filter { it.chatMessage.messageType == ChatMessage.TYPE_SHARE_REFRESH_APPROVAL_NEEDED }
            assertEquals(if (device.index == 0) 0 else 1, lines.size, "device ${device.index} must be asked once, the proposer never")
        }
    }

    /**
     * The last recipient to take part deals at once, and confirms on the next
     * pass that brings the coordinator's sum, with no call in between.
     */
    @Test
    fun `the last to take part deals and then confirms with nothing more asked`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        all.dropLast(1).forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all)
        val last = all.last()
        assertTrue(outbox(last).none { it.kind == ShareRefreshEvents.ROUND_1 })

        ShareRefreshManager.approve(last.db, last.room, refresh.id, last.privateKey)
        assertTrue(outbox(last).any { it.kind == ShareRefreshEvents.ROUND_1 }, "it deals in the approve call")
        pump(all)
        assertTrue(outbox(last).any { it.kind == ShareRefreshEvents.ROUND_2 }, "and confirms when the sum arrives")
        all.forEach { assertEquals(ShareRefreshStage.COMPLETE, it.refresh(refresh.id)?.stage) }
    }

    /**
     * What the second approval was said to protect, protected without it: a
     * coordinator's sum altered on its way fails the rotation on every device,
     * and nobody arrives at a new generation.
     */
    @Test
    fun `a tampered sum fails the rotation with nobody asked`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all, tamper = { event ->
            if (event.kind != ShareRefreshEvents.COORDINATOR_ROUND_1) event
            else event.copy(content = event.content.dropLast(2) + if (event.content.endsWith("00")) "01" else "00")
        })

        all.forEach { device ->
            val session = device.refresh(refresh.id)!!
            assertEquals(ShareRefreshStage.FAILED, session.stage, "device ${device.index}")
            assertEquals(0, device.active().generation, "device ${device.index} moved to a generation")
        }
        assertTrue(all.drop(1).any { it.refresh(refresh.id)!!.failureReason!!.contains("Share refresh") }, "an honest recipient has to say what it found")
    }


    /**
     * The upgrade: an older build stored the coordinator's sum and stopped, asking
     * its owner to store the new share. Nothing more will arrive, so only the
     * sweep can move it -- and it does, once, and the rotation completes.
     */
    @Test
    fun `a rotation left waiting on the old second approval resumes from the sweep`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val (coordinator, others) = all.first() to all.drop(1)
        val refresh = propose(coordinator)
        pump(all)
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        // The sum goes out, but the others' build files it and stops.
        pump(all, hold = { it.kind == ShareRefreshEvents.COORDINATOR_ROUND_1 })
        val sum = outbox(coordinator).single { it.kind == ShareRefreshEvents.COORDINATOR_ROUND_1 }
        others.forEach { device ->
            device.received += sum.id
            device.db.marmotInnerEventDao().upsert(sum.copy(chatRoomId = group.roomId))
            device.db.shareRefreshDao().upsert(
                device.refresh(refresh.id)!!.copy(
                    coordinatorRound1 = sum.content,
                    stage = ShareRefreshStage.COLLECTING_ROUND_2,
                    approvalRequestedThrough = com.machankura.dedun.compose.database.model.types.ShareRefreshApprovalStep.CONFIRM,
                )
            )
            assertTrue(outbox(device).none { it.kind == ShareRefreshEvents.ROUND_2 }, "device ${device.index}")
        }
        pump(all)
        others.forEach { assertTrue(outbox(it).none { e -> e.kind == ShareRefreshEvents.ROUND_2 }, "nothing arrives to move device ${it.index}") }

        others.forEach { assertEquals(1, ShareRefreshManager.resumeRunning(it.db, it.publicKey, it.privateKey)) }
        others.forEach { assertEquals(1, outbox(it).count { e -> e.kind == ShareRefreshEvents.ROUND_2 }, "device ${it.index} confirmed from the sweep") }
        pump(all)
        all.forEach { assertEquals(ShareRefreshStage.COMPLETE, it.refresh(refresh.id)?.stage, "device ${it.index}") }

        // Twice more: nothing runs, nothing goes out.
        val sent = all.map { outbox(it).size }
        repeat(2) { all.forEach { device -> assertEquals(0, ShareRefreshManager.resumeRunning(device.db, device.publicKey, device.privateKey)) } }
        assertEquals(sent, all.map { outbox(it).size })
    }

    /** A rotation nobody has taken part in yet is looked at by the sweep and sends nothing. */
    @Test
    fun `the sweep sends nothing for a member who has not taken part`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        val member = all[1]
        assertEquals(1, ShareRefreshManager.resumeRunning(member.db, member.publicKey, member.privateKey))
        assertTrue(outbox(member).isEmpty())
        assertEquals(com.machankura.dedun.compose.database.model.types.ShareRefreshApprovalStep.DEAL, ShareRefreshManager.pendingApproval(member.db, member.refresh(refresh.id)!!))
        val asked = member.db.chatMessageDao().getChatMessagesByChatRoomId(group.roomId).count { it.chatMessage.messageType == ChatMessage.TYPE_SHARE_REFRESH_APPROVAL_NEEDED }
        assertEquals(1, asked, "asked once, however often the sweep looks")
    }

    @Test
    fun `a build without rotation resumes nothing`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        propose(all[0])
        pump(all)
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = false
        assertEquals(0, ShareRefreshManager.resumeRunning(all[1].db, all[1].publicKey, all[1].privateKey))
    }

    /**
     * The whole of the change: a recipient is asked one thing before the old
     * shares are deleted -- to take part. Storing the new share and signing the
     * new generation's key state follow from it. The proposer is asked nothing:
     * proposing was taking part.
     */
    @Test
    fun `a recipient is asked only to take part`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        val asks = answerEverything(all, refresh.id)

        val rotation = listOf(Ask.Rotation(com.machankura.dedun.compose.database.model.types.ShareRefreshApprovalStep.DEAL))
        assertEquals(emptyList(), asks[0], "the proposer, whose proposal was its answer")
        all.drop(1).forEach { device -> assertEquals(rotation, asks[device.index], "device ${device.index}") }
        all.forEach { device ->
            assertEquals(1, device.db.groupKeyStateDao().getByChatRoomId(group.roomId)?.generation, "device ${device.index}")
            assertNull(device.key().secretShare, "device ${device.index} kept the old share")
        }
    }

    /**
     * A completed rotation's key state is the one statement its recipients'
     * devices can vouch for, so they sign it with nobody asked, and every device
     * deletes the share it was meant to delete.
     */
    @Test
    fun `a completed rotation signs its new key state and the old shares go, with nobody asked`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all)

        all.forEach { device ->
            assertEquals(ShareRefreshStage.COMPLETE, device.refresh(refresh.id)?.stage)
            assertEquals(1, device.db.groupKeyStateDao().getByChatRoomId(group.roomId)?.generation, "device ${device.index}")
            assertNull(device.key().secretShare, "device ${device.index} kept the old share")
            val signing = device.db.frostSigningSessionDao()
            assertTrue(signing.getSessionsForChatRoom(group.roomId).none { FrostSigningManager.isAwaitingApproval(it, signing.getItems(it.id)) }, "device ${device.index} was asked")
        }
    }

    /**
     * The coordinator proposes the key state the moment its own certificate
     * verifies, so a recipient can hear the proposal first. It cannot vouch for a
     * rotation not yet complete here; once the certificate arrives, it can, and
     * does, without its owner.
     */
    @Test
    fun `a key state heard before the certificate is vouched for once the certificate arrives`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all, hold = { it.kind == ShareRefreshEvents.CERTIFICATE })

        val member = all[1]
        val signing = member.db.frostSigningSessionDao()
        val keyState = signing.getSessionsForChatRoom(group.roomId).single()
        assertEquals(1, keyState.generation)
        assertNull(keyState.signApprovedAt, "not vouched for before the rotation completes here")
        assertTrue(member.refresh(refresh.id)!!.stage != ShareRefreshStage.COMPLETE)

        pump(all)
        assertNotNull(signing.getSessionById(keyState.id)?.signApprovedAt, "vouched for once it completes")
        all.forEach { device ->
            assertEquals(1, device.db.groupKeyStateDao().getByChatRoomId(group.roomId)?.generation, "device ${device.index}")
            assertNull(device.key().secretShare, "device ${device.index}")
        }
    }

    /** Each rule, broken on its own, leaves a rotation's key state with the member. */
    @Test
    fun `a device vouches for its rotation's key state and nothing else`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        // Every device completes the rotation; the key state is proposed, and held.
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all, hold = { FrostSigningEvents.isProposal(it.kind) })
        val (coordinator, member) = all
        assertEquals(ShareRefreshStage.COMPLETE, member.refresh(refresh.id)?.stage)

        val proposed = coordinator.db.frostSigningSessionDao().getSessionsForChatRoom(group.roomId).single()
        val session = proposed.copy(userPublicKey = member.publicKey, signApprovedAt = null)
        val item = coordinator.db.frostSigningSessionDao().getItems(proposed.id).single()
        val event = Event.fromJson(item.unsignedEventJson)
        fun with(tags: Array<Array<String>>) = listOf(item.copy(unsignedEventJson = Event(event.id, event.pubKey, event.createdAt, event.kind, tags, event.content, event.sig).toJson()))
        fun replacing(name: String, value: String) = with(event.tags.map { if (it.firstOrNull() == name) arrayOf(name, value) else it }.toTypedArray())
        suspend fun vouches(on: Device = member, s: com.machankura.dedun.compose.database.model.FrostSigningSession = session, items: List<com.machankura.dedun.compose.database.model.FrostSigningItem> = listOf(item)) =
            GroupKeyStateManager.vouchesFor(on.db, s.copy(userPublicKey = on.publicKey), items)

        assertTrue(vouches(), "the one true statement")
        assertTrue(!vouches(items = replacing("frost_pubshares", "ab".repeat(32))), "another table")
        assertTrue(!vouches(items = replacing("frost_refresh", "cd".repeat(32))), "another rotation")
        assertTrue(!vouches(items = with(event.tags + arrayOf("frost_retired", group.idOf[2].toString()))), "retiring someone this rotation did not")
        val binding = com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent.MemberBinding(group.idOf[2], KeyPair().pubKey.toHexKey(), group.hostKeys[0])
        assertTrue(!vouches(items = with(event.tags + arrayOf("frost_member", binding.encode()))), "binding an id to a new identity")
        assertTrue(!vouches(s = session.copy(generation = 0)), "a session at another generation")
        assertTrue(!vouches(items = replacing("frost_generation", "2")), "a generation this device does not sign with")
        assertTrue(!vouches(s = session.copy(derivationPath = SharedKeyDerivation.formatPath(listOf(9420L, 0L, 1L)))), "at another path")

        // A rotation not complete here: the same statement, from a device that has not finished.
        member.db.shareRefreshDao().upsert(member.refresh(refresh.id)!!.copy(stage = ShareRefreshStage.COLLECTING_ROUND_2))
        assertTrue(!vouches(), "a rotation that is not complete here")
        member.db.shareRefreshDao().upsert(member.refresh(refresh.id)!!.copy(stage = ShareRefreshStage.COMPLETE))

        // Once the group has signed it, the next statement at the same generation is not the one true one.
        all.forEach { it.received.clear() }
        pump(all)
        assertEquals(1, member.db.groupKeyStateDao().getByChatRoomId(group.roomId)?.generation)
        assertTrue(!vouches(), "a room already holding a state at this generation")
    }

    /** A member withheld from the rotation holds no share of it, and vouches for nothing it did not help make. */
    @Test
    fun `a withheld member does not vouch for the new key state`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val withheld = all[2]
        val refresh = propose(all[0], mapOf(group.idOf[2] to ShareStanding.STALE))
        pump(all)
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all, hold = { FrostSigningEvents.isProposal(it.kind) })
        assertEquals(ShareRefreshStage.COMPLETE, withheld.refresh(refresh.id)?.stage)

        val proposed = all[0].db.frostSigningSessionDao().getSessionsForChatRoom(group.roomId).single()
        val items = all[0].db.frostSigningSessionDao().getItems(proposed.id)
        assertTrue(GroupKeyStateManager.vouchesFor(all[1].db, proposed.copy(userPublicKey = all[1].publicKey), items), "a recipient does")
        assertTrue(!GroupKeyStateManager.vouchesFor(withheld.db, proposed.copy(userPublicKey = withheld.publicKey), items), "the withheld member does not")
    }

    /** A key state an older build left asking is answered by the sweep, once. */
    @Test
    fun `a key state left asking by an older build is signed from the sweep`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all, hold = { FrostSigningEvents.isProposal(it.kind) })

        // The proposal arrives at a member as an older build filed it: stored, asked, not vouched for.
        val member = all[1]
        val proposal = outbox(all[0]).first { FrostSigningEvents.isProposal(it.kind) }
        member.received += proposal.id
        member.db.marmotInnerEventDao().upsert(proposal.copy(chatRoomId = group.roomId))
        val shareRefresh = member.refresh(refresh.id)!!
        member.db.shareRefreshDao().upsert(shareRefresh.copy(stage = ShareRefreshStage.COLLECTING_ROUND_2))
        FrostSigningManager.processSigningPayload(member.db, member.room, Event(proposal.id, proposal.publicKey, proposal.createdAt.epochSeconds, proposal.kind, proposal.tags, proposal.content, ""), member.publicKey)
        member.db.shareRefreshDao().upsert(shareRefresh)
        val filed = member.db.frostSigningSessionDao().getSessionsForChatRoom(group.roomId).single()
        assertNull(filed.signApprovedAt, "filed and asked, as the older build left it")

        assertEquals(1, FrostSigningManager.resumeVouched(member.db, member.publicKey))
        assertNotNull(member.db.frostSigningSessionDao().getSessionById(filed.id)?.signApprovedAt)
        assertEquals(0, FrostSigningManager.resumeVouched(member.db, member.publicKey), "once")
    }

    // ---- Phase 6 of rotate-shares-single-approval: the Shares section ------------------------------------------------

    private suspend fun summaryOn(device: Device): com.machankura.dedun.compose.ui.view.state.SharesSummary {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job())
        return try {
            assertNotNull(com.machankura.dedun.compose.database.repository.DatabaseChatRepository(device.db, scope).sharesSummary(device.room), "device ${device.index}")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private fun nameOf(device: Device) = device.publicKey.take(8)

    @Test
    fun `a rotation asks one thing, to take part`() {
        assertEquals(
            listOf(com.machankura.dedun.compose.ui.view.state.SharesSummary.PendingApproval.Kind.REFRESH_DEAL, com.machankura.dedun.compose.ui.view.state.SharesSummary.PendingApproval.Kind.REPAIR_HELP),
            com.machankura.dedun.compose.ui.view.state.SharesSummary.PendingApproval.Kind.entries.toList(),
        )
    }

    /** Taking part is agreeing to who is left out, so the row that asks it names them. */
    @Test
    fun `the taking-part row names who is left out, and the withheld member sees the rotation run`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        propose(all[0], mapOf(group.idOf[2] to ShareStanding.STALE))
        pump(all)

        val asked = summaryOn(all[1])
        val row = asked.pending.single()
        assertEquals(com.machankura.dedun.compose.ui.view.state.SharesSummary.PendingApproval.Kind.REFRESH_DEAL, row.kind)
        assertEquals(listOf(com.machankura.dedun.compose.ui.view.state.SharesSummary.Withheld(nameOf(all[2]), ShareStanding.STALE)), row.withheld)
        assertNull(asked.running, "the row covers a member not yet asked")

        val withheld = summaryOn(all[2])
        assertTrue(withheld.pending.isEmpty())
        val running = assertNotNull(withheld.running)
        assertEquals(listOf(nameOf(all[1])), running.waitingOn)
        assertTrue(!running.canAbandon, "only a member taking part may end it")
    }

    /**
     * Once a member has taken part there is nothing left to approve, so the
     * running rotation is what they see: the step, who it waits on, and abandon.
     */
    @Test
    fun `a running rotation names who it waits on at each step`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val (alice, bob, carol) = all
        val refresh = propose(alice)
        pump(all)
        ShareRefreshManager.approve(bob.db, bob.room, refresh.id, bob.privateKey)
        pump(all)

        val dealing = assertNotNull(summaryOn(bob).running)
        assertEquals(com.machankura.dedun.compose.ui.view.state.SharesSummary.Running.Step.DEALING, dealing.step)
        assertEquals(listOf(nameOf(carol)), dealing.waitingOn)
        assertTrue(dealing.canAbandon, "a member who took part can still end it")
        assertTrue(summaryOn(bob).pending.isEmpty())
        assertTrue(summaryOn(bob).rotation is com.machankura.dedun.compose.ui.view.state.SharesSummary.Rotation.Held)

        // Carol takes part; her confirmation is slow to arrive.
        ShareRefreshManager.approve(carol.db, carol.room, refresh.id, carol.privateKey)
        pump(all, hold = { it.kind == ShareRefreshEvents.ROUND_2 && it.publicKey == carol.publicKey })
        val storing = assertNotNull(summaryOn(bob).running)
        assertEquals(com.machankura.dedun.compose.ui.view.state.SharesSummary.Running.Step.STORING, storing.step)
        assertEquals(listOf(nameOf(carol)), storing.waitingOn)

        // A relay repeating itself is not progress. Dated a day back first, so a
        // redelivery that rewrote a row would show, whatever the clock's precision.
        val dao = bob.db.shareRefreshDao()
        val dayAgo = kotlin.time.Clock.System.now() - kotlin.time.Duration.parse("1d")
        dao.getMessagesForSession(refresh.id).forEach { dao.upsert(it.copy(createdAt = dayAgo, updatedAt = dayAgo, savedAt = dayAgo)) }
        val since = assertNotNull(summaryOn(bob).running).since
        assertTrue(kotlin.time.Clock.System.now() - since > kotlin.time.Duration.parse("23h"))
        bob.received.clear()
        pump(all, hold = { it.kind == ShareRefreshEvents.ROUND_2 && it.publicKey == carol.publicKey })
        assertEquals(since, summaryOn(bob).running?.since, "a redelivered message moved the stall clock")
    }

    @Test
    fun `the last step is the group signing the new key state, which nobody abandons`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all, hold = { FrostSigningEvents.isProposal(it.kind) })

        val signing = assertNotNull(summaryOn(all[0]).running, "the coordinator's own key state is open")
        assertEquals(com.machankura.dedun.compose.ui.view.state.SharesSummary.Running.Step.SIGNING, signing.step)
        assertTrue(!signing.canAbandon)

        pump(all)
        all.forEach { assertNull(summaryOn(it).running, "device ${it.index}: nothing is running once it is signed") }
    }

    @Test
    fun `an abandoned rotation says why until the next one`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        ShareRefreshManager.approve(all[1].db, all[1].room, refresh.id, all[1].privateKey)
        ShareRefreshManager.abandon(all[1].db, all[1].room, refresh.id)
        pump(all)

        all.forEach { device ->
            val summary = summaryOn(device)
            assertNull(summary.running)
            assertTrue(summary.lastFailure!!.contains("Abandoned"), summary.lastFailure)
            assertEquals(com.machankura.dedun.compose.ui.view.state.SharesSummary.Rotation.Available, summary.rotation)
        }
    }

    /**
     * Two members propose from one base before either hears of the other. Each is
     * approved for its own and refuses the other on arrival, as a recipient,
     * out loud -- so neither stalls and nothing forks.
     */
    @Test
    fun `two proposals crossing on the wire end with neither stuck and at most one generation`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val first = propose(all[0])
        val second = propose(all[1])
        pump(all)
        approveAll(all, first.id)
        approveAll(all, second.id)

        all.forEach { device ->
            val stages = listOf(first.id, second.id).map { device.refresh(it)?.stage }
            assertTrue(stages.none { it != null && it != ShareRefreshStage.COMPLETE && it != ShareRefreshStage.FAILED }, "device ${device.index} left one running: $stages")
            assertTrue(stages.count { it == ShareRefreshStage.COMPLETE } <= 1, "device ${device.index}: $stages")
            assertTrue(ShareRefreshManager.rotationHold(device.db, device.room, device.key()) !is RotationHold.Running, "device ${device.index}")
        }
        assertEquals(1, all.map { it.active().publicShares }.toSet().size, "every device on one table")
    }

    @Test
    fun `a withheld member ends with the new table and no share, and declines to sign`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val withheldDevice = all[2]
        val withheldId = group.idOf[2]
        val refresh = propose(all[0], mapOf(withheldId to ShareStanding.STALE))
        pump(all)
        approveAll(all, refresh.id)

        // The withheld device never dealt nor confirmed...
        assertTrue(outbox(withheldDevice).none { it.kind == ShareRefreshEvents.ROUND_1 || it.kind == ShareRefreshEvents.ROUND_2 })
        // ...but followed the certificate to the new generation, with no share of it.
        val onWithheld = withheldDevice.active()
        assertEquals(1, onWithheld.generation)
        assertNull(onWithheld.secretShare)
        assertEquals(ShareStanding.STALE, onWithheld.roster.single { it.participantId == withheldId }.standing)
        assertEquals(all[0].active().publicShares, onWithheld.publicShares)
        assertNull(FrostSigningManager.completedKey(withheldDevice.db, group.roomId))

        val session = FrostSigningManager.proposeSigning(all[0].db, all[0].room, all[0].publicKey, kind = 1, tags = arrayOf(), content = "Without them")
        pump(all)
        val decline = outbox(withheldDevice).single { it.kind == FrostSigningEvents.DECLINE && FrostSigningEvents.parseSessionId(it.tags) == session.id }
        assertEquals(FrostSigningManager.GENERATION_REFUSAL, decline.content)
    }

    @Test
    fun `fewer recipients than a majority is refused`() = runBlocking<Unit> {
        val group = Group(4, 2)
        val all = devicesFor(group)
        val proposer = all[0]
        val others = (0 until 4).filter { it != group.idOf[0] }.take(2)
        val error = assertFailsWith<IllegalStateException> { propose(proposer, others.associateWith { ShareStanding.RETIRED }) }
        assertTrue(error.message!!.contains("not a majority"), error.message)
        assertNull(proposer.db.shareRefreshDao().getSessionsForKey(ceremonyId).firstOrNull())
    }

    /**
     * The rule that keeps a group from splitting. Two refreshes from one
     * generation could otherwise both complete; a device's one confirmation per
     * generation is what stops the second.
     */
    @Test
    fun `a device confirms one refresh per generation`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val device = devicesFor(group).first()
        val dao = device.db.shareRefreshDao()
        fun generation(refresh: String) = KeyShareGeneration(ceremonyId, 1, "ab".repeat(32), "02".padEnd(66, '0'), ShareGenerationState.PENDING, refreshSessionId = refresh)

        assertEquals("a".repeat(64), dao.confirmAndStore(ShareRefreshConfirmation(ceremonyId, 0, "a".repeat(64)), generation("a".repeat(64)), emptyList()))
        assertEquals("a".repeat(64), dao.confirmAndStore(ShareRefreshConfirmation(ceremonyId, 0, "b".repeat(64)), generation("b".repeat(64)), emptyList()))
        assertEquals("a".repeat(64), device.db.keyShareDao().getGeneration(ceremonyId, 1)?.refreshSessionId, "the second refresh must have written nothing")
    }

    @Test
    fun `a second proposal while one runs hands back the first`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val first = propose(all[0])
        pump(all)
        // Bob's own device has the first refresh running, so it opens nothing new
        // and hands that one back.
        assertEquals(first.id, propose(all[1]).id)
    }

    @Test
    fun `a redelivered transcript changes nothing`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        approveAll(all, refresh.id)
        val before = all.map { it.db.chatMessageDao().getChatMessagesByChatRoomId(group.roomId).size to it.active().secretShare }

        all.forEach { it.received.clear() }
        pump(all)

        assertEquals(before, all.map { it.db.chatMessageDao().getChatMessagesByChatRoomId(group.roomId).size to it.active().secretShare })
        all.forEach { assertEquals(ShareRefreshStage.COMPLETE, it.refresh(refresh.id)?.stage) }
    }

    // ---- Phase 8: the cutover ----------------------------------------------------------------------------------

    /** Every owner approves every signing session waiting on them, until none is. */
    private suspend fun approveSigning(between: List<Device>) {
        var asked = true
        while (asked) {
            asked = false
            for (device in between) {
                device.db.frostSigningSessionDao().getSessionsForChatRoom(device.group.roomId)
                    .filter { it.signApprovedAt == null && it.signDeclinedAt == null && it.stage != FrostSigningStage.COMPLETE && it.stage != FrostSigningStage.FAILED }
                    .forEach { FrostSigningManager.approve(device.db, device.room, it.id); asked = true }
            }
            pump(between)
        }
    }

    /**
     * The point of the phase. The coordinator asks the group to sign the new
     * generation's key state as the refresh completes; once a quorum has, every
     * device that holds the new generation deletes the ceremony's share and the
     * randomness that rebuilds it, and keeps signing with the new one.
     */
    @Test
    fun `once the group signs the new generation, every device deletes its old share`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        // The key state is held on its way, so the rotation completes with nothing signed yet.
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all, hold = { FrostSigningEvents.isProposal(it.kind) })
        all.forEach { assertEquals(ShareRefreshStage.COMPLETE, it.refresh(refresh.id)?.stage) }

        // Not yet: the old share stays until the new ones have signed something.
        all.forEach { assertNotNull(it.key().secretShare, "device ${it.index} deleted before the group signed") }

        pump(all)
        approveSigning(all)

        all.forEach { device ->
            val state = assertNotNull(device.db.groupKeyStateDao().getByChatRoomId(group.roomId), "device ${device.index} filed no key state")
            assertEquals(1, state.generation)
            assertEquals(ShareGenerations.publicSharesHash(device.active().publicShares!!), state.publicSharesHash)
            val key = device.key()
            assertNull(key.secretShare, "device ${device.index} kept the ceremony's share")
            assertEquals("", key.round1Random, "the randomness that rebuilds the share has to go too")
            assertEquals("", key.round2AuxRandom)
            assertNull(ShareGenerations.at(device.db, key, 0)!!.secretShare)
            assertNotNull(device.active().secretShare, "and the new share stays")
        }

        // The group still signs, with the new shares.
        val session = FrostSigningManager.proposeSigning(all[1].db, all[1].room, all[1].publicKey, kind = 1, tags = arrayOf(), content = "Still the room")
        pump(all)
        approveSigning(all)
        assertEquals(FrostSigningStage.COMPLETE, all[1].db.frostSigningSessionDao().getSessionById(session.id)?.stage)
    }

    @Test
    fun `a session left at the old generation fails out loud when its shares go`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val old = FrostSigningManager.proposeSigning(all[0].db, all[0].room, all[0].publicKey, kind = 1, tags = arrayOf(), content = "Before the rotation")
        assertEquals(0, old.generation)
        pump(all)

        val refresh = propose(all[0])
        pump(all)
        approveAll(all, refresh.id)
        // Approve only the key state's session, not the old one.
        all.forEach { device ->
            device.db.frostSigningSessionDao().getSessionsForChatRoom(group.roomId)
                .filter { it.id != old.id && it.signApprovedAt == null && it.signDeclinedAt == null }
                .forEach { FrostSigningManager.approve(device.db, device.room, it.id) }
        }
        pump(all)

        val failed = all[0].db.frostSigningSessionDao().getSessionById(old.id)!!
        assertEquals(FrostSigningStage.FAILED, failed.stage)
        assertEquals("This share was rotated before the session finished", failed.failureReason)
    }

    @Test
    fun `a key state at an older generation loses whatever its timestamp`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val device = devicesFor(group).first()
        val dao = device.db.groupKeyStateDao()
        val base = com.machankura.dedun.compose.database.model.GroupKeyState(
            chatRoomId = group.roomId, dkgSessionId = ceremonyId, thresholdPublicKey = group.thresholdPublicKey,
            derivationPath = SharedKeyDerivation.formatPath(), announcedBy = device.publicKey,
            announcedAt = kotlin.time.Instant.fromEpochSeconds(2_000), generation = 1, publicSharesHash = "aa".repeat(32),
        )
        dao.replace(base)
        val older = base.copy(announcedAt = kotlin.time.Instant.fromEpochSeconds(9_000), generation = null, publicSharesHash = null)
        assertEquals(1, dao.replace(older).generation)
        assertEquals(1, dao.getByChatRoomId(group.roomId)?.generation)
    }

    // ---- Phase 9: rotating without a removed member --------------------------------------------------------------

    /**
     * A removed member is out of the room but still holds a share. Rotating
     * without them, withheld for good, leaves the other two signing at the new
     * generation -- and the removed member's share, which their device still has,
     * combines with nothing of it.
     */
    @Test
    fun `rotating without a removed member leaves their share useless`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val (alice, bob, carol) = all
        val carolId = group.idOf[2]
        assertEquals(carolId, ShareRefreshManager.liveIdOf(alice.db, alice.room, carol.publicKey))

        val refresh = ShareRefreshManager.proposeWithout(alice.db, alice.room, alice.publicKey, alice.privateKey, carol.publicKey)
        assertEquals(mapOf(carolId to ShareStanding.RETIRED), refresh.withheldStandings())
        pump(all)
        approveAll(all, refresh.id)
        approveSigning(all)

        listOf(alice, bob).forEach { device ->
            val active = device.active()
            assertEquals(1, active.generation)
            assertEquals(ShareStanding.RETIRED, active.roster.single { it.participantId == carolId }.standing)
            assertNull(active.signerIds[carol.publicKey], "carol must not be a signer")
            assertNull(ShareRefreshManager.liveIdOf(device.db, device.room, carol.publicKey))
        }
        // Retired is not stale: carol's id is gone from the ids that count.
        assertNull(alice.active().participantIds[carol.publicKey])

        // Alice and Bob sign at generation 1.
        val session = FrostSigningManager.proposeSigning(alice.db, alice.room, alice.publicKey, kind = 1, tags = arrayOf(), content = "Without carol")
        assertEquals(1, session.generation)
        pump(all)
        approveSigning(all)
        assertEquals(FrostSigningStage.COMPLETE, alice.db.frostSigningSessionDao().getSessionById(session.id)?.stage)

        // Carol's old share and Alice's new one: no signature.
        val table = alice.active().publicShares!!
        val aliceId = group.idOf[0]
        val mixed = (0 until 3).map { id ->
            when (id) {
                carolId -> group.keyMaterial.secretShares[carolId]
                aliceId -> alice.active().secretShare!!
                else -> bob.active().secretShare!!
            }
        }
        val km = KeyMaterial(group.keyMaterial.thresholdPublicKey, mixed, table, 2)
        val signers = listOf(aliceId, carolId).sorted().map { it.toUInt() }
        val msg = ByteVector32("5a".repeat(32))
        val signs = runCatching {
            val nonces = signers.map { id -> Frost.generateNonce(ByteVector32("0${id}".padEnd(64, '7')), km.secretShares[id.toInt()], km.publicShares[id.toInt()], km.tweakCache().tweakedPublicKey, msg, null) }
            val publicNonces = nonces.map { it.second }
            val partials = signers.mapIndexed { i, id -> Frost.sign(km.secretShares[id.toInt()], nonces[i].first, id, msg, signers, km, publicNonces).right!! }
            val sig = Frost.aggregatePartialSignatures(partials, msg, signers, km, publicNonces).right!!
            fr.acinq.bitcoin.Crypto.verifySignatureSchnorr(msg, sig, km.tweakCache().tweakedPublicKey)
        }.getOrDefault(false)
        assertTrue(!signs, "a removed member's old share must not sign with a new one")
    }

    @Test
    fun `rotating without a member is refused when too few would be left to sign`() = runBlocking<Unit> {
        val group = Group(2, 2)
        val (alice, bob) = devicesFor(group)
        val error = assertFailsWith<IllegalStateException> {
            ShareRefreshManager.proposeWithout(alice.db, alice.room, alice.publicKey, alice.privateKey, bob.publicKey)
        }
        assertTrue(error.message!!.contains("fewer than the 2 it takes to sign"), error.message)
    }

    // ---- a rotation waits for the last one to be signed (docs/rotation-waits-for-key-state.md) -------------------

    /**
     * Signing proposals arrive, and nothing after them: a key state stays open,
     * approved or not. Since docs/rotate-shares-single-approval.md the new
     * generation's key state signs itself, so a test that looks at a rotation
     * whose key state is not yet signed has to hold it there.
     */
    private val signingHeldOpen: (MarmotInnerEvent) -> Boolean = { it.kind in FrostSigningEvents.ALL && !FrostSigningEvents.isProposal(it.kind) }

    /** Every recipient takes part and the rotation completes; its key state is proposed and left open. */
    private suspend fun takePart(all: List<Device>, refreshId: String) {
        all.forEach { ShareRefreshManager.approve(it.db, it.room, refreshId, it.privateKey) }
        pump(all, hold = signingHeldOpen)
    }

    @Test
    fun `the next rotation waits until the group has signed the last one`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val (alice, bob) = all
        assertNull(ShareRefreshManager.rotationHold(alice.db, alice.room, alice.key()), "generation 0 waits on nothing")

        val first = propose(alice)
        pump(all)
        assertEquals(RotationHold.Running(first.id), ShareRefreshManager.rotationHold(bob.db, bob.room, bob.key()))
        takePart(all, first.id)

        // The refresh is complete and its key state is out for signatures: everyone waits.
        all.forEach { device ->
            val hold = ShareRefreshManager.rotationHold(device.db, device.room, device.key())
            assertTrue(hold is RotationHold.AwaitingKeyState && hold.generation == 1 && hold.signingSessionId != null, "device ${device.index}: $hold")
        }
        val refused = assertFailsWith<IllegalStateException> { propose(bob) }
        assertTrue(refused.message!!.contains("generation 1"), refused.message)
        assertEquals(listOf(first.id), bob.db.shareRefreshDao().getSessionsForKey(ceremonyId).map { it.id }, "nothing new was opened")

        approveSigning(all)
        all.forEach { assertNull(ShareRefreshManager.rotationHold(it.db, it.room, it.key()), "device ${it.index}") }
        assertNotEquals(first.id, propose(bob).id)
    }

    @Test
    fun `rotating without a member waits as well`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val (alice, _, carol) = all
        val first = propose(alice)
        pump(all)
        takePart(all, first.id)

        val refused = assertFailsWith<IllegalStateException> {
            ShareRefreshManager.proposeWithout(alice.db, alice.room, alice.publicKey, alice.privateKey, carol.publicKey)
        }
        assertTrue(refused.message!!.contains("not yet signed"), refused.message)
    }

    @Test
    fun `a key state with nobody asking for it still holds the next rotation`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val first = propose(all[0])
        pump(all)
        takePart(all, first.id)
        // The coordinator's key-state proposal failed, or its device went away.
        all.forEach { device ->
            device.db.frostSigningSessionDao().getSessionsForChatRoom(group.roomId).forEach {
                device.db.frostSigningSessionDao().upsert(it.copy(stage = FrostSigningStage.FAILED))
            }
        }
        val hold = ShareRefreshManager.rotationHold(all[1].db, all[1].room, all[1].key())
        assertEquals(RotationHold.AwaitingKeyState(1, signingSessionId = null), hold)
    }

    @Test
    fun `a rebind waiting on signatures holds a rotation, until this device declines it`() = runBlocking<Unit> {
        // Four members, one left stale: three live signers of a 2-of-n key, so a
        // single refusal leaves a session open rather than ending it.
        val group = Group(4, 2)
        val all = devicesFor(group)
        val (alice, bob, carol) = all
        val staleId = group.idOf[3]
        val refresh = propose(alice, mapOf(staleId to ShareStanding.STALE))
        pump(all)
        approveAll(all, refresh.id)
        approveSigning(all)
        assertNull(ShareRefreshManager.rotationHold(alice.db, alice.room, alice.key()))

        val daveKeys = KeyPair()
        val daveHost = ChillDkgRitualManager.deriveHostPublicKey(daveKeys.privKey!!).value.toHex()
        val rebind = GroupKeyStateManager.proposeRebind(alice.db, alice.room, alice.publicKey, alice.key(), staleId, daveKeys.pubKey.toHexKey(), daveHost)
        pump(all)

        listOf(alice, bob, carol).forEach { device ->
            assertEquals(RotationHold.AwaitingKeyState(1, rebind.id), ShareRefreshManager.rotationHold(device.db, device.room, device.key()), "device ${device.index}")
        }

        // One refusal does not end it, so it sits open; it no longer holds Bob, who refused it.
        FrostSigningManager.decline(bob.db, bob.room, rebind.id)
        pump(all)
        assertNotEquals(FrostSigningStage.FAILED, bob.db.frostSigningSessionDao().getSessionById(rebind.id)!!.stage)
        assertNull(ShareRefreshManager.rotationHold(bob.db, bob.room, bob.key()))
        assertEquals(RotationHold.AwaitingKeyState(1, rebind.id), ShareRefreshManager.rotationHold(carol.db, carol.room, carol.key()))
    }

    @Test
    fun `a device that missed the certificate cannot propose from the old generation`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val first = propose(all[0])
        pump(all)
        approveAll(all, first.id)
        approveSigning(all)

        // Alice on a second device that heard the group's signature but none of the refresh.
        val late = device(group, 0)
        val signed = assertNotNull(GroupKeyStateManager.signedEventFor(all[1].db, group.roomId)).toEvent()
        assertNotNull(GroupKeyStateManager.record(late.db, group.roomId, signed))
        assertEquals(0, late.active().generation)

        assertEquals(RotationHold.Behind(groupGeneration = 1, localGeneration = 0), ShareRefreshManager.rotationHold(late.db, late.room, late.key()))
        assertFailsWith<IllegalStateException> { propose(late) }
    }

    /** The rotate action as the room's Shares section reads it. */
    private suspend fun rotation(device: Device): SharesSummary.Rotation? =
        DatabaseChatRepository(device.db, CoroutineScope(Job())).sharesSummary(device.room)?.rotation

    @Test
    fun `the Shares section holds the button while a rotation is unfinished, and says why`() = runBlocking<Unit> {
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        val group = Group(3, 2)
        val all = devicesFor(group)
        val (alice, bob) = all
        assertEquals(SharesSummary.Rotation.Available, rotation(bob))

        val refresh = propose(alice)
        pump(all)
        assertEquals(SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.ROTATION_RUNNING), rotation(bob))

        takePart(all, refresh.id)
        val awaiting = SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.AWAITING_SIGNATURES, canAskForSignature = false)
        assertEquals(awaiting, rotation(bob), "the coordinator is already asking")

        // The coordinator's proposal fails: nobody is asking, so Bob may.
        all.forEach { device ->
            device.db.frostSigningSessionDao().getSessionsForChatRoom(group.roomId).forEach {
                device.db.frostSigningSessionDao().upsert(it.copy(stage = FrostSigningStage.FAILED))
            }
        }
        assertEquals(awaiting.copy(canAskForSignature = true), rotation(bob))
        val asked = ShareRefreshManager.proposeOwedKeyState(bob.db, bob.room, bob.publicKey, bob.key())
        pump(all, hold = signingHeldOpen)
        assertEquals(awaiting, rotation(bob))
        assertFailsWith<IllegalStateException>("one ask at a time") {
            ShareRefreshManager.proposeOwedKeyState(alice.db, alice.room, alice.publicKey, alice.key())
        }

        approveSigning(all)
        assertEquals(FrostSigningStage.COMPLETE, bob.db.frostSigningSessionDao().getSessionById(asked.id)?.stage)
        all.forEach { assertEquals(SharesSummary.Rotation.Available, rotation(it), "device ${it.index}") }
        val stated = Event.fromJson(bob.db.frostSigningSessionDao().getItems(asked.id).single().unsignedEventJson)
        assertTrue(stated.tags.any { it.contains(refresh.id) }, "the refresh is named as the coordinator would have named it")
    }

    @Test
    fun `a member left out of a rotation is offered no rotate button`() = runBlocking<Unit> {
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        val (_, all) = rotatedWithout(2)
        assertEquals(SharesSummary.Rotation.Unavailable, rotation(all[2]))
        assertEquals(SharesSummary.Rotation.Available, rotation(all[0]))
    }

    // ---- Phase 10: re-enrollment -----------------------------------------------------------------------------------

    /** Every helper approves every repair waiting on them. */
    private suspend fun approveRepairs(between: List<Device>) {
        var asked = true
        while (asked) {
            asked = false
            for (device in between) {
                device.db.shareRepairDao().getSessionsForKey(ceremonyId)
                    .filter { ShareRepairManager.isAwaitingApproval(device.db, it) }
                    .forEach { ShareRepairManager.approve(device.db, device.room, it.id, device.privateKey); asked = true }
            }
            pump(between)
        }
    }

    /** A 3-member 2-of-3 group that has rotated without [withheldIndex], left stale, with its key state signed and its old shares deleted. */
    private suspend fun rotatedWithout(withheldIndex: Int): Pair<Group, List<Device>> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all.first { it.index != withheldIndex }, mapOf(group.idOf[withheldIndex] to ShareStanding.STALE))
        pump(all)
        approveAll(all, refresh.id)
        approveSigning(all)
        return group to all
    }

    /**
     * 10a: a member withheld from a refresh -- offline, or on a build that could
     * not take part -- is brought back by two helpers, with a share of the new
     * generation its old one is not, and signs.
     */
    @Test
    fun `a stale member is repaired into the new generation and signs`() = runBlocking<Unit> {
        val (group, all) = rotatedWithout(2)
        val (alice, bob, carol) = all
        val carolId = group.idOf[2]
        assertNull(carol.active().secretShare)

        val repair = ShareRepairManager.requestRepair(carol.db, carol.room, carol.publicKey, carol.privateKey, carol.key())
        assertEquals(com.machankura.dedun.compose.database.model.types.ShareRepairStage.COLLECTING, repair.stage)
        pump(all)
        // Nothing of a helper's goes out before its owner approves.
        assertTrue(outbox(alice).none { it.kind in com.machankura.dedun.compose.nostr.frost.ShareRepairEvents.ALL })
        approveRepairs(all)

        val repaired = assertNotNull(carol.active().secretShare, "carol must hold a share of generation 1")
        assertEquals(carol.active().publicShares!![carolId], repaired.publicKey())
        assertNotEquals(group.keyMaterial.secretShares[carolId], repaired, "not the share her old device had")
        all.forEach { device ->
            assertEquals(ShareStanding.LIVE, device.active().roster.single { it.participantId == carolId }.standing, "on device ${device.index}")
        }

        // Carol signs, with Alice; Bob stays out.
        val session = FrostSigningManager.proposeSigning(alice.db, alice.room, alice.publicKey, kind = 1, tags = arrayOf(), content = "Carol is back")
        pump(all)
        FrostSigningManager.approve(carol.db, carol.room, session.id)
        pump(all)
        assertEquals(FrostSigningStage.COMPLETE, alice.db.frostSigningSessionDao().getSessionById(session.id)?.stage)
        assertTrue(alice.db.frostSigningSessionDao().getSessionById(session.id)!!.signerIdList()!!.contains(carolId))
        assertNull(bob.db.frostSigningSessionDao().getSessionById(session.id)?.signApprovedAt)
    }

    /** A repair whose expected public share is not the one the group signed fails at round 2, not at signing. */
    @Test
    fun `a repair checked against the wrong public share stores nothing`() = runBlocking<Unit> {
        val (group, all) = rotatedWithout(2)
        val carol = all[2]
        val carolId = group.idOf[2]
        // Carol's table says something else at her id.
        val row = carol.db.keyShareDao().getGeneration(ceremonyId, 1)!!
        val table = row.publicShareList()!!.toMutableList().also { it[carolId] = it[(carolId + 1) % 3] }
        carol.db.keyShareDao().upsert(row.copy(publicShares = table.joinToString(",") { it.value.toHex() }))

        val repair = ShareRepairManager.requestRepair(carol.db, carol.room, carol.publicKey, carol.privateKey, carol.key())
        pump(all)
        approveRepairs(all)

        assertEquals(com.machankura.dedun.compose.database.model.types.ShareRepairStage.FAILED, carol.db.shareRepairDao().getSessionById(repair.id)?.stage)
        assertNull(carol.active().secretShare)
        assertEquals(ShareStanding.STALE, all[0].active().roster.single { it.participantId == carolId }.standing, "nobody marks her live without a proof")
    }

    /**
     * 10b: Carol's device is lost; Dave -- her new identity -- comes back at her
     * id. A request from an identity the group has not bound waits, with nothing
     * sent; once the group signs the binding, the same repair runs, and Dave signs.
     */
    @Test
    fun `a new identity is bound to a stale id before it is repaired into it`() = runBlocking<Unit> {
        val (group, all) = rotatedWithout(2)
        val (alice, bob) = all
        val carolId = group.idOf[2]
        val daveKeys = KeyPair()
        val daveHost = ChillDkgRitualManager.deriveHostPublicKey(daveKeys.privKey!!).value.toHex()
        val dave = newcomer(group, daveKeys, from = alice)
        val everyone = listOf(alice, bob, dave)

        // Dave asks before the group has bound him: everyone waits, and no helper sends anything.
        val repair = ShareRepairManager.requestRepair(dave.db, dave.room, dave.publicKey, dave.privateKey, dave.key(), targetId = carolId)
        assertEquals(com.machankura.dedun.compose.database.model.types.ShareRepairStage.AWAITING_BINDING, repair.stage)
        pump(everyone)
        listOf(alice, bob).forEach {
            assertEquals(com.machankura.dedun.compose.database.model.types.ShareRepairStage.AWAITING_BINDING, it.db.shareRepairDao().getSessionById(repair.id)?.stage)
            assertTrue(!ShareRepairManager.isAwaitingApproval(it.db, it.db.shareRepairDao().getSessionById(repair.id)!!))
        }

        // The group binds Carol's id to Dave, and Dave's device learns it as the chronicle would hand it over.
        GroupKeyStateManager.proposeRebind(alice.db, alice.room, alice.publicKey, alice.key(), carolId, dave.publicKey, daveHost)
        pump(everyone)
        approveSigning(listOf(alice, bob))
        pump(everyone)
        val signed = assertNotNull(GroupKeyStateManager.signedEventFor(alice.db, group.roomId)).toEvent()
        GroupKeyStateManager.record(dave.db, group.roomId, signed)
        everyone.forEach { device ->
            val entry = device.active().roster.single { it.participantId == carolId }
            assertEquals(dave.publicKey, entry.memberPublicKey, "on device ${device.index}")
            assertEquals(ShareStanding.STALE, entry.standing)
        }

        approveRepairs(everyone)
        val share = assertNotNull(dave.active().secretShare, "dave must hold carol's id's share of generation 1")
        assertEquals(dave.active().publicShares!![carolId], share.publicKey())
        listOf(alice, bob).forEach { assertEquals(ShareStanding.LIVE, it.active().roster.single { e -> e.participantId == carolId }.standing) }
    }

    /**
     * A newcomer's device, seeded with what the member chronicle would hand it:
     * the key's public record -- the ceremony without a share, its host keys, and
     * the current generation's table and roster -- and nothing secret.
     */
    private suspend fun newcomer(group: Group, keyPair: KeyPair, from: Device): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        val publicKey = keyPair.pubKey.toHexKey()
        (group.members + publicKey).forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }
        val chatRoom = ChatRoom(id = group.roomId, userPublicKey = publicKey, subject = "#Ekklesia", description = SharedKeyDerivation.describe("Admins."), mlsGroupState = "00")
        db.chatRoomDao().upsert(chatRoom)
        val key = from.key()
        db.dkgSessionDao().upsert(key.copy(userPublicKey = publicKey, secretShare = null, round1Random = "", round2AuxRandom = "", hostPublicKey = ChillDkgRitualManager.deriveHostPublicKey(keyPair.privKey!!).value.toHex()))
        from.db.dkgSessionDao().getMessagesByKind(ceremonyId, DkgRitualEvents.HOST_KEY).forEach { db.dkgSessionDao().upsert(it) }
        from.db.keyShareDao().getGenerations(ceremonyId).forEach { generation ->
            db.keyShareDao().upsert(generation.copy(secretShare = null))
            db.keyShareDao().upsert(from.db.keyShareDao().getMembers(ceremonyId, generation.generation))
        }
        return Device(group, -1, db, LocalChatRoom(chatRoom = chatRoom), keyPair).also { devices += it }
    }

    // ---- member-admin-promotion, Phase 8: enrollment at a new id -----------------------------------------------

    /** A 3-member 2-of-3 group that has rotated once, everyone included, with its key state signed. */
    private suspend fun rotatedOnce(): Pair<Group, List<Device>> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)
        approveAll(all, refresh.id)
        approveSigning(all)
        return group to all
    }

    /** A device for [keyPair] holding nothing of the key at all: the room, the members' profiles, no ceremony. */
    private suspend fun stranger(group: Group, keyPair: KeyPair): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        val publicKey = keyPair.pubKey.toHexKey()
        (group.members + publicKey).forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }
        val chatRoom = ChatRoom(id = group.roomId, userPublicKey = publicKey, subject = "#Ekklesia", description = SharedKeyDerivation.describe("Admins."), mlsGroupState = "00")
        db.chatRoomDao().upsert(chatRoom)
        return Device(group, -1, db, LocalChatRoom(chatRoom = chatRoom), keyPair).also { devices += it }
    }

    /** The group signs the key state binding the next id to [member] under [host], with that id's public share added. */
    private suspend fun bindNextId(all: List<Device>, member: HexKey, host: HexKey) {
        val alice = all[0]
        val table = alice.active().publicShares!!
        val binding = com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent.MemberBinding(table.size, member, host)
        val template = GroupKeyStateManager.generationTemplate(
            alice.db, alice.room, alice.key(), refreshId = null, newBinding = binding,
            publicShares = PromotionManager.extended(table, alice.key().threshold),
        )
        FrostSigningManager.proposeSigning(alice.db, alice.room, alice.publicKey, template.kind, template.tags, template.content, alice.key(), template.createdAt)
        pump(all)
        approveSigning(all)
        pump(all)
    }

    /** The handover the proposer's device would send, as Erin's device receives it. */
    private suspend fun handoverFrom(from: Device, to: HexKey): MarmotInnerEvent =
        MarmotInnerEvent(
            id = "f".repeat(64), publicKey = from.publicKey, kind = com.machankura.dedun.compose.nostr.frost.PromotionEvents.HANDOVER,
            tags = com.machankura.dedun.compose.nostr.frost.PromotionEvents.handoverTags(from.group.roomId, to),
            content = assertNotNull(PromotionManager.handoverContent(from.db, from.group.roomId)),
            chatRoomId = from.group.roomId,
        )

    /**
     * A member who never took part in the ceremony is enrolled at id 3 of a 2-of-3
     * key: every device adds the id from the key state the group signed, the new
     * member checks what they are handed against it, two holders run reduced
     * enrollment with nobody asked, and the new share signs.
     */
    @Test
    fun `a new member is enrolled at the next id, and signs with the group`() = runBlocking<Unit> {
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        val (_, all) = rotatedOnce()
        val (alice, bob, carol) = all
        val erinKeys = KeyPair()
        val erinHost = ChillDkgRitualManager.deriveHostPublicKey(erinKeys.privKey!!).value.toHex()
        val erin = stranger(all[0].group, erinKeys)

        bindNextId(all, erin.publicKey, erinHost)
        all.forEach { device ->
            assertEquals(4, device.active().publicShares!!.size, "the table on device ${device.index}")
            val entry = device.active().roster.single { it.participantId == 3 }
            assertEquals(erin.publicKey, entry.memberPublicKey)
            assertEquals(ShareStanding.STALE, entry.standing, "id 3 waits for its share")
        }

        PromotionManager.takeHandover(erin.db, erin.room, erin.publicKey, erin.privateKey, handoverFrom(alice, erin.publicKey))
        val everyone = all + erin
        pump(everyone)

        val share = assertNotNull(erin.active().secretShare, "Erin must hold a share of id 3")
        assertEquals(alice.active().publicShares!![3], share.publicKey(), "a share on the group's polynomial")
        everyone.forEach { device ->
            assertEquals(ShareStanding.LIVE, device.active().roster.single { it.participantId == 3 }.standing, "id 3 on device ${device.index}")
        }

        // Erin signs, with Alice; Bob and Carol stay out.
        val session = FrostSigningManager.proposeSigning(alice.db, alice.room, alice.publicKey, kind = 1, tags = arrayOf(), content = "Erin signs")
        assertEquals(4, session.participantCount, "n is the table's, which grew")
        pump(everyone)
        FrostSigningManager.approve(erin.db, erin.room, session.id)
        pump(everyone)
        assertEquals(FrostSigningStage.COMPLETE, alice.db.frostSigningSessionDao().getSessionById(session.id)?.stage)
        assertTrue(alice.db.frostSigningSessionDao().getSessionById(session.id)!!.signerIdList()!!.contains(3))
        listOf(bob, carol).forEach { assertNull(it.db.frostSigningSessionDao().getSessionById(session.id)?.signApprovedAt) }
    }

    /** A handover that does not match what the group signed is refused, and nothing is asked for. */
    @Test
    fun `a handover whose table the group did not sign is refused`() = runBlocking<Unit> {
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        val (_, all) = rotatedOnce()
        val erinKeys = KeyPair()
        val erin = stranger(all[0].group, erinKeys)
        bindNextId(all, erin.publicKey, ChillDkgRitualManager.deriveHostPublicKey(erinKeys.privKey!!).value.toHex())

        val honest = handoverFrom(all[0], erin.publicKey)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(honest.content).jsonObject
        val shares = json["publicShares"]!!.jsonArray
        val swapped = kotlinx.serialization.json.JsonArray(listOf(shares[1], shares[0]) + shares.drop(2))
        val forged = honest.copy(content = kotlinx.serialization.json.JsonObject(json + ("publicShares" to swapped)).toString())

        PromotionManager.takeHandover(erin.db, erin.room, erin.publicKey, erin.privateKey, forged)

        assertNull(erin.db.dkgSessionDao().getSessionById(ceremonyId), "a key was filed from a forged handover")
        assertTrue(erin.db.shareRepairDao().getSessionsForKey(ceremonyId).isEmpty())
    }

    /** A binding of the next id whose table this device cannot extend to is not applied. */
    @Test
    fun `an id enrolled with a table that does not extend this one is not added`() = runBlocking<Unit> {
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        val (_, all) = rotatedOnce()
        val alice = all[0]
        val table = alice.active().publicShares!!
        val state = assertNotNull(GroupKeyStateManager.keyStateFor(alice.db, alice.group.roomId))
        // The next id, but a hash over some other fourth share.
        val wrong = state.copy(publicSharesHash = ShareGenerations.publicSharesHash(table + table[0]))
        PromotionManager.enrollNewId(alice.db, wrong, com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent.MemberBinding(3, "a".repeat(64), "02" + "b".repeat(64)))

        assertEquals(3, alice.active().publicShares!!.size)
        assertTrue(alice.active().roster.none { it.participantId == 3 })
    }

    // ---- docs/admin-change-rotation.md: today's behaviour, pinned ---------------------------------------------------

    /**
     * The rotation a removal owes, as `GovernanceFollowThrough.rotateAway` opens it.
     * Pinned before Phase 5: every remaining holder is asked to take part, though the
     * group's signature on the removal already decided who is withheld.
     */
    @Test
    fun `pinned -- after a removal, every remaining holder is asked to take part in its rotation`() = runBlocking<Unit> {
        val group = Group(4, 2)
        val all = devicesFor(group)
        val (alice, bob, carol, dave) = all
        val refresh = ShareRefreshManager.proposeWithout(alice.db, alice.room, alice.publicKey, alice.privateKey, dave.publicKey)
        pump(all)

        listOf(bob, carol).forEach { device ->
            assertEquals(ShareRefreshApprovalStep.DEAL, ShareRefreshManager.pendingApproval(device.db, device.refresh(refresh.id)!!), "device ${device.index}")
        }
        all.forEach { assertEquals(0, it.active().generation, "device ${it.index} moved without anyone agreeing") }
    }

    /**
     * H1: a removal that lands while a rotation runs. The rotation it owes is handed
     * the running one back -- which was proposed before the removal, deals to the
     * removed member, and leaves them a live share of the new generation.
     */
    @Test
    fun `pinned -- a removal during a rotation leaves the removed member a live share`() = runBlocking<Unit> {
        val group = Group(4, 2)
        val all = devicesFor(group)
        val (alice, _, _, dave) = all
        val running = propose(alice)
        pump(all)

        val owed = ShareRefreshManager.proposeWithout(alice.db, alice.room, alice.publicKey, alice.privateKey, dave.publicKey)
        assertEquals(running.id, owed.id, "the owed rotation was handed the running one")
        approveAll(all, running.id)

        assertEquals(1, alice.active().generation)
        assertEquals(ShareStanding.LIVE, alice.active().roster.single { it.memberPublicKey == dave.publicKey }.standing)
    }

    /**
     * H2: a promotion's key state signed while a rotation runs. The new id grows the
     * base generation's table under a rotation dealt against the old one, and the
     * rotation's own check refuses what it combines: the whole rotation fails.
     */
    @Test
    fun `pinned -- a new admin bound during a rotation fails the rotation`() = runBlocking<Unit> {
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = true
        val (_, all) = rotatedOnce()
        val alice = all[0]
        val running = propose(alice)
        pump(all)

        val erinKeys = KeyPair()
        bindNextId(all, erinKeys.pubKey.toHexKey(), ChillDkgRitualManager.deriveHostPublicKey(erinKeys.privKey!!).value.toHex())
        approveAll(all, running.id)

        assertEquals(1, alice.active().generation)
        all.forEach { device ->
            val refresh = device.refresh(running.id)!!
            assertEquals(ShareRefreshStage.FAILED, refresh.stage, "device ${device.index}")
            assertTrue("does not match the commitments" in refresh.failureReason.orEmpty(), refresh.failureReason)
        }
    }

    /**
     * H4: a governance statement being signed at generation g when the rotation's
     * key state at g+1 is signed. Its session is failed with the old shares.
     */
    @Test
    fun `pinned -- a statement still being signed fails when a rotation lands`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val alice = all[0]
        val statement = FrostSigningManager.proposeSigning(
            alice.db, alice.room, alice.publicKey,
            kind = com.machankura.dedun.compose.nostr.frost.GovernanceStatementEvent.KIND, tags = arrayOf(), content = "{}",
        )
        val holdStatement = { event: MarmotInnerEvent -> event.tags.any { tag -> statement.id in tag } }
        val refresh = propose(alice)
        var asked = true
        while (asked) {
            asked = false
            pump(all, hold = holdStatement)
            all.forEach { device ->
                val session = device.refresh(refresh.id) ?: return@forEach
                if (ShareRefreshManager.pendingApproval(device.db, session) != null) {
                    ShareRefreshManager.approve(device.db, device.room, refresh.id, device.privateKey)
                    asked = true
                }
            }
        }
        pump(all, hold = holdStatement)

        assertEquals(1, alice.active().generation)
        assertEquals(FrostSigningStage.FAILED, alice.db.frostSigningSessionDao().getSessionById(statement.id)?.stage)
    }

    // ---- Phase 12: restore ---------------------------------------------------------------------------------------

    /**
     * A restored device has its nsec and the room's backlog. ChillDKG's recovery
     * gives it generation 0 (here dealt by hand, so given), and every refresh's
     * recovery data, applied in turn, gives the next: the walk lands on the share
     * the device holds live, three refreshes later.
     */
    @Test
    fun `the recovery walk rebuilds the live share three refreshes on`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        repeat(3) { round ->
            val refresh = propose(all[round % 3])
            pump(all)
            approveAll(all, refresh.id)
            // The next rotation waits for this one's key state, and the old shares go.
            approveSigning(all)
        }
        all.forEach { device ->
            assertEquals(3, device.active().generation)
            val hostSecretKey = ChillDkgRitualManager.deriveHostSecretKey(device.privateKey)
            val id = group.idOf[device.index]
            val walked = ShareGenerations.recoverFrom(device.db, device.key(), hostSecretKey, group.keyMaterial.secretShares[id])
            assertEquals((0..3).toList(), walked.keys.toList(), "on device ${device.index}")
            assertEquals(device.active().secretShare, walked[3])
            (1..3).forEach { g ->
                val table = device.db.keyShareDao().getGeneration(ceremonyId, g)!!.publicShareList()!!
                assertEquals(table[id], walked.getValue(g).publicKey(), "generation $g on device ${device.index}")
            }
        }
    }

    // ---- Phase 13: a release build ---------------------------------------------------------------------------------

    /** A build without the feature declines a rotation it is asked into, out loud, rather than leaving it waiting. */
    @Test
    fun `a release build declines a rotation out loud`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        com.machankura.dedun.compose.ExperimentalFeatures.shareRefreshOverride = false
        pump(all)

        assertEquals(ShareRefreshStage.FAILED, all[0].refresh(refresh.id)?.stage)
        assertTrue(all[0].refresh(refresh.id)!!.failureReason!!.contains("this build cannot rotate shares"), all[0].refresh(refresh.id)!!.failureReason)
        assertFailsWith<IllegalStateException> { propose(all[1]) }
    }
}
