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
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.loading
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.not_following_anyone_yet
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState

class FollowingListViewModel(
    initialFollowingListUIState: com.machankura.dedun.compose.ui.view.state.FollowingListUIState,
    val publicKey: String,
    val synchronizationFilter: com.machankura.dedun.compose.database.model.types.SynchronizationFilter,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
): ViewModel() {
    val logger = Logger.withTag(TAG)
    var followingListUIState: com.machankura.dedun.compose.ui.view.state.FollowingListUIState by mutableStateOf(initialFollowingListUIState)
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
            nostrRepository.observeProfileWithFollowing(
                publicKey
            ).collect { localFollowing ->
                followingListUIState = com.machankura.dedun.compose.ui.view.state.FollowingListUIState.Loaded(
                    following = localFollowing?.following ?: emptyList()
                )
            }
        }
    }

    fun scheduleSynchronization() {
        logger.d("scheduleSynchronization")
        viewModelScope.launch(Dispatchers.IO) {
            // Sync Notifications... might want to also run this in the background
            nostrRepository.queueNegentropySynchronizeRequest(
                com.machankura.dedun.compose.nostr.Relays.DefaultDMRelayList.shuffled().map { normalizedRelayUrl ->
                    com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest(
                        id = com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest.computeId(
                            relayURL = normalizedRelayUrl.url,
                            synchronizationFilter = synchronizationFilter
                        ),
                        purpose = "following",
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
            when (val feedListUIState = followingListUIState) {
                com.machankura.dedun.compose.ui.view.state.FollowingListUIState.Error -> {
                    ErrorState()
                }
                is com.machankura.dedun.compose.ui.view.state.FollowingListUIState.Loaded -> {
                    if (feedListUIState.following.isEmpty()) {
                        EmptyState(message = stringResource(Res.string.not_following_anyone_yet))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                        ) {
                            items(
                                items = feedListUIState.following,
                                key = { profile -> profile.publicKey }
                            ) { profile ->
                                profile.RenderAsListItem(
                                    activeUserPublicKey = activeUserPublicKey,
                                    onNavigateToEvent = onNavigateToEvent
                                )
                            }
                        }
                    }

                }
                com.machankura.dedun.compose.ui.view.state.FollowingListUIState.Loading -> {
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
        const val TAG = "FollowingListViewModel"

        fun factory(
            initialFeedListUIState: com.machankura.dedun.compose.ui.view.state.FollowingListUIState = com.machankura.dedun.compose.ui.view.state.FollowingListUIState.Loading,
            publicKey: String,
            synchronizationFilter: com.machankura.dedun.compose.database.model.types.SynchronizationFilter,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FollowingListViewModel(
                    initialFollowingListUIState = initialFeedListUIState,
                    publicKey = publicKey,
                    synchronizationFilter = synchronizationFilter,
                    nostrRepository = nostrRepository
                )
            }
        }
    }
}