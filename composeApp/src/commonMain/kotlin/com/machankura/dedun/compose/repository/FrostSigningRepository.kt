package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.wallet.GroupSpend
import com.machankura.dedun.compose.wallet.lightning.GroupSweep
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.psbt.Psbt
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.machankura.dedun.compose.database.model.FrostSignerMessage
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.FrostSignerTally
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession

/**
 * Reads, opens and answers FROST signing sessions.
 *
 * Advancing one is the inbound path's job, with one exception: a session waiting
 * on this device's owner does not advance until [approve] is called, because it
 * will not sign on their behalf until they say so.
 */
interface FrostSigningRepository {
    fun observeSessionById(sessionId: String): Flow<FrostSigningSession?>

    /**
     * Every session the room has run, newest first, each with what it signs.
     *
     * Unlike a ceremony, signing recurs -- and two sessions can be open at once,
     * which is why the proposal list shows all of them rather than the latest.
     */
    fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>>

    /**
     * How far each of a room's sessions has got at gathering what it needs, as
     * a count of members per session per kind of message.
     *
     * A companion to [observeSessionsForChatRoom] rather than part of it: the
     * session rows say which stage a proposal is in, and this says how far
     * through that stage it is. Kept apart because the counts change far more
     * often than the rows do -- every member's nonce moves one -- and because
     * carrying the messages themselves would carry every nonce payload the room
     * has ever exchanged to a screen that only wants to count them.
     *
     * Only members holding a share of the key are counted; see the query.
     */
    fun observeSignerTalliesForChatRoom(chatRoomId: String): Flow<List<FrostSignerTally>>

    fun observeMessages(sessionId: String): Flow<List<FrostSignerMessage>>

    /**
     * Who holds a share of the key a ceremony made, and so can take part in a
     * session signing with it.
     *
     * The members who put a host key into the ceremony -- the same rows
     * `FrostSigningManager` places signer ids over, so this cannot disagree with
     * whose nonces, partial signatures and refusals the session counts. A
     * ceremony cannot complete without every participant's host key, so for a
     * completed one this is the whole participant set.
     *
     * Not the room's roster. A room can hold members who were added after its
     * ceremony and hold no share, and a subgroup's ceremony runs in the parent's
     * room, which holds everybody the subgroup is not. A screen listing the room
     * would show people the session is not waiting on and cannot be answered by.
     */
    fun observeShareHolders(dkgSessionId: String): Flow<Set<HexKey>>

    /**
     * The events a session signs, in the order its proposal fixed.
     *
     * Separate from the session because a session signs a batch, and what
     * separates one event of it from another is forced by FROST rather than
     * chosen — see [FrostSigningItem].
     */
    fun observeItems(sessionId: String): Flow<List<FrostSigningItem>>

    suspend fun getItems(sessionId: String): List<FrostSigningItem>

    suspend fun getSessionById(sessionId: String): FrostSigningSession?

    /** Whether this room holds a key it can sign with, so the UI offers nothing that would fail. */
    suspend fun canSign(chatRoomId: String): Boolean

    /**
     * Opens a session asking the group to sign an event with these fields. The
     * author is the room's own key, not the proposer's, and is filled in here --
     * the room's id, since signing runs at the path the room was derived at.
     */
    suspend fun proposeSigning(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        kind: Kind,
        tags: Array<Array<String>>,
        content: String
    ): FrostSigningSession?

    /**
     * Opens one session over several events, so a group answers once instead of
     * once per event.
     *
     * Four group events and one approval whatever the size, but k independent
     * FROST instances underneath -- there is no such thing as one signature over
     * k messages, and no way to share a nonce between two of them. See
     * [FrostSigningItem].
     *
     * Two things a caller has to decide before reaching for this.
     *
     * **A batch is only as available as its worst item.** It is all-or-nothing:
     * if any event cannot be aggregated the session fails and none of them are
     * applied. Events that do not belong together should not travel together.
     *
     * **A retry is a new batch, never this one again.** Its items' nonce seeds
     * have already been published against an aggregate, and reusing one would
     * produce two partial signatures over a single secret nonce -- which is how
     * a share is extracted. Propose afresh; the manager mints new seeds by
     * construction.
     */
    suspend fun proposeSigningBatch(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        events: List<EventTemplate<*>>
    ): FrostSigningSession?

