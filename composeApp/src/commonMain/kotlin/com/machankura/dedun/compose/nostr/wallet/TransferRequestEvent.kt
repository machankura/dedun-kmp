package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.nostr.MovedKinds
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import kotlin.time.Clock

/**
 * A member asking the group to move part of their balance to another member: `docs/member-transfers.md`, T2.
 *
 * ```
 * kind:    30342
 * pubkey:  <the giver>
 * content: ""
 * tags:
 *   ["d",          "<room id>:<chain>:<rail>:<asked_at>"]
 *   ["h",          <room id>]
 *   ["chain",      "mainnet" | "signet" | …]
 *   ["rail",       "wavelength" | "lightning" | "onchain"]
 *   ["p",          <the receiver>]
 *   ["amount_sat", <sats>]
 *   ["expires_at", <unix seconds>]
 *   ["note",       "<free text>"]                optional
 * ```
 *
 * or, taking back one not yet signed, `["d", "<room id>:<chain>:withdrawn:<request id>"]`, `h`, `chain` and
 * `["withdrawn", <request id>]`.
 *
 * ### Signed by the giver, not the group
 *
 * Payouts-only rests on a quorum being unable to move a member's money anywhere but to that member. A transfer the group
 * could sign alone would let any `t` members move a balance to themselves and pay it out. So the giver signs, and the
 * group's transfer (30343) embeds this whole, where every device -- and a member who joins years later -- checks it.
 *
 * ### Signed, and carried whole
 *
 * An inner event's authority is the MLS frame, which a later reader of the group's transfer doesn't have. So the room
 * carries an inner event of this kind whose content is the signed request, whole: [carried], as
 * [GroupPayoutDestinationEvent] does.
 *
 * ### Why 30392, and 30342 before it
 *
 * After the coin assignment, 30341. Nothing used 30342 to 30348.
 *
 * It was 30342 until Giza and Bennu took that number for a share refresh round. It moved to
 * 30392 (docs/giza-into-dedun.md, Phase K), and 30342 is its legacy number: an event signed
 * under it keeps it, and every reader takes either through [com.machankura.dedun.compose.nostr.MovedKinds].
 */
object TransferRequestEvent {
    val KIND: Kind = 30392

    private const val D_TAG = "d"
    private const val ROOM_TAG = "h"
    private const val CHAIN_TAG = "chain"
    private const val RAIL_TAG = "rail"
    private const val RECEIVER_TAG = "p"
    private const val AMOUNT_TAG = "amount_sat"
    private const val EXPIRES_TAG = "expires_at"
    private const val NOTE_TAG = "note"
    private const val WITHDRAWN_TAG = "withdrawn"
    private const val WITHDRAWN = "withdrawn"

    /** The longest note, in characters: a line in the transcript, not a letter. */
    const val MAX_NOTE_CHARS: Int = 140

    /**
     * The least a transfer moves, on every rail: the dust limit both payout rules use. A smaller balance could never be paid
     * out alone, so a transfer below it would only make balances that can never leave (T5).
     */
    const val MIN_TRANSFER_SAT: Long = 546

    /** The most a transfer can name: every bitcoin there will ever be, so no sum of transfers overflows a balance. */
    const val MAX_TRANSFER_SAT: Long = 2_100_000_000_000_000

    /** Which of the group's wallets a balance is in. A transfer stays on one. */
    enum class Rail(val wire: String) {
        WAVELENGTH("wavelength"),
        LIGHTNING("lightning"),
        ONCHAIN("onchain");

        companion object {
            fun named(wire: String): Rail? = entries.firstOrNull { it.wire == wire }
        }
    }

    sealed interface Statement {
        val giver: HexKey
        val chatRoomId: String
        val chain: Chain
        val event: Event
        val createdAt: Long get() = event.createdAt
    }

    data class Request(
        override val giver: HexKey,
        override val chatRoomId: String,
        override val chain: Chain,
        val rail: Rail,
        val receiver: HexKey,
        val amountSat: Long,
        val expiresAt: Long,
        val note: String?,
        override val event: Event,
    ) : Statement {
        val id: String get() = event.id
    }

    data class Withdrawal(
        override val giver: HexKey,
        override val chatRoomId: String,
        override val chain: Chain,
        val requestId: String,
        override val event: Event,
    ) : Statement

