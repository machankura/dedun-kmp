package com.machankura.dedun.compose.ui.composable.widgets.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.copied
import dedun.composeapp.generated.resources.copy
import org.jetbrains.compose.resources.stringResource

/**
 * One field of a transaction: what it is, what it says, and a way to take a value away with you.
 *
 * Came out of the profile wallet's own detail screen, where it was private, because the group wallet's
 * detail needed the same thing (`docs/group-wavelength-transaction-details.md`, T8). [copyable] does the
 * clipboard and the snackbar itself, which is four identical lambdas fewer at the call sites, and makes
 * the value selectable as well -- a hash read off a screen is no use to anybody.
 */
@Composable
fun DetailLine(label: String, value: String, modifier: Modifier = Modifier, copyable: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    val notify = rememberNotifier(rememberCoroutineScope())
    // Read above the handler: both of these are composable and an onClick lambda is not.
    val copied = stringResource(Res.string.copied)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space25)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (copyable) {
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(value))
                notify(copied)
            }) { Text(stringResource(Res.string.copy)) }
        } else {
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
