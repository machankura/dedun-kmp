package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.ui.view.state.MemberAction
import com.machankura.dedun.compose.ui.view.state.MemberActionBlocker
import com.machankura.dedun.compose.ui.view.state.MemberActionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a member row's menu offers, and why an item is not open, for every reason
 * docs/member-row-actions.md names. Over plain sets: the agreement with the
 * group's own rules is `AdminChangesJvmTest`'s and `GovernanceGateJvmTest`'s,
 * which call [AdminGovernance.memberActions] -- this, filtered to what is open.
 */
class MemberActionPolicyTest {

    private val me = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)
    private val dana = "d".repeat(64)
    private val gone = "e".repeat(64)

    /** A convenient room I administer, with Bob and Carol in it. */
    private fun convenient(
        admins: Set<String> = setOf(me),
        members: Set<String> = setOf(me, bob, carol),
    ) = MemberActionContext(room = MemberActionRoom.CURRENT, me = me, admins = admins, members = members)

    /** A keyed room where every change can be made: governed, rotation on, floor three. */
    private fun keyed(
        admins: Set<String> = setOf(me, bob, carol, dana),
        members: Set<String> = setOf(me, bob, carol, dana),
    ) = MemberActionContext(
        room = MemberActionRoom.CURRENT,
        me = me,
        admins = admins,
        members = members,
        isKeyed = true,
        governed = true,
        shareRefresh = true,
        floor = 3,
    )

    private fun MemberActionContext.option(member: String, action: MemberAction): MemberActionOption? =
        MemberActionPolicy.options(this, members + admins + gone).getValue(member).firstOrNull { it.action == action }

    private fun MemberActionContext.blockers(member: String, action: MemberAction) =
        requireNotNull(option(member, action)) { "$action was not offered on $member's row" }.blockers

    @Test
    fun `a member is offered make admin and removal, an admin removal as admin and removal, in that order`() {
        val options = MemberActionPolicy.options(convenient(admins = setOf(me, carol)), listOf(bob, carol))

        assertEquals(listOf(MemberAction.MAKE_ADMIN, MemberAction.REMOVE_FROM_GROUP), options.getValue(bob).map { it.action })
        assertEquals(listOf(MemberAction.REMOVE_AS_ADMIN, MemberAction.REMOVE_FROM_GROUP), options.getValue(carol).map { it.action })
        assertTrue(options.values.flatten().all { it.isAvailable })
    }

    @Test
    fun `your own row lists step down when it is open, and nothing otherwise`() {
        val twoAdmins = MemberActionPolicy.options(convenient(admins = setOf(me, bob)), listOf(me))
        assertEquals(listOf(MemberActionOption(MemberAction.STEP_DOWN)), twoAdmins[me])

        assertNull(MemberActionPolicy.options(convenient(), listOf(me))[me], "the only admin was offered to step down")
        assertNull(MemberActionPolicy.options(convenient(admins = setOf(bob)), listOf(me))[me], "a member was offered to step down")
        assertNull(MemberActionPolicy.options(keyed(), listOf(me))[me], "an admin of a keyed room was offered to step down alone")
    }

    @Test
    fun `a NIP-17 room explains that it has no admins, and nothing else`() {
        val context = MemberActionContext(room = MemberActionRoom.NIP17, me = me, admins = emptySet(), members = emptySet())

        val options = MemberActionPolicy.options(context, listOf(me, bob)).getValue(bob)

        assertEquals(listOf(MemberAction.MAKE_ADMIN, MemberAction.REMOVE_FROM_GROUP), options.map { it.action })
        assertTrue(options.all { it.blockers == listOf(MemberActionBlocker.NoAdminsInThisRoom) })
        assertNull(MemberActionPolicy.options(context, listOf(me))[me])
    }

    @Test
    fun `a legacy room says so, and reads the role from the participants`() {
        val context = MemberActionContext(room = MemberActionRoom.LEGACY, me = me, admins = setOf(bob), members = emptySet())

        val options = MemberActionPolicy.options(context, listOf(bob)).getValue(bob)

        assertEquals(MemberAction.REMOVE_AS_ADMIN, options.first().action)
        assertTrue(options.all { it.blockers == listOf(MemberActionBlocker.LegacyRoom) })
    }

    @Test
    fun `a disbanded room, and a room you left, lead with that`() {
        assertEquals(MemberActionBlocker.Disbanded, convenient().copy(isDisbanded = true).blockers(bob, MemberAction.MAKE_ADMIN).first())
        assertEquals(MemberActionBlocker.YouLeft, convenient().copy(hasLeft = true).blockers(bob, MemberAction.REMOVE_FROM_GROUP).first())
    }

    @Test
    fun `a member who is not an admin is told who to ask, on every item`() {
        val context = convenient(admins = setOf(carol))

        assertEquals(listOf(MemberActionBlocker.NotAnAdmin(setOf(carol))), context.blockers(bob, MemberAction.MAKE_ADMIN))
        assertEquals(listOf(MemberActionBlocker.NotAnAdmin(setOf(carol))), context.blockers(bob, MemberAction.REMOVE_FROM_GROUP))
        // Carol is the only admin: the role comes first, the depletion after it.
        assertEquals(
            listOf(MemberActionBlocker.NotAnAdmin(setOf(carol)), MemberActionBlocker.OnlyAdmin),
            context.blockers(carol, MemberAction.REMOVE_AS_ADMIN),
        )
    }

    @Test
    fun `the room's role comes before the viewer's`() {
        assertEquals(
            listOf(MemberActionBlocker.Disbanded, MemberActionBlocker.NotAnAdmin(setOf(carol))),
            convenient(admins = setOf(carol)).copy(isDisbanded = true).blockers(bob, MemberAction.MAKE_ADMIN),
        )
    }

    @Test
    fun `a keyed room that does not need the group's signature names who it waits on`() {
        val context = keyed(admins = setOf(me, carol, dana)).copy(governed = false, waitingOn = setOf(dana))

        assertEquals(listOf(MemberActionBlocker.SignaturesNotRequired(setOf(dana))), context.blockers(bob, MemberAction.MAKE_ADMIN))
        assertEquals(listOf(MemberActionBlocker.SignaturesNotRequired(setOf(dana))), context.blockers(carol, MemberAction.REMOVE_AS_ADMIN).take(1))
        assertTrue(context.option(bob, MemberAction.REMOVE_FROM_GROUP)!!.isAvailable, "removal needs no signature rule to be offered")
    }

    @Test
    fun `a keyed room in a build without share rotation says so`() {
        val context = keyed(admins = setOf(me, carol, dana)).copy(shareRefresh = false)

        assertEquals(listOf(MemberActionBlocker.ShareRotationUnavailable), context.blockers(bob, MemberAction.MAKE_ADMIN))
        assertTrue(context.option(bob, MemberAction.REMOVE_FROM_GROUP)!!.isAvailable)
    }

    @Test
    fun `a convenient room has neither keyed rule`() {
        val context = convenient().copy(governed = false, shareRefresh = false)

        assertTrue(context.option(bob, MemberAction.MAKE_ADMIN)!!.isAvailable)
    }

    @Test
    fun `someone without a leaf can be neither made an admin nor removed`() {
        val context = convenient()

        assertEquals(listOf(MemberActionBlocker.NotInGroupYet), context.blockers(gone, MemberAction.MAKE_ADMIN))
        assertEquals(listOf(MemberActionBlocker.NotInGroupYet), context.blockers(gone, MemberAction.REMOVE_FROM_GROUP))
    }

    @Test
    fun `the floor holds on demotion and removal, and is only spoken of below it`() {
        val atFloor = keyed(admins = setOf(me, bob, carol), members = setOf(me, bob, carol, dana))

        assertEquals(listOf(MemberActionBlocker.BelowFloor(3)), atFloor.blockers(bob, MemberAction.REMOVE_AS_ADMIN))
        assertEquals(listOf(MemberActionBlocker.BelowFloor(3)), atFloor.blockers(bob, MemberAction.REMOVE_FROM_GROUP))
        assertTrue(atFloor.option(dana, MemberAction.REMOVE_FROM_GROUP)!!.isAvailable, "a member's removal touches no floor")

        val aboveFloor = keyed()
        assertTrue(aboveFloor.option(bob, MemberAction.REMOVE_AS_ADMIN)!!.isAvailable)
    }

    @Test
    fun `work in flight comes last`() {
        val context = convenient(admins = setOf(carol)).copy(changePending = true, offeredAdmin = setOf(bob))

        assertEquals(
            listOf(MemberActionBlocker.NotAnAdmin(setOf(carol)), MemberActionBlocker.ChangePending, MemberActionBlocker.OfferPending),
            context.blockers(bob, MemberAction.MAKE_ADMIN),
        )
        assertEquals(
            listOf(MemberActionBlocker.NotAnAdmin(setOf(carol)), MemberActionBlocker.ChangePending),
            context.blockers(bob, MemberAction.REMOVE_FROM_GROUP),
        )
    }
}
