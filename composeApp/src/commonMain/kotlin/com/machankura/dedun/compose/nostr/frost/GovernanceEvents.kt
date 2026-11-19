package com.machankura.dedun.compose.nostr.frost

import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag

/**
 * The envelopes a governed room's commits travel in. docs/member-admin-promotion.md,
 * the headline and Phase 4.
 *
 * In a room whose state says changes need the group's signature, no commit is sent
 * bare. A change goes inside the group's signature ([GovernanceStatementEvent],
 * 30370); a commit that needs nobody's signature -- a member's own leave folded in,
 * a committer's own key update (D6) -- goes inside its committer's ([OwnCommitEvent],
 * 30371). Either way the envelope's content is the complete kind:445 event that
 * carries the commit, exactly as it would have been published, so every device hands
 * the engine the same event with the same id.
 *
 * They travel as application messages inside the group, which no relay can open,
 * and none is a chat line. A statement is signed by the room, not by whoever sends
 * it, and the inbound path holds every application message to its sender's own key
 * (MIP-03) -- so a statement goes inside a [GovernanceCarrierEvent] signed by the
 * member who sends it. An own commit is already its sender's and needs no carrier.
 */
object GovernanceEvents {
    val ALL: Set<Kind> = setOf(GovernanceStatementEvent.KIND, OwnCommitEvent.KIND, GovernanceSupportEvent.KIND, GovernanceCarrierEvent.KIND)

    /** The epoch tag every envelope names: the epoch its commit is built at. */
    const val EPOCH_TAG = "epoch"

    /**
     * The kind:445 an envelope carries, or null when its content is not one. Its id
     * is checked against its fields -- the envelope's signature covers the content,
     * not the inner event's own consistency.
     */
    fun innerCommitOf(envelope: Event): GroupEvent? = runCatching {
        val inner = Event.fromJson(envelope.content)
        if (inner.kind != GroupEvent.KIND || !inner.verify()) return null
        GroupEvent(inner.id, inner.pubKey, inner.createdAt, inner.tags, inner.content, inner.sig)
    }.getOrNull()

    /** The room an envelope names, by its `d` tag. */
    fun roomOf(envelope: Event): HexKey? = envelope.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)

    /** The epoch an envelope names. */
    fun epochOf(envelope: Event): Long? =
        envelope.tags.firstOrNull { it.size >= 2 && it[0] == EPOCH_TAG }?.get(1)?.toLongOrNull()

    /**
     * Whether [event] is one of these envelopes. Until K3 (docs/giza-into-dedun.md, Phase K),
     * 30370-30373 are also the legacy numbers of four of Dedun's Wavelength kinds -- the wallet
     * and operator statements, the auth seed and the signing request -- and Dedun devices
     * still write them, so the number alone does not say. The shape does: a statement and an
     * own commit name an `epoch`, which no Wavelength event carries; the support word names
     * its `governance` version; and a carrier's content is a statement.
     */
    fun isGovernance(event: Event): Boolean = when (event.kind) {
        GovernanceStatementEvent.KIND, OwnCommitEvent.KIND -> epochOf(event) != null
        GovernanceSupportEvent.KIND -> GovernanceSupportEvent.supports(event)
        GovernanceCarrierEvent.KIND -> GovernanceCarrierEvent.statementOf(event)?.let { epochOf(it) != null } == true
        else -> false
    }
}

/**
 * A commit that needs nobody's signature (D6), signed by the account that made it:
 * a member's own leave, folded in by an admin's device, or a committer's own key
 * update. Accepted only when running it shows it really is one of those and its
 * sender -- whom MLS authenticates -- is the account whose leaf committed it.
 */
object OwnCommitEvent {
    val KIND: Kind = 30371

    fun assembleTags(chatRoomId: HexKey, epoch: Long): Array<Array<String>> =
        arrayOf(DTag.assemble(chatRoomId), arrayOf(GovernanceEvents.EPOCH_TAG, epoch.toString()))
}

/**
 * A member's word that their device applies signed commits, said once per room so
 * the room can tell when the rule can be switched on without leaving anybody behind.
 * Signed by the member's account. docs/member-admin-promotion.md, Phase 4b.
 */
object GovernanceSupportEvent {
    val KIND: Kind = 30372

    const val VERSION_TAG = "governance"
    const val VERSION = "signed-commits/v1"

    fun assembleTags(chatRoomId: HexKey): Array<Array<String>> =
        arrayOf(DTag.assemble(chatRoomId), arrayOf(VERSION_TAG, VERSION))

    fun supports(event: Event): Boolean =
        event.kind == KIND && event.tags.any { it.size >= 2 && it[0] == VERSION_TAG && it[1] == VERSION }
}

/**
 * A member sending the group's signed statement into the room: its content is the
 * statement, as signed. The carrier vouches for nothing -- the statement inside is
 * checked on its own signature -- it only gets the statement past the rule that an
 * application message is its sender's (MIP-03).
 */
object GovernanceCarrierEvent {
    val KIND: Kind = 30373

    fun assembleTags(chatRoomId: HexKey): Array<Array<String>> = arrayOf(DTag.assemble(chatRoomId))

    /** The statement [carrier] carries, or null when it carries none. */
    fun statementOf(carrier: Event): Event? = runCatching {
        Event.fromJson(carrier.content).takeIf { it.kind == GovernanceStatementEvent.KIND }
    }.getOrNull()
}
