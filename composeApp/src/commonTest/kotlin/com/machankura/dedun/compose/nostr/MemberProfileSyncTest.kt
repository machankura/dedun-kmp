package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class MemberProfileSyncTest {

    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)

    // --- who gets asked about -----------------------------------------------------------

    /**
     * The whole point. A member with no row at all and a member holding the "LOADING..."
     * placeholder are in the same position — nobody has read a kind:0 for either — and the
     * placeholder is the one that looks resolved if you only check for null. That is the
     * check every mint site makes, which is why a Marmot group's members stay "LOADING..."
     * once their single ask comes back empty.
     */
    @Test
    fun `a placeholder counts as unknown the same as no row at all`() {
        val rooms = listOf(
            room(
                members = listOf(
                    member(me, resolved(me)),
                    member(alice, placeholder(alice)),
                    member(bob, null),
                )
            )
        )

        assertEquals(listOf(alice, bob).sorted(), MemberProfileSync.unresolvedMemberPublicKeys(rooms, me))
    }

    @Test
    fun `a member whose profile has arrived is not asked about again`() {
        val rooms = listOf(room(members = listOf(member(me, resolved(me)), member(alice, resolved(alice)))))

        assertTrue(MemberProfileSync.unresolvedMemberPublicKeys(rooms, me).isEmpty())
    }

    /**
     * Ours is the one profile a relay cannot teach us, and on a fresh device it is a
     * placeholder like any other — so without this every foreground would ask the relays
     * about us.
     */
    @Test
    fun `we are never asked about`() {
        val rooms = listOf(room(members = listOf(member(me, placeholder(me)))))

        assertTrue(MemberProfileSync.unresolvedMemberPublicKeys(rooms, me).isEmpty())
    }

    /** Someone in three groups is still one kind:0. */
    @Test
    fun `a member in several rooms is asked about once`() {
        val rooms = listOf(
            room(id = "one", members = listOf(member(alice, placeholder(alice)))),
            room(id = "two", members = listOf(member(alice, null))),
        )

        assertEquals(listOf(alice), MemberProfileSync.unresolvedMemberPublicKeys(rooms, me))
    }

    // --- what gets asked ----------------------------------------------------------------

    @Test
    fun `nobody to ask about means no requests`() {
        assertTrue(MemberProfileSync.requests(emptyList()).isEmpty())
    }

    @Test
    fun `one request per relay asking those authors for kind zero`() {
        val requests = MemberProfileSync.requests(listOf(alice, bob))

        assertEquals(Relays.DefaultDMRelayList.size, requests.size)
        assertEquals(Relays.DefaultDMRelayList.map { it.url }.sorted(), requests.map { it.relayURL }.sorted())

        requests.forEach { request ->
            assertEquals(MemberProfileSync.PURPOSE, request.purpose)
            // Level 0 is what marks a request as one somebody is waiting on, and what lets
            // the arriving kind:0 pull the rest of the member in behind it.
            assertEquals(0, request.level)
            assertEquals(listOf(MetadataEvent.KIND), request.synchronizationFilter.kinds?.toList())
            assertEquals(listOf(alice, bob), request.synchronizationFilter.authors?.toList())
        }
    }

    /** One over-long filter a relay refuses would take every member in it down with it. */
    @Test
    fun `authors are chunked so one refused filter cannot lose everybody`() {
        val many = (1..250).map { it.toString().padStart(64, '0') }

        val chunkSizes = MemberProfileSync.requests(many)
            .filter { it.relayURL == Relays.DefaultDMRelayList.first().url }
            .map { it.synchronizationFilter.authors?.size }

        assertEquals(listOf(100, 100, 50), chunkSizes)
    }

    // --- fixtures -----------------------------------------------------------------------

    private fun room(
        id: String = "room",
        members: List<LocalParticipant>,
    ) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = id,
            userPublicKey = me,
            subject = null,
            description = null,
            mlsGroupState = "state",
        ),
        localParticipants = members,
    )

    private fun member(publicKey: HexKey, profile: Profile?) = LocalParticipant(
        participant = Participant(
            participantPublicKey = publicKey,
            chatRoomId = "room",
            relayHint = null,
        ),
        profile = profile,
    )

    /** What every mint site writes: the "LOADING..." name, stamped [GENESIS_AT]. */
    private fun placeholder(publicKey: HexKey) = Profile(
        publicKey = publicKey,
        displayName = "LOADING...",
        nostrEventId = "event",
        createdAt = GENESIS_AT,
    )

    private fun resolved(publicKey: HexKey) = Profile(
        publicKey = publicKey,
        displayName = "Somebody",
        nostrEventId = "event",
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
    )
}
