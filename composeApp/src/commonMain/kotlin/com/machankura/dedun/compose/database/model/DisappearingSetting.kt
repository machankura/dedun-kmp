package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant

/**
 * An identity's default for how long what it writes in NIP-17 conversations lasts, in seconds;
 * `0` is Off. No row reads as [com.machankura.dedun.compose.nostr.DisappearingMessages.DEFAULT_SECONDS], which
 * is how a new identity and an upgraded one both start at a day without a data migration.
 * docs/disappearing-messages.md, D4 and D9.
 */
@Entity
data class DisappearingSetting(
    @PrimaryKey
    val userPublicKey: HexKey,
    val defaultSeconds: Long,
    val updatedAt: Instant,
)

/**
 * A gift wrap this device has already handled and deleted, kept until the wrap's own expiry so a
 * re-sync that brings it back is dropped before it is decrypted again. The wrap outlives its rumor
 * by up to two days on a relay, by design. docs/disappearing-messages.md, D6.
 */
@Entity
data class ExpiredGiftWrap(
    @PrimaryKey
    val id: HexKey,
    val dropAfter: Instant,
)
