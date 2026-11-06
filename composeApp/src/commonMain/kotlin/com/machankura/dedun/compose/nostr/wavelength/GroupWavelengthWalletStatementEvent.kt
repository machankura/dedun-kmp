package com.machankura.dedun.compose.nostr.wavelength

import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The group's statement that it keeps a Wavelength wallet: kind 30370, FROST-signed by the room through the
 * ordinary proposal flow, never broadcast (`docs/group-wavelength-wallet.md`, Phase 4).
 *
 * It binds what every co-signer checks against: which onchain wallet money leaves to, which Iceberg key holds the
 * coins and who holds it, the identity and manifest those keys and the authentication seed make, whose operator the
 * coins sit with, and what bounds the fees. A change at Ark's operator takes the group's agreement this way, rather
 * than the daemon quietly following it.
 */
object GroupWavelengthWalletStatementEvent {
    val KIND: Kind = GroupWavelengthEvents.WALLET_STATEMENT

    private const val D_TAG = "d"
    private const val CHAIN_TAG = "chain"
    private const val WALLET_TAG = "wallet"
    private const val ONCHAIN_TAG = "onchain"
    private const val ICEBERG_TAG = "iceberg"
    private const val PARTICIPANTS_TAG = "participants"
    private const val IDENTITY_TAG = "identity"
    private const val MANIFEST_TAG = "manifest"
    private const val OPERATOR_TAG = "ark_operator"
    private const val POLICY_TAG = "policy"

    /**
     * What bounds the group's automatic signatures, in sats. [maxRoundFeeSat] is the most a round may take from
     * what the group puts in without an authorisation; [maxOnchainFeeSat] the most an onchain spend may pay in fees.
     * [exitDelay] is the operator's exit delay in blocks, which every VTXO the group accepts commits to: a longer one
     * would hold its coins up for longer on the way out, so it isn't the operator's to change alone. [minSweepDelay] is the
     * shortest life a batch of the group's VTXOs may have, in blocks: a shorter one leaves less time to renew, and no time to exit.
     * The default is a guess until a real operator's terms have been read for it.
     */
    data class Policy(val maxRoundFeeSat: Long, val maxOnchainFeeSat: Long, val exitDelay: Long, val minSweepDelay: Long) {
        companion object {
            val DEFAULT = Policy(maxRoundFeeSat = 2_000, maxOnchainFeeSat = 5_000, exitDelay = 144, minSweepDelay = 1_000)
        }
    }

    data class Statement(
        val chain: Chain,
        val wallet: Long,
        /** The onchain account on [chain] that exits and sweeps pay. */
        val onchainAccount: Long,
        /** The Iceberg group key, compressed hex. */
        val icebergKey: HexKey,
        val ceremonyId: String,
        val threshold: Int,
        /** The Iceberg participants' nostr keys, sorted. */
        val participants: List<HexKey>,
        /** The wallet's identity, from the authentication seed: what binds that seed to this statement. */
        val identity: HexKey,
        /** The hash of the manifest the daemon opens with, which it reports back as `manifestHash`. */
        val manifestHash: String,
        /** The key of the Ark operator the wallet's coins sit with, compressed hex. */
        val arkOperatorKey: HexKey,
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
                arrayOf(IDENTITY_TAG, statement.identity),
                arrayOf(MANIFEST_TAG, statement.manifestHash),
                arrayOf(OPERATOR_TAG, statement.arkOperatorKey),
                arrayOf(POLICY_TAG, statement.policy.maxRoundFeeSat.toString(), statement.policy.maxOnchainFeeSat.toString(), statement.policy.exitDelay.toString(), statement.policy.minSweepDelay.toString()),
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
        val policy = tag(POLICY_TAG)?.takeIf { it.size >= 5 }?.let { p ->
            Policy(p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: return null, p[3].toLongOrNull() ?: return null, p[4].toLongOrNull() ?: return null)
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
            identity = tag(IDENTITY_TAG)?.getOrNull(1) ?: return null,
            manifestHash = tag(MANIFEST_TAG)?.getOrNull(1) ?: return null,
            arkOperatorKey = tag(OPERATOR_TAG)?.getOrNull(1) ?: return null,
            policy = policy,
        )
    }
}
