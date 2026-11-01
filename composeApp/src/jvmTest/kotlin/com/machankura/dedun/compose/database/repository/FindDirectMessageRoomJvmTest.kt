package com.machankura.dedun.compose.database.repository

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What counts as a conversation between two keys.
 *
 * The rule `ChatRoomMessagingViewModel.initiateNewChat` applied inline since it was
 * written -- the two of us and nobody else, or yourself alone -- now has a name and a
 * test, because the profile preview reads it too and the two must agree.
 */
class FindDirectMessageRoomJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )
    private val scope = CoroutineScope(Job())
    private val repository = DatabaseChatRepository(db, scope)

    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)

    @AfterTest
    fun closeDb() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `a room with exactly the two of us is the conversation`() = runBlocking {
        seedPeople(me, alice, bob)
        seedRoom("group", me, alice, bob)
        seedRoom("dm", me, alice)

        assertEquals("dm", repository.findDirectMessageRoom(me, alice)?.chatRoom?.id)
    }

    /** A group of two is not told apart from a direct message; a group of three is not one. */
    @Test
    fun `a room with a third member is not`() = runBlocking {
        seedPeople(me, alice, bob)
        seedRoom("group", me, alice, bob)

        assertNull(repository.findDirectMessageRoom(me, alice))
    }

    /** The peer in a room I am not in is not a conversation with me. */
    @Test
    fun `a room the peer has with somebody else is not`() = runBlocking {
        seedPeople(me, alice, bob)
        seedRoom("theirs", alice, bob)

        assertNull(repository.findDirectMessageRoom(me, alice))
    }

    @Test
    fun `a room with myself alone is the conversation with myself`() = runBlocking {
        seedPeople(me, alice)
        seedRoom("notes", me)
        seedRoom("dm", me, alice)

        assertEquals("notes", repository.findDirectMessageRoom(me, me)?.chatRoom?.id)
        assertEquals("dm", repository.findDirectMessageRoom(me, alice)?.chatRoom?.id)
    }

    @Test
    fun `nobody in any room is nothing`() = runBlocking {
        seedPeople(me, alice)

        assertNull(repository.findDirectMessageRoom(me, alice))
    }

    // --- fixtures -----------------------------------------------------------------------

    /** A participant row needs a profile, and a profile needs the event it was read off. */
    private suspend fun seedPeople(vararg publicKeys: HexKey) {
        publicKeys.forEach { publicKey ->
            val nostrEventId = publicKey.take(8).repeat(8)
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
            db.profileDao().upsert(Profile(publicKey = publicKey, userName = publicKey.take(4), nostrEventId = nostrEventId))
        }
    }

    private suspend fun seedRoom(id: String, vararg members: HexKey) {
        db.chatRoomDao().upsert(
            ChatRoom(
                id = id,
                userPublicKey = me,
                subject = null,
                description = null,
                mlsGroupState = null,
            )
        )
        db.participantDao().upsert(
            members.map { publicKey ->
                Participant(
                    participantPublicKey = publicKey,
                    chatRoomId = id,
                    relayHint = null,
                )
            }
        )
    }
}
