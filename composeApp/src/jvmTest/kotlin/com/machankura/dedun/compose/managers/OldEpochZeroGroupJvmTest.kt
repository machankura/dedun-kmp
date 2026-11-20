package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.marmot.groups.MarmotGroupPolicy
import com.vitorpamplona.marmot.groups.currentMarmotData
import com.vitorpamplona.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.group.MlsGroupState
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.extensions.exporterSecret

/**
 * A room the old engine created and nobody has committed in yet still moves forward.
 *
 * mls-kmp 1.3.1 changed what a new group's epoch 0 is: the interim transcript hash, empty
 * up to 1.3.0 and in quartz 1.15.2, is now computed from a confirmation tag over the
 * empty confirmed transcript, as RFC 9420 §11 has it. A state the old engine saved at
 * epoch 0 is *not* repaired: 1.3.1's first draft did repair a creator's on restore, and
 * its audit commit (`125614a`) took that out, because repairing the creator forked it
 * from a PublicGroup saved beside it. So "an old epoch-0 group keeps its hash until its
 * first commit" -- the library's upgrading guide, in as many words.
 *
 * [OldEngineBlob] is exactly that state -- quartz 1.15.2's, epoch 0, interim hash empty
 * -- and the next thing such a room does is the commit that adds its first member, which
 * is the one commit the interim hash feeds. So: restore it, add someone, let them join
 * through the Welcome, and have each side read the other. A joiner takes the confirmed
 * transcript from the Welcome's GroupInfo rather than recomputing it, which is the
 * argument that the old hash costs nothing here; this is the measurement.
 * docs/quartz-mls-marmot-update.md, Phase 3.
 */
class OldEpochZeroGroupJvmTest {

    private fun restoredOldRoom(): MlsGroup =
        MlsGroup.restore(MlsGroupState.decodeTls(OldEngineBlob.HEX.hexToByteArray()), MarmotGroupPolicy)

    /**
     * What 1.3.1 does with the old hash, pinned both ways round: an old epoch-0 room keeps
     * its empty one, and a room created now gets the §11 one. If a later mls-kmp starts
     * repairing on restore, the first assertion fails -- re-read its upgrading guide
     * then, because a repair that reaches only the creator is the fork `125614a` undid.
     */
    @Test
    fun `an old epoch-0 room keeps its empty interim hash, and a new room gets the RFC 9420 one`() {
        val stored = MlsGroupState.decodeTls(OldEngineBlob.HEX.hexToByteArray())
        assertEquals(0L, stored.groupContext.epoch, "the fixture is no longer an epoch-0 group")
        assertTrue(stored.interimTranscriptHash.isEmpty(), "the fixture's interim hash is not the old engine's empty one")

        val resaved = restoredOldRoom().saveState()
        assertTrue(resaved.interimTranscriptHash.isEmpty(), "restore rewrote the old room's epoch-0 interim hash")

        val creator = KeyPair()
        val fresh = MlsGroup.create(
            identity = creator.pubKey.toHexKey().hexToByteArray(),
            initialExtensions = listOf(
                MarmotGroupData.bootstrap(
                    nostrGroupId = "b".repeat(64),
                    creatorPubKey = creator.pubKey.toHexKey(),
                    outboxRelays = listOf("wss://relay.example.com"),
                ).toExtension()
            ),
            policy = MarmotGroupPolicy,
        )
        assertTrue(fresh.saveState().interimTranscriptHash.isNotEmpty(), "a new room still starts with an empty interim hash")
    }

    @Test
    fun `an old epoch-0 room adds its first member, and both sides read each other`() = runBlocking {
        val creator = restoredOldRoom()
        val joiner = KeyPair()
        val bundle = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(joiner))

        val commit = creator.addMember(bundle.keyPackage.toTlsBytes())
        val joined = MlsGroup.processWelcome(assertNotNull(commit.welcomeBytes, "the add made no Welcome"), bundle, MarmotGroupPolicy)

        assertEquals(1L, creator.epoch, "the creator did not advance")
        assertEquals(1L, joined.epoch, "the joiner did not land on the creator's epoch")
        assertContentEquals(creator.epochAuthenticator, joined.epochAuthenticator, "the two sides derived different key schedules")
        assertContentEquals(creator.exporterSecret(), joined.exporterSecret(), "the kind:445 key differs between the two sides")
        assertEquals(OldEngineBlob.ROOM_ID, joined.currentMarmotData()?.nostrGroupId, "the joiner sees a different room")
        assertTrue(joiner.pubKey.toHexKey() in creator.currentMemberIdentities(), "the creator does not list the joiner")

        val fromCreator = joined.decrypt(creator.encrypt("from the old room's creator".encodeToByteArray()))
        assertEquals("from the old room's creator", fromCreator.content.decodeToString())

        val fromJoiner = creator.decrypt(joined.encrypt("from its first new member".encodeToByteArray()))
        assertEquals("from its first new member", fromJoiner.content.decodeToString())
    }
}
