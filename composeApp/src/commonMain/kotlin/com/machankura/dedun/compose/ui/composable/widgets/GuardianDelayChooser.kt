package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.delay_custom
import bennu.composeapp.generated.resources.delay_in_blocks
import bennu.composeapp.generated.resources.delay_longest
import bennu.composeapp.generated.resources.delay_one_day
import bennu.composeapp.generated.resources.delay_one_hour
import bennu.composeapp.generated.resources.delay_one_month
import bennu.composeapp.generated.resources.delay_one_week
import bennu.composeapp.generated.resources.delay_six_months
import bennu.composeapp.generated.resources.delay_two_hours
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.view.model.GuardianDelayInput
import org.jetbrains.compose.resources.stringResource

/**
 * How long a coin waits before a group can take it through its own leaf: in a
 * guarded subgroup's wallet (`docs/guardian-delay.md`) or in the wallets it
 * guards for its members (`docs/member-wallet-delay.md`). Presets for what
 * people mean, and a field for the exact number of blocks, which says what it
 * works out to while it is typed.
 *
 * The words are the caller's -- [title], and [consequence], the trade a long
 * delay makes -- because *the subgroup* and *the member* differ.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuardianDelayChooser(
    title: String,
    consequence: String,
    input: String,
    delay: Long?,
    isCustom: Boolean,
    problem: String?,
    onPreset: (Long) -> Unit,
    onCustom: () -> Unit,
    onInput: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
        ) {
            GuardianDelayInput.PRESETS.forEach { preset ->
                FilterChip(
                    selected = !isCustom && delay == preset,
                    onClick = { onPreset(preset) },
                    label = { Text(presetLabel(preset)) }
                )
            }
            FilterChip(
                selected = isCustom,
                onClick = onCustom,
                label = { Text(stringResource(Res.string.delay_custom)) }
            )
        }
        if (isCustom) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(Res.string.delay_in_blocks)) },
                isError = problem != null,
                supportingText = { Text(problem ?: delay?.let { delayText(it) }.orEmpty()) }
            )
        } else if (delay != null && problem == null) {
            Text(text = delayText(delay), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = consequence,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** What a preset chip calls its delay: the time it was chosen to be, not its estimate. */
@Composable
private fun presetLabel(blocks: Long): String = when (blocks) {
    6L -> stringResource(Res.string.delay_one_hour)
    12L -> stringResource(Res.string.delay_two_hours)
    144L -> stringResource(Res.string.delay_one_day)
    1_008L -> stringResource(Res.string.delay_one_week)
    4_320L -> stringResource(Res.string.delay_one_month)
    26_280L -> stringResource(Res.string.delay_six_months)
    else -> stringResource(Res.string.delay_longest)
}
