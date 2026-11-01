package com.machankura.dedun.compose.database.repository

import com.machankura.dedun.compose.database.model.MarmotCommitResult
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.BroadcastStatus
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalAccount
import com.machankura.dedun.compose.database.model.intermdiate.LocalBroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.database.dao.NostrEventDao
import com.machankura.dedun.compose.database.query.NostrEventFilterQuery
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRelayListEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.references
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip02FollowList.RelaySet
import com.vitorpamplona.quartz.nip02FollowList.tags.ContactTag
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip10Notes.content.findHashtags
import com.vitorpamplona.quartz.nip10Notes.content.findNostrUris
import com.vitorpamplona.quartz.nip10Notes.content.findURLs
import com.vitorpamplona.quartz.nip10Notes.tags.markedETags
import com.vitorpamplona.quartz.nip10Notes.tags.notify
import com.vitorpamplona.quartz.nip10Notes.tags.prepareETagsAsReplyTo
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip18Reposts.quotes.QEventTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.quotes
import com.vitorpamplona.quartz.nip21UriScheme.toNostrUri
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip31Alts.AltTag
import com.vitorpamplona.quartz.nip50Search.SearchRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.RelayFeedsListEvent
// NIP-51 has two RelayTag classes with identical `assemble` signatures: the generic list one
// uses "r", while the relay-list events (kind 10007 search, kind 10012 feeds) parse "relay".
// Importing the wrong one compiled cleanly and produced lists nothing could read back.
import com.vitorpamplona.quartz.nip51Lists.relayLists.tags.RelayTag
import com.vitorpamplona.quartz.nip57Zaps.LnZapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.machankura.dedun.compose.identity.Identity
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * @param activeIdentity whose requests the two fetch queues are stamped with. Every
 *   request queued through here is queued *as* the active identity -- a screen cannot
 *   queue anything as anyone else -- so the stamp is the repository's, not the call
 *   site's, and no call site can forget it. Defaults to nobody, which stamps nothing
 *   and is what a test that has no identity gets.
 */
