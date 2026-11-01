package com.machankura.dedun.compose.ui.composable.widgets.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Diversity2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.close
import bennu.composeapp.generated.resources.create_new_chat
import bennu.composeapp.generated.resources.direct_message_via_npub
import bennu.composeapp.generated.resources.start_a_group_chat


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatBottomSheetDialog(
    scope: CoroutineScope,
    sheetState: SheetState,
    onSetShowBottomSheetUpdate: (Boolean) -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateToChatRoomCreation: () -> Unit,
    onStartDirectMessageChatWithNpub: () -> Unit
) {

    ModalBottomSheet(
        onDismissRequest = {
            onSetShowBottomSheetUpdate.invoke(false)
        },
        sheetState = sheetState
    ) {
        // Sheet content
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space125),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(Res.string.create_new_chat),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Card(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onStartDirectMessageChatWithNpub.invoke()
                    }
                }
            ) {
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = "Direct message via npub"
                        )
                    },
                    headlineContent = {
                        Text(stringResource(Res.string.direct_message_via_npub))
                    },
                    trailingContent = {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "Direct message via npub"
                        )
                    }
                )
            }

            Card(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onNavigateToChatRoomCreation.invoke()
                    }
                }
            ) {
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Default.Diversity2,
                            contentDescription = "Start group chat"
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(Res.string.start_a_group_chat)
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "Start group chat"
                        )
                    }
                )
            }

            TextButton(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            onSetShowBottomSheetUpdate.invoke(false)
                        }
                    }
                }
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close new chat"
                )
                Spacer(
                    modifier = Modifier.width(MaterialTheme.spacing.space125)
                )
                Text(stringResource(Res.string.close))
            }
        }

    }
}