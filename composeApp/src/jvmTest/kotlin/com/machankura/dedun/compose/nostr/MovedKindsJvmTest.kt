package com.machankura.dedun.compose.nostr

import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.managers.GroupLightningFixture
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Dedun's kinds that moved out of Giza's way, in K1 -- docs/giza-into-dedun.md, Phase K.
 *
 * Every number is pinned, because the old ones are on the wire and in every device's database
 * for good: a group-signed event's id hashes its kind. The rest says what K1 promises: a device
 * writes the legacy number and reads both, from what arrives, from what it stored, and from a
 * chronicle.
 */
class MovedKindsJvmTest {

    private val fixture = GroupLightningFixture()

    @AfterTest
    fun close() {
        MovedKinds.writesNew = false
        fixture.close()
    }

    @Test
    fun `the seventeen numbers, old and new`() {
        assertEquals(30390, DepositAttributionEvent.KIND)
        assertEquals(30391, CoinAssignmentEvent.KIND)
        assertEquals(30392, TransferRequestEvent.KIND)
        assertEquals(30393, MemberTransferEvent.KIND)
        assertEquals((30400..30412).toSet(), GroupWavelengthEvents.ALL)
        assertEquals(17, MovedKinds.LEGACY_TO_NEW.size)
        assertEquals(30340, MovedKinds.NEW_TO_LEGACY[DepositAttributionEvent.KIND])
        assertEquals(30343, MovedKinds.NEW_TO_LEGACY[MemberTransferEvent.KIND])
        assertEquals(30370, MovedKinds.NEW_TO_LEGACY[GroupWavelengthEvents.WALLET_STATEMENT])
        assertEquals(30382, MovedKinds.NEW_TO_LEGACY[GroupWavelengthEvents.STOPPED])
        // Each family keeps its order.
        MovedKinds.LEGACY_TO_NEW.forEach { (legacy, new) -> assertEquals(legacy, MovedKinds.NEW_TO_LEGACY[new]) }
    }

    @Test
    fun `no new number is one of Giza's, or one of Dedun's other kinds`() {
        val new = MovedKinds.NEW_TO_LEGACY.keys
        assertTrue(new.none { it in GizaProtocolEvents.ALL })
        val others = DkgRitualEvents.ALL + FrostSigningEvents.ALL + GroupLightningEvents.ALL + (30326..30339)
        assertTrue(new.none { it in others })
        assertTrue(new.none { it in MovedKinds.LEGACY_TO_NEW.keys })
    }

    @Test
    fun `K1 writes the legacy number, and K2 the new one`() {
        assertEquals(30340, MovedKinds.written(DepositAttributionEvent.KIND))
        assertEquals(30373, MovedKinds.written(GroupWavelengthEvents.SIGNING_REQUEST))
        assertEquals(GroupLightningEvents.SNAPSHOT, MovedKinds.written(GroupLightningEvents.SNAPSHOT), "a kind that never moved")
        MovedKinds.writesNew = true
        assertEquals(30390, MovedKinds.written(DepositAttributionEvent.KIND))
        assertEquals(30403, MovedKinds.written(GroupWavelengthEvents.SIGNING_REQUEST))
    }

    @Test
    fun `a reader takes either number`() {
        assertEquals(DepositAttributionEvent.KIND, MovedKinds.current(30340))
        assertEquals(DepositAttributionEvent.KIND, MovedKinds.current(30390))
        assertEquals(GroupWavelengthEvents.HIGH_WATER, MovedKinds.current(30377))
        assertTrue(MovedKinds.isKind(30382, GroupWavelengthEvents.STOPPED))
        assertFalse(MovedKinds.isKind(30344, GroupWavelengthEvents.STOPPED))
        // Giza's own refresh kinds past Dedun's four stay what they are.
        assertEquals(30344, MovedKinds.current(30344))
    }

    @Test
    fun `stored signed events are found under both numbers`() = runBlocking {
        val device = fixture.group()[0]
        fun row(id: Char, kind: Int, at: Long) = GroupSignedEvent(
            id = id.toString().repeat(64), chatRoomId = fixture.roomId, publicKey = fixture.roomId, kind = kind,
            tags = emptyArray(), content = "", signature = "f".repeat(128), createdAt = Instant.fromEpochSeconds(at),
        )
        device.db.groupSignedEventDao().upsert(row('a', 30340, 1))
        device.db.groupSignedEventDao().upsert(row('b', 30390, 2))
        device.db.groupSignedEventDao().upsert(row('c', 30341, 3))

        val attributions = device.db.groupSignedEventDao().getByChatRoomIdAndKind(fixture.roomId, DepositAttributionEvent.KIND)
        assertEquals(listOf(30340, 30390), attributions.map { it.kind }, "the legacy one, signed first, and the new one")
        assertEquals(2, device.db.groupSignedEventDao().observeCountByChatRoomIdAndKind(fixture.roomId, DepositAttributionEvent.KIND).first())
        assertEquals(3, device.db.groupSignedEventDao().observeCountOfKinds(listOf(DepositAttributionEvent.KIND, CoinAssignmentEvent.KIND)).first())
    }

    @Test
    fun `a chronicle carries both numbers, and applies them as one kind`() {
        val kinds = ChronicleEvent.KINDS
        assertTrue(kinds.isChroniclable(30340) && kinds.isChroniclable(30390))
        assertTrue(kinds.isChroniclable(30343) && kinds.isChroniclable(30393))
        assertEquals(kinds.applyRank(30390), kinds.applyRank(30340))
        assertEquals(kinds.applyRank(30391), kinds.applyRank(30341))
        assertFalse(kinds.isChroniclable(30344), "a Giza refresh certificate is never chronicled")
    }
}
