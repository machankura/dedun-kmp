package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.DepositBranch
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent.isHex64
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent.single
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The group opening deposits for a contact on one network: a branch of the group's wallet that
 * is theirs, as a member's 30339 is. `docs/non-member-deposits.md`, D10.
 *
 * ```
 * kind:    30426
 * pubkey:  <the room>
 * content: ""
 * tags:
 *   ["d",       "<chain>:<contact id>"]
 *   ["contact", <contact id>]
 *   ["chain",   "mainnet" | …]
 *   ["branch",  "<b>", "<salt>"]               b = DepositBranch.of(room, contact id, salt)
 * ```
 *
 * A kind of its own rather than a 30339 with a contact in it: a 30339 embeds the member's own
 * signed destination, which a contact can't make, and an older build would fail to verify one and
 * silently not watch its branch. An older build doesn't know this kind at all, which is the
 * visible kind of ignorance. Write-once per contact per network, as 30339 is.
 */
object ContactDepositorStatementEvent {
    val KIND: Kind = 30426

    private const val D_TAG = "d"
    private const val CONTACT_TAG = "contact"
    private const val CHAIN_TAG = "chain"
    private const val BRANCH_TAG = "branch"

    fun identifier(chain: Chain, contactId: HexKey): String = "${GroupWalletStatementEvent.chainName(chain)}:${contactId.lowercase()}"

    data class Depositor(val contactId: HexKey, val chain: Chain, val branch: Long, val salt: Int, val event: Event) {
        val createdAt: Long get() = event.createdAt
    }

    fun template(chatRoomId: String, contactId: HexKey, chain: Chain, salt: Int = 0, createdAt: Long = Clock.System.now().epochSeconds): EventTemplate<*> =
        EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, identifier(chain, contactId)),
                arrayOf(CONTACT_TAG, contactId.lowercase()),
                arrayOf(CHAIN_TAG, GroupWalletStatementEvent.chainName(chain)),
                arrayOf(BRANCH_TAG, DepositBranch.of(chatRoomId, contactId.lowercase(), salt).toString(), salt.toString()),
            ),
            content = "",
        )

    /** What [event] says about [chatRoomId] by its shape: one of each tag, `d` agreeing, and a branch that recomputes. */
    fun read(event: Event, chatRoomId: String): Depositor? = runCatching {
        if (event.kind != KIND) return null
        val contactId = single(event, CONTACT_TAG)?.get(1)?.lowercase()?.takeIf(::isHex64) ?: return null
        val chain = single(event, CHAIN_TAG)?.get(1)?.let(GroupWalletStatementEvent::chainNamed) ?: return null
        if (single(event, D_TAG)?.get(1) != identifier(chain, contactId)) return null
        val branchTag = single(event, BRANCH_TAG)?.takeIf { it.size >= 3 } ?: return null
        val branch = branchTag[1].toLongOrNull() ?: return null
        val salt = branchTag[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        if (branch != DepositBranch.of(chatRoomId, contactId, salt)) return null
        Depositor(contactId, chain, branch, salt, event)
    }.getOrNull()

    fun verify(event: Event, chatRoomId: String): Depositor? =
        read(event, chatRoomId)?.takeIf { GroupKeyStateEvent.isSignedByRoom(event, chatRoomId) }

    /** Each contact's statement among [events] on [chain]: the first the group signed. */
    fun firstPerContact(events: List<Event>, chatRoomId: String, chain: Chain): Map<HexKey, Depositor> =
        events.asSequence()
            .mapNotNull { verify(it, chatRoomId) }
            .filter { it.chain == chain }
            .groupBy { it.contactId }
            .mapValues { (_, statements) -> statements.minWith(compareBy<Depositor>({ it.createdAt }, { it.event.id })) }
}
