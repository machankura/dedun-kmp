package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlin.time.Clock
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupInboxPublication
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.inbox.InboxAnnouncement

/**
 * What a signed announcement of the group's inbox changes on a device (docs/group-inbox.md, D7
 * and D14): the key it names becomes the one replies are sealed from, the ones before it are
 * kept to open with, and the device that proposed it owes it to relays.
 */
object InboxAnnouncements {
    /** Called by [FrostSigningManager.complete] on every device, with what the session signed. */
    suspend fun onSigned(database: DedunDatabase, session: FrostSigningSession, signedEvents: List<Event>) {
        val roomId = session.chatRoomId
        val ours = signedEvents.filter {
            it.pubKey.equals(roomId, ignoreCase = true) &&
                (it.kind == InboxAnnouncement.KEY_KIND || it.kind == InboxAnnouncement.RELAYS_KIND)
        }
        if (ours.isEmpty()) return

        ours.firstOrNull { it.kind == InboxAnnouncement.KEY_KIND }?.let { announcement ->
            InboxAnnouncement.groupKeyOf(announcement)?.let { key -> settle(database, roomId, session.userPublicKey, key, announcement.id) }
        }

        // Only the device that asked for the signature sends it, as for a confederation
        // event: one sender, retrying until a relay takes it, rather than every signer racing.
        if (!session.isCoordinator()) return
        val relays = (relaysOf(database, roomId, signedEvents) + Relays.DefaultDMRelayList).distinct()
        ours.forEach { event ->
            database.groupInboxDao().upsertPublication(
                GroupInboxPublication(
                    eventId = event.id,
                    chatRoomId = roomId,
                    kind = event.kind,
                    eventJson = event.toJson(),
                    relays = relays.joinToString(",") { it.url },
                    signingSessionId = session.id,
                    recipientPublicKey = null,
                )
            )
        }
    }

    /**
     * [key] is the group's announced inbox key now: its generation is [InboxKeyState.ANNOUNCED]
     * and every earlier one this device still holds is [InboxKeyState.RETIRED]. A later
     * generation made but not yet announced stays as it is.
     */
    private suspend fun settle(database: DedunDatabase, roomId: String, userPublicKey: String, key: String, eventId: String) {
        val keys = database.inboxKeyDao().getKeys(roomId, userPublicKey)
        val named = keys.firstOrNull { it.publicKey == key } ?: return
        val now = Clock.System.now()
        keys.forEach { held ->
            val next = when {
                held.generation == named.generation -> held.copy(state = InboxKeyState.ANNOUNCED, announcementEventId = eventId, announcedAt = now)
                held.generation < named.generation && held.state != InboxKeyState.WIPED -> held.copy(state = InboxKeyState.RETIRED)
                else -> held
            }
            if (next != held) database.inboxKeyDao().upsertKey(next)
        }
    }

    /** The relays the group's mail should go to: this batch's 10050, else the newest one it signed. */
    suspend fun relaysOf(database: DedunDatabase, roomId: String, batch: List<Event> = emptyList()): List<NormalizedRelayUrl> {
        batch.firstOrNull { it.kind == InboxAnnouncement.RELAYS_KIND && it.pubKey.equals(roomId, ignoreCase = true) }
            ?.let { InboxAnnouncement.groupRelaysOf(it) }?.let { return it }
        return database.groupSignedEventDao().getByChatRoomIdAndKind(roomId, InboxAnnouncement.RELAYS_KIND)
            .filter { it.publicKey.equals(roomId, ignoreCase = true) }
            .maxByOrNull { it.createdAt }
            ?.let { InboxAnnouncement.groupRelaysOf(it.kind, it.tags, it.content) }
            .orEmpty()
    }

    /** The key the group's newest signed 10044 names, or null if it has announced none. */
    suspend fun announcedKey(database: DedunDatabase, roomId: String): String? =
        database.groupSignedEventDao().getByChatRoomIdAndKind(roomId, InboxAnnouncement.KEY_KIND)
            .filter { it.publicKey.equals(roomId, ignoreCase = true) }
            .maxByOrNull { it.createdAt }
            ?.let { InboxAnnouncement.groupKeyOf(it.kind, it.tags, it.content) }
}
