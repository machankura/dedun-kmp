package com.machankura.dedun.compose.ui.composable.widgets.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.repository.observeProfilesOf

/**
 * The nostr profiles of [publicKeys], for a screen that has no view model to hold them
 * -- the startup screen, whose own state machine is the wallet's. The switcher gets the
 * same map from `ProfilesViewModel`; both draw `WalletsSelector`, and both lists should
 * look the same.
 */
@Composable
fun rememberProfilesOf(publicKeys: Collection<HexKey>, nostrRepository: NostrRepository): State<Map<HexKey, Profile?>> {
    val keys = publicKeys.toSet()
    return produceState<Map<HexKey, Profile?>>(initialValue = emptyMap(), keys, nostrRepository) {
        nostrRepository.observeProfilesOf(keys).collect { value = it }
    }
}
