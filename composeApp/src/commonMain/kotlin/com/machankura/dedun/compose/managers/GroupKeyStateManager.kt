package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupParentage

/**
 * Puts what key a room signs with to the group, and files what the group says.
 *
 * [propose] asks for it before the room exists; every device [record]s the state
 * when the signing session behind it produces a signature, or [adopt]s it the
 * moment the room it names comes into being. All three ends land on the same
 * [GroupKeyState] row, which is what a signing request is resolved against --
 * see `FrostSigningManager.completedKey`.
 *
 * ### Why this is proposed rather than announced
 *
 * It used to be announced: the coordinator wrote the row, said so in the room,
 * and every receiver kept the statement if the room's id rederived from the key
 * it named. That check is still here and still the thing safety rests on -- a
 * state that does not rederive the room it names is dropped, whoever it came
 * from -- but it left the *first* thing a group ever does as the one thing one
 * member decides alone.
 *
 * So it goes through the same door everything else the group says goes through.
 * A room's key state is a `FrostSigningEvents.PROPOSAL` over a
 * [GroupKeyStateEvent], and the state exists when a quorum has signed it, on
 * every device at once, authored by the group's own key.
 *
 * ### Why it is signed before the room is created
 *
 * ```
 *  ceremony completes in the NIP-17 room
 *  coordinator --[ 30320 proposal over a 30326 ]-> the same NIP-17 room
 *  ...a quorum signs, on gift wraps...
 *  every device holds the signed 30326, naming a room nobody has made yet
 *  coordinator creates the admin room --> adopt() files the state as it appears
 * ```
 *
 * It used to be the new room's first application message, which meant the group
 * created a room and only afterwards agreed what it signs with. Two things were
 * wrong with that order. The room's founding fact was settled after the
 * founding, so a session that never reached a quorum left a live room whose
 * every member had to fall back to rederiving; and the members who had to sign
 * it were exactly the ones the room had just been created to hold, so a member
 * whose key package could not be found was excluded from a decision they held a
 * share of.
 *
 * Signed first, the state is a precondition of the room rather than an
 * afterthought: the group agrees what it signs with while it is still only a
 * ceremony and a NIP-17 chat, and the room is created already knowing. That is
 * what puts `FrostSigningManager` on two transports -- see [propose].
 */
object GroupKeyStateManager {
    private const val TAG = "GroupKeyStateManager"

    private val logger = Logger.withTag(TAG)

    /** The key state a room signs under, or null while it has none. */
    suspend fun keyStateFor(database: DedunDatabase, chatRoomId: String): GroupKeyState? =
        database.groupKeyStateDao().getByChatRoomId(chatRoomId)

    /**
     * Asks the group to say what the room it is about to make signs with.
     *
     * Called once, by the ceremony's coordinator, in the NIP-17 room the
     * ceremony ran in -- before the admin room exists. [localChatRoom] is
     * therefore where the session *runs*, and is not usually the room the state
     * is *about*: that one is derived here, from [key] at [path], because the
     * room's id and the key it signs as are the same value.
     *
     * No row is written. The state is not this device's to assert, and there is
     * not yet a room to hang one on; it appears on every device together when
     * the room is created and [adopt] files the event a quorum signed.
     *
     * The session signs at the subject room's path, so what comes back is a
     * state signed by the very key it names. A reader needs nothing but the
     * event to check that -- not even the room, which is why this can be signed
     * before the room exists at all.
     *
     * Refuses to propose from a room that is neither the subject nor the
     * ceremony's own, because `FrostSigningManager.signingPath` will resolve no
     * path for one and the group would sign as its bare threshold key -- an
     * identity no room answers to. Refuses a state that fails
     * [GroupKeyState.verifies] for the same class of reason: it would mean this
     * device derived the subject room from one key and proposed another.
     *
     * [key] is handed to the signing session rather than looked up from the
     * room, because no room here has a key state -- establishing one is exactly
     * what this session exists to do.
     *
     * A session of one, always, and never batched with anything else. A batch is
     * all-or-nothing, so it is only as available as its worst item -- and this is
     * the statement every other session in the group is opened against. Bundling
     * it with a dialect would make the room's ability to sign at all depend on
     * that dialect's aggregation succeeding.
     */
    suspend fun propose(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        key: DkgSession,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH,
        parent: SubgroupParentage? = null,
        createdAt: Long = Clock.System.now().epochSeconds
    ): FrostSigningSession {
        val signingRoomId = localChatRoom.chatRoom.id
        val thresholdPublicKey = key.thresholdPublicKey
            ?: throw IllegalStateException(
                "Ceremony ${key.id} has produced no key for $signingRoomId to propose"
            )

        // The room this is about: the group's key walked to the admin path,
        // which is that room's id. It need not exist yet, and in the flow this
        // was written for it does not.
        val chatRoomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path)

