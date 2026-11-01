package com.machankura.dedun.compose.nostr.frost

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.serialization.json.jsonArray
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.frost.tags.FrostKeyTag
import com.machankura.dedun.compose.nostr.frost.tags.FrostSessionIdTag
import com.machankura.dedun.compose.nostr.frost.tags.FrostSignerIdsTag
import com.machankura.dedun.compose.nostr.frost.tags.FrostSilentMembersTag

/**
 * The nostr kinds a FROST signing session is carried on.
 *
 * These are **rumor** kinds: they only ever exist inside an encryption a relay
 * cannot open, so no relay sees them and the replaceable semantics normally
 * implied by the 3xxxx range never apply. Almost always that is a Marmot group
 * event -- MLS-encrypted to the group and then wrapped again under the group's
 * exporter secret. The exception is a group's first session, which agrees what
 * its admin room will sign with *before* that room exists and so runs in the
 * NIP-17 room its ceremony ran in, on gift wraps. See
 * `GroupKeyStateManager.propose`.
 *
 * Who talks to whom, in order:
 *
 * ```
 *  proposer --[ 30320 proposal   ]-> everyone   the unsigned event to sign
 *  signer   --[ 30321 nonce      ]-> everyone   this device's public nonce
 *  proposer --[ 30322 signer set ]-> everyone   who is signing, and their aggregated nonce
 *  signer   --[ 30323 partial    ]-> everyone   this device's partial signature
 *  proposer --[ 30324 signature  ]-> everyone   the finished 64-byte signature
 *  anyone   --[ 30325 failure    ]-> everyone   abandon + blame
 *  signer   --[ 30330 decline    ]-> everyone   this member will not sign
 * ```
 *
 * A decline is not a failure, which is why it is a kind of its own. A t-of-n
 * key needs t willing members, not n, so one member saying no leaves the
 * session open for the rest -- and every device counts the refusals it has
 * seen against the same arithmetic, abandoning only once fewer than t members
 * could still sign. See `FrostSigningManager.recordDecline`. A failure is the
 * other thing: a fault that no quorum can work around, and it ends the session
 * wherever it lands.
 *
 * [GroupKeyStateEvent] sits just past the first six on 30326. It is not part of
 * a session -- it is the standing fact a session is opened against, saying which
 * key the room signs with -- so it is deliberately outside [ALL], which is what
 * the inbound path dispatches a session message on. 30327-30329 went to the
 * chronicle and subgroup families before a session needed a seventh kind, which
 * is why the decline sits at 30330 rather than in the run.
 *
 * ### Why 3032x
 *
 * These started past the nip30303 document kinds, which ran 30300 up to 30312
 * in the same Marmot inner-event space and were dispatched on kind by the same
 * inbound path. Those kinds are gone (docs/ceremony-creation.md), and 30300-30309
 * are free.
 *
 * The DKG's 30310-30316 *did* clash with that family once a subgroup's ceremony
 * moved into its parent's Marmot room: its round 1 reached the parent's chat as
 * an unreadable document submission. `ChatMessage` recognises this family's
 * kinds and the DKG's before any content kind, so a protocol kind is never read
 * as content whatever shares its number. A new family should still take numbers
 * nobody else uses.
 */
object FrostSigningEvents {
    /**
     * Opens a session. Content is the unsigned nostr event -- or, for a batch,
     * the array of them -- as JSON; the key to sign with is named in [FrostKeyTag].
     * See [encodeProposal] for why the two forms are not one.
     */
    val PROPOSAL: Kind = 30320

    /** A signer's `IndividualNonce`, hex encoded. */
    val NONCE: Kind = 30321

    /**
     * The coordinator's chosen signers, in [FrostSignerIdsTag], with their
     * `AggregatedNonce` as the content, hex encoded.
     */
    val SIGNER_SET: Kind = 30322

    /** A signer's 32-byte partial signature, hex encoded. */
    val PARTIAL_SIGNATURE: Kind = 30323

