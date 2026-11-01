package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.MemberWalletDerivation
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import kotlin.time.Clock

/**
 * A member asking their group to guard a wallet only they can spend from.
 *
 * The member's device has already made the wallet's key, worked out every
 * ancestor's leaf and both descriptors, and stored the key. This is the first
 * thing that leaves the device, and it carries all of it, so the group signs off
 * on a wallet the member has already built rather than one it builds for them.
 * See `docs/member-guarded-wallets.md`, Phase 3.
 *
 * ```
 * kind:    30335
 * pubkey:  <the member's npub>
 * content: <the account xpub>
 * tags:
 *   ["h",          G]                                    the group asked
 *   ["wallet",     W]
 *   ["wallet_key", <P_W, 33-byte hex>]
 *   ["chain",      "mainnet" | "signet" | …]
 *   ["guardian",   <room id>, <key>, <chain code>, "12"] one per guardian, nearest first
 *   ["descriptor", tr(…,{…}), "receive"]
 *   ["descriptor", tr(…,{…}), "change"]
 *   ["label",      "Savings"]                            optional
 *   ["possession", <BIP340 signature by k>]
 * ```
 *
 * ### Signed twice
 *
 * By the member's nostr key, so it is bound to who asked, and -- in the
 * `possession` tag -- by the wallet key itself, over [possessionMessage], so the
 * member is shown to hold the key they are asking about. Without the second, Bob
 * could ask for a certificate on Alice's wallet key under his own name.
 *
 * `k` signs exactly that one message, under its own tagged hash. It is never an
 * output key: the account step always tweaks it first.
 *
 * ### Carried whole
 *
 * An inner event is an unsigned rumor -- its authority is the MLS frame -- and the
 * row it travels in keeps no signature. This one's signature has to survive the
 * room, because the group's certificate embeds it for ancestors who were never
 * in the room. So the room carries an inner event of this kind whose content is
 * the signed request, whole, as a 30333 notice carries a statement: [carried].
 *
 * ### Never on a relay
 *
 * It carries an xpub, which is every address and the whole balance.
 *
 * ### Why 30335
 *
 * After the certificate it asks for, at 30334.
 */
object MemberWalletRequestEvent {
    val KIND: Kind = 30335

    private const val ROOM_TAG = "h"
    private const val WALLET_TAG = "wallet"
    private const val WALLET_KEY_TAG = "wallet_key"
    private const val CHAIN_TAG = "chain"
    private const val DESCRIPTOR_TAG = "descriptor"
    private const val RECEIVE_MARKER = "receive"
    private const val CHANGE_MARKER = "change"
    private const val LABEL_TAG = "label"
    private const val POSSESSION_TAG = "possession"
    private const val POLICY_TAG = "member_wallet_policy"

    /** The tag of the possession signature's message. Frozen once a request carries one. */
    private const val POSSESSION_HASH_TAG = "bennu/member-wallet/possession/v1"

    /** The longest label a request carries: a name for a wallet, not a note. */
    const val MAX_LABEL_LENGTH = 64

    /** What a request says, read but not yet believed. [verified] is what believes it. */
    data class Reading(
        val memberPublicKey: HexKey,
        val groupChatRoomId: String,
        val walletId: String,
        val walletKey: PublicKey,
        val chain: Chain,
        val xpub: String,
        val receiveDescriptor: String,
        val changeDescriptor: String,
        val guardians: List<GuardianDerivation.Guardian>,
        val label: String?,
        val possession: ByteVector64,
        /**
         * The group's member-wallet policy the request was built under, whole and
         * unverified -- or null for a request from before policies, which every
         * signer now refuses and every reader of an old certificate still
         * accepts. See `docs/member-wallet-delay.md`.
         */
        val policy: Event? = null,
    )

    /** A request that checks out on everything a reader can check without the group's key. */
    data class Verified(
        val request: Event,
        val reading: Reading,
        /** The account the request opens, rederived from the wallet key -- never what it said. */
        val account: GroupWalletDerivation.Account,
        /** The open policy it was built under, verified as the group's own; null for a request from before policies. */
        val policy: MemberWalletPolicyEvent.Policy.Open? = null,
    )