class DatabaseNostrRepository(
    private val database: com.machankura.dedun.compose.database.DedunDatabase,
    private val scope: CoroutineScope,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
): com.machankura.dedun.compose.repository.NostrRepository, com.machankura.dedun.compose.repository.RelayRepository {
    companion object {
        const val TAG = "DatabaseNostrRepository"

        /** Falls back to the same window the per-shape queries this replaced hard-coded. */
        private const val DEFAULT_NEGENTROPY_LIMIT = 50

        private val storeNostrEventMutex = Mutex()
    }

    val logger = Logger.withTag(TAG)

    override suspend fun getLocalAccounts(): List<LocalAccount> {
        return database.unsignedNostrEventDao().getLocalAccounts()
    }

    override suspend fun observeLocalAccount(publicKey: HexKey): Flow<LocalAccount?> {
        return database.unsignedNostrEventDao().observeProfile(publicKey)
    }

    override suspend fun getProfileWithPublicKey(publicKey: HexKey): Profile? {
        return database.profileDao().getProfileByPublicKey(publicKey)
    }

    override suspend fun observeProfileWithPublicKey(publicKey: HexKey): Flow<Profile?> {
        return database.profileDao().observeProfileByPublicKey(publicKey)
    }

    override suspend fun observeRelation(
        publicKey: HexKey,
        activePublicKey: HexKey
    ): Flow<List<com.machankura.dedun.compose.database.model.Connection>> {
        return database.connectionDao().observeRelation(
            alicePublicKey = publicKey,
            bobPublicKey = activePublicKey
        )
    }

    override suspend fun deleteConnection(connection: com.machankura.dedun.compose.database.model.Connection) {
        database.connectionDao().delete(connection)
    }

    override suspend fun observeUnsignedNostrEvents(publicKey: HexKey): Flow<List<UnsignedNostrEvent>> {
        return database.unsignedNostrEventDao().observeUnsignedNostrEvents(publicKey)
    }

    override suspend fun observePendingBroadcastNostrEventRequests(): Flow<LocalBroadcastNostrEventRequest?> {
        return database.broadcastNostrEventRequestDao().observeBroadcastNostrEventRequestsByStatus("pending")
    }

    override suspend fun observePendingSynchronizeNostrEventRequests(ownerPublicKey: HexKey): Flow<com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest?> {
        return database.synchronizeNostrEventRequestDao().observeSynchronizeNostrEventRequestsByStatusFor("pending", ownerPublicKey)
    }

    override suspend fun observePendingNegentropySynchronizeRequests(ownerPublicKey: HexKey): Flow<com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest?> {
        return database.negentropySynchronizeRequestDao().observeNegentropySynchronizeRequestsByStatusFor("pending", ownerPublicKey)
    }

    override suspend fun reopenInbox(activeKeyPair: KeyPair): Int {
        // Under the same lock as every other write through indexNostrEvent.
        return storeNostrEventMutex.withLock {
            database.nostrDao().reopenInbox(activeKeyPair)
        }
    }

    override suspend fun createNewProfile(
        publicKey: HexKey,
        name: String?,
        biography: String?,
        onCompletion: suspend () -> Unit
    ) {
        logger.d("createNewProfile: $publicKey")

        saveUnsignedNostrEvents(bootstrapUnsignedEvents(publicKey, name, biography))

        onCompletion.invoke()
    }

    override suspend fun setUpProfileForExistingKey(
        publicKey: HexKey,
        placeholderUnsignedNostrEventId: Long,
        name: String?,
        biography: String?,
    ) {
        logger.d("setUpProfileForExistingKey: $publicKey")

        val events = bootstrapUnsignedEvents(publicKey, name, biography)
        val profile = events.first { it.kind == MetadataEvent.KIND }

        // The placeholder row is *replaced*, under its own id, rather than joined by a
        // second kind 0: `getLocalAccounts` is every kind-0 row, and two for one pubkey
        // would be two accounts disagreeing about which is this one. Its `signedAt` goes
        // back to null so the notary signs it, and the sync requests that hang off the id
        // stay where they are as the record of the search that found nothing.
        database.unsignedNostrEventDao().upsert(
            profile.copy(
                id = placeholderUnsignedNostrEventId,
                signedAt = null,
            )
        )
        saveUnsignedNostrEvents(events.filterNot { it.kind == MetadataEvent.KIND })
    }

    override suspend fun forgetLocalAccount(publicKey: HexKey) {
        logger.d("forgetLocalAccount: $publicKey")
        database.unsignedNostrEventDao().deleteAllForPublicKey(publicKey)
    }

    /**
     * What a key gets when it first becomes a profile here: the kind 0, a contact list
     * that follows itself, and the relay lists the app reads, all pointed at the defaults.
     * The kind 0 is first.
     */
    private fun bootstrapUnsignedEvents(
        publicKey: HexKey,
        name: String?,
        biography: String?,
    ): List<UnsignedNostrEvent> {
        val profileEventTemplate = MetadataEvent.createNew(
            name = name,
            about = biography,
        )

        val unsignedNostrEvents = mutableListOf(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = profileEventTemplate.kind,
                createdAt = Instant.fromEpochSeconds(profileEventTemplate.createdAt),
                tags = profileEventTemplate.tags,
                content = profileEventTemplate.content,
                signedAt = null
            )
        )

        val followUsers = listOf(ContactTag(publicKey, null, null))
        val contactListTagList = followUsers.map { it.toTagArray() }

        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = ContactListEvent.KIND,
                tags = contactListTagList.toTypedArray(),
                content = RelaySet.assemble(
                    emptyMap()
                )
            )
        )

        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = ChatMessageRelayListEvent.KIND,
                tags = ChatMessageRelayListEvent.createTagArray(
                    Relays.DefaultDMRelayList
                ),
                content = ""
            )
        )

        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = KeyPackageRelayListEvent.KIND,
                tags = Relays.DefaultDMRelayList.map { arrayOf("relay", it.url) }
