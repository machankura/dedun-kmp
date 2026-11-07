package com.machankura.dedun.compose.database.dao

import com.machankura.dedun.compose.managers.GroupLightningOperatorManager
import com.machankura.dedun.compose.managers.GroupLightningCoSigner
import com.machankura.dedun.compose.managers.GroupLightningReports
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.managers.GroupWavelengthDeposits
import com.machankura.dedun.compose.managers.GroupWavelengthReports
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.lightning.GroupLightningEvents
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.managers.IcebergRitualManager
import androidx.room3.Dao
import androidx.room3.Transaction
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatMessageBroadcastNostrEventRequestRelation
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.Connection
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.Mention
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.NostrEventRelay
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Post
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.MarmotReindexReport
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.exceptions.GiftWrapImpersonationException
import com.machankura.dedun.compose.exceptions.GiftWrapSealDecryptionException
import com.machankura.dedun.compose.exceptions.GiftWrapUnsealException
import com.machankura.dedun.compose.exceptions.MarmotMissingChatGroupException
import com.machankura.dedun.compose.exceptions.MarmotMissingKeyPackageBundleException
import com.machankura.dedun.compose.exceptions.MarmotMissingNostrGroupDataExtension
import com.machankura.dedun.compose.exceptions.MarmotNotMemberOfChatGroupException
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.ChillDkgRitualManager
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.managers.GroupKeyStateManager
import com.machankura.dedun.compose.managers.MlsGroupCache
import com.machankura.dedun.compose.managers.MarmotInboundManager
import com.machankura.dedun.compose.managers.MarmotReindexSweep
import com.machankura.dedun.compose.managers.MarmotEngine.Companion.inRoom
import com.machankura.dedun.compose.managers.WelcomeBundleMatch
import com.machankura.dedun.compose.managers.roomDescription
import com.machankura.dedun.compose.managers.roomName
import com.machankura.dedun.compose.managers.roomId
import com.machankura.dedun.compose.managers.routingId
import com.machankura.dedun.compose.database.model.MarmotRouting
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRelayListEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.machankura.dedun.compose.managers.CommitOrdering
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.tags.events.taggedEvents
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