    /**
     * What `k` signs: the member, the group, the wallet key and the network --
     * everything that has to bind one key to one person in one group on one chain.
     */
    fun possessionMessage(memberPublicKey: HexKey, groupChatRoomId: String, walletKey: PublicKey, chain: Chain): ByteVector32 =
        Crypto.taggedHash(
            memberPublicKey.lowercase().hexToByteArray() +
                groupChatRoomId.lowercase().hexToByteArray() +
                walletKey.value.toByteArray() +
                chain.chainHash.value.toByteArray(),
            POSSESSION_HASH_TAG,
        )

    /**
     * The request for [account] -- opened under the wallet key [walletPrivateKey]
     * holds -- of [groupChatRoomId], signed as the member [memberPrivateKey] holds.
     */
    fun build(
        memberPrivateKey: PrivateKey,
        groupChatRoomId: String,
        walletPrivateKey: PrivateKey,
        account: GroupWalletDerivation.Account,
        label: String?,
        createdAt: Long = Clock.System.now().epochSeconds,
        /** The group's open policy the grant was worked out under, carried whole. */
        policy: Event? = null,
    ): Event {
        val memberPublicKey = memberPrivateKey.xOnlyPublicKey().value.toHex()
        val walletKey = walletPrivateKey.publicKey()
        require(account.extendedPublicKey == MemberWalletDerivation.account(walletKey, account.chain, account.guardians).extendedPublicKey) {
            "the account is not the wallet key's"
        }
        val possession = Crypto.signSchnorr(possessionMessage(memberPublicKey, groupChatRoomId, walletKey, account.chain), walletPrivateKey, null)

        val tags = listOfNotNull(
            arrayOf(ROOM_TAG, groupChatRoomId.lowercase()),
            arrayOf(WALLET_TAG, MemberWalletDerivation.walletId(walletKey)),
            arrayOf(WALLET_KEY_TAG, walletKey.toString()),
            arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(account.chain)),
            *account.guardians.map(GuardianTag::assemble).toTypedArray(),
            arrayOf(DESCRIPTOR_TAG, account.receiveDescriptor, RECEIVE_MARKER),
            arrayOf(DESCRIPTOR_TAG, account.changeDescriptor, CHANGE_MARKER),
            label?.trim()?.takeIf { it.isNotEmpty() }?.let { arrayOf(LABEL_TAG, it.take(MAX_LABEL_LENGTH)) },
            policy?.let { arrayOf(POLICY_TAG, it.toJson()) },
            arrayOf(POSSESSION_TAG, possession.toHex()),
        ).toTypedArray()

