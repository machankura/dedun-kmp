package com.machankura.dedun.compose.managers

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

    /** The group signs at a generation this device has not reached: it missed a certificate. */
    data class Behind(val groupGeneration: Int, val localGeneration: Int) : RotationHold {
        override val reason get() = "the group is at generation $groupGeneration and this device at $localGeneration"
    }
}
