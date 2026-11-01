package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Unpublished
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.machankura.dedun.compose.database.model.MarmotKeyPackageBundle
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.repository.MarmotRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.view.model.KeyPackageManagementViewModel
import com.machankura.dedun.compose.identity.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.machankura.dedun.compose.ui.view.state.KeyPackageManagementUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.key_package_management
import dedun.composeapp.generated.resources.publish_new_key_package
import dedun.composeapp.generated.resources.something_went_wrong
import dedun.composeapp.generated.resources.we_couldn_t_find_the_local_profile_please
import dedun.composeapp.generated.resources.key_packages
import com.machankura.dedun.compose.ui.composable.widgets.ErrorState
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import dedun.composeapp.generated.resources.key_package_published
import dedun.composeapp.generated.resources.key_package_rotated
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyPackageManagementScreen(
    initialActiveProfileUIState: KeyPackageManagementUIState = KeyPackageManagementUIState.Loading,
    activeUserPublicKey: HexKey,
    nostrEventId: HexKey,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    marmotRepository: MarmotRepository,
    activeIdentityStateFlow: StateFlow<Identity?>,
) {

    // Read outside the click handlers: stringResource and rememberNotifier are both
    // composable, and a lambda passed to onClick is not.
    val notify = rememberNotifier(rememberCoroutineScope())
    val publishedMessage = stringResource(Res.string.key_package_published)
    val rotatedMessage = stringResource(Res.string.key_package_rotated)

    val keyPackageManagementViewModel: KeyPackageManagementViewModel = viewModel(
        factory = KeyPackageManagementViewModel.factory(
            nostrEventId = nostrEventId,
            initialActiveProfileUIState = initialActiveProfileUIState,
            marmotRepository = marmotRepository,
            activeUserPublicKey = activeUserPublicKey,
            activeIdentityStateFlow = activeIdentityStateFlow,
        ),
    )

    ScreenStateTransition(keyPackageManagementViewModel.keyPackageManagementUIState) { uiState ->
    when (val keyPackageManagementUIState = uiState) {
            KeyPackageManagementUIState.Error -> {
                ErrorState()
            }
            is KeyPackageManagementUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(Res.string.key_package_management)
                                )
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding)
                        .readableContent()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            item {
                                Button(
                                    onClick = {
                                        keyPackageManagementViewModel.publishNewKeyPackage {
                                            notify(publishedMessage)
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.LockReset,
                                        contentDescription = "Publish new key package"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        text = stringResource(Res.string.publish_new_key_package)
                                    )
                                }
                            }
                            item {
                                Text(
                                    stringResource(Res.string.key_packages, keyPackageManagementUIState.keyPackageBundles.size)
                                )
                            }

                            items(
                                items = keyPackageManagementUIState.keyPackageBundles
                            ) { marmotKeyPackageBundle ->

                                Card {
                                    ListItem(
                                        leadingContent = {
                                            if (marmotKeyPackageBundle.rotated) {
                                                Icon(
                                                    Icons.Default.Unpublished,
                                                    contentDescription = "Key package rotated"
                                                )
                                            } else if (marmotKeyPackageBundle.consumed) {
                                                Icon(
                                                    Icons.Default.DoneAll,
                                                    contentDescription = "Consumed key package event"
                                                )
                                            } else {
                                                // TODO: Get broadcast status of key package...
                                                Icon(
                                                    Icons.Default.VerifiedUser,
                                                    contentDescription = "Key package published"
                                                )
                                            }
                                        },
                                        headlineContent = {
                                            Text(
                                                text = marmotKeyPackageBundle.id,
                                                maxLines = 1,
                                                overflow = TextOverflow.MiddleEllipsis
                                            )
                                        },
                                        supportingContent = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = marmotKeyPackageBundle.createdAt.toFormattedTimeAndDateString(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )

                                                Spacer(
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }

                                        },
                                        trailingContent = {
                                            if (marmotKeyPackageBundle.rotated.not() && marmotKeyPackageBundle.consumed.not()) {
                                                IconButton(
                                                    onClick = {
                                                        keyPackageManagementViewModel.rotateKeyPackage(
                                                            marmotKeyPackageBundle
                                                        ) {
                                                            notify(rotatedMessage)
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.LockReset,
                                                        contentDescription = "Rotate key"
                                                    )
                                                }
                                            }

                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            KeyPackageManagementUIState.Loading -> {
                LoadingDataIndicator()
            }
            KeyPackageManagementUIState.NotFound -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(Res.string.we_couldn_t_find_the_local_profile_please))
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun UnannouncedProfileScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            KeyPackageManagementScreen(
                initialActiveProfileUIState = KeyPackageManagementUIState.Loaded(
                    keyPackageBundles = listOf(
                        MarmotKeyPackageBundle(
                            id = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                            publicKey = "",
                            tlsEncodedMarmotKeyPackage = ByteArray(0),
                            ncryptsecInitPrivateKey = "",
                            ncryptsecEncryptionPrivateKey = "",
                            ncryptsecSignaturePrivateKey = "",
                            consumed = true
                        )
                    )
                ),
                activeUserPublicKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                nostrEventId = "",
                marmotRepository = MarmotRepository.NO_OP_MARMOT_KEY_PACKAGE_BUNDLE,
                onNavigateToRoute = {},
                onNavigateBack = {},
                activeIdentityStateFlow = MutableStateFlow(null),
            )
        }
    }
}