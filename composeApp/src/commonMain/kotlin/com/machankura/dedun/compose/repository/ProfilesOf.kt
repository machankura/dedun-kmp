package com.machankura.dedun.compose.repository

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import com.machankura.dedun.compose.database.model.Profile

/**
 * The nostr profile of each of [publicKeys], as one map that moves when any of them
 * does. For the two lists of the device's profiles -- the switcher and the startup
 * selector -- which show a name and a picture where the device has a kind 0 and the
 * npub where it does not.
 */
fun NostrRepository.observeProfilesOf(publicKeys: Collection<HexKey>): Flow<Map<HexKey, Profile?>> {
    val keys = publicKeys.distinct()
    if (keys.isEmpty()) return flowOf(emptyMap())
    val flows = keys.map { publicKey ->
        flow { emitAll(observeProfileWithPublicKey(publicKey)) }
    }
    return combine(flows) { profiles -> keys.zip(profiles.toList()).toMap() }
}
