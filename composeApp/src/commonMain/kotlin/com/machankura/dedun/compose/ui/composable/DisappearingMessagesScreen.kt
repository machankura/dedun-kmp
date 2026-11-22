package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.repository.DisappearingSettingsStore
import com.machankura.dedun.compose.extensions.toFormattedTimeAndDateString
import com.machankura.dedun.compose.nostr.DisappearingMessages
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.cancel
import dedun.composeapp.generated.resources.disappearing_custom
import dedun.composeapp.generated.resources.disappearing_custom_amount
import dedun.composeapp.generated.resources.disappearing_custom_out_of_range
import dedun.composeapp.generated.resources.disappearing_custom_title
import dedun.composeapp.generated.resources.disappearing_default_value
import dedun.composeapp.generated.resources.disappearing_explanation
import dedun.composeapp.generated.resources.disappearing_groups_note
import dedun.composeapp.generated.resources.disappearing_the_default
import dedun.composeapp.generated.resources.disappearing_messages
import dedun.composeapp.generated.resources.disappearing_notice
import dedun.composeapp.generated.resources.disappearing_notice_change
import dedun.composeapp.generated.resources.disappearing_notice_dismiss
import dedun.composeapp.generated.resources.disappearing_off
import dedun.composeapp.generated.resources.disappearing_offline_warning
import dedun.composeapp.generated.resources.disappearing_protocol_note
import dedun.composeapp.generated.resources.disappearing_saved
import dedun.composeapp.generated.resources.disappearing_set
import dedun.composeapp.generated.resources.disappearing_timer_description
import dedun.composeapp.generated.resources.disappearing_unit_days
import dedun.composeapp.generated.resources.disappearing_unit_hours
import dedun.composeapp.generated.resources.disappearing_unit_minutes
import dedun.composeapp.generated.resources.disappearing_use_default
import dedun.composeapp.generated.resources.disappears_at
import dedun.composeapp.generated.resources.duration_days
import dedun.composeapp.generated.resources.duration_hours
import dedun.composeapp.generated.resources.duration_minutes
import dedun.composeapp.generated.resources.duration_one_day
import dedun.composeapp.generated.resources.duration_one_hour
import dedun.composeapp.generated.resources.duration_one_minute
import dedun.composeapp.generated.resources.duration_one_week
import dedun.composeapp.generated.resources.duration_weeks
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.composable.widgets.rememberNotifier
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * A length as a person reads it: the largest unit that divides it exactly, so a preset reads
 * "1 week" and a custom ninety minutes reads "90 minutes". Off is "Off".
 */
@Composable
fun disappearingLengthText(seconds: Long): String {
    val (unit, count) = DisappearingLength.split(seconds) ?: return stringResource(Res.string.disappearing_off)
    return when (unit) {
        DisappearingLength.Unit.WEEKS -> if (count == 1L) stringResource(Res.string.duration_one_week) else stringResource(Res.string.duration_weeks, count)
        DisappearingLength.Unit.DAYS -> if (count == 1L) stringResource(Res.string.duration_one_day) else stringResource(Res.string.duration_days, count)
        DisappearingLength.Unit.HOURS -> if (count == 1L) stringResource(Res.string.duration_one_hour) else stringResource(Res.string.duration_hours, count)
        DisappearingLength.Unit.MINUTES -> if (count == 1L) stringResource(Res.string.duration_one_minute) else stringResource(Res.string.duration_minutes, count)
    }
}

/** The arithmetic under [disappearingLengthText] and the custom dialog, kept out of composition so it can be tested. */
object DisappearingLength {
    enum class Unit(val seconds: Long) {
        MINUTES(DisappearingMessages.MINUTE),
        HOURS(DisappearingMessages.HOUR),
        DAYS(DisappearingMessages.DAY),
        WEEKS(7 * DisappearingMessages.DAY),
    }

    /** The largest unit [seconds] is a whole number of, and how many; null for Off. */
    fun split(seconds: Long): Pair<Unit, Long>? {
        if (seconds <= 0) return null
        val unit = Unit.entries.reversed().firstOrNull { seconds % it.seconds == 0L } ?: Unit.MINUTES
        return unit to seconds / unit.seconds
    }

