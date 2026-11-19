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
import kotlin.test.Ignore
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

    private val roomId = SharedKeyDerivation.marmotGroupId("02" + KeyPair().pubKey.toHexKey())

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
    private suspend fun welcome(from: Phone, to: Phone) {
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
     * writes none of them.
     */
    @Ignore("Phase 2: the Welcome writes no admin flags")
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
     * Legacy only until Phase 4. On the current profile `MarmotGroupCreation` invites
     * the founders through a room read before its routing id was recorded, so the
     * engine's group at the real address still holds only the creator, and a later
     * invite takes the founding path -- no commit, and nobody else told.
     */
    @Test
    fun `a member added after the founding is not an admin`() = onEveryProfile(listOf(MarmotProfile.LEGACY)) { profile, phones ->
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