        check(chatRoomId == signingRoomId || key.chatRoomId == signingRoomId) {
            "Room $signingRoomId is neither $chatRoomId nor where ceremony ${key.id} was held, " +
                "so it has no standing to propose a key state"
        }

        val state = GroupKeyState(
            chatRoomId = chatRoomId,
            dkgSessionId = key.id,
            thresholdPublicKey = thresholdPublicKey,
            derivationPath = SharedKeyDerivation.formatPath(path),
            announcedBy = userPublicKey,
            announcedAt = Instant.fromEpochSeconds(createdAt),
            parentChatRoomId = parent?.parentChatRoomId,
            birthCertificateJson = parent?.certificate?.toJson()
        )

        check(state.verifies()) {
            "Room $chatRoomId is not derived from $thresholdPublicKey at ${state.derivationPath}"
        }

        // Refused here rather than discovered by the group. Every device that
        // receives this state runs the same check in `parentageOf` and drops the
        // state when it fails, so proposing one this device would not believe
        // spends a quorum's attention on a statement nobody will keep.
        if (parent != null) {
            check(
                SubgroupBirthCertificateEvent.certifies(
                    event = parent.certificate,
                    subgroupChatRoomId = chatRoomId,
                    parentChatRoomId = parent.parentChatRoomId
                )
            ) {
                "Room ${parent.parentChatRoomId} has not certified $chatRoomId as its subgroup"
            }
        }

        logger.i(
            "Proposing key ${state.thresholdPublicKey} for room $chatRoomId at " +
                "${state.derivationPath}, from $signingRoomId"
        )

