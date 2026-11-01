package com.machankura.dedun.compose.database.model

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import kotlin.test.assertNotNull
import com.machankura.dedun.compose.nostr.MarmotDirectMessage
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents

/**
 * A key ceremony's or a signing session's message, arriving in a Marmot room,
 * is recognised as one before anything that shares its kind number.
 *
 * 30310-30312 are both ceremony kinds and nip30303 document kinds. Round 1 of a
 * subgroup's ceremony (30312, hex content) was read as a document submission,
 * failed to parse, and reached the parent's chat as a bubble of raw hex on every
 * other admin's device -- docs/ceremony-creation.md, finding F11.
 */
class ProtocolKindDispatchJvmTest {

    private val database: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = database.close()

    private val self = KeyPair()
    private val senderSigner = NostrSignerSync(KeyPair())
    private val sender = senderSigner.pubKey
    private val chatRoomId = "639f".repeat(16)

    private var next = 0

    /** A kind:445 this device has stored, carrying one inner event of [kind]. */
    private suspend fun arrive(kind: Kind, content: String): Pair<GroupEvent, ChatMessage?> = arrive(
        Event(
            id = "%064x".format(2000 + next),
            pubKey = sender,
            createdAt = 1,
            kind = kind,
            tags = DkgRitualEvents.assembleTags(sessionId = "5e55".repeat(16)),
            content = content,
            sig = "",
        )
    )

    /** A kind:445 this device has stored, carrying [inner]. */
    private suspend fun arrive(inner: Event): Pair<GroupEvent, ChatMessage?> {
        val n = next++
        val groupEventId = "%064x".format(1000 + n)

        database.nostrEventDao().upsert(
            NostrEvent(
                id = groupEventId,
                pubKey = sender,
                kind = GroupEvent.KIND,
                tags = arrayOf(arrayOf("h", chatRoomId)),
                content = "ciphertext",
                sig = "0".repeat(128),
            )
        )
        database.marmotGroupEventDao().upsert(
            MarmotGroupEvent(
                id = groupEventId,
                userPublicKey = self.pubKey.toHexKey(),
                publicKey = sender,
                chatRoomId = chatRoomId,
                signature = "0".repeat(128),
                encryptedContent = "ciphertext",
                expiresAt = null,
                createdAt = Instant.fromEpochSeconds(1),
            )
        )

        val groupEvent = GroupEvent(
            id = groupEventId,
            pubKey = sender,
            createdAt = 1,
            tags = arrayOf(arrayOf("h", chatRoomId)),
            content = "ciphertext",
            sig = "0".repeat(128),
        )
        val line = ChatMessage.fromGroupEventResult(
            database = database,
            activeKeyPair = self,
            groupEvent = groupEvent,
            groupEventResult = GroupEventResult.ApplicationMessage(
                groupId = chatRoomId,
                innerEventJson = inner.toJson(),
                senderLeafIndex = 1,
                epoch = 1,
            ),
            senderIdentity = sender,
        )

        return groupEvent to line
    }

    // What a round-1 message actually carries: a ChillDKG pmsg1, hex encoded.
    // Its leading zero is what made it unparseable as an event.
    private val round1Content = "024f99efa68cafafd766eec95ad940cd096a5f1a7b42d14132121ad7d2dc310d03"

    @Test
    fun `a ceremony message writes no chat line, whatever shares its number`(): Unit = runBlocking {
        DkgRitualEvents.ALL.forEach { kind ->
            val (groupEvent, line) = arrive(kind, content = round1Content)

            assertNull(line, "kind $kind is the ceremony's, and writes no line of its own")
            assertNull(
                database.chatMessageDao().getChatMessagesByMarmotGroupEventId(groupEvent.id),
                "and nothing is filed in its place",
            )
        }

        assertEquals(
            DkgRitualEvents.ALL,
            database.marmotInnerEventDao()
                .getByChatRoomAndKinds(chatRoomId, DkgRitualEvents.ALL.toList())
                .map { it.kind }
                .toSet(),
            "every one is still stored, so a ceremony can replay what arrived before its proposal",
        )
    }

    /**
     * A direct message's envelope shares 30330 with a signing session's decline.
     *
     * Treating every 30330 as a decline -- which is what recognising protocol
     * kinds first did at first -- dropped every direct message in a
     * current-profile room before it was opened. What tells the two apart is
     * what the event carries.
     */
    @Test
    fun `a direct message envelope is opened, not taken for a decline`(): Unit = runBlocking {
        val wrap = MarmotDirectMessage.wrap(
            signer = senderSigner,
            recipientPublicKey = self.pubKey.toHexKey(),
            kind = ChatMessageEvent.KIND,
            createdAt = 1,
            tags = arrayOf(PTag.assemble(self.pubKey.toHexKey(), null)),
            content = "the vote is at six",
        )
        val envelope = MarmotDirectMessage.envelope(sender, self.pubKey.toHexKey(), wrap, createdAt = 1)
        assertEquals(FrostSigningEvents.DECLINE, envelope.kind, "the collision this test exists for")

        val (_, line) = arrive(Event.fromJson(envelope.toJson()))

        val opened = assertNotNull(line, "the envelope must reach the direct-message path")
        assertTrue(opened.content.contains("the vote is at six"), "and be opened: ${opened.content}")
    }

    @Test
    fun `a signing message writes no chat line`(): Unit = runBlocking {
        FrostSigningEvents.ALL.forEach { kind ->
            val (_, line) = arrive(kind, content = "{}")
            assertNull(line, "kind $kind is a signing session's")
        }

        assertTrue(
            database.chatMessageDao().getChatMessagesByChatRoomId(chatRoomId).isEmpty(),
            "and the room's transcript is untouched",
        )
    }

    /**
     * The Lightning wallet's protocol is the third family, and the one that was missed:
     * every kind of it left a raw-JSON bubble, attributed to whoever sent it, beside the
     * lines its managers write. docs/group-lightning-transcript.md, Phase 1.
     */
    @Test
    fun `a Lightning wallet message writes no chat line`(): Unit = runBlocking {
        GroupLightningEvents.PROTOCOL.forEach { kind ->
            val (_, line) = arrive(kind, content = """{"wallet":"w"}""")
            assertNull(line, "kind $kind is the Lightning wallet's protocol")
        }

        assertTrue(
            database.chatMessageDao().getChatMessagesByChatRoomId(chatRoomId).isEmpty(),
            "and the room's transcript is untouched",
        )
        assertEquals(
            GroupLightningEvents.PROTOCOL,
            database.marmotInnerEventDao()
                .getByChatRoomAndKinds(chatRoomId, GroupLightningEvents.PROTOCOL.toList())
                .map { it.kind }
                .toSet(),
            "every one is still stored, for the managers that replay them",
        )
    }

    /** The three the group signs are not protocol: their arms check them and describe them. */
    @Test
    fun `none of the kinds the group signs is protocol`() {
        assertTrue((GroupLightningEvents.PROTOCOL intersect GroupLightningEvents.GROUP_SIGNED).isEmpty())
        assertEquals(GroupLightningEvents.ALL, GroupLightningEvents.PROTOCOL + GroupLightningEvents.GROUP_SIGNED)
    }
}
