package com.machankura.dedun.compose.ui.composable.navigation.routes

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.Serializable

/**
 * The person behind a pasted npub, before a chat with them is started.
 *
 * Keyed by public key rather than by a kind 0's event id, because that is all the
 * entrance has -- and it is why the same screen will serve a scanned code or a
 * `nostr:npub…` link. No relay hint: the dialog has none to give.
 * See docs/npub-profile-preview.md.
 */
@Serializable
data class ProfilePreviewRoute(
    val activeUserPublicKey: String,
    val profilePublicKey: HexKey,
): Route()
