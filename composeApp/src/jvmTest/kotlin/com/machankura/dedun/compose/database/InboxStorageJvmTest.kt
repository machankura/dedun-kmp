package com.machankura.dedun.compose.database

import androidx.room3.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.GroupInboxWrap
import com.machankura.dedun.compose.database.model.InboxKey
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyMessage
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyStage
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.nostr.inbox.InboxKeyRitualEvents

/**
 * Where a group's inbox is kept (docs/group-inbox.md, Phase 3): redelivery changes nothing,
 * a wipe keeps the key's name and loses its secret, and nothing a signing path reads can find a
 * threshold-1 key.
 */
class InboxStorageJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val room = "f".repeat(64)
    private val admin = "a".repeat(64)

    @AfterTest
    fun close() = db.close()

    private fun seedRoom() = runBlocking {
        // A room belongs to a profile, which belongs to the event that made it.
        val profileEvent = "9".repeat(64)
        db.nostrEventDao().upsert(NostrEvent(id = profileEvent, pubKey = admin, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = admin, nostrEventId = profileEvent))
        db.chatRoomDao().upsert(ChatRoom(id = room, userPublicKey = admin, subject = "&Ekklesia", description = "", mlsGroupState = null))
    }

    private fun completedCeremonyAndKey() = runBlocking {
        seedRoom()
        db.inboxKeyDao().upsertCeremony(
            InboxKeyCeremony(
                id = "c".repeat(64),
                chatRoomId = room,
                generation = 0,
                coordinatorPublicKey = admin,
                userPublicKey = admin,
                participantPublicKeys = InboxKeyCeremony.formatParticipants(listOf(admin, "b".repeat(64))),
                stage = InboxKeyCeremonyStage.COMPLETE,
                hostPublicKey = "02" + "1".repeat(64),
                round1Random = "2".repeat(64),
                round2AuxRandom = "3".repeat(64),
                inboxPublicKey = "e".repeat(64),
            )
        )
        db.inboxKeyDao().upsertKey(
            InboxKey(
                chatRoomId = room,
                userPublicKey = admin,
                generation = 0,
                publicKey = "e".repeat(64),
                secretKey = "5".repeat(64),
                recoveryData = "66",
                ceremonyId = "c".repeat(64),
            )
        )
    }

    @Test
    fun `a ceremony message delivered twice is kept once, as first received`() = runBlocking {
        completedCeremonyAndKey()
        val first = InboxKeyCeremonyMessage("c".repeat(64), admin, InboxKeyRitualEvents.HOST_KEY, "first")
        assertTrue(db.inboxKeyDao().insertMessage(first) > 0)
        assertEquals(-1L, db.inboxKeyDao().insertMessage(first.copy(payload = "second")))
        assertEquals("first", db.inboxKeyDao().getMessage("c".repeat(64), admin, InboxKeyRitualEvents.HOST_KEY)?.payload)
    }

    @Test
    fun `a wrap or a rumor delivered twice is one row`() = runBlocking {
        seedRoom()
        val wrap = GroupInboxWrap(id = "1".repeat(64), chatRoomId = room, wrapJson = "{}", wrapCreatedAt = 10)
        db.groupInboxDao().insertWrap(wrap)
        db.groupInboxDao().insertWrap(wrap.copy(wrapJson = "{\"again\":true}"))
        assertEquals(listOf(wrap.id to wrap.wrapJson), db.groupInboxDao().getUnopenedWraps(room).map { it.id to it.wrapJson })

        val message = GroupInboxMessage(
            chatRoomId = room, rumorId = "2".repeat(64), wrapId = wrap.id, conversationId = "x",
            participants = "b".repeat(64), senderPublicKey = "b".repeat(64), kind = 14,
            rumorJson = "{}", createdAt = 5, generation = 0, outgoing = false,
        )
        db.groupInboxDao().insertMessage(message)
        db.groupInboxDao().insertMessage(message.copy(wrapId = "3".repeat(64)))
        assertEquals(listOf(message.wrapId), db.groupInboxDao().getMessages(room).map { it.wrapId })
        assertEquals(1, db.groupInboxDao().observeUnreadCount(room).first())

        db.groupInboxDao().markOpened(wrap.id, 0)
        assertEquals(0, db.groupInboxDao().observeUnopenedCount(room).first())
    }

    @Test
    fun `a wipe loses the secret and keeps the key's name`() = runBlocking {
        completedCeremonyAndKey()
        db.inboxKeyDao().wipeKeys(room, admin, Clock.System.now())

        val key = assertNotNull(db.inboxKeyDao().getKey(room, admin, 0))
        assertNull(key.secretKey)
        assertNull(key.recoveryData)
        assertEquals(InboxKeyState.WIPED, key.state)
        assertEquals("e".repeat(64), key.publicKey)
        assertEquals(emptyList(), db.inboxKeyDao().getHeldKeys(admin))
    }

    @Test
    fun `no signing path can find an inbox key`() = runBlocking {
        // D3: a threshold-1 key any one admin holds, found by the signing path, would let one
        // admin sign as the group. It lives where nothing that signs looks.
        completedCeremonyAndKey()
        assertEquals(emptyList(), db.dkgSessionDao().getKeyHoldingSessions())
        assertNull(FrostSigningManager.completedKey(db, room))
        assertEquals(false, FrostSigningManager.canSign(db, room))
    }
}
