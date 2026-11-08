package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.managers.GroupWavelengthDaemon
import com.machankura.dedun.compose.managers.GroupWavelengthDaemonStatus
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.ME
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.OTHER
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.localChatRoom
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.wallet
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHealth.Fix
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHealth.Kind
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHealth.Standing
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The checklist, read off a fixture rather than off a screen.
 *
 * Every item is derived from `GroupWavelengthWalletUIState.Opened`, so the whole of B7's first
 * paragraph is a pure function and these are the cases that matter: each item's own states, the
 * worst-first rule, all-fine, and the one that would be a lie -- a green tick against a deadline on a
 * wallet that holds nothing.
 */
class GroupWavelengthHealthJvmTest {
    private val now = Clock.System.now()

    /** The group's four devices, three of which answered a minute ago: `2t-1` is 3 for a threshold of 2. */
    private val quorumSeen: Map<HexKey, kotlin.time.Instant> =
        wallet().statement.participants.take(3).associateWith { now - 1.minutes }

    private fun opened(
        expiry: WalletExpiry = WalletExpiry.Fine(10_000),
        seen: Map<HexKey, kotlin.time.Instant> = quorumSeen,
        operator: HexKey? = OTHER,
        isOperator: Boolean = false,
        daemon: GroupWavelengthDaemonStatus = GroupWavelengthDaemonStatus.NotOnThisDevice,
        feesSat: Long = 0,
        departed: List<HexKey> = emptyList(),
        canRunHere: Boolean = true,
    ) = GroupWavelengthWalletUIState.Opened(
        localChatRoom = localChatRoom,
        wallet = wallet(),
        operator = operator?.let { GroupWavelengthUiFixtures.operator(it) },
        isParticipant = true,
        isOperator = isOperator,
        canRunHere = canRunHere,
        userPublicKey = ME,
        expiry = expiry,
        seen = seen,
        daemon = daemon,
        feesSat = feesSat,
        departed = departed,
    )

    private fun standing(state: GroupWavelengthWalletUIState.Opened, kind: Kind) =
        GroupWavelengthHealth.of(state, now).firstOrNull { it.kind == kind }?.standing

    @Test
    fun `a wallet in good order says so on all five`() {
        val items = GroupWavelengthHealth.of(opened(seen = quorumSeen + (OTHER to now)), now)
        assertEquals(Kind.entries, items.map { it.kind }, "all five, in the order the screen lists them")
        assertEquals(listOf(Standing.FINE), items.map { it.standing }.distinct())
    }

    /**
     * A wallet holding nothing has no deadline, so it gets no item at all. A green tick here would
     * read as "checked and fine" where the truth is "nothing to check".
     */
    @Test
    fun `a wallet with no coins has no deadline item rather than a green one`() {
        val items = GroupWavelengthHealth.of(opened(expiry = WalletExpiry.Nothing), now)
        assertNull(items.firstOrNull { it.kind == Kind.COINS })
        assertEquals(4, items.size)
    }

    @Test
    fun `the coins' deadline is fine, then amber while it renews itself, then a problem`() {
        assertEquals(Standing.FINE, standing(opened(expiry = WalletExpiry.Fine(10_000)), Kind.COINS))
        assertEquals(Standing.ATTENTION, standing(opened(expiry = WalletExpiry.Renewing(3_000)), Kind.COINS))
        assertEquals(Standing.PROBLEM, standing(opened(expiry = WalletExpiry.Urgent(144)), Kind.COINS))
        assertEquals(Standing.PROBLEM, standing(opened(expiry = WalletExpiry.Expired), Kind.COINS))
        assertEquals(Fix.ADVANCED, GroupWavelengthHealth.of(opened(expiry = WalletExpiry.Expired), now).first { it.kind == Kind.COINS }.fix)
    }

    /** Too few devices is amber on its own and a problem once the coins are close: the same threshold B7 notifies on. */
    @Test
    fun `too few devices matters more as the deadline comes closer`() {
        val quiet = mapOf(ME to now)
        assertEquals(Standing.ATTENTION, standing(opened(seen = quiet, expiry = WalletExpiry.Fine(10_000)), Kind.DEVICES))
        assertEquals(Standing.PROBLEM, standing(opened(seen = quiet, expiry = WalletExpiry.Fine(1_000)), Kind.DEVICES))
        assertEquals(Standing.PROBLEM, standing(opened(seen = quiet, expiry = WalletExpiry.Urgent(144)), Kind.DEVICES))
        assertEquals(Standing.FINE, standing(opened(seen = quorumSeen, expiry = WalletExpiry.Urgent(144)), Kind.DEVICES))
    }

