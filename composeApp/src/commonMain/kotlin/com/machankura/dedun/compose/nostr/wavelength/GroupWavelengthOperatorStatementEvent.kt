package com.machankura.dedun.compose.nostr.wavelength

import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * Who runs a group's Wavelength wallet: kind 30371, FROST-signed by the room, never broadcast
 * (`docs/group-wavelength-wallet.md`, Phase 5). The term is what every co-signer fences on, so an old operator's daemon
 * that keeps running gets no signature from anybody.
 */
object GroupWavelengthOperatorStatementEvent {
    val KIND: Kind = GroupWavelengthEvents.OPERATOR_STATEMENT

    private const val D_TAG = "d"
    private const val OPERATOR_TAG = "operator"
    private const val TERM_TAG = "term"
    const val NOBODY = "none"

    data class Statement(val chain: Chain, val wallet: Long, val operator: HexKey?, val term: Long)

    fun identifier(chain: Chain, wallet: Long): String = "${GroupWalletStatementEvent.chainName(chain)}:$wallet"

    fun template(statement: Statement, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, identifier(statement.chain, statement.wallet)),
                arrayOf(OPERATOR_TAG, statement.operator ?: NOBODY),
                arrayOf(TERM_TAG, statement.term.toString()),
            ),
            content = "",
        )

    fun read(tags: Array<Array<String>>): Statement? {
        fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        val identifier = tag(D_TAG)?.split(":")?.takeIf { it.size == 2 } ?: return null
        val chain = GroupWalletStatementEvent.chainNamed(identifier[0]) ?: return null
        val wallet = identifier[1].toLongOrNull() ?: return null
        val operator = tag(OPERATOR_TAG) ?: return null
        val term = tag(TERM_TAG)?.toLongOrNull() ?: return null
        return Statement(chain, wallet, operator.takeIf { it != NOBODY }, term)
    }
}
