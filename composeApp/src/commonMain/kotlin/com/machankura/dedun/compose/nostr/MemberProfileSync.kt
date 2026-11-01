package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter

/**
 * Asking the relays who a room's members are, for the members this device cannot name.
 *
 * A pubkey gets a Profile row the moment it is first seen, and that row is a
 * placeholder: the "LOADING..." display name, stamped `GENESIS_AT` so it is
 * distinguishable from a profile actually read off a kind:0. What replaces it is a
 * kind:0, and every path that mints a placeholder asks for one -- exactly once, on
 * whichever relay that pubkey happened to arrive on.
 *
 * One ask is enough for a NIP-17 room only by accident. Opening one fetches each
 * member's kind:10050, and indexing any event authored by a pubkey we hold a
 * placeholder for re-queues the full profile sync (see `NostrDao.indexNostrEvent`),
 * so the name still arrives on the second bounce when the first ask came back empty.
 *
 * A Marmot group gets no such bounce. Its members never author anything this device
 * indexes -- a kind:445 is signed by a throwaway key and the sender's real identity
 * lives inside the MLS frame -- so the single ask made at the Welcome, or at the
 * commit that added them, is the only one there will ever be. Lose it to a relay that
 * did not hold their kind:0 yet and that member reads "LOADING..." for as long as the
 * room does.
 *
 * Hence asking again from what is on screen, rather than only from what just arrived.
 */
object MemberProfileSync {

    /** Tells these requests apart in the queue and in the logs. */
    const val PURPOSE = "member-profiles"

    /**
     * Authors per request. A relay is entitled to refuse a filter it considers too
     * large, and one over-long request being refused takes every member in it down
     * with it.
     */
    const val MAX_AUTHORS_PER_REQUEST = 100

    /**
     * The members of [rooms] this device still cannot put a name to.
     *
     * A missing row and a placeholder row mean the same thing here: nobody has read a
     * kind:0 for that pubkey. Ourselves excluded -- our own profile is not something a
     * relay teaches us.
     */
    fun unresolvedMemberPublicKeys(
        rooms: List<LocalChatRoom>,
        activeUserPublicKey: HexKey,
    ): List<HexKey> =
        rooms
            .flatMap { it.localParticipants }
            .filter { participant -> participant.profile?.isResolved() != true }
            .map { it.participant.participantPublicKey }
            .filter { it != activeUserPublicKey }
            .distinct()
            .sorted()

    /**
     * One kind:0 request per DM relay per chunk of [publicKeys], and nothing at all for
     * nobody.
     *
     * Level 0 on purpose. It is what marks a request as one somebody is waiting on, and
     * it is what lets an arriving kind:0 pull the rest of a member with it: indexing it
     * finds the placeholder still in place and queues that member's DM relay list and
     * key packages off the back of it.
     */
    fun requests(publicKeys: List<HexKey>): List<NegentropySynchronizeRequest> =
        publicKeys.chunked(MAX_AUTHORS_PER_REQUEST).flatMap { chunk ->
            val synchronizationFilter = SynchronizationFilter(
                kinds = arrayOf(MetadataEvent.KIND),
                authors = chunk.toTypedArray(),
            )

            Relays.DefaultDMRelayList.map { relay ->
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
}
