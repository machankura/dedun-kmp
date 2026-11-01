package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.extensions.bech32ToHexOrNull
import com.machankura.dedun.compose.ui.view.state.SearchResultListUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class SearchResultType {
    Profiles
}

class SearchResultViewModel(
    initialSearchResultType: SearchResultType,
    val searchQuery: String,
    val nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
    val searchRepository: com.machankura.dedun.compose.repository.SearchRepository,
): ViewModel() {
    val logger = Logger.withTag(TAG)

    var searchResultType: SearchResultType by mutableStateOf(initialSearchResultType)
        private set

    var searchResultListUIState: SearchResultListUIState by mutableStateOf(
        SearchResultListUIState.Loading)
        private set

    fun updateSearchResultType (searchResultType: SearchResultType) {
        searchResultListUIState = SearchResultListUIState.Loading

        this.searchResultType = searchResultType

        initiateSearchResults(searchResultType)
    }

    fun computeSynchronizationFilter(): SynchronizationFilter {
        if (searchQuery.startsWith("npub")) {
            searchQuery.bech32ToHexOrNull()?.let { publicKey ->
                logger.d("Looking for pubkey: $publicKey")
                return SynchronizationFilter(
                    kinds = arrayOf(
                        MetadataEvent.KIND,
                    ),
                    authors = arrayOf(
                        publicKey
                    ),
                    limit = 50
                )
            }
        }

        return SynchronizationFilter(
            kinds = arrayOf(
                MetadataEvent.KIND,
            ),
            search = searchQuery,
            limit = 50
        )
    }

    fun initiateSearchResults(searchResultType: SearchResultType = this.searchResultType) {
        logger.d("initiateSearchResults")
        val synchronizationFilter = when (searchResultType) {
            SearchResultType.Profiles -> {
                computeSynchronizationFilter()
            }
            // We might want to support other search result types...
        }

        saveSearchQuery(synchronizationFilter)
        scheduleSearch(synchronizationFilter)
        loadSearchResultFeed(synchronizationFilter)
    }

    private fun saveSearchQuery(synchronizationFilter: SynchronizationFilter) {
        logger.d("saveSearchQuery: $searchQuery")
        viewModelScope.launch(Dispatchers.IO) {
            nostrRepository.saveSearchQuery(
                searchQuery,
                arrayOf(
                    synchronizationFilter
                )
            )
        }
    }

    private fun scheduleSearch(synchronizationFilter: SynchronizationFilter) {
        logger.d("scheduleSearch: $searchQuery")
        viewModelScope.launch(Dispatchers.IO) {
            // Sync Notifications... might want to also run this in the background
            searchRepository.submitSearch(
                com.machankura.dedun.compose.nostr.Relays.eventFinderRelaySet.map { normalizedRelay -> // TODO: Search all relays instead of the first
                    com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest(
                        purpose = "search",
                        synchronizationFilters = arrayOf(
                            synchronizationFilter
                        ),
                        relayURL = normalizedRelay.url,
                        level = 0
                    )
                }
            )
        }
    }

    private fun loadSearchResultFeed(synchronizationFilter: SynchronizationFilter) {
        logger.d("loadSearchResultFeed: $searchQuery")
        viewModelScope.launch(Dispatchers.IO) {
            // This is something not taking into consideration the results we are actually looking for...
            nostrRepository.observeNostrFeed(synchronizationFilter).distinctUntilChanged().collect { localNostrEvents ->
                searchResultListUIState = SearchResultListUIState.Loaded(
                    localNostrEvents = localNostrEvents
                )
            }
        }
    }

    companion object {
        const val TAG = "SearchViewModel"

        fun factory(
            initialSearchResultType: SearchResultType = SearchResultType.Profiles,
            searchQuery: String,
            nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
            searchRepository: com.machankura.dedun.compose.repository.SearchRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SearchResultViewModel(
                    initialSearchResultType = initialSearchResultType,
                    searchQuery = searchQuery,
                    nostrRepository = nostrRepository,
                    searchRepository = searchRepository
                )
            }
        }
    }
}