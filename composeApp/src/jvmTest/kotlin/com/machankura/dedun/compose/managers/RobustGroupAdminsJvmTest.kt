package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.marmot.groups.isLocalAdmin
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DatabaseMarmotRepository
import com.machankura.dedun.compose.database.repository.DatabaseNostrRepository
import com.machankura.dedun.compose.nostr.MarmotProfile

/**
 * The promise a robust group makes -- "everyone administers the group together" --
 * held on every device of every founding member. docs/robust-group-admins.md.
 *
 * Two halves. The ceremony half runs real ChillDKG on [CeremonyNetwork] and checks
 * that every device agrees who the founding members are. The room half has one app
 * device create the admin room and the others join it by a real Welcome, gift
 * wrapped and stored through the same funnel a relay's copy goes through, because
 * the Welcome is where a joiner first learns -- or fails to learn -- who its
 * admins are.
 */
class RobustGroupAdminsJvmTest {

    /** One phone: its own database, as in the app, and nothing shared with the others. */
    private class Phone(profile: MarmotProfile) : AutoCloseable {
        val keyPair = KeyPair()
        val publicKey: HexKey = keyPair.pubKey.toHexKey()
        val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
        val chatRepository = DatabaseChatRepository(db, CoroutineScope(Dispatchers.Default))
        private val nostrRepository = DatabaseNostrRepository(db, CoroutineScope(Dispatchers.Default))
        private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))

        /** This phone's key package, as another phone would have synced it. */
        lateinit var keyPackage: MarmotKeyPackage

        val ready: suspend () -> Unit = {
            marmotRepository.publishMarmotKeyPackageBundle(keyPair, setOf(profile))
            val bundle = db.marmotKeyPackageBundleDao().getAllMarmotKeyPackageBundles(publicKey).single()
            keyPackage = MarmotKeyPackage(
                id = bundle.id,
                publicKey = publicKey,
                tlsEncodedMarmotKeyPackage = bundle.tlsEncodedMarmotKeyPackage,
                profile = profile.wireName,
            )
        }

        /**
         * A relay's OK on the newest commit this phone put on the wire. An invite
         * into a room that already has members holds its Welcome back until then.
         */
        suspend fun okTheLatestCommit() {
            val event = db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 20)
                .filter { it.kind == GroupEvent.KIND }
                .last { db.marmotCommitResultDao().getMarmotCommitRequestById(it.id) != null }
            val request = assertNotNull(db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(event.id))
            nostrRepository.broadcastProcessed(request, "published")
        }

        override fun close() = db.close()
        override fun toString() = publicKey.take(8)
    }

    private val foundingThresholdPublicKey = "02" + KeyPair().pubKey.toHexKey()
    private val roomId = SharedKeyDerivation.marmotGroupId(foundingThresholdPublicKey)

    /**
     * Three phones; the first creates the admin room with all three as its admins,
     * as `CeremonyFollowThrough` does after a ceremony, and the other two join it.
     */
    private suspend fun foundedRoom(profile: MarmotProfile, size: Int = 3): List<Phone> {
        val phones = List(size) { Phone(profile) }
        phones.forEach { it.ready() }
        phones.forEach { phone -> phones.forEach { MarmotStandIn.seedProfile(phone.db, it.publicKey) } }

        val creator = phones.first()
        phones.drop(1).forEach { creator.db.marmotKeyPackageDao().upsert(it.keyPackage) }

        val outcome = MarmotGroupCreation.create(
            database = creator.db,
            chatRepository = creator.chatRepository,
            groupId = roomId,
            name = "Ekklesia",
            purpose = "Admins of Ekklesia.",
            adminPublicKeys = phones.map { it.publicKey }.toSet(),
            userPublicKey = creator.publicKey,
            keyPair = creator.keyPair,
            keyPackageLookupTimeout = 2_000L,
            profile = profile,
        )
        val created = assertIs<MarmotGroupCreation.Outcome.Created>(outcome)
        assertTrue(created.notAdded.isEmpty(), "the creator could not invite ${created.notAdded}")

        phones.drop(1).forEach { welcome(from = creator, to = it) }
        return phones
    }

    /** Hands [to] the Welcome [from] queued for it, wrapped as a relay would carry it. */
    private suspend fun welcome(from: Phone, to: Phone, roomId: String = this.roomId) {
        val payload = from.db.giftWrapPayloadDao().getAllGiftWrapUnsigned()
            .filter { it.kind == WelcomeEvent.KIND }
            .single { payload -> payload.tags.any { it.size > 1 && it[1] == to.keyPackage.id } }

        val rumor = RumorAssembler.assembleRumor<WelcomeEvent>(
            pubKey = from.publicKey,
            ev = EventTemplate(payload.createdAt.epochSeconds, payload.kind, payload.tags, payload.content),
        )
        val signer = NostrSignerSync(from.keyPair)
        val seal = signer.signNormal<SealEvent>(
            createdAt = rumor.createdAt, kind = SealEvent.KIND, tags = emptyArray(),
            content = signer.nip44Encrypt(rumor.toJson(), to.publicKey),
        )
        val wrap = GiftWrapEvent.create(event = seal, recipientPubKey = to.publicKey, createdAt = rumor.createdAt)

        MarmotStandIn.storeInApp(to.db, to.keyPair, wrap)
        assertNotNull(to.db.chatRoomDao().findChatRoomById(roomId), "$to did not join from the Welcome")
    }

    // ---- The room ----------------------------------------------------------------

    /** [block] on a freshly founded room, once on each profile a room can be made on. */
    private fun onEveryProfile(
        profiles: List<MarmotProfile> = listOf(MarmotProfile.LEGACY, MarmotProfile.CURRENT),
        block: suspend (MarmotProfile, List<Phone>) -> Unit,
    ) = runBlocking {
        profiles.forEach { profile ->
            val phones = foundedRoom(profile)
            try {
                block(profile, phones)
            } finally {
                phones.forEach { it.close() }
            }
        }
    }

    private suspend fun Phone.room() = assertNotNull(db.chatRoomDao().findChatRoomById(roomId), "$this has no room")

    /** Whom this phone's own rows call an admin. */
    private suspend fun Phone.flaggedAdmins(): Set<HexKey> = room().localParticipants
        .map { it.participant }
        .filter { it.deletedAt == null && it.adminAt != null }
        .map { it.participantPublicKey }
        .toSet()

    @Test
    fun `every founding member is in the room's admin policy, on every device`() = onEveryProfile { profile, phones ->
        val founders = phones.map { it.publicKey }.toSet()

        phones.forEach { phone ->
            val group = assertNotNull(phone.room().chatRoom.toMlsGroup(), "$phone holds no group ($profile)")
            assertEquals(founders, group.currentAdminIdentities(), "$phone's admin policy ($profile)")
            assertTrue(group.isLocalAdmin(), "$phone is not an admin of its own room ($profile)")
        }
    }

    /**
     * The rows, which are what the screens label members with. The creator's are
     * caught up by `MarmotGroupCreation`; a joiner's come from the Welcome, which
     * wrote none of them until Phase 2 put `AdminFlags.reconcile` on that path.
     */
    @Test
    fun `every device flags every founding member as an admin`() = onEveryProfile { profile, phones ->
        val founders = phones.map { it.publicKey }.toSet()

        phones.forEach { phone ->
            assertEquals(founders, phone.flaggedAdmins(), "$phone's rows say a different set are admins ($profile)")
        }
    }

    /**
     * The boundary docs/robust-group-admins.md, D1 draws: founding, not present.
     *
     * On both profiles since Phase 4. Before it, `MarmotGroupCreation` invited the
     * founders on the current profile through a room read before its routing id was
     * recorded, so the engine's group at the real address still held only the
     * creator, and this later invite took the founding path -- no commit, and
     * nobody else told.
     */
    @Test
    fun `a member added after the founding is not an admin`() = onEveryProfile { profile, phones ->
        val founders = phones.map { it.publicKey }.toSet()
        val creator = phones.first()

        Phone(profile).use { later ->
            later.ready()
            (phones + later).forEach { phone -> (phones + later).forEach { MarmotStandIn.seedProfile(phone.db, it.publicKey) } }

            creator.chatRepository.inviteMember(creator.room(), later.publicKey, later.keyPackage)
            creator.okTheLatestCommit()
            welcome(from = creator, to = later)

            listOf(creator, later).forEach { phone ->
                val group = assertNotNull(phone.room().chatRoom.toMlsGroup(), "$phone holds no group ($profile)")
                assertEquals(founders, group.currentAdminIdentities(), "$phone's admin policy ($profile)")
                assertTrue(later.publicKey !in phone.flaggedAdmins(), "$phone flags the later member ($profile)")
            }
            assertFalse(assertNotNull(later.room().chatRoom.toMlsGroup()).isLocalAdmin(), "the later member governs ($profile)")
        }
    }

    /**
     * A room joined before Phase 2, whose joiner's rows flag nobody, is caught up
     * at the next start, once. docs/robust-group-admins.md, Phase 6.
     */
    @Test
    fun `a room joined before the Welcome wrote flags is caught up at start`() = onEveryProfile { profile, phones ->
        val founders = phones.map { it.publicKey }.toSet()
        val joiner = phones.last()
        joiner.db.participantDao().upsert(joiner.room().localParticipants.map { it.participant.copy(adminAt = null) })
        assertTrue(joiner.flaggedAdmins().isEmpty(), "the fixture did not clear the flags ($profile)")

        assertEquals(founders.size, AdminFlags.reconcileAll(joiner.db), "($profile)")
        assertEquals(founders, joiner.flaggedAdmins(), "($profile)")
        assertEquals(0, AdminFlags.reconcileAll(joiner.db), "and a second start changes nothing ($profile)")
    }

    // ---- The founding invite: docs/robust-group-admins.md, Phase 4 -------------

    private val ceremonyRoomId = "ce1e".repeat(16)

    /** The ceremony [phones] ran, as [owner]'s device holds it: everyone in it, and its key [roomId]'s. */
    private suspend fun ceremonyOn(owner: Phone, phones: List<Phone>, thresholdPublicKey: HexKey): Pair<DkgSession, LocalChatRoom> {
        owner.db.chatRoomDao().upsert(
            ChatRoom(id = ceremonyRoomId, userPublicKey = owner.publicKey, subject = "Ekklesia", description = null, mlsGroupState = null)
        )
        val session = DkgSession(
            id = "the-ceremony",
            chatRoomId = ceremonyRoomId,
            coordinatorPublicKey = owner.publicKey,
            userPublicKey = owner.publicKey,
            threshold = 2,
            participantCount = phones.size,
            participantPublicKeys = DkgSession.formatParticipants(phones.map { it.publicKey }.toSet()),
            subject = "Ekklesia",
            stage = DkgRitualStage.COMPLETE,
            hostPublicKey = owner.publicKey,
            round1Random = "aa".repeat(32),
            round2AuxRandom = "bb".repeat(32),
            thresholdPublicKey = thresholdPublicKey,
            secretShare = "cc".repeat(32),
        )
        owner.db.dkgSessionDao().upsert(session)
        return session to assertNotNull(owner.db.chatRoomDao().findChatRoomById(ceremonyRoomId))
    }

    /**
     * A room whose founding invite never went out: made, with every founder in
     * its policy, and nobody but its creator in its tree -- what `create` leaves
     * when `addMembers` throws.
     */
    private suspend fun roomWithoutItsInvite(profile: MarmotProfile, phones: List<Phone>, roomId: String) {
        val creator = phones.first()
        val created = MarmotGroupFactory.create(
            creator = creator.keyPair,
            roomId = roomId,
            name = "#Ekklesia",
            description = SharedKeyDerivation.describe("Admins of Ekklesia."),
            adminPublicKeys = phones.map { it.publicKey },
            relays = Relays.DefaultDMRelayList.map { it.url },
            profile = profile,
        )
        assertNotNull(
            creator.chatRepository.getOrCreateChatRoom(
                chatRoomId = roomId,
                activeUserPublicKey = creator.publicKey,
                relayHint = null,
                defaultSubject = "#Ekklesia",
                description = null,
                mlsGroupState = created.group.saveState().encodeTls().toHex(),
            )
        )
        creator.chatRepository.recordMarmotRouting(chatRoomId = roomId, routingId = created.routingId)
    }

    @Test
    fun `a founding invite that never went out is finished, and every founder is an admin`(): Unit = runBlocking {
        listOf(MarmotProfile.LEGACY, MarmotProfile.CURRENT).forEach { profile ->
            val phones = List(3) { Phone(profile) }
            try {
                phones.forEach { it.ready() }
                phones.forEach { phone -> phones.forEach { MarmotStandIn.seedProfile(phone.db, it.publicKey) } }
                val creator = phones.first()
                phones.drop(1).forEach { creator.db.marmotKeyPackageDao().upsert(it.keyPackage) }

                val thresholdPublicKey = "02" + KeyPair().pubKey.toHexKey()
                val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
                roomWithoutItsInvite(profile, phones, roomId)
                val (session, ceremonyRoom) = ceremonyOn(creator, phones, thresholdPublicKey)

                val invite = MarmotGroupCreation.finishFoundingInvite(
                    creator.db, creator.chatRepository, session, ceremonyRoom, keyPackageLookupTimeout = 2_000L,
                )
                val sent = assertIs<MarmotGroupCreation.FoundingInvite.Sent>(invite, "($profile)")
                assertEquals(phones.drop(1).map { it.publicKey }.toSet(), sent.invited.toSet(), "($profile)")
                assertTrue(sent.notAdded.isEmpty(), "($profile)")

                assertEquals(
                    MarmotGroupCreation.FoundingInvite.NothingToDo,
                    MarmotGroupCreation.finishFoundingInvite(creator.db, creator.chatRepository, session, ceremonyRoom, 2_000L),
                    "and a second run invites nobody ($profile)",
                )

                phones.drop(1).forEach { welcome(from = creator, to = it, roomId = roomId) }
                val founders = phones.map { it.publicKey }.toSet()
                phones.forEach { phone ->
                    val room = assertNotNull(phone.db.chatRoomDao().findChatRoomById(roomId), "$phone has no room ($profile)")
                    val group = assertNotNull(room.chatRoom.toMlsGroup(), "$phone holds no group ($profile)")
                    assertEquals(founders, group.currentAdminIdentities(), "$phone's admin policy ($profile)")
                    assertTrue(group.isLocalAdmin(), "$phone is not an admin ($profile)")
                }
            } finally {
                phones.forEach { it.close() }
            }
        }
    }

    /** Past epoch 0 a founder without a leaf may be one who left; that is not this step's to undo. */
    @Test
    fun `a room past its founding invites nobody`() = onEveryProfile { profile, phones ->
        val creator = phones.first()
        val (session, ceremonyRoom) = ceremonyOn(creator, phones, foundingThresholdPublicKey)

        assertEquals(
            MarmotGroupCreation.FoundingInvite.NothingToDo,
            MarmotGroupCreation.finishFoundingInvite(creator.db, creator.chatRepository, session, ceremonyRoom, 2_000L),
            "($profile)",
        )
    }

    // ---- The ceremony ------------------------------------------------------------

    @Test
    fun `the founding set is the same set on every device that took part`(): Unit = runBlocking {
        CeremonyNetwork.nip17(size = 3).use { network ->
            val everyone = network.devices.map { it.publicKey }.toSet()
            val session = network.propose(threshold = 2)
            network.deliverUntilQuiet()
            network.devices.drop(1).forEach { network.approve(it, session.id, DkgApprovalStep.HOST_KEY) }
            network.deliverUntilQuiet()

            network.devices.forEach { device ->
                val theirs = assertNotNull(network.session(device, session.id), "$device has no ceremony")
                assertEquals(everyone, ChillDkgRitualManager.memberPublicKeys(network.room(device)), "$device's room")
                assertEquals(everyone, theirs.participantPublicKeySet(), "$device's ceremony")
                assertEquals(
                    everyone,
                    ChillDkgRitualManager.participantsOf(theirs, network.room(device)),
                    "$device's founding set",
                )
            }
        }
    }

    @Test
    fun `a subgroup's founding set is its ceremony's participants on every device in it`(): Unit = runBlocking {
        val parentId = SharedKeyDerivation.marmotGroupId("02" + KeyPair().pubKey.toHexKey())
        CeremonyNetwork.marmot(size = 4, roomId = parentId).use { network ->
            val picked = network.devices.take(3).map { it.publicKey }.toSet()
            val session = network.propose(threshold = 2, participants = picked, parentChatRoomId = parentId, subject = "Translation")
            network.deliverUntilQuiet()

            network.devices.take(3).forEach { device ->
                val theirs = assertNotNull(network.session(device, session.id), "$device has no ceremony")
                assertEquals(picked, ChillDkgRitualManager.participantsOf(theirs, network.room(device)), "$device")
            }
        }
    }
}
