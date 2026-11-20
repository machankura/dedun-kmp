package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.appComponents.AdminPolicyV1
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.frost.FrostEnrollment
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.GovernanceProposal
import com.machankura.dedun.compose.database.model.GovernanceProposalStage
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.KeyShareMember
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.ShareGenerationState
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.exceptions.MarmotMissingChatGroupException
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.frost.PromotionEvents

/**
 * Making a member an admin of a keyed room, which makes them a holder of its key
 * too (R1). docs/member-admin-promotion.md, Phase 8; the steps are on
 * [PromotionEvents].
 *
 * The new member is enrolled at the next participant id, `n`, by reduced FROST
 * enrollment -- the repair protocol run with `newId = n` and the `n` before it
 * (`ShareRepairManager`). Every device puts the id in its table the same way: its
 * public share is derived from the table it already has ([publicShareFor]) and
 * accepted only when the extended table hashes to what the group signed
 * ([enrollNewId]). So nothing about the new id is anybody's say-so.
 *
 * Only from a key past its ceremony's generation: generation 0 lives in the
 * ceremony's own record, which has no room for an id the ceremony did not make.
 */
object PromotionManager {
    private val logger = Logger.withTag("PromotionManager")

    // ---- the new id's public share, the same on every device -------------------------------------------------------

    /**
     * The public share of participant [newId] on the polynomial [table] lies on, by
     * interpolation in the exponent from its first [threshold] entries. Any t entries
     * give the same point, so every device that holds the same table gets the same one.
     */
    fun publicShareFor(table: List<PublicKey>, threshold: Int, newId: Int): PublicKey {
        val helperIds = (0 until threshold).map { it.toUInt() }
        val derived = FrostEnrollment.derivePublicShare(table.take(threshold), helperIds, newId.toUInt(), newId, threshold)
        return derived.right ?: throw IllegalStateException("Couldn't derive id $newId's public share: ${derived.left?.message}")
    }

    /** [table] with the next id's public share appended. */
    fun extended(table: List<PublicKey>, threshold: Int): List<PublicKey> = table + publicShareFor(table, threshold, table.size)

    /**
     * Puts the next id in this device's table at [state]'s generation, when [binding]
     * names it and the table extended by its derived public share is the one the
     * group signed. Called with a signed key state's bindings, before they are
     * applied; anything else -- an id already there, a table that would hash
     * differently -- is left alone.
     */
    suspend fun enrollNewId(database: DedunDatabase, state: GroupKeyState, binding: GroupKeyStateEvent.MemberBinding) {
        val generation = state.generation ?: return
        val key = database.dkgSessionDao().getSessionById(state.dkgSessionId) ?: return
        val row = database.keyShareDao().getGeneration(key.id, generation) ?: return
        val table = row.publicShareList() ?: return
        if (binding.participantId != table.size) return
        if (database.keyShareDao().getMembers(key.id, generation).any { it.participantId == binding.participantId }) return

        val withNewId = extended(table, key.threshold)
        if (ShareGenerations.publicSharesHash(withNewId) != state.publicSharesHash) {
            logger.w("A key state enrolls id ${binding.participantId} of ${key.id} with a table this device's does not extend to; leaving it")
            return
        }
        val now = Clock.System.now()
        database.keyShareDao().upsert(row.copy(publicShares = withNewId.joinToString(",") { it.value.toHex() }, updatedAt = now))
        database.keyShareDao().upsert(
            listOf(
                KeyShareMember(
                    dkgSessionId = key.id,
                    generation = generation,
                    participantId = binding.participantId,
                    memberPublicKey = binding.memberPublicKey,
                    hostPublicKey = binding.hostPublicKey,
                    standing = ShareStanding.STALE,
                )
            )
        )
        logger.i("Id ${binding.participantId} of ${key.id} added for ${binding.memberPublicKey.take(8)}…, to be enrolled")
    }

    // ---- 1. the offer --------------------------------------------------------------------------------------------