    /** A custom length from what was typed, or null when it is not a whole number in bounds. */
    fun parse(amount: String, unit: Unit): Long? {
        val count = amount.trim().toLongOrNull() ?: return null
        if (count <= 0 || count > DisappearingMessages.MAX_SECONDS / unit.seconds) return null
        return (count * unit.seconds).takeIf { DisappearingMessages.isValidPolicy(it) }
    }
}

private sealed interface DisappearingUIState {
    data object Loading : DisappearingUIState

    /** [selected] is null when a conversation follows the default; [default] is the identity's. */
    data class Loaded(val selected: Long?, val default: Long) : DisappearingUIState
}

/**
 * How long what the user writes lasts: their default, or with [chatRoomId] one conversation's
 * override, which may also go back to following the default. docs/disappearing-messages.md, D3,
 * D4, D7 and D11.
 *
 * No error or empty state: the value is one row of the local database with a default when absent,
 * and a read that fails there has failed for every screen in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisappearingMessagesScreen(
    activeUserPublicKey: String,
    chatRoomId: String?,
    store: DisappearingSettingsStore,
    onNavigateBack: () -> Unit,
) {
    val state by remember(activeUserPublicKey, chatRoomId) {
        if (chatRoomId == null) {
            store.observeDefault(activeUserPublicKey).map { default -> DisappearingUIState.Loaded(default, default) }
        } else {
            combine(store.observeOverride(chatRoomId), store.observeDefault(activeUserPublicKey)) { override, default ->
                DisappearingUIState.Loaded(override, default)
            }
        }
    }.collectAsState(initial = DisappearingUIState.Loading)

    val scope = rememberCoroutineScope()
    val notify = rememberNotifier(scope)
    var customOpen by remember { mutableStateOf(false) }

    fun save(seconds: Long?, label: String) {
        scope.launch {
            if (chatRoomId == null) store.saveDefault(activeUserPublicKey, seconds ?: DisappearingMessages.DEFAULT_SECONDS)
            else store.saveOverride(chatRoomId, seconds)
            notify(getString(Res.string.disappearing_saved, label))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.disappearing_messages)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).readableContent()) {
            ScreenStateTransition(state) { uiState ->
                when (uiState) {
                    DisappearingUIState.Loading -> LoadingDataIndicator()
                    is DisappearingUIState.Loaded -> {
                        val defaultLabel = disappearingLengthText(uiState.default)
                        val isCustom = uiState.selected != null && uiState.selected !in DisappearingMessages.PRESETS
                        LazyColumn(modifier = Modifier.selectableGroup()) {
                            if (chatRoomId != null) item {
                                val label = stringResource(Res.string.disappearing_use_default, defaultLabel)
                                ChoiceRow(label, selected = uiState.selected == null) { save(null, defaultLabel) }
                            }
                            DisappearingMessages.PRESETS.forEach { preset ->
                                item(key = preset) {
                                    val label = disappearingLengthText(preset)
                                    // On the profile's own setting, Off is marked as where everyone starts:
                                    // Dedun's default, unlike Giza's day (docs/giza-second-batch.md, D11).
                                    ChoiceRow(
                                        label,
                                        selected = uiState.selected == preset,
                                        supporting = if (chatRoomId == null && preset == DisappearingMessages.DEFAULT_SECONDS) {
                                            stringResource(Res.string.disappearing_the_default)
                                        } else {
                                            null
                                        },
                                    ) { save(preset, label) }
                                }
                            }
                            item {
                                ChoiceRow(
                                    label = stringResource(Res.string.disappearing_custom),
                                    selected = isCustom,
                                    supporting = if (isCustom) disappearingLengthText(uiState.selected!!) else null,
                                ) { customOpen = true }
                            }
                            item {
                                Column(
                                    modifier = Modifier.padding(MaterialTheme.spacing.screenMargin),
                                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                                ) {
                                    Explanation(stringResource(Res.string.disappearing_explanation))
                                    Explanation(stringResource(Res.string.disappearing_offline_warning))
                                    Explanation(stringResource(Res.string.disappearing_protocol_note))
                                    Explanation(stringResource(Res.string.disappearing_groups_note))
                                }
                            }
                        }
                        if (customOpen) {
                            CustomLengthDialog(
                                initial = uiState.selected?.takeIf { isCustom },
                                onDismiss = { customOpen = false },
                                onSet = { seconds, label ->
                                    customOpen = false
                                    save(seconds, label)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Explanation(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** A radio row chosen from anywhere on it: the row is the target, at the list item's height. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, supporting: String? = null, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomLengthDialog(initial: Long?, onDismiss: () -> Unit, onSet: (Long, String) -> Unit) {
    val start = initial?.let { DisappearingLength.split(it) }
        ?.let { (unit, count) -> if (unit == DisappearingLength.Unit.WEEKS) DisappearingLength.Unit.DAYS to count * 7 else unit to count }
    var amount by remember { mutableStateOf(start?.second?.toString() ?: "") }
    var unit by remember { mutableStateOf(start?.first ?: DisappearingLength.Unit.HOURS) }
    val parsed = DisappearingLength.parse(amount, unit)
    val showError = amount.isNotBlank() && parsed == null
    val label = parsed?.let { disappearingLengthText(it) }
    val units = listOf(
        DisappearingLength.Unit.MINUTES to stringResource(Res.string.disappearing_unit_minutes),
        DisappearingLength.Unit.HOURS to stringResource(Res.string.disappearing_unit_hours),
        DisappearingLength.Unit.DAYS to stringResource(Res.string.disappearing_unit_days),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.disappearing_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(Res.string.disappearing_custom_amount)) },
                    singleLine = true,
                    isError = showError,
                    supportingText = { if (showError) Text(stringResource(Res.string.disappearing_custom_out_of_range)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    units.forEachIndexed { index, (option, text) ->
                        SegmentedButton(
                            selected = unit == option,
                            onClick = { unit = option },
                            shape = SegmentedButtonDefaults.itemShape(index, units.size),
                        ) { Text(text) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (parsed != null && label != null) onSet(parsed, label) }, enabled = parsed != null) {
                Text(stringResource(Res.string.disappearing_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

/**
 * The hourglass beside an expiring line's time. Its description carries what the icon shows and
 * the text beside it does not: when the line goes (D12).
 */
