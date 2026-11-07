package com.machankura.dedun.compose.nostr.wavelength

import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * A quorum of a group asking its Wavelength wallet to leave for the group's own onchain wallet: kind 30379, FROST-signed by the room
 * (`docs/group-wavelength-ui.md`, Phase 8). A device would sign such a leave anyway, since it pays only the group's own addresses (D9).
 * This is how anyone in the group gets it done, rather than only whoever runs the wallet.
 */
object GroupWavelengthExitEvent {
    val KIND: Kind = GroupWavelengthEvents.AUTHORISATION

    private const val D_TAG = "d"
    private const val TYPE_TAG = "type"
    private const val TYPE_EXIT = "exit"
    private const val EXPIRES_TAG = "expires_at"

    data class Exit(val chain: Chain, val wallet: Long, val expiresAt: Long, val askedAt: Long)

    fun template(exit: Exit): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = exit.askedAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, "exit:${GroupWalletStatementEvent.chainName(exit.chain)}:${exit.wallet}:${exit.askedAt}"),
                arrayOf(TYPE_TAG, TYPE_EXIT),
                arrayOf(EXPIRES_TAG, exit.expiresAt.toString()),
            ),
            content = "",
        )

    fun read(tags: Array<Array<String>>): Exit? {
        fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        if (tag(TYPE_TAG) != TYPE_EXIT) return null
        val identifier = tag(D_TAG)?.split(":")?.takeIf { it.size == 4 && it[0] == "exit" } ?: return null
        return Exit(
            chain = GroupWalletStatementEvent.chainNamed(identifier[1]) ?: return null,
            wallet = identifier[2].toLongOrNull() ?: return null,
            askedAt = identifier[3].toLongOrNull() ?: return null,
            expiresAt = tag(EXPIRES_TAG)?.toLongOrNull() ?: return null,
        )
    }

    fun asking(chain: Chain, wallet: Long, now: Long = Clock.System.now().epochSeconds): Exit = Exit(chain, wallet, now + LASTS_SECONDS, now)

    /** Long enough for a round to come along with enough of the group's devices online. */
    private const val LASTS_SECONDS = 24L * 60 * 60
}
