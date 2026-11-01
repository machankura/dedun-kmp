package com.machankura.dedun.compose.nostr.subgroup.tags

import com.machankura.dedun.compose.managers.GuardianDerivation
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey

/**
 * One ancestor's guardian key, as granted to the group a statement is about:
 *
 * ```
 * ["guardian", <ancestor room id>, <33-byte key hex>, <32-byte chain code hex>, <delay in blocks>]
 * ```
 *
 * On a birth certificate it is the parent agreeing to guard the child -- its
 * own key and every key its own ancestors gave it, one step further each, in
 * that order -- and on a guarded wallet statement it is the child agreeing to be
 * guarded by exactly those keys. See `GuardianDerivation` and
 * `docs/group-wallet-guardians.md`.
 *
 * Shape only, as `SubgroupKeyTag` is. Whether a key is the one the ancestor
 * actually grants is settled by rederiving it, never by looking at it.
 */
object GuardianTag {
    const val TAG_NAME = "guardian"

    private val ROOM_ID = Regex("^[0-9a-f]{64}$")
    private val KEY = Regex("^0[23][0-9a-f]{64}$")
    private val CHAIN_CODE = Regex("^[0-9a-f]{64}$")

    fun assemble(guardian: GuardianDerivation.Guardian): Array<String> = arrayOf(
        TAG_NAME,
        guardian.chatRoomId.lowercase(),
        guardian.publicKey.toString(),
        guardian.chainCode.toHex(),
        guardian.delay.toString(),
    )

    /** The guardian [tag] names, or null when it is not a guardian tag or any of its fields is the wrong shape. */
    fun parse(tag: Array<String>): GuardianDerivation.Guardian? {
        ensure(tag.has(4)) { return null }
        ensure(tag[0] == TAG_NAME) { return null }
        ensure(ROOM_ID.matches(tag[1])) { return null }
        ensure(KEY.matches(tag[2])) { return null }
        ensure(CHAIN_CODE.matches(tag[3])) { return null }
        // Within the range a leaf may carry, so a delay a modified client wrote
        // below the floor is a tag that does not read, rather than a leaf that
        // lets a guardian race the child. Every tag before this said 12.
        val delay = tag[4].toLongOrNull()?.takeIf { it in GuardianDerivation.DELAY_RANGE } ?: return null

        val publicKey = runCatching { PublicKey.fromHex(tag[2]) }.getOrNull()?.takeIf { it.isValid() } ?: return null
        return GuardianDerivation.Guardian(
            chatRoomId = tag[1],
            publicKey = publicKey,
            chainCode = ByteVector32(tag[3]),
            delay = delay,
        )
    }

    /**
     * Every guardian in [tags], in order -- or null when any tag *named*
     * `guardian` fails to read. A list with one entry quietly dropped is a
     * different list, and the order is the tree.
     */
    fun parseAll(tags: Array<Array<String>>): List<GuardianDerivation.Guardian>? {
        val named = tags.filter { it.isNotEmpty() && it[0] == TAG_NAME }
        val parsed = named.mapNotNull(::parse)
        return parsed.takeIf { it.size == named.size }
    }
}
