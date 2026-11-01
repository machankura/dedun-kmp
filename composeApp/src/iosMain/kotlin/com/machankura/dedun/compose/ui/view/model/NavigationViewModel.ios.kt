package com.machankura.dedun.compose.ui.view.model

import fr.acinq.bitcoin.Chain
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.DecryptSeedResult
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.ios.BusinessManager
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import kotlinx.coroutines.flow.Flow


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
    return DataStoreManager(
        phoenixGlobal.ctx,
        chain = Chain.Mainnet
    ).loadGlobalPrefsForWallet().getAvailableWalletsMeta
}

actual suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    metadata: UserWalletMetadata
) {
    DataStoreManager(
        phoenixGlobal.ctx,
        chain = Chain.Mainnet
    ).loadGlobalPrefsForWallet().saveAvailableWalletMeta(metadata)
}

actual suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    walletId: WalletId,
    name: String?,
    avatar: String,
    isHidden: Boolean
) {
    DataStoreManager(
        phoenixGlobal.ctx,
        chain = Chain.Mainnet
    ).loadGlobalPrefsForWallet().saveAvailableWalletMeta(
        walletId = walletId,
        name = name,
        avatar = avatar,
        isHidden = isHidden
    )
}
