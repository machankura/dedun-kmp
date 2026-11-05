package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.Network
import fr.acinq.phoenix.data.WalletId
import okio.FileSystem
import okio.Path

/**
 * Where a profile's wallet lives on disk -- `docs/wavelength-wallet.md`, D8 and D9, all under
 * a directory no backup reaches:
 * `wavelength/<network>/<walletId>/` for the daemon's own files,
 * `wavelength/consented/<walletId>` for the first-open sheet having been accepted, and
 * `wavelength/identity/<network>/<walletId>` for the identity the first open reported.
 *
 * Nothing here is secret. Nothing here deletes anything either, on purpose: code that removes
 * a wallet's files is the one thing that could lose money by itself.
 */
interface WalletFiles {
    fun dataDir(network: Network, id: WalletId): String

    fun consented(id: WalletId): Boolean

    fun recordConsent(id: WalletId)

    /**
     * The identity this directory is pinned to, once an open has succeeded. Null means none has,
     * which is also what tells a first open to recover (D12): a process killed halfway through
     * one leaves files behind, but no pin.
     */
    fun pinnedIdentity(network: Network, id: WalletId): String?

    fun pinIdentity(network: Network, id: WalletId, identityPubKey: String)

    companion object {
        fun under(root: Path, fileSystem: FileSystem = FileSystem.SYSTEM): WalletFiles =
            OkioWalletFiles(root / "wavelength", fileSystem)
    }
}

private class OkioWalletFiles(private val root: Path, private val fileSystem: FileSystem) : WalletFiles {
    override fun dataDir(network: Network, id: WalletId): String = (root / network.wire / id.nodeIdHash).toString()

    override fun consented(id: WalletId): Boolean = fileSystem.exists(consentFile(id))

    override fun recordConsent(id: WalletId) = write(consentFile(id), "1\n")

    override fun pinnedIdentity(network: Network, id: WalletId): String? {
        val file = identityFile(network, id)
        if (!fileSystem.exists(file)) return null
        return fileSystem.read(file) { readUtf8() }.trim().ifEmpty { null }
    }

    override fun pinIdentity(network: Network, id: WalletId, identityPubKey: String) =
        write(identityFile(network, id), identityPubKey + "\n")

    private fun write(file: Path, text: String) {
        file.parent?.let(fileSystem::createDirectories)
        fileSystem.write(file) { writeUtf8(text) }
    }

    private fun consentFile(id: WalletId) = root / "consented" / id.nodeIdHash

    private fun identityFile(network: Network, id: WalletId) = root / "identity" / network.wire / id.nodeIdHash
}
