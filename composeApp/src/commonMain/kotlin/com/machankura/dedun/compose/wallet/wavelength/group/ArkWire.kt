package com.machankura.dedun.compose.wallet.wavelength.group

import kotlinx.serialization.Serializable

/**
 * What the operator's device and the members' co-signers say to each other about a Wavelength wallet
 * (`docs/group-wavelength-wallet.md`, Phase 6), as JSON inside Marmot inner events. A round asks for many signatures at once, so
 * requests, echoes and shares travel in batches, or a five-VTXO round is dozens of messages against a deadline.
 */
object ArkWire {
    /** Which share and pool a label belongs to: the group's own, or one tree key's. */
    @Serializable
    data class Domain(val child: Long? = null)

    @Serializable
    data class TreeProvision(val child: Long, val publicShare: String, val nonces: List<String>)

    /** Round one, ahead of time: a window of labels, and a window of tree keys. */
    @Serializable
    data class Provision(val wallet: String, val plainFrom: Long, val plain: List<String>, val trees: List<TreeProvision>)

    /** One signature wanted. [contributions] are the group's own round one, and [cosignerNonce] everyone else's aggregate. */
    @Serializable
    data class SigningItem(
        val requestId: String,
        val domain: Domain,
        val counter: Long,
        val request: ArkRequest,
        val contributions: List<String>,
        val cosignerNonce: String? = null,
    )

    /** Everything the operator wants right now, with how much round one it wants the co-signers to have published. */
    @Serializable
    data class SigningBatch(
        val wallet: String,
        val term: Long,
        val items: List<SigningItem> = emptyList(),
        val provisionPlainTo: Long? = null,
        val provisionTreeTo: Long? = null,
    )

    @Serializable
    data class EchoItem(val requestId: String, val label: String, val messageHash: String)

    @Serializable
    data class EchoBatch(val wallet: String, val items: List<EchoItem>)

    @Serializable
    data class ShareItem(val requestId: String, val label: String, val share: String)

    @Serializable
    data class ShareBatch(val wallet: String, val items: List<ShareItem>)

    /** How far this device has answered, or a question to the others about how far they have. */
    @Serializable
    data class HighWater(val wallet: String, val asking: Boolean, val highest: Long)
}
