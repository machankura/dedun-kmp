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

    private suspend fun pump(between: List<Device>) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                outbox(from).forEach { queued ->
                    if (!to.received.add(queued.id)) return@forEach
                    moved = true
                    to.db.marmotInnerEventDao().upsert(queued.copy(chatRoomId = to.group.roomId))
                    val event = Event(queued.id, queued.publicKey, queued.createdAt.epochSeconds, queued.kind, queued.tags, queued.content, "")
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

        // The proposer confirmed who is withheld before proposing, and is the
        // coordinator CONFIRM guards against: it deals at once and is asked nothing
        // (docs/rotation-waits-for-key-state.md, Phase 4).
        assertEquals(listOf(ShareRefreshEvents.PROPOSAL, ShareRefreshEvents.ROUND_1), outbox(all[0]).map { it.kind })
        assertNull(ShareRefreshManager.pendingApproval(all[0].db, all[0].refresh(refresh.id)!!), "the proposer must not be asked")
        assertTrue(all[0].db.chatMessageDao().getChatMessagesByChatRoomId(group.roomId).none { it.chatMessage.messageType == ChatMessage.TYPE_SHARE_REFRESH_APPROVAL_NEEDED })

        // Everyone else is asked, and has sent nothing.
        all.drop(1).forEach { device ->
            assertEquals(emptyList(), outbox(device).map { it.kind }, "device ${device.index}")
            assertEquals(ShareRefreshApprovalStep.DEAL, ShareRefreshManager.pendingApproval(device.db, device.refresh(refresh.id)!!), "device ${device.index} must be asked")
        }

        // And once they have dealt, they are asked again before confirming; the proposer is not.
        all.drop(1).forEach { ShareRefreshManager.approve(it.db, it.room, refresh.id, it.privateKey) }
        pump(all)
        assertNull(ShareRefreshManager.pendingApproval(all[0].db, all[0].refresh(refresh.id)!!))
        all.drop(1).forEach { device ->
            assertEquals(ShareRefreshApprovalStep.CONFIRM, ShareRefreshManager.pendingApproval(device.db, device.refresh(refresh.id)!!), "device ${device.index}")
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
        approveAll(all, refresh.id)

        // Not yet: the old share stays until the new ones have signed something.
        all.forEach { assertNotNull(it.key().secretShare, "device ${it.index} deleted before the group signed") }

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

    @Test
    fun `the next rotation waits until the group has signed the last one`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val (alice, bob) = all
        assertNull(ShareRefreshManager.rotationHold(alice.db, alice.room, alice.key()), "generation 0 waits on nothing")

        val first = propose(alice)
        pump(all)
        assertEquals(RotationHold.Running(first.id), ShareRefreshManager.rotationHold(bob.db, bob.room, bob.key()))
        approveAll(all, first.id)

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
        approveAll(all, first.id)

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
        approveAll(all, first.id)
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

        approveAll(all, refresh.id)
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
        pump(all)
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
