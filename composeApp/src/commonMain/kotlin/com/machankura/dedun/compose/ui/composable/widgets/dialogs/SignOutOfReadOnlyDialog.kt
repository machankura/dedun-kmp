package com.machankura.dedun.compose.ui.composable.widgets.dialogs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.cancel
import bennu.composeapp.generated.resources.sign_out
import bennu.composeapp.generated.resources.this_device_holds_no_key_for_s_so_there
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.extensions.hexToNpubHrp
import com.machankura.dedun.compose.ui.composable.widgets.Decorative

/**
 * The confirmation before a read-only identity leaves the device, naming the npub so
 * the user sees which one. Shared by the profile tab's *sign out* and the not-found
 * screen's *use a different key*, which differ only in what the title calls it.
 */
@Composable
fun SignOutOfReadOnlyDialog(
    title: StringResource,
    nostrPublicKey: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Logout, contentDescription = Decorative) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(Res.string.this_device_holds_no_key_for_s_so_there, nostrPublicKey?.hexToNpubHrp() ?: "")) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.sign_out)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}
