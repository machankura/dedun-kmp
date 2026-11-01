package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.invite_a_friend
import bennu.composeapp.generated.resources.skip_for_now
import bennu.composeapp.generated.resources.tell_friends_to_join_you_so_your_feed_stays
import bennu.composeapp.generated.resources.view_and_accept_invites_you_may_have
import bennu.composeapp.generated.resources.view_invites
import bennu.composeapp.generated.resources.who_will_you_be_passing_the_aux_to
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@Composable
fun SocialPreconditionScreen(
    onNavigateToSkipForNow: () -> Unit,
    onNavigateToInviteFriend: () -> Unit,
    onNavigateToViewInvites: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        bottomBar = {
            BottomAppBar(
                modifier = Modifier,
                actions = {

                },
                floatingActionButton = {
                    ExtendedFab(
                        label = stringResource(Res.string.skip_for_now),
                        icon = { Icon(Icons.Default.SkipNext, contentDescription = "Skip for now") },
                        onClick = {
                            onNavigateToSkipForNow.invoke()
                        }
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
            ) {

                Text(
                    stringResource(Res.string.who_will_you_be_passing_the_aux_to),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(
                    modifier = Modifier.weight(1f)
                )

                // Both pending today, and both writes when they exist: an invite is sent,
                // an acceptance signed. A read-only identity is offered only the way past.
                if (LocalCanSign.current) {
                    Text(
                        stringResource(Res.string.tell_friends_to_join_you_so_your_feed_stays),
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = {
                            onNavigateToInviteFriend.invoke()
                        }
                    ) {
                        Text(
                            stringResource(Res.string.invite_a_friend)
                        )
                    }

                    Spacer(
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        stringResource(Res.string.view_and_accept_invites_you_may_have),
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = {
                            onNavigateToViewInvites.invoke()
                        }
                    ) {
                        Text(stringResource(Res.string.view_invites))
                    }
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun SocialPreconditionScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            SocialPreconditionScreen(
                onNavigateToSkipForNow = {},
                onNavigateToInviteFriend = {},
                onNavigateToViewInvites = {}
            )
        }
    }
}