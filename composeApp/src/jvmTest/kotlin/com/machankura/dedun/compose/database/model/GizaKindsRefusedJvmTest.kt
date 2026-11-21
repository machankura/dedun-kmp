package com.machankura.dedun.compose.database.model

import com.machankura.dedun.compose.nostr.MovedKinds
import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.GizaProtocolEvents
import com.machankura.dedun.compose.nostr.frost.GovernanceEvents
import com.machankura.dedun.compose.nostr.inbox.InboxKeyRitualEvents
import com.machankura.dedun.compose.nostr.frost.ShareRefreshEvents
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthDepositAnswer
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthStopped
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
 *
 * Giza went on to take 30379-30416 for confederations, a group's direct messages and its inbox
 * (docs/giza-second-batch.md, Phase 4), and Dedun's own numbers there moved to 30420-30442. The
 * last section feeds those in as Giza writes them at a5a802c5.
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
        assertTrue((30340..30343).all { ShareRefreshEvents.isShareRefreshKind(it) })
        assertTrue((30370..30377).all { it in GovernanceEvents.ALL })
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

    // --- Giza after d0437d6c: docs/giza-second-batch.md, Phase 4 ---

    /**
     * A confederation's carried envelope as Giza's `ConfederationRitualManager.writeIntoRoom` and
     * `ConfederationSigningManager` write it at a5a802c5: the proposal and the room named in
     * tags, and a JSON array of its author's signed events as the content.
     */
    private fun carried(kind: Kind): Event = giza.sign<Event>(
        createdAt = 1_800_000_000,
        kind = kind,
        tags = arrayOf(arrayOf("confederation_proposal_id", hex(13)), arrayOf("room", chatRoomId)),
        content = "[" + giza.sign<Event>(createdAt = 1_800_000_000, kind = 30381, tags = arrayOf(arrayOf("confederation_proposal_id", hex(13))), content = "{\"roles\":{}}").toJson() + "]",
    )

    /** A round of a group's direct messages or of its inbox's key ceremony: Giza's session tag and a hex payload. */
    private fun round(kind: Kind): Event = giza.sign<Event>(
        createdAt = 1_800_000_000,
        kind = kind,
        tags = arrayOf(arrayOf("d", chatRoomId), arrayOf("session", hex(14))),
        content = "024f99efa68cafafd766eec95ad940cd096a5f1a7b42d14132121ad7d2dc310d03",
    )

    @Test
    fun `Giza's new kinds are reserved, and none of them is one of Dedun's`() {
        val dedun = setOf(
            DepositAttributionEvent.KIND, CoinAssignmentEvent.KIND, TransferRequestEvent.KIND, MemberTransferEvent.KIND,
        ) + GroupWavelengthEvents.ALL + (30424..30429)
        val giza = GizaProtocolEvents.CONFEDERATION_CEREMONY + GizaProtocolEvents.CONFEDERATION_SIGNING +
            GizaProtocolEvents.GROUP_DM
        assertEquals((30420..30442).toSet(), dedun, "Dedun's block after the second move")
        // The inbox key ceremony is Dedun's too now (docs/giza-second-batch.md, Phase 6), at Giza's numbers.
        assertEquals((30410..30416).toSet(), InboxKeyRitualEvents.ALL)
        assertTrue(InboxKeyRitualEvents.ALL.none { it in dedun || it in GizaProtocolEvents.ALL || MovedKinds.current(it) != it })
        // 30379-30382 are still the legacy numbers of four Wavelength kinds until K3; every other
        // number Giza took reaches no Dedun reader at all.
        val stillLegacy = setOf(30379, 30380, 30381, 30382)
        for (kind in giza) {
            if (kind in stillLegacy) continue
            assertTrue(MovedKinds.current(kind) !in dedun, "Giza's $kind reaches a Dedun reader")
            assertTrue(kind in GizaProtocolEvents.ALL, "Giza's $kind is not reserved")
        }
    }

    @Test
    fun `a carried confederation envelope at 30379 is no Wavelength authorisation`() {
        val envelope = carried(30379)
        assertEquals(GroupWavelengthEvents.AUTHORISATION, MovedKinds.current(envelope.kind))
        assertNull(GroupWavelengthExitEvent.read(envelope.tags))
        assertNull(GroupWavelengthPaymentEvent.read(envelope.tags))
        assertNull(GroupWavelengthOnchainPayoutEvent.read(envelope.tags))
    }

    @Test
    fun `a confederation's host keys and dealings are no Wavelength deposit answer or stop`() {
        // Giza no longer sends these bare -- they travel inside a 30379 -- but an older Giza build did.
        assertFails { CommonJson.decodeFromString(GroupWavelengthDepositAnswer.serializer(), carried(30381).content) }
        assertFails { CommonJson.decodeFromString(GroupWavelengthStopped.serializer(), carried(30382).content) }
    }

    @Test
    fun `none of Giza's new events leaves a line or a bubble in the transcript`() = runBlocking {
        for (kind in GizaProtocolEvents.CONFEDERATION_CEREMONY + GizaProtocolEvents.CONFEDERATION_SIGNING) {
            assertNull(arrive(carried(kind)), "confederation kind $kind reached the transcript")
        }
        for (kind in GizaProtocolEvents.GROUP_DM + InboxKeyRitualEvents.ALL) {
            assertNull(arrive(round(kind)), "kind $kind reached the transcript")
        }
    }
}
