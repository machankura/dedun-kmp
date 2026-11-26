package com.machankura.dedun.compose.ui.composable.widgets.buttons

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.broadcast
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.theme.spacing

/**
 * The way into `BroadcastGroupSignedEventScreen`, drawn the same under every
 * event the group signed -- its profile, each relay list, each post, each schema
 * and each entry -- so that it reads as one action on whichever screen it sits.
 *
 * Behind no gate anywhere it appears: sending what the group has already signed
 * takes no share of the key, since the signature on it is the group's whoever
 * repeats it. Only *making* a statement is gated, and that is the caller's
 * concern. The icon is decorative because the label is beside it.
 */
@Composable
fun BroadcastButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.CellTower, contentDescription = Decorative)

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

        Text(stringResource(Res.string.broadcast))
    }
}
