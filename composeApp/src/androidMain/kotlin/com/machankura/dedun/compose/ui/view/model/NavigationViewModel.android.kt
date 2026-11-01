package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.android.BennuApplication
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.android.BusinessManager
import fr.acinq.phoenix.data.DecryptSeedResult
import fr.acinq.phoenix.data.WalletId
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
    val machankuraApplication = phoenixGlobal.ctx.applicationContext as BennuApplication
    return machankuraApplication.getGlobalPrefs().getAvailableWalletsMeta
}

actual suspend fun saveAvailableWalletMeta(phoenixGlobal: PhoenixGlobal, metadata: UserWalletMetadata) {
    val machankuraApplication = phoenixGlobal.ctx.applicationContext as BennuApplication
    machankuraApplication.getGlobalPrefs().saveAvailableWalletMeta(metadata)
}

actual suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    walletId: WalletId,
    name: String?,
    avatar: String,
    isHidden: Boolean
) {
    val machankuraApplication = phoenixGlobal.ctx.applicationContext as BennuApplication
    machankuraApplication.getGlobalPrefs().saveAvailableWalletMeta(
        walletId = walletId,
        name = name,
        avatar = avatar,
        isHidden = isHidden
    )
}
