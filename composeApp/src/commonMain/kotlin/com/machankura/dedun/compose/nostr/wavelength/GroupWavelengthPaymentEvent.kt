package com.machankura.dedun.compose.nostr.wavelength

import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * A quorum of a group agreeing that its Wavelength wallet pays one Lightning invoice: kind 30379, FROST-signed by the room, never
 * broadcast (`docs/group-wavelength-wallet.md`, Phase 11). This is what stands between whoever runs the daemon and the group's money,
 * since a co-signer pays nothing else. Everything is read from the invoice, so nobody can approve one thing and have it mean another.
 */
object GroupWavelengthPaymentEvent {
    val KIND: Kind = GroupWavelengthEvents.AUTHORISATION

    private const val D_TAG = "d"
    private const val INVOICE_TAG = "invoice"
    private const val MAX_FEE_TAG = "max_fee_sat"
    private const val MAX_REFUND_TAG = "max_refund_height"
    private const val EXPIRES_TAG = "expires_at"
    private const val PAYOUT_TAG = "payout"

    /** Whose balance it comes from, and the destination of theirs it pays: the only thing the group's wallet pays (`docs/payouts-only.md`). */
    data class Payout(val member: String, val destinationEventId: String)

    data class Payment(val chain: Chain, val wallet: Long, val invoice: String, val maxFeeSat: Long, val maxRefundHeight: Long, val expiresAt: Long, val payout: Payout? = null) {
        private val decoded: Pair<ByteVector32, Long?>? get() = readInvoice(invoice, chain)

        val paymentHash: ByteVector32? get() = decoded?.first

        /** What the invoice asks for, in whole sats, rounded up: an invoice with no amount can't be approved, since no budget would mean anything. */
        val amountSat: Long? get() = decoded?.second?.let { (it + 999) / 1000 }

        val maxSat: Long? get() = amountSat?.plus(maxFeeSat)
    }

    private fun prefixOf(chain: Chain): String? = when (chain) {
        Chain.Mainnet -> "lnbc"
        Chain.Regtest -> "lnbcrt"
        Chain.Signet -> "lntbs"
        else -> null
    }

    /**
     * An invoice's payment hash and amount in millisatoshi, for this chain. `Bolt11Invoice` knows no signet prefix, and reads signet's as
     * testnet's with an amount that doesn't parse, so what a budget depends on is read here, from BOLT 11 itself. Null for anything
     * that isn't such an invoice, or has no hash; a null amount is an invoice that names none.
     */
    private fun readInvoice(invoice: String, chain: Chain): Pair<ByteVector32, Long?>? = runCatching {
        val (hrp, words, _) = Bech32.decode(invoice.lowercase(), noChecksum = false)
        val amount = hrp.removePrefix(prefixOf(chain)!!).takeIf { hrp.startsWith(prefixOf(chain)!!) } ?: return null
        val millisatoshi = when {
            amount.isEmpty() -> null
            amount.last().isDigit() -> scaled(amount, 100_000_000_000) ?: return null
            amount.last() == 'm' -> scaled(amount.dropLast(1), 100_000_000) ?: return null
            amount.last() == 'u' -> scaled(amount.dropLast(1), 100_000) ?: return null
            amount.last() == 'n' -> scaled(amount.dropLast(1), 100) ?: return null
            amount.last() == 'p' -> amount.dropLast(1).toLong().also { require(it % 10 == 0L) } / 10
            else -> return null
        }
        // A timestamp of seven words, tagged fields, and a signature of 104 words. Tag 1 is the payment hash, 52 words of it.
        var at = 7
        val end = words.size - 104
        while (at + 3 <= end) {
            val tag = words[at].toInt()
            val length = words[at + 1].toInt() * 32 + words[at + 2].toInt()
            if (tag == 1 && length == 52) {
                var bits = 0
                var buffer = 0
                val hash = ArrayList<Byte>(32)
                for (i in at + 3 until at + 3 + 52) {
                    buffer = (buffer shl 5) or words[i].toInt()
                    bits += 5
                    if (bits >= 8) {
                        bits -= 8
                        hash += ((buffer shr bits) and 0xff).toByte()
                    }
                }
                return ByteVector32(hash.take(32).toByteArray()) to millisatoshi
            }
            at += 3 + length
        }
        return null
    }.getOrNull()

    /** [amount] times [scale], or null past all the bitcoin there is: an amount that overflowed would come out negative, and pass any limit. */
    private fun scaled(amount: String, scale: Long): Long? = amount.toLong().takeIf { it in 0..MAX_MSAT / scale }?.let { it * scale }

    private const val MAX_MSAT = 2_100_000_000_000_000_000L
    private const val MAX_SAT = MAX_MSAT / 1000

    fun template(payment: Payment, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, "${GroupWalletStatementEvent.chainName(payment.chain)}:${payment.wallet}:${payment.paymentHash}"),
                arrayOf(INVOICE_TAG, payment.invoice),
                arrayOf(MAX_FEE_TAG, payment.maxFeeSat.toString()),
                arrayOf(MAX_REFUND_TAG, payment.maxRefundHeight.toString()),
                arrayOf(EXPIRES_TAG, payment.expiresAt.toString()),
            ) + listOfNotNull(payment.payout?.let { arrayOf(PAYOUT_TAG, it.member, it.destinationEventId) }),
            content = "",
        )

    fun read(tags: Array<Array<String>>): Payment? {
        fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        val identifier = tag(D_TAG)?.split(":")?.takeIf { it.size == 3 } ?: return null
        val chain = GroupWalletStatementEvent.chainNamed(identifier[0]) ?: return null
        val payment = Payment(
            chain = chain,
            wallet = identifier[1].toLongOrNull() ?: return null,
            invoice = tag(INVOICE_TAG) ?: return null,
            maxFeeSat = tag(MAX_FEE_TAG)?.toLongOrNull()?.takeIf { it in 0..MAX_SAT } ?: return null,
            maxRefundHeight = tag(MAX_REFUND_TAG)?.toLongOrNull() ?: return null,
            expiresAt = tag(EXPIRES_TAG)?.toLongOrNull() ?: return null,
            payout = tags.firstOrNull { it.size >= 3 && it[0] == PAYOUT_TAG }?.let { Payout(it[1], it[2]) },
        )
        // A label can't say one hash while the invoice says another, and an invoice with no hash or amount approves nothing.
        return payment.takeIf { it.paymentHash?.toString() == identifier[2] && it.amountSat != null && it.maxSat in 1..MAX_SAT }
    }
}
