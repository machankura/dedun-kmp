/*
 * Copyright 2025 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.machankura.dedun.compose.ui.composable.widgets.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.machankura.dedun.compose.ui.composable.widgets.buttons.Clickable
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import fr.acinq.phoenix.utils.preferences.getByWalletIdOrDefault
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.read_only
import bennu.composeapp.generated.resources.wallet
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.identity.StoredIdentity
import com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar
import com.machankura.dedun.compose.ui.theme.spacing

/**
 * The device's profiles as a list: the open one above a divider, the others below,
 * each with its nostr name and picture where the device holds a kind 0 for it and its
 * npub where it does not.
 *
 * @param profiles the nostr profile of each listed key, by public key; a key with no
 *   entry, or a null one, is shown by its npub over the metadata's emoji.
 */
@Composable
fun WalletsSelector(
    modifier: Modifier = Modifier,
    wallets: Map<WalletId, StoredIdentity>,
    walletsMetadata: Map<WalletId, UserWalletMetadata>,
    activeWalletId: WalletId?,
    canEdit: Boolean,
    onWalletClick: (StoredIdentity) -> Unit,
    profiles: Map<HexKey, Profile?> = emptyMap(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    topContent: @Composable (() -> Unit)? = null,
    bottomContent: @Composable (() -> Unit)? = null,
) {

    val currentWallet = remember(wallets) { wallets.entries.firstOrNull { it.key == activeWalletId }?.value }
    val otherWalletsList = remember(wallets, walletsMetadata) { wallets.entries.filterNot { it.key == activeWalletId || walletsMetadata[it.key]?.isHidden == true }.toList() }

    LazyColumn(modifier = modifier, verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment) {
        topContent?.let {
            item { it.invoke() }
        }
        if (currentWallet != null) {
            item {
                AvailableWalletView(
                    identity = currentWallet,
                    profile = profiles[currentWallet.nostrPublicKey],
                    metadata = walletsMetadata.getByWalletIdOrDefault(currentWallet.id),
                    isCurrent = true,
                    canEdit = canEdit,
                    onClick = { onWalletClick(currentWallet) }
                )
                Spacer(Modifier.height(MaterialTheme.spacing.space150))
                HorizontalDivider()
                Spacer(Modifier.height(MaterialTheme.spacing.space100))
            }
        }
        items(items = otherWalletsList) { (walletId, identity) ->
            AvailableWalletView(
                identity = identity,
                profile = profiles[identity.nostrPublicKey],
                metadata = walletsMetadata.getByWalletIdOrDefault(walletId),
                isCurrent = false,
                canEdit = canEdit,
                onClick = { onWalletClick(identity) }
            )
            Spacer(Modifier.height(MaterialTheme.spacing.space100))
        }
        bottomContent?.let {
            item { it.invoke() }
        }
    }
}

/**
 * One profile. The name and picture are the nostr profile's -- the widgets the profile
 * tab draws itself with -- and the npub is the second line, the identifier the rest of
 * the app shows and the one a user might actually recognise. A key the device has no
 * kind 0 for yet, one just created or a read-only one never found, is the npub over the
 * metadata's emoji. It used to be "Default name" over a random emoji for every row,
 * because nothing in this app writes the wallet metadata's name.
 *
 * What the device holds for the profile is said before the tap -- *wallet* for a seed
 * attached, *read only* for a bare public key, nothing for the plain case of a bare
 * key -- because it is the difference between a profile that can be signed out of and
 * one whose sign-out is a wallet question, and between two profiles with the same name.
 */
@Composable
private fun AvailableWalletView(
    modifier: Modifier = Modifier,
    identity: StoredIdentity,
    profile: Profile?,
    metadata: UserWalletMetadata,
    isCurrent: Boolean,
    canEdit: Boolean,
    onClick: (WalletId) -> Unit,
) {
    var showWalletEditDialog by remember { mutableStateOf(false) }
    if (showWalletEditDialog) {
        com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator(
            text = "Edit function coming soon"
        )
//        EditWalletDialog(
//            onDismiss = { showWalletEditDialog = false },
//            walletId = identity.id,
//            globalPrefs = globalPrefs,
//            metadata = metadata,
//        )
    }

    Clickable(
        modifier = modifier,
        onClick = {
            if (isCurrent) {
                if (canEdit) showWalletEditDialog = true else return@Clickable
            } else {
                onClick(identity.id)
            }
        },
        shape = RoundedCornerShape(16.dp),
        backgroundColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.spacing.space150, vertical = MaterialTheme.spacing.space150)
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (profile != null) {
                    ProfileAvatar(size = AvatarSize, publicKey = identity.nostrPublicKey, profile = profile)
                } else {
                    WalletAvatar(avatar = metadata.avatar, backgroundColor = Color.Transparent, internalPadding = PaddingValues(MaterialTheme.spacing.space50))
                }
                Spacer(Modifier.width(MaterialTheme.spacing.space150))
                Column {
                    val npub = identity.nostrPublicKey.hexToNpubHrp()
                    Text(
                        text = profile?.humanReadableNameOrPubkey() ?: npub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (profile != null) {
                        Spacer(Modifier.height(MaterialTheme.spacing.space25))
                        Text(text = npub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    }
                    val held = when (identity) {
                        is StoredIdentity.Mnemonic -> Res.string.wallet
                        is StoredIdentity.NostrPublic -> Res.string.read_only
                        is StoredIdentity.NostrSecret -> null
                    }
                    if (held != null) {
                        Spacer(Modifier.height(MaterialTheme.spacing.space25))
                        Text(
                            text = stringResource(held),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (isCurrent && canEdit) {
                Spacer(Modifier.width(MaterialTheme.spacing.space100))
//              TODO:  PhoenixIcon(R.drawable.ic_edit, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(MaterialTheme.spacing.space200))
            }
        }
    }
}

/** A dimension, not a spacing: the picture is the same size the emoji avatar renders at. */
private val AvatarSize = 44.dp
