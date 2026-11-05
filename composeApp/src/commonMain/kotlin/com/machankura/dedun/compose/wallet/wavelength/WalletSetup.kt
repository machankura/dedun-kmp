package com.machankura.dedun.compose.wallet.wavelength

/** Why a profile has no wallet open. Each one is a single line on the profile's wallet row. */
enum class WalletUnavailability {
    /** No words: an npub, or an nsec until Phase 13 (D13). */
    NoSeed,

    /** Upstream has no runtime for this platform (D4). */
    UnsupportedPlatform,

    /** Tor is on, and the daemon can be given no proxy (D7). */
    TorOn,

    /** The first-open sheet has not been accepted (D9). */
    NotConsented,

    /** Not consented, and the wallet is not being offered any more (D5). */
    NotOffered,
}

/** What the active profile can do about a wallet, before anything is opened. */
sealed interface WalletSetup {
    data object Ready : WalletSetup

    data class Unavailable(val reason: WalletUnavailability) : WalletSetup

    companion object {
        /**
         * In the order a user would want them explained: what the profile is, what this device
         * is, then their own choices. **[offered] only ever matters to a profile that hasn't
         * consented**, so that shutting the gate can't lock anyone out of their money (D5).
         */
        fun resolve(
            hasSeed: Boolean,
            platformSupported: Boolean,
            torEnabled: Boolean,
            consented: Boolean,
            offered: Boolean = WavelengthWalletRollout.OFFERED,
        ): WalletSetup = when {
            !hasSeed -> Unavailable(WalletUnavailability.NoSeed)
            !platformSupported -> Unavailable(WalletUnavailability.UnsupportedPlatform)
            torEnabled -> Unavailable(WalletUnavailability.TorOn)
            consented -> Ready
            offered -> Unavailable(WalletUnavailability.NotConsented)
            else -> Unavailable(WalletUnavailability.NotOffered)
        }
    }
}
