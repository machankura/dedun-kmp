package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.database.model.NotificationLedger
import com.machankura.dedun.compose.notifications.NotificationSettings
import com.machankura.dedun.compose.notifications.NotificationSettingsStore
import com.machankura.dedun.compose.notifications.rememberNotificationPermission
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.allow_notifications
import dedun.composeapp.generated.resources.notification_channel_group_activity
import dedun.composeapp.generated.resources.notification_channel_messages
import dedun.composeapp.generated.resources.notification_channel_needs_you
import dedun.composeapp.generated.resources.notifications
import dedun.composeapp.generated.resources.notifications_group_activity_supporting
import dedun.composeapp.generated.resources.notifications_master_supporting
import dedun.composeapp.generated.resources.notifications_messages_supporting
import dedun.composeapp.generated.resources.notifications_needs_you_supporting
import dedun.composeapp.generated.resources.notifications_not_allowed
import dedun.composeapp.generated.resources.notifications_start_after_first_sync
import dedun.composeapp.generated.resources.show_message_text
import dedun.composeapp.generated.resources.show_message_text_supporting
import com.machankura.dedun.compose.ui.composable.widgets.EmptyState
import com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import org.jetbrains.compose.resources.stringResource

/** What the screen is showing: loading, not yet armed, or the switches. */
private sealed interface NotificationSettingsUIState {
    data object Loading : NotificationSettingsUIState
    data object NotArmed : NotificationSettingsUIState
    data class Loaded(val ledger: NotificationLedger) : NotificationSettingsUIState
}

/**
 * An identity's notification switches, and whether the platform lets them do anything.
 * docs/notifications-and-background-sync.md, Phase 7.
 *
 * No error state of its own: the switches are one row in the local database, and a read that
 * fails there has failed for every screen in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    activeUserPublicKey: String,
    store: NotificationSettingsStore,
    onNavigateBack: () -> Unit,
    /** Extra rows from the platform's background sync (Part B); nothing until it exists. */
    backgroundSection: @Composable () -> Unit = {},
) {
    val ledgerState by remember(activeUserPublicKey) { store.observe(activeUserPublicKey) }
        .collectAsState(initial = Unit)
    val uiState = when (val ledger = ledgerState) {
        Unit -> NotificationSettingsUIState.Loading
        is NotificationLedger -> NotificationSettingsUIState.Loaded(ledger)
        else -> NotificationSettingsUIState.NotArmed
    }
    val scope = rememberCoroutineScope()
    val permission = rememberNotificationPermission()

    fun save(settings: NotificationSettings) {
        scope.launch { store.save(activeUserPublicKey, settings) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.notifications)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).readableContent()) {
            ScreenStateTransition(uiState) { state ->
                when (state) {
                    NotificationSettingsUIState.Loading -> LoadingDataIndicator()
                    NotificationSettingsUIState.NotArmed -> EmptyState(
                        message = stringResource(Res.string.notifications_start_after_first_sync),
                        icon = Icons.Default.NotificationsOff,
                    )
                    is NotificationSettingsUIState.Loaded -> {
                        val settings = state.ledger.settings
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                            if (settings.enabled && !permission.granted) item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.screenMargin),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                                    ) {
                                        Text(stringResource(Res.string.notifications_not_allowed))
                                        Button(onClick = { permission.request() }) {
                                            Text(stringResource(Res.string.allow_notifications))
                                        }
                                    }
                                }
                            }
                            item {
                                SwitchRow(
                                    headline = stringResource(Res.string.notifications),
                                    supporting = stringResource(Res.string.notifications_master_supporting),
                                    checked = settings.enabled,
                                    enabled = true,
                                ) { on ->
                                    if (on && !permission.granted) permission.request()
                                    save(settings.copy(enabled = on))
                                }
                            }
                            item {
                                SwitchRow(
                                    headline = stringResource(Res.string.notification_channel_messages),
                                    supporting = stringResource(Res.string.notifications_messages_supporting),
                                    checked = settings.messages,
                                    enabled = settings.enabled,
                                ) { save(settings.copy(messages = it)) }
                            }
                            item {
                                SwitchRow(
                                    headline = stringResource(Res.string.notification_channel_needs_you),
                                    supporting = stringResource(Res.string.notifications_needs_you_supporting),
                                    checked = settings.needsYou,
                                    enabled = settings.enabled,
                                ) { save(settings.copy(needsYou = it)) }
                            }
                            item {
                                SwitchRow(
                                    headline = stringResource(Res.string.notification_channel_group_activity),
                                    supporting = stringResource(Res.string.notifications_group_activity_supporting),
                                    checked = settings.groupActivity,
                                    enabled = settings.enabled,
                                ) { save(settings.copy(groupActivity = it)) }
                            }
                            item {
                                SwitchRow(
                                    headline = stringResource(Res.string.show_message_text),
                                    supporting = stringResource(Res.string.show_message_text_supporting),
                                    checked = settings.showMessageText,
                                    enabled = settings.enabled && settings.messages,
                                ) { save(settings.copy(showMessageText = it)) }
                            }
                            item { backgroundSection() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A list item with a switch, toggled from anywhere on the row: the row is the target, at the
 * list item's own height, and the switch only shows the state.
 */
@Composable
fun SwitchRow(
    headline: String,
    supporting: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}