    /**
     * Opens a session over an event and the events that name it, for a batch
     * that is not a flat list: a chapter and its chunks, where each chunk
     * carries the chapter's id.
     *
     * That id cannot be worked out by a caller -- it is a hash over the group's
     * key at the room's derivation path, neither of which is resolved until the
     * proposal is made -- so [lead] is built first and handed to [dependents],
     * which returns the events referring to it. The lead is item 0 and so is
     * applied first, which is what anything holding a foreign key to it needs.
     *
     * Everything the flat form says still holds: all-or-nothing, only as
     * available as its worst item, and a retry is a new batch.
     */
    suspend fun proposeSigningBatch(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        lead: EventTemplate<*>,
        dependents: (lead: Event) -> List<EventTemplate<*>>
    ): FrostSigningSession?

    /**
     * Re-publishes the messages this device wrote for a session, for the members
     * it has heard nothing from.
     *
     * For a session left waiting on a member who has nothing to answer with: a
     * group event that decrypted to nothing because MLS had moved past the
     * generation it arrived at, a disappearing-message group that expired it off
     * the relay, a member added to the room after the proposal was made. A
     * resent message is a new message, so it is readable where the first one was
     * not.
     *
     * **The coordinator's own messages, and nobody else's.** A member's nonce is
     * read by nobody but the coordinator, who already has it -- which is how it
     * knows who is quiet -- so there is nothing of theirs to repeat. And a
     * message relayed by somebody else would be filed under the relayer, which
     * for a refusal is a way to manufacture one. What the resend does for a
     * silent member is name them, so their own device republishes what it
     * already said.
     *
     * Returns false when there was nothing to do: not this device's session,
     * already abandoned, nobody owed, or inside the cooldown.
     */
    suspend fun resend(localChatRoom: LocalChatRoom, sessionId: String): Boolean

    /**
     * Asks again about several sessions, spaced out, and says how many published.
     *
     * One call rather than a loop over [resend], because the spacing is the
     * point: every resend is a two-message burst, and launching several at once
     * would put all of them in one second -- which is how a proposal was lost
     * once already. See `FrostSigningManager.RESEND_SPACING`.
     *
     * A session that publishes nothing is stepped over, not a reason to abandon
     * the rest, so the count returned may be less than the list given.
     */
    suspend fun resendAll(localChatRoom: LocalChatRoom, sessionIds: List<String>): Int

    /**
     * The share holders a session is still waiting on, as the manager counts
     * them -- who has said nothing while it collects nonces, and which chosen
     * signer has yet to sign once it collects partial signatures.
     *
     * Asked rather than derived from the messages a screen already holds. The
     * same set decides whether [resend] publishes anything, and a second
     * reading of it is a second thing to keep in step: a screen offering a knock
     * the manager would refuse, or hiding one it would make.
     */
    suspend fun membersOwing(session: FrostSigningSession): Set<HexKey>

    /**
     * Takes every session in a room whose owner has answered as far as its
     * stored messages allow, for the steps no arriving message will drive.
     *
     * A device killed part way through a session's turn -- the signer set
     * recorded, its own partial signature not yet published -- is waiting on a
     * message that has already been delivered and will not arrive twice. This is
     * what gets it moving again, and it publishes nothing its owner has not
     * already agreed to.
     */
    suspend fun resumeAnswered(localChatRoom: LocalChatRoom)

    /** Agrees to sign, letting the session publish this device's part and run on. */
    suspend fun approve(localChatRoom: LocalChatRoom, sessionId: String)

    /** Refuses, and says so, since a t-of-n group can proceed without this member. */
    suspend fun decline(localChatRoom: LocalChatRoom, sessionId: String)

    /**
     * The events a session was opened over, signature or no signature.
     *
     * What [signedEvents] gives back once the group has answered, and what it
     * refuses to give back before then. A caller wanting an id -- to open a
     * second session over events that name one of these -- needs it while the
     * first session is still running, and the id is settled at proposal time:
     * it is the hash the signers are putting their shares behind.
     */
    fun unsignedEvents(items: List<FrostSigningItem>): List<Event>

    /** The finished events, or empty while any of them is still running. */
    fun signedEvents(items: List<FrostSigningItem>): List<Event>

    /**
     * Opens a session over one transaction from the group's wallet, or null when
     * the spend could not be proposed -- it failed a check, or the group has no
     * wallet on [chain]. See `FrostSigningManager.proposeSpend`.
     */
    suspend fun proposeSpend(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        psbt: Psbt,
        chain: Chain,
    ): FrostSigningSession?