    /**
     * Offers [target] the role of admin in [localChatRoom], as [me]. Refused, in words
     * a person can act on, where the room or its key cannot take a new admin.
     */
    suspend fun offer(database: DedunDatabase, localChatRoom: LocalChatRoom, me: HexKey, target: HexKey, nostrPrivateKey: ByteArray? = null): HexKey {
        val group = database.marmotEngine.groupFor(localChatRoom.routingId)
            ?: throw MarmotMissingChatGroupException("No live group for ${localChatRoom.chatRoom.id}")
        check(AdminGovernance.keyedChangesAllowed(group)) { "This group's admins can't be changed from this device" }
        check(me in group.currentAdminIdentities()) { "Only an admin can make someone else one" }
        require(target in AdminGovernance.members(group)) { "They are not in the group any more" }
        if (target in group.currentAdminIdentities()) return ""
        // An offer the group cannot act on until a rotation ends only asks them to wait.
        check(AdminGovernance.adminChangeHold(database, localChatRoom) == null) { AdminGovernance.ROTATION_PENDING_REFUSAL }
        val key = FrostSigningManager.completedKey(database, localChatRoom.chatRoom.id)
            ?: throw IllegalStateException("This device holds no share of the group's key")
        // The ceremony's generation has no room for a new id: the first rotation makes
        // the rows an enrollment extends. Rather than send the admin to rotate by hand
        // and come back, the offer starts that rotation -- which withholds nobody new,
        // so nobody is asked to take part -- and its proposal waits for it in
        // GovernanceFollowThrough (docs/admin-change-rotation.md, Phase 6).
        val rotatesFirst = ShareGenerations.active(database, key).generation == 0
        if (rotatesFirst) {
            checkNotNull(nostrPrivateKey) {
                "Rotate the group's shares once before adding an admin: the key's first generation is its ceremony's, and has no room for a new member"
            }
            ShareRefreshManager.proposeRefresh(database, localChatRoom, me, nostrPrivateKey, key)
        }
        val id = send(database, localChatRoom.chatRoom.id, me, PromotionEvents.OFFER, "", PromotionEvents.offerTags(localChatRoom.chatRoom.id, target))
        announce(
            database, localChatRoom.chatRoom.id, me, ChatMessage.TYPE_ADMIN_OFFERED,
            if (rotatesFirst) "Asked ${nameOf(database, target)} to become an admin. The group's shares are rotated first"
            else "Asked ${nameOf(database, target)} to become an admin",
        )
        return id
    }

    /** An offer to [me] in [chatRoomId] that has not been answered, if there is one. */
    data class Offer(val id: HexKey, val from: HexKey)

