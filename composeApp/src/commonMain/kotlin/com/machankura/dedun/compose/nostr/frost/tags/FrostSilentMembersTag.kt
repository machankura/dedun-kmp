package com.machankura.dedun.compose.nostr.frost.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * The share holders a coordinator has heard nothing from, carried on a resent
 * proposal.
 *
 * A proposer can resend what the proposer wrote, and nothing else: a nonce or a
 * refusal relayed by somebody else is filed under the relayer -- see
 * `FrostSigningManager.record`, which keys a signer's message on the inner
 * event's own `pubKey` -- and for a refusal that is a way to manufacture one.
 * So the resend names the quiet members instead, and their own devices
 * republish what they already said, under the keys that said it.
 *
 * It names rather than counts because a count cannot be acted on. A device
 * reading this tag is asking one question -- am I in it -- and the answer has
 * to be about them specifically.
 *
 * Only the coordinator's tag is honoured, and only to republish messages that
 * are already on file. Nothing here can talk a device into publishing a nonce
 * its owner has not approved; that gate is `advance`'s, and this does not reach
 * it. See `FrostSigningManager.republishOwn`.
 *
 * Carrying it leaks nothing. On Marmot every nonce is a group event the whole
 * room received, and on NIP-17 every member is p-tagged on every message, so
 * who has spoken is not a secret this could give away.
 */
class FrostSilentMembersTag(
    val members: List<HexKey>,
) {
    fun toTagArray() = assemble(members = members)

    companion object {
        const val TAG_NAME = "frost_silent"

        /** The length of a nostr public key in hex, which is what this list holds. */
        private const val KEY_LENGTH = 64

        /**
         * All or nothing, like [FrostSignerIdsTag]: a list naming the wrong
         * people is worse than no list. One unreadable entry means the whole tag
         * is dropped -- and the resend then simply nudges nobody, which is the
         * same as a build that does not write the tag at all.
         */
        fun parse(tag: Array<String>): FrostSilentMembersTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val members = tag[1].split(",").map { entry ->
                entry.trim().lowercase().takeIf { it.isHexKey() } ?: return null
            }
            if (members.isEmpty()) return null

            return FrostSilentMembersTag(members = members)
        }

        /**
         * Lowercased, because a pubkey is compared against
         * `FrostSigningSession.userPublicKey` by string equality and a tag that
         * shouted would name nobody.
         */
        fun assemble(members: List<HexKey>): Array<String> =
            arrayOf(TAG_NAME, members.joinToString(",") { it.lowercase() })

        fun assemble(frostSilentMembersTag: FrostSilentMembersTag) =
            assemble(members = frostSilentMembersTag.members)

        private fun String.isHexKey(): Boolean =
            length == KEY_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }
    }
}
