package com.machankura.dedun.compose.text

import fr.acinq.phoenix.utils.EmailLikeAddress
import fr.acinq.phoenix.utils.Parser

/**
 * A Lightning address, LUD-16: `name@domain`, lowercase.
 *
 * The one form a member's payout destination takes (`docs/group-payout-destination.md`,
 * D5): it can be paid any number of times without anybody handing out a fresh invoice,
 * which is the point of a standing record. Everything else that starts with `ln` or
 * holds an `@` is refused with its own reason, rather than resolved into something the
 * group's node could not pay or that pays once.
 */
data class LightningAddress(val username: String, val domain: String) {

    override fun toString(): String = "$username@$domain"

    /** Where the address's LNURL-pay intent is served: LUD-16. */
    val wellKnownUrl: String get() = "https://$domain/.well-known/lnurlp/$username"

    /** What somebody typed as a Lightning address, read -- or the reason it is not one. */
    sealed interface Parsed {
        data class Address(val address: LightningAddress) : Parsed
        data object Empty : Parsed
        /** No `@`, an `@` with nothing either side, characters LUD-16 does not allow, or more than the address. */
        data object NotAnAddress : Parsed
        /** A bech32 `lnurl1…` or a LUD-17 `lnurlp://` link: an LNURL, but not an address. */
        data object Lnurl : Parsed
        /** A BOLT12 offer: the group's node pays only BOLT11. */
        data object Offer : Parsed
        /** A BOLT11 invoice: it can be paid once, and a destination is paid again and again. */
        data object Invoice : Parsed
        /** `₿name@domain`, BIP-353: resolved over DNS, possibly to an offer. */
        data object Bip353 : Parsed
        /** A `.onion` domain, which this device cannot reach without a Tor transport. */
        data object OnionDomain : Parsed
    }

    companion object {
        private const val LIGHTNING_SCHEME = "lightning:"

        /** LUD-16's alphabet for the name: `a-z0-9-_.+`. */
        private val USERNAME = Regex("^[a-z0-9._+-]+$")

        /** One DNS label: letters, digits and inner hyphens, at most 63. */
        private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        private val INVOICE_PREFIXES = listOf("lnbcrt", "lntbs", "lntb", "lnbc", "lnsb")

        /**
         * [input] as a Lightning address: trimmed, with a leading `lightning:` dropped and
         * everything lowercased, since LUD-16 names are lowercase and domains are not case
         * sensitive. The fork's `Parser.parseEmailLikeAddress` tells BIP-353 apart; the
         * rest is LUD-16's grammar, held strictly -- a second word is refused, not ignored.
         */
        fun parse(input: String): Parsed {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return Parsed.Empty
            val stripped = if (trimmed.startsWith(LIGHTNING_SCHEME, ignoreCase = true)) trimmed.drop(LIGHTNING_SCHEME.length).trim() else trimmed
            val lower = stripped.lowercase()

            when {
                lower.startsWith("lnurl1") || lower.startsWith("lnurlp://") || lower.startsWith("lnurlp:") -> return Parsed.Lnurl
                lower.startsWith("lno1") -> return Parsed.Offer
                '@' !in lower && INVOICE_PREFIXES.any { lower.startsWith(it) } -> return Parsed.Invoice
            }

            val email = Parser.parseEmailLikeAddress(stripped) ?: return Parsed.NotAnAddress
            if (email is EmailLikeAddress.Bip353) return Parsed.Bip353
            // The fork takes the first word of the first line; anything after it is
            // something this field was not asked for.
            if (!email.source.equals(stripped, ignoreCase = true)) return Parsed.NotAnAddress

            val username = email.username.lowercase()
            val domain = email.domain.lowercase().removeSuffix(".")
            if (!USERNAME.matches(username)) return Parsed.NotAnAddress

            val labels = domain.split('.')
            if (domain.length > 253 || labels.size < 2 || labels.any { !LABEL.matches(it) }) return Parsed.NotAnAddress
            if (labels.last() == "onion") return Parsed.OnionDomain
            // A top-level label is never all digits, so an IP address is not a domain here.
            if (labels.last().all { it.isDigit() }) return Parsed.NotAnAddress

            return Parsed.Address(LightningAddress(username, domain))
        }

        /** [input] as an address, or null for any of [Parsed]'s refusals. */
        fun parseOrNull(input: String): LightningAddress? = (parse(input) as? Parsed.Address)?.address
    }
}
