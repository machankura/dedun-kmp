package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GovernanceProposal
import com.machankura.dedun.compose.database.model.GovernanceProposalStage
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.nostr.frost.PromotionEvents

/**
 * Which promotion offers are waiting on whom. docs/member-admin-promotion.md,
 * Phase 8: an offer waits on the member until they accept it, and an accepted
 * offer waits on the device that made it until it is proposed -- once.
 */
class PromotionOffersJvmTest {

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val roomId = "1".repeat(64)
    private val alice = KeyPair().pubKey.toHexKey()
    private val erin = KeyPair().pubKey.toHexKey()

    private suspend fun room() {
        MarmotStandIn.seedProfile(db, alice, "Alice")
        MarmotStandIn.seedProfile(db, erin, "Erin")
        db.chatRoomDao().upsert(ChatRoom(id = roomId, userPublicKey = erin, subject = "&keyed", description = null, mlsGroupState = null))
    }

    private suspend fun said(id: Char, by: String, kind: Int, tags: Array<Array<String>>, at: Long) =
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(id = id.toString().repeat(64), publicKey = by, kind = kind, tags = tags, content = "", chatRoomId = roomId, createdAt = Instant.fromEpochSeconds(at))
        )

    @Test
    fun `an offer waits on the member until they accept it`() = runBlocking {
        room()
        said('a', alice, PromotionEvents.OFFER, PromotionEvents.offerTags(roomId, erin), 1)

        assertEquals(PromotionManager.Offer("a".repeat(64), alice), PromotionManager.pendingOffer(db, roomId, erin))
        assertNull(PromotionManager.pendingOffer(db, roomId, alice), "an offer to Erin was shown to Alice")

        said('b', erin, PromotionEvents.ACCEPT, PromotionEvents.acceptTags(roomId, "a".repeat(64), "02" + "c".repeat(64)), 2)
        assertNull(PromotionManager.pendingOffer(db, roomId, erin), "an accepted offer still asked")
    }

    /** Who the member rows say has already been asked. docs/member-row-actions.md, Phase 5. */
    @Test
    fun `whoever was offered admin and has not answered has already been asked`() = runBlocking {
        room()
        assertEquals(emptySet(), PromotionManager.unansweredOfferTargets(db, roomId))

        said('a', alice, PromotionEvents.OFFER, PromotionEvents.offerTags(roomId, erin), 1)
        assertEquals(setOf(erin), PromotionManager.unansweredOfferTargets(db, roomId))

        // An acceptance by somebody else answers nothing.
        said('c', alice, PromotionEvents.ACCEPT, PromotionEvents.acceptTags(roomId, "a".repeat(64), "02" + "d".repeat(64)), 2)
        assertEquals(setOf(erin), PromotionManager.unansweredOfferTargets(db, roomId), "Alice answered Erin's offer")

        said('b', erin, PromotionEvents.ACCEPT, PromotionEvents.acceptTags(roomId, "a".repeat(64), "02" + "c".repeat(64)), 3)
        assertEquals(emptySet(), PromotionManager.unansweredOfferTargets(db, roomId), "an accepted offer still waited")
    }

    @Test
    fun `an accepted offer is the offerer's to propose, once`() = runBlocking {
        room()
        said('a', alice, PromotionEvents.OFFER, PromotionEvents.offerTags(roomId, erin), 1)
        assertTrue(PromotionManager.acceptedOffers(db, alice).isEmpty(), "proposed before it was accepted")

        // Accepted by somebody else first: not an acceptance.
        said('c', alice, PromotionEvents.ACCEPT, PromotionEvents.acceptTags(roomId, "a".repeat(64), "02" + "c".repeat(64)), 2)
        assertTrue(PromotionManager.acceptedOffers(db, alice).isEmpty(), "the offerer accepted on the member's behalf")

        said('b', erin, PromotionEvents.ACCEPT, PromotionEvents.acceptTags(roomId, "a".repeat(64), "02" + "c".repeat(64)), 3)
        assertEquals(listOf("a".repeat(64)), PromotionManager.acceptedOffers(db, alice).map { it.first.id })
        assertTrue(PromotionManager.acceptedOffers(db, erin).isEmpty(), "the member was asked to propose their own promotion")

        db.governanceProposalDao().upsert(
            GovernanceProposal(
                id = "d".repeat(64), chatRoomId = roomId, routingId = roomId, epoch = 1, frostSigningSessionId = "s",
                commitEventId = "e".repeat(64), commitEventJson = "{}", priorStateHex = "", pendingStateHex = "",
                promotionOfferId = "a".repeat(64),
            )
        )
        assertTrue(PromotionManager.acceptedOffers(db, alice).isEmpty(), "proposed twice")

        // A proposal the group moved past frees the offer to be proposed again.
        db.governanceProposalDao().upsert(db.governanceProposalDao().getById("d".repeat(64))!!.copy(stage = GovernanceProposalStage.STALE))
        assertEquals(1, PromotionManager.acceptedOffers(db, alice).size)
    }
}
