package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.DisappearingMessages
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * When an expired NIP-17 payload may leave this device. docs/disappearing-messages.md, D6 and
 * Phase 5.
 *
 * Two managers rebuild an open session from the payloads stored for it -- `ChillDkgRitualManager`
 * and `FrostSigningManager` -- so a round is kept, past its expiry,
 * until the session it names has completed or failed. What the session produced (the shares, the
 * signature, the key) lives on the session's own row and never expires; only the messages that
 * carried it do.
 *
 * A round whose session this device does not have is released: no session here will read it, and
 * a round expires only after its floor, a day for signing and a week for a key ceremony, by which
 * time a session that was going to be made from it has been. Everything else -- what a person
 * wrote, a Welcome, which is processed the moment it arrives, a kind nobody reads back -- is
 * released at its expiry.
 */
object ProtocolRetention : PayloadRetention {
    override suspend fun isReleased(database: DedunDatabase, payload: GiftWrapPayload): Boolean = when {
        DisappearingMessages.classOf(payload.kind) == DisappearingMessages.ExpiryClass.Written -> true
        payload.kind == WelcomeEvent.KIND -> true
        payload.kind in DkgRitualEvents.ALL -> {
            val session = DkgRitualEvents.parseSessionId(payload.tags)?.let { database.dkgSessionDao().getSessionById(it) }
            session == null || session.stage == DkgRitualStage.COMPLETE || session.stage == DkgRitualStage.FAILED
        }
        payload.kind in FrostSigningEvents.ALL -> {
            val session = FrostSigningEvents.parseSessionId(payload.tags)?.let { database.frostSigningSessionDao().getSessionById(it) }
            session == null || session.stage == FrostSigningStage.COMPLETE || session.stage == FrostSigningStage.FAILED
        }
        else -> true
    }
}
