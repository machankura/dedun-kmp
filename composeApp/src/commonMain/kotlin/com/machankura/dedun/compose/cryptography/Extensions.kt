package com.machankura.dedun.compose.cryptography

import com.machankura.dedun.compose.database.model.NostrEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import kotlin.time.Instant

fun com.machankura.dedun.compose.database.model.UnsignedNostrEvent.signOrThrow(nsec: String): NostrEvent {
    val hexPrivateKey = Bech32.decodeBytes(nsec).second
    return this.signOrThrow(hexPrivateKey)
}

fun com.machankura.dedun.compose.database.model.UnsignedNostrEvent.signOrThrow(hexPrivateKey: ByteArray): NostrEvent {
    val tempSigner = NostrSignerSync(
        KeyPair(
            privKey = hexPrivateKey
        )
    )

    val event = tempSigner.signNormal<Event>(
        createdAt = this.createdAt.epochSeconds,
        kind = this.kind,
        tags = this.tags,
        content = this.privateTags?.let { PrivateTagsInContent.encryptNip44(it, tempSigner) } ?: this.content
    )
    return NostrEvent(
        id = event.id,
        pubKey = event.pubKey,
        kind = event.kind,
        tags = event.tags,
        content = event.content,
        createdAt = Instant.fromEpochSeconds(event.createdAt),
        sig = event.sig,
        unsignedNostrEventId = this.id
    )
}