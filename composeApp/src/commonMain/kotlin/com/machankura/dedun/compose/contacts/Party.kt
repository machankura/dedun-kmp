package com.machankura.dedun.compose.contacts

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Whom a deposit, a coin or a payout belongs to: a member, by their public key, or a contact the
 * group signed, by the contact's id. `docs/non-member-deposits.md`, D3.
 *
 * Both are 64 hex characters, which is why the ledger, coin shares and balances carry a contact's
 * id unchanged in the `member` columns they already have. This type is read at the boundaries --
 * the wire, the signer checks, the payout context and the screens -- so code that sends to a key
 * or looks up a profile is never handed a contact's id.
 */
sealed interface Party {
    /** The member's key or the contact's id, lowercase. */
    val id: HexKey

    /** How a payee slot spells it: the bare key for a member, `contact:<id>` for a contact. */
    val wire: String

    data class Member(override val id: HexKey) : Party {
        override val wire: String get() = id
    }

    data class Contact(override val id: HexKey) : Party {
        override val wire: String get() = "$CONTACT_PREFIX$id"
    }

    /**
     * Who this party is now: a contact the group has said a member was is that member, by
     * [claims] (contact id → member key). `docs/non-member-deposits.md`, D8.
     */
    fun resolve(claims: Map<HexKey, HexKey>): Party = when (this) {
        is Member -> this
        is Contact -> claims[id]?.let { Member(it.lowercase()) } ?: this
    }

    companion object {
        const val CONTACT_PREFIX: String = "contact:"

        private fun isHex64(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

        /** A payee slot's text as a party: 64 hex is a member, `contact:` and 64 hex a contact, anything else nobody. */
        fun parseWire(text: String): Party? {
            val lower = text.lowercase()
            return if (lower.startsWith(CONTACT_PREFIX)) lower.removePrefix(CONTACT_PREFIX).takeIf(::isHex64)?.let(::Contact)
            else lower.takeIf(::isHex64)?.let(::Member)
        }

        fun of(id: HexKey, contact: Boolean): Party = if (contact) Contact(id.lowercase()) else Member(id.lowercase())
    }
}
