package com.machankura.dedun.compose.database.model.intermdiate

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant

/**
 * The newest line in a room, as much of it as a chat list row can show.
 *
 * Not the [com.machankura.dedun.compose.database.model.ChatMessage] itself: embedding the entity
 * would mean aliasing thirty-odd columns onto every chat-room query, and colliding with
 * the room's own `id` and its four timestamps on the way. These six are everything a
 * one-line preview and the clock beside it are written from.
 *
 * Read back as null for a room nothing has been said in yet, which is a different thing
 * from a room whose last line has no words -- a private message this device cannot open
 * is exactly that, and says so rather than showing an empty row.
 */
data class ChatRoomLastMessage(
    val senderPublicKey: HexKey,
    val isUserMessage: Boolean,
    val content: String,
    val messageType: String,
    val directMessageRecipientPublicKey: HexKey?,
    val createdAt: Instant,
)