    @Test
    fun `nobody running it is a problem, and a device that could run it is offered that`() {
        val items = GroupWavelengthHealth.of(opened(operator = null), now)
        val who = items.first { it.kind == Kind.WHO_RUNS_IT }
        assertEquals(Standing.PROBLEM, who.standing)
        assertEquals(Fix.THIS_DEVICE_CAN_RUN_IT, who.fix)
        // A platform with no runtime at all is offered nothing: only the group can fix it.
        assertEquals(Fix.NOTHING, GroupWavelengthHealth.of(opened(operator = null, canRunHere = false), now).first { it.kind == Kind.WHO_RUNS_IT }.fix)
    }

    @Test
    fun `whoever runs it going quiet is amber, and a problem once the coins are close`() {
        val stale = quorumSeen + (OTHER to now - 2.days)
        assertEquals(Standing.ATTENTION, standing(opened(seen = stale, expiry = WalletExpiry.Fine(10_000)), Kind.WHO_RUNS_IT))
        assertEquals(Standing.PROBLEM, standing(opened(seen = stale, expiry = WalletExpiry.Urgent(144)), Kind.WHO_RUNS_IT))
    }

    /** On the device that runs it, its own process says more than "seen lately" can. */
    @Test
    fun `the device that runs it reads its own process rather than the room`() {
        val mine = opened(operator = ME, isOperator = true, seen = emptyMap())
        assertEquals(Standing.FINE, standing(mine.copy(daemon = GroupWavelengthDaemonStatus.Running(3)), Kind.WHO_RUNS_IT))
        assertEquals(Standing.PROBLEM, standing(mine.copy(daemon = GroupWavelengthDaemonStatus.Failed), Kind.WHO_RUNS_IT))
        assertEquals(
            Standing.PROBLEM,
            standing(mine.copy(daemon = GroupWavelengthDaemonStatus.Refused(GroupWavelengthDaemon.Refusal.NO_SEED)), Kind.WHO_RUNS_IT),
        )
    }

    @Test
    fun `the fee budget is fine, then amber at four fifths, then a problem when it is gone`() {
        val budget = wallet().statement.policy.maxMonthlyFeeSat
        assertEquals(Standing.FINE, standing(opened(feesSat = 0), Kind.FEES))
        assertEquals(Standing.FINE, standing(opened(feesSat = (budget * 0.5).toLong()), Kind.FEES))
        assertEquals(Standing.ATTENTION, standing(opened(feesSat = (budget * 0.9).toLong()), Kind.FEES))
        assertEquals(Standing.PROBLEM, standing(opened(feesSat = budget), Kind.FEES))
    }

    /** Never amber: either nobody who left holds a share, or the money has to move to a new key. */
    @Test
    fun `a share held by somebody who left is a problem or nothing`() {
        assertEquals(Standing.FINE, standing(opened(), Kind.DEPARTED))
        val gone = GroupWavelengthHealth.of(opened(departed = listOf("f".repeat(64))), now).first { it.kind == Kind.DEPARTED }
        assertEquals(Standing.PROBLEM, gone.standing)
        assertEquals(Fix.CEREMONY, gone.fix)
    }

    @Test
    fun `the worst of them is what the row shows, and the first of equals`() {
        val twoProblems = GroupWavelengthHealth.of(opened(expiry = WalletExpiry.Expired, departed = listOf("f".repeat(64))), now)
        assertEquals(Kind.COINS, GroupWavelengthHealth.worst(twoProblems)?.kind, "the one with the soonest consequence")
        val oneAmber = GroupWavelengthHealth.of(opened(expiry = WalletExpiry.Renewing(3_000)), now)
        assertEquals(Standing.ATTENTION, GroupWavelengthHealth.worst(oneAmber)?.standing)
        assertEquals(Standing.FINE, GroupWavelengthHealth.worst(GroupWavelengthHealth.of(opened(seen = quorumSeen + (OTHER to now)), now))?.standing)
        assertNull(GroupWavelengthHealth.worst(emptyList()))
    }
}
