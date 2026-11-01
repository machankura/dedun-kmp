package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant

/**
 * Writes the group Lightning wallet's lines into its room's transcript ([ChatMessage.LIGHTNING_TYPES]).
 * See docs/group-lightning-transcript.md, Phase 4.
 *
 * Each line hangs off the inner event it describes, which is what makes it one line: the device
 * that sends a message writes it on the way out, every other device writes it on arrival, and a
 * redelivery or a replay finds the line already there. The inner event row exists in both cases --
 * `GroupLightningWalletManager.queue` writes it on the way out, and the inbound path files every
 * inner event before its manager sees it -- which the foreign key on [ChatMessage.marmotInnerEventId]
 * needs.
 */
internal object GroupLightningLines {
    private val logger = Logger.withTag("GroupLightningLines")

    suspend fun write(
        database: DedunDatabase,
        chatRoomId: String,
        innerEventId: HexKey,
        messageType: String,
        actor: HexKey,
        content: String,
        createdAt: Instant,
    ) {
        if (database.chatMessageDao().getChatMessagesByMarmotInnerEventId(innerEventId) != null) return
        if (database.marmotInnerEventDao().getById(innerEventId) == null) {
            return logger.w("No inner event $innerEventId is stored to hang a $messageType line off; writing none")
        }
        val reader = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.userPublicKey
        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = actor,
                isUserMessage = reader != null && reader.equals(actor, ignoreCase = true),
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = innerEventId,
                chatRoomId = chatRoomId,
                content = content,
                messageType = messageType,
                createdAt = createdAt,
            )
        )
    }

    /**
     * Writes the line for one fact the operator's snapshots report, unless the room already has it.
     * There is no message to hang it off: the same fact arrives in every snapshot until it stops
     * being true, so [factKey] -- the channel, the payment -- is what makes it one line.
     */
    suspend fun writeFact(
        database: DedunDatabase,
        chatRoomId: String,
        messageType: String,
        factKey: String,
        reportedBy: HexKey,
        content: String,
        createdAt: Instant,
    ) {
        if (database.chatMessageDao().getLightningFactLine(chatRoomId, messageType, factKey) != null) return
        val reader = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.userPublicKey
        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = reportedBy,
                isUserMessage = reader != null && reader.equals(reportedBy, ignoreCase = true),
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = chatRoomId,
                content = content,
                messageType = messageType,
                lightningFactKey = factKey,
                createdAt = createdAt,
            )
        )
    }
}
