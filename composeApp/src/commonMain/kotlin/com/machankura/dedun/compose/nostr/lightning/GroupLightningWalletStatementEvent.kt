package com.machankura.dedun.compose.nostr.lightning

import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The group's statement that it keeps a Lightning wallet: kind 30355, FROST-signed by the room through
 * the ordinary proposal flow, never broadcast (`docs/group-lightning-wallet.md`, Phase 6).
 *
 * It binds together everything a co-signer checks before it answers for a channel: which onchain wallet
 * the Lightning wallet closes to, which Iceberg key funds its channels and who holds it, which node the
 * wallet's seed produces, which LSP the node opens channels with, and the policy that bounds what the
 * node can put at risk. Like the onchain statement it is a record that the group agreed, and every
 * value a reader can recompute it recomputes rather than believes.
 */
object GroupLightningWalletStatementEvent {
    val KIND: Kind = GroupLightningEvents.WALLET_STATEMENT

    private const val D_TAG = "d"
    private const val CHAIN_TAG = "chain"
    private const val WALLET_TAG = "wallet"
    private const val ONCHAIN_TAG = "onchain"
    private const val ICEBERG_TAG = "iceberg"
    private const val PARTICIPANTS_TAG = "participants"
    private const val NODE_TAG = "node"
    private const val LSP_TAG = "lsp"
    private const val POLICY_TAG = "policy"

    /**
     * What bounds the node, in sats. [inFlightCapSat] is the most that can be in HTLCs at once, which is
     * the most the node seed is ever worth (the trust table in the plan); [commitmentFeeAllowanceSat] is
     * how far our balance may fall to commitment fees between authorisations; [maxLiquidityFeeSat] is the
     * most the LSP may take from one incoming payment to open or grow a channel.
     */
    data class Policy(val inFlightCapSat: Long, val commitmentFeeAllowanceSat: Long, val maxLiquidityFeeSat: Long) {
        companion object {
            val DEFAULT = Policy(inFlightCapSat = 200_000, commitmentFeeAllowanceSat = 5_000, maxLiquidityFeeSat = 10_000)
        }
    }

    data class Statement(
        val chain: Chain,
        val wallet: Long,
        /** The onchain account on [chain] the wallet closes to. */
        val onchainAccount: Long,
        /** The Iceberg group key, compressed hex. */
        val icebergKey: HexKey,
        val ceremonyId: String,
        val threshold: Int,
        /** The Iceberg participants' nostr keys, sorted. */
        val participants: List<HexKey>,
        val nodeId: HexKey,
        val lspNodeId: HexKey,
        val policy: Policy,
    )

    /** One statement per network and wallet number, the newest winning. */
    fun identifier(chain: Chain, wallet: Long): String = "${GroupWalletStatementEvent.chainName(chain)}:$wallet"

    fun template(statement: Statement, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, identifier(statement.chain, statement.wallet)),
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(statement.chain)),
                arrayOf(WALLET_TAG, statement.wallet.toString()),
                arrayOf(ONCHAIN_TAG, statement.onchainAccount.toString()),
                arrayOf(ICEBERG_TAG, statement.icebergKey, statement.ceremonyId, statement.threshold.toString()),
                arrayOf(PARTICIPANTS_TAG) + statement.participants.toTypedArray(),
                arrayOf(NODE_TAG, statement.nodeId),
                arrayOf(LSP_TAG, statement.lspNodeId),
                arrayOf(POLICY_TAG, statement.policy.inFlightCapSat.toString(), statement.policy.commitmentFeeAllowanceSat.toString(), statement.policy.maxLiquidityFeeSat.toString()),
            ),
            content = "",
        )

    /** The statement's fields, or null when any is missing or unreadable. Nothing is verified here. */
    fun read(tags: Array<Array<String>>): Statement? {
        fun tag(name: String): Array<String>? = tags.firstOrNull { it.isNotEmpty() && it[0] == name }
        val chain = tag(CHAIN_TAG)?.getOrNull(1)?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        val wallet = tag(WALLET_TAG)?.getOrNull(1)?.toLongOrNull() ?: return null
        if (tag(D_TAG)?.getOrNull(1) != identifier(chain, wallet)) return null
        val iceberg = tag(ICEBERG_TAG)?.takeIf { it.size >= 4 } ?: return null
        val policy = tag(POLICY_TAG)?.takeIf { it.size >= 4 }?.let { p ->
            Policy(p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: return null, p[3].toLongOrNull() ?: return null)
        } ?: return null
        val participants = tag(PARTICIPANTS_TAG)?.drop(1)?.takeIf { it.isNotEmpty() } ?: return null
        if (participants != participants.sorted()) return null
        return Statement(
            chain = chain,
            wallet = wallet,
            onchainAccount = tag(ONCHAIN_TAG)?.getOrNull(1)?.toLongOrNull() ?: return null,
            icebergKey = iceberg[1],
            ceremonyId = iceberg[2],
            threshold = iceberg[3].toIntOrNull() ?: return null,
            participants = participants,
            nodeId = tag(NODE_TAG)?.getOrNull(1) ?: return null,
            lspNodeId = tag(LSP_TAG)?.getOrNull(1) ?: return null,
            policy = policy,
        )
    }
}
