package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.cloud_backup
import bennu.composeapp.generated.resources.download_and_securely_store_everything
import bennu.composeapp.generated.resources.emergency_kit
import bennu.composeapp.generated.resources.encrypt_and_back_your_recovery_information
import bennu.composeapp.generated.resources.key_recovery
import bennu.composeapp.generated.resources.not_backed_up_yet
import bennu.composeapp.generated.resources.nostr_secret_key
import bennu.composeapp.generated.resources.recovery_phrase
import bennu.composeapp.generated.resources.store_the_nsec_that_signs_as_this_profile
import bennu.composeapp.generated.resources.these_are_your_keys_keep_them_safe_no_wallet
import bennu.composeapp.generated.resources.you_said_you_stored_it
import bennu.composeapp.generated.resources.these_are_your_keys_keep_them_safe_so_they
import bennu.composeapp.generated.resources.write_down_and_secure_the_12_word_phrase
import bennu.composeapp.generated.resources.yolo
import bennu.composeapp.generated.resources.you_only_live_once_lose_this_phone_and_the
import bennu.composeapp.generated.resources.you_said_you_wrote_it_down
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.NostrSecretRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.RecoveryPhraseRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.ConformancePreviews
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing

/**
 * Everything that can put this profile back on another phone.
 *
 * A Bennu profile is its key: the npub that signs and, for a wallet, the coins
 * it holds, both come out of one secret that never leaves the device -- twelve
 * words, or a bare nostr key that was signed in with. There is no account to
 * reset, so this screen is the only thing standing between a lost phone and a
 * lost identity -- it exists to get the user to take a backup while they still
 * can.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyRecoveryScreen(
    activeUserPublicKey: HexKey,
    activeIdentityStateFlow: StateFlow<Identity?>,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit,
) {
    val activeIdentity by activeIdentityStateFlow.collectAsState()

    // The same preference the recovery phrase screen writes, so the state of the backup is
    // visible before the user goes looking for it.
    val showBackupNotice by remember(activeIdentity) {
        activeIdentity?.internalPrefs?.showSeedBackupNotice ?: flowOf(false)
    }.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.key_recovery)
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .readableContent()
                .padding(horizontal = MaterialTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.containerPadding),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        // A bare-key identity has no coins to speak of. Exhaustive rather than
                        // `else`: the `else` here used to mean "mnemonic", and a kind added later
                        // would have fallen into it and been promised coins. A read-only identity
                        // is never offered this screen (docs/npub-sign-in.md, Phase 5); it reads
                        // as the bare-key kind so that, if it ever is, the sentence is at least
                        // not about a wallet.
                        text = when (activeIdentity?.kind) {
                            IdentityKind.NostrSecret,
                            IdentityKind.NostrPublic -> stringResource(Res.string.these_are_your_keys_keep_them_safe_no_wallet)
                            IdentityKind.Mnemonic,
                            null -> stringResource(Res.string.these_are_your_keys_keep_them_safe_so_they)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = activeUserPublicKey.hexToNpubHrp(),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                // The backup an identity has depends on what it was made from. Twelve words
                // for a wallet; for a bare key there is no phrase, and the key is the whole
                // backup. Same flags underneath -- they are per identity and mean "this
                // identity's secret is not backed up" for whichever secret it is.
                when (activeIdentity?.kind) {
                    IdentityKind.NostrSecret -> KeyRecoveryOption(
                        icon = Icons.Default.Key,
                        title = stringResource(Res.string.nostr_secret_key),
                        description = stringResource(Res.string.store_the_nsec_that_signs_as_this_profile),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        status = if (showBackupNotice) {
                            KeyRecoveryStatus(
                                icon = Icons.Default.Warning,
                                text = stringResource(Res.string.not_backed_up_yet)
                            )
                        } else {
                            KeyRecoveryStatus(
                                icon = Icons.Default.CheckCircle,
                                text = stringResource(Res.string.you_said_you_stored_it)
                            )
                        },
                        onClick = {
                            onNavigateToRoute.invoke(NostrSecretRoute)
                        }
                    )

                    // Nothing to recover: the device holds no secret for this identity. The
                    // row that leads here is hidden for the kind, so this arm is not reached;
                    // it exists so that the `when` says so rather than handing a read-only
                    // identity the phrase option through an `else`.
                    IdentityKind.NostrPublic -> Unit

                    IdentityKind.Mnemonic,
                    null -> KeyRecoveryOption(
                        icon = Icons.Default.Spellcheck,
                        title = stringResource(Res.string.recovery_phrase),
                        description = stringResource(Res.string.write_down_and_secure_the_12_word_phrase),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        status = if (showBackupNotice) {
                            KeyRecoveryStatus(
                                icon = Icons.Default.Warning,
                                text = stringResource(Res.string.not_backed_up_yet)
                            )
                        } else {
                            KeyRecoveryStatus(
                                icon = Icons.Default.CheckCircle,
                                text = stringResource(Res.string.you_said_you_wrote_it_down)
                            )
                        },
                        onClick = {
                            onNavigateToRoute.invoke(RecoveryPhraseRoute)
                        }
                    )
                }
            }

            item {
                KeyRecoveryOption(
                    icon = Icons.Default.AddToDrive,
                    title = stringResource(Res.string.cloud_backup),
                    description = stringResource(Res.string.encrypt_and_back_your_recovery_information),
                    onClick = {
                        onNavigateToRoute.invoke(
                            ImplementationPendingRoute("Cloud backup")
                        )
                    }
                )
            }

            item {
                KeyRecoveryOption(
                    icon = Icons.Default.LocalHospital,
                    title = stringResource(Res.string.emergency_kit),
                    description = stringResource(Res.string.download_and_securely_store_everything),
                    onClick = {
                        onNavigateToRoute.invoke(
                            ImplementationPendingRoute("Emergency kit")
                        )
                    }
                )
            }

            item {
                KeyRecoveryOption(
                    icon = Icons.Default.VolunteerActivism,
                    title = stringResource(Res.string.yolo),
                    description = stringResource(Res.string.you_only_live_once_lose_this_phone_and_the),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = {
                        onNavigateBack.invoke()
                    }
                )
            }

            item {
                Spacer(
                    modifier = Modifier.height(MaterialTheme.spacing.itemGap)
                )
            }
        }
    }
}

/** A one-line verdict on an option, shown under it. */
private data class KeyRecoveryStatus(
    val icon: ImageVector,
    val text: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyRecoveryOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    status: KeyRecoveryStatus? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = Decorative
                )

                Spacer(
                    modifier = Modifier.width(MaterialTheme.spacing.space125)
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall
            )

            status?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        it.icon,
                        contentDescription = Decorative,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(
                        modifier = Modifier.width(MaterialTheme.spacing.relatedGap)
                    )

                    Text(
                        text = it.text,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun KeyRecoveryScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            KeyRecoveryScreen(
                activeUserPublicKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                activeIdentityStateFlow = MutableStateFlow(null),
                onNavigateBack = {},
                onNavigateToRoute = {}
            )
        }
    }
}
