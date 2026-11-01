package com.machankura.dedun.compose.wallet.ark

import fr.acinq.phoenix.data.WalletId
import okio.FileSystem
import okio.Path

/**
 * Where a profile's Ark wallet lives on disk -- `docs/bark-wallet.md`, D2:
 * `<appFiles>/ark/<network>/<walletId>/` for Bark's own files, and
 * `<appFiles>/ark/consented/<walletId>` for the profile having accepted the first-open
 * sheet (D10).
 *
 * The consent is a file rather than a preference because `InternalPrefs` keeps its
 * DataStore private to the library, and one boolean is not worth a submodule commit.
 * It sits beside the wallets so that removing a profile's Ark files removes both.
 */
interface ArkFiles {
    fun datadir(network: ArkNetwork, id: WalletId): String

    /** Whether Bark has created anything in [datadir] yet: the first open of one runs the mailbox scan. */
    fun exists(datadir: String): Boolean

    fun consented(id: WalletId): Boolean

    fun recordConsent(id: WalletId)

    companion object {
        fun under(root: Path, fileSystem: FileSystem = FileSystem.SYSTEM): ArkFiles = OkioArkFiles(root / "ark", fileSystem)
    }
}

private class OkioArkFiles(private val ark: Path, private val fileSystem: FileSystem) : ArkFiles {
    override fun datadir(network: ArkNetwork, id: WalletId): String = (ark / network.segment / id.nodeIdHash).toString()

    override fun exists(datadir: String): Boolean {
        val path = datadir.toPathOrNull() ?: return false
        return fileSystem.exists(path) && fileSystem.list(path).isNotEmpty()
    }

    override fun consented(id: WalletId): Boolean = fileSystem.exists(consentFile(id))

    override fun recordConsent(id: WalletId) {
        val file = consentFile(id)
        file.parent?.let(fileSystem::createDirectories)
        fileSystem.write(file) { writeUtf8("1\n") }
    }

    private fun consentFile(id: WalletId) = ark / "consented" / id.nodeIdHash

    private fun String.toPathOrNull(): Path? = runCatching { with(Path) { this@toPathOrNull.toPath() } }.getOrNull()
}
