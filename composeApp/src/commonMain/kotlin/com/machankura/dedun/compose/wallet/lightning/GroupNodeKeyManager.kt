package com.machankura.dedun.compose.wallet.lightning

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.KeyPath
import fr.acinq.lightning.crypto.ChannelKeys
import fr.acinq.lightning.crypto.FundingSigner
import fr.acinq.lightning.crypto.GroupBasepoints
import fr.acinq.lightning.crypto.KeyManager

/**
 * The key manager a group Lightning wallet's node runs on (`docs/group-lightning-wallet.md`, Phase 8):
 * the node seed's own keys for everything but three things. Every channel's funding key is the group's
 * Iceberg key, held by [fundingSigner]; its payment and delayed-payment basepoints are the group's FROST
 * keys ([basepoints]); and it closes to [finalScriptPubKey], the group's onchain wallet.
 */
class GroupNodeKeyManager(
    private val delegate: KeyManager,
    private val fundingSigner: FundingSigner,
    private val basepoints: GroupBasepoints,
    private val finalScriptPubKey: ByteVector,
) : KeyManager by delegate {
    override fun channelKeys(fundingKeyPath: KeyPath): ChannelKeys =
        delegate.channelKeys(fundingKeyPath).withFundingSigner(fundingSigner).withGroupBasepoints(basepoints)

    override val defaultFinalScriptPubKey: ByteVector get() = finalScriptPubKey
}
