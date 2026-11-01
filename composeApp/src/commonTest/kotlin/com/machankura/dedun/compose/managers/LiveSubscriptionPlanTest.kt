package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotRouting
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.GIFT_WRAP_SUB_ID
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.GROUP_SUB_ID_PREFIX
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.giftWrapFilter
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.groupChunks
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.groupFilter
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.groupIdsFrom
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.groupSubId
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.subscriptionIndex
import com.machankura.dedun.compose.managers.LiveSubscriptionManager.Companion.subscriptionKey
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.network.sockets.endsLiveSubscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LiveSubscriptionPlanTest {

    private val me = "a".repeat(64)

    // --- filters ----------------------------------------------------------------------

    /**
     * The one that will look like an oversight to whoever reads it next.
     *
     * NIP-59 randomizes a gift wrap's `created_at` into the past, and our own outbound path
     * stamps them with `TimeUtils.randomWithTwoDays()` — so a wrap published this second can
     * carry a timestamp two days old. A `since` anywhere near the present silently drops a
     * large share of genuinely new messages, and the symptom is "some DMs just never arrive":
     * no error, no log, nothing to grep for.
     */
    @Test
    fun `the gift wrap filter carries no since`() {
        assertNull(giftWrapFilter(me).since)
    }

    @Test
    fun `the gift wrap filter asks for wraps addressed to us`() {
        val filter = giftWrapFilter(me)

        assertEquals(listOf(GiftWrapEvent.KIND), filter.kinds)
        assertEquals(mapOf("p" to listOf(me)), filter.tags)
        assertEquals(100, filter.limit)
    }

    /**
     * `limit` is not a cap on the subscription — NIP-01 scopes it to the stored events a relay
     * sends before EOSE, explicitly not to the stream after it. It bounds what a reconnect
     * costs; it does not bound what arrives live.
     */
    @Test
    fun `the group filter asks for the given groups and also carries no since`() {
        val filter = groupFilter(listOf("group-a", "group-b"))

        assertEquals(listOf(GroupEvent.KIND), filter.kinds)
        assertEquals(mapOf("h" to listOf("group-a", "group-b")), filter.tags)
        assertEquals(500, filter.limit)
        assertNull(filter.since)
    }

    // --- which groups are watched -----------------------------------------------------

    @Test
    fun `only MLS rooms are watched`() {
        val rooms = listOf(
            room(id = "mls", mlsGroupState = "state"),
            // A NIP-17 room has no group state; its messages arrive as gift wraps instead, so
            // h-tag subscribing to it would ask for events that do not exist.
            room(id = "nip17", mlsGroupState = null),
        )

        assertEquals(listOf("mls"), groupIdsFrom(rooms))
    }

    /**
     * A room we left keeps its history locally and must stop pulling new messages. Getting
     * this wrong is not merely wasteful: it means still receiving from a group we are no
     * longer a member of.
     */
    @Test
    fun `rooms we have left or deleted are not watched`() {
        val rooms = listOf(
            room(id = "here", mlsGroupState = "state"),
            room(id = "left", mlsGroupState = "state", leftGroupAt = Instant.fromEpochSeconds(10)),
            room(id = "gone", mlsGroupState = "state", deletedAt = Instant.fromEpochSeconds(10)),
        )

        assertEquals(listOf("here"), groupIdsFrom(rooms))
    }

    /**
     * Sorted, so the same membership in a different row order is the same value and the
     * `distinctUntilChanged` upstream of the reconcile does not re-send a filter that has not
     * actually changed.
     */
    @Test
    fun `group ids come out sorted`() {
        val rooms = listOf("c", "a", "b").map { room(id = it, mlsGroupState = "state") }

        assertEquals(listOf("a", "b", "c"), groupIdsFrom(rooms))
    }

    /**
     * The filter names addresses, not rooms. A current-profile room is addressed by a
     * routing id apart from its own, and every address it has had is watched, so a
     * message published to a prior one inside the retention window is not missed. A
     * room with no routing recorded is its own one address. See docs/marmot-v2.md,
     * Phase 4.
     */
    @Test
    fun `rooms are watched by their routing addresses`() {
        val routed = room(id = "room", mlsGroupState = "state").copy(
            routings = listOf(
                MarmotRouting(routingId = "addr-old", chatRoomId = "room", activeFromEpoch = 0, retiredAtEpoch = 3),
                MarmotRouting(routingId = "addr-new", chatRoomId = "room", activeFromEpoch = 3),
            ),
        )
        val unrouted = room(id = "legacy", mlsGroupState = "state")

        assertEquals(listOf("addr-new", "addr-old", "legacy"), groupIdsFrom(listOf(routed, unrouted)))
        assertEquals("addr-new", routed.routingId)
        assertEquals("legacy", unrouted.routingId)
    }

    // --- chunking and subscription ids -------------------------------------------------

    @Test
    fun `groups are chunked to bound the size of one filter's tag array`() {
        val chunks = groupChunks((1..250).map { "group-$it" })

        assertEquals(3, chunks.size)
        assertEquals(listOf(100, 100, 50), chunks.map { it.size })
    }

    @Test
    fun `no groups means no subscriptions`() {
        assertTrue(groupChunks(emptyList()).isEmpty())
    }

    @Test
    fun `a subscription key round-trips back to its chunk index`() {
        val key = subscriptionKey(relayUrl = "wss://relay.example.com", subId = groupSubId(3))

        assertEquals("wss://relay.example.com|${GROUP_SUB_ID_PREFIX}3", key)
        assertEquals(3, subscriptionIndex(key))
    }

    /**
     * `subscriptionIndex` decides which subscriptions get closed when membership shrinks —
     * anything at or past the new chunk count goes. Unparseable therefore has to read as "past
     * the end", so a key nobody recognises is closed rather than kept open forever.
     */
    @Test
    fun `an unrecognisable key sorts past the end so it gets closed`() {
        assertEquals(Int.MAX_VALUE, subscriptionIndex("wss://relay.example.com|$GIFT_WRAP_SUB_ID"))
        assertEquals(Int.MAX_VALUE, subscriptionIndex("nonsense"))
    }

    // --- what ends a live subscription -------------------------------------------------

    @Test
    fun `EOSE does not end a live subscription CLOSED does`() {
        assertFalse(NostrIncomingMessage.EoseMessage(subscriptionId = "s").endsLiveSubscription())
        assertFalse(NostrIncomingMessage.EventMessage(subscriptionId = "s").endsLiveSubscription())
        assertFalse(NostrIncomingMessage.NoticeMessage(message = "hi").endsLiveSubscription())

        assertTrue(
            NostrIncomingMessage.ClosedMessage(subscriptionId = "s", message = "rate-limited")
                .endsLiveSubscription()
        )
    }

    private fun room(
        id: String,
        mlsGroupState: String?,
        leftGroupAt: Instant? = null,
        deletedAt: Instant? = null,
    ) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = id,
            userPublicKey = me,
            subject = null,
            description = null,
            mlsGroupState = mlsGroupState,
            leftGroupAt = leftGroupAt,
            deletedAt = deletedAt,
        )
    )
}
