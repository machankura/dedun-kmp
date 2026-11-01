package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.subgroup.tags.SubgroupBirthCertificateTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * A member asking the group for its key, and a member answering -- the two
 * things a guarded wallet's grant is worked out from.
 *
 * A member's guarded wallet carries the group's leaf and its ancestors' leaves,
 * and the member's own device works them out before it asks the group to guard
 * anything: `GuardianDerivation.grant` over the group's threshold key and the
 * guardian keys in the group's own birth certificate. Both are read off the
 * group's verified key state, which only ceremony participants hold. A member
 * welcomed later has neither. See `docs/member-guarded-wallets.md`, D4.
 *
 * ```
 *  member without a key state  --[ 30336, no content         ]-> the room   "what does this group sign with?"
 *  member holding one          --[ 30336, the key + its cert ]-> the room
 *  the asking device checks the answer on its own
 * ```
 *
 * ### Nothing the answerer says is taken on its word
 *
 * The answer carries the threshold key, the path, and the certificate if the
 * group has one, and every one of them checks itself: the key at the path
 * rederives the room's id, and the certificate certifies that room and names
 * that key. A forged answer cannot name another key, and one that leaves out or
 * swaps the certificate gives a grant every signer refuses, because each signer
 * works the grant out again from its own key state.
 *
 * The signed key state itself is not carried: its row keeps what it said and not
 * the event, and nothing in it is needed beyond what checks itself.
 *
 * ### The group's policy on members' wallets, too
 *
 * An answer also carries, whole, the newest `MemberWalletPolicyEvent` the
 * answering device holds -- the other thing a late member needs before asking
 * for a wallet, and one MLS will not let them read from before their welcome.
 * It checks itself as well: the group's own room signed it. See
 * `docs/member-wallet-delay.md`, Phase 3.
 *
 * ### Never filed as key state
 *
 * An answer is read in one place, for one purpose, every time it is used. Filing
 * it into `GroupKeyState` would give every late member a key state and quietly
 * change what `GroupWalletManager.thresholdKeyFor` answers for them across the
 * app.
 *
 * ### Why 30336
 *
 * After the member wallet's certificate (30334) and request (30335).
 */
object GroupKeyNoticeEvent {
    val KIND: Kind = 30336

    private const val ROOM_TAG = "h"
    private const val REQUEST_TAG = "e"
    private const val PATH_TAG = "frost_path"
    private const val POLICY_TAG = "member_wallet_policy"

    /** What a group signs with, as a member's device has checked it. */
    data class GroupKey(
        val chatRoomId: String,
        val thresholdPublicKey: HexKey,
        /** The group's own birth certificate, or null for a group nobody's subgroup. */
        val certificate: Event?,
        /**
         * The group's newest policy on its members' wallets as the answer carried
         * it, verified, or null when the answering device held none. Never read
         * off a key state: `MemberWalletManager.policyFor` reads the group's own.
         */
        val policy: MemberWalletPolicyEvent.Policy? = null,
    ) {
        /** The guardian keys the group's certificate grants it, nearest first: what it passes down. */
        val guardians: List<GuardianDerivation.Guardian>
            get() = certificate?.let { SubgroupBirthCertificateEvent.parseGuardians(it.tags) }.orEmpty()
    }

    /** The asking shape's tags. */
    fun askingTags(chatRoomId: String): Array<Array<String>> = arrayOf(arrayOf(ROOM_TAG, chatRoomId))

    /** The answering shape's tags, answering [requestId]. The content is [GroupKey.thresholdPublicKey]. */
    fun answeringTags(requestId: HexKey, groupKey: GroupKey, path: List<Long>): Array<Array<String>> =
        listOfNotNull(
            arrayOf(ROOM_TAG, groupKey.chatRoomId),
            arrayOf(REQUEST_TAG, requestId),
            arrayOf(PATH_TAG, SharedKeyDerivation.formatPath(path)),
            groupKey.certificate?.let { SubgroupBirthCertificateTag.assemble(it) },
            groupKey.policy?.let { arrayOf(POLICY_TAG, it.event.toJson()) },
        ).toTypedArray()

    /** Whether [event] asks rather than answers. */
    fun isAsking(event: Event): Boolean = event.kind == KIND && event.content.isBlank()

    /** The asking notice [event] answers, or null when it answers none. */
    fun answers(event: Event): HexKey? =
        event.takeIf { it.kind == KIND && it.content.isNotBlank() }?.tags
            ?.firstOrNull { it.size >= 2 && it[0] == REQUEST_TAG }?.get(1)

    /**
     * The key [event] says [chatRoomId] signs with, or null when it is not an
     * answer or does not check out:
     *
     *  1. it is an answer, and names [chatRoomId];
     *  2. the key at the path it names rederives [chatRoomId];
     *  3. a certificate it carries certifies [chatRoomId], names that key, and
     *     reads -- its guardian keys and its wallet policy both. One present and
     *     unreadable is refused rather than read as none, for the reason
     *     `GroupKeyStateManager.stateFrom` refuses it;
     *  4. a member-wallet policy it carries is [chatRoomId]'s own, signed by
     *     the room. The same rule: present and unreadable is not "no policy".
     */
    fun read(event: Event, chatRoomId: String): GroupKey? = runCatching {
        if (event.kind != KIND || event.content.isBlank()) return null
        fun value(name: String): String? = event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        if (!value(ROOM_TAG).equals(chatRoomId, ignoreCase = true)) return null

        val thresholdPublicKey = event.content.trim().lowercase()
        val path = value(PATH_TAG)?.let { SharedKeyDerivation.parsePathString(it) } ?: return null
        if (!SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path).equals(chatRoomId, ignoreCase = true)) return null

        val certificateTags = event.tags.filter { it.isNotEmpty() && it[0] == SubgroupBirthCertificateTag.TAG_NAME }
        if (certificateTags.size > 1) return null
        val certificate = certificateTags.singleOrNull()?.let { tag ->
            SubgroupBirthCertificateTag.parse(tag)?.certificate ?: return null
        }
        if (certificate != null) {
            val parentChatRoomId = SubgroupBirthCertificateEvent.parseParentChatRoomId(certificate.tags) ?: return null
            if (!SubgroupBirthCertificateEvent.certifies(certificate, chatRoomId, parentChatRoomId)) return null
            if (!SubgroupBirthCertificateEvent.parseThresholdPublicKey(certificate.tags).equals(thresholdPublicKey, ignoreCase = true)) return null
            SubgroupBirthCertificateEvent.parseGuardians(certificate.tags) ?: return null
            SubgroupBirthCertificateEvent.parseWalletPolicy(certificate.tags) ?: return null
        }

        val policyTags = event.tags.filter { it.isNotEmpty() && it[0] == POLICY_TAG }
        if (policyTags.size > 1) return null
        val policy = policyTags.singleOrNull()?.let { tag ->
            val carried = tag.getOrNull(1)?.let { Event.fromJsonOrNull(it) } ?: return null
            MemberWalletPolicyEvent.verify(carried, chatRoomId) ?: return null
        }

        GroupKey(chatRoomId = chatRoomId.lowercase(), thresholdPublicKey = thresholdPublicKey, certificate = certificate, policy = policy)
    }.getOrNull()
}
