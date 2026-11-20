package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GovernanceProposal
import com.machankura.dedun.compose.database.model.GovernanceProposalStage

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
        database.governanceProposalDao().observeAll()
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
    }

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
        val failure = runCatching { ShareRefreshManager.proposeWithout(database, room, me, privateKey, member) }.exceptionOrNull()
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
