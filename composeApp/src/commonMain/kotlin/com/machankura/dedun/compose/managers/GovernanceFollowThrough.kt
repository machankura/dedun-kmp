package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.frost.GovernanceSupportEvent
import com.machankura.dedun.compose.nostr.frost.PromotionEvents
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GovernanceProposal
import com.machankura.dedun.compose.database.model.GovernanceProposalStage
import com.machankura.dedun.compose.database.model.types.ShareStanding

/**
 * What a governed room's signed changes need after they land, taken by the device
 * that proposed them, which holds the key pair those steps need.
 * docs/member-admin-promotion.md, Phases 8 and 9.
 *
 * Runs beside `CeremonyFollowThrough`, for the same reason: it needs the active key
 * pair and stops with it. Each pass reads everything again from the database, so a
 * step interrupted halfway, or a change that landed while the app was closed, is
 * taken on the next one.
 */
object GovernanceFollowThrough {
    private val logger = Logger.withTag("GovernanceFollowThrough")
    private val mutex = Mutex()

    /** Follows this device's proposals until cancelled. */
    suspend fun observe(database: DedunDatabase, keyPair: KeyPair) {
        combine(
            database.governanceProposalDao().observeAll(),
            // A member saying their device follows signed commits may be the last one
            // a room was waiting on before its rule can be switched on.
            database.marmotInnerEventDao().observeCountByKinds(
                listOf(PromotionEvents.OFFER, PromotionEvents.ACCEPT, PromotionEvents.HANDOVER, PromotionEvents.STEP_DOWN, GovernanceSupportEvent.KIND)
            ),
            database.groupSignedEventDao().observeByKind(GroupKeyStateEvent.KIND),
            // A rotation abandoned or failed releases what waited on it, with no key state filed.
            database.shareRefreshDao().observeRunningCount(),
        ) { _, _, _, _ -> Unit }
            .conflate()
            .collect {
                try {
                    pass(database, keyPair)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.e("A governance follow-through pass failed", e)
                }
            }
    }

    /** One pass over everything owed. */
    suspend fun pass(database: DedunDatabase, keyPair: KeyPair) = mutex.withLock {
        rotationsOwed(database).forEach { proposal -> rotateAway(database, keyPair, proposal) }
        val me = keyPair.pubKey.toHexKey()
        // A keyed room's rule, switched on once every member can follow it, by the one
        // device whose it is to switch. docs/remove-convenient-groups.md, "After the build".
        GovernanceSupport.switchWhereOwed(database, me)
        if (!ExperimentalFeatures.shareRefresh) return@withLock
        val privateKey = keyPair.privKey ?: return@withLock

        // Phase 8, the device that offered: an accepted offer is proposed for the
        // group's signature, once; a promotion that landed is handed over.
        PromotionManager.acceptedOffers(database, me).forEach { (offer, acceptance) ->
            if (offer.id in failedOffers) return@forEach
            val room = database.chatRoomDao().findChatRoomById(offer.chatRoomId) ?: return@forEach
            // Waits while the shares are being rotated, rather than failing
            // (docs/admin-change-rotation.md, A7): proposed on the pass after.
            if (AdminGovernance.adminChangeHold(database, room) != null) return@forEach
            runCatching { PromotionManager.propose(database, room, me, offer, acceptance) }.onFailure {
                if (it is CancellationException) throw it
                failedOffers += offer.id
                logger.w("Couldn't propose the promotion ${offer.id.take(8)}…: ${it.message}")
                announce(database, offer.chatRoomId, me, "Couldn't ask the group to make them an admin: ${it.message}")
            }
        }
        // An admin who asked to step down, when this device is the one to propose it.
        PromotionManager.followStepDowns(database, me)

        PromotionManager.handoversOwed(database).forEach { proposal ->
            runCatching { PromotionManager.handover(database, proposal, me) }.onFailure {
                if (it is CancellationException) throw it
                logger.w("Couldn't hand over ${proposal.id.take(8)}…: ${it.message}")
            }
        }

        // Phase 8, the new admin's device: a handover is checked and taken.
        PromotionManager.handoversToMe(database, me).forEach { handover ->
            if (handover.id in failedHandovers) return@forEach
            val room = database.chatRoomDao().findChatRoomById(handover.chatRoomId) ?: return@forEach
            runCatching { PromotionManager.takeHandover(database, room, me, privateKey, handover) }.onFailure {
                if (it is CancellationException) throw it
                failedHandovers += handover.id
                logger.w("Couldn't take the handover ${handover.id.take(8)}…: ${it.message}")
            }
        }
    }

