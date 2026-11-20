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
    suspend fun offer(database: DedunDatabase, localChatRoom: LocalChatRoom, me: HexKey, target: HexKey): HexKey {
        val group = database.marmotEngine.groupFor(localChatRoom.routingId)
            ?: throw MarmotMissingChatGroupException("No live group for ${localChatRoom.chatRoom.id}")
        check(AdminGovernance.keyedChangesAllowed(group)) { "This group's admins can't be changed from this device" }
        check(me in group.currentAdminIdentities()) { "Only an admin can make someone else one" }
        require(target in AdminGovernance.members(group)) { "They are not in the group any more" }
        if (target in group.currentAdminIdentities()) return ""
        val key = FrostSigningManager.completedKey(database, localChatRoom.chatRoom.id)
            ?: throw IllegalStateException("This device holds no share of the group's key")
        check(ShareGenerations.active(database, key).generation >= 1) {
            "Rotate the group's shares once before adding an admin: the key's first generation is its ceremony's, and has no room for a new member"
        }
        val id = send(database, localChatRoom.chatRoom.id, me, PromotionEvents.OFFER, "", PromotionEvents.offerTags(localChatRoom.chatRoom.id, target))
        announce(database, localChatRoom.chatRoom.id, me, ChatMessage.TYPE_ADMIN_OFFERED, "Asked ${nameOf(database, target)} to become an admin")
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

    // ---- 3. the proposal, by the device that offered ---------------------------------------------------------------

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
