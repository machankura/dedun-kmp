package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.view.model.DkgRitualViewModel
import com.machankura.dedun.compose.ui.view.state.DkgRitualUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.flow.StateFlow
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.back
import bennu.composeapp.generated.resources.not_now
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent

/**
 * The frame every ChillDKG approval screen sits in: the view model, the states
 * where there is nothing to approve, and the approve/decline bar.
 *
 * The three screens are separate because each is asking for something different
 * and has its own case to make. What they share is only the chrome and, more
 * importantly, the guard below — which is a correctness concern rather than a
 * cosmetic one and should not be re-argued three times.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DkgApprovalScaffold(
    step: DkgApprovalStep,
    title: String,
    approveLabel: String,
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    dkgSessionId: String,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    dkgRepository: DkgRepository,
    onDone: () -> Unit,
    body: @Composable (DkgRitualUIState.Loaded) -> Unit,
) {
    val dkgRitualViewModel: DkgRitualViewModel = viewModel(
        factory = DkgRitualViewModel.factory(
            chatRoomId = chatRoomId,
            dkgSessionId = dkgSessionId,
            activeUserPublicKey = activeUserPublicKey,
            activeIdentityStateFlow = activeIdentityStateFlow,
            chatRepository = chatRepository,
            dkgRepository = dkgRepository
        )
    )

    // Nothing loads the room or starts watching the session until this runs, so
    // without it the screen sits on its initial Loading state forever.
    LaunchedEffect(true) {
        dkgRitualViewModel.initiate()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                // The same leave as "Not now" below, reachable from the loading and error
                // states too -- which have no other way off the screen on desktop.
                navigationIcon = { NavigateBackButton(onDone) },
            )
        }
    ) { padding ->
        when (val state = dkgRitualViewModel.dkgRitualUIState) {
            is DkgRitualUIState.Loading -> Column(
                modifier = Modifier.fillMaxWidth().padding(padding).readableContent().padding(MaterialTheme.spacing.space250),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                CircularProgressIndicator()
            }

            is DkgRitualUIState.Error -> Column(
                modifier = Modifier.fillMaxWidth().padding(padding).readableContent().padding(MaterialTheme.spacing.space250),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                Text(text = state.message, textAlign = TextAlign.Center)
            }

            is DkgRitualUIState.Loaded -> {
                // The ritual may have moved on since this screen was opened -- a
                // redelivery can carry it forward, and the member may have approved
                // on another device. Approving anyway would be approving a step that
                // is no longer the question, so the screen says so instead. The
                // manager checks this too; this is the half the member can see.
                if (state.pendingApproval != step) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(padding)
                            .readableContent()
                            .padding(MaterialTheme.spacing.space250),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space150)
                    ) {
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.space500))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = Decorative,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "There is nothing to approve here right now. The ceremony has " +
                                "either moved on or is waiting on somebody else.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = onDone) { Text(text = stringResource(Res.string.back)) }
                    }

                    return@Scaffold
                }

                val isActionPending = dkgRitualViewModel.isActionPending.value

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(padding)
                        .readableContent()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = MaterialTheme.spacing.space250, vertical = MaterialTheme.spacing.containerPadding),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200)
                ) {
                    body(state)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space150),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Declining is deliberately not a button that refuses on the
                        // member's behalf: there is no "no" in ChillDKG short of
                        // abandoning the ceremony, and quietly leaving is what a
                        // member who is not ready actually wants. Abandoning is on
                        // the ritual screen, where the consequence can be spelled out.
                        TextButton(
                            onClick = onDone,
                            enabled = !isActionPending
                        ) {
                            Text(text = stringResource(Res.string.not_now))
                        }

                        Button(
                            // Closes once the approval has gone out, not on the
                            // press: closing clears the view model and would cancel
                            // the publish with it. The button spins meanwhile.
                            onClick = { dkgRitualViewModel.approve(step, onApproved = onDone) },
                            enabled = !isActionPending,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isActionPending) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Text(text = approveLabel)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One "this is what happens" line on an approval screen. */
@Composable
internal fun DkgApprovalPoint(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
    ) {
        Text(text = "•", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
