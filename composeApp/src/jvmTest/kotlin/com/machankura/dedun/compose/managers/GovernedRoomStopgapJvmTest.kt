package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.exceptions.MarmotGovernedRoomException
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEventEncryption
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A room carrying Giza's governance marker takes no bare commit from this build:
 * docs/giza-into-dedun.md, Phase 1.
 *
 * The room is the app's own, made with the marker in its purpose so the description
 * reads as a Giza-made keyed room's does, with the stand-in invited by the founding
 * Add -- the one commit Giza makes bare too, while the creator is the only member.
 * After that, every change a Giza device would drop is refused here.
 */
class GovernedRoomStopgapJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val scope = CoroutineScope(Dispatchers.Default)
    private val chatRepository = DatabaseChatRepository(db, scope)

    @AfterTest
    fun closeDb() = db.close()

    private val keyPair = KeyPair()
    private val user = keyPair.pubKey.toHexKey()
    private val roomId = SharedKeyDerivation.marmotGroupId("02" + KeyPair().pubKey.toHexKey())
    private val relay = "wss://relay.example"

    private val member = MarmotStandIn()

    private suspend fun seedProfile(publicKey: String) {
        val eventId = publicKey.take(32).padEnd(64, 'f')
        db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = publicKey.take(8), nostrEventId = eventId))
    }

    private suspend fun store(event: Event) = db.nostrDao().storeNostrEvent(
        nostrEvent = NostrEvent(
            id = event.id, pubKey = event.pubKey, kind = event.kind, tags = event.tags,
            content = event.content, sig = event.sig, createdAt = Instant.fromEpochSeconds(event.createdAt),
        ),
        relayURL = relay, synchronizationRelayURLs = listOf(relay), level = 0, activeKeyPair = keyPair,
    )

    private suspend fun room() = db.chatRoomDao().findChatRoomById(roomId)!!

    /** The app's room, governed or not, with the stand-in in it at epoch 1. */
    private suspend fun roomWithMember(governed: Boolean): String {
        seedProfile(user)
        seedProfile(member.hex)
        val purpose = if (governed) GovernanceMarker.describe("Admins.", minimumAdmins = 3) else "Admins."
        assertIs<MarmotGroupCreation.Outcome.Created>(
            MarmotGroupCreation.create(
                database = db, chatRepository = chatRepository, groupId = roomId, name = "#room",
                purpose = purpose, adminPublicKeys = setOf(user), userPublicKey = user, keyPair = keyPair,
                profile = MarmotProfile.CURRENT,
            )
        )
        member.makeBundle()
        val memberPackage = MarmotKeyPackage(
            id = member.hex, publicKey = member.hex,
            tlsEncodedMarmotKeyPackage = member.bundle.keyPackage.toTlsBytes(), profile = MarmotProfile.CURRENT.wireName,
        )
        // The founding invite: the creator is the only member, so it forks nothing.
        chatRepository.inviteMember(room(), member.hex, memberPackage)

        val welcome = db.giftWrapPayloadDao().getAllGiftWrapUnsigned().single { it.kind == WelcomeEvent.KIND }
        val welcomeEvent = WelcomeEvent(id = welcome.id, pubKey = user, createdAt = welcome.createdAt.epochSeconds, tags = welcome.tags, content = welcome.content, sig = "")
        val (_, routingId) = member.manager.processWelcome(Base64.decode(welcomeEvent.welcomeBase64()), member.bundle)
        member.inbound.trackGroup(routingId)
        return routingId
    }

    @Test
    fun `the marker Giza writes is read, and a room without it is not governed`() {
        val giza = "Admins.\n\nChanges need the group's signature; at least 3 admins."
        assertEquals(3, GovernanceMarker.minimumAdmins(giza))
        assertEquals(giza, GovernanceMarker.describe("Admins.", 3))
        assertNull(GovernanceMarker.minimumAdmins("Admins."))
        assertNull(GovernanceMarker.minimumAdmins(null))
        assertNull(GovernanceMarker.minimumAdmins("Changes need the group's signature; at least 0 admins."))
    }

    @Test
    fun `a governed room is recognised from its state`() = runBlocking<Unit> {
        val routingId = roomWithMember(governed = true)
        val group = db.marmotEngine.groupFor(routingId)!!
        assertTrue(group.isGoverned())
        assertTrue(group.refusesBareCommits())
    }

    @Test
    fun `a room without the marker is not governed`() = runBlocking<Unit> {
        val routingId = roomWithMember(governed = false)
        val group = db.marmotEngine.groupFor(routingId)!!
        assertFalse(group.isGoverned())
        assertFalse(group.refusesBareCommits())
    }

    @Test
    fun `removing a member from a governed room is refused, and nothing goes on the wire`() = runBlocking<Unit> {
        roomWithMember(governed = true)
        val before = db.marmotPublishObligationDao().getAll().size

        assertIs<MarmotGovernedRoomException>(runCatching { chatRepository.removeMember(room(), member.hex) }.exceptionOrNull())
        assertEquals(before, db.marmotPublishObligationDao().getAll().size)
    }

    @Test
    fun `disbanding a governed room is refused`() = runBlocking<Unit> {
        roomWithMember(governed = true)
        val before = db.marmotPublishObligationDao().getAll().size

        assertIs<MarmotGovernedRoomException>(runCatching { chatRepository.disbandChatRoom(room()) }.exceptionOrNull())
        assertEquals(before, db.marmotPublishObligationDao().getAll().size)
    }

    @Test
    fun `inviting into a governed room with members is refused`() = runBlocking<Unit> {
        roomWithMember(governed = true)
        val third = MarmotStandIn().apply { makeBundle() }
        seedProfile(third.hex)
        val thirdPackage = MarmotKeyPackage(
            id = third.hex, publicKey = third.hex,
            tlsEncodedMarmotKeyPackage = third.bundle.keyPackage.toTlsBytes(), profile = MarmotProfile.CURRENT.wireName,
        )

        assertIs<MarmotGovernedRoomException>(
            runCatching { chatRepository.inviteMember(room(), third.hex, thirdPackage) }.exceptionOrNull()
        )
    }

    @Test
    fun `a member's leave from a governed room is not committed by this device`() = runBlocking<Unit> {
        val routingId = roomWithMember(governed = true)
        val before = db.marmotPublishObligationDao().getAll().size

        val (framedProposal, exporterSecret) = member.manager.leaveGroup(routingId)
        store(
            NostrSignerInternal(KeyPair()).sign(
                GroupEvent.build(encryptedContentBase64 = GroupEventEncryption.encrypt(framedProposal, exporterSecret), nostrGroupId = routingId)
            )
        )

        assertEquals(before, db.marmotPublishObligationDao().getAll().size, "a bare commit of the leave went on the wire")
        assertEquals(1L, db.marmotEngine.groupManager.getGroup(routingId)!!.epoch)
    }
}