        return FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            kind = GroupKeyStateEvent.KIND,
            tags = GroupKeyStateEvent.assembleTags(
                chatRoomId = chatRoomId,
                dkgSessionId = key.id,
                path = path,
                parent = parent
            ),
            content = thresholdPublicKey,
            key = key,
            createdAt = createdAt
        )
    }

    /**
     * Files a state the group signed, or drops it and says why.
     *
     * Storing is all this adds to [stateFrom], which is where the deciding
     * happens -- kept apart so the check a member's safety rests on can be
     * exercised without standing up a database.
     *
     * A state about a room this device does not have is kept rather than filed.
     * That is the normal case now and not an error: the group signs its state
     * before creating the room it describes, so the event routinely lands
     * minutes before the room does. [adopt] is the other end of that, and the
     * signed event waits on file in the meantime.
     */
    suspend fun record(
        database: DedunDatabase,
        chatRoomId: String,
        innerEvent: Event
    ): GroupKeyState? {
        val state = stateFrom(chatRoomId, innerEvent) ?: return null

        if (database.chatRoomDao().findChatRoomById(state.chatRoomId) == null) {
            logger.i(
                "The group signed a key state for ${state.chatRoomId}, which does not exist " +
                    "yet; holding it until it does"
            )
            return null
        }

        val filed = database.groupKeyStateDao().replace(state)
        // `replace` re-stamps what it files, so the row is compared on what it says.
        if (filed.generation == state.generation && filed.announcedAt == state.announcedAt) {
            retireOlderShares(database, state)
            applyBindings(database, state)
        }
        return filed
    }

    /**
     * The group has signed with generation [state]'s generation, which proves t
     * members hold working shares of it: the older generations' shares go
     * (docs/frost-share-refresh.md, D6). Only when this device holds that
     * generation, active, with the same public-share table the group signed --
     * a device behind the group, or one that disagrees with it about who holds
     * what, keeps what it has and says so.
     */
    private suspend fun retireOlderShares(database: DedunDatabase, state: GroupKeyState) {
        val generation = state.generation ?: return
        val key = database.dkgSessionDao().getSessionById(state.dkgSessionId) ?: return
        val shares = ShareGenerations.at(database, key, generation)
        if (shares == null || ShareGenerations.active(database, key).generation != generation) {
            logger.w("The group signs with generation $generation of ${key.id}, which this device does not hold; keeping what it has")
            return
        }
        val local = shares.publicShares?.let(ShareGenerations::publicSharesHash)
        if (local != state.publicSharesHash) {
            logger.w("The group's generation $generation of ${key.id} has a public-share table this device disagrees with; keeping what it has")
            return
        }
        FrostSigningManager.failRotated(database, key.id, generation)
        ShareGenerations.wipeBefore(database, key, generation)
    }

    /**
     * Rebinds the ids a signed state names to their new identities, in this
     * device's roster of that generation (docs/frost-share-refresh.md, Phase 10b).
     * Only an id that is not live can change hands: a live one is somebody's
     * working share, and binding it elsewhere would leave two holders of one point.
     * Then any repair that was waiting on the binding goes on.
     */
    private suspend fun applyBindings(database: DedunDatabase, state: GroupKeyState) {
        val generation = state.generation ?: return
        val bindings = GroupKeyStateEvent.MemberBinding.decodeAll(state.memberBindings)
        if (bindings.isEmpty()) return
        val members = database.keyShareDao().getMembers(state.dkgSessionId, generation)
        val changed = bindings.mapNotNull { binding ->
            val row = members.firstOrNull { it.participantId == binding.participantId } ?: return@mapNotNull null
            if (row.standing == ShareStanding.LIVE) {
                logger.w("Key state binds live id ${binding.participantId} of ${state.dkgSessionId} elsewhere; ignoring that binding")
                return@mapNotNull null
            }
            if (row.memberPublicKey == binding.memberPublicKey && row.hostPublicKey.equals(binding.hostPublicKey, ignoreCase = true)) return@mapNotNull null
            row.copy(memberPublicKey = binding.memberPublicKey, hostPublicKey = binding.hostPublicKey, standing = ShareStanding.STALE, updatedAt = Clock.System.now())
        }
        if (changed.isEmpty()) return
        database.keyShareDao().upsert(changed)
        ShareRepairManager.resumeAwaitingBinding(database, state.dkgSessionId)
    }

    /**
     * Asks the group to bind participant id [participantId] to a new identity
     * (docs/frost-share-refresh.md, Phase 10b): [memberPublicKey], asking under
     * ChillDKG host key [hostPublicKey]. The id must already be withheld -- the
     * old identity's share made worthless by a refresh -- or a repair to the new
     * one would hand it the point the old device still has.
     */
    suspend fun proposeRebind(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        key: DkgSession,
        participantId: Int,
        memberPublicKey: HexKey,
        hostPublicKey: HexKey,
    ): FrostSigningSession {
        val entry = ShareGenerations.active(database, key).roster.firstOrNull { it.participantId == participantId }
            ?: throw IllegalStateException("Id $participantId is not in the key")
        check(entry.standing != ShareStanding.LIVE) {
            "Id $participantId still holds a live share; rotate the shares without it before binding it to anyone else"
        }
        val binding = GroupKeyStateEvent.MemberBinding(participantId, memberPublicKey.lowercase(), hostPublicKey.lowercase())
        return proposeGeneration(database, localChatRoom, userPublicKey, key, refreshId = null, newBinding = binding)
    }

    /**
     * Asks the group to sign the key state at the generation it now signs with,
     * after a refresh (docs/frost-share-refresh.md, Phase 8). The same room, key
     * and path, and the same parentage if the room has one, plus what the
     * refresh changed. Signed with the new shares, which makes it the proof that
     * they work -- and what every device waits on before deleting the old ones.
     */
    suspend fun proposeGeneration(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        key: DkgSession,
        refreshId: String?,
        newBinding: GroupKeyStateEvent.MemberBinding? = null,
    ): FrostSigningSession {
        val shares = ShareGenerations.active(database, key)
        check(shares.generation >= 1) { "Generation 0 is the ceremony's, and needs no new key state" }
        val publicShares = requireNotNull(shares.publicShares) { "Generation ${shares.generation} has no public shares" }
        val current = keyStateFor(database, localChatRoom.chatRoom.id)
        val path = current?.pathIndices() ?: SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
        val parent = current?.parentChatRoomId?.let { parentId ->
            current.birthCertificateJson?.let { Event.fromJsonOrNull(it) }?.let { SubgroupParentage(parentId, it) }
        }
        val thresholdPublicKey = requireNotNull(key.thresholdPublicKey)
        return FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            kind = GroupKeyStateEvent.KIND,
            tags = GroupKeyStateEvent.assembleTags(
                chatRoomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path),
                dkgSessionId = key.id,
                path = path,
                parent = parent,
                generation = GroupKeyStateEvent.ShareGenerationTags(
                    generation = shares.generation,
                    publicSharesHash = ShareGenerations.publicSharesHash(publicShares),
                    refreshId = refreshId,
                    retiredIds = shares.roster.filter { it.standing == ShareStanding.RETIRED }.map { it.participantId },
                    // Bindings already in force at this generation stay said, so a
                    // later state does not quietly undo them; a new one replaces any
                    // earlier binding of the same id.
                    bindings = (
                        GroupKeyStateEvent.MemberBinding.decodeAll(current?.takeIf { it.generation == shares.generation }?.memberBindings)
                            .filter { it.participantId != newBinding?.participantId } + listOfNotNull(newBinding)
                        ),
                ),
            ),
            content = thresholdPublicKey,
            key = key,
            // Later than the state it amends, by a second if it must be. Two states
            // at one generation are told apart by their time alone, and a rebind
            // stamped in the same second as the state it adds to would tie with it
            // -- and the binding could be the one that loses.
            createdAt = maxOf(
                Clock.System.now().epochSeconds,
                (current?.takeIf { (it.generation ?: 0) == shares.generation }?.announcedAt?.epochSeconds ?: 0L) + 1
            ),
        )
    }

    /**
     * Files the state the group has already signed for a room that has just come
     * into existence, if there is one.
     *
     * The counterpart to [record], and the reason a room can be created already
     * knowing what it signs with. Both ways into a room end here: the member who
     * creates it, and the member who arrives on a Welcome -- who was in the
     * ceremony and so holds the signed event too, because it was signed in the
     * NIP-17 room they were already in rather than in the room they have just
     * joined.
     *
     * Nothing is trusted that [stateFrom] would not trust. This only looks
     * somewhere else for the event: at what the group has signed, rather than at
     * what has just arrived. A member who was not in the ceremony holds no such
     * event and gets nothing, which is correct -- they have no share either, and
     * `FrostSigningManager.completedKey` rederives for them as it always did.
     *
     * Idempotent: `replace` keeps the newest state per room, so adopting twice
     * settles on the same row.
     */
    suspend fun adopt(database: DedunDatabase, chatRoomId: String): GroupKeyState? {
        val state = signedStateFor(database, chatRoomId) ?: return null

        logger.i("Room $chatRoomId adopts the key state its group signed at ${state.announcedAt}")

        return database.groupKeyStateDao().replace(state)
    }

    /**
     * The newest state the group has signed for [chatRoomId], read off this
     * device's stored signed events rather than off the [GroupKeyState] table.
     *
     * Answerable for a room that does not exist, which is the whole point of it:
     * between the group agreeing its key state and somebody creating the room,
     * this is the only thing that can say the agreement was reached. It is what
     * the ritual screen gates the "create the room" button on, and what [adopt]
     * files once there is a room to file it against.
     */
    suspend fun signedStateFor(database: DedunDatabase, chatRoomId: String): GroupKeyState? =
        stateAmong(database.groupSignedEventDao().getByKind(GroupKeyStateEvent.KIND), chatRoomId)

    /**
     * The event the group signed to say what [chatRoomId] signs with, as the
     * group signed it.
     *
     * The statement itself rather than the reading of it in [signedStateFor],
     * for a screen that shows a member what their group actually put a signature
     * to -- and lets them copy it somewhere it can be checked. Found wherever it
     * is filed, which for the room it is about is *not* that room: the group
     * agrees this before the room exists, so the event lives under the NIP-17
     * room its ceremony ran in.
     */
    suspend fun signedEventFor(
        database: DedunDatabase,
        chatRoomId: String
    ): GroupSignedEvent? =
        signedAmong(database.groupSignedEventDao().getByKind(GroupKeyStateEvent.KIND), chatRoomId)
            ?.first

    /**
     * The newest state for [chatRoomId] among some signed events, or null if
     * none of them is one.
     *
     * Takes the events rather than fetching them so a caller watching them
     * arrive can ask the same question of each emission -- which is how the
     * ceremony screen knows the group has finished agreeing.
     */
    fun stateAmong(signedEvents: List<GroupSignedEvent>, chatRoomId: String): GroupKeyState? =
        signedAmong(signedEvents, chatRoomId)?.second

    /**
     * The newest signed event for [chatRoomId] and the state it amounts to.
     *
     * The two travel together because neither is worth having without the other
     * here: the state is what the app acts on, and the event is the group's own
     * statement of it -- and an event that produces no state is one this device
     * would not have believed, so it must not be shown as though the group had
     * settled anything.
     */
    private fun signedAmong(
        signedEvents: List<GroupSignedEvent>,
        chatRoomId: String
    ): Pair<GroupSignedEvent, GroupKeyState>? =
        signedEvents
            .asSequence()
            .filter { it.kind == GroupKeyStateEvent.KIND }
            // Named rather than inferred. Every other reader may fall back to
            // the room an event arrived in; this one is walking events from
            // every room at once, so a state that names no room names nothing.
            .filter { GroupKeyStateEvent.parseChatRoomId(it.tags) == chatRoomId }
            .mapNotNull { signed -> stateFrom(chatRoomId, signed.toEvent())?.let { signed to it } }
            // The highest generation, then the newest: `created_at` is the
            // author's to choose, and a state at an older generation is still
            // validly signed by the group (docs/frost-share-refresh.md, Phase 8).
            .maxWithOrNull(compareBy({ (_, state) -> state.generation ?: 0 }, { (_, state) -> state.announcedAt }))

    /**
     * The state an event amounts to, or null if it amounts to none.
     *
     * Two questions, and a state has to answer both. Is it true -- does the room
     * it names rederive from the key it names? And did the group say it -- does
     * the signature verify against the identity of that same key?
     *
     * The first is the one that cannot be given up. Acting on a state naming a
     * key the room was not derived from means signing with a share that will not
     * aggregate, or worse, treating a key the group does not hold as the key the
     * group holds. It is also entirely independent of who is speaking: a member
     * with no share, or none of the ceremony at all, can state a true state and
     * it is still true.
     *
     * The second is what the proposal flow buys. It does not make a state truer;
     * it makes a state the group's, so that the record of what a room signs with
     * is a thing a quorum agreed to rather than a thing its creator said.
     *
     * ### Which room the state is about
     *
     * The one its own `d` tag names, not the one it arrived in. Those used to be
     * required to agree and a mismatch was dropped, which was the right rule
     * while a state was made in the room it described -- and is the wrong one
     * now that a group signs its state before creating that room, so the two are
     * ordinarily different by design.
     *
     * Nothing is given up by that. The check the drop was standing in for is
     * still made, and made against the *named* room: the state has to rederive
     * it, and the signature has to be by the key that rederivation reaches. A
     * state can therefore only ever be about a room it derives, whatever room it
     * turned up in, so nobody can point one room at another room's key by
     * putting it through the wrong door.
     *
     * [chatRoomId] -- where it arrived -- is only the fallback for a state that
     * names no room at all, and appears in the logs.
     */
    /**
     * A parentage that was checked, wrapping a value that is allowed to be
     * absent.
     *
     * Three outcomes have to be told apart and a bare nullable can only carry
     * two. A state may claim no parent, which is every group made before
     * subgroups and every top-level group made after; it may claim one and prove
     * it; or it may claim one it cannot prove, which is not a state with a field
     * missing but a device asserting a relationship the parent never agreed to.
     * The third has to stop [stateFrom] rather than be flattened into the first.
     *
     * So `null` here means refused, and a present [value] of `null` means none
     * claimed.
     */
    private class Parentage(val value: SubgroupParentage?)

    /**
     * The parentage an event actually establishes, or null if it claims one it
     * cannot back.
     *
     * Four checks, and they exist because a parent link is the one thing on a key
     * state that is not self-evident from the room it describes. The rest of
     * [stateFrom] is answerable from the event and the room's own id; this is a
     * statement about a *second* group, so it needs that group's own signature.
     *
     * A failure drops the whole state. Keeping it as a parentless one was the
     * alternative and is worse: a state claiming a parentage it cannot back is
     * not a state with one field wrong, and half-believing it would file a group
     * as top-level that its own members will see as a subgroup.
     */
    private fun parentageOf(
        announced: String,
        thresholdPublicKey: HexKey,
        innerEvent: Event
    ): Parentage? {
        if (!GroupKeyStateEvent.claimsParentage(innerEvent.tags)) return Parentage(null)

        // Both or neither. A parent named with no certificate is a claim with the
        // checkable part removed; a certificate with no parent named beside it
        // has nothing to be an index of. Either alone is refused rather than
        // completed from the other, because completing it would be this device
        // deciding what the group meant.
        val parentChatRoomId = GroupKeyStateEvent.parseParentChatRoomId(innerEvent.tags)
        val certificate = GroupKeyStateEvent.parseBirthCertificate(innerEvent.tags)

        if (parentChatRoomId == null || certificate == null) {
            logger.w(
                "Key state for $announced claims a parent in a form that cannot be read " +
                    "(parent=${parentChatRoomId != null}, certificate=${certificate != null}); dropping"
            )
            return null
        }

        // The parent's own quorum, over this very room's id. Everything that
        // makes a lineage worth anything is in here -- see
        // `SubgroupBirthCertificateEvent.certifies`, which needs no lookup and no
        // key it has to be told.
        if (!SubgroupBirthCertificateEvent.certifies(certificate, announced, parentChatRoomId)) {
            logger.w(
                "Key state for $announced carries no certificate by $parentChatRoomId for " +
                    "that room; dropping"
            )
            return null
        }

        // Belt and braces: the id already derives from this key, and the
        // certificate's id already derives from the key it names, so two
        // certificates for one room cannot name two keys. Stated anyway because
        // the two facts live in different places and a future change to either
        // should have to notice this.
        val certifiedKey = SubgroupBirthCertificateEvent.parseThresholdPublicKey(certificate.tags)
        if (!thresholdPublicKey.equals(certifiedKey, ignoreCase = true)) {
            logger.w(
                "Key state for $announced names $thresholdPublicKey and its certificate names " +
                    "$certifiedKey; dropping"
            )
            return null
        }

        // A wallet policy present and unreadable must never look absent: an
        // undecided child is offered both wallets, so a corrupt `guarded` would
        // hand it the plain one its parent took away -- the lesson of
        // [GroupKeyStateEvent.claimsParentage], one tag further on.
        // `docs/subgroup-wallet-policy.md`.
        if (SubgroupBirthCertificateEvent.parseWalletPolicy(certificate.tags) == null) {
            logger.w(
                "Key state for $announced carries a certificate whose wallet policy does not " +
                    "read, or contradicts its guardian keys; dropping"
            )
            return null
        }

        return Parentage(
            SubgroupParentage(parentChatRoomId = parentChatRoomId, certificate = certificate)
        )
    }

    fun stateFrom(chatRoomId: String, innerEvent: Event): GroupKeyState? =
        read(chatRoomId, innerEvent, requireGroupSignature = true)

    /**
     * The state an event would amount to once the group signs it, or null if it
     * would amount to none: every check [stateFrom] makes except the signature.
     *
     * For a statement still being asked for -- the unsigned event a signing
     * session carries. `FrostSigningManager` reads it to decide whether this
     * device can vouch for a key state without asking its owner
     * ([vouchesFor]), and has to judge it by exactly the rules the group's
     * signature will later be judged by, or it would sign states nobody keeps.
     */
    fun claimFrom(chatRoomId: String, innerEvent: Event): GroupKeyState? =
        read(chatRoomId, innerEvent, requireGroupSignature = false)

    /**
     * Whether this device can put its share to [session] without asking its owner:
     * true only when what is being signed is the one key state that could be true
     * for a ceremony this device finished.
     *
     * A ceremony makes one key. Walked to the admin path it reaches one room, and
     * for a subgroup there is one certificate the parent's quorum signed over that
     * room. So for a ceremony this device took part in there is exactly one correct
     * statement of what its room signs with, and a person shown it has nothing to
     * check that the device has not (docs/automatic-key-ceremony.md, Phase 7).
     *
     * Every one of these has to hold, and anything else is asked about as before.
     * Failing one is not a reason to refuse -- it means this device cannot vouch
     * for the statement on its own, so its owner decides:
     *
     * 1. the session signs exactly one event, a key state -- never a batch;
     * 2. the key it signs with is a ceremony this user finished on this device;
     * 3. the state is about that ceremony and names its key;
     * 4. at the admin path, in the state and in the session alike;
     * 5. naming the room that key and path derive, and signed as that room --
     *    [claimFrom] checks the derivation, the same check [stateFrom] makes;
     * 6. claiming the parent the ceremony was run for, or none for a group's own,
     *    with a certificate that parent's quorum signed -- [claimFrom] again;
     * 7. with no state for that room already signed on this device;
     * 8. and naming no share generation and no member binding. A rotation's key
     *    state, or one binding an id to a new identity, says more than the
     *    ceremony did -- which shares the group now signs with, who holds an id --
     *    and that is its owner's to agree to (docs/frost-share-refresh.md).
     */
    suspend fun vouchesFor(
        database: DedunDatabase,
        session: FrostSigningSession,
        items: List<FrostSigningItem>,
    ): Boolean {
        // 1
        val item = items.singleOrNull() ?: return false
        val unsigned = Event.fromJsonOrNull(item.unsignedEventJson) ?: return false
        if (unsigned.kind != GroupKeyStateEvent.KIND) return false

        // 2
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return false
        if (key.userPublicKey != session.userPublicKey || key.stage != DkgRitualStage.COMPLETE) return false
        val thresholdPublicKey = key.thresholdPublicKey ?: return false

        // 3, 5 and 6's certificate
        val claim = claimFrom(session.chatRoomId, unsigned) ?: return false
        if (claim.dkgSessionId != key.id) return false
        if (!claim.thresholdPublicKey.equals(thresholdPublicKey, ignoreCase = true)) return false

        // 4
        val adminPath = SharedKeyDerivation.formatPath(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH)
        if (claim.derivationPath != adminPath || session.derivationPath != adminPath) return false

        // 5's author: the room speaks for itself, under the key that derives it
        if (unsigned.pubKey != claim.chatRoomId) return false

        // 6's parent
        if (claim.parentChatRoomId != key.parentChatRoomId) return false

        // 7
        val signed = stateAmong(database.groupSignedEventDao().getByKind(GroupKeyStateEvent.KIND), claim.chatRoomId)
        if (signed != null) return false

        // 8
        if (claim.generation != null || claim.memberBindings != null) return false

        return true
    }

    private fun read(chatRoomId: String, innerEvent: Event, requireGroupSignature: Boolean): GroupKeyState? {
        val announced = GroupKeyStateEvent.parseChatRoomId(innerEvent.tags) ?: chatRoomId

        val thresholdPublicKey = GroupKeyStateEvent.parseThresholdPublicKey(innerEvent.content)
        if (thresholdPublicKey == null) {
            logger.w("Key state in $chatRoomId carries no threshold key; dropping")
            return null
        }

        val dkgSessionId = GroupKeyStateEvent.parseDkgSessionId(innerEvent.tags)
        if (dkgSessionId == null) {
            logger.w("Key state in $chatRoomId names no ceremony; dropping")
            return null
        }

        val path = GroupKeyStateEvent.parsePath(innerEvent.tags)
        if (path == null) {
            logger.w("Key state in $chatRoomId carries no walkable derivation path; dropping")
            return null
        }

        // Only the group may say what the group signs with. A member holding a
        // single share cannot produce this signature, so nothing short of a
        // quorum can put a room's key state on the record -- not even a member
        // saying something true.
        if (requireGroupSignature && !GroupKeyStateEvent.isSignedByGroup(innerEvent, thresholdPublicKey, path)) {
            logger.w(
                "Key state in $chatRoomId from ${innerEvent.pubKey} carries no signature by " +
                    "the group holding $thresholdPublicKey; dropping"
            )
            return null
        }

        val parent = parentageOf(announced, thresholdPublicKey, innerEvent) ?: return null

        val shareGeneration = GroupKeyStateEvent.parseShareGeneration(innerEvent.tags).getOrElse {
            logger.w("Key state in $chatRoomId names a share generation this device cannot read (${it.message}); dropping")
            return null
        }

        val state = GroupKeyState(
            chatRoomId = announced,
            dkgSessionId = dkgSessionId,
            thresholdPublicKey = thresholdPublicKey,
            derivationPath = SharedKeyDerivation.formatPath(path),
            announcedBy = innerEvent.pubKey,
            announcedAt = Instant.fromEpochSeconds(innerEvent.createdAt),
            parentChatRoomId = parent.value?.parentChatRoomId,
            birthCertificateJson = parent.value?.certificate?.toJson(),
            generation = shareGeneration?.generation,
            publicSharesHash = shareGeneration?.publicSharesHash,
            memberBindings = shareGeneration?.bindings?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.encode() }
        )

        // Half the trust model, in one line, and the half that does not care who
        // is speaking. Only the truth rederives the room it names.
        if (!state.verifies()) {
            logger.w(
                "Key state from ${innerEvent.pubKey} names $thresholdPublicKey at " +
                    "${state.derivationPath}, which does not derive room $announced; dropping"
            )
            return null
        }

        return state
    }
}
