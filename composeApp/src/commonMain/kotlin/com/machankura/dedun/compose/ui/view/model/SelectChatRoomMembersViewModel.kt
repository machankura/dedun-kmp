package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.navigation.routes.SelectChatRoomTypeRoute
import com.machankura.dedun.compose.ui.view.state.SelectChatRoomMembersUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * Second step of group creation: who the group is for.
 *
 * Only profiles already in the local store are offered — this screen is a
 * picker, not a directory search. Nothing is persisted here; the picked keys
 * ride on to [SelectChatRoomTypeViewModel], which mints the group.
 */
class SelectChatRoomMembersViewModel(
    val activeUserPublicKey: HexKey,
    val name: String,
    val description: String?,
    initialSelectChatRoomMembersUIState: SelectChatRoomMembersUIState,
    val nostrRepository: NostrRepository,
): ViewModel() {

    var selectChatRoomMembersUIState: SelectChatRoomMembersUIState by mutableStateOf(initialSelectChatRoomMembersUIState)
        private set

    private val logger = Logger.withTag(TAG)

    /** Public keys the user has ticked, in the order they were ticked. */
    val selectedPublicKeys = mutableStateListOf<HexKey>()

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val profiles = nostrRepository.searchableProfiles(
                excludingPublicKeys = arrayOf(activeUserPublicKey)
            )

            selectChatRoomMembersUIState = SelectChatRoomMembersUIState.Loaded(
                profiles = profiles
            )

            // Ask for everybody's key package up front: by the time the user has finished
            // ticking names and chosen how the group is run, the relay round trip is
            // usually done, so creating the group doesn't have to sit and wait for it.
            scheduleKeyPackageEventSynchronization(profiles)
        }
    }

    fun toggleMember(publicKey: HexKey) {
        if (!selectedPublicKeys.remove(publicKey)) {
            selectedPublicKeys.add(publicKey)
        }
    }

    fun isSelected(publicKey: HexKey): Boolean = selectedPublicKeys.contains(publicKey)

    fun selectChatRoomType(
        onNavigateToRoute: (Route) -> Unit
    ) {
        logger.d("selectChatRoomType with ${selectedPublicKeys.size} member(s)")

        onNavigateToRoute.invoke(
            SelectChatRoomTypeRoute(
                activeUserPublicKey = activeUserPublicKey,
                name = name,
                description = description,
                memberPublicKeys = selectedPublicKeys.toList()
            )
        )
    }

    private suspend fun scheduleKeyPackageEventSynchronization(profiles: List<Profile>) {
        if (profiles.isEmpty()) return

        // TODO: Sync from aggregated relays of profile KeyPackageRelays...
        val synchronizationFilter = SynchronizationFilter(
            authors = profiles.map { it.publicKey }.toTypedArray(),
            kinds = arrayOf(
                KeyPackageEvent.KIND
            )
        )

        nostrRepository.queueNegentropySynchronizeRequest(
            Relays.DefaultDMRelayList.map { normalizedRelayUrl ->
                NegentropySynchronizeRequest(
                    id = NegentropySynchronizeRequest.computeId(
                        relayURL = normalizedRelayUrl.url,
                        synchronizationFilter = synchronizationFilter
                    ),
                    purpose = "key-packages",
                    synchronizationFilter = synchronizationFilter,
                    relayURL = normalizedRelayUrl.url,
                    level = 0
                )
            }
        )
    }

    companion object {
        private const val TAG = "SelectChatRoomMembersViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            name: String,
            description: String?,
            initialSelectChatRoomMembersUIState: SelectChatRoomMembersUIState = SelectChatRoomMembersUIState.Loading,
            nostrRepository: NostrRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SelectChatRoomMembersViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    name = name,
                    description = description,
                    initialSelectChatRoomMembersUIState = initialSelectChatRoomMembersUIState,
                    nostrRepository = nostrRepository
                )
            }
        }
    }
}