    suspend fun pendingOffer(database: DedunDatabase, chatRoomId: String, me: HexKey): Offer? {
        val events = database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(PromotionEvents.OFFER, PromotionEvents.ACCEPT))
        val answered = events.filter { it.kind == PromotionEvents.ACCEPT && it.publicKey == me }
            .mapNotNull { PromotionEvents.value(it.tags, PromotionEvents.OFFER_TAG) }
            .toSet()
        val offer = events.lastOrNull {
            it.kind == PromotionEvents.OFFER && PromotionEvents.value(it.tags, PromotionEvents.TARGET_TAG) == me && it.id !in answered
        } ?: return null
        return Offer(offer.id, offer.publicKey)
    }

    /**
     * Who in [chatRoomId] has been offered admin, by anyone, and has not answered:
     * a second offer would only ask them twice. docs/member-row-actions.md, Phase 5.
     */
    suspend fun unansweredOfferTargets(database: DedunDatabase, chatRoomId: String): Set<HexKey> {
        val events = database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(PromotionEvents.OFFER, PromotionEvents.ACCEPT))
        val answered = events.filter { it.kind == PromotionEvents.ACCEPT }
            .mapNotNull { accept -> PromotionEvents.value(accept.tags, PromotionEvents.OFFER_TAG)?.let { it to accept.publicKey } }
            .toSet()
        return events.filter { it.kind == PromotionEvents.OFFER }
            .mapNotNull { offer ->
                val target = PromotionEvents.value(offer.tags, PromotionEvents.TARGET_TAG) ?: return@mapNotNull null
                target.takeIf { (offer.id to target) !in answered }
            }
            .toSet()
    }

    // ---- 2. the acceptance ---------------------------------------------------------------------------------------

    /**
     * Accepts [offerId], as [me], naming the ChillDKG host key that shares sent to
     * this device will be sealed to: derived from the account key, as a ceremony's is.
     */
    suspend fun accept(database: DedunDatabase, localChatRoom: LocalChatRoom, me: HexKey, nostrPrivateKey: ByteArray, offerId: HexKey) {
        check(ExperimentalFeatures.shareRefresh) { "This build does not take part in enrollment" }
        val host = ChillDkgRitualManager.deriveHostPublicKey(nostrPrivateKey).value.toHex()
        send(database, localChatRoom.chatRoom.id, me, PromotionEvents.ACCEPT, "", PromotionEvents.acceptTags(localChatRoom.chatRoom.id, offerId, host))
        announce(database, localChatRoom.chatRoom.id, me, ChatMessage.TYPE_ADMIN_OFFERED, "You accepted becoming an admin. The group signs it next")
    }

    // ---- stepping down, asked of another admin ---------------------------------------------------------------------

    /**
     * Asks, as [me], to stop being an admin of keyed room [localChatRoom]. An admin
     * cannot propose their own demotion there: the rotation that takes their share
     * away is started by the demotion's proposer, who has to keep a share. So the
     * request goes into the room, and [followStepDowns] on
     * [AdminGovernance.stepDownProposer]'s device proposes it, as that admin pressing
     * **Remove as admin** would. Nothing is committed here.
     */
    suspend fun requestStepDown(database: DedunDatabase, localChatRoom: LocalChatRoom, me: HexKey): HexKey {
        val group = database.marmotEngine.groupFor(localChatRoom.routingId)
            ?: throw MarmotMissingChatGroupException("No live group for ${localChatRoom.chatRoom.id}")
        check(me in group.currentAdminIdentities()) { "You're not an admin of this group" }
        check(AdminGovernance.stepDownProposer(group, me) != null) { "You're the only admin. Make someone else an admin first" }
        check(AdminGovernance.adminChangeHold(database, localChatRoom) == null) { AdminGovernance.ROTATION_PENDING_REFUSAL }
        val id = send(database, localChatRoom.chatRoom.id, me, PromotionEvents.STEP_DOWN, "", PromotionEvents.stepDownTags(localChatRoom.chatRoom.id))
        announce(database, localChatRoom.chatRoom.id, me, ChatMessage.TYPE_ADMIN_OFFERED, "You asked to step down as admin. The group signs it next")
        return id
    }

    /**
     * Takes up the step-down requests whose [AdminGovernance.stepDownProposer] is
     * [me]: proposes each one's demotion for the group's signature, through the same
     * `demote` an admin's own **Remove as admin** runs, so its rotation follows the
     * same way. Run by `GovernanceFollowThrough` whenever a request arrives, and on
     * start.
     *
     * A request is used once. The proposer's device drops it after proposing, or
     * after saying in the room why it could not; every device drops one whose
     * requester is no longer an admin, so a member made an admin again later is not
     * stepped down by what they asked the first time.
     *
     * @return how many demotions were proposed.
     */
    suspend fun followStepDowns(database: DedunDatabase, me: HexKey): Int {
        var proposed = 0
        // Shaped as a request, before anything is deleted: a Wavelength high-water mark
        // shares the number until K3, and is neither a request nor this code's to delete.
        database.marmotInnerEventDao().getByKinds(listOf(PromotionEvents.STEP_DOWN))
            .filter { PromotionEvents.isStepDown(it.content) }
            .forEach { request ->
            val requester = request.publicKey
            val room = database.chatRoomDao().findChatRoomById(request.chatRoomId)
            val group = room?.let { database.marmotEngine.groupFor(it.routingId) }
            if (room == null || group == null || requester !in group.currentAdminIdentities()) {
                database.marmotInnerEventDao().deleteById(request.id)
                return@forEach
            }
            if (room.chatRoom.userPublicKey != me || AdminGovernance.stepDownProposer(group, requester) != me) return@forEach
            // Kept, not used up, while the room's shares are being rotated: taken up by
            // the first pass after the group signs the new ones (docs/admin-change-rotation.md, A7).
            if (AdminGovernance.adminChangeHold(database, room) != null) return@forEach

            val failure = runCatching { database.marmotOutboundDao().demote(room, requester) }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            database.marmotInnerEventDao().deleteById(request.id)
            if (failure == null) {
                proposed++
                logger.i("Proposed ${requester.take(8)}…'s step-down in ${room.chatRoom.id}")
            } else {
                logger.w("Couldn't propose ${requester.take(8)}…'s step-down: ${failure.message}")
                announce(
                    database, room.chatRoom.id, me, ChatMessage.TYPE_CHANGE_NOT_APPLIED,
                    "Couldn't ask the group to remove ${nameOf(database, requester)} as admin, as they asked: ${failure.message}",
                )
            }
        }
        return proposed
    }

    // ---- 3. the proposal, by the device that offered ---------------------------------------------------------------

    /**
     * Whether [offer], accepted, can be proposed in [localChatRoom] now: not while the
     * shares are being rotated, which a first promotion starts itself (Phase 6 of
     * docs/admin-change-rotation.md), and not while the key is still at its
     * ceremony's generation. Still there with nothing running means the rotation
     * the offer started was abandoned or failed: the offer ends, said once, and is
     * dropped on this device, as a step-down request is once used.
     */
    suspend fun readyToPropose(database: DedunDatabase, localChatRoom: LocalChatRoom, me: HexKey, offer: MarmotInnerEvent): Boolean {
        if (AdminGovernance.adminChangeHold(database, localChatRoom) != null) return false
        val key = FrostSigningManager.completedKey(database, localChatRoom.chatRoom.id) ?: return true
        if (ShareGenerations.active(database, key).generation >= 1) return true
        val target = PromotionEvents.value(offer.tags, PromotionEvents.TARGET_TAG)
        announce(
            database, localChatRoom.chatRoom.id, me, ChatMessage.TYPE_CHANGE_NOT_APPLIED,
            "Couldn't make ${target?.let { nameOf(database, it) } ?: "them"} an admin: the rotation it needed did not finish. Make them an admin again",
        )
        database.marmotInnerEventDao().deleteById(offer.id)
        return false
    }

    /** This device's offers that have been accepted and not yet proposed, with their acceptance. */
    suspend fun acceptedOffers(database: DedunDatabase, me: HexKey): List<Pair<MarmotInnerEvent, MarmotInnerEvent>> {
        val events = database.marmotInnerEventDao().getByKinds(listOf(PromotionEvents.OFFER, PromotionEvents.ACCEPT))
        val proposed = database.governanceProposalDao().getAll()
            .filter { it.stage != GovernanceProposalStage.STALE }
            .mapNotNull { it.promotionOfferId }
            .toSet()
        return events.filter { it.kind == PromotionEvents.OFFER && it.publicKey == me && it.id !in proposed }.mapNotNull { offer ->
            val target = PromotionEvents.value(offer.tags, PromotionEvents.TARGET_TAG) ?: return@mapNotNull null
            val acceptance = events.lastOrNull {
                it.kind == PromotionEvents.ACCEPT && it.publicKey == target &&
                    PromotionEvents.value(it.tags, PromotionEvents.OFFER_TAG) == offer.id
            } ?: return@mapNotNull null
            offer to acceptance
        }
    }

    /**
     * Proposes the promotion [offer] and [acceptance] agreed: one batch for the group
     * to sign, the change that makes the member an admin and the key state that binds
     * the next participant id to them, with that id's public share in the table.
     */
    suspend fun propose(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        me: HexKey,
        offer: MarmotInnerEvent,
        acceptance: MarmotInnerEvent,
    ): String {
        val target = requireNotNull(PromotionEvents.value(offer.tags, PromotionEvents.TARGET_TAG))
        val host = requireNotNull(PromotionEvents.value(acceptance.tags, PromotionEvents.HOST_KEY_TAG)?.lowercase()?.takeIf { it.length == 66 }) {
            "The acceptance names no host key"
        }
        val group = database.marmotEngine.groupFor(localChatRoom.routingId)
            ?: throw MarmotMissingChatGroupException("No live group for ${localChatRoom.chatRoom.id}")
        val key = FrostSigningManager.completedKey(database, localChatRoom.chatRoom.id)
            ?: throw IllegalStateException("This device holds no share of the group's key")
        val shares = ShareGenerations.active(database, key)
        val table = requireNotNull(shares.publicShares) { "Generation ${shares.generation} has no public shares" }
        val binding = GroupKeyStateEvent.MemberBinding(table.size, target, host)
        val keyState = GroupKeyStateManager.generationTemplate(
            database, localChatRoom, key, refreshId = null, newBinding = binding, publicShares = extended(table, key.threshold),
        )
        val admins = group.currentAdminIdentities()
        return GovernanceManager.propose(
            database, localChatRoom, me,
            promotionOfferId = offer.id,
            alsoSign = listOf(keyState),
        ) { copy ->
            copy.proposeAppDataUpdate(AdminPolicyV1.COMPONENT_ID, AdminPolicyV1.ofHex(admins + target).encode())
        }
    }

    // ---- 4. the handover, once the change lands --------------------------------------------------------------------

    /** This device's promotions that have landed and not yet been handed over. */
    suspend fun handoversOwed(database: DedunDatabase): List<GovernanceProposal> =
        database.governanceProposalDao().getAll().filter { proposal ->
            proposal.promotionOfferId != null && (
                proposal.stage == GovernanceProposalStage.APPLIED ||
                    (proposal.stage == GovernanceProposalStage.PUBLISHED && (database.marmotEngine.groupFor(proposal.routingId)?.epoch ?: 0) > proposal.epoch)
                )
        }

    /**
     * Hands the new admin what they need to be enrolled: the table and roster of the
     * generation the group signs with, and the key state that binds their id, as the
     * group signed it. Marks [proposal] done.
     */
    suspend fun handover(database: DedunDatabase, proposal: GovernanceProposal, me: HexKey) {
        val offer = database.marmotInnerEventDao().getById(proposal.promotionOfferId ?: return) ?: return
        val target = PromotionEvents.value(offer.tags, PromotionEvents.TARGET_TAG) ?: return
        val content = handoverContent(database, proposal.chatRoomId) ?: return
        send(database, proposal.chatRoomId, me, PromotionEvents.HANDOVER, content, PromotionEvents.handoverTags(proposal.chatRoomId, target))
        database.governanceProposalDao().upsert(proposal.copy(stage = GovernanceProposalStage.DONE, updatedAt = Clock.System.now()))
    }

    /**
     * What a handover says about [chatRoomId]'s key, from this device: the table and
     * roster of the generation the group signs with, and the key state as the group
     * signed it. Null when this device holds no key for the room or no signed state.
     */
    suspend fun handoverContent(database: DedunDatabase, chatRoomId: String): String? {
        val key = FrostSigningManager.completedKey(database, chatRoomId) ?: return null
        val shares = ShareGenerations.active(database, key)
        val table = shares.publicShares ?: return null
        val signedState = GroupKeyStateManager.signedEventFor(database, chatRoomId)?.toEvent() ?: return null
        return buildJsonObject {
            put("key", key.id)
            put("generation", shares.generation)
            put("threshold", key.threshold)
            put("thresholdPublicKey", requireNotNull(key.thresholdPublicKey))
            put("publicShares", buildJsonArray { table.forEach { add(JsonPrimitive(it.value.toHex())) } })
            put("roster", buildJsonArray {
                shares.roster.forEach { entry ->
                    add(buildJsonArray {
                        add(JsonPrimitive(entry.participantId)); add(JsonPrimitive(entry.memberPublicKey))
                        add(JsonPrimitive(entry.hostPublicKey)); add(JsonPrimitive(entry.standing.name))
                    })
                }
            })
            put("keyState", signedState.toJson())
        }.toString()
    }

    // ---- 5. the new admin's device ----------------------------------------------------------------------------------

    /** Handovers to [me] this device has not acted on: no enrollment of its own for the key yet. */
    suspend fun handoversToMe(database: DedunDatabase, me: HexKey): List<MarmotInnerEvent> =
        database.marmotInnerEventDao().getByKinds(listOf(PromotionEvents.HANDOVER)).filter { handover ->
            PromotionEvents.value(handover.tags, PromotionEvents.TARGET_TAG) == me && handover.publicKey != me &&
                runCatching { Json.parseToJsonElement(handover.content).jsonObject["key"]!!.jsonPrimitive.content }.getOrNull()
                    ?.let { keyId -> database.shareRepairDao().getSessionsForKey(keyId).none { it.targetPublicKey == me } } == true
        }

    /**
     * Takes [handover] as the new admin: checks it against the room's own signature,
     * files what it says -- this device's record of the key, its table and roster,
     * and the signed key state -- and asks to be enrolled at its id. Nothing in it is
     * taken on the sender's word: the key state has to be one the room signed and
     * that derives the room, the table has to hash to what it names, and it has to
     * bind the next id to this account under this device's host key.
     */
    suspend fun takeHandover(database: DedunDatabase, localChatRoom: LocalChatRoom, me: HexKey, nostrPrivateKey: ByteArray, handover: MarmotInnerEvent) {
        val json = Json.parseToJsonElement(handover.content).jsonObject
        val keyId = json["key"]!!.jsonPrimitive.content
        val generation = json["generation"]!!.jsonPrimitive.int
        val threshold = json["threshold"]!!.jsonPrimitive.int
        val thresholdPublicKey = json["thresholdPublicKey"]!!.jsonPrimitive.content
        val table = json["publicShares"]!!.jsonArray.map { PublicKey(ByteVector(it.jsonPrimitive.content.hexToByteArray())) }
        val roster = (json["roster"] as JsonArray).map { row ->
            val cells = row.jsonArray
            KeyShareMember(
                dkgSessionId = keyId,
                generation = generation,
                participantId = cells[0].jsonPrimitive.int,
                memberPublicKey = cells[1].jsonPrimitive.content,
                hostPublicKey = cells[2].jsonPrimitive.content.lowercase(),
                standing = ShareStanding.valueOf(cells[3].jsonPrimitive.content),
            )
        }
        val keyStateEvent = Event.fromJson(json["keyState"]!!.jsonPrimitive.content)
        val myHost = ChillDkgRitualManager.deriveHostPublicKey(nostrPrivateKey).value.toHex()
        val newId = table.size - 1

        val state = GroupKeyStateManager.stateFrom(localChatRoom.chatRoom.id, keyStateEvent)
        val binding = GroupKeyStateEvent.MemberBinding.decodeAll(state?.memberBindings).firstOrNull { it.participantId == newId }
        val refusal = when {
            state == null -> "its key state is not one this room signed"
            state.dkgSessionId != keyId || state.generation != generation -> "its key state is for another key or generation"
            !state.thresholdPublicKey.equals(thresholdPublicKey, ignoreCase = true) -> "its key is not the room's"
            ShareGenerations.publicSharesHash(table) != state.publicSharesHash -> "its table is not the one the group signed"
            binding == null || binding.memberPublicKey != me || !binding.hostPublicKey.equals(myHost, ignoreCase = true) -> "the group did not bind the new id to this device"
            roster.map { it.participantId } != table.indices.toList() -> "its roster does not match its table"
            else -> null
        }
        if (refusal != null) {
            logger.w("Refusing a handover in ${localChatRoom.chatRoom.id}: $refusal")
            announce(database, localChatRoom.chatRoom.id, me, ChatMessage.TYPE_CHANGE_NOT_APPLIED, "Couldn't take a share of the group's key: $refusal")
            return
        }

        val now = Clock.System.now()
        val key = database.dkgSessionDao().getSessionById(keyId) ?: DkgSession(
            id = keyId,
            chatRoomId = localChatRoom.chatRoom.id,
            coordinatorPublicKey = handover.publicKey,
            userPublicKey = me,
            threshold = threshold,
            participantCount = newId,
            stage = DkgRitualStage.COMPLETE,
            hostPublicKey = myHost,
            round1Random = "00".repeat(32),
            round2AuxRandom = "00".repeat(32),
            thresholdPublicKey = thresholdPublicKey,
        ).also { database.dkgSessionDao().upsert(it) }
        if (database.keyShareDao().getGeneration(keyId, generation) == null) {
            database.keyShareDao().upsert(
                KeyShareGeneration(
                    dkgSessionId = keyId,
                    generation = generation,
                    secretShare = null,
                    publicShares = table.joinToString(",") { it.value.toHex() },
                    state = ShareGenerationState.ACTIVE,
                    activatedAt = now,
                )
            )
            database.keyShareDao().upsert(roster)
        }
        GroupKeyStateManager.record(database, localChatRoom.chatRoom.id, keyStateEvent)
        ShareRepairManager.requestRepair(database, localChatRoom, me, nostrPrivateKey, key, targetId = newId, nParticipants = newId)
    }

    // ---- plumbing ----------------------------------------------------------------------------------------------------

    /** Puts an inner event on the outbound queue, as every manager's messages go. Returns its id. */
    private suspend fun send(database: DedunDatabase, chatRoomId: String, me: HexKey, kind: Int, content: String, tags: Array<Array<String>>): HexKey {
        val createdAt = Clock.System.now().epochSeconds
        val id = EventHasher.hashId(pubKey = me, createdAt = createdAt, kind = kind, tags = tags, content = content)
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id, publicKey = me, kind = kind, createdAt = Instant.fromEpochSeconds(createdAt),
                tags = tags, content = content, chatRoomId = chatRoomId,
            )
        )
        return id
    }

    private suspend fun announce(database: DedunDatabase, chatRoomId: String, me: HexKey, type: String, content: String) {
        database.chatMessageDao().upsert(
            ChatMessage(
                content = content, messageType = type, chatRoomId = chatRoomId, senderPublicKey = me,
                isUserMessage = true, giftWrapPayloadId = null, marmotGroupEventId = null, marmotInnerEventId = null,
            )
        )
    }

    private suspend fun nameOf(database: DedunDatabase, publicKey: HexKey): String =
        database.profileDao().getProfileByPublicKey(publicKey)?.humanReadableNameOrPubkey() ?: publicKey.take(8)
}
