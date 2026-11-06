package com.machankura.dedun.compose.wallet.wavelength.group

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * What a daemon asks its host, as the fork's `callbacksigner.Request` writes it. Everything here came from the operator's
 * device, so it is what [ArkPolicy] checks, never why it agrees. Binary fields are hex.
 */
@Serializable
data class ArkRequest(
    val kind: String,
    val family: Int = 0,
    val branch: Long = 0,
    val index: Long = 0,
    val message: String? = null,
    val tag: String? = null,
    val schnorr: Boolean = false,
    val compact: Boolean = false,
    @SerialName("double_hash") val doubleHash: Boolean = false,
    @SerialName("tap_tweak") val tapTweak: String? = null,
    val peer: String? = null,
    val tx: String? = null,
    @SerialName("prev_outs") val prevOuts: List<ArkOutput> = emptyList(),
    @SerialName("input_index") val inputIndex: Int = 0,
    val method: String? = null,
    @SerialName("merkle_root") val merkleRoot: String? = null,
    @SerialName("leaf_script") val leafScript: String? = null,
    @SerialName("leaf_version") val leafVersion: Int = 0,
    val digest: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val signers: List<String> = emptyList(),
    @SerialName("script_root") val scriptRoot: String? = null,
    @SerialName("combined_nonce") val combinedNonce: String? = null,
    val note: ArkNote? = null,
) {
    companion object {
        const val MESSAGE = "message"
        const val SHARED_KEY = "shared_key"
        const val INPUT = "input"
        const val MUSIG2_CREATE = "musig2_create"
        const val MUSIG2_NONCE = "musig2_nonce"
        const val MUSIG2_SIGN = "musig2_sign"
        const val MUSIG2_CLEANUP = "musig2_cleanup"
        const val NEXT_TREE_KEY = "next_tree_key"
        const val TREE_KEY = "tree_key"

        const val METHOD_BIP86 = "bip86"
        const val METHOD_KEY_PATH = "key_path"
        const val METHOD_SCRIPT_PATH = "script_path"

        /** The on-chain account's family, in a request: it isn't one of lnd's. */
        const val FAMILY_ONCHAIN = -1
    }
}

/** A host's answer, as `callbacksigner.Response` reads it. Binary fields are hex. */
@Serializable
data class ArkResponse(
    val signature: String? = null,
    @SerialName("shared_key") val sharedKey: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("public_nonce") val publicNonce: String? = null,
    @SerialName("pub_key") val pubKey: String? = null,
    val index: Long = 0,
)

@Serializable
data class ArkOutput(val value: Long, @SerialName("pk_script") val pkScript: String)

/** What the daemon said about a request: `signctx.Note`. */
@Serializable
data class ArkNote(val kind: String, val id: String, val material: JsonElement) {
    companion object {
        const val ROUND = "round"
        const val OOR = "oor"
        const val JOIN = "join"
    }
}

@Serializable
data class NotedNode(val tx: String, val digest: String, @SerialName("co_signers") val coSigners: List<String>, val signature: String? = null)

@Serializable
data class NotedTree(
    @SerialName("output_index") val outputIndex: Int,
    @SerialName("batch_output") val batchOutput: ArkOutput,
    @SerialName("sweep_root") val sweepRoot: String,
    val nodes: List<NotedNode>,
)

@Serializable
data class NotedVtxo(
    @SerialName("signing_key") val signingKey: String,
    @SerialName("owner_key") val ownerKey: String,
    val amount: Long,
    @SerialName("pk_script") val pkScript: String,
    @SerialName("policy_template") val policyTemplate: String = "",
)

@Serializable
data class NotedForfeit(
    @SerialName("vtxo_outpoint") val vtxoOutpoint: String,
    @SerialName("vtxo_amount") val vtxoAmount: Long,
    @SerialName("connector_outpoint") val connectorOutpoint: String,
    @SerialName("connector_pk_script") val connectorPkScript: String,
    @SerialName("connector_amount") val connectorAmount: Long,
    @SerialName("connector_path") val connectorPath: List<String>,
)

/** `round.notedRound`: a validated round, for this device to validate again. */
@Serializable
data class NotedRound(
    @SerialName("commitment_tx") val commitmentTx: String,
    @SerialName("prev_outs") val prevOuts: List<ArkOutput>,
    @SerialName("sweep_key") val sweepKey: String,
    @SerialName("sweep_delay") val sweepDelay: Long,
    val trees: List<NotedTree> = emptyList(),
    val vtxos: List<NotedVtxo> = emptyList(),
    val leaves: List<ArkOutput> = emptyList(),
    val forfeits: List<NotedForfeit> = emptyList(),
)

@Serializable
data class NotedTx(val tx: String, @SerialName("prev_outs") val prevOuts: List<ArkOutput>, val sigs: List<NotedSig> = emptyList())

/** A signature an input of a noted transaction already carries, which is worth nothing until someone verifies whose it is. */
@Serializable
data class NotedSig(val input: Int, val signature: String)

/** `oor.notedPackage`: a transfer's Ark transaction and the checkpoints that fund it. */
@Serializable
data class NotedTransfer(val ark: NotedTx, val checkpoints: List<NotedTx>)

/** `round.notedJoin`: the to_spend transaction that makes a proof of funds unspendable. */
@Serializable
data class NotedJoin(@SerialName("to_spend") val toSpend: String)
