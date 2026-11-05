package com.machankura.dedun.compose.wallet.wavelength

/** Why a profile has no wallet open. Each one is a single line on the profile's wallet row. */
enum class WalletUnavailability {
    /** No words and no way to have any: an npub, which can't sign (D13). */
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
            /** An nsec: no words yet, but accepting the sheet makes some (D13). */
            canCreateSeed: Boolean = false,
        ): WalletSetup = when {
            !hasSeed && !canCreateSeed -> Unavailable(WalletUnavailability.NoSeed)
            !platformSupported -> Unavailable(WalletUnavailability.UnsupportedPlatform)
            torEnabled -> Unavailable(WalletUnavailability.TorOn)
            consented && hasSeed -> Ready
            offered -> Unavailable(WalletUnavailability.NotConsented)
            else -> Unavailable(WalletUnavailability.NotOffered)
        }
    }
}
