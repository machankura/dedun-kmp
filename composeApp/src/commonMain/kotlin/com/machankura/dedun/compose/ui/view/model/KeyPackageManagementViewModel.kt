package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.MarmotKeyPackageBundle
import com.machankura.dedun.compose.repository.MarmotRepository
import com.machankura.dedun.compose.ui.view.state.KeyPackageManagementUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.StateFlow
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.toKeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class KeyPackageManagementViewModel(
    val activeUserPublicKey: HexKey,
    val nostrEventId: HexKey,
    initialActiveProfileUIState: KeyPackageManagementUIState,
    val marmotRepository: MarmotRepository,
    /**
     * The identity, not just its public key: publishing a current-profile package means
     * signing an identity proof with the account key, which the public key cannot do.
     */
    val activeIdentityStateFlow: StateFlow<Identity?>,
): ViewModel() {
    var keyPackageManagementUIState: KeyPackageManagementUIState by mutableStateOf(initialActiveProfileUIState)
        private set

    init {
        loadNostrFeed()
    }

    fun loadNostrFeed() {
        viewModelScope.launch(Dispatchers.IO) {
            marmotRepository.observeMarmotKeyPackageBundles(activeUserPublicKey).distinctUntilChanged().collect { keyPackageBundles ->
                keyPackageManagementUIState = KeyPackageManagementUIState.Loaded(
                    keyPackageBundles = keyPackageBundles
                )
            }
        }
    }

    /**
     * @param onDone called on completion so the screen can report it. Both of these were
     *   fire and forget: you tapped, a coroutine ran, and nothing on screen changed --
     *   which is indistinguishable from a tap that missed.
     */
    fun publishNewKeyPackage(onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            // A read-only identity has nothing to sign the proof with, and no way to
            // sign the kind:30443 either; the notary would refuse it downstream, so
            // refuse it here and report nothing rather than "published".
            val keyPair = activeIdentityStateFlow.value?.takeIf { it.canSign }?.toKeyPair()
            if (keyPair == null) {
                logger.w("Not publishing a key package: the active identity cannot sign")
                return@launch
            }
            // TODO: Rotate all other keys...
            marmotRepository.publishMarmotKeyPackageBundle(keyPair = keyPair)
            onDone()
        }
    }

    fun rotateKeyPackage(
        marmotKeyPackageBundle: MarmotKeyPackageBundle,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            marmotRepository.rotateMarmotKeyPackageBundle(marmotKeyPackageBundle)
            onDone()
        }
    }

    private val logger = Logger.withTag(TAG)

    companion object {
        const val TAG = "KeyPackageManagementViewModel"

        fun factory(
            nostrEventId: HexKey,
            activeUserPublicKey: HexKey,
            initialActiveProfileUIState: KeyPackageManagementUIState = KeyPackageManagementUIState.Loading,
            marmotRepository: MarmotRepository,
            activeIdentityStateFlow: StateFlow<Identity?>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                KeyPackageManagementViewModel(
                    nostrEventId = nostrEventId,
                    initialActiveProfileUIState = initialActiveProfileUIState,
                    marmotRepository = marmotRepository,
                    activeUserPublicKey = activeUserPublicKey,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                )
            }
        }
    }
}