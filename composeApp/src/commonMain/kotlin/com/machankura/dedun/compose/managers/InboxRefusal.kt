package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.InboxSigning
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.inbox.InboxAnnouncement

/**
 * What a device declines, out loud, when the group is asked to sign something about its inbox
 * (docs/group-inbox.md, D5): the announcement of its key (10044) and of where its mail goes
 * (10050).
 *
 * The signer has no allowlist, so without this a peer's 10044 reached the member as "Event of
 * kind 10044" and could be signed whatever key it named -- including one a removed admin made,
 * or one nobody in the group holds, either of which hands the group's mail to someone the
 * group did not choose. Dedun signs only what it can check.
 *
 * Checked in the four places every refusal sits in [FrostSigningManager]: on arrival, again at
 * approval, as the decline's reason, and (for a session filed before this existed) at approval
 * through the same call.
 */
object InboxRefusal {
    const val KEY_NOT_MADE = "Dedun doesn't sign an inbox key the group's admins didn't make together"
    const val KEY_NOT_NEWEST = "Dedun doesn't sign an inbox key the group has since replaced"
    const val KEY_NOT_ADMINS = "Dedun doesn't sign an inbox key made by anyone but the group's current admins"
    const val KEY_SHAPE = "Dedun doesn't sign an inbox key announcement it would not write"
    const val RELAYS_SHAPE = "Dedun doesn't sign a list of inbox relays a stranger could not reach"

    /** Whether any of [events] is one this refusal is about. */
    fun involves(events: List<Event>): Boolean =
        events.any { it.kind == InboxAnnouncement.KEY_KIND || it.kind == InboxAnnouncement.RELAYS_KIND }

    /**
     * Why this device declines to sign [events] in [localChatRoom] as [userPublicKey] sees it, or
     * null when it would sign them.
     */
    suspend fun of(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        events: List<Event>,
    ): String? {
        for (event in events) {
            when (event.kind) {
                InboxAnnouncement.RELAYS_KIND ->
                    if (InboxAnnouncement.groupRelaysOf(event) == null) return RELAYS_SHAPE
                InboxAnnouncement.KEY_KIND -> {
                    val key = InboxAnnouncement.groupKeyOf(event) ?: return KEY_SHAPE
                    keyRefusal(database, localChatRoom, userPublicKey, key)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * D5's three checks on the key a 10044 names: it is what a ceremony in this room made, the
     * newest one, and that ceremony was run over the admins the room has now.
     */
    private suspend fun keyRefusal(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, key: HexKey): String? {
        val roomId = localChatRoom.chatRoom.id
        val completed = database.inboxKeyDao().getCompletedCeremonies(roomId, userPublicKey)
        val maker = completed.firstOrNull { keyOf(database, it) == key }
        if (maker == null) {
            // The coordinator asks for the announcement the moment its own copy of the key is
            // made, which is routinely before this device has finished the same ceremony. Not a
            // refusal yet: the session waits, approval checks again, and the ceremony finishing
            // here asks again ([FrostSigningManager.resumeInboxAnnouncements]).
            val running = database.inboxKeyDao().getRunningCeremoniesIn(roomId).any { it.userPublicKey == userPublicKey }
            return if (running) null else KEY_NOT_MADE
        }
        if (completed.any { it.generation > maker.generation }) return KEY_NOT_NEWEST
        val admins = RoomAdmins.of(database, roomId)?.admins ?: return null
        if (admins != maker.participants().toSet()) return KEY_NOT_ADMINS
        return null
    }

    /** The key [ceremony] made, recomputing it on a watcher's device that missed it at the time. */
    private suspend fun keyOf(database: DedunDatabase, ceremony: InboxKeyCeremony): HexKey? =
        ceremony.inboxPublicKey ?: InboxKeyRitualManager.keyWatched(database, ceremony)

    /**
     * Whether this device can sign [items] on its owner's behalf (D5): a rotation's 10044 alone,
     * naming a key this device holds the secret of, in a room whose quorum has announced an
     * inbox before. The decision to have an inbox was asked; keeping it is what a rotation is.
     */
    suspend fun vouchesFor(database: DedunDatabase, localChatRoom: LocalChatRoom, session: FrostSigningSession, items: List<FrostSigningItem>): Boolean {
        val events = items.mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }
        val announcement = events.singleOrNull()?.takeIf { it.kind == InboxAnnouncement.KEY_KIND } ?: return false
        if (of(database, localChatRoom, session.userPublicKey, events) != null) return false
        val key = InboxAnnouncement.groupKeyOf(announcement) ?: return false
        val roomId = localChatRoom.chatRoom.id
        // Never on the strength of a ceremony still running: the refusal lets one wait, and
        // vouching is for a key this device has.
        val held = database.inboxKeyDao().getKeys(roomId, session.userPublicKey)
        if (held.none { it.publicKey == key && it.secretKey != null }) return false
        val announcedBefore = database.groupSignedEventDao().getByChatRoomIdAndKind(roomId, InboxAnnouncement.KEY_KIND)
        return announcedBefore.any { it.publicKey == roomId && InboxAnnouncement.groupKeyOf(it.kind, it.tags, it.content) != key }
    }

    /** Keeps why [sessionId] was refused, so the decline can say it. */
    suspend fun remember(database: DedunDatabase, sessionId: String, chatRoomId: String, refusal: String?, manifest: String? = null) {
        val existing = database.groupInboxDao().getSigning(sessionId)
        database.groupInboxDao().upsertSigning(
            (existing ?: InboxSigning(sessionId = sessionId, chatRoomId = chatRoomId)).copy(
                refusal = refusal ?: existing?.refusal,
                manifest = manifest ?: existing?.manifest,
            )
        )
    }

    suspend fun reasonFor(database: DedunDatabase, sessionId: String): String? =
        database.groupInboxDao().getSigning(sessionId)?.refusal
}