    /** Offers and handovers that failed once in this process: not retried on every pass. */
    private val failedOffers = mutableSetOf<HexKey>()
    private val failedHandovers = mutableSetOf<HexKey>()

    /**
     * This device's changes that have landed and owe a rotation without a member
     * they demoted or removed (Phase 9). Landed means a relay accepted it -- marked
     * [GovernanceProposalStage.APPLIED] -- or, for one confirmed while this device
     * was not watching, that the group is past its epoch and no longer names the
     * member an admin, or no longer holds them at all.
     */
    suspend fun rotationsOwed(database: DedunDatabase): List<GovernanceProposal> =
        database.governanceProposalDao().getAll().filter { proposal ->
            val member = proposal.thenRotateWithout ?: return@filter false
            when (proposal.stage) {
                GovernanceProposalStage.APPLIED -> true
                GovernanceProposalStage.PUBLISHED -> {
                    val group = database.marmotEngine.groupFor(proposal.routingId) ?: return@filter false
                    group.epoch > proposal.epoch &&
                        (member !in group.currentAdminIdentities() || member !in group.currentMemberIdentities())
                }
                else -> false
            }
        }

    /**
     * Starts the rotation without [proposal]'s member, and marks it done either way:
     * a rotation that could not start -- one already running, a holder this device
     * cannot reach -- is said in the room, and the Shares section offers it from
     * there, as it does after any removal.
     */
    private suspend fun rotateAway(database: DedunDatabase, keyPair: KeyPair, proposal: GovernanceProposal) {
        val member = proposal.thenRotateWithout ?: return
        val room = database.chatRoomDao().findChatRoomById(proposal.chatRoomId) ?: return
        val me = keyPair.pubKey.toHexKey()
        val privateKey = keyPair.privKey ?: return
        val failure = runCatching {
            val rotation = ShareRefreshManager.proposeWithout(database, room, me, privateKey, member)
            // A rotation already running is handed back. If it was proposed before
            // the change and deals to the member, it is not the one owed: it would
            // leave them a live share of the next generation
            // (docs/admin-change-rotation.md, H1).
            val id = ShareGenerations.at(database, database.dkgSessionDao().getSessionById(rotation.dkgSessionId)!!, rotation.baseGeneration)
                ?.signerIds?.get(member)
            check(id == null || rotation.withheldStandings()[id] == ShareStanding.RETIRED) {
                "a rotation that still deals to them was already running"
            }
        }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        database.governanceProposalDao().upsert(proposal.copy(stage = GovernanceProposalStage.DONE, updatedAt = Clock.System.now()))
        if (failure != null) {
            logger.w("Could not rotate ${member.take(8)}… away after ${proposal.id.take(8)}…: ${failure.message}")
            announce(database, proposal.chatRoomId, me, "Couldn't start rotating the shares without a member who is no longer an admin: ${failure.message}. Rotate them from the Shares section")
        }
    }

    private suspend fun announce(database: DedunDatabase, chatRoomId: String, me: HexKey, content: String) {
        database.chatMessageDao().upsert(
            ChatMessage(
                content = content,
                messageType = ChatMessage.TYPE_CHANGE_NOT_APPLIED,
                chatRoomId = chatRoomId,
                senderPublicKey = me,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
            )
        )
    }
}
