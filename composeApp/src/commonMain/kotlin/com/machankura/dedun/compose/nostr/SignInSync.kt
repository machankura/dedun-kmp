package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip28PublicChat.list.ChannelListEvent
import com.vitorpamplona.quartz.nip50Search.SearchRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.IndexerRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.RelayFeedsListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter

/**
 * The sync that finds an existing identity's profile: what it asks for, whom it asks,
 * and the one hop it follows.
 *
 * Three call sites want the same request -- the screen that first queues it, the
 * "try again" on the screen that waits for it, and the hop below -- so it is built here
 * once. See docs/nsec-sign-in.md, Phase 5.
 */
object SignInSync {
    const val PURPOSE = "sign-in"

    /** A request has left the queue but no relay has finished answering it. */
    val IN_FLIGHT_STATUSES = setOf("pending", "sent")

    /** Set once a relay has said everything it has -- EOSE, CLOSED or the bounded timeout. */
    const val STATUS_COMPLETE = "complete"

    /** The profile, the contacts, and every relay list the app reads. */
    val KINDS = arrayOf(
        MetadataEvent.KIND,
        ContactListEvent.KIND,
        AdvertisedRelayListEvent.KIND,
        ChatMessageRelayListEvent.KIND,
        SearchRelayListEvent.KIND,
        IndexerRelayListEvent.KIND,
        ChannelListEvent.KIND,
        RelayFeedsListEvent.KIND,
    )

    /**
     * Whom to ask first. Indexer relays exist to hold everyone's kinds 0, 3 and 10002 --
     * that is the whole of their purpose, and it is exactly the list above -- so they are
     * where an identity that has lived elsewhere for years will be found. Our own relay
     * stays in the set so a profile created here is found here. This used to be our relay
     * alone, which is the right answer only for a profile this app made.
     */
    val bootstrapRelays: List<NormalizedRelayUrl> =
        (Relays.DefaultIndexerRelayList + Relays.ephemeral).distinct()

    fun requests(
        unsignedNostrEventId: Long,
        publicKey: HexKey,
        relays: Collection<NormalizedRelayUrl>,
        level: Int,
    ): List<SynchronizeNostrEventRequest> = relays.map { relay ->
        SynchronizeNostrEventRequest(
            purpose = PURPOSE,
            synchronizationFilters = arrayOf(
                SynchronizationFilter(
                    authors = arrayOf(publicKey),
                    kinds = KINDS,
                )
            ),
            unsignedNostrEventId = unsignedNostrEventId,
            relayURL = relay.url,
            level = level,
        )
    }

    /**
     * The relays a kind 10002 says its author writes to -- `r` tags with no marker or a
     * `write` marker -- less the ones already asked. This is the outbox model doing what
     * it is for: the indexers know *where* the user publishes, and the user's own relays
     * are where the rest of their lists are authoritative. Bounded at one hop by the
     * caller, which only follows from a level-0 request; anything past that is the feed,
     * not the profile.
     */
    fun writeRelaysOf(tags: Array<Array<String>>, alreadyAsked: Collection<NormalizedRelayUrl>): List<NormalizedRelayUrl> =
        tags.asSequence()
            .filter { it.size >= 2 && it[0] == "r" && (it.getOrNull(2).isNullOrBlank() || it[2] == "write") }
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it[1]) }
            .distinct()
            .filterNot { it in alreadyAsked }
            .toList()
}
