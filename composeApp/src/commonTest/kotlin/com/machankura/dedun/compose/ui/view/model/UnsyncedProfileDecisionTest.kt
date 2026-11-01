package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalAccount
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.nostr.SignInSync
import com.machankura.dedun.compose.ui.view.state.UnsyncedProfileUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * When "searching" becomes "not found".
 *
 * A request is `pending` when queued, `sent` when its REQ is out, `processed` if an
 * event came back for it, and -- since Phase 5 -- `complete` if its relay finished with
 * nothing. Only the first two mean a relay has not answered yet, and only when none is
 * in either state, and still no kind 0 has arrived, may the screen say the search is
 * over. Anything else is the wrong message at the wrong time: too early and the user
 * sets up a second profile while the first is on its way; never and they sit under a
 * spinner for good, which is what the screen did before.
 */
class UnsyncedProfileDecisionTest {

    private val publicKey = "a".repeat(64)
    private val at = Instant.fromEpochSeconds(1_700_000_000)

    private val placeholder = UnsignedNostrEvent(
        id = 1,
        pubKey = publicKey,
        kind = 0,
        tags = emptyArray(),
        content = "{}",
        signedAt = GENESIS_AT,
        createdAt = at,
        updatedAt = at,
        savedAt = at,
    )

    private fun request(status: String, relay: String) = SynchronizeNostrEventRequest(
        id = "req-$relay",
        purpose = SignInSync.PURPOSE,
        status = status,
        relayURL = "wss://$relay.example",
        level = 0,
        synchronizationFilters = arrayOf(SynchronizationFilter(authors = arrayOf(publicKey), kinds = SignInSync.KINDS)),
        unsignedNostrEventId = 1,
        createdAt = at,
        updatedAt = at,
    )

    private fun account(
        vararg requests: SynchronizeNostrEventRequest,
        profile: Profile? = null,
        nostrEvent: NostrEvent? = null,
    ) = LocalAccount(
        unsignedNostrEvent = placeholder,
        nostrEvent = nostrEvent,
        profile = profile,
        broadcastNostrEventRequest = null,
        broadcastNostrEventReceipt = null,
        synchronizeNostrEventRequests = requests.toList(),
    )

    @Test
    fun `the answer flips only when the last relay has finished`() {
        val sequence = listOf(
            account(request("pending", "one"), request("pending", "two")),
            account(request("sent", "one"), request("pending", "two")),
            account(request("sent", "one"), request("sent", "two")),
            account(request("complete", "one"), request("sent", "two")),
            account(request("complete", "one"), request("complete", "two")),
        )

        val decisions = sequence.map { UnsyncedProfileViewModel.decide(it) }

        assertEquals(
            listOf(
                UnsyncedProfileUIState.Searching,
                UnsyncedProfileUIState.Searching,
                UnsyncedProfileUIState.Searching,
                UnsyncedProfileUIState.Searching,
                UnsyncedProfileUIState.NotFound(unsignedNostrEventId = 1),
            ),
            decisions,
        )
    }

    /** An event came back for a request -- a contact list, say -- but still no kind 0: the search is still over. */
    @Test
    fun `a relay that answered with something other than a profile still counts as finished`() {
        val decision = UnsyncedProfileViewModel.decide(account(request("processed", "one"), request("complete", "two")))

        assertEquals(UnsyncedProfileUIState.NotFound(unsignedNostrEventId = 1), decision)
    }

    @Test
    fun `a kind 0 that has arrived indexed or not is never not-found`() {
        val event = NostrEvent(
            id = "e".repeat(64),
            pubKey = publicKey,
            kind = 0,
            tags = emptyArray(),
            content = "{}",
            createdAt = at,
            sig = "f".repeat(128),
            unsignedNostrEventId = 1,
        )
        val profile = Profile(publicKey = publicKey, nostrEventId = event.id, createdAt = at, updatedAt = at, savedAt = at)

        assertEquals(UnsyncedProfileUIState.Searching, UnsyncedProfileViewModel.decide(account(request("complete", "one"), nostrEvent = event)))
        assertEquals(UnsyncedProfileUIState.Searching, UnsyncedProfileViewModel.decide(account(request("complete", "one"), profile = profile)))
    }

    @Test
    fun `no account or no request yet is still searching`() {
        assertEquals(UnsyncedProfileUIState.Searching, UnsyncedProfileViewModel.decide(null))
        assertEquals(UnsyncedProfileUIState.Searching, UnsyncedProfileViewModel.decide(account()))
    }
}
