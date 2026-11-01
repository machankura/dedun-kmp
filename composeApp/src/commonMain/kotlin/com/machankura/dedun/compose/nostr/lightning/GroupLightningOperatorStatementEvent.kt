package com.machankura.dedun.compose.nostr.lightning

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * Who runs the group's Lightning node: kind 30356, FROST-signed, never broadcast
 * (`docs/group-lightning-wallet.md`, Phase 7).
 *
 * The term is what every co-signer fences on. It only goes up, and a request from anyone but the
 * newest term's operator is refused, so an old node that keeps running cannot sign anything -- which
 * is the whole of "only one operator at a time" that does not depend on the old device behaving.
 */
object GroupLightningOperatorStatementEvent {
    val KIND: Kind = GroupLightningEvents.OPERATOR_STATEMENT

    private const val D_TAG = "d"
    private const val OPERATOR_TAG = "operator"
    private const val TERM_TAG = "term"
    private const val PREVIOUS_TAG = "previous"

    /** Written in place of a key when the operator steps down and nobody replaces them. */
    const val NOBODY = "none"

    data class Statement(
        val chain: Chain,
        val wallet: Long,
        /** The operator's nostr key, or null when the statement names nobody. */
        val operator: HexKey?,
        val term: Long,
        val previous: HexKey?,
    )

    fun template(statement: Statement, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = buildList {
                add(arrayOf(D_TAG, GroupLightningWalletStatementEvent.identifier(statement.chain, statement.wallet)))
                add(arrayOf(OPERATOR_TAG, statement.operator ?: NOBODY))
                add(arrayOf(TERM_TAG, statement.term.toString()))
                statement.previous?.let { add(arrayOf(PREVIOUS_TAG, it)) }
            }.toTypedArray(),
            content = "",
        )

    fun read(tags: Array<Array<String>>): Statement? {
        fun value(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        val d = value(D_TAG) ?: return null
        val chainName = d.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return null
        val chain = com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent.chainNamed(chainName) ?: return null
        val wallet = d.substringAfter(':', "").toLongOrNull() ?: return null
        val term = value(TERM_TAG)?.toLongOrNull()?.takeIf { it >= 1 } ?: return null
        val operator = value(OPERATOR_TAG) ?: return null
        return Statement(chain, wallet, operator.takeIf { it != NOBODY }, term, value(PREVIOUS_TAG))
    }
}