    /** [amountSat] of the balance [giverPrivateKey] holds in [chatRoomId] on [rail], to [receiver], signed by the giver. */
    fun build(
        giverPrivateKey: PrivateKey,
        chatRoomId: String,
        chain: Chain,
        rail: Rail,
        receiver: HexKey,
        amountSat: Long,
        expiresAt: Long,
        note: String? = null,
        createdAt: Long = Clock.System.now().epochSeconds,
    ): Event {
        require(amountSat in MIN_TRANSFER_SAT..MAX_TRANSFER_SAT) { "not an amount a transfer may move" }
        require(expiresAt > createdAt) { "expires before it is asked" }
        val written = note?.trim()?.takeIf { it.isNotEmpty() }
        require(written == null || written.length <= MAX_NOTE_CHARS) { "a note longer than $MAX_NOTE_CHARS characters" }
        val room = chatRoomId.lowercase()
        return signed(
            giverPrivateKey,
            createdAt,
            listOfNotNull(
                arrayOf(D_TAG, "$room:${GroupWalletStatementEvent.chainName(chain)}:${rail.wire}:$createdAt"),
                arrayOf(ROOM_TAG, room),
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(chain)),
                arrayOf(RAIL_TAG, rail.wire),
                arrayOf(RECEIVER_TAG, receiver.lowercase()),
                arrayOf(AMOUNT_TAG, amountSat.toString()),
                arrayOf(EXPIRES_TAG, expiresAt.toString()),
                written?.let { arrayOf(NOTE_TAG, it) },
            ).toTypedArray(),
        )
    }

    /** That the giver [giverPrivateKey] holds takes back [requestId], which the group hasn't signed yet (T9). */
    fun buildWithdrawn(
        giverPrivateKey: PrivateKey,
        chatRoomId: String,
        chain: Chain,
        requestId: String,
        createdAt: Long = Clock.System.now().epochSeconds,
    ): Event {
        val room = chatRoomId.lowercase()
        return signed(
            giverPrivateKey,
            createdAt,
            arrayOf(
                arrayOf(D_TAG, "$room:${GroupWalletStatementEvent.chainName(chain)}:$WITHDRAWN:${requestId.lowercase()}"),
                arrayOf(ROOM_TAG, room),
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(chain)),
                arrayOf(WITHDRAWN_TAG, requestId.lowercase()),
            ),
        )
    }

    private fun signed(privateKey: PrivateKey, createdAt: Long, tags: Array<Array<String>>): Event {
        val giver = privateKey.xOnlyPublicKey().value.toHex()
        val kind = MovedKinds.written(KIND)
        val id = EventHasher.hashId(giver, createdAt, kind, tags, "")
        val sig = Crypto.signSchnorr(ByteVector32(id), privateKey, null)
        return Event(id = id, pubKey = giver, createdAt = createdAt, kind = kind, tags = tags, content = "", sig = sig.toHex())
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * What [event] says, by its shape alone, or null when a tag is missing, doubled, malformed, or disagrees with another.
     * The signature is [verified]'s.
     */
    fun read(event: Event): Statement? = runCatching {
        if (!MovedKinds.isKind(event.kind, KIND) || event.content.isNotEmpty()) return null
        fun all(name: String) = event.tags.filter { it.isNotEmpty() && it[0] == name }
        fun single(name: String): String? = all(name).singleOrNull()?.takeIf { it.size >= 2 }?.get(1)

        val giver = event.pubKey.lowercase()
        val room = single(ROOM_TAG)?.takeIf { HEX64.matches(it) } ?: return null
        val chain = single(CHAIN_TAG)?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        val chainName = GroupWalletStatementEvent.chainName(chain)
        val d = single(D_TAG) ?: return null

        val withdrawn = all(WITHDRAWN_TAG)
        if (withdrawn.isNotEmpty()) {
            val requestId = single(WITHDRAWN_TAG)?.takeIf { HEX64.matches(it) } ?: return null
            if (listOf(RAIL_TAG, RECEIVER_TAG, AMOUNT_TAG, EXPIRES_TAG, NOTE_TAG).any { all(it).isNotEmpty() }) return null
            if (d != "$room:$chainName:$WITHDRAWN:$requestId") return null
            return Withdrawal(giver, room, chain, requestId, event)
        }

        val rail = single(RAIL_TAG)?.let(Rail::named) ?: return null
        val receiver = single(RECEIVER_TAG)?.takeIf { HEX64.matches(it) && it != giver } ?: return null
        val amount = single(AMOUNT_TAG)?.let { written -> written.toLongOrNull()?.takeIf { it.toString() == written } }
            ?.takeIf { it in MIN_TRANSFER_SAT..MAX_TRANSFER_SAT } ?: return null
        val expiresAt = single(EXPIRES_TAG)?.toLongOrNull()?.takeIf { it > event.createdAt } ?: return null
        val notes = all(NOTE_TAG)
        if (notes.size > 1) return null
        val note = notes.singleOrNull()?.let { tag -> tag.getOrNull(1)?.takeIf { it.isNotBlank() && it.length <= MAX_NOTE_CHARS } ?: return null }
        if (d != "$room:$chainName:${rail.wire}:${event.createdAt}") return null
        Request(giver, room, chain, rail, receiver, amount, expiresAt, note, event)
    }.getOrNull()

    /** [event] read, and signed by the giver it names -- a plain BIP-340 check over its id. */
    fun verified(event: Event): Statement? {
        val statement = read(event) ?: return null
        return statement.takeIf { GroupKeyStateEvent.isSignedByRoom(event, it.giver) }
    }

    /**
     * The signed request an inner event of this kind carries -- or null when its content is not one, or was put in the room
     * by somebody other than the member who signed it.
     */
    fun carried(inner: Event): Event? {
        if (!MovedKinds.isKind(inner.kind, KIND)) return null
        val request = Event.fromJsonOrNull(inner.content)?.takeIf { MovedKinds.isKind(it.kind, KIND) } ?: return null
        return request.takeIf { it.pubKey.equals(inner.pubKey, ignoreCase = true) }
    }
}
