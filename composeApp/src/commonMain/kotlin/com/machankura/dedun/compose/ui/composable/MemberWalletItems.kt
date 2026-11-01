package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.a_group
import dedun.composeapp.generated.resources.balance_not_known_yet
import dedun.composeapp.generated.resources.guarded_wallets
import dedun.composeapp.generated.resources.key_on_another_device
import dedun.composeapp.generated.resources.new_guarded_wallet
import dedun.composeapp.generated.resources.s_did_not_sign
import dedun.composeapp.generated.resources.s_dot_s
import dedun.composeapp.generated.resources.s_is_signing
import dedun.composeapp.generated.resources.s_sats
import dedun.composeapp.generated.resources.waiting_for_someone_in_s_to_put_it_to_the_group
import dedun.composeapp.generated.resources.you_have_no_guarded_wallets
import dedun.composeapp.generated.resources.you_re_no_longer_in_s
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.MemberWalletsViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * A member's guarded wallets, on their own profile: one row per wallet, named
 * for the group that guards it, and the way to ask for another.
 * `docs/member-guarded-wallets.md`, Phase 8.
 *
 * Never published. A profile's kind 0 is public, and a member wallet's xpub
 * never leaves an encryption.
 */
fun LazyListScope.memberWalletItems(
    rows: List<MemberWalletsViewModel.Row>?,
    onOpen: (walletId: String) -> Unit,
    onNew: (() -> Unit)?,
) {
    item {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(Res.string.guarded_wallets),
            style = MaterialTheme.typography.titleMedium,
        )
    }

    when {
        rows == null -> Unit
        rows.isEmpty() -> item { EmptyState(message = stringResource(Res.string.you_have_no_guarded_wallets), icon = Icons.Default.AccountBalanceWallet) }
        else -> items(rows, key = { "member-wallet-" + it.wallet.walletId }) { row ->
            ListItem(
                modifier = Modifier.clickable { onOpen(row.wallet.walletId) }.minimumInteractiveComponentSize(),
                leadingContent = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative) },
                headlineContent = {
                    val group = groupNameOf(row.wallet)
                    Text(
                        text = row.wallet.label?.let { stringResource(Res.string.s_dot_s, group, it) } ?: group,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = { Text(supportingLine(row)) },
            )
        }
    }

    onNew?.let { new ->
        item {
            TextButton(onClick = new) {
                Icon(Icons.Default.Add, contentDescription = Decorative)
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                Text(stringResource(Res.string.new_guarded_wallet))
            }
        }
    }
}

/** The guarding group as the member's rows name it: the room's name here, or a generic one. */
@Composable
internal fun groupNameOf(wallet: MemberWalletManager.MemberWalletRow): String =
    wallet.groupRoom?.chatRoom?.subject?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.a_group)

@Composable
private fun supportingLine(row: MemberWalletsViewModel.Row): String {
    val group = groupNameOf(row.wallet)
    if (!row.wallet.isMember) return stringResource(Res.string.you_re_no_longer_in_s, group)
    val balance = row.walletState?.let { stringResource(Res.string.s_sats, it.confirmedBalance.grouped()) }
        ?: stringResource(Res.string.balance_not_known_yet)
    return when (row.wallet.state) {
        MemberWalletManager.WalletState.ASKED -> stringResource(Res.string.waiting_for_someone_in_s_to_put_it_to_the_group, group)
        MemberWalletManager.WalletState.SIGNING -> stringResource(Res.string.s_is_signing, group)
        MemberWalletManager.WalletState.NOT_SIGNED -> stringResource(Res.string.s_did_not_sign, group)
        MemberWalletManager.WalletState.OPEN -> balance
        MemberWalletManager.WalletState.WATCH_ONLY -> stringResource(Res.string.s_dot_s, balance, stringResource(Res.string.key_on_another_device))
    }
}