//                    .plusElement(AltTag.assemble(KeyPackageRelayListEvent.ALT_DESCRIPTION))
                    .toTypedArray(),
                content = ""
            )
        )



        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = SearchRelayListEvent.KIND,
                tags = Relays.DefaultSearchRelayList.map {
                    RelayTag.assemble(
                        it
                    )
                }.toTypedArray(),
                content = ""
            )
        )

        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = RelayFeedsListEvent.KIND,
                tags = arrayOf(
//                    AltTag.assemble(RelayFeedsListEvent.ALT)
                ),
                privateTags = Relays.DefaultDMRelayList.map {
                    RelayTag.assemble(
                        it
                    )
                }.toTypedArray(),
                content = ""
            )
        )

        return unsignedNostrEvents
    }

    override suspend fun signInToProfile(
        publicKey: HexKey
    ) {
        // Idempotent by pubkey. Until the read-only kind, nothing called this twice for
        // one key -- the writers refused a second sign-in before it got here. Pasting the
        // nsec of a key held read-only is the first path that signs in a pubkey whose
        // account already exists, and a second placeholder would be two kind-0 rows for
        // one pubkey: two accounts disagreeing about which is this one, the failure
        // `setUpProfileForExistingKey`'s own comment describes.
        if (database.unsignedNostrEventDao().getLocalAccounts().any { it.unsignedNostrEvent?.pubKey == publicKey }) {
            logger.d("signInToProfile: $publicKey already has an account here, nothing to plant")
            return
        }

        val profileEventTemplate = MetadataEvent.createNew()

        saveUnsignedNostrEvent(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = profileEventTemplate.kind,
                createdAt = Instant.fromEpochSeconds(profileEventTemplate.createdAt),
                tags = profileEventTemplate.tags,
                content = profileEventTemplate.content,
                signedAt = GENESIS_AT // This will keep us from trying to sign this event... but we should automatically sync the npub profile
            )
        )
    }
    override suspend fun createNewTextNote(
        publicKey: HexKey,
        mentionedPublicKeys: List<HexKey>,
        textInput: String,
        inReplyToNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent?,
        quotedNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent?,
        onError: () -> Unit,
        onSuccess: () -> Unit
    ) {
        val quotedNostrURI = quotedNostrEvent?.let {
            // TODO: Convert qoutedNostrEvent to nostrURI
            val quotedTextNoteEvent = it.nostrEvent.toTextNoteEvent()

            val relayHint = null // TODO: get relyhint from where ever the nostrEvent was synced from...

            "\n${quotedTextNoteEvent.toNostrUri(relayHint)}"
        } ?: ""
        val text: String = textInput + quotedNostrURI

        // TODO: Handle mentioned profile tags...
        val textNote = TextNoteEvent.build(
            note = text,
        ) {
            inReplyToNostrEvent?.let {
                val replyToTextNoteEvent = it.nostrEvent.toTextNoteEvent()

                val replyingTo = EventHintBundle(
                    replyToTextNoteEvent
                )

                val tags = prepareETagsAsReplyTo(replyingTo)

                markedETags(
                    tags
                )

                it.profile?.let { profile ->
                    val publicKeys = mutableSetOf<HexKey>()
                    publicKeys.addAll(
                        mentionedPublicKeys.toSet()
                    )

                    publicKeys.add(profile.publicKey)

                    val ptags = publicKeys.map { publicKey ->
                        PTag(
                            publicKey,
                            // TODO: Add relayHints...
                        )
                    }

                    notify(ptags)
                }
            }

            if (inReplyToNostrEvent == null || inReplyToNostrEvent.profile == null) {
                val pTags = mentionedPublicKeys.toSet().map {
                    PTag(
                        pubKey = it
                    )
                }

                notify(pTags)
            }

            quotedNostrEvent?.let {
                it.profile?.let { profile ->
                    val ptags = listOf(
                        PTag(
                            profile.publicKey,
                            // TODO: Add relayHints...
                        ),
                        // Other profiles tagged in the event...
                    )

                    add(
                        QEventTag.assemble(
                            eventId = quotedNostrEvent.nostrEvent.id,
                            relay = null, // TODO: Add relayHints...
                            author = profile.publicKey
                        )
                    )
                    notify(ptags)

                    // TODO: ZapForwarding support...
                }
            }

            hashtags(findHashtags(text))
            references(findURLs(text))
            quotes(findNostrUris(text))
        }


        val unsignedNostrEvent = saveUnsignedNostrEvent(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = textNote.kind,
                createdAt = Instant.fromEpochSeconds(textNote.createdAt),
                tags = textNote.tags,
                content = textNote.content
            )
        )

        if (unsignedNostrEvent == null) {
           onError.invoke()
        } else {
            if (inReplyToNostrEvent != null) {
                // TODO: Schedule inReplyToNostrEvent broadcast
            }

            if (quotedNostrEvent != null) {
                // TODO: Schedule quotedNostrEvent broadcast
            }

            onSuccess.invoke()
        }
    }

    override suspend fun saveUnsignedNostrEvent(unsignedNostrEvent: UnsignedNostrEvent): UnsignedNostrEvent? = try {
        logger.d("Save unsigned: ${unsignedNostrEvent.kind}")
        // TODO: We can actually check the unsignedNostrEvent.pubKey for support... if it's writeable
        if (unsignedNostrEvent.kind == MetadataEvent.KIND){
            delay(2_100) // Just to make it look like we are doing serious work...
        }

        unsignedNostrEvent.copy(
            id = database.unsignedNostrEventDao().upsert(unsignedNostrEvent)
        )
    } catch (e: Throwable) {
        logger.e("Failed to saveUnsignedNostrEvent", e)
        null
    }

    override suspend fun saveUnsignedNostrEvents(unsignedNostrEvents: List<UnsignedNostrEvent>) {
        logger.d("saveUnsignedNostrEvents: $unsignedNostrEvents")

        database.unsignedNostrEventDao().insert(
            unsignedNostrEvents
        )
    }

    override suspend fun wipeDatabase() {
        database.wipeData()
    }

    override suspend fun publishNostrEvent(
        unsignedNostrEvent: UnsignedNostrEvent,
        nostrEvent: NostrEvent,
        relayURLs: List<String>,
        activeKeyPair: KeyPair
    ) {
        database.nostrDao().publishNostrEvent(
            unsignedNostrEvent,
            nostrEvent,
            relayURLs,
            activeKeyPair = activeKeyPair
        )
    }

    override suspend fun requeueStaleBroadcastNostrEventRequests(): Int {
        val requeued = database.broadcastNostrEventRequestDao().requeueStaleBroadcastNostrEventRequests(
            staleBefore = Clock.System.now()
        )
        if (requeued > 0) {
            logger.i("Requeued $requeued stale broadcast request(s) left over from a previous run")
        }
        return requeued
    }

    override suspend fun broadcastProcessed(
        broadcastNostrEventRequest: BroadcastNostrEventRequest,
        status: String,
        reason: String?,
    ) {
        database.broadcastNostrEventRequestDao().upsert(
            broadcastNostrEventRequest.copy(
                status = status
            )
        )

        if (status == BroadcastStatus.REJECTED) {
            discardCommitRejectedEverywhere(broadcastNostrEventRequest.nostrEventId, reason)
        }

        if (status == BroadcastStatus.PUBLISHED) {
            val broadcastNostrEventReceiptId = database.broadcastNostrEventReceiptDao().upsert(
                com.machankura.dedun.compose.database.model.BroadcastNostrEventReceipt(
                    nostrEventId = broadcastNostrEventRequest.nostrEventId,
                    unsignedNostrEventId = broadcastNostrEventRequest.unsignedNostrEventId,
                    isAccepted = true,
                    relayURL = broadcastNostrEventRequest.relayURL
                )
            )

            database.chatMessageBroadcastNostrEventRequestRelationDao().getChatMessageBroadcastNostrEventRequestRelationByBroadcastRequest(
                broadcastNostrEventRequestId = broadcastNostrEventRequest.id
            )?.let { chatMessageBroadcastNostrEventRequestRelation ->
                // This is actually a chatMessage related broadcast event...
                database.chatMessageBroadcastNostrEventReceiptRelationDao().upsert(
                    com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventReceiptRelation(
                        chatMessageId = chatMessageBroadcastNostrEventRequestRelation.chatMessageId,
                        broadcastNostrEventReceiptId = broadcastNostrEventReceiptId
                    )
                )
            }

            database.marmotCommitResultDao().getMarmotCommitRequestById(
                nostrEventId = broadcastNostrEventRequest.nostrEventId
            )?.let { marmotCommitResult ->
                logger.d("marmotCommitResult: $marmotCommitResult")

                // Publish before apply: a relay accepted the commit, so the state it
                // makes canonical is installed now -- and only now, and before the
                // Welcome below, which is the MIP-02 ordering enforced by sequence
                // rather than by hope. See docs/marmot-v2.md, Phase 6.
                marmotCommitResult.obligationId?.let { obligationId ->
                    database.marmotEngine.confirmPublished(obligationId)
                }

                // A removal, a committed leave or a disband invites nobody, and so
                // has no Welcome to send once it is applied.
                if (marmotCommitResult.peerKeyPackageEventId == MarmotCommitResult.NO_INVITEE) return@let

                if (marmotCommitResult.isOneMemberInitialGroupCreation.not()) {
                    logger.d("Deliver Welcome")
                    database.chatRoomDao().findChatRoomById(marmotCommitResult.chatRoomId)?.let { localChatRoom ->
                        // TODO: Update status of participant Invitation.PENDING -> Invitation.SENT
                        logger.d("localChatRoom: $localChatRoom")
                        val welcomeBytes = marmotCommitResult.welcomeBytes
                        if (welcomeBytes == null) {
                            // The commit was acknowledged and there is nothing to let
                            // the invitee in with, so this invite is over. Nobody is
                            // waiting on the answer by now -- the screen that asked
                            // closed when the commit was made -- which is why it goes
                            // in the room rather than to a caller.
                            database.marmotOutboundDao().announceInviteFailed(
                                chatRoomId = marmotCommitResult.chatRoomId,
                                userPublicKey = marmotCommitResult.userPublicKey,
                                peerPublicKey = database.marmotKeyPackageDao()
                                    .getMarmotKeyPackageById(marmotCommitResult.peerKeyPackageEventId)
                                    ?.publicKey,
                                reason = "the commit carried no invitation",
                            )
                            return@let
                        }

                        logger.d("welcomeBytes: ${welcomeBytes.toHex()}")
                        val delivered = database.marmotOutboundDao().deliveryWelcome(
                            chatRoomId = marmotCommitResult.chatRoomId,
                            userPublicKey = marmotCommitResult.userPublicKey,
                            welcomeBytes = welcomeBytes,
                            peerKeyPackageEventId = marmotCommitResult.peerKeyPackageEventId,
                            createdAt = marmotCommitResult.createdAt,
                            relays = Relays.DefaultDMRelayList.map { it.url } // TODO: Get localChatRoom relays...
                        )

                        // This is the deferred delivery site, and the only one where
                        // the Welcome goes out later than the invite that made it --
                        // so it is the only one that owes the room a second line. An
                        // invite whose room was still just its creator sent its
                        // Welcome in the same breath and said so once already. A
                        // failure needs nothing here: `deliveryWelcome` files its own.
                        if (delivered) {
                            database.marmotOutboundDao().announceInviteSent(
                                chatRoomId = marmotCommitResult.chatRoomId,
                                userPublicKey = marmotCommitResult.userPublicKey,
                                peerKeyPackageEventId = marmotCommitResult.peerKeyPackageEventId,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Publish before apply, the other half: a commit every relay in its scope has
     * refused is discarded, and the room is free to make another.
     *
     * One relay's refusal decides nothing -- another may still accept, and a
     * timeout on another is retried on the next launch with the same bytes -- so
     * the obligation goes only when every request row for the event is
     * "rejected". Then the gate drops the pending state, the live group having
     * never advanced, and `canPrepareLocalCommit` is true again; the invite or
     * the removal that was asked for is simply not done, and the room says so in
     * the relay's words, because the screen that asked closed when the commit
     * was made and the line is the only account the user gets. See
     * docs/marmot-v2.md, Phase 6.
     */
    private suspend fun discardCommitRejectedEverywhere(nostrEventId: HexKey, reason: String?) {
        val marmotCommitResult = database.marmotCommitResultDao().getMarmotCommitRequestById(nostrEventId) ?: return
        val obligationId = marmotCommitResult.obligationId ?: return
        val requests = database.broadcastNostrEventRequestDao().getBroadcastNostrEventRequestsByNostrEventId(nostrEventId)
        if (requests.any { it.status != BroadcastStatus.REJECTED }) return

        if (!database.marmotEngine.discardRejected(obligationId)) return
        logger.w("Commit $nostrEventId rejected by every relay in scope; its obligation is discarded: $reason")

        if (marmotCommitResult.peerKeyPackageEventId != MarmotCommitResult.NO_INVITEE) {
            database.marmotOutboundDao().announceInviteFailed(
                chatRoomId = marmotCommitResult.chatRoomId,
                userPublicKey = marmotCommitResult.userPublicKey,
                peerPublicKey = database.marmotKeyPackageDao()
                    .getMarmotKeyPackageById(marmotCommitResult.peerKeyPackageEventId)
                    ?.publicKey,
                reason = reason?.let { "the relays refused it ($it)" } ?: "the relays refused it",
            )
        } else {
            database.marmotOutboundDao().announceChangeRejected(
                chatRoomId = marmotCommitResult.chatRoomId,
                userPublicKey = marmotCommitResult.userPublicKey,
                reason = reason,
            )
        }
    }

    override suspend fun synchronizeNostrEventRequestProcessed(synchronizeNostrEventRequest: com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest) {
        database.synchronizeNostrEventRequestDao().upsert(
            synchronizeNostrEventRequest.copy(
                status = "sent",
                updatedAt = Clock.System.now()
            )
        )
    }

    override suspend fun synchronizeNostrEventRequestCompleted(synchronizeNostrEventRequest: com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest) {
        database.synchronizeNostrEventRequestDao().markCompleteIfSent(
            id = synchronizeNostrEventRequest.id,
            status = com.machankura.dedun.compose.nostr.SignInSync.STATUS_COMPLETE,
            updatedAt = Clock.System.now(),
        )
    }

    override suspend fun negentropySynchronizeRequestProcessed(negentropySynchronizeRequest: com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest) {
        database.negentropySynchronizeRequestDao().upsert(
            negentropySynchronizeRequest.copy(
                status = "sent",
                updatedAt = Clock.System.now()
            )
        )
    }

    override suspend fun saveBroadcastReceipt(broadcastNostrEventReceipt: com.machankura.dedun.compose.database.model.BroadcastNostrEventReceipt) {
        database.broadcastNostrEventReceiptDao().upsert(
            broadcastNostrEventReceipt
        )
    }

    override suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        synchronizeNostrEventRequest: com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest,
        synchronizationRelayURLs: List<String>,
        activeKeyPair: KeyPair,
    ) {
        storeNostrEventMutex.withLock {
            logger.d("saveNostrEvent: $nostrEvent")
            try {
                database.nostrDao().storeNostrEvent(
                    nostrEvent.copy(
                        broadcastedAt = nostrEvent.createdAt,
                    ),
                    synchronizationRelayURLs = synchronizationRelayURLs,
                    relayURL = synchronizeNostrEventRequest.relayURL,
                    level = synchronizeNostrEventRequest.level,
                    activeKeyPair = activeKeyPair
                )

                database.synchronizeNostrEventRequestDao().upsert(
                    synchronizeNostrEventRequest.copy(
                        nostrEventId = nostrEvent.id,
                        status = "processed"
                    )
                )
            } catch (e: Throwable) {
                logger.e("Failed to save nostrEvent ${nostrEvent.id}", e)
            }

        }
    }

    override suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        negentropySynchronizeRequest: com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest,
        synchronizationRelayURLs: List<String>,
        activeKeyPair: KeyPair
    ) {
        // Under the same lock as the other overloads. storeNostrEvent reads the event, then
        // writes it and its indexes; two of those interleaving is a lost update. This path
        // used to skip the lock, which was survivable only while a single queue was the one
        // thing writing — a live subscription writing alongside a backfill is not that.
        storeNostrEventMutex.withLock {
            logger.d("saveNostrEvent: $nostrEvent")

            try {
                database.nostrDao().storeNostrEvent(
                    nostrEvent,
                    relayURL = negentropySynchronizeRequest.relayURL,
                    synchronizationRelayURLs = synchronizationRelayURLs,
                    level = negentropySynchronizeRequest.level,
                    activeKeyPair = activeKeyPair
                )

                database.negentropySynchronizeRequestDao().upsert(
                    negentropySynchronizeRequest.copy(
                        status = "processed"
                    )
                )
            } catch (e: Throwable) {
                logger.e("Failed to save nostr event ${nostrEvent.id}", e)
            }
        }
    }

    override suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        relayURL: String,
        synchronizationRelayURLs: List<String>,
        level: Int,
        activeKeyPair: KeyPair
    ) {
        storeNostrEventMutex.withLock {
            logger.d("saveNostrEvent (live): $nostrEvent")

            try {
                database.nostrDao().storeNostrEvent(
                    nostrEvent,
                    relayURL = relayURL,
                    synchronizationRelayURLs = synchronizationRelayURLs,
                    level = level,
                    activeKeyPair = activeKeyPair
                )
            } catch (e: Throwable) {
                logger.e("Failed to save nostr event ${nostrEvent.id}", e)
            }
        }
    }

    /**
     * Stamped with the active identity where the caller stamped nothing. A request
     * queued while nothing is active stays nobody's, which any identity may drain.
     */
    override suspend fun queueSynchronizeNostrEvent(
        synchronizeNostrEventRequests: List<com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest>,
    ) {
        val owner = activeIdentity.value?.nostrPublicKey
        database.synchronizeNostrEventRequestDao().insert(
            synchronizeNostrEventRequests.map { it.copy(ownerPublicKey = it.ownerPublicKey ?: owner) }
        )
    }

    override suspend fun queueNegentropySynchronizeRequest(negentropySynchronizeRequests: List<com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest>) {
        val owner = activeIdentity.value?.nostrPublicKey
        database.negentropySynchronizeRequestDao().insert(
            negentropySynchronizeRequests.map { it.copy(ownerPublicKey = it.ownerPublicKey ?: owner) }
        )
    }

    override suspend fun observeSynchronizeNostrEventRequestByPurposeAndStatusCount(purpose: String): Flow<Int> {
        return database.synchronizeNostrEventRequestDao().observeSynchronizeNostrEventRequestByPurposeAndStatusCount(
            purpose,
            listOf("pending", "processing")
        )
    }

    override suspend fun getNostrEvent(publicKey: HexKey, kind: Kind): NostrEvent? {
        return database.nostrEventDao().getNostrEventByPublicKeyAndKind(
            publicKey = publicKey,
            kind = kind
        )
    }

    override suspend fun observeNostrFeed(): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>> {
        return database.nostrEventDao().observeNostrEvents(
            kinds = arrayOf(
                TextNoteEvent.KIND,
                RepostEvent.KIND,
                ReactionEvent.KIND,
                LnZapEvent.KIND
            )
        )
    }

    override suspend fun observeNostrFeed(synchronizationFilter: SynchronizationFilter): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>> {
        return when {
            synchronizationFilter.kinds != null && synchronizationFilter.search != null -> {
                logger.d("observeFilteredNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeFilteredNostrEvents(
                    kinds = synchronizationFilter.kinds,
                    search = synchronizationFilter.search
                )
            }
            synchronizationFilter.kinds != null && synchronizationFilter.ids != null -> {
                logger.d("observeFilteredNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeFilteredNostrEvents(
                    kinds = synchronizationFilter.kinds,
                    ids = synchronizationFilter.ids
                )
            }
            synchronizationFilter.kinds != null && synchronizationFilter.authors != null -> {
                logger.d("observeAuthoredNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeAuthoredNostrEvents(
                    kinds = synchronizationFilter.kinds,
                    authors = synchronizationFilter.authors
                )
            }
            synchronizationFilter.kinds != null && synchronizationFilter.since != null &&  synchronizationFilter.tags?.contains("e") == true && synchronizationFilter.tags["e"]?.first()?.isNotEmpty() == true -> {
                logger.d("observePublicKeyMentionedNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeNostrEventReplies(
                    kinds = synchronizationFilter.kinds,
                    eventId = synchronizationFilter.tags["e"]?.first()!!,
                    since = synchronizationFilter.since
                )
            }
            synchronizationFilter.kinds != null && synchronizationFilter.tags?.contains("p") == true && synchronizationFilter.tags["p"]?.first()?.isNotEmpty() == true -> {
                logger.d("observePublicKeyMentionedNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observePublicKeyMentionedNostrEvents(
                    kinds = synchronizationFilter.kinds,
                    publicKey = synchronizationFilter.tags["p"]?.first()!!
                )
            }
            else -> {
                logger.d("observeNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeNostrEvents(
                    kinds = arrayOf(
                        TextNoteEvent.KIND,
                    )
                )
            }
        }
    }

    override suspend fun observeNostrEventsMatching(synchronizationFilter: SynchronizationFilter): Flow<List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>> {
        return database.nostrEventDao().observeNostrEventsMatchingFilter(
            NostrEventFilterQuery.build(synchronizationFilter)
        )
    }

    override suspend fun getNegentropicNostrFeedIds(
        synchronizationFilters: Array<SynchronizationFilter>,
        applyLimits: Boolean
    ): List<NostrEvent> {
        logger.d("getNegentropicNostrFeedIds: ${synchronizationFilters.contentToString()}")

        // A negentropy session reconciles against the union of its filters. Returning nothing
        // for anything other than a single filter (as this used to) is the worst possible
        // answer: an empty local set tells the relay we have none of these events, so it
        // hands back its entire set as ids to download.
        return synchronizationFilters
            .flatMap { synchronizationFilter ->
                matchNegentropicNostrEvents(synchronizationFilter, applyLimits)
            }
            .distinctBy { it.id }
    }

    private suspend fun matchNegentropicNostrEvents(
        synchronizationFilter: SynchronizationFilter,
        applyLimits: Boolean,
    ): List<NostrEvent> {
        val limit = if (applyLimits) {
            synchronizationFilter.limit ?: DEFAULT_NEGENTROPY_LIMIT
        } else {
            NostrEventFilterQuery.NO_LIMIT
        }

        val routingIds = synchronizationFilter.marmotGroupChatRoomIds()
        if (routingIds != null) {
            // Group messages live in their own table, which carries the NIP-40 expiry a relay
            // uses to decide whether it still serves the event, and an indexed chatRoomId
            // rather than a scan of the tags JSON. The filter names routing addresses, as a
            // relay indexes them; the table is keyed by room, so each is resolved first.
            logger.d("getMarmotGroupEvents($applyLimits): $synchronizationFilter")
            val chatRoomIds = routingIds.map { database.marmotRoutingDao().chatRoomIdFor(it) }.distinct().toTypedArray()
            return database.nostrEventDao().getMarmotGroupEvents(
                chatRoomIds = chatRoomIds,
                since = synchronizationFilter.since ?: GENESIS_AT,
                until = synchronizationFilter.until ?: Instant.DISTANT_FUTURE,
                limit = limit,
            ).map { marmotGroupEvent ->
                // Only the id and createdAt reach the negentropy vector; the rest is filled in
                // so callers keep a NostrEvent-shaped result.
                NostrEvent(
                    id = marmotGroupEvent.id,
                    content = marmotGroupEvent.encryptedContent,
                    kind = GroupEvent.KIND,
                    tags = arrayOf(arrayOf("h", marmotGroupEvent.chatRoomId)),
                    createdAt = marmotGroupEvent.createdAt,
                    sig = marmotGroupEvent.signature,
                    pubKey = marmotGroupEvent.publicKey
                )
            }
        }

        logger.d("getNostrEventsMatchingFilter($applyLimits): $synchronizationFilter")
        return database.nostrEventDao().getNostrEventsMatchingFilter(
            NostrEventFilterQuery.build(
                filter = synchronizationFilter,
                limit = limit,
            )
        )
    }

    /**
     * The chat room ids of a filter that asks for nothing but Marmot group messages, or null if
     * the filter says anything the [NostrEventDao.getMarmotGroupEvents] lookup cannot express.
     * Kept deliberately narrow: the branch it guards answers from a different table, so a filter
     * it only partly understands would silently drop the rest of the constraints — which is
     * exactly what the shape-guessing chain this replaced did.
     */
    private fun SynchronizationFilter.marmotGroupChatRoomIds(): Array<HexKey>? {
        val tags = tags ?: return null
        val rooms = tags["h"] ?: return null

        val onlyGroupEvents = kinds != null && kinds.size == 1 && kinds.first() == GroupEvent.KIND
        val nothingElseAsked = ids == null &&
            authors == null &&
            search == null &&
            tagsAll.isNullOrEmpty() &&
            tags.keys == setOf("h")

        return if (onlyGroupEvents && nothingElseAsked) rooms.toTypedArray() else null
    }

    override suspend fun observeLocalNostrEventById(nostrEventId: String): Flow<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent?> {
        return database.nostrEventDao().observeNostrEventById(nostrEventId)
    }

    override suspend fun searchableProfiles(excludingPublicKeys: Array<HexKey>): List<com.machankura.dedun.compose.database.model.Profile> {
        return database.profileDao().getAllProfilesExcludingOnesWithThesePublicKeys(
            excludedPublicKeys = excludingPublicKeys
        )
    }

    override suspend fun filterProfiles(searchTerm: String): List<com.machankura.dedun.compose.database.model.intermdiate.LocalProfile> {
        return database.profileDao().filterProfiles(searchTerm = searchTerm)
    }


    override suspend fun observeProfileWithFollowers(publicKey: String): Flow<com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowers?> {
        return database.profileDao().observeProfileWithFollowersByPublicKey(publicKey)
    }

    override suspend fun observeProfileWithFollowing(publicKey: String): Flow<com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing?> {
        return database.profileDao().observeProfileWithFollowingByPublicKey(publicKey)
    }

    override suspend fun getRecentSearches(): List<com.machankura.dedun.compose.database.model.RecentSearch> {
        return database.recentSearchDao().getAllRecentSearches()
    }

    override suspend fun removeRecentSearch(recentSearch: com.machankura.dedun.compose.database.model.RecentSearch) {
        database.recentSearchDao().deleteRecentSearch(recentSearch)
    }

    override suspend fun saveSearchQuery(
        query: String,
        synchronizationFilterArray: com.machankura.dedun.compose.database.model.typealiases.SynchronizationFilterArray?
    ) {
        database.recentSearchDao().upsert(
            com.machankura.dedun.compose.database.model.RecentSearch(
                query = query,
                synchronizationFilters = synchronizationFilterArray
            )
        )
    }

    override suspend fun observePublicKeyRelays(publicKey: String): Flow<List<com.machankura.dedun.compose.database.model.Relay>> {
        return database.relayDao().observePublicKeyRelays(publicKey)
    }

    override suspend fun rescheduleBroadcastNostrEventRequests(broadcastNostrEventRequests: List<BroadcastNostrEventRequest>) {
        database.nostrDao().rescheduleBroadcastNostrEventRequests(
            broadcastNostrEventRequests
        )
    }
}
