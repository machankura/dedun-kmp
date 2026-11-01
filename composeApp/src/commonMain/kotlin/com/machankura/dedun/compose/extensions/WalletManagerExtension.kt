package com.machankura.dedun.compose.extensions

import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.phoenix.managers.nostrPrivateKey

fun LocalKeyManager.nostrPublicKey(): String {
    return KeyPair(
        privKey = nostrPrivateKey().value.toByteArray()
    ).pubKey.toHexKey()
}