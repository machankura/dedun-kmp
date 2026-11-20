package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Why this device must not propose a share rotation now
 * (docs/rotation-waits-for-key-state.md). [ShareRefreshManager.rotationHold]
 * answers it; [reason] is the refusal the propose paths give.
 */
sealed interface RotationHold {
    val reason: String

    /** A refresh of the key is still running here. */
    data class Running(val refreshId: String) : RotationHold {
        override val reason get() = "a rotation is still running"
    }

    /**
     * The group has not signed the key state at [generation] -- the proof that
     * the shares the last rotation dealt can sign -- or a key-state proposal is
     * open, [signingSessionId] when this device holds it.
     */
    data class AwaitingKeyState(val generation: Int, val signingSessionId: String?) : RotationHold {
        override val reason get() = "the group has not yet signed the key state at generation $generation"
    }

    /**
     * Dedun's: a spend or a sweep from the group's wallet is still being signed with
     * this key. Rotating would fail it -- every unfinished session below the new
     * generation is failed when the old shares go -- and a spend with every partial
     * signature in would have to be proposed again. docs/giza-into-dedun.md, Phase 7.
     */
    data class SpendOpen(val signingSessionId: String) : RotationHold {
        override val reason get() = "a spend from the group's wallet is still being signed"
    }

    /**
     * Dedun's: one of its money statements -- a deposit attribution, a coin assignment, a
     * transfer, or a payout from the Wavelength or the Lightning wallet -- is still being
     * signed with this key. docs/giza-into-dedun.md, Phase 7.
     */
    data class StatementOpen(val signingSessionId: String) : RotationHold {
        override val reason get() = "a payout, transfer or attribution is still being signed"
    }

    /** The group signs at a generation this device has not reached: it missed a certificate. */
    data class Behind(val groupGeneration: Int, val localGeneration: Int) : RotationHold {
        override val reason get() = "the group is at generation $groupGeneration and this device at $localGeneration"
    }

    /**
     * A change to who is an admin, or a removal of a member who holds a share, is
     * being signed or has not landed yet ([signingSessionId] is its signature's
     * session). A rotation proposed now is fixed against a roster that change is
     * about to move (docs/admin-change-rotation.md, Phase 2).
     */
    data class AdminChangeInFlight(val signingSessionId: String) : RotationHold {
        override val reason get() = "a change to the group's admins is on its way"
    }

    /**
     * A new admin's id is bound by the key state the group signed, and has no
     * share yet. A rotation now would leave them stale, and fails outright if
     * the binding lands while it deals (docs/admin-change-rotation.md, H2 and H3).
     */
    data class Enrolling(val memberPublicKey: HexKey) : RotationHold {
        override val reason get() = "a new admin is still receiving their share"
    }
}
