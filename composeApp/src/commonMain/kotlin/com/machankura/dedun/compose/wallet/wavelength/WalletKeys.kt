package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.Network
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.byteVector

/**
 * The keys a profile's words give its Wavelength wallet -- `docs/wavelength-wallet.md`, D1.
 *
 * Account-level keys rather than the master key, for two reasons. A daemon handed a root also
 * watches and spends `m/84'/0'/0'`, and that is the Phoenix node's final wallet. And the root
 * derives the nostr key and the node key, neither of which is any business of this daemon's.
 *
 * Frozen once a release holds money, as the group wallet's tags are: moving a path here moves
 * where the coins are. `WalletKeysTest` pins it against vectors the daemon's own code computed.
 *
 * Nothing here is ever logged. The result is seed-equivalent for this wallet.
 */
object WalletKeys {
    /** BIP86. btcwallet's on-chain scopes use coin type 0 on every network. */
    private const val ONCHAIN_ACCOUNT = "m/86'/0'/0'"

    /** lnd's key families. Unlike the on-chain scope, this one follows the network. */
    private fun familiesPath(network: Network): String = "m/1017'/${coinType(network)}'"

    private fun coinType(network: Network): Int = if (network == Network.MAINNET) 0 else 1

    /**
     * The entries for `DescriptorWalletRequest.descriptors`: the BIP86 account, and the
     * coin-level key the daemon expands into every key family.
     */
    fun descriptors(words: List<String>, network: Network): List<String> {
        val master = DeterministicWallet.generate(MnemonicCode.toSeed(words, "").byteVector())
        val prefix = if (network == Network.MAINNET) DeterministicWallet.xprv else DeterministicWallet.tprv
        val fingerprint = master.fingerprint().toString(16).padStart(8, '0')
        val account = master.derivePrivateKey(ONCHAIN_ACCOUNT).encode(prefix)
        val families = master.derivePrivateKey(familiesPath(network)).encode(prefix)
        return listOf(
            "tr([$fingerprint/86h/0h/0h]$account/<0;1>/*)",
            "[$fingerprint/1017h/${coinType(network)}h]$families",
        )
    }

}
