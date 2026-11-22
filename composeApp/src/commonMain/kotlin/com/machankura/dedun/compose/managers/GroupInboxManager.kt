package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.DisappearingMessages
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import kotlin.time.Clock
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.GroupInboxWrap
import com.machankura.dedun.compose.database.model.InboxKey
import com.machankura.dedun.compose.nostr.inbox.InboxAnnouncement
import com.machankura.dedun.compose.nostr.inbox.InboxConversation
import com.machankura.dedun.compose.nostr.inbox.InboxSeal
import com.machankura.dedun.compose.nostr.inbox.InboxWrap

/**
 * Reading a group's inbox (docs/group-inbox.md, D8 and D9): every gift wrap addressed to a group
 * this device holds an inbox key for is put in the ledger, opened if any key it holds opens it,
 * and filed as one message per rumor.
 *
 * Its own path, beside the personal inbox and never inside it. The personal path tries only the
 * active identity's key and files what it opens into the admin's own direct messages; the
 * group's correspondents are not the admin's, and nothing here writes a `GiftWrapMessage` or a
 * `NostrEvent`.
 */
object GroupInboxManager {
    private val logger = Logger.withTag("GroupInboxManager")

    /** The secrets this identity can open [chatRoomId]'s mail with, newest generation first. */
    private suspend fun openers(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey): List<InboxKey> =
        database.inboxKeyDao().getKeys(chatRoomId, userPublicKey).filter { it.secretKey != null }

    /**
     * Files [wrap], heard on a relay, for whichever of [userPublicKey]'s groups it is addressed
     * to. Returns the message it filed, or null when it filed none: not a group's, already
     * known, or not one this device can open yet -- which is kept and counted, not dropped.
     */
    suspend fun receive(database: DedunDatabase, wrap: Event, userPublicKey: HexKey): GroupInboxMessage? {
        if (wrap.kind != InboxWrap.KIND) return null
        val tagged = InboxWrap.recipientOf(wrap)?.lowercase() ?: return null
        // The group's npub, as #2361 has it; or one of its inbox keys, as an app that only
        // encrypts to the announced key tags it.
        val roomId = if (openers(database, tagged, userPublicKey).isNotEmpty()) {
            tagged
        } else {
            database.inboxKeyDao().findHeldByPublicKey(userPublicKey, tagged)?.chatRoomId ?: return null
        }
        val keys = openers(database, roomId, userPublicKey)
        if (keys.isEmpty()) return null

        val inserted = database.groupInboxDao().insertWrap(
            GroupInboxWrap(id = wrap.id, chatRoomId = roomId, wrapJson = wrap.toJson(), wrapCreatedAt = wrap.createdAt)
        )
        if (inserted < 0 && database.groupInboxDao().getWrap(wrap.id)?.openedGeneration != null) return null
        return open(database, roomId, userPublicKey, wrap, keys)
    }

    /**
     * Tries every wrap in [chatRoomId] no key has opened yet, with the keys held now: what a key
     * that arrives after the mail -- a ceremony finishing, a restore -- is for. Returns how many
     * it opened.
     */
    suspend fun reopen(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey): Int {
        val keys = openers(database, chatRoomId, userPublicKey)
        if (keys.isEmpty()) return 0
        return database.groupInboxDao().getUnopenedWraps(chatRoomId).count { row ->
            val wrap = Event.fromJsonOrNull(row.wrapJson) ?: return@count false
            open(database, chatRoomId, userPublicKey, wrap, keys) != null
        }
    }

    private suspend fun open(database: DedunDatabase, roomId: String, userPublicKey: HexKey, wrap: Event, keys: List<InboxKey>): GroupInboxMessage? {
        val secrets = keys.map { it.secretKey!!.hexToByteArray() }
        val (index, seal) = InboxWrap.open(wrap, secrets) ?: return null
        val key = keys[index]

        // The sender's own 10044, if this device has heard it, for a seal from a decoupled
        // sender whose app wrote no `n` tag.
        val senderAnnounced = database.nostrEventDao().getNostrEventByPublicKeyAndKind(seal.pubKey, InboxAnnouncement.KEY_KIND)
            ?.let { InboxAnnouncement.announcedKeyOf(it.toEvent()) }
        val opened = InboxSeal.open(seal, secrets[index], senderAnnounced) ?: run {
            logger.w("Wrap ${wrap.id.take(8)} to ${roomId.take(8)} opened, but its seal did not; leaving it counted")
            return null
        }
        val rumor = opened.rumor
        // NIP-40: a message already past its expiry is not filed; the ledger keeps the wrap's id
        // but not its words (docs/disappearing-messages.md, D6).
        if (DisappearingMessages.expirationOf(rumor.tags, rumor.createdAt)?.let { DisappearingMessages.isExpired(it, Clock.System.now().epochSeconds) } == true) {
            database.groupInboxDao().markOpened(wrap.id, key.generation)
            database.disappearingDao().blankInboxWrap(wrap.id)
            return null
        }
        val participants = InboxConversation.participantsOf(rumor, roomId)
        val message = GroupInboxMessage(
            chatRoomId = roomId,
            rumorId = rumor.id,
            wrapId = wrap.id,
            conversationId = InboxConversation.idOf(participants),
            participants = participants.joinToString(","),
            senderPublicKey = rumor.pubKey,
            kind = rumor.kind,
            rumorJson = InboxSeal.rumorJson(rumor),
            createdAt = rumor.createdAt,
            generation = key.generation,
            outgoing = rumor.pubKey.equals(roomId, ignoreCase = true),
        )
        database.groupInboxDao().insertMessage(message)
        database.groupInboxDao().markOpened(wrap.id, key.generation)
        return database.groupInboxDao().getMessage(roomId, rumor.id)
    }

    /**
     * Forgets [chatRoomId]'s inbox on this device when [userPublicKey] is no longer one of its
     * admins (docs/group-inbox.md, D14): the keys' secrets and every message they opened. Said
     * in the room, once. Cooperation, not enforcement: a device that does not run this keeps
     * what it had, and the rotation that follows is what the group can do about it.
     */
    suspend fun forgetIfNotAdmin(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey, stillAdmin: Boolean) {
        if (stillAdmin) return
        if (openers(database, chatRoomId, userPublicKey).isEmpty()) return
        database.inboxKeyDao().wipeKeys(chatRoomId, userPublicKey, Clock.System.now())
        database.groupInboxDao().deleteMessages(chatRoomId)
        database.groupInboxDao().deleteWraps(chatRoomId)
        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = userPublicKey,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = chatRoomId,
                content = "This device forgot the group's inbox key and its mail, since you are no longer an admin.",
                messageType = ChatMessage.TYPE_INBOX_FORGOTTEN,
            )
        )
        logger.i("Forgot the inbox of ${chatRoomId.take(8)}: no longer an admin")
    }
}
