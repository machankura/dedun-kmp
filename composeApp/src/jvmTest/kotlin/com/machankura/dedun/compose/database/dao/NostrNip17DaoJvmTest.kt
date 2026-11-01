package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NIP-17 rooms have no MLS group, no key packages and no invites -- membership *is* the p-tag
 * set on each message. Two properties follow from that, and both are load-bearing.
 *
 * The room id is [ChatRoom.deriveChatRoomId] over the member set, the same aggregate the
 * inbound path derives from an arriving gift wrap. That is what makes creation idempotent:
 * two people starting the same conversation have to land on one room rather than two, or the
 * same thread exists twice with each side writing into its own copy.
 *
 * And `mlsGroupState = null` is not incidental -- `sendChatMessage` reads exactly that to
 * decide between a group event and gift wraps. A NIP-17 room that acquired MLS state would
 * have its messages routed down a path no recipient is running.
 */
class NostrNip17DaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    // Real keys: deriveChatRoomId does secp256k1 point work and treats an off-curve value
    // differently from a valid one, so hex filler would exercise a path users never hit.
    private val user = KeyPair().pubKey.toHexKey()
    private val alice = KeyPair().pubKey.toHexKey()
    private val bob = KeyPair().pubKey.toHexKey()

    private suspend fun seedProfile(publicKey: String) {
        val nostrEventId = publicKey.take(63) + "f"
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = publicKey,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(
            Profile(publicKey = publicKey, userName = "member", nostrEventId = nostrEventId)
        )
    }

    /**
     * Every member, not just the creator. `Participant.participantPublicKey` is a foreign key
     * onto Profile, so a room cannot be stood up for someone this device has never seen -- see
     * `creating a room with an unknown member is refused by the schema` for what that costs.
     */
    private suspend fun seedMembers(vararg publicKeys: String) = publicKeys.forEach { seedProfile(it) }

    private suspend fun participantsOf(chatRoomId: String) =
        db.participantDao().findParticipantsByChatRoomId(chatRoomId).map { it.participantPublicKey }.toSet()

    @Test
    fun `a nip17 room is created with its members as participants`() = runBlocking {
        seedMembers(user, alice, bob)

        val room = assertNotNull(
            db.nostrNip17Dao().createNip17ChatRoom(
                userPublicKey = user,
                participantPublicKeys = listOf(alice, bob),
                subject = "a thread",
            ),
            "createNip17ChatRoom returned null",
        )

        assertEquals(setOf(user, alice, bob), participantsOf(room.chatRoom.id))
        assertEquals("a thread", room.chatRoom.subject)
    }

    /**
     * The author is a member of their own conversation. `sealGiftWrapPayload` walks the
     * participants to decide who to wrap for, and a room that omitted its creator would send
     * messages every other member could read and the sender could not.
     */
    @Test
    fun `the creator is a participant even when not listed`() = runBlocking {
        seedMembers(user, alice)

        val room = assertNotNull(
            db.nostrNip17Dao().createNip17ChatRoom(
                userPublicKey = user,
                participantPublicKeys = listOf(alice),
            )
        )

        assertTrue(user in participantsOf(room.chatRoom.id))
    }

    /** Membership is a set, so naming the creator among the participants is not a second member. */
    @Test
    fun `listing the creator among the participants does not duplicate them`() = runBlocking {
        seedMembers(user, alice)

        val room = assertNotNull(
            db.nostrNip17Dao().createNip17ChatRoom(
                userPublicKey = user,
                participantPublicKeys = listOf(user, alice),
            )
        )

        assertEquals(setOf(user, alice), participantsOf(room.chatRoom.id))
        assertEquals(2, db.participantDao().findParticipantsByChatRoomId(room.chatRoom.id).size)
    }

    /**
     * What actually enforces this is the `.sorted()` inside `deriveChatRoomId` -- the DAO's
     * `.toSet()` dedupes but carries an order. Sorting is what lets both ends of a
     * conversation derive the same id independently: one from the list a user typed, the other
     * from the p-tags on an arriving gift wrap, which will not be in the same order.
     */
    @Test
    fun `the room id does not depend on the order members are named`() = runBlocking {
        seedMembers(user, alice, bob)

        val first = assertNotNull(
            db.nostrNip17Dao().createNip17ChatRoom(
                userPublicKey = user,
                participantPublicKeys = listOf(alice, bob),
            )
        )
        val second = assertNotNull(
            db.nostrNip17Dao().createNip17ChatRoom(
                userPublicKey = user,
                participantPublicKeys = listOf(bob, alice),
            )
        )

        assertEquals(first.chatRoom.id, second.chatRoom.id)
    }

    /**
     * Idempotence, which is the point of deriving the id rather than generating one. Creating
     * the same conversation twice reuses the room instead of standing up a second one that
     * would split the thread.
     */
    @Test
    fun `creating the same conversation twice reuses the room`() = runBlocking {
        seedMembers(user, alice, bob)

        val first = assertNotNull(
            db.nostrNip17Dao().createNip17ChatRoom(user, listOf(alice, bob), subject = "first")
        )
        val second = assertNotNull(
            db.nostrNip17Dao().createNip17ChatRoom(user, listOf(alice, bob), subject = "ignored")
        )

        assertEquals(first.chatRoom.id, second.chatRoom.id)
        assertEquals(
            "first",
            second.chatRoom.subject,
            "the existing room is reused as it stands rather than rewritten",
        )
        assertEquals(3, db.participantDao().findParticipantsByChatRoomId(first.chatRoom.id).size)
    }

    /** A different member set is a different conversation. */
    @Test
    fun `a different member set derives a different room`() = runBlocking {
        seedMembers(user, alice, bob)

        val pair = assertNotNull(db.nostrNip17Dao().createNip17ChatRoom(user, listOf(alice)))
        val trio = assertNotNull(db.nostrNip17Dao().createNip17ChatRoom(user, listOf(alice, bob)))

        assertTrue(pair.chatRoom.id != trio.chatRoom.id)
    }

    /**
     * What marks the room NIP-17. `sendChatMessage` branches on this field to choose between a
     * kind:445 group event and per-recipient gift wraps, so a non-null value here would route
     * direct messages down the MLS path.
     */
    @Test
    fun `a nip17 room carries no mls state`() = runBlocking {
        seedMembers(user, alice)

        val room = assertNotNull(db.nostrNip17Dao().createNip17ChatRoom(user, listOf(alice)))

        assertNull(room.chatRoom.mlsGroupState, "MLS state is what tells the two room kinds apart")
    }

    /**
     * The precondition, and an asymmetry worth knowing about. A member with no Profile row
     * violates Participant's foreign key, so this raises rather than returning null -- while
     * `getOrCreateChatRoom`, one method down, answers the same "I have never seen this user"
     * situation by returning null. A caller that treats the two alike gets an unhandled
     * exception out of the first one.
     */
    @Test
    fun `creating a room with an unknown member is refused by the schema`() = runBlocking<Unit> {
        seedMembers(user)

        assertFailsWith<androidx.sqlite.SQLiteException> {
            db.nostrNip17Dao().createNip17ChatRoom(
                userPublicKey = user,
                participantPublicKeys = listOf(alice),
            )
        }
    }

    /**
     * getOrCreateChatRoom is the inbound counterpart and takes the id as given, since it comes
     * off an arriving event rather than from a member list. It returns the room already stored
     * rather than overwriting it.
     */
    @Test
    fun `getOrCreate returns the room that already exists`() = runBlocking {
        seedProfile(user)
        val chatRoomId = "11".repeat(32)
        db.chatRoomDao().upsert(
            ChatRoom(
                id = chatRoomId,
                userPublicKey = user,
                subject = "already here",
                description = null,
                mlsGroupState = null,
            )
        )

        val found = assertNotNull(
            db.nostrNip17Dao().getOrCreateChatRoom(
                chatRoomId = chatRoomId,
                activeUserPublicKey = user,
                relayHint = null,
                defaultSubject = "would be new",
            )
        )

        assertEquals("already here", found.chatRoom.subject)
    }

    @Test
    fun `getOrCreate stands up a room the active user has a profile for`() = runBlocking {
        seedProfile(user)
        val chatRoomId = "22".repeat(32)

        val created = assertNotNull(
            db.nostrNip17Dao().getOrCreateChatRoom(
                chatRoomId = chatRoomId,
                activeUserPublicKey = user,
                relayHint = "wss://relay.example",
                defaultSubject = "new room",
            )
        )

        assertEquals(chatRoomId, created.chatRoom.id)
        assertEquals("new room", created.chatRoom.subject)
        assertTrue(user in participantsOf(chatRoomId))
    }

    /**
     * The guard: with no profile for the active user there is nothing to hang a room off, and
     * the DAO returns null rather than writing a room whose owner it cannot name.
     */
    @Test
    fun `getOrCreate refuses when the active user has no profile`() = runBlocking {
        val chatRoomId = "33".repeat(32)

        val created = db.nostrNip17Dao().getOrCreateChatRoom(
            chatRoomId = chatRoomId,
            activeUserPublicKey = user,
            relayHint = null,
        )

        assertNull(created)
        assertNull(db.chatRoomDao().findChatRoomById(chatRoomId), "no room should have been written")
    }
}
