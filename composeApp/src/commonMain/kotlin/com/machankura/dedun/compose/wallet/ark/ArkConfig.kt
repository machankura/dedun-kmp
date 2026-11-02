package com.machankura.dedun.compose.wallet.ark

/** Why a profile has no Ark wallet open. Each one is a single line on the profile's wallet row. */
enum class ArkUnavailability {
    /** The rollout gate is shut. */
    RolloutOff,

    /** The profile cannot have words: an npub, which cannot sign -- D13. */
    NoSeed,

    /** The first-open sheet has not been accepted -- D10. */
    NotConsented,

    /** Tor is on, and the SDK can be given no proxy -- D6. */
    TorOn,

    /** No Bark library for this platform -- D4. */
    UnsupportedPlatform,
}

/**
 * What a wallet is opened with: the parts of Bark's eighteen-field `Config` this app
 * sets. Every other field is left to Bark's default, inside the adapters.
 */
data class ArkConfig(
    val network: ArkNetwork,
    val endpoints: ArkEndpoints,
    /** `dedun/<version>`: Second's server refuses an agent with no slash -- Phase 0, spike 2. */
    val userAgent: String,
)

/** A profile's Ark wallet can be opened with [config], or cannot be, for [reason]. */
sealed interface ArkSetup {
    data class Ready(val config: ArkConfig) : ArkSetup

    data class Unavailable(val reason: ArkUnavailability) : ArkSetup

    companion object {
        /**
         * The checks a profile passes before anything is opened, in the order a user
         * would want them explained: a gate they cannot change, then what the profile is,
         * then what this device is, then their own choices.
         */
        fun resolve(
            hasSeed: Boolean,
            platformSupported: Boolean,
            torEnabled: Boolean,
            consented: Boolean,
            appVersion: String,
            network: ArkNetwork = ArkWalletRollout.NETWORK,
            rolloutEnabled: Boolean = ArkWalletRollout.ENABLED,
            /** An nsec: no words of its own, but turning the wallet on makes some -- Phase 13. */
            canCreateSeed: Boolean = false,
        ): ArkSetup = when {
            !rolloutEnabled -> Unavailable(ArkUnavailability.RolloutOff)
            !hasSeed && !canCreateSeed -> Unavailable(ArkUnavailability.NoSeed)
            !platformSupported -> Unavailable(ArkUnavailability.UnsupportedPlatform)
            torEnabled -> Unavailable(ArkUnavailability.TorOn)
            !consented || !hasSeed -> Unavailable(ArkUnavailability.NotConsented)
            else -> Ready(ArkConfig(network, ArkWalletRollout.endpointsFor(network), userAgent(appVersion)))
        }

        /** A product token the server accepts: no spaces, and always a slash. */
        fun userAgent(appVersion: String): String =
            "dedun/" + appVersion.trim().replace(Regex("\\s+"), "-").ifEmpty { "0" }
    }
}