    /** The finished 64-byte BIP-340 signature over the event id, hex encoded. */
    val SIGNATURE: Kind = 30324

    /** Session abandoned. Content is the reason, for showing to the group. */
    val FAILURE: Kind = 30325

    /**
     * One member will not sign. Content is their reason, for showing to the
     * group; the session goes on without them unless too few are left.
     *
     * A build predating this kind does not route it -- it is not in that build's
     * [ALL] -- so an old device never learns of a refusal and simply keeps
     * waiting, which is what it did for a member who said nothing. It still
     * sends its own refusals as [FAILURE], and a new device honours those as
     * the abandonment they always were.
     */
    val DECLINE: Kind = 30330

    /**
     * Opens a session over a transaction from the group's wallet. Content is the
     * PSBT, base64; the key is named in [FrostKeyTag] as for [PROPOSAL], and the
     * wallet in a `chain` and an `account` tag -- which pick the room's statement
     * to check against, and nothing more.
     *
     * A kind of its own rather than a PSBT inside a [PROPOSAL]. A build that does
     * not know this kind does not route it, so an old device never signs a
     * transaction it cannot show its owner: it is a member who did not sign, and
     * a quorum without them still can. Every message after the proposal is the
     * kinds above, unchanged, with one value per input.
     *
     * 30332 because 30331 is the wallet statement it is opened against.
     */
    val TRANSACTION_PROPOSAL: Kind = 30332

    /** Every kind above, for filtering inbound payloads in one check. */
    val ALL: Set<Kind> = setOf(
        PROPOSAL,
        TRANSACTION_PROPOSAL,
        NONCE,
        SIGNER_SET,
        PARTIAL_SIGNATURE,
        SIGNATURE,
        FAILURE,
        DECLINE
    )

    fun isFrostSigningKind(kind: Kind): Boolean = kind in ALL

    /**
     * Tags for a signing message. The session id is on every kind so a message
     * from an abandoned attempt can be dropped rather than mixed in.
     *
     * [silentMembers] rides a resent proposal and nothing else. It is additive
     * in the only sense that matters: a build that does not know the tag ignores
     * it and accepts the proposal it is attached to, which is the half of a
     * resend that fixes the common case anyway. See [FrostSilentMembersTag].
     */
    fun assembleTags(
        sessionId: String,
        dkgSessionId: String? = null,
        signerIds: List<Int>? = null,
        silentMembers: List<HexKey>? = null
    ): Array<Array<String>> = buildList {
        add(FrostSessionIdTag.assemble(sessionId))
        dkgSessionId?.let { add(FrostKeyTag.assemble(it)) }
        signerIds?.let { add(FrostSignerIdsTag.assemble(it)) }
        silentMembers?.takeIf { it.isNotEmpty() }?.let { add(FrostSilentMembersTag.assemble(it)) }
    }.toTypedArray()

    /** Whether [kind] opens a session -- over events or over a transaction. */
    fun isProposal(kind: Kind): Boolean = kind == PROPOSAL || kind == TRANSACTION_PROPOSAL

    private const val CHAIN_TAG = "chain"
    private const val ACCOUNT_TAG = "account"

    /** The wallet a [TRANSACTION_PROPOSAL] spends from, as tags. */
    fun walletTags(chain: String, account: Long): Array<Array<String>> =
        arrayOf(arrayOf(CHAIN_TAG, chain), arrayOf(ACCOUNT_TAG, account.toString()))

    private const val SWEEP_TAG = "sweep"

    /**
     * The tags of a [TRANSACTION_PROPOSAL] that is a sweep from the Lightning wallet [lightningWallet] into
     * the onchain wallet: the wallet's tags, and which Lightning wallet the outputs are from.
     */
    fun sweepTags(chain: String, account: Long, lightningWallet: Long): Array<Array<String>> =
        walletTags(chain, account) + arrayOf(arrayOf(SWEEP_TAG, lightningWallet.toString()))

