package com.machankura.dedun.compose.database.model

import androidx.room3.Entity
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The one refresh this device has confirmed from one generation of one key.
 * Written once, before the confirmation goes out, and never replaced.
 *
 * This is the rule that keeps a group from splitting (docs/frost-share-refresh.md,
 * D3a). Two refreshes from the same generation with different recipients could
 * both complete -- each making a different next generation, and the group able
 * to sign with neither. A certificate needs every recipient's confirmation, and
 * recipients are a majority, so any two recipient sets share a member; that
 * member confirming only one is what leaves the other without a certificate.
 * It matters for the same reason the nonce rules on `FrostSigningSession` do:
 * it is the one way the whole thing is lost.
 */
@Entity(primaryKeys = ["dkgSessionId", "baseGeneration"])
data class ShareRefreshConfirmation(
    val dkgSessionId: String,
    val baseGeneration: Int,
    val refreshSessionId: String,
    val confirmedAt: Instant = Clock.System.now(),
)
