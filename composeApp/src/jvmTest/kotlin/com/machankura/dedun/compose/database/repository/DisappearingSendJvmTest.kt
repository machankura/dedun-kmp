package com.machankura.dedun.compose.database.repository

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.DisappearingMessages
import com.machankura.dedun.compose.nostr.DisappearingMessages.DAY
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * docs/disappearing-messages.md, Phase 3: what a NIP-17 send writes, and what the wrap carries.
 *
 * Real keys throughout: the room id is derived over the members by secp256k1 point work, and the
 * seal is opened again as the recipient to see the rumor they would see.
 */
class DisappearingSendJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val userKeys = KeyPair()
    private val aliceKeys = KeyPair()
    private val user = userKeys.pubKey.toHexKey()
    private val alice = aliceKeys.pubKey.toHexKey()

    private val repository = DatabaseChatRepository(database = db, scope = CoroutineScope(Dispatchers.Unconfined))
    private val store = DisappearingSettingsStore(db)

    private suspend fun seedProfile(publicKey: String) {
        val nostrEventId = publicKey.take(63) + "f"
        db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = "member", nostrEventId = nostrEventId))
    }

    private suspend fun room(): LocalChatRoom {
        seedProfile(user)
        seedProfile(alice)
        return assertNotNull(db.nostrNip17Dao().createNip17ChatRoom(user, listOf(alice)))
    }

    private suspend fun onlyPayload(room: LocalChatRoom): GiftWrapPayload =
        db.giftWrapPayloadDao().getByChatRoomAndKinds(room.chatRoom.id, listOf(14)).single()

    @Test
    fun `a message carries no expiry by default, which is Off in Dedun`() = runBlocking {
        // D11: nobody has set anything, as on every profile the upgrade finds.
        val room = room()
        repository.sendChatMessage(text = "hello", localChatRoom = room)

        val payload = onlyPayload(room)
        assertNull(DisappearingMessages.expirationOf(payload.tags))
        assertNull(payload.expiresAt)
        assertNull(assertNotNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id)).expiresAt)
    }

    @Test
    fun `a message is stamped a day out once a day is chosen, and its id covers the tag`() = runBlocking {
        val room = room()
        store.saveDefault(user, DAY)
        repository.sendChatMessage(text = "hello", localChatRoom = room)

        val payload = onlyPayload(room)
        val expiry = assertNotNull(DisappearingMessages.expirationOf(payload.tags, payload.createdAt.epochSeconds))
        assertEquals(payload.createdAt.epochSeconds + DAY, expiry)
        assertEquals(Instant.fromEpochSeconds(expiry), payload.expiresAt)
        assertEquals(
            EventHasher.hashId(user, payload.createdAt.epochSeconds, payload.kind, payload.tags, payload.content),
            payload.id,
            "the payload id is not the id of the rumor the recipient will see",
        )
        val line = assertNotNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id))
        assertEquals(payload.expiresAt, line.expiresAt)
    }

    @Test
    fun `the conversation's override wins, and Off stamps nothing`() = runBlocking {
        val room = room()
        store.saveDefault(user, DisappearingMessages.HOUR)
        store.saveOverride(room.chatRoom.id, DisappearingMessages.OFF)
        repository.sendChatMessage(text = "kept", localChatRoom = room)

        val payload = onlyPayload(room)
        assertNull(DisappearingMessages.expirationOf(payload.tags))
        assertNull(payload.expiresAt)
        assertNull(assertNotNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id)).expiresAt)
    }

    @Test
    fun `a protocol round expires at its floor even with the policy Off`() = runBlocking {
        val room = room()
        store.saveDefault(user, DisappearingMessages.OFF)
        val createdAt = 1_800_000_000L
        val tags = store.stamp(room.chatRoom.id, user, FrostSigningEvents.NONCE, createdAt, arrayOf(arrayOf("p", alice)))
        assertEquals(createdAt + DisappearingMessages.SIGNING_FLOOR, DisappearingMessages.expirationOf(tags, createdAt))
    }

    @Test
    fun `every wrap asks relays to drop it after the rumor, and every seal stays untagged`() = runBlocking {
        val room = room()
        store.saveDefault(user, DAY)
        repository.sendChatMessage(text = "hello", localChatRoom = room)
        val payload = onlyPayload(room)
        val rumorExpiry = assertNotNull(payload.expiresAt).epochSeconds

        repository.sealGiftWrapPayload(payload, NostrSignerSync(userKeys))

        val seals = db.giftWrapSealDao().getAllGiftWrapSeals()
        assertEquals(2, seals.size, "one seal for alice and one for the sender's own copy")
        seals.forEach { seal ->
            assertTrue(seal.tags.isEmpty(), "a seal was tagged: ${seal.tags.map { it.toList() }}")
            val wrap = assertNotNull(db.nostrEventDao().getNostrEventById(assertNotNull(seal.giftWrapMessageId)))
            val wrapExpiry = assertNotNull(DisappearingMessages.expirationOf(wrap.tags), "a wrap carries no expiration")
            assertTrue(wrapExpiry >= rumorExpiry, "the wrap would be dropped before the rumor expires")
            assertTrue(wrapExpiry <= rumorExpiry + 2 * DAY, "the wrap outlives the rumor by more than two days")
        }

        // What alice reads: the rumor, carrying the sender's expiry and nothing merged in from the seal.
        val opened = seals.firstNotNullOfOrNull { it.decryptGiftWrapPayload(aliceKeys.privKey!!) }
        val rumor = assertNotNull(opened, "alice could not open her seal")
        assertEquals(1, rumor.tags.count { it[0] == DisappearingMessages.TAG })
        assertEquals(Instant.fromEpochSeconds(rumorExpiry), rumor.expiresAt)
    }

    @Test
    fun `a message that expired before it could be sent is not sealed`() = runBlocking {
        val room = room()
        val createdAt = Clock.System.now().epochSeconds - 2 * DAY
        val tags = arrayOf(arrayOf("p", alice), arrayOf(DisappearingMessages.TAG, (createdAt + DAY).toString()))
        val payload = GiftWrapPayload(
            id = EventHasher.hashId(user, createdAt, 14, tags, "late"),
            publicKey = user,
            chatRoomId = room.chatRoom.id,
            kind = 14,
            tags = tags,
            content = "late",
            createdAt = Instant.fromEpochSeconds(createdAt),
        )
        db.giftWrapPayloadDao().upsert(payload)

        repository.sealGiftWrapPayload(payload, NostrSignerSync(userKeys))

        assertEquals(0, db.giftWrapSealDao().getAllGiftWrapSeals().size, "an expired message was sealed for relays")
        assertNull(assertNotNull(db.giftWrapPayloadDao().getById(payload.id)).giftWrapSealId)
    }
}
