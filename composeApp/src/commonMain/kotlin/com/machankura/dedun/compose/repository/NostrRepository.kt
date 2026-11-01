package com.machankura.dedun.compose.repository

import com.machankura.dedun.compose.database.model.BroadcastNostrEventReceipt
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.Connection
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.RecentSearch
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.UnsignedNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalAccount
import com.machankura.dedun.compose.database.model.intermdiate.LocalBroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalProfile
import com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowers
import com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.flow.Flow

interface NostrRepository {
    suspend fun getLocalAccounts(): List<LocalAccount>

    suspend fun observeLocalAccount(publicKey: HexKey): Flow<LocalAccount?>

    suspend fun getProfileWithPublicKey(publicKey: HexKey): Profile?

    suspend fun observeProfileWithPublicKey(publicKey: HexKey): Flow<Profile?>

    suspend fun observeRelation(publicKey: HexKey, activePublicKey: HexKey): Flow<List<Connection>>

    suspend fun deleteConnection(connection: Connection)

    /**
     * The whole unsigned backlog for [publicKey], lowest kind first.
     *
     * See `UnsignedNostrEventDao.observeUnsignedNostrEvents`: a row that cannot be
     * published used to be the entire queue. Callers work through it in order, and a row
     * that fails costs only itself.
     */
    suspend fun observeUnsignedNostrEvents(publicKey: HexKey): Flow<List<UnsignedNostrEvent>>

    suspend fun observePendingBroadcastNostrEventRequests(): Flow<LocalBroadcastNostrEventRequest?>

    /** The head of the fetch queue for one identity: its own requests and the ones nobody owns. */
    suspend fun observePendingSynchronizeNostrEventRequests(ownerPublicKey: HexKey): Flow<SynchronizeNostrEventRequest?>

    suspend fun observePendingNegentropySynchronizeRequests(ownerPublicKey: HexKey): Flow<NegentropySynchronizeRequest?>

    /**
     * Opens every gift wrap addressed to [activeKeyPair] that the device stored and could
     * not open at the time. Run once per activation, before the live inbox is opened.
     * Returns how many were opened.
     */
    suspend fun reopenInbox(activeKeyPair: KeyPair): Int

    suspend fun createNewProfile(
        publicKey: HexKey,
        name: String?,
        biography: String?,
        onCompletion: suspend () -> Unit
    )

    suspend fun signInToProfile(
        publicKey: HexKey
    )

    /**
     * The exit from a sign-in whose search found nothing: the key already exists, only
     * the events do not. Gives [publicKey] the same bootstrap [createNewProfile] gives a
     * fresh key, with the kind 0 written *over* the placeholder [signInToProfile] planted
     * -- see the implementation for why over rather than beside.
     */
    suspend fun setUpProfileForExistingKey(
        publicKey: HexKey,
        placeholderUnsignedNostrEventId: Long,
        name: String?,
        biography: String?,
    )

    /**
     * Stops treating [publicKey] as one of this device's own: its unsigned rows go, and
     * with them the requests that hang off them. What it published, and its profile, stay
     * cached as anyone else's would.
     */
    suspend fun forgetLocalAccount(publicKey: HexKey)

    suspend fun createNewTextNote(
        publicKey: HexKey,
        mentionedPublicKeys: List<HexKey>,
        textInput: String,
        inReplyToNostrEvent: LocalNostrEvent?,
        quotedNostrEvent: LocalNostrEvent?,
        onError: () -> Unit,
        onSuccess: () -> Unit
    )

    suspend fun saveUnsignedNostrEvent(unsignedNostrEvent: UnsignedNostrEvent): UnsignedNostrEvent?

    suspend fun saveUnsignedNostrEvents(unsignedNostrEvents: List<UnsignedNostrEvent>)

    suspend fun wipeDatabase()

    suspend fun publishNostrEvent(
        unsignedNostrEvent: UnsignedNostrEvent,
        nostrEvent: NostrEvent,
        relayURLs: List<String> = emptyList(),
        activeKeyPair: KeyPair
    )

