// The ios actuals with one import changed: BusinessManager, ported to the library's jvmMain.
// The seed writer that used to be the bulk of this file was byte-identical on all three
// platforms and now lives once, in commonMain, as IdentityWriter.writeMnemonic.
//
// What is left is kept as a copy for the same reason the ported BusinessManager is: sharing
// it means an intermediate source set between iosMain and jvmMain, which is a change to how
// the module is wired rather than to what it does.

package com.machankura.dedun.compose.ui.view.model

import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.DecryptSeedResult
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.jvm.BusinessManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.extensions.JvmGlobalPrefs


actual fun updateBusinessActiveInUI(walletId: WalletId) {
    BusinessManager.updateBusinessActiveInUI(walletId)
}

actual fun stopPlatformBusiness(walletId: WalletId) {
    BusinessManager.stopBusiness(walletId)
}

actual fun loadAndDecryptSeed(phoenixGlobal: PhoenixGlobal): DecryptSeedResult {
    return SeedManager.loadAndDecrypt(
        phoenixGlobal
    )
}

actual fun getAvailableWalletsMeta(phoenixGlobal: PhoenixGlobal): Flow<Map<WalletId, UserWalletMetadata>> {
    return JvmGlobalPrefs.of(phoenixGlobal).getAvailableWalletsMeta
}

actual suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    metadata: UserWalletMetadata
) {
    JvmGlobalPrefs.of(phoenixGlobal).saveAvailableWalletMeta(metadata)
}

actual suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    walletId: WalletId,
    name: String?,
    avatar: String,
    isHidden: Boolean
) {
    JvmGlobalPrefs.of(phoenixGlobal).saveAvailableWalletMeta(
        walletId = walletId,
        name = name,
        avatar = avatar,
        isHidden = isHidden
    )
}
