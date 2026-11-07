package com.machankura.dedun.compose.ui.composable.widgets.wallet

import androidx.compose.runtime.Composable
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.you
import org.jetbrains.compose.resources.stringResource

/**
 * What to call [member] on a screen: *you* for the reader, the profile's own name where this device holds
 * one, and the start of the key where it doesn't.
 *
 * The group wallet screens wrote this four times over
 * (`docs/group-wavelength-transaction-details.md`, T8), and a fifth copy was what the transaction detail
 * needed. The fallback is deliberately a prefix rather than the whole key: a row has one line, and a
 * member whose profile hasn't arrived is still better named by something they can compare than by
 * nothing.
 */
@Composable
fun memberName(localChatRoom: LocalChatRoom, member: HexKey?, reader: HexKey? = null): String {
    val key = member?.lowercase() ?: return ""
    if (reader != null && key == reader.lowercase()) return stringResource(Res.string.you)
    return localChatRoom.localParticipants
        .firstOrNull { it.participant.participantPublicKey.lowercase() == key }
        ?.profile?.humanReadableNameOrPubkey()
        ?: key.take(KEY_PREFIX)
}

/** Enough of a key to tell two members apart, as every one of the copies this replaces used. */
private const val KEY_PREFIX = 16