    /** The Lightning wallet a sweep proposal sweeps from, or null for an ordinary spend. */
    fun parseSweep(tags: Array<Array<String>>): Long? =
        tags.firstOrNull { it.size >= 2 && it[0] == SWEEP_TAG }?.get(1)?.toLongOrNull()

    /** The network and account a [TRANSACTION_PROPOSAL] names, or null when it names either badly. */
    fun parseWallet(tags: Array<Array<String>>): Pair<String, Long>? {
        val chain = tags.firstOrNull { it.size >= 2 && it[0] == CHAIN_TAG }?.get(1) ?: return null
        val account = tags.firstOrNull { it.size >= 2 && it[0] == ACCOUNT_TAG }?.get(1)?.toLongOrNull() ?: return null
        return chain to account
    }

    private const val GUARDED_TAG = "guarded"

    /**
     * On a [TRANSACTION_PROPOSAL] from an ancestor's room: the descendant whose
     * guarded wallet it spends from, through the room's guardian leaf. Absent
     * for a spend from the room's own wallet. Which wallet it is is all this
     * says; every signer then reads that wallet from the statement its own room
     * was told, never from the proposal.
     */
    fun guardedTags(descendantChatRoomId: String): Array<Array<String>> = arrayOf(arrayOf(GUARDED_TAG, descendantChatRoomId))

    /** The descendant a guardian's [TRANSACTION_PROPOSAL] names, or null for the room's own wallet. */
    fun parseGuarded(tags: Array<Array<String>>): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == GUARDED_TAG }?.get(1)?.takeIf { it.isNotBlank() }

    fun parseSessionId(tags: Array<Array<String>>): String? =
        tags.firstNotNullOfOrNull(FrostSessionIdTag::parse)?.sessionId

    fun parseKey(tags: Array<Array<String>>): String? =
        tags.firstNotNullOfOrNull(FrostKeyTag::parse)?.dkgSessionId

    fun parseSignerIds(tags: Array<Array<String>>): List<Int>? =
        tags.firstNotNullOfOrNull(FrostSignerIdsTag::parse)?.signerIds

    /**
     * Who the coordinator says it has not heard from, or null on any proposal
     * that is not a resend.
     */
    fun parseSilentMembers(tags: Array<Array<String>>): List<HexKey>? =
        tags.firstNotNullOfOrNull(FrostSilentMembersTag::parse)?.members

    /**
     * The unsigned events of a proposal, as its content.
     *
     * A batch of one serialises as the bare event object it always did, and only
     * a genuine batch becomes an array. That is not tidiness. A build predating
     * batching reads an array with `Event.fromJsonOrNull`, gets null, and drops
     * the proposal -- so an old device refuses a batch outright rather than
     * signing part of one, while single signing keeps working right through a
     * mixed-version rollout. Emitting an array unconditionally would break every
     * one-event session for those devices and buy nothing.
     *
     * An empty list has no honest encoding and is not one, so callers do not
     * produce it and [decodeProposal] does not accept it.
     */
    fun encodeProposal(events: List<Event>): String =
        events.singleOrNull()?.toJson()
            ?: events.joinToString(separator = ",", prefix = "[", postfix = "]") { it.toJson() }

    /**
     * The other half. Both forms are accepted, permanently: proposals in the old
     * shape do not stop arriving just because this build stopped writing them.
     *
     * All-or-nothing. An array with one unreadable element is refused rather than
     * silently shortened, because the batch's length is what every later payload
     * is checked against -- a proposal that quietly lost an event would have every
     * signer's contribution rejected for being the wrong size, which is a stall
     * with no message to blame.
     */
    fun decodeProposal(content: String): List<Event>? {
        if (!content.trimStart().startsWith("[")) {
            return Event.fromJsonOrNull(content)?.let { listOf(it) }
        }

        val elements = runCatching { CommonJson.parseToJsonElement(content).jsonArray }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return elements.map { element -> Event.fromJsonOrNull(element.toString()) ?: return null }
    }
}
