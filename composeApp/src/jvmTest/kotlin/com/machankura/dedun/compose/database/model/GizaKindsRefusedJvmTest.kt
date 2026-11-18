package com.machankura.dedun.compose.database.model

import com.machankura.dedun.compose.nostr.MovedKinds
import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.GizaProtocolEvents
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOperatorStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.wallet.wavelength.group.ArkWire
import com.vitorpamplona.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Giza's share refresh and governed-room events, arriving in a room a Dedun device shares,
 * are refused by every Dedun reader that owns the same number: docs/giza-into-dedun.md,
 * Phase 1.
 *
 * Until K3 (Phase K), 30340-30343 are the legacy numbers of its deposit attribution, coin
 * assignment and the two transfer kinds, and 30370-30377 its Wavelength wallet's. A Giza or
 * Bennu device sends refresh rounds at the first numbers and governance envelopes at the
 * second. A reader that believed one would put a line in a member's ledger, so each is fed
 * the event as Giza writes it -- its tag names copied from Giza's `ShareRefreshEvents`,
 * `GovernanceStatementEvent` and `GovernanceEvents` at `d0437d6c` -- and has to say no. The
 * transcript has to show nothing at all: neither a line nor a raw-JSON bubble.
 *
 * K3 turns this round: from then on these numbers are Giza's meaning on every Dedun device.
 */
class GizaKindsRefusedJvmTest {

    private val database: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = database.close()

    private val self = KeyPair()
    private val giza = NostrSignerSync(KeyPair())
    private val chatRoomId = "639f".repeat(16)
    private var next = 0

    private fun hex(seed: Int) = "%064x".format(seed)

    /** A refresh round as Giza's `ShareRefreshEvents.assembleTags` writes it. */
    private fun refresh(kind: Kind): Event = giza.sign<Event>(
        createdAt = 1_800_000_000,
        kind = kind,
        tags = arrayOf(
            arrayOf("refresh_session", hex(7)),
            arrayOf("frost_key", hex(8)),
            arrayOf("frost_generation", "0"),
            arrayOf("refresh_recipients", "1,2,3"),
        ),
        // A round's payload is hex, as a ChillDKG message's is.
        content = "024f99efa68cafafd766eec95ad940cd096a5f1a7b42d14132121ad7d2dc310d03",
    )

    /** A kind:445 an envelope carries: Giza puts the complete event in the content. */
    private val innerCommit: String = giza.sign<Event>(
        createdAt = 1_800_000_000,
        kind = GroupEvent.KIND,
        tags = arrayOf(arrayOf("h", chatRoomId)),
        content = "Y29tbWl0",
    ).toJson()

    /** The group-signed statement, an own commit, a carrier, and the promotion messages. */
    private fun governance(kind: Kind): Event = giza.sign<Event>(
        createdAt = 1_800_000_000,
        kind = kind,
        tags = when (kind) {
            30370 -> arrayOf(
                arrayOf("d", chatRoomId),
                arrayOf("epoch", "4"),
                arrayOf("add", hex(9)),
                arrayOf("proposer", giza.pubKey),
            )
            30371 -> arrayOf(arrayOf("d", chatRoomId), arrayOf("epoch", "4"))
            30373 -> arrayOf(arrayOf("d", chatRoomId))
            else -> arrayOf(arrayOf("d", chatRoomId), arrayOf("target", hex(10)), arrayOf("offer", hex(11)), arrayOf("host", hex(12)))
        },
        content = when (kind) {
            30370, 30371 -> innerCommit
            30373 -> giza.sign<Event>(createdAt = 1_800_000_000, kind = 30370, tags = arrayOf(arrayOf("d", chatRoomId)), content = innerCommit).toJson()
            else -> ""
        },
    )

