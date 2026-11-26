package com.machankura.dedun.compose.nostr.frost

import com.vitorpamplona.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.mls.group.GroupView
import com.vitorpamplona.mls.group.PendingProposal
import com.vitorpamplona.mls.messages.Proposal
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import com.machankura.dedun.compose.extensions.toHex

/**
 * The statement a keyed room's quorum signs before its commit, and the one
 * function that holds a commit to it. docs/member-admin-promotion.md, Phase 3.
 */
class GovernanceStatementEventTest {

    private val room = KeyPair()
    private val roomId = room.pubKey.toHexKey()
    private val alice = "a1".repeat(32)
    private val bob = "b2".repeat(32)
    private val carol = "c3".repeat(32)
    private val epoch = 7L

    /** A tree of Alice, Bob and Carol at leaves 0, 1 and 2. */
    private val group = GroupView(
        extensions = emptyList(),
        leafCount = 3,
        myLeafIndex = 0,
        identityAt = { listOf(alice, bob, carol).getOrNull(it) },
        capabilitiesAt = { null },
    )

    private fun pending(proposal: Proposal, from: Int = 0) = PendingProposal(proposal, senderLeafIndex = from)

    private fun adminsUpdate(vararg admins: String) = Proposal.AppDataUpdate(
        AdminPolicyV1.COMPONENT_ID,
        Proposal.AppDataUpdate.Operation.Update(AdminPolicyV1.ofHex(admins.toList()).encode()),
    )

    private fun statement(change: GovernanceChange, at: Long = epoch, signer: KeyPair = room, about: String = roomId): Event =
        NostrSignerSync(signer).signNormal<Event>(
            createdAt = 1_700_000_000,
            kind = GovernanceStatementEvent.KIND,
            tags = GovernanceStatementEvent.assembleTags(about, at, change, proposer = alice),
            content = "",
        )

    @Test
    fun `a statement reads back as the change it was written from`() {
        val change = GovernanceChange(
            adds = setOf(AddedMember(carol, "ff".repeat(32))),
            removes = setOf(bob),
            admins = setOf(alice, carol),
            components = mapOf(0x800c to "ee".repeat(32)),
        )

        val read = assertNotNull(GovernanceStatementEvent.parse(statement(change)))

        assertEquals(GovernanceStatement(roomId, epoch, alice, change), read)
    }

    @Test
    fun `a promotion commit is exactly what its statement says`() {
        val proposals = listOf(pending(adminsUpdate(alice, bob, carol)))
        val change = assertNotNull(GovernanceChange.of(group, proposals))

        assertEquals(GovernanceChange(admins = setOf(alice, bob, carol)), change)
        assertTrue(GovernanceStatementEvent.authorises(statement(change), roomId, epoch, change))
    }

    @Test
    fun `a removal names the account, not the leaf`() {
        val change = assertNotNull(GovernanceChange.of(group, listOf(pending(Proposal.Remove(2)), pending(adminsUpdate(alice, bob)))))

        assertEquals(GovernanceChange(removes = setOf(carol), admins = setOf(alice, bob)), change)
    }

    @Test
    fun `an add is pinned to the exact key package`() = runTest {
        val dave = KeyPair()
        val keyPackage = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(dave)).keyPackage
        val change = assertNotNull(GovernanceChange.of(group, listOf(pending(Proposal.Add(keyPackage)))))

        assertEquals(setOf(AddedMember(dave.pubKey.toHexKey(), keyPackage.reference().toHex())), change.adds)

        // Another of Dave's packages is another add, and the statement does not cover it.
        val other = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(dave)).keyPackage
        val otherChange = assertNotNull(GovernanceChange.of(group, listOf(pending(Proposal.Add(other)))))
        assertFalse(GovernanceStatementEvent.authorises(statement(change), roomId, epoch, otherChange))
    }

    @Test
    fun `nothing more and nothing less`() {
        val signed = GovernanceChange(admins = setOf(alice, bob, carol))
        val event = statement(signed)

        // An extra removal folded in beside the signed promotion.
        assertFalse(GovernanceStatementEvent.authorises(event, roomId, epoch, signed.copy(removes = setOf(bob))))
        // The admin list off by one.
        assertFalse(GovernanceStatementEvent.authorises(event, roomId, epoch, signed.copy(admins = setOf(alice, bob))))
    }

    @Test
    fun `a statement is good at its own epoch only`() {
        val change = GovernanceChange(admins = setOf(alice, bob))

        assertFalse(GovernanceStatementEvent.authorises(statement(change, at = epoch), roomId, epoch + 1, change))
    }

    @Test
    fun `only the room itself can sign for the room`() {
        val change = GovernanceChange(admins = setOf(alice, bob))

        // Signed by one member's own key.
        assertFalse(GovernanceStatementEvent.authorises(statement(change, signer = KeyPair()), roomId, epoch, change))
        // Signed by another room, about this one.
        val otherRoom = KeyPair()
        assertFalse(GovernanceStatementEvent.authorises(statement(change, signer = otherRoom), roomId, epoch, change))
        // This room's signature, about another room.
        assertFalse(GovernanceStatementEvent.authorises(statement(change, about = otherRoom.pubKey.toHexKey()), roomId, epoch, change))
    }

    @Test
    fun `a member's own leave needs no signature, and rides along with one that does`() {
        val leave = pending(Proposal.SelfRemove(), from = 1)

        assertTrue(GovernanceChange.isExempt(listOf(leave)))
        assertTrue(GovernanceChange.isExempt(emptyList()), "a committer's own key update needed a signature")
        assertFalse(GovernanceChange.isExempt(listOf(leave, pending(adminsUpdate(alice)))))
        assertEquals(GovernanceChange(admins = setOf(alice)), GovernanceChange.of(group, listOf(leave, pending(adminsUpdate(alice)))))
    }

    @Test
    fun `a change a statement cannot describe is never authorised`() {
        assertNull(GovernanceChange.of(group, listOf(pending(Proposal.GroupContextExtensions(emptyList())))))
    }

    @Test
    fun `malformed tags make no statement`() {
        val event = NostrSignerSync(room).signNormal<Event>(
            createdAt = 1_700_000_000,
            kind = GovernanceStatementEvent.KIND,
            tags = arrayOf(arrayOf("d", roomId), arrayOf("epoch", "seven")),
            content = "",
        )

        assertNull(GovernanceStatementEvent.parse(event))
    }
}
