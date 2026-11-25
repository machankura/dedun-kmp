package com.machankura.dedun.compose.contacts

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.Crypto

/**
 * Who a contact is to the group: one phone number or one email address, in its one canonical
 * spelling. `docs/non-member-deposits.md`, D2 and D3.
 *
 * The spelling is what the group signs and what the contact's id is a hash of, so it is never
 * edited (another number is another contact) and two builds must never spell one the same
 * person two ways. [parse] is what a signer uses to check a record: it takes only a spelling
 * that normalises to itself.
 */
sealed interface ContactIdentifier {
    /** The canonical spelling: `+27821234567` or `mum@example.com`. */
    val text: String

    /** `phone` or `email`, as the record's `identifier` tag says it. */
    val type: String

    /** The byte the contact's id hashes in front of [text], so a number and an address never share one. */
    val typeByte: Byte

    /** [text] for reading. */
    val formatted: String

    data class Phone(override val text: String) : ContactIdentifier {
        init { require(PhoneNumbers.isCanonical(text)) { "not an E.164 number" } }
        override val type: String get() = PHONE
        override val typeByte: Byte get() = 'p'.code.toByte()
        override val formatted: String get() = PhoneNumbers.formatted(text)
    }

    data class Email(override val text: String) : ContactIdentifier {
        init { require(Emails.isCanonical(text)) { "not a canonical email address" } }
        override val type: String get() = EMAIL
        override val typeByte: Byte get() = 'e'.code.toByte()
        override val formatted: String get() = text
    }

    /**
     * The contact's id in [chatRoomId]: `taggedHash("dedun/contact/v1", room ‖ type ‖ text)`.
     * Derived, so a proposer can't choose a member's key; and one per number per room, so a second
     * record for a number the group already counts has the same id and is refused as a second.
     */
    fun contactId(chatRoomId: String): HexKey =
        Crypto.taggedHash(chatRoomId.lowercase().hexToByteArray() + byteArrayOf(typeByte) + text.encodeToByteArray(), ID_TAG).toHex()

    companion object {
        const val PHONE: String = "phone"
        const val EMAIL: String = "email"

        /** Frozen once a group has signed a record: changing it gives every contact a new id. */
        const val ID_TAG: String = "dedun/contact/v1"

        /** [text] as a [type] identifier, only if it is already canonical -- what a signer checks a record with. */
        fun parse(type: String, text: String): ContactIdentifier? = when (type) {
            PHONE -> text.takeIf(PhoneNumbers::isCanonical)?.let { Phone(it) }
            EMAIL -> text.takeIf(Emails::isCanonical)?.let { Email(it) }
            else -> null
        }
    }
}

/**
 * Email addresses as one canonical spelling: trimmed and lowercased whole.
 *
 * ASCII only. Normalising Unicode (NFC) needs each platform's own normaliser, and two of them
 * disagreeing on one address would give one person two ids. An internationalised address is
 * refused rather than risked. No provider's rules: a dot in a Gmail address stays, because that
 * is Google's rule and not the address's.
 */
object Emails {
    const val MAX_LENGTH: Int = 254
    private const val MAX_LOCAL: Int = 64

    enum class Refusal { EMPTY, NOT_ASCII, NOT_AN_ADDRESS, TOO_LONG }

    sealed interface Normalised {
        data class Address(val text: String) : Normalised
        data class Refused(val why: Refusal) : Normalised
    }

    fun normalise(typed: String): Normalised {
        val text = typed.trim()
        if (text.isEmpty()) return Normalised.Refused(Refusal.EMPTY)
        if (text.any { it.code > 0x7e || it.code < 0x21 }) {
            return Normalised.Refused(if (text.any { it.code > 0x7e }) Refusal.NOT_ASCII else Refusal.NOT_AN_ADDRESS)
        }
        val lower = text.lowercase()
        if (lower.length > MAX_LENGTH) return Normalised.Refused(Refusal.TOO_LONG)
        return if (isCanonical(lower)) Normalised.Address(lower) else Normalised.Refused(Refusal.NOT_AN_ADDRESS)
    }

    /** Whether [text] is a lowercase ASCII address with one `@`, a non-empty local part and a dotted domain. */
    fun isCanonical(text: String): Boolean {
        if (text.isEmpty() || text.length > MAX_LENGTH) return false
        if (text.any { it.code > 0x7e || it.code < 0x21 || it in 'A'..'Z' }) return false
        val parts = text.split('@')
        if (parts.size != 2) return false
        val (local, domain) = parts
        if (local.isEmpty() || local.length > MAX_LOCAL) return false
        val labels = domain.split('.')
        return labels.size >= 2 && labels.all { it.isNotEmpty() }
    }
}
