package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.ui.composable.widgets.feed.ListView
import com.machankura.dedun.compose.ui.view.model.SearchResultType
import com.machankura.dedun.compose.ui.view.model.SearchResultViewModel
import com.machankura.dedun.compose.ui.view.state.SearchResultListUIState
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.profiles
import dedun.composeapp.generated.resources.search
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.searching_for_on
import dedun.composeapp.generated.resources.nothing_matched_that_search
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchResultScreen(
    activeUserPublicKey: String,
    searchQuery: String,
    nostrRepository: com.machankura.dedun.compose.repository.NostrRepository,
    searchRepository: com.machankura.dedun.compose.repository.SearchRepository,
    onNavigateBack: () -> Unit,
    onNavigateToEvent: (com.machankura.dedun.compose.ui.composable.navigation.routes.Route) -> Unit
) {
    val scope = rememberCoroutineScope()

    val searchViewModel: SearchResultViewModel = viewModel(
        factory = SearchResultViewModel.factory(
            initialSearchResultType = SearchResultType.Profiles,
            searchQuery = searchQuery,
            nostrRepository = nostrRepository,
            searchRepository = searchRepository
        ),
    )

    val textFieldState = rememberTextFieldState(searchQuery)
    // Controls expansion state of the search bar
    var expanded by rememberSaveable { mutableStateOf(true) }


    Scaffold(snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { isTraversalGroup = true }
            ) {

                SearchBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .semantics { traversalIndex = 0f },
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = textFieldState.text.toString(),
                            onQueryChange = { textFieldState.edit { replace(0, length, it) } },
                            onSearch = {
                                // Since search bar is disabled we don't need to do anything...
                            },
                            enabled = false,
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            placeholder = { Text(stringResource(Res.string.search)) },
                            leadingIcon = {
                                if (expanded) {
                                    IconButton(
                                        enabled = true,
                                        colors = IconButtonDefaults.iconButtonColors(
                                            contentColor = MaterialTheme.colorScheme.primary
                                        ),
                                        onClick = {
                                            // TODO: onNavigateBack
                                            onNavigateBack.invoke()
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowBackIosNew,
                                            contentDescription = "Back"
                                        )
                                    }

                                } else {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search"
                                    )
                                }
                            },
                            trailingIcon = {
                                IconButton(
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = MaterialTheme.colorScheme.primary
                                    ),
                                    onClick = {
                                        // TODO: Adjust search
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = "Adjust search"
                                    )
                                }
                            }
                        )
                    },
                    expanded = expanded,
                    onExpandedChange = {
                        onNavigateBack.invoke()
                    },
                    windowInsets = TopAppBarDefaults.windowInsets
                ) {
                    val pagerState = rememberPagerState { SearchResultType.entries.size }

                    PrimaryScrollableTabRow(
                        modifier = Modifier,
                        edgePadding = 10.dp,
                        selectedTabIndex = searchViewModel.searchResultType.ordinal,
                    ) {
                        SearchResultType.entries.forEach { searchResultType ->
                            Tab(
                                selected = searchViewModel.searchResultType == searchResultType,
                                onClick = {
                                    searchViewModel.updateSearchResultType(searchResultType)
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            searchResultType.ordinal
                                        )
                                    }
                                },
                                text = {
                                    Text(
                                        text = searchResultType.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }




                    HorizontalPager(
                        state = pagerState
                    ) { pageIndex ->

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when(SearchResultType.entries[pageIndex]) {
                                SearchResultType.Profiles -> {
                                    Text(stringResource(Res.string.profiles))
                                }
                            }

                            when (val searchResultListUIState = searchViewModel.searchResultListUIState) {
                                SearchResultListUIState.Error -> {
                                    ErrorState()
                                }
                                is SearchResultListUIState.Loaded -> {
                                    // TODO: Handle gallery UI
                                    if (searchResultListUIState.localNostrEvents.isEmpty()) {
                                        EmptyState(message = stringResource(Res.string.nothing_matched_that_search))
                                    } else {

                                        LazyColumn(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                                        ) {
                                            items(
                                                items = searchResultListUIState.localNostrEvents,
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
                                SearchResultListUIState.Loading -> {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    )  {
                                        Spacer(
                                            modifier = Modifier.height(MaterialTheme.spacing.space600)
                                        )
                                        Text(
                                            text = stringResource(Res.string.searching_for_on, searchViewModel.searchResultType.name.lowercase(), searchQuery.lowercase()),
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
                }
            }
        }
    }

    LaunchedEffect(true) {
        searchViewModel.initiateSearchResults()
    }
}

@ConformancePreviews
@Composable
private fun SearchResultScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            SearchResultScreen(
                activeUserPublicKey = "",
                searchQuery = "What happened...",
                nostrRepository = com.machankura.dedun.compose.repository.NostrRepository.NO_OP_NOSTR_REPOSITORY,
                searchRepository = com.machankura.dedun.compose.repository.SearchRepository.NO_OP_SEARCH_REPOSITORY,
                onNavigateBack = {},
                onNavigateToEvent = {}
            )
        }
    }
}