package com.machankura.dedun.compose.database.model

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase

/**
 * What the transcript does with a profile or a post the group signed as itself.
 *
 * `applyInnerEvent` is reached three ways -- a rumor arriving in the room, a
 * signing session completing, a chronicle page -- and these are signed the way a
 * completed session's are: by the key whose public half is the room's id.
 * docs/group-nostr-profile-and-posts.md.
 */
class GroupStatementApplyJvmTest {

    private val database: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = database.close()

    /** The room's own key: its public half is the room's id, as for a derived room. */
    private val room = KeyPair()
    private val roomId = room.pubKey.toHexKey()
    private val member = KeyPair()

    private fun signedBy(keyPair: KeyPair, kind: Int, content: String): Event =
        NostrSignerSync(keyPair).signNormal(
            createdAt = 1_700_000_000,
            kind = kind,
            tags = emptyArray(),
            content = content,
        )

    private suspend fun apply(event: Event): ChatMessage? = ChatMessage.applyInnerEvent(
        database = database,
        groupId = roomId,
        event = event,
        marmotGroupEventId = null,
        marmotInnerEventId = null,
        senderPublicKey = member.pubKey.toHexKey(),
        isUserMessage = false,
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
        groupSignedEventId = event.id,
    )

    @Test
    fun `a profile the room signed says what the group is now called`(): Unit = runBlocking {
        val profile = signedBy(room, MetadataEvent.KIND, """{"name":"Translation collective"}""")

        val line = assertNotNull(apply(profile))

        assertEquals(ChatMessage.TYPE_GROUP_PROFILE_SIGNED, line.messageType)
        assertEquals("The group is now called Translation collective", line.content)
        assertTrue(line.messageType in ChatMessage.GROUP_IDENTITY_TYPES, "a system line, not a bubble")
    }

    @Test
    fun `a profile with no name is still a line`(): Unit = runBlocking {
        val profile = signedBy(room, MetadataEvent.KIND, """{"about":"Hard books"}""")

        assertEquals("The group signed a new profile for itself", apply(profile)?.content)
    }

    @Test
    fun `a member's own profile passing through is no line`(): Unit = runBlocking {
        val profile = signedBy(member, MetadataEvent.KIND, """{"name":"Thabo"}""")

        assertNull(apply(profile))
    }

    @Test
    fun `a profile claiming the room's key without its signature is no line`(): Unit = runBlocking {
        // A rumor carries whatever pubkey its sender likes: one member must not be
        // able to rename the group in its own transcript.
        val forged = signedBy(member, MetadataEvent.KIND, """{"name":"Hijacked"}""").let {
            Event(it.id, roomId, it.createdAt, it.kind, it.tags, it.content, it.sig)
        }

        assertNull(apply(forged))
    }

    @Test
    fun `a post the room signed is a line, not the post`(): Unit = runBlocking {
        val post = signedBy(room, TextNoteEvent.KIND, "We meet on Thursday")

        val line = assertNotNull(apply(post))

        assertEquals(ChatMessage.TYPE_GROUP_POST_SIGNED, line.messageType)
        assertEquals("The group posted in its own name", line.content)
        assertTrue(line.messageType in ChatMessage.GROUP_IDENTITY_TYPES, "a system line, not a bubble")
    }

    @Test
    fun `a member's own post is a raw bubble`(): Unit = runBlocking {
        val post = signedBy(member, TextNoteEvent.KIND, "We meet on Thursday")

        assertEquals("unsupported", apply(post)?.messageType)
    }

    @Test
    fun `a post claiming the room's key without its signature is a raw bubble`(): Unit = runBlocking {
        val forged = signedBy(member, TextNoteEvent.KIND, "Words in the group's mouth").let {
            Event(it.id, roomId, it.createdAt, it.kind, it.tags, it.content, it.sig)
        }

        assertEquals("unsupported", apply(forged)?.messageType)
    }

    @Test
    fun `a relay list the room signed is still no line`(): Unit = runBlocking {
        // docs/group-nostr-profile-and-posts.md, D1.
        assertNull(apply(signedBy(room, AdvertisedRelayListEvent.KIND, "")))
    }

    @Test
    fun `lines older builds wrote for the group's profile and posts read as today's`() {
        // The stored strings did not change when the types came back (D3), so a
        // row Dedun wrote before it removed the feature is one of these.
        assertEquals("groupProfileSigned", ChatMessage.TYPE_GROUP_PROFILE_SIGNED)
        assertEquals("groupPostSigned", ChatMessage.TYPE_GROUP_POST_SIGNED)
        listOf("groupProfileSigned", "groupPostSigned", "groupRelaysSigned").forEach { type ->
            assertTrue(type in ChatMessage.GROUP_IDENTITY_TYPES, type)
        }
    }
}
