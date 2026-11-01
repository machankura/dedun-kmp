package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.codec.TlsReader
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.messages.MlsKeyPackage
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.encoding.Base64

/**
 * A peer's kind:30443 through the sync funnel and into an invite, on both profiles.
 *
 * Before docs/marmot-v2.md Phase 1 the ingest stored a KeyPackage's content as
 * published and validated nothing, and the invite decoded it as a bare KeyPackage.
 * A current-profile package -- Amethyst 1.15.2's, White Noise's -- frames its
 * KeyPackage in an MLSMessage, so that decode read the frame's version and
 * wire-format words as the package's own and the invite failed. These are the two
 * shapes, end to end: stored bare, decoded by the invite, and a member in the tree.
 */
class MarmotKeyPackageIngestJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val peer = KeyPair()
    private val relay = "wss://relay.example"
    private val roomId = "b".repeat(64)

    private suspend fun store(nostrEvent: NostrEvent) = db.nostrDao().storeNostrEvent(
        nostrEvent = nostrEvent,
        relayURL = relay,
        synchronizationRelayURLs = listOf(relay),
        level = 0,
        activeKeyPair = user,
    )

    private suspend fun seedProfile(publicKey: String, name: String) {
        val eventId = publicKey.take(32).padEnd(64, 'f')
        db.nostrEventDao().upsert(
            NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = name, nostrEventId = eventId))
    }

    /** A legacy room this device created, as every room before Phase 4 is. */
    private suspend fun seedLegacyRoom(): LocalChatRoom {
        val userHex = user.pubKey.toHexKey()
        seedProfile(userHex, "user")
        seedProfile(peer.pubKey.toHexKey(), "peer")
        val mlsGroup = MlsGroup.create(
            identity = userHex.hexToByteArray(),
            initialExtensions = listOf(
                MarmotGroupData.bootstrap(
                    nostrGroupId = roomId,
                    creatorPubKey = userHex,
                    outboxRelays = Relays.DefaultDMRelayList.map { it.url },
                ).toExtension()
            ),
        )
        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = userHex,
            subject = "a room",
            description = null,
            mlsGroupState = mlsGroup.saveState().encodeTls().toHex(),
        )
        db.chatRoomDao().upsert(chatRoom)
        return LocalChatRoom(chatRoom = chatRoom)
    }

    @Test
    fun `a current-profile key package is stored bare and decodes as one`() = runBlocking {
        val (event, bundle) = currentProfileKeyPackageEventFor(peer)
        assertTrue(event.isCurrentProfile(), "the fixture is not a current-profile event")

        store(event.asStoredNostrEvent())

        val stored = assertNotNull(db.marmotKeyPackageDao().getMarmotKeyPackageById(event.id), "not stored")
        val decoded = MlsKeyPackage.decodeTls(TlsReader(stored.tlsEncodedMarmotKeyPackage))
        assertTrue(
            decoded.reference().contentEquals(bundle.keyPackage.reference()),
            "the stored bytes are not the published KeyPackage",
        )
    }

    @Test
    fun `a legacy key package is stored as it always was`() = runBlocking {
        val event = legacyKeyPackageEventFor(peer)
        assertTrue(!event.isCurrentProfile())

        store(event.asStoredNostrEvent())

        val stored = assertNotNull(db.marmotKeyPackageDao().getMarmotKeyPackageById(event.id), "not stored")
        assertEquals(
            KeyPackageUtils.decodeKeyPackage(stored.tlsEncodedMarmotKeyPackage).reference().toHexKey(),
            event.keyPackageRef()?.lowercase(),
        )
    }

    /**
     * The Amethyst-user case. Their leaf advertises the legacy `0xF2EE` extension
     * alongside the current profile's, so a legacy room's required capabilities
     * are met, and the only thing that ever stood between them and this room was
     * the frame around their KeyPackage.
     */
    @Test
    fun `a current-profile peer can be invited into a legacy room`() = runBlocking {
        val localChatRoom = seedLegacyRoom()
        val (event, _) = currentProfileKeyPackageEventFor(peer)
        store(event.asStoredNostrEvent())
        val stored = assertNotNull(db.marmotKeyPackageDao().getMarmotKeyPackageById(event.id))

        db.marmotOutboundDao().inviteMemberToChatRoom(
            localChatRoom = localChatRoom,
            peerPublicKey = peer.pubKey.toHexKey(),
            peerKeyPackage = stored,
        )

        val group = assertNotNull(db.chatRoomDao().findChatRoomById(roomId)!!.chatRoom.toMlsGroup())
        assertEquals(2, group.members().size, "the invitee is not in the tree")
        assertTrue(
            group.currentMemberIdentities().contains(peer.pubKey.toHexKey()),
            "the tree does not name the invitee",
        )
    }

    /**
     * The validator is the one a conformant peer runs on this app's own packages, so
     * what fails it here would fail there. A package whose content was altered after
     * signing no longer matches its `i` tag and no longer verifies.
     */
    @Test
    fun `a key package that does not verify is not stored`() = runBlocking {
        val (event, _) = currentProfileKeyPackageEventFor(peer)
        // A byte of the KeyPackage's own signature -- its last field -- flipped after
        // signing. Flipped in the decoded bytes rather than the base64 text, because
        // rewriting the text's tail can change nothing but its padding.
        val bytes = Base64.decode(event.content)
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        val tampered = event.asStoredNostrEvent().copy(content = Base64.encode(bytes))

        store(tampered)

        assertNull(db.marmotKeyPackageDao().getMarmotKeyPackageById(event.id), "a tampered package was stored")
    }
}