    /**
     * Opens a session over one transaction from [descendantChatRoomId]'s guarded
     * wallet, signed through this room's guardian leaf, or null when it could not
     * be proposed. See `FrostSigningManager.proposeGuardianSpend`.
     */
    suspend fun proposeGuardianSpend(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        psbt: Psbt,
        descendantChatRoomId: String,
        chain: Chain,
    ): FrostSigningSession?

    /** The spend a transaction session signs, checked again; null for anything else. */
    suspend fun checkedSpend(sessionId: String): GroupSpend.Checked?

    /** The sweep a sweep session signs, checked again; null for anything else. */
    suspend fun checkedSweep(sessionId: String): GroupSweep.Checked? = null

    /** The coins the room's open spends are already spending. */
    suspend fun reservedOutPoints(chatRoomId: String): Set<OutPoint>

    /** The change indices under [account] the room's open spends already pay back to. */
    suspend fun reservedChangeIndices(chatRoomId: String, account: GroupWalletDerivation.Account): Set<Long>

    /** The receive indices of [account], a descendant's guarded wallet, the room's open spends already fund. */
    suspend fun reservedFundingIndices(chatRoomId: String, account: GroupWalletDerivation.Account): Set<Long>

    /** Every spend the room has proposed, newest first, as it moves from proposed to confirmed. */
    fun observeGroupTransactions(chatRoomId: String): Flow<List<GroupTransaction>>

    companion object {
        val NO_OP_FROST_SIGNING_REPOSITORY: FrostSigningRepository = object : FrostSigningRepository {
            override fun observeSessionById(sessionId: String): Flow<FrostSigningSession?> = flowOf(null)

            override fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>> =
                flowOf(emptyList())

            override fun observeSignerTalliesForChatRoom(
                chatRoomId: String
            ): Flow<List<FrostSignerTally>> = flowOf(emptyList())

            override fun observeMessages(sessionId: String): Flow<List<FrostSignerMessage>> =
                flowOf(emptyList())

            override fun observeShareHolders(dkgSessionId: String): Flow<Set<HexKey>> =
                flowOf(emptySet())

            override fun observeItems(sessionId: String): Flow<List<FrostSigningItem>> =
                flowOf(emptyList())

            override suspend fun getItems(sessionId: String): List<FrostSigningItem> = emptyList()

            override suspend fun getSessionById(sessionId: String): FrostSigningSession? = null

            override suspend fun canSign(chatRoomId: String): Boolean = false

            override suspend fun proposeSigning(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                kind: Kind,
                tags: Array<Array<String>>,
                content: String
            ): FrostSigningSession? = null

            override suspend fun proposeSigningBatch(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                events: List<EventTemplate<*>>
            ): FrostSigningSession? = null

            override suspend fun proposeSigningBatch(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                lead: EventTemplate<*>,
                dependents: (lead: Event) -> List<EventTemplate<*>>
            ): FrostSigningSession? = null

            override suspend fun resend(
                localChatRoom: LocalChatRoom,
                sessionId: String
            ): Boolean = false

            override suspend fun resendAll(
                localChatRoom: LocalChatRoom,
                sessionIds: List<String>
            ): Int = 0

            override suspend fun membersOwing(session: FrostSigningSession): Set<HexKey> = emptySet()

            override suspend fun resumeAnswered(localChatRoom: LocalChatRoom) = Unit

            override suspend fun approve(localChatRoom: LocalChatRoom, sessionId: String) = Unit

            override suspend fun decline(localChatRoom: LocalChatRoom, sessionId: String) = Unit

            override fun unsignedEvents(items: List<FrostSigningItem>): List<Event> = emptyList()

            override suspend fun proposeSpend(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                psbt: Psbt,
                chain: Chain,
            ): FrostSigningSession? = null

            override suspend fun proposeGuardianSpend(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                psbt: Psbt,
                descendantChatRoomId: String,
                chain: Chain,
            ): FrostSigningSession? = null

            override suspend fun checkedSpend(sessionId: String): GroupSpend.Checked? = null

            override suspend fun reservedOutPoints(chatRoomId: String): Set<OutPoint> = emptySet()
            override suspend fun reservedChangeIndices(chatRoomId: String, account: GroupWalletDerivation.Account): Set<Long> = emptySet()
            override suspend fun reservedFundingIndices(chatRoomId: String, account: GroupWalletDerivation.Account): Set<Long> = emptySet()

            override fun observeGroupTransactions(chatRoomId: String): Flow<List<GroupTransaction>> = flowOf(emptyList())

            override fun signedEvents(items: List<FrostSigningItem>): List<Event> = emptyList()
        }
    }
}
