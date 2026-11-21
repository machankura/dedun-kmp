package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.inbox.InboxAnnouncement

/**
 * What follows an inbox key ceremony on the device that coordinated it (docs/group-inbox.md,
 * D5 and D6): asking the group to announce the key it made.
 *
 * The first time, that is a batch of two -- the 10044 naming the key and the 10050 naming where
 * mail should arrive -- and the group's members are asked, since signing it is the group
 * deciding to have an inbox at all. After that, a rotation's 10044 alone, which every admin's
 * device can vouch for and signs unasked ([InboxRefusal.vouchesFor]).
 */
object InboxFollowThrough {
    private val logger = Logger.withTag("InboxFollowThrough")

    /** Called once, on every participant's device, when [ceremony] has made its key. */
    suspend fun onKeyMade(database: DedunDatabase, localChatRoom: LocalChatRoom, ceremony: InboxKeyCeremony) {
        if (!ceremony.isCoordinator()) return
        val key = database.inboxKeyDao().getKey(ceremony.chatRoomId, ceremony.userPublicKey, ceremony.generation) ?: return
        val now = Clock.System.now().epochSeconds

        val events = buildList<EventTemplate<Event>> {
            add(InboxAnnouncement.keyTemplate(key.publicKey, now))
            if (InboxAnnouncements.announcedKey(database, ceremony.chatRoomId) == null) {
                val chosen = ceremony.announceRelays.orEmpty().split(",")
                    .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.trim()) }
                add(InboxAnnouncement.relaysTemplate(chosen.ifEmpty { Relays.DefaultDMRelayList }, now))
            }
        }

        try {
            val session = FrostSigningManager.proposeSigningBatch(database, localChatRoom, ceremony.userPublicKey, events = events)
            logger.i("Asked the group to announce inbox key generation ${ceremony.generation} in session ${session.id.take(8)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The key is made and kept either way; an admin can ask again from the inbox's
            // settings. Not a ceremony failure, so not said as one.
            logger.e("Made inbox key generation ${ceremony.generation} but could not ask the group to announce it", e)
        }
    }
}
