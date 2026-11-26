package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DisappearingSettingsStore
import com.machankura.dedun.compose.nostr.DisappearingMessages
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * Disappearing messages reach NIP-17 only (docs/giza-second-batch.md, Phase 7). A group's records
 * -- who owns which coins, whose deposit is whose, payouts -- arrive through its Marmot room, and
 * members settle disagreements about money by pointing at them. Whatever anyone sets, and however
 * far the clock runs, the sweeper leaves them where they are.
 */
class DisappearingLeavesGroupRecordsJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val user = "a".repeat(64)
    private val room = "1".repeat(64)
    private val createdAt = Instant.fromEpochSeconds(1_800_000_000)

    @AfterTest
    fun close() = db.close()

    private suspend fun seed() {
        val profileEvent = "9".repeat(64)
        db.nostrEventDao().upsert(NostrEvent(id = profileEvent, pubKey = user, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = user, nostrEventId = profileEvent))
        db.chatRoomDao().upsert(ChatRoom(id = room, userPublicKey = user, subject = "Stokvel", description = "", mlsGroupState = "state"))
    }

    @Test
    fun `a group room's attributions and coin assignments outlive every sweep`(): Unit = runBlocking {
        seed()
        // The shortest policy a person can choose, on the profile and on the room.
        DisappearingSettingsStore(db).saveDefault(user, DisappearingMessages.MIN_SECONDS)
        DisappearingSettingsStore(db).saveOverride(room, DisappearingMessages.MIN_SECONDS)

        val records = listOf(DepositAttributionEvent.KIND, CoinAssignmentEvent.KIND).mapIndexed { i, kind ->
            MarmotInnerEvent(
                id = ('b' + i).toString().repeat(64),
                publicKey = room,
                chatRoomId = room,
                kind = kind,
                createdAt = createdAt,
                tags = arrayOf(arrayOf("d", room)),
                content = "{}",
            )
        }
        records.forEach { db.marmotInnerEventDao().upsert(it) }
        db.chatMessageDao().upsert(
            ChatMessage(
                giftWrapPayloadId = null,
                messageType = ChatMessage.TYPE_COINS_ASSIGNED,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                senderPublicKey = room,
                isUserMessage = false,
                chatRoomId = room,
                createdAt = createdAt,
                content = "The group assigned coins.",
            )
        )

        // A year past everything, with every payload released.
        val swept = DisappearingMessageSweeper.sweep(
            db,
            { _, _ -> true },
            now = Instant.fromEpochSeconds(createdAt.epochSeconds + 2 * DisappearingMessages.MAX_SECONDS),
        )

        assertEquals(0, swept.payloads)
        records.forEach { assertNotNull(db.marmotInnerEventDao().getById(it.id), "a group record of kind ${it.kind} was swept") }
        assertEquals(1, db.chatMessageDao().getChatMessagesByChatRoomId(room).count { it.chatMessage.messageType == ChatMessage.TYPE_COINS_ASSIGNED })
    }
}