    /**
     * Record how a publish attempt ended. [reason] is the relay's own words when it
     * refused, for the line that tells the user a change was rejected.
     */
    suspend fun broadcastProcessed(
        broadcastNostrEventRequest: BroadcastNostrEventRequest,
        status: String = "processing",
        reason: String? = null,
    )

    /**
     * Move broadcasts a previous process left in "processing"/"failed" back to "pending" so an
     * interrupted or rejected publish is retried instead of stranded. Returns the row count.
     */
    suspend fun requeueStaleBroadcastNostrEventRequests(): Int

    suspend fun synchronizeNostrEventRequestProcessed(synchronizeNostrEventRequest: SynchronizeNostrEventRequest)

    /**
     * The relay has finished answering [synchronizeNostrEventRequest] -- EOSE, CLOSED or
     * the bounded timeout -- and the request is still `sent`, meaning nothing arrived. Until
     * this existed "still searching" and "searched, found nothing" were the same row.
     */
    suspend fun synchronizeNostrEventRequestCompleted(synchronizeNostrEventRequest: SynchronizeNostrEventRequest)

    suspend fun negentropySynchronizeRequestProcessed(negentropySynchronizeRequest: NegentropySynchronizeRequest)

    suspend fun saveBroadcastReceipt(broadcastNostrEventReceipt: BroadcastNostrEventReceipt)

