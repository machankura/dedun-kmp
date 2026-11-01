package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.we_are_looking_for_your_profile_on_as_many
import dedun.composeapp.generated.resources.we_are_searching_the_internet_to_find_your
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UnqueuedProfileSynchronizationScreen(
    unsignedNostrEventId: Long,
    profilePublicKey: String,
    nostrRepository: com.machankura.dedun.compose.repository.NostrRepository
) {
    val unqueuedProfileSynchronizationViewModel: com.machankura.dedun.compose.ui.view.model.UnqueuedProfileSynchronizationViewModel = viewModel(
        factory = com.machankura.dedun.compose.ui.view.model.UnqueuedProfileSynchronizationViewModel.factory(
            unsignedNostrEventId = unsignedNostrEventId,
            profilePublicKey = profilePublicKey,
            nostrRepository = nostrRepository
        )
    )

    Scaffold(snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) }) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding)
            .readableContent()
        ) {

            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    MaterialTheme.spacing.space125
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
            ) {

                Spacer(
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(Res.string.we_are_searching_the_internet_to_find_your),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(Res.string.we_are_looking_for_your_profile_on_as_many),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )

                com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator(
                    fillScreen = false
                )

                Spacer(
                    modifier = Modifier.weight(2f)
                )
            }
        }
    }

    LaunchedEffect(true) {
        unqueuedProfileSynchronizationViewModel.queueSynchronization()
    }
}

@ConformancePreviews
@Composable
private fun UnqueuedProfileSynchronizationScreenPreview() {
    com.machankura.dedun.compose.ui.theme.DedunTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            UnqueuedProfileSynchronizationScreen(
                unsignedNostrEventId = 2,
                profilePublicKey = "npub is for living.",
                nostrRepository = com.machankura.dedun.compose.repository.NostrRepository.NO_OP_NOSTR_REPOSITORY
            )
        }
    }
}