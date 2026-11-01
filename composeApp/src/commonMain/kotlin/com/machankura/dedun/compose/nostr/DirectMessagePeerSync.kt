package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter

/**
 * Asking the relays for what a direct message with one person needs.
 *
 * Two asks, because two sets of relays hold two different things. The DM relays
 * hold the key package and the DM relay list, and the kind 0 of anyone who has
 * published one here; the indexer relays hold everyone's kind 0 and nothing else a
 * chat needs. A three-kind filter to the indexers comes back with the kind 0 alone;
 * a kind-0-only filter to our relay finds a person who has never opened Bennu
 * nowhere.
 *
 * Both at level 0, for the reason `MemberProfileSync` gives: it marks a request as
 * one somebody is waiting on, and a kind 0 arriving at level 0 over a placeholder
 * queues the rest of that person's profile kinds off the back of it
 * (`NostrDao.indexNostrEvent`). From an indexer that follow-up asks the indexer for
 * key packages it does not hold, which is harmless and is why [requests] is queued
 * explicitly rather than relied on.
 *
 * See docs/npub-profile-preview.md.
 */
object DirectMessagePeerSync {
    /** The purpose the chat screen has queued under since it was written; kept so nothing reading the queue by purpose changes. */
    const val PURPOSE = "initiate-chat"

    /** The kind 0 on its own, for the preview that shows a person before a chat is started. */
    const val PROFILE_PURPOSE = "profile-preview"

    /**
     * What a chat needs: the peer's metadata, their MLS key package, their DM relay
     * list -- and their NIP-65 list, which is where a current-profile peer publishes
     * their key packages (`transports/nostr.md`: there is no dedicated KeyPackage
     * relay list), so a later fetch can look where they actually write.
     *
     * No `limit` on the filter built from these: it is ignored on the negentropy path
     * but honoured on the REQ fallback, where `limit = 1` returned a single newest
     * event across all the kinds -- almost always the kind 0 -- so the key package
     * never arrived.
     */
    val KINDS = arrayOf(
        MetadataEvent.KIND,
        KeyPackageEvent.KIND,
        ChatMessageRelayListEvent.KIND,
        AdvertisedRelayListEvent.KIND,
    )

    /**
     * One negentropy request per DM relay for the three kinds, authored by
     * [peerPublicKey]. The id is `computeId`'s, bucketed by minute, so a retry inside
     * the same minute re-arms the row rather than adding one.
     */
    fun requests(peerPublicKey: HexKey): List<NegentropySynchronizeRequest> {
        val synchronizationFilter = SynchronizationFilter(
            kinds = KINDS,
            authors = arrayOf(peerPublicKey),
        )

        return Relays.DefaultDMRelayList.map { relay ->
            NegentropySynchronizeRequest(
                id = NegentropySynchronizeRequest.computeId(
                    relayURL = relay.url,
                    synchronizationFilter = synchronizationFilter,
                ),
                purpose = PURPOSE,
                synchronizationFilter = synchronizationFilter,
                relayURL = relay.url,
                level = 0,
            )
        }
    }

    /**
     * The kind 0 alone, as a REQ to `SignInSync.bootstrapRelays` -- the indexers plus
     * our own -- which is where a key pasted from elsewhere has lived.
     */
    fun profileRequests(peerPublicKey: HexKey): List<SynchronizeNostrEventRequest> {
        val synchronizationFilter = SynchronizationFilter(
            kinds = arrayOf(MetadataEvent.KIND),
            authors = arrayOf(peerPublicKey),
        )

        return SignInSync.bootstrapRelays.map { relay ->
            SynchronizeNostrEventRequest(
                purpose = PROFILE_PURPOSE,
                synchronizationFilters = arrayOf(synchronizationFilter),
                relayURL = relay.url,
                level = 0,
            )
        }
    }
}
