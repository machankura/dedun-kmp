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
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.ui.composable.widgets.feed.ListView
import com.machankura.dedun.compose.ui.view.state.FeedListUIState
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.loading
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.nothing_in_this_feed_yet
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState

class FeedListViewModel(
    initialFeedListUIState: FeedListUIState,
    val synchronizationFilter: com.machankura.dedun.compose.database.model.types.SynchronizationFilter,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
): ViewModel() {
    val logger = Logger.withTag(TAG)
    var feedListUIState: FeedListUIState by mutableStateOf(initialFeedListUIState)
        private set

    val isSynchronizationPending: MutableState<Boolean> = mutableStateOf(false)

    val negentropySynchronizeRequests = com.machankura.dedun.compose.nostr.Relays.DefaultDMRelayList.shuffled().map { normalizedRelayUrl ->
        NegentropySynchronizeRequest(
            id = NegentropySynchronizeRequest.computeId(
                normalizedRelayUrl.url,
                synchronizationFilter = synchronizationFilter
            ),
            purpose = "feed",
            synchronizationFilter = synchronizationFilter,
            relayURL = normalizedRelayUrl.url,
            level = 0
        )
    }


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
            ).distinctUntilChanged().first().let { localNostrEvents ->
                feedListUIState = FeedListUIState.Loaded(
                    localNostrEvents = localNostrEvents
                )
            }
        }
    }

    fun scheduleSynchronization() {
        logger.d("scheduleSynchronization")
        viewModelScope.launch(Dispatchers.IO) {
            // Sync Notifications... might want to also run this in the background
            nostrRepository.queueNegentropySynchronizeRequest(
                negentropySynchronizeRequests
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
            when (val feedListUIState = feedListUIState) {
                FeedListUIState.Error -> {
                    ErrorState()
                }
                is FeedListUIState.Loaded -> {
                    if (feedListUIState.localNostrEvents.isEmpty()) {
                        EmptyState(message = stringResource(Res.string.nothing_in_this_feed_yet))
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
                FeedListUIState.Loading -> {
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
        const val TAG = "FeedListViewModel"

        fun factory(
            initialFeedListUIState: FeedListUIState = FeedListUIState.Loading,
            synchronizationFilter: com.machankura.dedun.compose.database.model.types.SynchronizationFilter,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FeedListViewModel(
                    initialFeedListUIState = initialFeedListUIState,
                    synchronizationFilter = synchronizationFilter,
                    nostrRepository = nostrRepository
                )
            }
        }

    }
}