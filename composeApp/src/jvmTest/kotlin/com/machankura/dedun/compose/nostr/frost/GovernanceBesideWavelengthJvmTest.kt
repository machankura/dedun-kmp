package com.machankura.dedun.compose.nostr.frost

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.managers.PromotionManager
import com.machankura.dedun.compose.nostr.MovedKinds
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOperatorStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.text.ProposedEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.Chain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * Governed rooms take 30370-30377 at Giza's numbers (docs/giza-into-dedun.md, Phase 8, D1), and
 * until K3 those are also the legacy numbers of eight of Dedun's Wavelength kinds, which Dedun
 * devices still write. Neither may be taken for the other: a Wavelength wallet statement read as
 * a change to the room is declined by every signer, and an admin's high-water mark read as a
 * step-down request has them demoted. `GovernanceEvents.isGovernance` tells them apart by shape.
 */
class GovernanceBesideWavelengthJvmTest {

    private val room = "1".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private val database: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun tearDown() = database.close()

    private fun event(kind: Int, tags: Array<Array<String>> = emptyArray(), content: String = "") =
        Event(id = "a".repeat(64), pubKey = bob, createdAt = 1_800_000_000, kind = kind, tags = tags, content = content, sig = "0".repeat(128))

    private val statementTags = GovernanceStatementEvent.assembleTags(room, epoch = 4, change = GovernanceChange(removes = setOf(carol)), proposer = bob)

    /** Every envelope and promotion step, as this build writes it. */
    private fun governance(): List<Event> {
        val statement = event(GovernanceStatementEvent.KIND, statementTags)
        return listOf(
            statement,
            event(OwnCommitEvent.KIND, OwnCommitEvent.assembleTags(room, 4), content = "{}"),
            event(GovernanceSupportEvent.KIND, GovernanceSupportEvent.assembleTags(room)),
            event(GovernanceCarrierEvent.KIND, GovernanceCarrierEvent.assembleTags(room), content = statement.toJson()),
            event(PromotionEvents.OFFER, PromotionEvents.offerTags(room, carol)),
            event(PromotionEvents.ACCEPT, PromotionEvents.acceptTags(room, "d".repeat(64), "02" + "e".repeat(64))),
            event(PromotionEvents.HANDOVER, PromotionEvents.handoverTags(room, carol), content = "{\"key\":\"k\"}"),
            event(PromotionEvents.STEP_DOWN, PromotionEvents.stepDownTags(room)),
        )
    }

    /**
     * The Wavelength traffic a Dedun device writes at 30370-30377 today: the two statements from
     * their own templates, and the wallet traffic and co-signing rounds as `queue` writes them --
     * no tags, and a JSON body.
     */
    private fun wavelength(): List<Event> {
        val wallet = GroupWavelengthWalletStatementEvent.template(
            GroupWavelengthWalletStatementEvent.Statement(
                chain = Chain.Regtest,
                wallet = 1,
                onchainAccount = 0,
                icebergKey = "02" + "f".repeat(64),
                ceremonyId = "c1",
                threshold = 2,
                participants = listOf(bob, carol),
                identity = "03" + "f".repeat(64),
                manifestHash = "9".repeat(64),
                arkOperatorKey = "02" + "8".repeat(64),
                policy = GroupWavelengthWalletStatementEvent.Policy(1, 2, 3, 4, 5, 6),
            ),
            createdAt = 1_800_000_000,
        )
        val operator = GroupWavelengthOperatorStatementEvent.template(
            GroupWavelengthOperatorStatementEvent.Statement(Chain.Regtest, 1, bob, 7),
            createdAt = 1_800_000_000,
        )
        val traffic = listOf(
            GroupWavelengthEvents.AUTH_SEED, GroupWavelengthEvents.SIGNING_REQUEST, GroupWavelengthEvents.ECHO,
            GroupWavelengthEvents.PROVISION, GroupWavelengthEvents.SIGNATURE_SHARES, GroupWavelengthEvents.HIGH_WATER,
        ).map { event(MovedKinds.NEW_TO_LEGACY.getValue(it), content = "{\"key\":\"k\",\"asking\":true,\"highest\":-1}") }
        return listOf(event(wallet.kind, wallet.tags, wallet.content), event(operator.kind, operator.tags, operator.content)) + traffic
    }

    @Test
    fun `the two families share 30370-30377 until K3`() {
        assertEquals((30370..30377).toList(), governance().map { it.kind })
        assertEquals((30370..30377).toList(), wavelength().map { it.kind }.sorted())
        assertEquals(GovernanceEvents.ALL, (30370..30377).toSet())
    }

    @Test
    fun `every governance envelope and promotion step is told as one`() {
        for (event in governance()) assertTrue(GovernanceEvents.isGovernance(event), "${event.kind} not told as governance")
    }

    @Test
    fun `no Wavelength event a Dedun device writes is taken for governance`() {
        for (event in wavelength()) assertFalse(GovernanceEvents.isGovernance(event), "Wavelength's ${event.kind} taken for governance")
    }

    @Test
    fun `a stored Wavelength wallet statement at 30370 still reads as one, and a statement as a change`() {
        val wallet = wavelength().first { it.kind == 30370 }
        assertNotNull(GroupWavelengthWalletStatementEvent.read(wallet.tags))
        assertNull(GovernanceStatementEvent.parse(wallet))
        assertEquals("A change to who is in this group", ProposedEvent.summarize(event(GovernanceStatementEvent.KIND, statementTags)).label)
        assertTrue(ProposedEvent.summarize(wallet).label != "A change to who is in this group")
        assertNull(GroupWavelengthWalletStatementEvent.read(statementTags))
    }

    private fun row(id: Char, kind: Int, content: String) = MarmotInnerEvent(
        id = id.toString().repeat(64),
        publicKey = bob,
        chatRoomId = room,
        kind = kind,
        createdAt = Instant.fromEpochSeconds(1_800_000_000),
        tags = arrayOf(arrayOf("d", room)),
        content = content,
    )

    @Test
    fun `a high-water mark at 30377 is never followed as a step-down, nor deleted`() = runBlocking {
        val highWater = row('1', 30377, "{\"key\":\"k\",\"asking\":true,\"highest\":-1}")
        val stepDown = row('2', PromotionEvents.STEP_DOWN, "")
        database.marmotInnerEventDao().upsert(highWater)
        database.marmotInnerEventDao().upsert(stepDown)

        // The room is unknown here, so a request is dropped as stale -- and the high-water mark,
        // which is no request, is left as the Wavelength wallet filed it.
        assertEquals(0, PromotionManager.followStepDowns(database, carol))
        assertNotNull(database.marmotInnerEventDao().getById(highWater.id))
        assertNull(database.marmotInnerEventDao().getById(stepDown.id))
    }
}
