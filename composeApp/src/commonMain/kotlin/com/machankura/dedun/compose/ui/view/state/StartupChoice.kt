package com.machankura.dedun.compose.ui.view.state

import fr.acinq.phoenix.data.WalletId
import com.machankura.dedun.compose.identity.StoredIdentity

/**
 * Which profile the startup screen opens without asking, and when it asks instead.
 *
 * Lifted out of the screen's `remember` so the table can be read and tested: the
 * order of these rows is the whole of the decision, and it was wrong in the one way a
 * switch would hit. `!startImmediately` sat above `desired`, and nothing ever set the
 * flag back to true, so after the first visit to the selector every sign-in on the
 * device landed on the selector instead of the profile it had just signed in.
 * See docs/multiple-profiles.md, Phase 3.
 */
object StartupChoice {

    /**
     * The identity to open, or null to show the selector.
     *
     * @param force an id the host insisted on; nothing passes one today.
     * @param desired the id a switch or a sign-in named. The most specific instruction,
     *   and the one a switch relies on, so it outranks the user's earlier "show me the
     *   list".
     * @param startImmediately false when the user asked for the list -- from a forget
     *   tail, or the lock prompt's back button -- and true otherwise, which a switch
     *   sets it back to.
     * @param default the last profile opened, saved at every activation, so that a cold
     *   boot resumes where the user was.
     */
    fun resolve(
        force: WalletId?,
        desired: WalletId?,
        startImmediately: Boolean,
        identities: Map<WalletId, StoredIdentity>,
        default: WalletId?,
    ): StoredIdentity? = when {
        force != null -> identities[force]
        desired != null -> identities[desired]
        !startImmediately -> null
        identities.size == 1 -> identities.values.single()
        default != null -> identities[default]
        else -> null
    }
}
