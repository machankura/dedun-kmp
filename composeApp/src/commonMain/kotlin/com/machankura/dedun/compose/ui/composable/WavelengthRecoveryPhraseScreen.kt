package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.wavelength_recovery_phrase
import dedun.composeapp.generated.resources.wavelength_recovery_phrase_explainer
import dedun.composeapp.generated.resources.wavelength_recovery_phrase_none
import dedun.composeapp.generated.resources.wavelength_recovery_phrase_show
import dedun.composeapp.generated.resources.hide
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The words of a wallet that has its own -- an nsec profile's, `docs/wavelength-wallet.md`,
 * D13 and Phase 13. Hidden until asked for, as the profile's own phrase is, and gone again
 * when the screen is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WavelengthRecoveryPhraseScreen(
    loadWords: suspend () -> List<String>?,
    onNavigateBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<String>?>(null) }
    var none by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.wavelength_recovery_phrase)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).readableContent().fillMaxSize().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            Text(stringResource(Res.string.wavelength_recovery_phrase_explainer), style = MaterialTheme.typography.bodyMedium)
            val shown = words
            when {
                none -> Text(stringResource(Res.string.wavelength_recovery_phrase_none), style = MaterialTheme.typography.bodyMedium)
                shown == null -> Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { loadWords().let { if (it == null) none = true else words = it } } },
                ) { Text(stringResource(Res.string.wavelength_recovery_phrase_show)) }
                else -> {
                    shown.forEachIndexed { index, word ->
                        // A number and a word: not catalogue text, and the same in every language.
                        Text("${index + 1}. $word", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = { words = null }) { Text(stringResource(Res.string.hide)) }
                }
            }
        }
    }
}
