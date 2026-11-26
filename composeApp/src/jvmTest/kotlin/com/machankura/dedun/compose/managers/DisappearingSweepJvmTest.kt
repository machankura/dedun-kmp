package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
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
import com.machankura.dedun.compose.database.repository.DatabaseChatRepository
import com.machankura.dedun.compose.database.repository.DisappearingSettingsStore
import com.machankura.dedun.compose.nostr.DisappearingMessages
import com.machankura.dedun.compose.nostr.DisappearingMessages.DAY
import com.machankura.dedun.compose.nostr.DisappearingMessages.HOUR
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * docs/disappearing-messages.md, Phase 4: what the sweeper deletes, what it must not, and what
 * happens to a message that arrives already expired.
 */
class DisappearingSweepJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val userKeys = KeyPair()
    private val aliceKeys = KeyPair()
    private val user = userKeys.pubKey.toHexKey()
    private val alice = aliceKeys.pubKey.toHexKey()
    private val relay = "wss://relay.example"

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

    /** Sends and seals one message under a one-hour policy; returns its payload. */
    private suspend fun sentMessage(room: LocalChatRoom): GiftWrapPayload {
        store.saveDefault(user, HOUR)
        repository.sendChatMessage(text = "gone in an hour", localChatRoom = room)
        val payload = db.giftWrapPayloadDao().getByChatRoomAndKinds(room.chatRoom.id, listOf(14)).single()
        repository.sealGiftWrapPayload(payload, NostrSignerSync(userKeys))
        return assertNotNull(db.giftWrapPayloadDao().getById(payload.id))
    }

    @Test
    fun `nothing goes before its expiry`(): Unit = runBlocking {
        val room = room()
        val payload = sentMessage(room)
        val swept = DisappearingMessageSweeper.sweep(db, WrittenOnlyRetention, now = assertNotNull(payload.expiresAt) - kotlin.time.Duration.parse("1s"))
        assertEquals(0, swept.payloads)
        assertNotNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id))
    }

    @Test
    fun `an expired message goes with every wrap and seal that held it, and the room stays`(): Unit = runBlocking {
        val room = room()
        val payload = sentMessage(room)
        val line = assertNotNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id))
        assertEquals(2, db.giftWrapSealDao().getAllGiftWrapSeals().size)

        val swept = DisappearingMessageSweeper.sweep(db, WrittenOnlyRetention, now = assertNotNull(payload.expiresAt))

        assertEquals(1, swept.payloads)
        assertEquals(setOf(line.id), swept.messageIds)
        assertNull(db.giftWrapPayloadDao().getById(payload.id))
        assertNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id))
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals(), "a seal the sender could still decrypt survived")
        assertEquals(emptyList(), db.giftWrapMessageDao().getAllGiftWrapMessages(), "a wrap survived its message")
        assertNotNull(db.chatRoomDao().findChatRoomById(room.chatRoom.id), "the conversation went with the message")
    }

    @Test
    fun `the payload a room was created from goes without taking the room`(): Unit = runBlocking {
        val room = room()
        val payload = sentMessage(room)
        db.chatRoomDao().upsert(room.chatRoom.copy(initialGiftWrapPayloadId = payload.id))

        DisappearingMessageSweeper.sweep(db, WrittenOnlyRetention, now = assertNotNull(payload.expiresAt))

        val after = assertNotNull(db.chatRoomDao().findChatRoomById(room.chatRoom.id), "the room cascaded away with its first payload")
        assertNull(after.chatRoom.initialGiftWrapPayloadId)
    }

    @Test
    fun `a ceremony's expired round stays until its session lets it go`(): Unit = runBlocking {
        val room = room()
        val createdAt = Clock.System.now().epochSeconds - 2 * DAY
        val tags = store.stamp(room.chatRoom.id, user, FrostSigningEvents.NONCE, createdAt, arrayOf(arrayOf("p", alice)))
        val round = GiftWrapPayload(
            id = EventHasher.hashId(user, createdAt, FrostSigningEvents.NONCE, tags, "nonce"),
            publicKey = user, chatRoomId = room.chatRoom.id, kind = FrostSigningEvents.NONCE,
            tags = tags, content = "nonce", createdAt = Instant.fromEpochSeconds(createdAt),
        )
        db.giftWrapPayloadDao().upsert(round)

        assertEquals(0, DisappearingMessageSweeper.sweep(db, WrittenOnlyRetention).payloads)
        assertNotNull(db.giftWrapPayloadDao().getById(round.id), "a round was swept before its session finished")

        assertEquals(1, DisappearingMessageSweeper.sweep(db, { _, _ -> true }).payloads)
        assertNull(db.giftWrapPayloadDao().getById(round.id))
    }

    @Test
    fun `a message that expired before it was sent is swept and counted`(): Unit = runBlocking {
        val room = room()
        store.saveDefault(user, HOUR)
        repository.sendChatMessage(text = "never sent", localChatRoom = room)
        val payload = db.giftWrapPayloadDao().getByChatRoomAndKinds(room.chatRoom.id, listOf(14)).single()

        val swept = DisappearingMessageSweeper.sweep(db, WrittenOnlyRetention, now = assertNotNull(payload.expiresAt))
        assertEquals(1, swept.unsent)
        assertNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id))
    }

    /** A wrap from alice to the user, carrying a kind 14 with [expiry]. */
    private fun wrapFromAlice(createdAt: Long, expiry: Long?): NostrEvent {
        val tags = listOfNotNull(arrayOf("p", user), expiry?.let { arrayOf("expiration", it.toString()) }).toTypedArray()
        val rumor = RumorAssembler.assembleRumor(alice, EventTemplate(createdAt, 14, tags, "dumela"))
        val signer = NostrSignerSync(aliceKeys)
        val seal = signer.signNormal<SealEvent>(
            createdAt = createdAt - 100,
            kind = SealEvent.KIND,
            tags = emptyArray(),
            content = signer.nip44Encrypt(plaintext = rumor.toJson(), toPublicKey = user),
        )
        val wrapCreatedAt = createdAt - 200
        val wrap = GiftWrapEvent.create(
            event = seal,
            recipientPubKey = user,
            createdAt = wrapCreatedAt,
            expirationDelta = expiry?.let { it - createdAt },
        )
        return NostrEvent(
            id = wrap.id, pubKey = wrap.pubKey, kind = wrap.kind, tags = wrap.tags,
            content = wrap.content, sig = wrap.sig, createdAt = Instant.fromEpochSeconds(wrap.createdAt),
        )
    }

    private suspend fun receive(wrap: NostrEvent) =
        db.nostrDao().storeNostrEvent(nostrEvent = wrap, relayURL = relay, synchronizationRelayURLs = listOf(relay), level = 0, activeKeyPair = userKeys)

    @Test
    fun `a message received live carries its sender's expiry, and its wrap goes with it`(): Unit = runBlocking {
        room()
        val now = Clock.System.now().epochSeconds
        val wrap = wrapFromAlice(now, now + HOUR)
        receive(wrap)

        val payload = db.giftWrapPayloadDao().getByKinds(listOf(14)).single()
        assertEquals(Instant.fromEpochSeconds(now + HOUR), payload.expiresAt)
        val line = assertNotNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id))
        assertEquals(payload.expiresAt, line.expiresAt)
        assertEquals(payload.id, db.giftWrapMessageDao().getAllGiftWrapMessages().single().giftWrapPayloadId)

        DisappearingMessageSweeper.sweep(db, WrittenOnlyRetention, now = Instant.fromEpochSeconds(now + HOUR))
        assertNull(db.nostrEventDao().getNostrEventById(wrap.id), "the wrap outlived the message it carried")
        assertNull(db.chatMessageDao().getChatMessagesByGiftWrapPayloadId(payload.id))
    }

    @Test
    fun `a message that arrives already expired is not filed, and its wrap is emptied and remembered`(): Unit = runBlocking {
        room()
        val now = Clock.System.now().epochSeconds
        val wrap = wrapFromAlice(now - 2 * HOUR, now - HOUR)
        receive(wrap)

        assertEquals(emptyList(), db.giftWrapPayloadDao().getByKinds(listOf(14)), "an expired message was filed")
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals(), "the seal this device can read was kept")
        assertEquals("", assertNotNull(db.nostrEventDao().getNostrEventById(wrap.id)).content, "the ciphertext was kept")
        assertTrue(db.disappearingDao().isTombstoned(wrap.id))

        // Once the wrap's own expiry passes, the empty row goes too.
        val wrapRow = db.giftWrapMessageDao().getAllGiftWrapMessages().single()
        DisappearingMessageSweeper.sweep(db, WrittenOnlyRetention, now = assertNotNull(wrapRow.expiresAt))
        assertNull(db.nostrEventDao().getNostrEventById(wrap.id))

        // A relay serving it again is turned away before anything is opened.
        receive(wrap)
        assertNull(db.nostrEventDao().getNostrEventById(wrap.id))
        assertEquals(emptyList(), db.giftWrapPayloadDao().getByKinds(listOf(14)))
    }

    @Test
    fun `another sender's expiry is obeyed whatever this device's own setting is`(): Unit = runBlocking {
        room()
        store.saveDefault(user, DisappearingMessages.OFF)
        val now = Clock.System.now().epochSeconds
        receive(wrapFromAlice(now, now + HOUR))
        val payload = db.giftWrapPayloadDao().getByKinds(listOf(14)).single()

        DisappearingMessageSweeper.sweep(db, WrittenOnlyRetention, now = Instant.fromEpochSeconds(now + HOUR))
        assertNull(db.giftWrapPayloadDao().getById(payload.id))
    }
}
