package com.machankura.dedun.compose.nostr.wavelength

import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.PayoutAddress
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * A quorum of a group agreeing that its Wavelength wallet pays one member onchain, as a leave in an Ark round: kind 30379, FROST-signed
 * by the room, never broadcast (`docs/group-wavelength-payouts.md`, W2). Unlike a Lightning payout, a device can name the payee exactly,
 * so a co-signer pays this leave only to [Payout.address] and only [Payout.amountSat] of it, once.
 *
 * Neither [GroupWavelengthPaymentEvent.read] nor [GroupWavelengthExitEvent.read] reads one, so a device that knows only those refuses
 * to sign it.
 */
object GroupWavelengthOnchainPayoutEvent {
    val KIND: Kind = GroupWavelengthEvents.AUTHORISATION

    private const val D_TAG = "d"
    private const val TYPE_TAG = "type"
    private const val TYPE_PAYOUT_ONCHAIN = "payout-onchain"
    private const val PAYOUT_TAG = "payout"
    private const val ADDRESS_TAG = "address"
    private const val AMOUNT_TAG = "amount_sat"
    private const val MAX_FEE_TAG = "max_fee_sat"
    private const val EXPIRES_TAG = "expires_at"

    private const val MAX_SAT = 21_000_000L * 100_000_000L

    data class Payout(
        val chain: Chain,
        val wallet: Long,
        val member: HexKey,
        val destinationEventId: String,
        /** The member's onchain address as their destination says it, which is what a leave has to pay. */
        val address: String,
        /** What arrives at [address], exactly: the daemon fixes a leave and takes the fee from the change. */
        val amountSat: Long,
        /** The most the round that pays it may cost, which the member is charged as well as [amountSat]. */
        val maxFeeSat: Long,
        val expiresAt: Long,
        val askedAt: Long,
    ) {
        /** What the payout is known by, everywhere: its approval's `d`. */
        val id: String get() = "$TYPE_PAYOUT_ONCHAIN:${GroupWalletStatementEvent.chainName(chain)}:$wallet:${member.lowercase()}:$askedAt"

        /** The script a leave pays, or null for an address that isn't one on [chain]. */
        val script: ByteVector? get() = PayoutAddress.parseOrNull(chain, address)?.takeIf { it.address == address }?.script

        /** The most the member's balance is charged with. */
        val maxSat: Long get() = amountSat + maxFeeSat
    }

    fun template(payout: Payout): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = payout.askedAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, payout.id),
                arrayOf(TYPE_TAG, TYPE_PAYOUT_ONCHAIN),
                arrayOf(PAYOUT_TAG, payout.member, payout.destinationEventId),
                arrayOf(ADDRESS_TAG, payout.address),
                arrayOf(AMOUNT_TAG, payout.amountSat.toString()),
                arrayOf(MAX_FEE_TAG, payout.maxFeeSat.toString()),
                arrayOf(EXPIRES_TAG, payout.expiresAt.toString()),
            ),
            content = "",
        )

    fun read(tags: Array<Array<String>>): Payout? {
        fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        if (tag(TYPE_TAG) != TYPE_PAYOUT_ONCHAIN) return null
        val identifier = tag(D_TAG)?.split(":")?.takeIf { it.size == 5 && it[0] == TYPE_PAYOUT_ONCHAIN } ?: return null
        val payout = tags.firstOrNull { it.size >= 3 && it[0] == PAYOUT_TAG } ?: return null
        val read = Payout(
            chain = GroupWalletStatementEvent.chainNamed(identifier[1]) ?: return null,
            wallet = identifier[2].toLongOrNull() ?: return null,
            member = payout[1],
            destinationEventId = payout[2],
            address = tag(ADDRESS_TAG) ?: return null,
            amountSat = tag(AMOUNT_TAG)?.toLongOrNull()?.takeIf { it in 1..MAX_SAT } ?: return null,
            maxFeeSat = tag(MAX_FEE_TAG)?.toLongOrNull()?.takeIf { it in 0..MAX_SAT } ?: return null,
            expiresAt = tag(EXPIRES_TAG)?.toLongOrNull() ?: return null,
            askedAt = identifier[4].toLongOrNull() ?: return null,
        )
        // The label names the member the payout tag does, and the address is one on this chain, written as it will be compared.
        return read.takeIf { it.member.equals(identifier[3], ignoreCase = true) && it.script != null && it.maxSat <= MAX_SAT }
    }

    fun paying(chain: Chain, wallet: Long, member: HexKey, destinationEventId: String, address: String, amountSat: Long, maxFeeSat: Long, now: Long = Clock.System.now().epochSeconds): Payout =
        Payout(chain, wallet, member.lowercase(), destinationEventId, address, amountSat, maxFeeSat, now + LASTS_SECONDS, now)

    /** Long enough for a round with enough of the group's devices online, and short of the seven days a device lets anything stand. */
    private const val LASTS_SECONDS = 2L * 24 * 60 * 60
}
