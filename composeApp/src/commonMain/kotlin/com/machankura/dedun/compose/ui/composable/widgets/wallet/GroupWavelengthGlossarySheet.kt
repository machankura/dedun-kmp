package com.machankura.dedun.compose.ui.composable.widgets.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.machankura.dedun.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.group_wavelength_glossary_leaving
import dedun.composeapp.generated.resources.group_wavelength_glossary_leaving_body
import dedun.composeapp.generated.resources.group_wavelength_glossary_parties
import dedun.composeapp.generated.resources.group_wavelength_glossary_parties_body
import dedun.composeapp.generated.resources.group_wavelength_glossary_round
import dedun.composeapp.generated.resources.group_wavelength_glossary_round_body
import dedun.composeapp.generated.resources.group_wavelength_glossary_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The three words this wallet cannot be used without, each said once.
 *
 * A round, the two parties, and the way out on your own. They are here because the screens have to
 * name them -- a deadline means nothing without a round, and "whoever runs the wallet" and "the Ark
 * operator" are two different parties that a member has to be able to tell apart to read the balance
 * card at all. Before this, the first was explained nowhere and the other two were a word apart.
 *
 * Three entries rather than a page of terms: anything longer stops being read, and a glossary nobody
 * opens is the same as no glossary. A term that needs a fourth entry is a term the screens should
 * probably stop using.
 */
@Composable
fun GroupWavelengthGlossarySheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)) {
            Text(
                stringResource(Res.string.group_wavelength_glossary_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Entry(Res.string.group_wavelength_glossary_round, Res.string.group_wavelength_glossary_round_body)
            Entry(Res.string.group_wavelength_glossary_parties, Res.string.group_wavelength_glossary_parties_body)
            Entry(Res.string.group_wavelength_glossary_leaving, Res.string.group_wavelength_glossary_leaving_body)
        }
    }
}

/**
 * The button that opens it, holding its own state.
 *
 * Owning the state here is what lets a screen add the glossary with one line in its app bar's
 * `actions` slot, which is the whole reason nothing on these screens had to move to make room for
 * it. The description is a real one rather than [com.machankura.dedun.compose.ui.composable.widgets.Decorative]:
 * the icon is the only route to the explanation, so an icon nobody can name is an explanation nobody
 * can reach.
 */
@Composable
fun GroupWavelengthGlossaryButton() {
    var isOpen by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { isOpen = true }) {
        Icon(Icons.Default.Info, contentDescription = stringResource(Res.string.group_wavelength_glossary_title))
    }
    if (isOpen) GroupWavelengthGlossarySheet(onDismiss = { isOpen = false })
}

@Composable
private fun Entry(term: StringResource, body: StringResource) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        Text(stringResource(term), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
    }
}