    /**
     * Save nostrEvent from relays (and index for local viewing)...
     */
    suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        synchronizeNostrEventRequest: SynchronizeNostrEventRequest,
        synchronizationRelayURLs: List<String>,
        activeKeyPair: KeyPair
    )

    suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        negentropySynchronizeRequest: NegentropySynchronizeRequest,
        synchronizationRelayURLs: List<String>,
        activeKeyPair: KeyPair
    )

    /**
     * Save a nostrEvent that arrived on a subscription rather than in answer to a queued
     * request.
     *
     * The other two overloads take the request row an event was fetched for, because they
     * also have to record which request produced it and flip that row to "processed". A
     * live subscription has no row and never finishes, so it carries the relay and level
     * itself.
     */
    suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        relayURL: String,
        synchronizationRelayURLs: List<String>,
        level: Int,
        activeKeyPair: KeyPair
    )

    suspend fun queueSynchronizeNostrEvent(
        synchronizeNostrEventRequests: List<SynchronizeNostrEventRequest>,
    )

    suspend fun queueNegentropySynchronizeRequest(
        negentropySynchronizeRequests: List<NegentropySynchronizeRequest>
    )

    suspend fun observeSynchronizeNostrEventRequestByPurposeAndStatusCount(purpose: String): Flow<Int>


    suspend fun getNostrEvent(publicKey: HexKey, kind: Kind): NostrEvent?

    suspend fun observeNostrFeed(): Flow<List<LocalNostrEvent>>

    suspend fun observeNostrFeed(synchronizationFilter: SynchronizationFilter): Flow<List<LocalNostrEvent>>

    /**
     * Every stored event matching [synchronizationFilter], newest first, for as long as
     * the caller collects.
     *
     * The filter is applied whole -- see `NostrEventFilterQuery` -- where [observeNostrFeed]
     * recognises a handful of shapes and falls back to text notes for the rest. A reader
     * that queued the same filter against a relay watches the answer arrive through this.
     */
    suspend fun observeNostrEventsMatching(synchronizationFilter: SynchronizationFilter): Flow<List<LocalNostrEvent>>

    suspend fun getNegentropicNostrFeedIds(
        synchronizationFilters: Array<SynchronizationFilter>,
        applyLimits: Boolean = true
    ): List<NostrEvent>

    suspend fun observeLocalNostrEventById(nostrEventId: String): Flow<LocalNostrEvent?>

    suspend fun searchableProfiles(
        excludingPublicKeys: Array<HexKey> = emptyArray()
    ): List<Profile>

    suspend fun filterProfiles(searchTerm: String): List<LocalProfile>

    suspend fun observeProfileWithFollowers(publicKey: String): Flow<LocalProfileWithFollowers?>

    suspend fun observeProfileWithFollowing(publicKey: String): Flow<LocalProfileWithFollowing?>

    suspend fun getRecentSearches(): List<RecentSearch>

    suspend fun removeRecentSearch(recentSearch: RecentSearch)

    suspend fun saveSearchQuery(query: String, synchronizationFilterArray: com.machankura.dedun.compose.database.model.typealiases.SynchronizationFilterArray? = null)

    suspend fun rescheduleBroadcastNostrEventRequests(broadcastNostrEventRequests: List<BroadcastNostrEventRequest>)

    companion object {
        val NO_OP_NOSTR_REPOSITORY = object : NostrRepository {
            override suspend fun getLocalAccounts(): List<LocalAccount> {
                TODO("Not yet implemented")
            }

            override suspend fun observeLocalAccount(publicKey: HexKey): Flow<LocalAccount?> {
                TODO("Not yet implemented")
            }

            override suspend fun getProfileWithPublicKey(publicKey: HexKey): Profile? {
                TODO("Not yet implemented")
            }

            override suspend fun observeProfileWithPublicKey(publicKey: HexKey): Flow<Profile?> {
                TODO("Not yet implemented")
            }

            override suspend fun observeRelation(
                publicKey: HexKey,
                activePublicKey: HexKey
            ): Flow<List<Connection>> {
                TODO("Not yet implemented")
            }

            override suspend fun deleteConnection(connection: Connection) {
                TODO("Not yet implemented")
            }

            override suspend fun observeUnsignedNostrEvents(publicKey: HexKey): Flow<List<UnsignedNostrEvent>> {
                TODO("Not yet implemented")
            }

            override suspend fun observePendingBroadcastNostrEventRequests(): Flow<LocalBroadcastNostrEventRequest> {
                TODO("Not yet implemented")
            }

            override suspend fun reopenInbox(activeKeyPair: KeyPair): Int = 0

            override suspend fun observePendingSynchronizeNostrEventRequests(ownerPublicKey: HexKey): Flow<SynchronizeNostrEventRequest?> {
                TODO("Not yet implemented")
            }

            override suspend fun observePendingNegentropySynchronizeRequests(ownerPublicKey: HexKey): Flow<NegentropySynchronizeRequest?> {
                TODO("Not yet implemented")
            }

            override suspend fun createNewProfile(
                publicKey: HexKey,
                name: String?,
                biography: String?,
                onCompletion: suspend () -> Unit
            ) {
                TODO("")
            }

            override suspend fun signInToProfile(publicKey: HexKey) {
                TODO("Not yet implemented")
            }

            override suspend fun setUpProfileForExistingKey(
                publicKey: HexKey,
                placeholderUnsignedNostrEventId: Long,
                name: String?,
                biography: String?,
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun forgetLocalAccount(publicKey: HexKey) {
                TODO("Not yet implemented")
            }

            override suspend fun createNewTextNote(
                publicKey: HexKey,
                mentionedPublicKeys: List<HexKey>,
                textInput: String,
                inReplyToNostrEvent: LocalNostrEvent?,
                quotedNostrEvent: LocalNostrEvent?,
                onError: () -> Unit,
                onSuccess: () -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun saveUnsignedNostrEvent(unsignedNostrEvent: UnsignedNostrEvent): UnsignedNostrEvent? {
                return null
            }

            override suspend fun saveUnsignedNostrEvents(unsignedNostrEvents: List<UnsignedNostrEvent>) {
                TODO("Not yet implemented")
            }

            override suspend fun wipeDatabase() {
            }

            override suspend fun publishNostrEvent(
                unsignedNostrEvent: UnsignedNostrEvent,
                nostrEvent: NostrEvent,
                relayURLs: List<String>,
                activeKeyPair: KeyPair
            ) {

            }

            override suspend fun broadcastProcessed(
                broadcastNostrEventRequest: BroadcastNostrEventRequest,
                status: String,
                reason: String?,
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun requeueStaleBroadcastNostrEventRequests(): Int {
                TODO("Not yet implemented")
            }

            override suspend fun synchronizeNostrEventRequestProcessed(synchronizeNostrEventRequest: SynchronizeNostrEventRequest) {
                TODO("Not yet implemented")
            }

            override suspend fun synchronizeNostrEventRequestCompleted(synchronizeNostrEventRequest: SynchronizeNostrEventRequest) {
                TODO("Not yet implemented")
            }

            override suspend fun negentropySynchronizeRequestProcessed(negentropySynchronizeRequest: NegentropySynchronizeRequest) {
                TODO("Not yet implemented")
            }

            override suspend fun saveBroadcastReceipt(broadcastNostrEventReceipt: BroadcastNostrEventReceipt) {
                TODO("Not yet implemented")
            }

            override suspend fun saveNostrEvent(
                nostrEvent: NostrEvent,
                synchronizeNostrEventRequest: SynchronizeNostrEventRequest,
                synchronizationRelayURLs: List<String>,
                activeKeyPair: KeyPair
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun saveNostrEvent(
                nostrEvent: NostrEvent,
                negentropySynchronizeRequest: NegentropySynchronizeRequest,
                synchronizationRelayURLs: List<String>,
                activeKeyPair: KeyPair
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun saveNostrEvent(
                nostrEvent: NostrEvent,
                relayURL: String,
                synchronizationRelayURLs: List<String>,
                level: Int,
                activeKeyPair: KeyPair
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun queueSynchronizeNostrEvent(synchronizeNostrEventRequests: List<SynchronizeNostrEventRequest>) {
                TODO("Not yet implemented")
            }

            override suspend fun queueNegentropySynchronizeRequest(negentropySynchronizeRequests: List<NegentropySynchronizeRequest>) {
                TODO("Not yet implemented")
            }

            override suspend fun observeSynchronizeNostrEventRequestByPurposeAndStatusCount(purpose: String): Flow<Int> {
                TODO("Not yet implemented")
            }

            override suspend fun getNostrEvent(
                publicKey: HexKey,
                kind: Kind
            ): NostrEvent? {
                TODO("Not yet implemented")
            }

            override suspend fun observeNostrFeed(): Flow<List<LocalNostrEvent>> {
                TODO("Not yet implemented")
            }

            override suspend fun observeNostrFeed(synchronizationFilter: SynchronizationFilter): Flow<List<LocalNostrEvent>> {
                TODO("Not yet implemented")
            }

            override suspend fun observeNostrEventsMatching(synchronizationFilter: SynchronizationFilter): Flow<List<LocalNostrEvent>> {
                TODO("Not yet implemented")
            }

            override suspend fun getNegentropicNostrFeedIds(
                synchronizationFilters: Array<SynchronizationFilter>,
                applyLimits: Boolean
            ): List<NostrEvent> {
                TODO("Not yet implemented")
            }

            override suspend fun observeLocalNostrEventById(nostrEventId: String): Flow<LocalNostrEvent?> {
                TODO("Not yet implemented")
            }

            override suspend fun searchableProfiles(excludingPublicKeys: Array<HexKey>): List<Profile> {
                TODO("Not yet implemented")
            }

            override suspend fun filterProfiles(searchTerm: String): List<LocalProfile> {
                TODO("Not yet implemented")
            }

            override suspend fun observeProfileWithFollowers(publicKey: String): Flow<LocalProfileWithFollowers?> {
                TODO("Not yet implemented")
            }

            override suspend fun observeProfileWithFollowing(publicKey: String): Flow<LocalProfileWithFollowing?> {
                TODO("Not yet implemented")
            }

            override suspend fun getRecentSearches(): List<RecentSearch> {
                TODO("Not yet implemented")
            }

            override suspend fun removeRecentSearch(recentSearch: RecentSearch) {
                TODO("Not yet implemented")
            }

            override suspend fun saveSearchQuery(
                query: String,
                synchronizationFilterArray: com.machankura.dedun.compose.database.model.typealiases.SynchronizationFilterArray?
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun rescheduleBroadcastNostrEventRequests(broadcastNostrEventRequests: List<BroadcastNostrEventRequest>) {
                TODO("Not yet implemented")
            }
        }
    }
}