package com.machankura.dedun.compose.database.repository

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.exceptions.MarmotMissingChatGroupException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the room is told when an invite does not happen.
 *
 * `MarmotOutboundDao.inviteMemberToChatRoom` writes the invite's transcript line inside its
 * own transaction, which is right: an invite that does not survive `addMember` must not leave
 * the room claiming one was made. But it means a refusal takes the only account of itself
 * down with it, and the screen that asked has closed by the time anybody looks -- so the
 * repository is where a failure has to be written from, outside the transaction that rolled
 * back.
 */
class DatabaseChatRepositoryJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val peer = KeyPair().pubKey.toHexKey()
    private val roomId = "b".repeat(64)

    private val repository = DatabaseChatRepository(
        database = db,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private suspend fun seedProfile(publicKey: String, name: String, nostrEventId: String) {
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
            Profile(publicKey = publicKey, userName = name, nostrEventId = nostrEventId)
        )
    }

    /** A room restored from an inbound gift wrap: no MLS state, so nothing to invite into. */
    private suspend fun seedStatelessRoom(): LocalChatRoom {
        seedProfile(user, "user", "c".repeat(64))
        seedProfile(peer, "peer", "e".repeat(64))
        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = user,
            subject = "a restored room",
            description = null,
            mlsGroupState = null,
        )
        db.chatRoomDao().upsert(chatRoom)
        return LocalChatRoom(chatRoom = chatRoom)
    }

    private fun keyPackage() = MarmotKeyPackage(
        id = "d".repeat(64),
        publicKey = peer,
        tlsEncodedMarmotKeyPackage = ByteArray(0),
    )

    @Test
    fun `a refused invite is written into the room it was refused for`() = runBlocking<Unit> {
        val localChatRoom = seedStatelessRoom()

        assertFailsWith<MarmotMissingChatGroupException> {
            repository.inviteMember(
                localChatRoom = localChatRoom,
                peerPublicKey = peer,
                peerKeyPackage = keyPackage(),
            )
        }

        val line = db.chatMessageDao().getChatMessagesByChatRoomId(roomId)
            .map { it.chatMessage }
            .singleOrNull { it.messageType == ChatMessage.TYPE_MEMBER_INVITE_FAILED }

        assertNotNull(line, "the refused invite left no line in the room")
        assertTrue(line.content.contains("peer"), "the line does not name the invitee: ${line.content}")
    }

    /**
     * The caller still has to hear about it. The transcript line is the copy that is there
     * tomorrow; the throw is what puts a message on the invite screen today, and swallowing it
     * would pop the user back to the chat as though the invite had gone out.
     */
    @Test
    fun `a refused invite still reaches the caller`() = runBlocking<Unit> {
        val localChatRoom = seedStatelessRoom()

        assertFailsWith<MarmotMissingChatGroupException> {
            repository.inviteMember(
                localChatRoom = localChatRoom,
                peerPublicKey = peer,
                peerKeyPackage = keyPackage(),
            )
        }
    }
}