@Dao
abstract class NostrDao(
    private val database: com.machankura.dedun.compose.database.DedunDatabase,
) {
    val logger = Logger.withTag("NostrDao")

    val profileEventKinds = arrayOf(
        MetadataEvent.KIND,
//        ContactListEvent.KIND, // TODO: Might want to get contacts for the social graph...
        ChatMessageRelayListEvent.KIND,
        KeyPackageEvent.KIND,
        KeyPackageRelayListEvent.KIND,
//        AdvertisedRelayListEvent.KIND,
//        SearchRelayListEvent.KIND,
//        IndexerRelayListEvent.KIND,
//        ChannelListEvent.KIND,
//        RelayFeedsListEvent.KIND
    )

    open suspend fun publishNostrEvent(
        unsignedNostrEvent: com.machankura.dedun.compose.database.model.UnsignedNostrEvent,
        nostrEvent: NostrEvent,
        relayURLs: List<String>,
        activeKeyPair: KeyPair
    ) {
        logger.i("Publish Nostr Event: $nostrEvent ($relayURLs)")

        commitPublishedNostrEvent(unsignedNostrEvent, nostrEvent, relayURLs)

        // Indexing is best-effort enrichment and runs in its OWN transaction. It used to share
        // the transaction above, so any throw in it rolled back `signedAt` too — and because the
        // notary drains one unsigned row at a time, that row would be re-selected forever and
        // every later event (including the MLS key package, which is enqueued last) would never
        // be signed at all.
        try {
            indexNostrEvent(
                nostrEvent = nostrEvent,
                relayURL = relayURLs.first(),
                synchronizationRelayURLs = relayURLs,
                level = 0,
                activeKeyPair = activeKeyPair
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Failed to index published event ${nostrEvent.id} (kind ${nostrEvent.kind})", e)
        }
    }

    /**
     * The durable half of publishing: mark the unsigned row signed, store the signed event and
     * queue it for every target relay. All-or-nothing, and independent of indexing.
     */
    @Transaction
    protected open suspend fun commitPublishedNostrEvent(
        unsignedNostrEvent: com.machankura.dedun.compose.database.model.UnsignedNostrEvent,
        nostrEvent: NostrEvent,
        relayURLs: List<String>
    ) {
        // Update unsignedEvent with signedTime time...
        database.unsignedNostrEventDao().upsert(
            unsignedNostrEvent.copy(
                signedAt = Clock.System.now()
            )
        )

        database.nostrEventDao().upsert(nostrEvent)

        relayURLs.forEach { relayURL ->
            database.broadcastNostrEventRequestDao().upsert(
                BroadcastNostrEventRequest(
                    nostrEventId = nostrEvent.id,
                    unsignedNostrEventId = nostrEvent.unsignedNostrEventId,
                    relayURL = relayURL
                )
            )
        }
    }

    /**
     * Opens every gift wrap addressed to [activeKeyPair] that the device stored and could
     * not open at the time -- because another identity fetched it, or because this one
     * held no key yet. Phase 6 of docs/multiple-profiles.md.
     *
     * [storeNostrEvent] never indexes an event it already holds, so a wrap that arrived
     * under the wrong key would otherwise stay closed for good: the live subscription
     * re-receives it, the DAO sees a known id, and returns. This runs the wrap through
     * [indexNostrEvent] again, under the right key, one transaction per wrap so that a
     * wrap that cannot be opened rolls back its own changes and the next is still tried.
     * One pass: wraps do not depend on one another, so a wrap that cannot be opened now
     * will not be helped by opening its neighbours.
     *
     * @return how many were opened.
     */
    open suspend fun reopenInbox(activeKeyPair: KeyPair): Int {
        if (activeKeyPair.privKey == null) return 0
        val me = activeKeyPair.pubKey.toHex()
        val unopened = database.giftWrapMessageDao().getUnopenedAddressedTo(me)
        if (unopened.isEmpty()) return 0
        logger.i("Reopening ${unopened.size} gift wrap(s) addressed to $me")

        var opened = 0
        for (wrap in unopened) {
            val nostrEvent = database.nostrEventDao().getNostrEventById(wrap.nostrEventId) ?: continue
            val relayURL = database.nostrEventRelayDao().getRelayUrls(nostrEvent.id).firstOrNull()
                ?: Relays.DefaultDMRelayList.first().url
            try {
                reopenGiftWrap(nostrEvent, relayURL, activeKeyPair)
                if (database.giftWrapSealDao().countForMessage(wrap.id) > 0) opened++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.w("Gift wrap ${wrap.id} could not be opened on the second try", e)
            }
        }
        logger.i("Reopened $opened of ${unopened.size} gift wrap(s) for $me")
        return opened
    }

    /** One wrap, indexed again, in a transaction of its own; see [reopenInbox]. */
    @Transaction
    open suspend fun reopenGiftWrap(nostrEvent: NostrEvent, relayURL: String, activeKeyPair: KeyPair) {
        indexNostrEvent(
            nostrEvent = nostrEvent,
            relayURL = relayURL,
            synchronizationRelayURLs = listOf(relayURL),
            level = 0,
            activeKeyPair = activeKeyPair,
        )
    }

    @Transaction
    open suspend fun storeNostrEvent(
        nostrEvent: NostrEvent,
        relayURL: String,
        synchronizationRelayURLs: List<String>,
        level: Int,
        activeKeyPair: KeyPair
    ) {
        val storedNostrEvent = database.nostrEventDao().getNostrEventById(nostrEvent.id)

        if (storedNostrEvent == null) {
            // Add new nostr event
            logger.i("Store Nostr Event: $nostrEvent")
            database.nostrEventDao().insert(nostrEvent)


        } else if (nostrEvent.createdAt > storedNostrEvent.createdAt) {
            // Update nostr event`
            logger.i("Update Nostr Event: $nostrEvent")
            database.nostrEventDao().upsert(nostrEvent)
        } else {
            // Nothing else needs to be done
            val profile = database.profileDao().getProfileByPublicKey(nostrEvent.pubKey)
            if (profile?.createdAt == GENESIS_AT && nostrEvent.kind == MetadataEvent.KIND) {
                logger.i("This is a placeholder profile might need to get synced...: ${profile.publicKey}")
                // Setup the sync here...
            } else {
                // TODO: Check if event got indexed...
                return
            }
        }

        database.nostrEventRelayDao().upsert(
            NostrEventRelay(
                relayURL = relayURL,
                nostrEventId = nostrEvent.id
            )
        )

        // Index nostrEvent
        logger.i("Index Nostr Event: ${nostrEvent.id}")
        indexNostrEvent(
            nostrEvent = nostrEvent,
            relayURL = relayURL,
            synchronizationRelayURLs = synchronizationRelayURLs,
            level = level,
            activeKeyPair = activeKeyPair
        )
    }

    private suspend fun indexNostrEvent(
        nostrEvent: NostrEvent,
        relayURL: String,
        synchronizationRelayURLs: List<String>,
        level: Int,
        activeKeyPair: KeyPair
    ) {
        val profilePublicKeysToSync = mutableMapOf<String, MutableSet<String>>()
        val eventIdsToSync = mutableMapOf<String, MutableSet<String>>()

        if (nostrEvent.unsignedNostrEventId == null && nostrEvent.kind != GiftWrapEvent.KIND) {
            // Find or create profile with the pubKey... if not found submit a sync request...
            val profile = database.profileDao().getProfileByPublicKey(nostrEvent.pubKey)

            if (profile == null) {
                val placeHolderProfile = Profile(
                    displayName = "LOADING...",
                    publicKey = nostrEvent.pubKey,
                    createdAt = GENESIS_AT,
                    nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                )
                database.profileDao().insertPlaceholderProfile(placeHolderProfile)
                if (profilePublicKeysToSync[relayURL] == null) {
                    profilePublicKeysToSync[relayURL] = mutableSetOf()
                }
                profilePublicKeysToSync[relayURL]?.add(nostrEvent.pubKey)
            } else if (profile.createdAt == GENESIS_AT && level == 0) {
                logger.i("This is a placeholder profile... that might need to get synced...: $profile")
                if (profilePublicKeysToSync[relayURL] == null) {
                    profilePublicKeysToSync[relayURL] = mutableSetOf()
                }
                profilePublicKeysToSync[relayURL]?.add(nostrEvent.pubKey)
            }
        } else {
            logger.w("We somehow have an unsignedNostrEvent/giftwrap event: $nostrEvent")
        }

        // Index reposts first because they have the contained posts...
        nostrEvent.toRepostedRelationWithContainedPost()?.let { (repostedRelation, containedPost) ->
            // Find or create placeHolder note that is being reposted
            val repostedNostrEvent = database.nostrEventDao().getNostrEventById(repostedRelation.repostedNostrEventId)

            val repostedProfilePublicKey = if (repostedNostrEvent == null) {
                // Sync event...
                if (eventIdsToSync[relayURL] == null) {
                    eventIdsToSync[relayURL] = mutableSetOf()
                }
                eventIdsToSync[relayURL]?.add(repostedRelation.repostedNostrEventId)

                database.nostrEventDao().insert(
                    if (containedPost != null) {
                        NostrEvent(
                            id = containedPost.id,
                            content = containedPost.content,
                            createdAt = Instant.fromEpochSeconds(containedPost.createdAt),
                            pubKey = containedPost.pubKey,
                            sig = containedPost.sig,
                            kind = containedPost.kind,
                            tags = containedPost.tags
                        )
                    } else {
                        NostrEvent(
                            id = repostedRelation.repostedNostrEventId,
                            content = "Pending Sync",
                            createdAt = GENESIS_AT,
                            pubKey = nostrEvent.pubKey,
                            sig = "",
                            kind = nostrEvent.kind,
                            tags = emptyArray()
                        )
                    }
                )

                repostedRelation.repostedProfilePublicKey
            } else {
                repostedRelation.repostedProfilePublicKey ?: repostedNostrEvent.pubKey
            }

            val repostedProfile = repostedProfilePublicKey?.let { database.profileDao().getProfileByPublicKey(it) }

            if (repostedProfilePublicKey != null && repostedProfile == null) {
                database.profileDao().insertPlaceholderProfile(
                    Profile(
                        displayName = "LOADING...",
                        publicKey = repostedProfilePublicKey,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                    )
                )
            }
            database.repostedRelationDao().upsert(
                repostedRelation.copy(
                    repostedProfilePublicKey = repostedProfilePublicKey
                )
            )
        }


        // Sync tagged events and authors...
        nostrEvent.tags.taggedEvents().take(4).forEach { taggedEvent -> // We only look at the first 3 tagged events...
            val taggedNostrEvent = database.nostrEventDao().getNostrEventById(taggedEvent.eventId)
            if (taggedNostrEvent == null) {
                database.nostrEventDao().insert(
                    NostrEvent(
                        id = taggedEvent.eventId,
                        content = "Pending Sync... ",
                        createdAt = GENESIS_AT,
                        pubKey = nostrEvent.pubKey,
                        sig = "",
                        kind = nostrEvent.kind,
                        tags = emptyArray()
                    )
                )

                val recommendRelayUrl = taggedEvent.relay?.url

                if (recommendRelayUrl != null) {
                    if (eventIdsToSync[recommendRelayUrl] == null) {
                        eventIdsToSync[recommendRelayUrl] = mutableSetOf()
                    }
                    eventIdsToSync[recommendRelayUrl]?.add(taggedEvent.eventId)
                } else {
                    if (eventIdsToSync[relayURL] == null) {
                        eventIdsToSync[relayURL] = mutableSetOf()
                    }
                    eventIdsToSync[relayURL]?.add(taggedEvent.eventId)

                }
            }

        }
        nostrEvent.tags.taggedUsers().take(5).forEach { taggedUser -> // 5 tagged users...
            val recommendedRelay = taggedUser.relayHint?.url

            val taggedProfile = database.profileDao().getProfileByPublicKey(taggedUser.pubKey)

            if (taggedProfile == null) {
                // Save a placeholder
                database.profileDao().insertPlaceholderProfile(
                    Profile(
                        displayName = "LOADING...",
                        publicKey = taggedUser.pubKey,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                    )
                )
            }

            database.mentionDao().insert(
                Mention(
                    mentionedPublicKey = taggedUser.pubKey,
                    mentioningNostrEventId = nostrEvent.id,
                    relayUrl = taggedUser.relayHint?.url
                )
            )
            if (recommendedRelay != null) {
                if (profilePublicKeysToSync[recommendedRelay] == null) {
                    profilePublicKeysToSync[recommendedRelay] = mutableSetOf()
                }
                profilePublicKeysToSync[recommendedRelay]?.add(taggedUser.pubKey)
            } else {
                if (profilePublicKeysToSync[relayURL] == null) {
                    profilePublicKeysToSync[relayURL] = mutableSetOf()
                }
                profilePublicKeysToSync[relayURL]?.add(
                    taggedUser.pubKey
                )
            }
        }

        nostrEvent.toProfile()?.let { profile ->
            logger.d("Profile: $profile")
            database.profileDao().upsert(profile)
        }

        // MLSGroupEvent handling...
        nostrEvent.toKeyPackageEvent()?.let { keyPackageEvent ->
            logger.d("KeyPackageEvent: ${keyPackageEvent.toJson()}")

            // Checked before it is stored, because nothing downstream checks it:
            // the invite decodes the bytes and adds the leaf, and until this line
            // a package with the wrong tags, a signature that does not verify, a
            // lifetime already over or -- on the current profile -- an identity
            // proof that does not bind the leaf to its account was stored like any
            // other and failed at addMember, or worse, did not. The validator is
            // the one a conformant peer runs on ours, so what it refuses here is
            // what they would refuse there.
            if (!KeyPackageUtils.isCryptographicallyValid(keyPackageEvent)) {
                logger.w("KeyPackage ${keyPackageEvent.id} from ${keyPackageEvent.pubKey} failed validation, not stored")
                return@let
            }

            // Stored bare, whichever way it was published. A current-profile
            // kind:30443 frames its KeyPackage in an MLSMessage; a legacy one does
            // not; MlsGroup.proposeAdd reads only the bare form. Unframing once
            // here is what lets every consumer of this row stay as it is.
            val keyPackageBytes = KeyPackageUtils.decodeKeyPackage(
                Base64.decode(source = keyPackageEvent.keyPackageBase64())
            ).toTlsBytes()

            database.marmotKeyPackageDao().upsert(
                MarmotKeyPackage(
                    id = keyPackageEvent.id,
                    publicKey = keyPackageEvent.pubKey,
                    tlsEncodedMarmotKeyPackage = keyPackageBytes,
                    profile = if (keyPackageEvent.isCurrentProfile()) MarmotProfile.CURRENT.wireName else MarmotProfile.LEGACY.wireName,
                    createdAt = Instant.fromEpochSeconds(keyPackageEvent.createdAt)
                )
            )
        }
        nostrEvent.toGroupEvent(
            userPublicKey = activeKeyPair.pubKey.toHex()
        )?.let { groupEvent ->
            logger.d("groupEvent: ${groupEvent.toJson()}")

            // The `h` tag is the room's address, which on the current profile is not
            // its id; the routing table says which room, and a room with no row is
            // addressed by its own id. See MarmotRouting.
            groupEvent.groupId()?.let { routingId ->
                indexMarmotGroupEvent(
                    groupEvent = groupEvent,
                    chatRoomId = database.marmotRoutingDao().chatRoomIdFor(routingId),
                    activeKeyPair = activeKeyPair,
                )
            }
        }

        nostrEvent.toGiftWrapMessageWithReceiverPTag()?.let { giftWrapMessage ->
            logger.d("giftWrapMessage: $giftWrapMessage")
            val giftWrapReceiverProfile = database.profileDao().getProfileByPublicKey(giftWrapMessage.receiverPublicKey)

            if (giftWrapReceiverProfile == null) {
                // Save a placeholder
                database.profileDao().insertPlaceholderProfile(
                    Profile(
                        displayName = "LOADING...",
                        publicKey = giftWrapMessage.receiverPublicKey,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                    )
                )

                val recommendRelayUrl = giftWrapMessage.receiverRelayHit

                if (recommendRelayUrl != null) {
                    if (profilePublicKeysToSync[recommendRelayUrl] == null) {
                        profilePublicKeysToSync[recommendRelayUrl] = mutableSetOf()
                    }
                    profilePublicKeysToSync[recommendRelayUrl]?.add(giftWrapMessage.receiverPublicKey)
                } else {
                    if (profilePublicKeysToSync[relayURL] == null) {
                        profilePublicKeysToSync[relayURL] = mutableSetOf()
                    }
                    profilePublicKeysToSync[relayURL]?.add(
                        giftWrapMessage.receiverPublicKey
                    )
                }
            }


            database.giftWrapMessageDao().upsert(
                giftWrapMessage
            )

            if (!giftWrapMessage.isAddressedTo(activeKeyPair)) {
                // Undecryptable by design rather than by failure, so keep the event and
                // the wrap we just stored and stop here. Throwing would roll the whole
                // transaction back and lose both.
                logger.d("GiftWrap ${nostrEvent.id} is addressed to ${giftWrapMessage.receiverPublicKey}, nothing to index")

                return@let
            }

            if (activeKeyPair.privKey == null) {
                // Addressed to us, and we cannot open it: a read-only identity. Keep the
                // event and the wrap, as the branch above does for wraps addressed to
                // someone else; the key that opens this one may be signed in later. Without
                // this, the unseal below would run with a key nobody has and throw, and
                // the throw would take the event with it.
                logger.d("GiftWrap ${nostrEvent.id} is addressed to us, but this identity holds no key")

                return@let
            }

            giftWrapMessage.decryptGiftWrapSeal(
                activeKeyPair
            ).let { giftWrapSeal ->
                if (giftWrapSeal == null) {
                    throw GiftWrapUnsealException("Failed to unseal ${nostrEvent.id} from the giftWrap")
                } else {
                    database.giftWrapSealDao().upsert(
                        giftWrapSeal
                    )

                    giftWrapSeal.decryptGiftWrapPayload(
                        activeKeyPair.privKey!!
                    ).let { decryptedGiftWrapPayload ->
                        if (decryptedGiftWrapPayload == null) {
                            throw GiftWrapSealDecryptionException(
                                "Failed to decrypt sealed ${giftWrapSeal.id} payload"
                            )
                        } else {
                            if (giftWrapSeal.publicKey.lowercase() != decryptedGiftWrapPayload.publicKey.lowercase()) {
                                val giftWrapImpersonation =
                                    GiftWrapImpersonationException(
                                        "Seal pubkey (${giftWrapSeal.publicKey}) and payload pubkey (${decryptedGiftWrapPayload.publicKey}) needs to be the same for the giftWrap ${nostrEvent.id}"
                                    )
                                logger.e("Impersonation", giftWrapImpersonation)
                                throw giftWrapImpersonation // Once this is thrown the database transaction fails and nothing gets saved to the DB...
                            }

                            database.giftWrapPayloadDao().upsert(
                                decryptedGiftWrapPayload
                            )

                            if (decryptedGiftWrapPayload.kind == ChatMessageEvent.KIND) {
                                logger.d("chatMessageEvent: $decryptedGiftWrapPayload")
                                // Handle normal nip-17 messages...
                                val localChatRoom = database.chatRoomDao().findChatRoomById(
                                    decryptedGiftWrapPayload.chatRoomId
                                )

                                if (localChatRoom == null) {
                                    val userPublicKey = activeKeyPair.pubKey.toHex()
                                    database.chatRoomDao().upsert(
                                        ChatRoom(
                                            id = decryptedGiftWrapPayload.chatRoomId,
                                            userPublicKey = userPublicKey,
                                            subject = decryptedGiftWrapPayload.parseSubject(),
                                            description = null,
                                            createdAt = decryptedGiftWrapPayload.createdAt,
                                            initialGiftWrapPayloadId = decryptedGiftWrapPayload.id,
                                            mlsGroupState = null
                                        )
                                    )

                                    // Add participants...
                                    val participants = decryptedGiftWrapPayload.participantPTags(
                                        NormalizedRelayUrl(relayURL) // TODO: Get relayURL for publicKey profile...
                                    ).map {
                                        Participant(
                                            participantPublicKey = it.pubKey,
                                            chatRoomId = decryptedGiftWrapPayload.chatRoomId,
                                            relayHint = it.relayHint?.url
                                        )
                                    }

                                    // Sync missing participant profiles...
                                    participants.forEach { participant ->
                                        val giftWrapParticipantProfile =
                                            database.profileDao()
                                                .getProfileByPublicKey(participant.participantPublicKey)

                                        if (giftWrapParticipantProfile == null) {
                                            // Save a placeholder
                                            database.profileDao().insertPlaceholderProfile(
                                                Profile(
                                                    displayName = "LOADING...",
                                                    publicKey = participant.participantPublicKey,
                                                    createdAt = GENESIS_AT,
                                                    nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                                                )
                                            )

                                            val recommendRelayUrl =
                                                giftWrapMessage.receiverRelayHit

                                            if (recommendRelayUrl != null) {
                                                if (profilePublicKeysToSync[recommendRelayUrl] == null) {
                                                    profilePublicKeysToSync[recommendRelayUrl] =
                                                        mutableSetOf()
                                                }
                                                profilePublicKeysToSync[recommendRelayUrl]?.add(
                                                    participant.participantPublicKey
                                                )
                                            } else {
                                                if (profilePublicKeysToSync[relayURL] == null) {
                                                    profilePublicKeysToSync[relayURL] =
                                                        mutableSetOf()
                                                }
                                                profilePublicKeysToSync[relayURL]?.add(
                                                    participant.participantPublicKey
                                                )
                                            }
                                        }
                                    }

                                    database.participantDao().upsert(
                                        participants
                                    )

                                    participants.filter { it.participantPublicKey != userPublicKey }
                                        .forEach { participant ->
                                            val publicKey = participant.participantPublicKey

                                            val chatMessageRelayListEvent =
                                                database.nostrEventDao()
                                                    .getAuthoredNostrEvents(
                                                        kinds = arrayOf(
                                                            ChatMessageRelayListEvent.KIND
                                                        ),
                                                        authors = arrayOf(
                                                            publicKey
                                                        ),
                                                        since = GENESIS_AT,
                                                        limit = 5
                                                    ).firstOrNull()?.let { nostrEvent ->
                                                        if (nostrEvent.kind != ChatMessageRelayListEvent.KIND) {
                                                            logger.e("getAuthoredNostrEvents returned invalid ChatMessageRelayListEvent for ${publicKey}: $nostrEvent")
                                                            null
                                                        } else {
                                                            ChatMessageRelayListEvent(
                                                                id = nostrEvent.id,
                                                                tags = nostrEvent.tags,
                                                                pubKey = nostrEvent.pubKey,
                                                                content = nostrEvent.content,
                                                                createdAt = nostrEvent.createdAt.epochSeconds,
                                                                sig = nostrEvent.sig
                                                            )
                                                        }
                                                    }

                                            // Nothing to sync when we do have the relay list: a gift
                                            // wrap is authored by a throwaway key, so authors=us matched
                                            // nothing and this request was always empty. Our own inbox is
                                            // synced by p-tag instead — by the live gift wrap
                                            // subscription, which also picks up the copy we wrap to
                                            // ourselves (createNip17ChatRoom puts us in our own
                                            // participant list), on every device.
                                            if (chatMessageRelayListEvent == null) {
                                                logger.w("We don't have a chatMessageRelayListEvent for the pubkey $publicKey")

                                                // Sync ChatMessageRelayListEvent publicKey...
                                                // TODO: Get relayHint form participant...
                                                // Inverted test: this created the set only when
                                                // one already existed (wiping it), and left the
                                                // key absent otherwise — so the `?.add` below was
                                                // a no-op and the participant whose DM relay list
                                                // we are missing never got queued for sync.
                                                if (
                                                    !profilePublicKeysToSync.containsKey(
                                                        relayURL
                                                    )
                                                ) {
                                                    profilePublicKeysToSync[relayURL] =
                                                        mutableSetOf()
                                                }
                                                profilePublicKeysToSync[relayURL]?.add(
                                                    participant.participantPublicKey
                                                )
                                            }
                                        }
                                } else {
                                    decryptedGiftWrapPayload.parseSubject()?.let { subject ->
                                        logger.d("Subject: $subject")
                                        database.chatRoomDao().upsert(
                                            localChatRoom.chatRoom.copy(
                                                subject = subject,
                                                updatedAt = decryptedGiftWrapPayload.createdAt
                                            )
                                        )
                                    }
                                }

                                // The room exists either way by here, so file the
                                // message itself. Without this the whole branch only
                                // ever built rooms: the payload was decrypted, stored
                                // and dropped, and the feed reads ChatMessage.
                                persistInboundChatMessage(
                                    decryptedGiftWrapPayload = decryptedGiftWrapPayload,
                                    activeKeyPair = activeKeyPair
                                )

                            } else if (decryptedGiftWrapPayload.kind == WelcomeEvent.KIND) {

                                val welcomeEvent = WelcomeEvent(
                                    id = decryptedGiftWrapPayload.id, // TODO: Need the ID as string in decryptedGiftWrapPayload...
                                    pubKey = decryptedGiftWrapPayload.publicKey,
                                    content = decryptedGiftWrapPayload.content,
                                    createdAt = decryptedGiftWrapPayload.createdAt.epochSeconds,
                                    tags = decryptedGiftWrapPayload.tags,
                                    sig = ""
                                )
                                logger.d("welcomeEvent: ${welcomeEvent.toJson()}")

                                val welcomeBytes = Base64.decode(
                                    welcomeEvent.welcomeBase64()
                                )

                                // The Welcome names the KeyPackage it was encrypted to;
                                // the `e` tag names the one the inviter thinks it used.
                                // The first is the authority and the second a hint that
                                // an inviter working from a cached copy gets wrong, so
                                // the refs are tried first and the tag only when they
                                // name nothing of ours -- see WelcomeBundleMatch.
                                val userPublicKeyHex = activeKeyPair.pubKey.toHex()
                                val keyPackageEventId = welcomeEvent.keyPackageEventId()
                                val marmotKeyPackageBundle = WelcomeBundleMatch.select(
                                    welcomeBytes = welcomeBytes,
                                    candidates = database.marmotKeyPackageBundleDao()
                                        .getAllMarmotKeyPackageBundles(userPublicKeyHex),
                                    keyPackageBytesOf = { it.tlsEncodedMarmotKeyPackage },
                                ) ?: keyPackageEventId?.let { eventId ->
                                    logger.d("keyPackageEventId: $eventId")
                                    database.marmotKeyPackageBundleDao().getMarmotKeyPackageBundleById(eventId)
                                } ?: throw MarmotMissingKeyPackageBundleException(
                                    "Couldn't find marmotKeyPackageBundle for $welcomeEvent"
                                )
                                logger.d("marmotKeyPackageBundle: $marmotKeyPackageBundle")

                                val initPrivateKey = marmotKeyPackageBundle.ncryptsecInitPrivateKey.hexToByteArray() // TODO: Decrypt
                                val signaturePrivateKey = marmotKeyPackageBundle.ncryptsecSignaturePrivateKey.hexToByteArray() // TODO: Decrypt
                                val encryptionPrivateKey = marmotKeyPackageBundle.ncryptsecEncryptionPrivateKey.hexToByteArray() // TODO: Decrypt

                                val group = MlsGroup.processWelcome(
                                    welcomeBytes,
                                    bundle = KeyPackageBundle(
                                        keyPackage = KeyPackageUtils.decodeKeyPackage(
                                            marmotKeyPackageBundle.tlsEncodedMarmotKeyPackage
                                        ),
                                        initPrivateKey = initPrivateKey,
                                        signaturePrivateKey = signaturePrivateKey,
                                        encryptionPrivateKey = encryptionPrivateKey
                                    )
                                )
                                logger.d("Group: $group")

                                // The room's address, from whichever profile the group
                                // is on: the routing component for a current-profile
                                // group, the 0xF2EE extension for a legacy one. A group
                                // with neither is not a Marmot group and there is no
                                // room to make of it. The room's *id* is what the group
                                // says it is -- the address, unless its description names
                                // the room, as a current-profile room this app created
                                // does. See MarmotRoomIdentity and MarmotRouting.
                                val routingId = group.routingId().takeIf { group.currentNostrGroupId() != null }
                                    ?: throw MarmotMissingNostrGroupDataExtension("Didn't find group data for $group")
                                val nostrGroupId = group.roomId()

                                logger.d("nostrGroupId: $nostrGroupId")

                                val localChatRoom = database.chatRoomDao().findChatRoomById(
                                    nostrGroupId
                                )

                                if (localChatRoom == null) {
                                    logger.d("processWelcome: welcomeBytes=${welcomeBytes.size}B")
                                    logger.d("Marmot state: ${group.currentGroupState()} legacy=${group.currentMarmotData()}")


                                    // TODO: Save the chat room We can now s
                                    val userPublicKey = activeKeyPair.pubKey.toHex()
                                    database.chatRoomDao().upsert(
                                        ChatRoom(
                                            id = nostrGroupId,
                                            userPublicKey = userPublicKey,
                                            subject = group.roomName(),
                                            description = group.roomDescription(),
                                            initialGiftWrapPayloadId = decryptedGiftWrapPayload.id,
                                            createdAt = decryptedGiftWrapPayload.createdAt,
                                            // The Welcome's own `created_at`, which the
                                            // inviter stamps as it mints the Welcome out of
                                            // the Add commit that made us a member. That
                                            // commit is what created the epoch we are joining
                                            // at, so it is the group's clock on when this
                                            // room's history stops being ours to read. See
                                            // ChatRoom.predatesMembership.
                                            joinedGroupAt = decryptedGiftWrapPayload.createdAt,
                                            mlsGroupState = group.saveState().encodeTls().toHex(),
                                        )
                                    )

                                    database.marmotRoutingDao().upsert(
                                        MarmotRouting(routingId = routingId, chatRoomId = nostrGroupId)
                                    )

                                    database.marmotKeyPackageBundleDao().upsert(
                                        marmotKeyPackageBundle.copy(
                                            consumed = true
                                        )
                                    )

                                    val normalizedRelayUrl = NormalizedRelayUrl(relayURL)

                                    val participants = Participant.fromMlsGroup(
                                        mlsGroup = group,
                                        nostrGroupId = nostrGroupId,
                                        relayHint = normalizedRelayUrl.url
                                    )

                                    logger.d("Participants: $participants")
                                    // Sync missing participant profiles...
                                    participants.forEach { participant ->
                                        val giftWrapParticipantProfile = database.profileDao().getProfileByPublicKey(participant.participantPublicKey)

                                        // A placeholder row is not a profile -- it is the
                                        // "LOADING..." name waiting on a kind:0 -- so a member we
                                        // already have one for (seen in another room, say) needs
                                        // asking about just as much as one we have never seen.
                                        // Only a resolved profile takes this member off the list.
                                        if (giftWrapParticipantProfile == null || giftWrapParticipantProfile.createdAt == GENESIS_AT) {
                                            if (giftWrapParticipantProfile == null) {
                                                // Save a placeholder
                                                database.profileDao().insertPlaceholderProfile(
                                                    Profile(
                                                        displayName = "LOADING...",
                                                        publicKey = participant.participantPublicKey,
                                                        createdAt = GENESIS_AT,
                                                        nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                                                    )
                                                )
                                            }

                                            val recommendRelayUrl = giftWrapMessage.receiverRelayHit

                                            if (recommendRelayUrl != null) {
                                                if (profilePublicKeysToSync[recommendRelayUrl] == null) {
                                                    profilePublicKeysToSync[recommendRelayUrl] = mutableSetOf()
                                                }
                                                profilePublicKeysToSync[recommendRelayUrl]?.add(participant.participantPublicKey)
                                            } else {
                                                if (profilePublicKeysToSync[relayURL] == null) {
                                                    profilePublicKeysToSync[relayURL] = mutableSetOf()
                                                }
                                                profilePublicKeysToSync[relayURL]?.add(
                                                    participant.participantPublicKey
                                                )
                                            }
                                        }
                                    }

                                    database.participantDao().upsert(
                                        participants
                                    )

                                    // Save ChatRoom Relays...
                                    participants.filter { it.participantPublicKey != userPublicKey }.forEach { participant ->
                                        val publicKey = participant.participantPublicKey

                                        val chatMessageRelayListEvent = database.nostrEventDao().getAuthoredNostrEvents(
                                            kinds = arrayOf(
                                                ChatMessageRelayListEvent.KIND
                                            ),
                                            authors = arrayOf(
                                                publicKey
                                            ),
                                            since = GENESIS_AT,
                                            limit = 5
                                        ).firstOrNull()?.let { nostrEvent ->
                                            if (nostrEvent.kind != ChatMessageRelayListEvent.KIND) {
                                                logger.e("getAuthoredNostrEvents returned invalid ChatMessageRelayListEvent for ${publicKey}: $nostrEvent")
                                                null
                                            } else {
                                                ChatMessageRelayListEvent(
                                                    id = nostrEvent.id,
                                                    tags = nostrEvent.tags,
                                                    pubKey = nostrEvent.pubKey,
                                                    content = nostrEvent.content,
                                                    createdAt = nostrEvent.createdAt.epochSeconds,
                                                    sig = nostrEvent.sig
                                                )
                                            }
                                        }

                                        // See the identical block above: authors=us never matches a
                                        // gift wrap, so only the missing-relay-list case has work to do.
                                        if (chatMessageRelayListEvent == null) {
                                            logger.w("We don't have a chatMessageRelayListEvent for the pubkey $publicKey")

                                            // Sync ChatMessageRelayListEvent publicKey...
                                            // TODO: Get relayHint form participant...
                                            // Inverted test — see the identical block above.
                                            if (!profilePublicKeysToSync.containsKey(relayURL)) {
                                                profilePublicKeysToSync[relayURL] = mutableSetOf()
                                            }
                                            profilePublicKeysToSync[relayURL]?.add(participant.participantPublicKey)
                                        }
                                    }

                                    // The room arrives already knowing what it
                                    // signs with, if this device was in the
                                    // ceremony. The state was signed in the NIP-17
                                    // room the ceremony ran in, before this room
                                    // existed to hold it, so joining is the first
                                    // moment there is a row to file it against --
                                    // see `GroupKeyStateManager.adopt`.
                                    //
                                    // Nothing for a member who was not in the
                                    // ceremony: they hold no signed state and no
                                    // share either, so there is nothing for them to
                                    // pick the wrong one of.
                                    GroupKeyStateManager.adopt(
                                        database = database,
                                        chatRoomId = nostrGroupId
                                    )

                                    // Where is the decoded welcome message...
                                    // TODO: Sync GroupEvents/Messages...
//                                    database.chatMessageDao().upsert(
//                                        ChatMessage(
//                                            giftWrapPayloadId = decryptedGiftWrapPayload.id,
//                                            senderPublicKey = decryptedGiftWrapPayload.publicKey,
//                                            isUserMessage = activeKeyPair.pubKey.toHex() == decryptedGiftWrapPayload.publicKey,
//                                            chatRoomId = nostrGroupId,
//                                            createdAt = decryptedGiftWrapPayload.createdAt,
//                                            content = "Coming soon.", // TODO: Figure out what to do here...
//                                        )
//                                    )
                                } else {
                                    logger.w("Chat Room already exists for $nostrGroupId")
                                }
                                // TODO: Check encoding...
                            } else if (DkgRitualEvents.isDkgRitualKind(decryptedGiftWrapPayload.kind)) {
                                // A ChillDKG ritual message for one of our NIP-17 groups.
                                // The manager is idempotent, so a redelivered message
                                // simply re-runs a step it has already taken.
                                //
                                // The room is created on demand for the same reason a
                                // chat message creates one: standing up a NIP-17 group
                                // sends nothing to anybody, so the ritual's proposal is
                                // routinely the first this group is heard of. Membership
                                // is the payload's p-tags either way.
                                val localChatRoom = getOrCreateNip17ChatRoom(
                                    decryptedGiftWrapPayload = decryptedGiftWrapPayload,
                                    activeKeyPair = activeKeyPair,
                                    nostrEventId = nostrEvent.id,
                                    relayURL = relayURL
                                )

                                if (localChatRoom == null) {
                                    logger.w("DKG payload for unknown chat room ${decryptedGiftWrapPayload.chatRoomId}")
                                } else {
                                    ChillDkgRitualManager.processRitualPayload(
                                        database = database,
                                        localChatRoom = localChatRoom,
                                        // The rumor as its sender wrote it, the
                                        // shape a Marmot inner event arrives in
                                        // too -- the manager reads one protocol
                                        // off both transports. `sig` is empty
                                        // because a gift-wrapped rumor carries
                                        // none; the seal is what vouches for the
                                        // author.
                                        innerEvent = Event(
                                            id = decryptedGiftWrapPayload.id,
                                            pubKey = decryptedGiftWrapPayload.publicKey,
                                            createdAt = decryptedGiftWrapPayload.createdAt.epochSeconds,
                                            kind = decryptedGiftWrapPayload.kind,
                                            tags = decryptedGiftWrapPayload.tags,
                                            content = decryptedGiftWrapPayload.content,
                                            sig = ""
                                        ),
                                        userPublicKey = activeKeyPair.pubKey.toHex(),
                                        nostrPrivateKey = activeKeyPair.privKey!!
                                    )
                                }
                            } else if (FrostSigningEvents.isFrostSigningKind(decryptedGiftWrapPayload.kind)) {
                                // A FROST signing message for one of our NIP-17
                                // groups, which is where a group makes its first
                                // signature: the statement of what its admin room
                                // signs with, agreed before that room is created.
                                // See `GroupKeyStateManager.propose`.
                                //
                                // The room is created on demand for the same reason
                                // a ritual message creates one, and the manager is
                                // idempotent, so a redelivered message re-runs a step
                                // it has already taken.
                                val localChatRoom = getOrCreateNip17ChatRoom(
                                    decryptedGiftWrapPayload = decryptedGiftWrapPayload,
                                    activeKeyPair = activeKeyPair,
                                    nostrEventId = nostrEvent.id,
                                    relayURL = relayURL
                                )

                                if (localChatRoom == null) {
                                    logger.w("FROST payload for unknown chat room ${decryptedGiftWrapPayload.chatRoomId}")
                                } else {
                                    FrostSigningManager.processSigningPayload(
                                        database = database,
                                        localChatRoom = localChatRoom,
                                        // The rumor as its sender wrote it. `sig` is
                                        // empty because a gift-wrapped rumor carries
                                        // none -- the seal is what vouches for the
                                        // author -- and nothing in a signing session
                                        // reads it: what a session puts a signature
                                        // to is the event inside the proposal, not
                                        // the message carrying it.
                                        innerEvent = Event(
                                            id = decryptedGiftWrapPayload.id,
                                            pubKey = decryptedGiftWrapPayload.publicKey,
                                            createdAt = decryptedGiftWrapPayload.createdAt.epochSeconds,
                                            kind = decryptedGiftWrapPayload.kind,
                                            tags = decryptedGiftWrapPayload.tags,
                                            content = decryptedGiftWrapPayload.content,
                                            sig = ""
                                        ),
                                        userPublicKey = activeKeyPair.pubKey.toHex()
                                    )
                                }
                            } else {
                                logger.w("Unsupported event: $decryptedGiftWrapPayload")
                            }
                        }
                    }
                }
            }
        }

        nostrEvent.toPost()?.let { post ->
            if (post.replyToId != null) {
                // Find or create placeHolder note that is replyTo
                val replyingToPost = database.postDao().getPostById(post.replyToId)

                if (replyingToPost == null) {
                    // Persist PlaceHolder and sync
                    // TODO: Get replyingToAuthorPublicKey
                    database.postDao().insert(
                        Post(
                            id = post.replyToId,
                            createdAt = GENESIS_AT,
                            nostrEventId = nostrEvent.id, // Will get overwriting by sync handled by tagged events...
                            content = post.replyToId,
                            profilePublicKey = nostrEvent.pubKey // Will get overwriting by sync handled by tagged events...
                        )
                    )
                }
            }

            database.postDao().upsert(post)
        }

        nostrEvent.toReaction()?.let { reaction ->
            val replyingToPost = database.postDao().getPostById(reaction.replyToId)

            if (replyingToPost == null) {
                // Persist PlaceHolder and sync
                database.postDao().insert(
                    Post(
                        id = reaction.replyToId,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEvent.id, // Will get overwriting by sync handled by tagged events...
                        content = reaction.replyToId,
                        profilePublicKey = nostrEvent.pubKey // Will get overwriting by sync handled by tagged events...
                    )
                )
            }
            database.reactionDao().upsert(reaction)
        }

        nostrEvent.toQuotedRelation()?.let { quotedRelation ->
            val quotedNostrEvent = database.nostrEventDao().getNostrEventById(quotedRelation.quotedNostrEventId)

            val quotedProfilePublicKey = if (quotedNostrEvent == null) {
                if (eventIdsToSync[relayURL] == null) {
                    eventIdsToSync[relayURL] = mutableSetOf()
                }
                eventIdsToSync[relayURL]?.add(quotedRelation.quotedNostrEventId)

                database.nostrEventDao().insert(
                    NostrEvent(
                        id = quotedRelation.quotedNostrEventId,
                        content = "Pending Sync",
                        createdAt = GENESIS_AT,
                        pubKey = nostrEvent.pubKey,
                        sig = "",
                        kind = nostrEvent.kind,
                        tags = emptyArray()
                    )
                )

                quotedRelation.quotedProfilePublicKey
            } else {
                quotedNostrEvent.pubKey
            }

            val quotedProfile = quotedProfilePublicKey?.let { database.profileDao().getProfileByPublicKey(it) }

            if (quotedProfilePublicKey != null && quotedProfile == null) {
                database.profileDao().insertPlaceholderProfile(
                    Profile(
                        displayName = "LOADING...",
                        publicKey = quotedProfilePublicKey,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                    )
                )
            }

            // TODO: Might need to store placeholders
            database.quotedRelationDao().upsert(
                quotedRelation.copy(
                    quotedProfilePublicKey = quotedProfilePublicKey
                )
            )
        }

        nostrEvent.toInReplyToRelation()?.let { inReplyToRelation ->

            val inReplyToNostrEvent = database.nostrEventDao().getNostrEventById(inReplyToRelation.inReplyToNostrEventId)

            val inReplyToProfilePublicKey = if (inReplyToNostrEvent == null) {
                if (eventIdsToSync[relayURL] == null) {
                    eventIdsToSync[relayURL] = mutableSetOf()
                }
                eventIdsToSync[relayURL]?.add(inReplyToRelation.inReplyToNostrEventId)

                database.nostrEventDao().insert(
                    NostrEvent(
                        id = inReplyToRelation.inReplyToNostrEventId,
                        content = "Pending Sync",
                        createdAt = GENESIS_AT,
                        pubKey = nostrEvent.pubKey,
                        sig = "",
                        kind = nostrEvent.kind,
                        tags = emptyArray()
                    )
                )

                inReplyToRelation.inReplyToProfilePublicKey
            } else {
                inReplyToNostrEvent.pubKey
            }

            val inReplyToRootNostrEvent = inReplyToRelation.inReplyToRootNostrEventId?.let { database.nostrEventDao().getNostrEventById(it) }

            val inReplyToRootProfilePublicKey = if (inReplyToRootNostrEvent == null) {
                if (eventIdsToSync[relayURL] == null) {
                    eventIdsToSync[relayURL] = mutableSetOf()
                }
                eventIdsToSync[relayURL]?.add(inReplyToRelation.inReplyToNostrEventId)

                inReplyToRelation.inReplyToRootProfilePublicKey
                // Might need to save place holder...
            } else {
                inReplyToRootNostrEvent.pubKey
            }

            val inReplyToProfile = inReplyToRootProfilePublicKey?.let { database.profileDao().getProfileByPublicKey(it) }

            if (inReplyToRootProfilePublicKey != null && inReplyToProfile == null) {
                database.profileDao().insertPlaceholderProfile(
                    Profile(
                        displayName = "LOADING...",
                        publicKey = inReplyToRootProfilePublicKey,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                    )
                )
            }

            database.inReplyToRelationDao().upsert(
                inReplyToRelation.copy(
                    inReplyToRootProfilePublicKey = inReplyToRootProfilePublicKey,
                    inReplyToProfilePublicKey = inReplyToProfilePublicKey
                )
            )
        }

        nostrEvent.toZap()?.let { zap ->
            // Find or create placeHolder note that is being reposted
            val zappedPost = database.postDao().getPostById(zap.postId)

            if (zappedPost == null) {
                // Persist PlaceHolder and sync
                database.postDao().insert(
                    Post(
                        id = zap.postId,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEvent.id, // Will get overwriting by sync,
                        content = zap.postId,
                        profilePublicKey = nostrEvent.pubKey // Will get overwriting by sync,
                    )
                )

                // Request a sync for the post being replied to
                if (eventIdsToSync[relayURL] == null) {
                    eventIdsToSync[relayURL] = mutableSetOf()
                }
                eventIdsToSync[relayURL]?.add(zap.postId)
            }

            database.zapDao().upsert(zap)
        }

        if (nostrEvent.kind == ContactListEvent.KIND) {
            val contactListEvent = ContactListEvent(
                id = nostrEvent.id,
                tags = nostrEvent.tags,
                pubKey = nostrEvent.pubKey,
                createdAt = nostrEvent.createdAt.epochSeconds,
                content = nostrEvent.content,
                sig = nostrEvent.sig
            )
            logger.i("Store contact list: $contactListEvent")

            contactListEvent.verifiedFollowKeySet().forEach { followedHexKEys ->

                // Find or create profile with the pubKey... if not found submit a sync request...
                val profile = database.profileDao().getProfileByPublicKey(followedHexKEys)

                if (profile == null) {
                    val placeHolderProfile =
                        Profile(
                            displayName = "LOADING...",
                            publicKey = followedHexKEys,
                            createdAt = GENESIS_AT,
                            nostrEventId = nostrEvent.id,  // Will get overwriting by sync,
                        )
                    database.profileDao().insertPlaceholderProfile(placeHolderProfile)
                    if (profilePublicKeysToSync[relayURL] == null) {
                        profilePublicKeysToSync[relayURL] = mutableSetOf()
                    }
                    profilePublicKeysToSync[relayURL]?.add(followedHexKEys)
                } else if (profile.createdAt == GENESIS_AT && level == 0) {
                    logger.i("${level} This is a placeholder profile... that might need to get synced...: $profile")

                    if (profilePublicKeysToSync[relayURL] == null) {
                        profilePublicKeysToSync[relayURL] = mutableSetOf()
                    }
                    profilePublicKeysToSync[relayURL]?.add(followedHexKEys)
                }

                // Save profile connections...
                database.connectionDao().upsert(
                    Connection(
                        sourcePublicKey = nostrEvent.pubKey,
                        destinationPublicKey = followedHexKEys,
                        nostrEventId = nostrEvent.id
                    )
                )
            }
            // TODO: Delete connections where sourcePublicKey == nostr.pubKey but destinationPublickKey !in verifiedFollowKeySet
        }

        if (level == 0) {
            val negentropySynchronizeRequests = mutableListOf<NegentropySynchronizeRequest>()
            profilePublicKeysToSync.forEach { relayUrlProfilePublicKey ->
                val synchronizationFilter =
                    SynchronizationFilter(
                        authors = relayUrlProfilePublicKey.value.toTypedArray(),
                        kinds = profileEventKinds
                    )
                negentropySynchronizeRequests.add(
                    NegentropySynchronizeRequest(
                        id = NegentropySynchronizeRequest.computeId(
                            relayUrlProfilePublicKey.key,
                            synchronizationFilter
                        ),
                        purpose = "synchronization",
                        synchronizationFilter = synchronizationFilter,
                        relayURL = relayUrlProfilePublicKey.key,
                        level = level + 1
                    )
                )
            }

            eventIdsToSync.forEach { relayUrlEventIds ->
                // TODO: Hash relayUrl + synchronicationFilter + ephocMinutes
                val synchronizationFilter =
                    SynchronizationFilter(
                        ids = relayUrlEventIds.value.toTypedArray(),
                    )
                Clock.System.now().toEpochMilliseconds()
                negentropySynchronizeRequests.add(
                    NegentropySynchronizeRequest(
                        id = NegentropySynchronizeRequest.computeId(
                            relayUrlEventIds.key,
                            synchronizationFilter
                        ),
                        purpose = "synchronization",
                        synchronizationFilter = synchronizationFilter,
                        relayURL = relayUrlEventIds.key,
                        level = level + 1
                    )
                )
            }

            if (negentropySynchronizeRequests.isNotEmpty()) {
                database.negentropySynchronizeRequestDao().insert(
                    negentropySynchronizeRequests
                )
            }
        }
    }

    /**
     * Reads one kind:445 against the room's MLS group and files whatever it turns
     * out to be: a message, a commit that advances the group, an entity for the
     * library, or a placeholder saying it could not be read yet.
     *
     * Its own function so a replay can run exactly what a first delivery ran --
     * see [reindexMarmotGroupEvents]. Throws for a room this device cannot process
     * the event against at all, which the caller decides what to do about: a first
     * delivery lets it roll back its transaction, a replay logs it and moves on.
     *
     * An event from before this device joined is not read and not filed -- see
     * [ChatRoom.predatesMembership]. Nothing about it is this device's: not the
     * epoch key it was encrypted under, and so not the message either. Reading it
     * anyway is how a room a member was invited to yesterday opened on a screenful
     * of "Undecryptable Message" above the conversation, one line for every
     * message the group had sent before they arrived.
     */
    private suspend fun indexMarmotGroupEvent(
        groupEvent: GroupEvent,
        chatRoomId: String,
        activeKeyPair: KeyPair,
    ) {
        val localChatRoom = database.chatRoomDao().findChatRoomById(chatRoomId)
            ?: throw MarmotMissingChatGroupException("Couldn't find chatRoom for ${groupEvent.id}")

        if (localChatRoom.chatRoom.predatesMembership(Instant.fromEpochSeconds(groupEvent.createdAt))) {
            logger.d("${groupEvent.id} predates this device joining $chatRoomId, nothing to index")
            return
        }

        // The room's profile picks its pipeline -- see MarmotEngine. A legacy room
        // is read by the app's own manager over the cache, as it always was; a
        // current-profile room by the library's processor over its manager, which
        // is where convergence and message-id dedup live.
        when (localChatRoom.chatRoom.marmotProfile()) {
            MarmotProfile.LEGACY -> indexLegacyMarmotGroupEvent(groupEvent, localChatRoom, activeKeyPair)
            MarmotProfile.CURRENT -> indexCurrentProfileGroupEvent(groupEvent, localChatRoom, activeKeyPair)
        }
    }

    private suspend fun indexCurrentProfileGroupEvent(
        groupEvent: GroupEvent,
        localChatRoom: LocalChatRoom,
        activeKeyPair: KeyPair,
    ) {
        val chatRoomId = localChatRoom.chatRoom.id
        val routingId = groupEvent.groupId()
            ?: throw MarmotMissingNostrGroupDataExtension("${groupEvent.id} carries no h tag")
        val engine = database.marmotEngine
        val mlsGroup = engine.groupFor(routingId)
            ?: throw MarmotMissingNostrGroupDataExtension("Couldn't restore the group for ${groupEvent.id}")

        if (!mlsGroup.currentMemberIdentities().contains(activeKeyPair.pubKey.toHex())) {
            throw MarmotNotMemberOfChatGroupException("We are not a member of the chat room $chatRoomId")
        }

        upsertMarmotGroupEvent(groupEvent, chatRoomId, activeKeyPair)

        // The processor names the group by its address; every row below is keyed by
        // the room, so the result is re-addressed once.
        val groupEventResult = engine.inbound.processGroupEvent(groupEvent).inRoom(chatRoomId)
        logger.d("groupEventResult: $groupEventResult")

        fileGroupEventResult(
            localChatRoom = localChatRoom,
            groupEvent = groupEvent,
            groupEventResult = groupEventResult,
            activeKeyPair = activeKeyPair,
            // The live group the manager holds, which the commit just moved.
            mlsGroup = engine.groupManager.getGroup(routingId) ?: mlsGroup,
        )

        // A commit that forked the group opened a pass, and a quiet group has
        // nothing else to drive it to its cutoff.
        if (engine.inbound.openConvergencePasses().isNotEmpty()) {
            engine.settleLater()
        }
    }

    private suspend fun indexLegacyMarmotGroupEvent(
        groupEvent: GroupEvent,
        localChatRoom: LocalChatRoom,
        activeKeyPair: KeyPair,
    ) {
        val chatRoomId = localChatRoom.chatRoom.id

        // Through the cache rather than rebuilt here, so the secret
        // tree's skipped-generation keys survive from one message to
        // the next. Two events published in the same instant arrive in
        // whatever order the relay feels like, and rebuilding between
        // them loses the earlier one for good -- see MlsGroupCache.
        val handled = MlsGroupCache.withGroup(
            chatRoomId = chatRoomId,
            storedStateHex = localChatRoom.chatRoom.mlsGroupState,
            build = { localChatRoom.chatRoom.toMlsGroup() },
            save = { stateHex ->
                database.chatRoomDao().upsert(
                    localChatRoom.chatRoom.copy(mlsGroupState = stateHex)
                )
            }
        ) { mlsGroup ->
            val memberPubkeys = mlsGroup.members().mapNotNull { (leafIndex, leafNode) ->

                val pubkey = when (val cred = leafNode.credential) {
                    is Credential.Basic -> cred.identity.toHexKey()
                    else -> null
                }

                logger.d("leafIndex=$leafIndex pubKey=$pubkey")
                pubkey
            }

            if (memberPubkeys.contains(activeKeyPair.pubKey.toHex())) {
                upsertMarmotGroupEvent(groupEvent, chatRoomId, activeKeyPair)
                MarmotInboundManager.processGroupEvent(
                    database = database,
                    activeKeyPair = activeKeyPair,
                    localChatRoom = localChatRoom,
                    mlsGroup = mlsGroup,
                    groupEvent = groupEvent
                )?.let { groupEventResult ->
                    logger.d("groupEventResult: $groupEventResult")

                    fileGroupEventResult(
                        localChatRoom = localChatRoom,
                        groupEvent = groupEvent,
                        groupEventResult = groupEventResult,
                        activeKeyPair = activeKeyPair,
                        mlsGroup = mlsGroup,
                    )
                }
            } else {
                throw MarmotNotMemberOfChatGroupException("We are not a member of the chat room ${localChatRoom.chatRoom.id}")
            }
        }

        // Null means the room has no usable group state, which is what
        // a failed toMlsGroup() meant before the cache existed.
        if (handled == null) {
            throw MarmotMissingNostrGroupDataExtension("Couldn't find chatRoom for ${groupEvent.id}")
        }
    }

    private suspend fun upsertMarmotGroupEvent(
        groupEvent: GroupEvent,
        chatRoomId: String,
        activeKeyPair: KeyPair,
    ) {
        database.marmotGroupEventDao().upsert(
            MarmotGroupEvent(
                id = groupEvent.id,
                userPublicKey = activeKeyPair.pubKey.toHex(),
                publicKey = groupEvent.pubKey,
                encryptedContent = groupEvent.encryptedContent(),
                chatRoomId = chatRoomId,
                createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                expiresAt = groupEvent.expiration()?.let { Instant.fromEpochSeconds(it) },
                signature = groupEvent.sig
            )
        )
    }

    /**
     * Turns what a kind:445 turned out to be into rows: the membership changes a
     * commit made, the chat line, and the signing or ceremony step a payload drives.
     * The same for either pipeline, which is why it is one function.
     *
     * [mlsGroup] is the room's live group after the event was applied: the tree
     * the sender's leaf index is resolved against, and the membership a commit is
     * diffed against.
     */
    private suspend fun fileGroupEventResult(
        localChatRoom: LocalChatRoom,
        groupEvent: GroupEvent,
        groupEventResult: GroupEventResult,
        activeKeyPair: KeyPair,
        mlsGroup: MlsGroup,
    ) {
        when(groupEventResult) {
            is GroupEventResult.CommitProcessed -> {
                MarmotInboundManager.processGroupMembershipChanges(
                    database = database,
                    mlsGroup = mlsGroup,
                    localChatRoom = localChatRoom
                )
            }
            // A member's standalone proposal -- their SelfRemove -- sits in the
            // pool until an admin commits it. An admin's device does so on
            // receipt; on any other device this does nothing.
            is GroupEventResult.ProposalStaged -> {
                database.marmotOutboundDao().commitStagedProposals(localChatRoom)
            }
            // Every application message lands here -- chat, documents, and the
            // ceremony and signing messages handled just below. Debug, because at
            // info it read like a failure on each of them and sent at least one
            // investigation the wrong way.
            else -> {
                logger.d("No membership change to file for: $groupEventResult")
            }
        }

        ChatMessage.fromGroupEventResult(
            database = database,
            activeKeyPair = activeKeyPair,
            groupEvent = groupEvent,
            groupEventResult = groupEventResult,
            // Who sent it comes from the MLS frame, not the
            // payload. Resolved here because this is where the
            // group is; the leaf index is on the result, and the
            // tree has not moved -- an application message
            // advances no epoch.
            senderIdentity = (groupEventResult as? GroupEventResult.ApplicationMessage)
                ?.let { mlsGroup.memberIdentityHex(it.senderLeafIndex) },
        )?.let { chatMessage ->
            logger.d("chatMessage: $chatMessage")
            persistMarmotChatMessage(chatMessage)
        }

        // A signing or ceremony message for this group. Driven
        // from here rather than from ChatMessage because each
        // manager needs the room to publish its own replies
        // into, and because both write their transcript lines
        // themselves. Both are idempotent, so a redelivered
        // message re-runs a step already taken.
        //
        // A ceremony arrives here when the group is standing up
        // a subgroup: the child's ChillDKG runs in this room,
        // over the admins the proposal p-tags, because the
        // child has no room of its own until the ceremony has
        // produced the key its id is derived from. A member of
        // this room who was not picked drops the proposal in
        // `acceptProposal` and never opens a session.
        //
        // A GroupKeyStateEvent is deliberately not dispatched
        // here. It is no longer something a member says --
        // it is what a session like this one produces -- and
        // `ChatMessage.applyInnerEvent` files it alongside
        // every other event the group has signed.
        if (groupEventResult is GroupEventResult.ApplicationMessage) {
            Event.fromJsonOrNull(groupEventResult.innerEventJson)
                ?.let { innerEvent ->
                    when {
                        FrostSigningEvents.isFrostSigningKind(innerEvent.kind) ->
                            FrostSigningManager.processSigningPayload(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                                userPublicKey = activeKeyPair.pubKey.toHex()
                            )

                        DkgRitualEvents.isDkgRitualKind(innerEvent.kind) ->
                            ChillDkgRitualManager.processRitualPayload(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                                userPublicKey = activeKeyPair.pubKey.toHex(),
                                nostrPrivateKey = activeKeyPair.privKey!!
                            )

                        // The Lightning wallet's co-signing: round one ahead of time,
                        // the operator's requests, every member's echo, the shares.
                        innerEvent.kind in GroupLightningEvents.CO_SIGNING ->
                            GroupLightningCoSigner.process(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                                userPublicKey = activeKeyPair.pubKey.toHex()
                            )

                        // A member handing out the Lightning wallet's node seed,
                        // encrypted to each participant inside the room.
                        innerEvent.kind == GroupLightningEvents.NODE_SEED ->
                            GroupLightningWalletManager.receiveSeed(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                                userPublicKey = activeKeyPair.pubKey.toHex(),
                                nostrPrivateKey = activeKeyPair.privKey!!
                            )

                        // The Wavelength wallet's co-signing, as the Lightning wallet's above.
                        innerEvent.kind in GroupWavelengthEvents.CO_SIGNING ->
                            GroupArkCoSigner.process(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                                userPublicKey = activeKeyPair.pubKey.toHex()
                            )

                        // A member handing out the Wavelength wallet's authentication seed,
                        // encrypted to each participant inside the room.
                        innerEvent.kind == GroupWavelengthEvents.AUTH_SEED ->
                            GroupWavelengthWalletManager.receiveSeed(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                                userPublicKey = activeKeyPair.pubKey.toHex(),
                                nostrPrivateKey = activeKeyPair.privKey!!
                            )

                        // A member asking for a boarding address, and the operator's answer.
                        innerEvent.kind == GroupWavelengthEvents.DEPOSIT_REQUEST || innerEvent.kind == GroupWavelengthEvents.DEPOSIT_ANSWER ->
                            GroupWavelengthDeposits.process(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                            )

                        // What the operator says about the Wavelength wallet.
                        innerEvent.kind == GroupWavelengthEvents.SNAPSHOT || innerEvent.kind == GroupWavelengthEvents.STOPPED ->
                            GroupWavelengthReports.process(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                            )

                        // What the operator says about the Lightning wallet, and the
                        // invoices it makes for members who ask.
                        innerEvent.kind == GroupLightningEvents.SNAPSHOT ||
                            innerEvent.kind == GroupLightningEvents.INVOICE ||
                            innerEvent.kind == GroupLightningEvents.INVOICE_REQUEST ->
                            GroupLightningReports.process(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                            )

                        // An operator's node stopping: a line, from whoever ran that term.
                        innerEvent.kind == GroupLightningEvents.STOPPED ->
                            GroupLightningOperatorManager.receiveStopped(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                            )

                        // The group Lightning wallet's key ceremony. Marmot only:
                        // it runs where the onchain wallet it closes to already is.
                        innerEvent.kind in GroupLightningEvents.CEREMONY ->
                            IcebergRitualManager.processCeremonyPayload(
                                database = database,
                                localChatRoom = localChatRoom,
                                innerEvent = innerEvent,
                                userPublicKey = activeKeyPair.pubKey.toHex(),
                                nostrPrivateKey = activeKeyPair.privKey!!
                            )
                    }
                }
        }
    }

    /**
     * Files the chat line a kind:445 produced, replacing the line that event
     * already has rather than adding a second one. The rule for which of the two
     * wins is [ChatMessage.reconcileMarmotLine].
     */
    private suspend fun persistMarmotChatMessage(chatMessage: ChatMessage) {
        val existing = chatMessage.marmotGroupEventId?.let {
            database.chatMessageDao().getChatMessagesByMarmotGroupEventId(it)
        }

        val row = ChatMessage.reconcileMarmotLine(fresh = chatMessage, existing = existing)

        if (row == null) {
            logger.d("Keeping ${existing?.messageType} line for ${chatMessage.marmotGroupEventId}")
            return
        }

        database.chatMessageDao().upsert(row)
    }

    /**
     * Reads a room's stored kind:445 events again, oldest first.
     *
     * Relays impose no ordering, so a group event can arrive before the group is
     * able to read it: an application message encrypted under an epoch whose commit
     * has not landed yet, or a commit for an epoch ahead of the local one. Both are
     * stored and then dropped -- `MarmotInboundManager` refuses to apply an
     * out-of-epoch commit precisely so it does not half-mutate the group -- and
     * nothing revisits them once the missing event fills the gap. This is the
     * revisit, driven from the group's detail screen.
     *
     * Only events with nothing to show for them are replayed: no chat line at all,
     * or one of [ChatMessage.UNRESOLVED_MARMOT_TYPES]. Everything already read is
     * left untouched, which is what keeps the replay from disturbing a room where
     * there was nothing wrong. Within that set a replay is safe to repeat: the ids
     * of every row it writes are derived from the events themselves, commits that
     * are behind the current epoch are rejected as duplicates before they can touch
     * the group, and the one row with a generated id is reconciled by
     * [persistMarmotChatMessage].
     *
     * Passes repeat while a pass recovers something -- see [MarmotReindexSweep] --
     * because created_at order is not necessarily epoch order, and a commit
     * recovered by one pass can be what lets the next read the messages that were
     * waiting on it.
     *
     * Events from before this device joined are left out of the sweep entirely --
     * see [ChatRoom.predatesMembership]. They are the one part of the backlog a
     * replay can say something about in advance: no pass will ever read them, so
     * replaying them only spends a refused decrypt each time and reports every one
     * of them as a failure, on a room where nothing is wrong.
     *
     * What this cannot do is recover a message whose key is gone: an application
     * message the ratchet has already advanced past. Those stay unreadable however
     * often they are replayed.
     */
    open suspend fun reindexMarmotGroupEvents(
        chatRoomId: String,
        activeKeyPair: KeyPair,
    ): MarmotReindexReport {
        val userPublicKey = activeKeyPair.pubKey.toHex()
        val chatRoom = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom

        // The query's LIKE only narrows; the h tag is what decides the room, and the
        // h tag is one of the room's routing addresses -- its own id, or the ones the
        // routing table records. Sorted by CommitOrdering's own comparator rather
        // than left in createdAt order, so that where two commits compete for an
        // epoch the first one replayed is the one every other member also picked.
        val routingIds = database.marmotRoutingDao().findByChatRoomId(chatRoomId).map { it.routingId }.ifEmpty { listOf(chatRoomId) }
        val groupEvents = routingIds
            .flatMap { database.nostrEventDao().getMarmotGroupNostrEventsByChatRoomId(it) }
            .distinctBy { it.id }
            .mapNotNull { storedNostrEvent ->
                storedNostrEvent
                    .toGroupEvent(userPublicKey = userPublicKey)
                    ?.takeIf { it.groupId() in routingIds }
            }
            .sortedWith(CommitOrdering.comparator)

        // Held back rather than dropped from the report: they are counted in
        // `stored` and again on their own, so the screen can say a room is mostly
        // older than the member reading it instead of calling those events read.
        //
        // A room with no row to ask keeps every event, so a replay against one
        // does what it always did rather than quietly finding nothing to do.
        val (readable, predatingMembership) = groupEvents.partition { groupEvent ->
            chatRoom?.predatesMembership(Instant.fromEpochSeconds(groupEvent.createdAt)) != true
        }

        logger.i(
            "Reindex $chatRoomId: ${groupEvents.size} stored group event(s), " +
                "${predatingMembership.size} from before this device joined"
        )

        // Held commits are what a replay is most often for, and they are only ever
        // cleared by one applying -- see MarmotInboundManager.forgetPendingCommits.
        // A current-profile room holds nothing of the kind: its competing commits
        // are candidates in a convergence pass, which is closed here so the replay
        // runs against settled state rather than a branch a pass is about to drop.
        when (chatRoom?.marmotProfile()) {
            MarmotProfile.CURRENT -> {
                val engine = database.marmotEngine
                routingIds.forEach { routingId ->
                    engine.groupFor(routingId)
                    engine.inbound.resolveConvergence(routingId)
                }
            }
            else -> MarmotInboundManager.forgetPendingCommits(chatRoomId)
        }

        val resolved = database.chatMessageDao().getResolvedMarmotGroupEventIds(
            chatRoomId = chatRoomId,
            unresolvedTypes = ChatMessage.UNRESOLVED_MARMOT_TYPES,
        ).toSet()

        return MarmotReindexSweep.run(
            stored = groupEvents.size,
            predatingMembership = predatingMembership.size,
            unresolved = readable.filterNot { it.id in resolved },
            replay = { groupEvent ->
                indexMarmotGroupEvent(
                    groupEvent = groupEvent,
                    chatRoomId = chatRoomId,
                    activeKeyPair = activeKeyPair,
                )
            },
            isUnresolved = { groupEvent -> isUnresolvedMarmotGroupEvent(groupEvent.id) },
        ).also {
            logger.i("Reindex $chatRoomId: $it")
        }
    }

    /**
     * Whether one group event still has nothing to show for it, asked again after
     * a replay to see whether that replay achieved anything.
     *
     * Some events can never leave this state and will be swept every time: a commit
     * rejected as a duplicate writes no line, and neither does a commit this device
     * sent, whose line was written against the send rather than the event. Both cost
     * one refused decrypt per sweep, which is cheaper than a bookkeeping column that
     * would have to be migrated in.
     */
    private suspend fun isUnresolvedMarmotGroupEvent(groupEventId: String): Boolean {
        val chatMessage = database.chatMessageDao().getChatMessagesByMarmotGroupEventId(groupEventId)
        return chatMessage == null || chatMessage.messageType in ChatMessage.UNRESOLVED_MARMOT_TYPES
    }

    /**
     * Files an arriving NIP-17 chat message so it shows up in the room's feed.
     *
     * The feed reads ChatMessage, and until now nothing wrote one for an inbound
     * gift wrap: the payload was decrypted, stored, used to build the room, and then
     * dropped. Members could send to each other and see their own words -- written
     * locally by `sendChatMessage` -- while everything they were sent stayed
     * invisible. MLS rooms were never affected, since that path builds its rows
     * through `ChatMessage.fromGroupEventResult`.
     *
     * Two arrivals are deliberately not filed:
     *
     * A message already filed. Relays redeliver and negentropy re-syncs, and the
     * same gift wrap yields the same payload id each time, so the payload id is what
     * makes a second delivery a no-op rather than a second line in the chat --
     * ChatMessage.id is autogenerated and would happily take a duplicate.
     *
     * Our own words coming back. `sealGiftWrapPayload` wraps a copy to every
     * participant including the sender, so a message returns to the device that sent
     * it, and `sendChatMessage` already wrote that row on the way out. The two copies
     * cannot be matched on the payload id -- the outbound row is keyed on the rumor's
     * hash and the inbound one on the seal's -- so the sender is matched instead.
     * The cost is that a second install of the same identity will not pick up
     * messages sent from the first; that needs the inbound payload keyed on the rumor
     * it came from, which is a change to identity for every kind, not just this one.
     */
    private suspend fun persistInboundChatMessage(
        decryptedGiftWrapPayload: GiftWrapPayload,
        activeKeyPair: KeyPair
    ) {
        if (database.chatMessageDao().getChatMessagesByGiftWrapPayloadId(decryptedGiftWrapPayload.id) != null) {
            logger.d("Chat message for payload ${decryptedGiftWrapPayload.id} is already filed")
            return
        }

        if (decryptedGiftWrapPayload.publicKey == activeKeyPair.pubKey.toHex()) {
            logger.d("Payload ${decryptedGiftWrapPayload.id} is our own message coming back; already stored on send")
            return
        }

        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = decryptedGiftWrapPayload.publicKey,
                isUserMessage = false,
                giftWrapPayloadId = decryptedGiftWrapPayload.id,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = decryptedGiftWrapPayload.chatRoomId,
                content = decryptedGiftWrapPayload.content,
                // The rumor's own timestamp, not the wrap's -- NIP-17 fuzzes the
                // wrap and the seal by up to two days to frustrate correlation, and
                // ordering the feed by that would shuffle the conversation.
                createdAt = decryptedGiftWrapPayload.createdAt
            )
        )
    }

    /**
     * The NIP-17 room a decrypted payload belongs to, standing it up if this is the
     * first the device has heard of the group.
     *
     * Membership under NIP-17 *is* the p-tag set on the message, and the room id is
     * the aggregate of it, so any payload that routes here carries everything the
     * room needs. Creating a group locally sends nothing to anyone, which is why the
     * first arrival for a group is just as likely to be a key ceremony as a message.
     */
    private suspend fun getOrCreateNip17ChatRoom(
        decryptedGiftWrapPayload: GiftWrapPayload,
        activeKeyPair: KeyPair,
        nostrEventId: String,
        relayURL: String
    ): LocalChatRoom? {
        database.chatRoomDao().findChatRoomById(decryptedGiftWrapPayload.chatRoomId)?.let { return it }

        val participants = decryptedGiftWrapPayload.participantPTags(
            NormalizedRelayUrl(relayURL) // TODO: Get relayURL for publicKey profile...
        ).map {
            Participant(
                participantPublicKey = it.pubKey,
                chatRoomId = decryptedGiftWrapPayload.chatRoomId,
                relayHint = it.relayHint?.url
            )
        }

        if (participants.none { it.participantPublicKey == activeKeyPair.pubKey.toHex() }) {
            logger.w("Payload ${decryptedGiftWrapPayload.id} builds a room this device isn't in")
            return null
        }

        // Both ChatRoom.userPublicKey and Participant.participantPublicKey are
        // foreign keys onto Profile, so every member needs a row before either.
        participants.forEach { participant ->
            if (database.profileDao().getProfileByPublicKey(participant.participantPublicKey) == null) {
                database.profileDao().insertPlaceholderProfile(
                    Profile(
                        displayName = "LOADING...",
                        publicKey = participant.participantPublicKey,
                        createdAt = GENESIS_AT,
                        nostrEventId = nostrEventId, // Will get overwritten by sync
                    )
                )
            }
        }

        database.chatRoomDao().upsert(
            ChatRoom(
                id = decryptedGiftWrapPayload.chatRoomId,
                userPublicKey = activeKeyPair.pubKey.toHex(),
                subject = decryptedGiftWrapPayload.parseSubject(),
                description = null,
                createdAt = decryptedGiftWrapPayload.createdAt,
                initialGiftWrapPayloadId = decryptedGiftWrapPayload.id,
                mlsGroupState = null
            )
        )

        database.participantDao().upsert(participants)

        return database.chatRoomDao().findChatRoomById(decryptedGiftWrapPayload.chatRoomId)
    }

    /**
     * Persist the rescheduling using a transaction so that we are assured of the existence of the
     * [ChatMessageBroadcastNostrEventRequestRelation] when we produce the [com.machankura.dedun.compose.database.model.BroadcastNostrEventReceipt]
     */
    @Transaction
    open suspend fun rescheduleBroadcastNostrEventRequests(broadcastNostrEventRequests: List<BroadcastNostrEventRequest>) {
        // Check if any previous chatMessageBroadcastNostrEventRequestRelationDao exists for this nostrEventId...
        broadcastNostrEventRequests.forEach { broadcastNostrEventRequest ->
            val broadcastNostrEventRequestId = database.broadcastNostrEventRequestDao().upsert(
                broadcastNostrEventRequest
            )

            database.chatMessageDao().getChatMessagesByMarmotGroupEventId(
                marmotGroupEventId = broadcastNostrEventRequest.nostrEventId
            )?.let { chatMessage ->
                database.chatMessageBroadcastNostrEventRequestRelationDao().upsert(
                    ChatMessageBroadcastNostrEventRequestRelation(
                        broadcastNostrEventRequestId = broadcastNostrEventRequestId,
                        chatMessageId = chatMessage.id
                    )
                )
            }
        }
    }

}