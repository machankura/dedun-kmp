package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Emails
import com.machankura.dedun.compose.contacts.PhoneNumbers
import com.machankura.dedun.compose.contacts.deviceRegionCode
import com.machankura.dedun.compose.managers.Contacts
import com.machankura.dedun.compose.nostr.wallet.ContactRecordEvent
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.spacing
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.ask_the_group
import dedun.composeapp.generated.resources.attribute_to_s
import dedun.composeapp.generated.resources.country
import dedun.composeapp.generated.resources.email_address
import dedun.composeapp.generated.resources.everyone_in_the_group_will_see_this_address
import dedun.composeapp.generated.resources.everyone_in_the_group_will_see_this_number
import dedun.composeapp.generated.resources.name
import dedun.composeapp.generated.resources.not_an_email_address
import dedun.composeapp.generated.resources.not_a_phone_number
import dedun.composeapp.generated.resources.only_plain_letters_in_an_email_address
import dedun.composeapp.generated.resources.phone_number
import dedun.composeapp.generated.resources.pick_a_country_or_start_with_plus
import dedun.composeapp.generated.resources.reopen_s
import dedun.composeapp.generated.resources.someone_outside_the_group
import dedun.composeapp.generated.resources.the_group_already_counts_this_as_s
import dedun.composeapp.generated.resources.the_group_will_see_s
import dedun.composeapp.generated.resources.too_long_for_a_phone_number
import dedun.composeapp.generated.resources.too_short_for_a_phone_number
import dedun.composeapp.generated.resources.waiting_for_s_to_update_dedun
import dedun.composeapp.generated.resources.what_the_group_will_call_them
import dedun.composeapp.generated.resources.wrong_length_for_s
import org.jetbrains.compose.resources.stringResource

/**
 * Proposing a contact: a name and one phone number or email address, which the group then signs
 * whole -- or finding that it already counts that number. `docs/non-member-deposits.md`, D2, D5,
 * D6 and Phase 3.
 *
 * Nothing is proposed while a member's device hasn't said it reads contacts ([waitingFor]): the
 * signed record carries the number in the clear, and an older build would show it as raw JSON.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactSheet(
    chatRoomId: String,
    contacts: Map<HexKey, Contacts.Contact>,
    /** The names of the current members whose devices haven't said they read contacts. */
    waitingFor: List<String>,
    pending: Boolean,
    onDismiss: () -> Unit,
    onUse: (Contacts.Contact) -> Unit,
    onReopen: (Contacts.Contact) -> Unit,
    onAsk: (ContactIdentifier, String) -> Unit,
) {
    var byEmail by remember { mutableStateOf(false) }
    var region by remember { mutableStateOf(deviceRegionCode()?.let(PhoneNumbers::region) ?: PhoneNumbers.REGIONS.first()) }
    var typed by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    val identifier: ContactIdentifier? = if (byEmail) {
        (Emails.normalise(typed) as? Emails.Normalised.Address)?.let { ContactIdentifier.Email(it.text) }
    } else {
        (PhoneNumbers.normalise(typed, region) as? PhoneNumbers.Normalised.Number)?.let { ContactIdentifier.Phone(it.e164) }
    }
    val problem: String? = when {
        typed.isBlank() || identifier != null -> null
        byEmail -> when ((Emails.normalise(typed) as Emails.Normalised.Refused).why) {
            Emails.Refusal.NOT_ASCII -> stringResource(Res.string.only_plain_letters_in_an_email_address)
            else -> stringResource(Res.string.not_an_email_address)
        }
        else -> when ((PhoneNumbers.normalise(typed, region) as PhoneNumbers.Normalised.Refused).why) {
            PhoneNumbers.Refusal.NEEDS_A_COUNTRY -> stringResource(Res.string.pick_a_country_or_start_with_plus)
            PhoneNumbers.Refusal.TOO_SHORT -> stringResource(Res.string.too_short_for_a_phone_number)
            PhoneNumbers.Refusal.TOO_LONG -> stringResource(Res.string.too_long_for_a_phone_number)
            PhoneNumbers.Refusal.WRONG_LENGTH_FOR_COUNTRY -> stringResource(Res.string.wrong_length_for_s, region.code)
            else -> stringResource(Res.string.not_a_phone_number)
        }
    }
    // The id is the number's, so a contact the group already counts is found, not proposed twice (D3).
    val existing = identifier?.let { contacts[it.contactId(chatRoomId)] }
    val trimmedName = name.trim()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.readableContent().verticalScroll(rememberScrollState()).padding(MaterialTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        ) {
            Text(stringResource(Res.string.someone_outside_the_group), style = MaterialTheme.typography.titleMedium)
            if (waitingFor.isNotEmpty()) {
                Text(
                    stringResource(Res.string.waiting_for_s_to_update_dedun, waitingFor.joinToString { "@$it" }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !byEmail, onClick = { byEmail = false; typed = "" }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(Res.string.phone_number))
                }
                SegmentedButton(selected = byEmail, onClick = { byEmail = true; typed = "" }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(Res.string.email_address))
                }
            }

            if (!byEmail) {
                ExposedDropdownMenuBox(expanded = picking, onExpandedChange = { picking = it }) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        value = "${region.code} +${region.callingCode}",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.country)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = picking) },
                    )
                    ExposedDropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        PhoneNumbers.REGIONS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("${option.code} +${option.callingCode}") },
                                onClick = { region = option; picking = false },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                label = { Text(stringResource(if (byEmail) Res.string.email_address else Res.string.phone_number)) },
                keyboardOptions = KeyboardOptions(keyboardType = if (byEmail) KeyboardType.Email else KeyboardType.Phone, imeAction = ImeAction.Next),
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } } ?: identifier?.let { { Text(stringResource(Res.string.the_group_will_see_s, it.formatted)) } },
            )

            if (existing != null) {
                Text(stringResource(Res.string.the_group_already_counts_this_as_s, existing.name), style = MaterialTheme.typography.bodyMedium)
                if (existing.closed) {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { onReopen(existing) }, enabled = !pending) {
                        Text(stringResource(Res.string.reopen_s, existing.name))
                    }
                } else {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { onUse(existing) }, enabled = !pending && existing.isOpen) {
                        Text(stringResource(Res.string.attribute_to_s, existing.name))
                    }
                }
                return@Column
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it.take(ContactRecordEvent.MAX_NAME_CHARS) },
                singleLine = true,
                label = { Text(stringResource(Res.string.name)) },
                supportingText = { Text(stringResource(Res.string.what_the_group_will_call_them)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Text(
                stringResource(if (byEmail) Res.string.everyone_in_the_group_will_see_this_address else Res.string.everyone_in_the_group_will_see_this_number),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { identifier?.let { onAsk(it, trimmedName) } },
                enabled = !pending && identifier != null && ContactRecordEvent.isValidName(trimmedName),
            ) {
                Text(stringResource(Res.string.ask_the_group))
            }
        }
    }
}
