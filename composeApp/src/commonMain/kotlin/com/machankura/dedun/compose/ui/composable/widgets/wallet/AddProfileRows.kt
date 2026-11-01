package com.machankura.dedun.compose.ui.composable.widgets.wallet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.create_a_new_profile
import bennu.composeapp.generated.resources.sign_in_with_a_key
import bennu.composeapp.generated.resources.the_profile_you_are_in_stays_on_this_device
import org.jetbrains.compose.resources.stringResource
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.theme.spacing

/**
 * The two ways to add a profile, under the list of the ones the device has -- on the
 * switcher and on the startup selector alike. Phase 5 of docs/multiple-profiles.md.
 *
 * Both push a screen that has a back button. Landing is not one of them: it has no back
 * button and clears the stack on its way out, because it is the first-run screen, and
 * pushing it from inside would make it a pushed screen on some days and a root on
 * others.
 *
 * @param caption said under the rows where there is a profile to say it of -- the
 *   switcher -- and omitted on the startup selector, where nothing is open.
 */
@Composable
fun AddProfileRows(
    onSignIn: () -> Unit,
    onCreateProfile: () -> Unit,
    caption: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(MaterialTheme.spacing.itemGap))
        HorizontalDivider()
        Spacer(Modifier.height(MaterialTheme.spacing.itemGap))
        TextButton(onClick = onSignIn) {
            Icon(Icons.Default.Key, contentDescription = Decorative)
            Spacer(Modifier.width(MaterialTheme.spacing.relatedGap))
            Text(stringResource(Res.string.sign_in_with_a_key))
        }
        TextButton(onClick = onCreateProfile) {
            Icon(Icons.Default.PersonAdd, contentDescription = Decorative)
            Spacer(Modifier.width(MaterialTheme.spacing.relatedGap))
            Text(stringResource(Res.string.create_a_new_profile))
        }
        if (caption) {
            Text(
                text = stringResource(Res.string.the_profile_you_are_in_stays_on_this_device),
                modifier = Modifier.padding(MaterialTheme.spacing.compactPadding),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