    /** A kind:445 this device has stored, carrying [inner], put through the transcript. */
    private suspend fun arrive(inner: Event): ChatMessage? {
        val groupEventId = hex(1000 + next++)
        database.nostrEventDao().upsert(
            NostrEvent(id = groupEventId, pubKey = giza.pubKey, kind = GroupEvent.KIND, tags = arrayOf(arrayOf("h", chatRoomId)), content = "ciphertext", sig = "0".repeat(128))
        )
        database.marmotGroupEventDao().upsert(
            MarmotGroupEvent(
                id = groupEventId,
                userPublicKey = self.pubKey.toHexKey(),
                publicKey = giza.pubKey,
                chatRoomId = chatRoomId,
                signature = "0".repeat(128),
                encryptedContent = "ciphertext",
                expiresAt = null,
                createdAt = Instant.fromEpochSeconds(1),
            )
        )
        return ChatMessage.fromGroupEventResult(
            database = database,
            activeKeyPair = self,
            groupEvent = GroupEvent(id = groupEventId, pubKey = giza.pubKey, createdAt = 1, tags = arrayOf(arrayOf("h", chatRoomId)), content = "ciphertext", sig = "0".repeat(128)),
            groupEventResult = GroupEventResult.ApplicationMessage(groupId = chatRoomId, innerEventJson = inner.toJson(), senderLeafIndex = 1, epoch = 1),
            senderIdentity = giza.pubKey,
        )
    }

    @Test
    fun `the numbers this test covers are the ones the two apps share`() {
        // Dedun's kinds have moved (Phase K, K1), but these are still their legacy numbers, read on every device.
        assertEquals(30340, MovedKinds.NEW_TO_LEGACY[DepositAttributionEvent.KIND])
        assertEquals(30341, MovedKinds.NEW_TO_LEGACY[CoinAssignmentEvent.KIND])
        assertEquals(30342, MovedKinds.NEW_TO_LEGACY[TransferRequestEvent.KIND])
        assertEquals(30343, MovedKinds.NEW_TO_LEGACY[MemberTransferEvent.KIND])
        assertEquals((30370..30377).toList(), (GroupWavelengthEvents.WALLET_STATEMENT..GroupWavelengthEvents.HIGH_WATER).map { MovedKinds.NEW_TO_LEGACY[it] })
        assertTrue((30340..30343).all { it in GizaProtocolEvents.ALL })
        assertTrue((30370..30377).all { it in GizaProtocolEvents.ALL })
    }

    @Test
    fun `a refresh round is no deposit attribution, coin assignment or transfer`() {
        assertNull(DepositAttributionEvent.read(refresh(30340)))
        assertNull(DepositAttributionEvent.verify(refresh(30340), chatRoomId))
        assertNull(CoinAssignmentEvent.read(refresh(30341)))
        assertNull(CoinAssignmentEvent.verify(refresh(30341), chatRoomId))
        assertNull(TransferRequestEvent.carried(refresh(30342)))
        assertNull(MemberTransferEvent.read(refresh(30343)))
        assertNull(MemberTransferEvent.verify(refresh(30343), chatRoomId))
    }

    @Test
    fun `a governance envelope is no Wavelength wallet or operator statement`() {
        assertNull(GroupWavelengthWalletStatementEvent.read(governance(30370).tags))
        assertNull(GroupWavelengthOperatorStatementEvent.read(governance(30371).tags))
    }

    @Test
    fun `a carrier and the promotion messages are no Wavelength co-signing message`() {
        assertFails { CommonJson.decodeFromString(ArkWire.SigningBatch.serializer(), governance(30373).content) }
        assertFails { CommonJson.decodeFromString(ArkWire.EchoBatch.serializer(), governance(30374).content) }
        assertFails { CommonJson.decodeFromString(ArkWire.Provision.serializer(), governance(30375).content) }
        assertFails { CommonJson.decodeFromString(ArkWire.ShareBatch.serializer(), governance(30376).content) }
        assertFails { CommonJson.decodeFromString(ArkWire.HighWater.serializer(), governance(30377).content) }
    }

    @Test
    fun `none of Giza's events leaves a line or a bubble in the transcript`() = runBlocking {
        for (kind in 30340..30349) {
            assertNull(arrive(refresh(kind)), "refresh kind $kind reached the transcript")
        }
        for (kind in (30370..30377) - 30372) {
            assertNull(arrive(governance(kind)), "governance kind $kind reached the transcript")
        }
    }
}
