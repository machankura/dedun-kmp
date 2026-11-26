package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupDmMessage
import com.machankura.dedun.compose.database.model.GroupDmOutbound
import com.machankura.dedun.compose.database.model.GroupDmWrap
import com.machankura.dedun.compose.database.model.GroupDmWrapState
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.nostr.DisappearingMessages.DAY
import com.machankura.dedun.compose.nostr.DisappearingMessages.HOUR

/**
 * docs/disappearing-messages.md, Phase 7: a group's expired messages leave the device with the
 * words of the wraps that carried them, and a confederation's statements are left alone.
 */
class DisappearingGroupMessagesJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val group = KeyPair().pubKey.toHexKey()
    private val alice = KeyPair().pubKey.toHexKey()
    private val createdAt = 1_800_000_000L

    private suspend fun seed() {
        val nostrEventId = user.take(63) + "f"
        db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = user, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = user, userName = "member", nostrEventId = nostrEventId))
        db.chatRoomDao().upsert(ChatRoom(id = group, userPublicKey = user, subject = "group", description = null, mlsGroupState = null))
    }

    private fun rumor(kind: Int, from: String, expiry: Long?): Event {
        val tags = listOfNotNull(arrayOf("p", alice), expiry?.let { arrayOf("expiration", it.toString()) }).toTypedArray()
        return RumorAssembler.assembleRumor(from, EventTemplate(createdAt, kind, tags, "dumela"))
    }

    @Test
    fun `an expired group message goes, its wrap keeps only its id, and what was owed is dropped`(): Unit = runBlocking {
        seed()
        val received = rumor(14, alice, createdAt + HOUR)
        db.groupDmDao().insertWrap(GroupDmWrap(id = "w".repeat(64), chatRoomId = group, wrapJson = "{ciphertext}", createdAt = Instant.fromEpochSeconds(createdAt), state = GroupDmWrapState.OPENED, sealJson = "{seal}"))
        db.groupDmDao().insertMessage(
            GroupDmMessage(rumorId = received.id, chatRoomId = group, conversationId = alice, senderPublicKey = alice, rumorJson = received.toJson(),
                createdAt = Instant.fromEpochSeconds(createdAt), outgoing = false, wrapId = "w".repeat(64))
        )
        val sent = rumor(14, group, createdAt + HOUR)
        db.groupDmDao().insertMessage(
            GroupDmMessage(rumorId = sent.id, chatRoomId = group, conversationId = alice, senderPublicKey = group, rumorJson = sent.toJson(),
                createdAt = Instant.fromEpochSeconds(createdAt), outgoing = true, signingSessionId = "session")
        )
        db.groupDmDao().insertOutbound(GroupDmOutbound(signingSessionId = "session", sealId = "s".repeat(64), chatRoomId = group, recipient = alice, relays = ""))
        val kept = rumor(14, alice, null)
        db.groupDmDao().insertMessage(
            GroupDmMessage(rumorId = kept.id, chatRoomId = group, conversationId = alice, senderPublicKey = alice, rumorJson = kept.toJson(),
                createdAt = Instant.fromEpochSeconds(createdAt), outgoing = false)
        )
        assertEquals(Instant.fromEpochSeconds(createdAt + HOUR), assertNotNull(db.groupDmDao().getMessage(received.id)).expiresAt)

        DisappearingMessageSweeper.sweep(db, ProtocolRetention, now = Instant.fromEpochSeconds(createdAt + HOUR))

        assertNull(db.groupDmDao().getMessage(received.id))
        assertNull(db.groupDmDao().getMessage(sent.id))
        assertNotNull(db.groupDmDao().getMessage(kept.id), "a message with no expiry was swept")
        val wrap = assertNotNull(db.groupDmDao().getWrap("w".repeat(64)), "the ledger row went, so a re-sync would file it again")
        assertEquals("", wrap.wrapJson)
        assertNull(wrap.sealJson)
        assertEquals(emptyList(), db.groupDmDao().getOutbound("session"))
    }

    @Test
    fun `an expired inbox reply goes, and one with no expiry stays`(): Unit = runBlocking {
        seed()
        val reply = rumor(14, alice, createdAt + DAY)
        // Dedun takes no confederations (D12), so the rumor Giza kept here is a statement; the one
        // kept here is a message whose sender set no expiry.
        val statement = rumor(14, alice, null)
        listOf(reply, statement).forEach {
            db.groupInboxDao().insertMessage(
                GroupInboxMessage(chatRoomId = group, rumorId = it.id, wrapId = null, conversationId = alice, participants = alice,
                    senderPublicKey = alice, kind = it.kind, rumorJson = it.toJson(), createdAt = createdAt, generation = 1, outgoing = false)
            )
        }

        DisappearingMessageSweeper.sweep(db, ProtocolRetention, now = Instant.fromEpochSeconds(createdAt + DAY))

        assertNull(db.groupInboxDao().getMessage(group, reply.id))
        assertNotNull(db.groupInboxDao().getMessage(group, statement.id), "a message with no expiry went with the expired one")
    }
}
