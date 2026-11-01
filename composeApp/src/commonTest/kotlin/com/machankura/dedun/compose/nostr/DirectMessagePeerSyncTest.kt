package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a direct message with one person asks the relays for, and whom it asks.
 *
 * Two request lists because two sets of relays hold two different things; the test
 * pins each list's shape and the one relay the two sets share, so that a change to
 * either relay set is noticed here rather than as a chat that never starts.
 */
class DirectMessagePeerSyncTest {

    private val alice = "b".repeat(64)

    // --- the DM relays: everything a chat needs ----------------------------------------

    @Test
    fun `one request per DM relay asking for the three kinds a chat needs`() {
        val requests = DirectMessagePeerSync.requests(alice)

        assertEquals(Relays.DefaultDMRelayList.size, requests.size)
        assertEquals(Relays.DefaultDMRelayList.map { it.url }.sorted(), requests.map { it.relayURL }.sorted())

        requests.forEach { request ->
            assertEquals(DirectMessagePeerSync.PURPOSE, request.purpose)
            // Level 0: somebody is waiting on this one.
            assertEquals(0, request.level)
            assertEquals(
                listOf(MetadataEvent.KIND, KeyPackageEvent.KIND, ChatMessageRelayListEvent.KIND, AdvertisedRelayListEvent.KIND),
                request.synchronizationFilter.kinds?.toList(),
            )
            assertEquals(listOf(alice), request.synchronizationFilter.authors?.toList())
            // `limit = 1` across three kinds returned the kind 0 alone on the REQ fallback,
            // and the key package never arrived.
            assertNull(request.synchronizationFilter.limit)
        }
    }

    /** `computeId` buckets by minute, and the id is what lets a retry re-arm the row rather than add one. */
    @Test
    fun `the id is the one computeId gives the relay and the filter`() {
        DirectMessagePeerSync.requests(alice).forEach { request ->
            assertEquals(
                NegentropySynchronizeRequest.computeId(request.relayURL, request.synchronizationFilter),
                request.id,
            )
        }
    }

    // --- the indexers: the kind 0 alone -------------------------------------------------

    @Test
    fun `one request per bootstrap relay asking for the kind 0 alone`() {
        val requests = DirectMessagePeerSync.profileRequests(alice)

        assertEquals(SignInSync.bootstrapRelays.size, requests.size)
        assertEquals(SignInSync.bootstrapRelays.map { it.url }.sorted(), requests.map { it.relayURL }.sorted())

        requests.forEach { request ->
            assertEquals(DirectMessagePeerSync.PROFILE_PURPOSE, request.purpose)
            assertEquals(0, request.level)
            // A peer has no unsigned event of ours to hang off, unlike a sign-in.
            assertNull(request.unsignedNostrEventId)

            val filter = request.synchronizationFilters.single()
            assertEquals(listOf(MetadataEvent.KIND), filter.kinds?.toList())
            assertEquals(listOf(alice), filter.authors?.toList())
        }
    }

    /**
     * Our own relay is the one place both lists ask, on purpose: a profile created here
     * is found here, and a chat can only be started here. Anything else the two share
     * is a relay set that has drifted.
     */
    @Test
    fun `the two relay sets share our own relay and nothing else`() {
        val dmRelays = DirectMessagePeerSync.requests(alice).map { it.relayURL }.toSet()
        val profileRelays = DirectMessagePeerSync.profileRequests(alice).map { it.relayURL }.toSet()

        assertEquals(setOf(Relays.ephemeral.url), dmRelays intersect profileRelays)
    }
}
