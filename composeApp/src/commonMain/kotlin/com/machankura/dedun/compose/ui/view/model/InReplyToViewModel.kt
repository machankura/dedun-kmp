package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.ui.composable.widgets.feed.ListView
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Instant
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.loading
import bennu.composeapp.generated.resources.something_went_wrong
import bennu.composeapp.generated.resources.no_replies_to_this_yet
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState

class InReplyToViewModel(
    val nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent,
    val profile: com.machankura.dedun.compose.database.model.Profile?,
    initialFeedListUIState: com.machankura.dedun.compose.ui.view.state.FeedListUIState,
    val synchronizationFilter: com.machankura.dedun.compose.database.model.types.SynchronizationFilter,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
): ViewModel() {
    val logger = Logger.withTag(TAG)
    var inReplyToFeedListUIState: com.machankura.dedun.compose.ui.view.state.FeedListUIState by mutableStateOf(initialFeedListUIState)
        private set

    val isSynchronizationPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        logger.d("init")
        scheduleSynchronization()
        observeLocalNostrFeed()
        observeSynchronizeNostrEventRequestByPurposeAndStatusCount()
    }

    fun observeLocalNostrFeed() {
        logger.d("observeLocalNostrFeed")
        viewModelScope.launch(Dispatchers.IO) {
            nostrRepository.observeNostrFeed(
                synchronizationFilter
            ).distinctUntilChanged().collect { localNostrEvents ->
                inReplyToFeedListUIState = com.machankura.dedun.compose.ui.view.state.FeedListUIState.Loaded(
                    localNostrEvents = localNostrEvents
                )
            }
        }
    }

    fun scheduleSynchronization() {
        logger.d("scheduleSynchronization")
        viewModelScope.launch(Dispatchers.IO) {
            // Sync Notifications... might want to also run this in the background
            val publicRelays = profile?.profilePublicRelays(
                nostrRepository
            ) ?: com.machankura.dedun.compose.nostr.Relays.DefaultDMRelayList

            nostrRepository.queueNegentropySynchronizeRequest(
                publicRelays.take(3).map { normalizedRelayUrl ->
                    com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest(
                        id = com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest.computeId(
                            relayURL = normalizedRelayUrl.url,
                            synchronizationFilter = synchronizationFilter
                        ),
                        purpose = "inReplyTo",
                        synchronizationFilter = synchronizationFilter,
                        relayURL = normalizedRelayUrl.url,
                        level = 0
                    )
                }
            )
        }
    }

    fun observeSynchronizeNostrEventRequestByPurposeAndStatusCount() {
        logger.d("observeSynchronizeNostrEventRequestByPurposeAndStatusCount")
        viewModelScope.launch(Dispatchers.IO) {
            nostrRepository.observeSynchronizeNostrEventRequestByPurposeAndStatusCount("feed").distinctUntilChanged().collect { count ->
                logger.d("Pending/Processing Synchronization Request: $count")
                isSynchronizationPending.value = count > 0
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun RenderFeed(
        activeUserPublicKey: String,
        onNavigateToEvent: (com.machankura.dedun.compose.ui.composable.navigation.routes.Route) -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val feedListUIState = inReplyToFeedListUIState) {
                com.machankura.dedun.compose.ui.view.state.FeedListUIState.Error -> {
                    ErrorState()
                }
                is com.machankura.dedun.compose.ui.view.state.FeedListUIState.Loaded -> {
                    if (feedListUIState.localNostrEvents.isEmpty()) {
                        EmptyState(message = stringResource(Res.string.no_replies_to_this_yet))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                        ) {
                            items(
                                items = feedListUIState.localNostrEvents,
                                key = { localNostrEvent -> localNostrEvent.nostrEvent.id}
                            ) { localNostrEvent ->
                                localNostrEvent.ListView(
                                    onNavigateToEvent = { nostrEventId ->
                                        onNavigateToEvent.invoke(
                                            com.machankura.dedun.compose.ui.composable.navigation.routes.NostrEventDetailRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                nostrEventId = nostrEventId
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }

                }
                com.machankura.dedun.compose.ui.view.state.FeedListUIState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    )  {
                        Spacer(
                            modifier = Modifier.height(MaterialTheme.spacing.space600)
                        )
                        Text(
                            text = stringResource(Res.string.loading),
                            textAlign = TextAlign.Center
                        )
                        Spacer(
                            modifier = Modifier.height(MaterialTheme.spacing.space600)
                        )

                        LoadingIndicator()
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "InReplyToViewModel"

        fun factory(
            nostrEvent: com.machankura.dedun.compose.database.model.NostrEvent,
            profile: com.machankura.dedun.compose.database.model.Profile?,
            createdAt: Instant,
            initialFeedListUIState: com.machankura.dedun.compose.ui.view.state.FeedListUIState = com.machankura.dedun.compose.ui.view.state.FeedListUIState.Loading,
            synchronizationFilter: com.machankura.dedun.compose.database.model.types.SynchronizationFilter = com.machankura.dedun.compose.database.model.types.SynchronizationFilter(
                kinds = arrayOf(
                    TextNoteEvent.KIND,
                    ReactionEvent.KIND,
                    RepostEvent.KIND,
                ),
                tags = mapOf(
                    "e" to listOf(
                        nostrEvent.id
                    )
                ),
                since = createdAt
            ),
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InReplyToViewModel(
                    initialFeedListUIState = initialFeedListUIState,
                    nostrEvent = nostrEvent,
                    profile = profile,
                    synchronizationFilter = synchronizationFilter,
                    nostrRepository = nostrRepository
                )
            }
        }

    }
}