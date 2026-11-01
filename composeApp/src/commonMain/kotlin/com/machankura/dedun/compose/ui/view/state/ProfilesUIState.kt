package com.machankura.dedun.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.identity.StoredIdentity

/**
 * The switcher's states. Three, not four: an empty list cannot happen while something
 * is signed in, and the screen does not pretend it can.
 */
sealed interface ProfilesUIState {
    /** The list is being read -- in practice never seen, since startup read it, but the state exists and says what it waits for. */
    data object Loading : ProfilesUIState

    /** One of the two key files could not be read. A retry re-lists. */
    data object Error : ProfilesUIState

    data class Loaded(
        val identities: Map<WalletId, StoredIdentity>,
        /** The one that is open, drawn above the divider. */
        val activeId: WalletId?,
        val walletsMetadata: Map<WalletId, UserWalletMetadata>,
        /** The nostr profile of each listed key, where the device has one; the row falls back to the npub. */
        val profiles: Map<HexKey, Profile?>,
    ) : ProfilesUIState
}
