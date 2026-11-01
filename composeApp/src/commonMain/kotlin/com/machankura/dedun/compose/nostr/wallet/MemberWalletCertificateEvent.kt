package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate

/**
 * A group saying it guards a wallet for one of its members: the group's
 * signature over the member's request, and what every device opens the wallet
 * on.
 *
 * ```
 *  member      --[ 30335 request, signed by them ]-> the group's room
 *  any signer  --[ 30320 proposal over a 30334   ]-> the group's room
 *  ...a signing session...
 *  everyone holds the signed 30334                   "this group guards W for this person"
 * ```
 *
 * ```
 * kind:       30334
 * pubkey:     G                         set by the signing session
 * created_at: the request's created_at  so every sponsor proposes one event id
 * content:    W
 * tags:
 *   ["d",          W]
 *   ["p",          <the member's npub>]
 *   ["wallet_key", <P_W>]
 *   ["chain",      …]
 *   ["guardian",   …]                   as in the request
 *   ["descriptor", …, "receive"]
 *   ["descriptor", …, "change"]
 *   ["label",      …]                   if the request had one
 *   ["request",    <the signed 30335, whole>]
 * ```
 *
 * ### Not a birth certificate
 *
 * A `SubgroupBirthCertificateEvent` says *this room is my child*, and every
 * reader of 30329 assumes a room -- its `certifies` rederives one from the key,
 * `SubgroupManager.subgroupsOf` lists one, the transcript announces one being
 * born. A member's wallet is not a room, so it has its own kind and none of the
 * lineage meaning. `docs/member-guarded-wallets.md`, "Should it be a birth
 * certificate?".
 *
 * ### Built from the request and nothing else
 *
 * [template] is the only way a certificate is made, and [verify] checks one is
 * exactly [template] of the request it carries. So the sponsor who puts a
 * request to the group chooses nothing, two sponsors propose one event id, and
 * no tag can say one thing in the request and another in the certificate.
 *
 * The request is embedded rather than looked up, as a guarded statement embeds
 * its birth certificate: an ancestor who was never in the group's room can
 * check that the member asked, from the group's signature alone.
 *
 * ### Never on a relay
 *
 * It carries an xpub.
 *
 * ### Why 30334
 *
 * The first free number past the guarded-wallet notice (30333), which carries
 * it upward to the groups above.
 */
object MemberWalletCertificateEvent {
    val KIND: Kind = 30334

    private const val D_TAG = "d"
    private const val MEMBER_TAG = "p"
    private const val REQUEST_TAG = "request"

    /** A certificate believed: who it guards a wallet for, and the wallet it opens. */
    data class Verified(
        val certificate: Event,
        val request: Event,
        val groupChatRoomId: String,
        val memberPublicKey: HexKey,
        val walletId: String,
        val label: String?,
        /** The account the request rederived to, guardians and all. */
        val account: GroupWalletDerivation.Account,
    )

    /**
     * The certificate [request] asks for: the request's own tags, minus the
     * possession signature it already carries whole, under the wallet id.
     */
    fun template(request: Event): EventTemplate<*> {
        val reading = requireNotNull(MemberWalletRequestEvent.read(request)) { "not a request" }
        val carried = request.tags.filterNot { it.isNotEmpty() && it[0] in setOf("h", "wallet", "possession") }

        return EventTemplate<Nothing>(
            createdAt = request.createdAt,
            kind = KIND,
            tags = (
                listOf(
                    arrayOf(D_TAG, reading.walletId),
                    arrayOf(MEMBER_TAG, reading.memberPublicKey),
                ) + carried + listOf(arrayOf(REQUEST_TAG, request.toJson()))
                ).toTypedArray(),
            content = reading.walletId,
        )
    }

    /** The request [certificate] carries, or null. Nothing is verified here. */
    fun requestOf(certificate: Event): Event? =
        certificate.tags.filter { it.size >= 2 && it[0] == REQUEST_TAG }.singleOrNull()?.let { Event.fromJsonOrNull(it[1]) }

    /** The member [certificate] names, or null. Nothing is verified here. */
    fun memberOf(certificate: Event): HexKey? =
        certificate.tags.firstOrNull { it.size >= 2 && it[0] == MEMBER_TAG }?.get(1)

    /**
     * [certificate] believed as [groupChatRoomId]'s, with no database:
     *
     *  1. it is a 30334, and the room signed it;
     *  2. the request it carries is believed (`MemberWalletRequestEvent.verified`)
     *     and asked this room;
     *  3. it is exactly [template] of that request -- every tag, the content and
     *     the time;
     *  4. so its `p` is the member who asked.
     *
     * Whether the guardians are the ones the group grants is what the group's
     * signers checked before signing. A reader trusts the group's quorum for
     * that, and it is no new trust: that quorum can already take every coin.
     *
     * [signed] false is for a certificate still being proposed, which has no
     * signature yet: everything else is checked, and its author has to be the
     * room the session will sign as.
     */
    fun verify(certificate: Event, groupChatRoomId: String, signed: Boolean = true): Verified? = runCatching {
        if (certificate.kind != KIND) return null
        if (signed && !GroupKeyStateEvent.isSignedByRoom(certificate, groupChatRoomId)) return null
        if (!signed && !certificate.pubKey.equals(groupChatRoomId, ignoreCase = true)) return null

        val request = requestOf(certificate) ?: return null
        val verified = MemberWalletRequestEvent.verified(request) ?: return null
        if (!verified.reading.groupChatRoomId.equals(groupChatRoomId, ignoreCase = true)) return null

        val expected = template(request)
        if (certificate.createdAt != expected.createdAt) return null
        if (certificate.content != expected.content) return null
        if (certificate.tags.map { it.toList() } != expected.tags.map { it.toList() }) return null

        Verified(
            certificate = certificate,
            request = request,
            groupChatRoomId = groupChatRoomId.lowercase(),
            memberPublicKey = verified.reading.memberPublicKey,
            walletId = verified.reading.walletId,
            label = verified.reading.label,
            account = verified.account,
        )
    }.getOrNull()
}
