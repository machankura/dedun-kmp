package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * A profile or a post the group is part-way through signing holds a share rotation back, as a
 * money statement does (docs/giza-second-batch.md, Phase 5, and `RotationHold.StatementOpen`):
 * rotating under it would leave the session signing with a generation its signers no longer hold.
 */
class GroupStatementHoldsRotationJvmTest {
    private val harness = SigningProposalHarness()

    @AfterTest
    fun close() = harness.close()

    @Test
    fun `a post being signed is an open statement`() = runBlocking {
        val sent = harness.propose(harness.members[0]) { db, room ->
            FrostSigningManager.proposeSigning(db, room, harness.members[0], TextNoteEvent.KIND, emptyArray(), "Meeting on Saturday")
        }
        assertEquals(sent.session.id, FrostSigningManager.openMoneyStatementSession(harness.db, harness.ceremonyId)?.id)
    }

    @Test
    fun `a profile being signed is an open statement`() = runBlocking {
        val sent = harness.propose(harness.members[0]) { db, room ->
            FrostSigningManager.proposeSigning(db, room, harness.members[0], MetadataEvent.KIND, emptyArray(), "{\"name\":\"Stokvel\"}")
        }
        assertEquals(sent.session.id, FrostSigningManager.openMoneyStatementSession(harness.db, harness.ceremonyId)?.id)
    }

    @Test
    fun `a room with nothing being signed holds nothing`() = runBlocking {
        harness.openDevice(harness.members[0])
        assertNull(FrostSigningManager.openMoneyStatementSession(harness.db, harness.ceremonyId))
    }
}