        val id = EventHasher.hashId(memberPublicKey, createdAt, KIND, tags, account.xpub)
        val sig = Crypto.signSchnorr(ByteVector32(id), memberPrivateKey, null)
        return Event(id = id, pubKey = memberPublicKey, createdAt = createdAt, kind = KIND, tags = tags, content = account.xpub, sig = sig.toHex())
    }

    /** The request's fields, or null when any is missing, doubled or the wrong shape. Nothing is verified here. */
    fun read(event: Event): Reading? = runCatching {
        if (event.kind != KIND) return null
        fun single(name: String): String? =
            event.tags.filter { it.isNotEmpty() && it[0] == name }.takeIf { it.size == 1 }?.single()?.takeIf { it.size >= 2 }?.get(1)
        fun descriptor(marker: String): String? =
            event.tags.filter { it.size >= 3 && it[0] == DESCRIPTOR_TAG && it[2] == marker }.singleOrNull()?.get(1)

        val labels = event.tags.filter { it.isNotEmpty() && it[0] == LABEL_TAG }
        if (labels.size > 1) return null
        val label = labels.singleOrNull()?.getOrNull(1)?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL_LENGTH }
        if (labels.isNotEmpty() && label == null) return null

        // Present and unreadable is not "built under no policy".
        val policies = event.tags.filter { it.isNotEmpty() && it[0] == POLICY_TAG }
        if (policies.size > 1) return null
        val policy = policies.singleOrNull()?.let { tag -> tag.getOrNull(1)?.let { Event.fromJsonOrNull(it) } ?: return null }

        Reading(
            memberPublicKey = event.pubKey.lowercase(),
            groupChatRoomId = single(ROOM_TAG)?.lowercase() ?: return null,
            walletId = single(WALLET_TAG)?.lowercase() ?: return null,
            walletKey = single(WALLET_KEY_TAG)?.let { PublicKey.fromHex(it) }?.takeIf { it.isValid() } ?: return null,
            chain = single(CHAIN_TAG)?.let(GroupWalletStatementEvent::chainNamed) ?: return null,
            xpub = event.content.takeIf { it.isNotBlank() } ?: return null,
            receiveDescriptor = descriptor(RECEIVE_MARKER) ?: return null,
            changeDescriptor = descriptor(CHANGE_MARKER) ?: return null,
            guardians = GuardianTag.parseAll(event.tags)?.takeIf { it.isNotEmpty() } ?: return null,
            label = label,
            possession = single(POSSESSION_TAG)?.let { ByteVector64(it) } ?: return null,
            policy = policy,
        )
    }.getOrNull()

    /**
     * [event] believed, on everything a reader can check without the group's key:
     *
     *  1. it is a request, signed by the member it names;
     *  2. its wallet id is the wallet key's;
     *  3. the wallet key signed the possession message;
     *  4. its nearest guardian is the group it asks, so a request cannot leave
     *     out the one group it is asking;
     *  5. the wallet key, under those guardians, rederives the xpub and both
     *     descriptors byte for byte;
     *  6. a policy it carries is an open one the group it asks signed, and its
     *     nearest guardian's delay is that policy's -- `docs/member-wallet-delay.md`.
     *     A request carrying none still reads: certificates signed before
     *     policies embed one, and their wallets are guarded all the same. That
     *     a *new* request must carry one is the signers' rule, not a reader's.
     *
     * Whether the guardians are the ones the group grants is not here: only a
     * device holding the group's key can say, and the group's signers do.
     */
    fun verified(event: Event): Verified? = runCatching {
        val reading = read(event) ?: return null
        if (!GroupKeyStateEvent.isSignedByRoom(event, reading.memberPublicKey)) return null
        if (MemberWalletDerivation.walletId(reading.walletKey) != reading.walletId) return null

        val message = possessionMessage(reading.memberPublicKey, reading.groupChatRoomId, reading.walletKey, reading.chain)
        if (!Crypto.verifySignatureSchnorr(message, reading.possession, reading.walletKey.xOnly())) return null

        if (!reading.guardians.first().chatRoomId.equals(reading.groupChatRoomId, ignoreCase = true)) return null

        val account = MemberWalletDerivation.account(reading.walletKey, reading.chain, reading.guardians)
        if (account.xpub != reading.xpub || account.receiveDescriptor != reading.receiveDescriptor || account.changeDescriptor != reading.changeDescriptor) return null

        val policy = reading.policy?.let { carried ->
            val open = MemberWalletPolicyEvent.verify(carried, reading.groupChatRoomId) as? MemberWalletPolicyEvent.Policy.Open ?: return null
            if (reading.guardians.first().delay != open.delay) return null
            open
        }

        Verified(request = event, reading = reading, account = account, policy = policy)
    }.getOrNull()

    /**
     * The signed request an inner event of this kind carries -- or null when its
     * content is not one, or was sent by somebody other than the member who
     * signed it. A request is passed on by nobody: whoever puts it in the room
     * is the one asking.
     */
    fun carried(inner: Event): Event? {
        if (inner.kind != KIND) return null
        val request = Event.fromJsonOrNull(inner.content)?.takeIf { it.kind == KIND } ?: return null
        return request.takeIf { it.pubKey.equals(inner.pubKey, ignoreCase = true) }
    }
}
