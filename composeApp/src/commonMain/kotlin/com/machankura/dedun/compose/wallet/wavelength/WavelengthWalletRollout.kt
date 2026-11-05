package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.NetworkPresets
import engineering.lightning.wavelength.RuntimeConfig
import fr.acinq.bitcoin.Satoshi
import kotlin.time.Instant

/**
 * The gates the profile's wallet ships behind -- `docs/wavelength-wallet.md`, D5 and D6 -- in
 * one place, each with the condition that lifts it.
 *
 * Its network is its own and not `NodeParamsManager.chain`, since it shares nothing with the
 * node.
 */
object WavelengthWalletRollout {
    /**
     * Whether a profile that has not turned the wallet on is offered it. **Not** a kill switch:
     * a profile that consented always opens, whatever this says, because a wallet that can't
     * open can't be emptied and its coins stop being renewed. Off is how a wind-down starts.
     */
    const val OFFERED: Boolean = true

    /**
     * The network every profile's wallet opens on. Signet until the hand-run table in Phase 11
     * has been done on a real device, with real money, and written down.
     */
    val NETWORK: Network = Network.SIGNET

    /**
     * The most one send may move, and the balance an invoice may take the wallet to, on
     * mainnet. The group wallets' figure. An onchain deposit somebody simply sends cannot be
     * bounded, so the wallet screen says what the cap is.
     */
    val MAINNET_CAP: Satoshi = Satoshi(100_000)

    /**
     * No key here can have received anything before this, so it bounds a first open's rescan
     * (D12). Frozen: a later date could miss coins.
     */
    val EARLIEST_BIRTHDAY: Instant = Instant.parse("2026-11-01T00:00:00Z")

    fun capFor(network: Network): Satoshi? = MAINNET_CAP.takeIf { network == Network.MAINNET }

    /** Whether receiving [incoming] would take [balance] past the cap on [network]. */
    fun wouldExceedCap(network: Network, balance: Satoshi, incoming: Satoshi): Boolean {
        val cap = capFor(network) ?: return false
        return balance + incoming > cap
    }

    /** Whether a send of [amount] is over the cap on [network]. */
    fun sendExceedsCap(network: Network, amount: Satoshi): Boolean {
        val cap = capFor(network) ?: return false
        return amount > cap
    }

    /**
     * The hosts are written down rather than left to the daemon's defaults, so a daemon bump
     * can't move them silently. The daemon's own mainnet Esplora default is a *staging* host,
     * which is not what holds a mainnet wallet's view of the chain (D6).
     */
    fun configFor(network: Network, dataDir: String): RuntimeConfig = when (network) {
        Network.MAINNET -> RuntimeConfig(
            network = Network.MAINNET,
            allowMainnet = true,
            dataDir = dataDir,
            arkServerAddress = "wavelength.lightning.finance:443",
            swapServerAddress = "swap.wavelength.lightning.finance:443",
            walletEsploraUrl = "https://mempool.space/api",
            debugLevel = DEBUG_LEVEL,
        )
        else -> RuntimeConfig.preset(network, dataDir).copy(debugLevel = DEBUG_LEVEL)
    }

    /** The daemon logs to logcat in release builds too, so it says little. */
    private const val DEBUG_LEVEL = "warn"
}
