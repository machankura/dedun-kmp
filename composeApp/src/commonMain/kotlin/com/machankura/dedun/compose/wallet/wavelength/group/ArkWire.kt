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

    /**
     * Round one, ahead of time: a window of labels, and a window of tree keys. And what of others' never reached this device: a message
     * lost to MLS's skipped keys is gone for good (`docs/mls-skipped-keys.md`), and only its author can say it again.
     */
    @Serializable
    data class Provision(
        val wallet: String,
        val plainFrom: Long,
        val plain: List<String>,
        val trees: List<TreeProvision>,
        val wanted: List<Wanted> = emptyList(),
        /** The ask this says again for. Otherwise it's the first publication word for word, and one in the same second is lost as a repeat. */
        val answers: String? = null,
    )

    /** [participant]'s round one, again: labels from [plainFrom], tree keys from [childFrom]. */
    @Serializable
    data class Wanted(val participant: String, val plainFrom: Long? = null, val childFrom: Long? = null)

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
        /** Asks a device that doesn't know how far the group has answered to find out now. */
        val askFloor: Boolean = false,
    )

    @Serializable
    data class EchoItem(val requestId: String, val label: String, val messageHash: String)

    @Serializable
    data class EchoBatch(val wallet: String, val items: List<EchoItem>)

    @Serializable
    data class ShareItem(val requestId: String, val label: String, val share: String)

    @Serializable
    data class ShareBatch(val wallet: String, val items: List<ShareItem>)

    /** How far this device has answered, in labels and in tree keys, or a question to the others about how far they have. */
    @Serializable
    data class HighWater(val wallet: String, val asking: Boolean, val highest: Long, val highestChild: Long = -1,
        /** The question this answers. Two answers are otherwise the same message, and one to a later question would be lost as a repeat. */
        val answers: String? = null,
    )
}
