package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.nostr.Relays
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.tags.people.isTaggedUser
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip02FollowList.RelaySet
import com.vitorpamplona.quartz.nip02FollowList.tags.ContactTag
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip23LongContent.LongTextNoteEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip31Alts.AltTag
import com.vitorpamplona.quartz.nip51Lists.bookmarkList.BookmarkListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.RelayFeedsListEvent
import com.vitorpamplona.quartz.nip56Reports.ReportEvent
import com.vitorpamplona.quartz.nip57Zaps.LnZapEvent
import com.vitorpamplona.quartz.nip68Picture.PictureEvent
import com.vitorpamplona.quartz.nip71Video.VideoNormalEvent
import com.vitorpamplona.quartz.nip71Video.VideoShortEvent
import com.vitorpamplona.quartz.utils.RandomInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

enum class MetadataEventDetailType {
    Posts, Replies, Articles, Followers, Following, Zaps, Photos, Shorts, Videos,  Bookmarks, Reports, Relays
}

class MetadataEventDetailViewModel(
    initialMetadataEventDetailUIState: com.machankura.dedun.compose.ui.view.state.MetadataEventDetailUIState = com.machankura.dedun.compose.ui.view.state.MetadataEventDetailUIState.Loading,
    val activeUserPublicKey: HexKey,
    val eventPublicKey: HexKey,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
    val pagerState: PagerState
): ViewModel() {

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    var metadataEventDetailUIState: com.machankura.dedun.compose.ui.view.state.MetadataEventDetailUIState by mutableStateOf(initialMetadataEventDetailUIState)
        private set

    val logger = Logger.withTag("MetadataEventDetailViewModel")

    fun initiate() {
        observeMetadataEventRelationWithActiveUser()
        scheduleSynchronization()
    }

    fun scheduleSynchronization() {
        logger.d("scheduleSynchronization")

        viewModelScope.launch(Dispatchers.IO) {
            // Sync Notifications... might want to also run this in the background
            nostrRepository.queueNegentropySynchronizeRequest(
                Relays.DefaultDMRelayList.map { relayUrl ->
                    NegentropySynchronizeRequest(
                        id = RandomInstance.bytes(32).toHexKey(),
                        purpose = "keypackage",
                        synchronizationFilter = SynchronizationFilter(
                            authors = arrayOf(
                                eventPublicKey
                            ),
                            kinds = arrayOf(
                                KeyPackageEvent.KIND
                            )
                        ),
                        relayURL = relayUrl.url,
                        level = 0
                    )
                }
            )
        }
    }

    fun observeMetadataEventRelationWithActiveUser() {
        logger.d("observeLocalNostrFeed")
        viewModelScope.launch(Dispatchers.IO) {

            nostrRepository.observeRelation(
                eventPublicKey,
                activePublicKey = activeUserPublicKey
            ).collect { relations ->
                logger.d("We found relations: $relations")

                metadataEventDetailUIState = com.machankura.dedun.compose.ui.view.state.MetadataEventDetailUIState.Loaded(
                    isActiveUser = isActiveUser(activeUserPublicKey),
                    followingActiveUserConnection = relations.find { connection -> connection.sourcePublicKey == eventPublicKey && connection.destinationPublicKey == activeUserPublicKey },
                    followedByActiveUserConnection = relations.find { connection -> connection.sourcePublicKey == activeUserPublicKey && connection.destinationPublicKey == eventPublicKey }
                )

                if (isActionPending.value) {
                    // This means this new relation was caused by user action...
                    isActionPending.value = false
                }
            }
        }
    }

    fun isActiveUser(activePublicKey: HexKey): Boolean {
        return activePublicKey == eventPublicKey
    }

    fun isActiveUser(): Boolean {
        return isActiveUser(
            activePublicKey = activeUserPublicKey
        )
    }

    fun getSynchronizationFilter(
        metadataEventDetailType: MetadataEventDetailType
    ) = when (metadataEventDetailType) {
        MetadataEventDetailType.Posts -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    TextNoteEvent.KIND,
                    RepostEvent.KIND,
                ),
                limit = 50
            )
        }
        MetadataEventDetailType.Replies -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    TextNoteEvent.KIND,
                    ReactionEvent.KIND,
                ),
                // TODO: replyTo is set...
                limit = 50
            )
        }
        MetadataEventDetailType.Articles -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    LongTextNoteEvent.KIND,
                ),
                limit = 50
            )
        }
        MetadataEventDetailType.Followers -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    ContactListEvent.KIND,
                ),
                // TODO: Check users with hexKey
                limit = 50
            )
        }
        MetadataEventDetailType.Following -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    ContactListEvent.KIND,
                ),
                limit = 50
            )
        }
        MetadataEventDetailType.Zaps -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    LnZapEvent.KIND,
                ),
                // TODO: Zaps user has received...
                limit = 50
            )
        }
        MetadataEventDetailType.Photos -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    PictureEvent.KIND,
                ),
                limit = 50
            )
        }
        MetadataEventDetailType.Shorts -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    VideoShortEvent.KIND,
                ),
                limit = 50
            )
        }
        MetadataEventDetailType.Videos -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    VideoNormalEvent.KIND,
                ),
                limit = 50
            )
        }
        MetadataEventDetailType.Bookmarks -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    BookmarkListEvent.KIND,
                ),
                limit = 50
            )
        }
        MetadataEventDetailType.Reports -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    ReportEvent.KIND,
                ),
                limit = 50
            )
        }
        MetadataEventDetailType.Relays -> {
            com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                authors = arrayOf(
                    eventPublicKey
                ),
                kinds = arrayOf(
                    RelayFeedsListEvent.KIND,
                ),
                limit = 50
            )
        }
    }

    fun follow() {
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val contactListEvent = nostrRepository.getNostrEvent(
                publicKey = activeUserPublicKey,
                kind = ContactListEvent.KIND
            )

            logger.d("ContactListEvent: $contactListEvent")

            val followUsers: TagArray = if (contactListEvent != null) {
                if (contactListEvent.tags.isTaggedUser(eventPublicKey)) {
                    // User already being followed
                    logger.d("User is already following $eventPublicKey")
                    return@launch
                }

                contactListEvent.tags.plus(
                    ContactTag(
                        pubKey = eventPublicKey
                    ).toTagArray()
                )
            } else {
                arrayOf(
                    ContactTag(activeUserPublicKey, null, null).toTagArray(),
                    ContactTag(eventPublicKey, null, null).toTagArray(),
                )
            }
            val contactListTagList = followUsers.map { it }

            nostrRepository.saveUnsignedNostrEvent(
                com.machankura.dedun.compose.database.model.UnsignedNostrEvent(
                    pubKey = activeUserPublicKey,
                    kind = ContactListEvent.KIND,
                    tags = contactListTagList.toTypedArray(),
                    content = RelaySet.assemble(
                        emptyMap()
                    )
                )
            )
        }
    }

    fun unfollow(connection: com.machankura.dedun.compose.database.model.Connection) {
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val contactListEvent = nostrRepository.getNostrEvent(
                publicKey = activeUserPublicKey,
                kind = ContactListEvent.KIND
            )


            if (contactListEvent != null) {
                if (!contactListEvent.tags.isTaggedUser(eventPublicKey)) {
                    logger.d("User is not being followed so no need to unfollow: $eventPublicKey")
                } else {
                    nostrRepository.saveUnsignedNostrEvent(
                        com.machankura.dedun.compose.database.model.UnsignedNostrEvent(
                            pubKey = activeUserPublicKey,
                            kind = ContactListEvent.KIND,
                            tags = contactListEvent.tags.filter { it.size > 1 && it[1] != eventPublicKey }
                                .toTypedArray(),
                            content = RelaySet.assemble(
                                emptyMap()
                            )
                        )
                    )
                }

                nostrRepository.deleteConnection(
                    connection
                )
            }
        }
    }
    companion object {
        const val TAG = "MetadataEventDetailViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            publicKey: HexKey,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
            pagerState: PagerState
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MetadataEventDetailViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    nostrRepository = nostrRepository,
                    pagerState = pagerState,
                    eventPublicKey = publicKey,
                )
            }
        }
    }
}