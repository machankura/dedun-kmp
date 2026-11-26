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
    val ALL: Set<Kind> = setOf(
        GovernanceStatementEvent.KIND, OwnCommitEvent.KIND, GovernanceSupportEvent.KIND, GovernanceCarrierEvent.KIND,
        PromotionEvents.OFFER, PromotionEvents.ACCEPT, PromotionEvents.HANDOVER, PromotionEvents.STEP_DOWN,
    )

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
     * Whether [event] is one of these envelopes, or a step of a promotion. Until K3
     * (docs/giza-into-dedun.md, Phase K), 30370-30377 are also the legacy numbers of eight of
     * Dedun's Wavelength kinds -- the wallet and operator statements, the auth seed, and the
     * signing request, echo, provision, signature shares and high-water mark -- and Dedun devices still write
     * them, so the number alone does not say. The shape does, since no Wavelength event carries
     * any of these tags: a statement and an own commit name an `epoch`; the support word names
     * its `governance` version; a carrier's content is a statement; an offer and a handover
     * name their `target`, and an acceptance its `offer` and `host`. A step-down (30377, the
     * high-water mark's legacy number) is told by its empty content.
     */
    fun isGovernance(event: Event): Boolean = when (event.kind) {
        GovernanceStatementEvent.KIND, OwnCommitEvent.KIND -> epochOf(event) != null
        GovernanceSupportEvent.KIND -> GovernanceSupportEvent.supports(event)
        GovernanceCarrierEvent.KIND -> GovernanceCarrierEvent.statementOf(event)?.let { epochOf(it) != null } == true
        PromotionEvents.OFFER, PromotionEvents.HANDOVER -> PromotionEvents.value(event.tags, PromotionEvents.TARGET_TAG) != null
        PromotionEvents.STEP_DOWN -> PromotionEvents.isStepDown(event.content)
        PromotionEvents.ACCEPT ->
            PromotionEvents.value(event.tags, PromotionEvents.OFFER_TAG) != null && PromotionEvents.value(event.tags, PromotionEvents.HOST_KEY_TAG) != null
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

/**
 * Making a member an admin of a keyed room, which also makes them a holder of its
 * key (R1). docs/member-admin-promotion.md, Phase 8.
 *
 * 1. An admin offers ([OFFER]) -- a request, carrying no authority.
 * 2. The member accepts ([ACCEPT]), naming the ChillDKG host key the shares sent
 *    to them will be sealed to. This is the one decision only they can make.
 * 3. The group signs the promotion and the key state that binds the next
 *    participant id to them, in one batch, and the commit lands.
 * 4. The proposer hands over ([HANDOVER]) what the new member needs to take part:
 *    the share table, the roster and the signed key state. Nothing in it is
 *    trusted on the proposer's word -- the table has to hash to what the group
 *    signed, and the state has to be the room's own signature.
 * 5. The member asks to be enrolled at the new id, and t holders run reduced
 *    FROST enrollment for them, as they would a repair.
 */
object PromotionEvents {
    val OFFER: Kind = 30374
    val ACCEPT: Kind = 30375
    val HANDOVER: Kind = 30376

    /**
     * An admin of a keyed room asking to be removed as one. They cannot propose it
     * themselves: the rotation that follows a demotion is started by its proposer,
     * and has to be started by somebody who keeps a share. So one remaining admin's
     * device proposes it for them (`PromotionManager.followStepDowns`). Signed by the
     * admin stepping down, whom MLS authenticates as its sender.
     */
    val STEP_DOWN: Kind = 30377

    val ALL: Set<Kind> = setOf(OFFER, ACCEPT, HANDOVER, STEP_DOWN)

    const val TARGET_TAG = "target"
    const val OFFER_TAG = "offer"
    const val HOST_KEY_TAG = "host"

    fun offerTags(chatRoomId: HexKey, target: HexKey): Array<Array<String>> =
        arrayOf(DTag.assemble(chatRoomId), arrayOf(TARGET_TAG, target))

    fun acceptTags(chatRoomId: HexKey, offerId: HexKey, hostPublicKey: HexKey): Array<Array<String>> =
        arrayOf(DTag.assemble(chatRoomId), arrayOf(OFFER_TAG, offerId), arrayOf(HOST_KEY_TAG, hostPublicKey))

    fun handoverTags(chatRoomId: HexKey, target: HexKey): Array<Array<String>> =
        arrayOf(DTag.assemble(chatRoomId), arrayOf(TARGET_TAG, target))

    fun stepDownTags(chatRoomId: HexKey): Array<Array<String>> = arrayOf(DTag.assemble(chatRoomId))

    /**
     * Whether a 30377 with [content] is a step-down request: it says nothing but who sent it,
     * so its content is empty. Until K3, 30377 is also the legacy number of the Wavelength
     * wallet's high-water mark, which Dedun devices still write and whose content is never
     * empty -- and an admin's high-water mark taken for a request would have them demoted.
     */
    fun isStepDown(content: String): Boolean = content.isEmpty()

    fun value(tags: Array<Array<String>>, name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
}
