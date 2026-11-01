package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import fr.acinq.bitcoin.Crypto

/**
 * The branch of a group's onchain account that is one member's deposit addresses:
 * `docs/member-deposits.md`, D3.
 *
 * ```
 * <account>/0/i   receive -- legacy: what landed before deposits were attributed, and fundings
 * <account>/1/i   change
 * <account>/b/i   one depositor's addresses, b = branch(room, member, salt)
 * ```
 *
 * A function of the room and the member, so two members opened at once get different
 * branches without either knowing of the other, and every device computes the same one.
 * Unhardened, as the whole tail is: the group's key is a threshold key and can only be
 * tweaked publicly (`docs/shared-key-derivation.md`). [salt] moves a member off a branch
 * that collides with another's, which a signer refuses; the depositor statement carries it,
 * so every reader recomputes the same branch.
 */
object DepositBranch {
    /**
     * The tagged hash's tag. Frozen once a group holds a coin on a member branch: changing it
     * moves every member's addresses, and the coins at the old ones are found by nobody.
     */
    private const val TAG = "dedun/deposit-branch/v1"

    /** The first branch a member can have: 0 is receive and 1 is change. */
    const val FIRST: Long = 2L

    /** One past the last unhardened index. */
    private const val UNHARDENED: Long = 1L shl 31

    /** Whether [branch] is one a member's could be. */
    fun isMemberBranch(branch: Long): Boolean = branch in FIRST until UNHARDENED

    /** [member]'s deposit branch in [chatRoomId], at [salt]. */
    fun of(chatRoomId: String, member: HexKey, salt: Int = 0): Long {
        require(salt >= 0) { "a salt is not negative" }
        val input = chatRoomId.lowercase().hexToByteArray() + member.lowercase().hexToByteArray() + salt.toString().encodeToByteArray()
        val hash = Crypto.taggedHash(input, TAG).toByteArray()
        val word = ((hash[0].toLong() and 0xff) shl 24) or ((hash[1].toLong() and 0xff) shl 16) or
            ((hash[2].toLong() and 0xff) shl 8) or (hash[3].toLong() and 0xff)
        return FIRST + word % (UNHARDENED - FIRST)
    }
}
