package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.InboxKey
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyStage
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import fr.acinq.bitcoin.Chain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * The group's inbox key never signs for money (docs/giza-second-batch.md, Phase 6).
 *
 * Giza keeps the inbox key apart from `DkgSession` so that no signing path can find a threshold-1
 * key, and pins that for its one signing path (`InboxStorageJvmTest`). Dedun has more: the onchain
 * wallet reads the room's threshold key, and the Lightning and Wavelength wallets read their
 * Iceberg keys. A key any one admin holds alone, found by any of them, would let one admin spend
 * the group's money. So a room that holds both a group key and an inbox key is read here through
 * every one of those lookups, and each finds the group's key or nothing -- never the inbox's.
 */
class InboxKeyReachesNoWalletJvmTest {
    private val harness = SigningProposalHarness()

    @AfterTest
    fun close() = harness.close()

    /** The inbox's public key, which no wallet lookup may return. */
    private val inboxPublicKey = "e".repeat(64)

    private fun device() = runBlocking {
        val owner = harness.members[0]
        harness.openDevice(owner) { db ->
            db.inboxKeyDao().upsertCeremony(
                InboxKeyCeremony(
                    id = "c".repeat(64),
                    chatRoomId = harness.roomId,
                    generation = 0,
                    coordinatorPublicKey = owner,
                    userPublicKey = owner,
                    participantPublicKeys = InboxKeyCeremony.formatParticipants(harness.members),
                    stage = InboxKeyCeremonyStage.COMPLETE,
                    hostPublicKey = "02" + "1".repeat(64),
                    round1Random = "2".repeat(64),
                    round2AuxRandom = "3".repeat(64),
                    inboxPublicKey = inboxPublicKey,
                )
            )
            db.inboxKeyDao().upsertKey(
                InboxKey(
                    chatRoomId = harness.roomId,
                    userPublicKey = owner,
                    generation = 0,
                    publicKey = inboxPublicKey,
                    secretKey = "5".repeat(64),
                    recoveryData = "66",
                    ceremonyId = "c".repeat(64),
                    state = InboxKeyState.ANNOUNCED,
                )
            )
        }
    }

    @Test
    fun `the room's signing key is the group's, with an inbox key beside it`() = runBlocking {
        val db = device()
        assertEquals(harness.thresholdPublicKey, FrostSigningManager.completedKey(db, harness.roomId)?.thresholdPublicKey)
        assertEquals(harness.ceremonyId, FrostSigningManager.completedKey(db, harness.roomId)?.id)
    }

    @Test
    fun `the onchain wallet reads the group's key, not the inbox's`() = runBlocking {
        val db = device()
        assertEquals(harness.thresholdPublicKey, GroupWalletManager.thresholdKeyFor(db, harness.roomId))
    }

    @Test
    fun `no Iceberg key, Lightning or Wavelength wallet is found in the inbox`() = runBlocking {
        val db = device()
        assertNull(IcebergRitualManager.completedKey(db, harness.roomId, IcebergKeySession.PURPOSE_LIGHTNING))
        assertNull(IcebergRitualManager.completedKey(db, harness.roomId, IcebergKeySession.PURPOSE_WAVELENGTH))
        assertNull(GroupLightningWalletManager.current(db, harness.roomId, Chain.Regtest))
        for (chain in GroupWavelengthRollout.NETWORKS + Chain.Regtest) {
            assertNull(GroupWavelengthWalletManager.current(db, harness.roomId, chain))
        }
        // And so D4 does not read an inbox as a wallet that holds the admins where they are.
        assertNull(WalletKeysHeld.of(db, harness.roomId))
    }
}
