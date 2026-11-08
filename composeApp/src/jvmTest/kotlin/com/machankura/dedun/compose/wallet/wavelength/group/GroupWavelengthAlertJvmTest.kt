package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthAlert.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a group's Wavelength wallet interrupts a member for.
 *
 * The rule rather than the plumbing, because the rule is what B7 is about: *one channel, two triggers,
 * both urgent*. The cases that matter are the ones that must stay quiet -- a notification that fires
 * for a wallet in good order is a notification nobody reads the next time.
 */
class GroupWavelengthAlertJvmTest {

    /** 2016 blocks is the fortnight the devices trigger watches; 648 is the operator's free window. */
    private val fortnight = GroupWavelengthHealth.SOON_BLOCKS

    @Test
    fun `a wallet in good order interrupts nobody`() {
        assertEquals(emptySet(), GroupWavelengthAlert.triggers(WalletExpiry.Fine(10_000), 10_000, readyForARound = true))
    }

    @Test
    fun `coins inside two days of the deadline are worth saying`() {
        assertEquals(setOf(Trigger.DEADLINE), GroupWavelengthAlert.triggers(WalletExpiry.Urgent(144), 144, readyForARound = true))
    }

    /** Past it the Ark operator can take them back, which is the one thing in this wallet with no undo. */
    @Test
    fun `coins past their deadline are worth saying`() {
        assertEquals(setOf(Trigger.DEADLINE), GroupWavelengthAlert.triggers(WalletExpiry.Expired, 0, readyForARound = true))
    }

    /**
     * The lead time is the whole point. The operator's free-renewal window is 648 blocks, so a level of
     * `Fine` still covers most of the fortnight -- which is why this trigger reads the blocks and not
     * the level.
     */
    @Test
    fun `quiet devices with coins inside a fortnight are worth saying, even at a level of fine`() {
        assertEquals(
            setOf(Trigger.DEVICES),
            GroupWavelengthAlert.triggers(WalletExpiry.Fine(1_500), 1_500, readyForARound = false),
            "1,500 blocks is nine days out and `Fine`, because the free window is 648",
        )
        assertEquals(setOf(Trigger.DEVICES), GroupWavelengthAlert.triggers(WalletExpiry.Fine(fortnight), fortnight, readyForARound = false))
    }

    /** Neither half alone is worth interrupting anybody for. */
    @Test
    fun `quiet devices with coins a month out are a line on the checklist, not an interruption`() {
        assertEquals(emptySet(), GroupWavelengthAlert.triggers(WalletExpiry.Fine(4_000), 4_000, readyForARound = false))
        assertEquals(emptySet(), GroupWavelengthAlert.triggers(WalletExpiry.Fine(fortnight + 1), fortnight + 1, readyForARound = false))
    }

    @Test
    fun `a near deadline with a quorum answering is the wallet working`() {
        assertEquals(setOf(Trigger.DEADLINE), GroupWavelengthAlert.triggers(WalletExpiry.Urgent(100), 100, readyForARound = true))
        assertEquals(emptySet(), GroupWavelengthAlert.triggers(WalletExpiry.Renewing(400), 400, readyForARound = true))
    }

    /** Both at once is the worst case, and says both: they have different next steps. */
    @Test
    fun `a near deadline and quiet devices say both`() {
        assertEquals(
            setOf(Trigger.DEADLINE, Trigger.DEVICES),
            GroupWavelengthAlert.triggers(WalletExpiry.Urgent(144), 144, readyForARound = false),
        )
    }

    /** A wallet holding nothing has no deadline to miss, whatever its devices are doing. */
    @Test
    fun `a wallet with no coins interrupts nobody`() {
        assertEquals(emptySet(), GroupWavelengthAlert.triggers(WalletExpiry.Nothing, 0, readyForARound = false))
    }

    /** Renewing is the level the wallet handles by itself while somebody's app is open. */
    @Test
    fun `renewing on its own is not an interruption`() {
        assertEquals(emptySet(), GroupWavelengthAlert.triggers(WalletExpiry.Renewing(600), 600, readyForARound = true))
        assertEquals(setOf(Trigger.DEVICES), GroupWavelengthAlert.triggers(WalletExpiry.Renewing(600), 600, readyForARound = false))
    }
}
