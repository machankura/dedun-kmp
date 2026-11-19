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
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.types.ShareGenerationState
import com.machankura.dedun.compose.database.model.types.ShareRefreshStage
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.frost.ShareRefreshEvents

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
    fun closeDatabases() = devices.forEach { it.db.close() }

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

    private inner class Device(val group: Group, val index: Int, val db: DedunDatabase, val room: LocalChatRoom) {
        val publicKey: HexKey get() = group.members[index]
        val privateKey: ByteArray get() = group.keyPairs[index].privKey!!
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

    private val ferried = (ShareRefreshEvents.ALL + FrostSigningEvents.ALL).toList()

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
    fun `nothing of a member's own goes out before they approve`() = runBlocking<Unit> {
        val group = Group(3, 2)
        val all = devicesFor(group)
        val refresh = propose(all[0])
        pump(all)

        all.forEach { device ->
            assertEquals(listOf(ShareRefreshEvents.PROPOSAL).takeIf { device.index == 0 } ?: emptyList(), outbox(device).map { it.kind }, "device ${device.index}")
            assertNotNull(ShareRefreshManager.pendingApproval(device.db, device.refresh(refresh.id)!!), "device ${device.index} must be asked")
        }
        // The proposer is asked like anyone: proposing is not agreeing to withhold whoever the form defaulted to.
        assertTrue(all[0].db.chatMessageDao().getChatMessagesByChatRoomId(group.roomId).any { it.chatMessage.messageType == ChatMessage.TYPE_SHARE_REFRESH_APPROVAL_NEEDED })
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
}