@Composable
fun DisappearsIcon(expiresAt: Instant) {
    Icon(
        Icons.Default.HourglassBottom,
        contentDescription = stringResource(Res.string.disappears_at, expiresAt.toFormattedTimeAndDateString()),
        modifier = Modifier.size(MaterialTheme.typography.labelSmall.lineHeight.value.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The conversation's timer in its app bar (D12), opening its setting; nothing while the policy in
 * force is Off.
 */
@Composable
fun DisappearingTimerAction(chatRoomId: String, userPublicKey: String, store: DisappearingSettingsStore, onOpen: () -> Unit) {
    val policy by remember(chatRoomId, userPublicKey) {
        combine(store.observeOverride(chatRoomId), store.observeDefault(userPublicKey)) { override, default ->
            DisappearingMessages.policyFor(override, default)
        }
    }.collectAsState(initial = DisappearingMessages.OFF)
    if (policy == DisappearingMessages.OFF) return
    IconButton(onClick = onOpen) {
        Icon(
            Icons.Default.HourglassBottom,
            contentDescription = stringResource(Res.string.disappearing_timer_description, disappearingLengthText(policy)),
        )
    }
}

/**
 * The one-time notice that what the user writes in this conversation now disappears (D9). Shown
 * until dismissed or acted on, once per conversation, and only while a policy is in force.
 */
@Composable
fun DisappearingNotice(chatRoomId: String, userPublicKey: String, store: DisappearingSettingsStore, onChange: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown by remember(chatRoomId) { store.observeNoticeShown(chatRoomId) }.collectAsState(initial = true)
    val policy by remember(chatRoomId, userPublicKey) {
        combine(store.observeOverride(chatRoomId), store.observeDefault(userPublicKey)) { override, default ->
            DisappearingMessages.policyFor(override, default)
        }
    }.collectAsState(initial = DisappearingMessages.OFF)
    if (shown || policy == DisappearingMessages.OFF) return

    fun dismiss() = scope.launch { store.markNoticeShown(chatRoomId, Clock.System.now()) }

    Card(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.compactPadding)) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                Icon(Icons.Default.HourglassBottom, contentDescription = Decorative)
                Text(stringResource(Res.string.disappearing_notice, disappearingLengthText(policy)))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { dismiss(); onChange() }) { Text(stringResource(Res.string.disappearing_notice_change)) }
                TextButton(onClick = { dismiss() }) { Text(stringResource(Res.string.disappearing_notice_dismiss)) }
            }
        }
    }
}
