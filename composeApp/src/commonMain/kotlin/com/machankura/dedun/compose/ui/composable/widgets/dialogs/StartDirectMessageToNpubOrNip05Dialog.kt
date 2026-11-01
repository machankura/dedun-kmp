package com.machankura.dedun.compose.ui.composable.widgets.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.machankura.dedun.compose.identity.CredentialParser
import com.machankura.dedun.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.utils.Parser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.ui.theme.spacing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.find_profile
import bennu.composeapp.generated.resources.input_npub_or_nip05
import bennu.composeapp.generated.resources.start_chat_via_npub_or_nip05
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

/**
 * Whom to write to, as an npub or a nip05.
 *
 * An npub no longer starts a chat from here: it opens the person's profile, and the
 * chat is started from there once the user has seen who it is. The field keeps what
 * it parsed rather than whether it parsed -- the enabled button and the route it
 * pushes read the same decoded key, which is what closed the gap where `nostr:npub1…`
 * passed the check and then failed the decode in silence. A nip05 still lands on the
 * placeholder; resolving one is out of scope. See docs/npub-profile-preview.md.
 */
@Composable
fun StartDirectMessageToNpubOrNip05Dialog(
    activeUserPublicKey: HexKey,
    scope: CoroutineScope,
    toggleOpenDialogSetting: (Boolean) -> Unit,
    onNavigateToRoute: (Route) -> Unit
) {

    Dialog(
        onDismissRequest = {

        }
    ) {
        // The key under a pasted npub, or null while the field holds anything else.
        val parsedPublicKey = remember { mutableStateOf<HexKey?>(null) }
        // A nip05 is recognised by shape alone; nothing resolves it yet.
        val emailLikeAddressEntered = remember { mutableStateOf(false) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(MaterialTheme.spacing.containerPadding),
            shape = RoundedCornerShape(16.dp),
        ) {
            val textFieldState = rememberTextFieldState()

            // M3's flow guidance for a dialog: "Focus is set to the dialog component,
            // likely to a specific interactive element within the dialog such as a text
            // input field." This dialog is one field and two buttons, so the field is
            // unambiguous -- and without this the whole dialog opens with nothing
            // focused, which for a keyboard or switch user means tabbing in from
            // wherever focus happened to be.
            val npubFieldFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { npubFieldFocus.requestFocus() }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
            ) {
                Spacer(
                    modifier = Modifier.height(MaterialTheme.spacing.space50)
                )
                Text(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = MaterialTheme.spacing.space250,
                    ),
                    text = stringResource(Res.string.start_chat_via_npub_or_nip05),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
                TextField(
                    modifier = Modifier.focusRequester(npubFieldFocus),
                    state = textFieldState,
                    placeholder = {
                        Text(stringResource(Res.string.input_npub_or_nip05))
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {

                                    toggleOpenDialogSetting.invoke(false)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close"
                            )
                        }
                    },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = {
                        val input = asCharSequence().toString()

                        parsedPublicKey.value = CredentialParser.npubOrNull(input)
                        emailLikeAddressEntered.value = parsedPublicKey.value == null &&
                            Parser.parseEmailLikeAddress(input.lowercase()) != null
                    }
                )

                TextButton(
                    enabled = parsedPublicKey.value != null || emailLikeAddressEntered.value,
                    onClick = {
                        // Read before the dialog closes: the state lives in it.
                        val publicKey = parsedPublicKey.value
                        val input = textFieldState.text.toString()

                        scope.launch {
                            toggleOpenDialogSetting.invoke(false)
                        }.invokeOnCompletion {
                            if (publicKey != null) {
                                onNavigateToRoute.invoke(
                                    ProfilePreviewRoute(
                                        activeUserPublicKey = activeUserPublicKey,
                                        profilePublicKey = publicKey,
                                    )
                                )
                            } else {
                                // TODO: Resolve the nip05 to a public key, then the same route.
                                onNavigateToRoute.invoke(
                                    ImplementationPendingRoute(
                                        "Direct Message to $input"
                                    )
                                )
                            }
                        }
                    }
                ) {
                    Text(stringResource(Res.string.find_profile))

                    Spacer(
                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                    )

                    Icon(
                        Icons.Default.PersonSearch,
                        // The label is beside it.
                        contentDescription = Decorative
                    )
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun StartDirectMessageToNpubOrNip05DialogPreview() {
    BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            StartDirectMessageToNpubOrNip05Dialog(
                activeUserPublicKey = "hex",
                scope = rememberCoroutineScope(),
                toggleOpenDialogSetting = {},
                onNavigateToRoute = {}
            )
        }
    }
}