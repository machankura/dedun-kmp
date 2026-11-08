package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry

/**
 * The two things a group's Wavelength wallet interrupts a member for, and nothing else.
 *
 * B7 of `docs/group-wavelength-redesign.md`: *one channel, two triggers, both urgent*. The guide's
 * reminder etiquette is explicit that a lower risk earns a quieter treatment, and everything else on
 * the health checklist is something a member can see when they look.
 *
 * A pure function because the rule is the part worth getting right. `GroupArkWatcher` has been
 * computing both of these facts every thirty minutes since long before this plan -- the tip, the
 * earliest deadline, and who has not answered -- and writing them into the room; what this decides is
 * which of them is worth a notification as well.
 */
object GroupWavelengthAlert {

    enum class Trigger {
        /**
         * The coins are inside two days of their deadline, or past it.
         *
         * Past it the Ark operator can take them back, so this is the one fact in the wallet with a
         * deadline a member cannot be told about too early.
         */
        DEADLINE,

        /**
         * Too few devices have answered for a round to work, and the coins need renewing within a
         * fortnight.
         *
         * The lead time is the whole point: by the time the deadline is urgent, a group whose devices
         * are quiet has no way to renew. Neither half alone is worth interrupting anybody for -- quiet
         * devices with coins a month out are a line on the checklist, and a near deadline with a
         * quorum answering is the wallet working.
         */
        DEVICES,
    }

    /**
     * Which of the two hold, for coins [blocksLeft] from their deadline with [readyForARound] saying
     * whether enough of the group's devices have answered lately.
     *
     * [expiry] and [blocksLeft] are both passed because they answer different questions: the expiry
     * carries the operator's free-renewal window, which is shorter than a fortnight, so the devices
     * trigger has to look at the blocks rather than at the level. A wallet holding nothing
     * ([WalletExpiry.Nothing]) triggers neither: there is no deadline to miss.
     */
    fun triggers(expiry: WalletExpiry, blocksLeft: Long, readyForARound: Boolean): Set<Trigger> {
        if (expiry == WalletExpiry.Nothing) return emptySet()
        val deadline = expiry is WalletExpiry.Urgent || expiry == WalletExpiry.Expired
        val devices = !readyForARound && blocksLeft <= GroupWavelengthHealth.SOON_BLOCKS
        return setOfNotNull(
            Trigger.DEADLINE.takeIf { deadline },
            Trigger.DEVICES.takeIf { devices },
        )
    }
}

/**
 * Where an urgent fact about a group's wallet goes besides the room.
 *
 * An interface rather than an `expect` on the watcher, so [GroupArkWatcher] stays something a test can
 * drive: the test hands it a recording implementation and asserts the words, and the platform that has
 * notifications supplies the real one. [None] is what a platform without them supplies, and what a
 * test gets by default.
 */
fun interface GroupWavelengthAlerts {

    /**
     * Posts one alert. [key] is the fact's own key, so a platform that can replace an alert rather
     * than stack a second one has what it needs to.
     */
    fun post(key: String, title: String, text: String)

    companion object {
        /** Says nothing, which is what desktop and iOS do. */
        val None: GroupWavelengthAlerts = GroupWavelengthAlerts { _, _, _ -> }
    }
}
