package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import kotlin.time.Clock
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.groupdm.AdminSeal
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose
import com.machankura.dedun.compose.nostr.groupdm.Evidence
import com.machankura.dedun.compose.nostr.groupdm.GroupDmManifest
import com.machankura.dedun.compose.nostr.groupdm.GroupDmSeal

/**
 * Every signer reads a message before the group sends it (D13 of docs/group-threshold-ecdh.md).
 *
 * A seal is ciphertext, and a device that signs one it cannot read is the rubber stamp
 * docs/dedun-wallet-removal.md says a Dedun device must never be. So a batch of kind 13s is
 * checked on arrival, again at approval, and declined out loud with a reason when it fails:
 *
 * 1. the manifest opens with this device's host key, from a proposer who is a current admin;
 * 2. every event is a kind 13 by the room, with no tags;
 * 3. every recipient's evidence is signed by that recipient, and is not the group;
 * 4. this device holds `conv(group, R)` for each, or it *waits* -- joins the round and asks
 *    nothing yet -- rather than declining;
 * 5. every seal opens to the **same** rumor: kind 14, by the room, unsigned, words in it, and
 *    `p` tags exactly the manifest's recipients;
 * 6. every `created_at` is within two days before now and not ahead of it.
 *
 * With the feature off, every batch of seals is declined: a release build cannot read one.
 */
object GroupDmRefusal {
    const val REASON = "Dedun doesn't sign a private message unless it can read it, and it says the same thing to everyone"
    const val CANNOT_READ = "This device can't read what the group would be sending"
    const val FEATURE_OFF = "Dedun doesn't sign a private message it can't read"

    /** How far a seal's `created_at` may be from now: NIP-59's two days back, and a little skew forward. */
    private const val TWO_DAYS = 2L * 24 * 60 * 60
    private const val SKEW = 10L * 60

    sealed interface Verdict {
        /** The message every seal carries, and the manifest it was read from. */
        data class Readable(val rumor: Event, val manifest: GroupDmManifest) : Verdict

        /** Declined, with the reason sent to the group. */
        data class Declined(val reason: String, val why: String) : Verdict

        /** This device is still computing a recipient's key; nothing is asked until it has. */
        data object Waiting : Verdict
    }

    fun involves(events: List<Event>): Boolean = events.any { it.kind == SealEvent.KIND }

    fun involvesItems(items: List<FrostSigningItem>): Boolean =
        items.any { Event.fromJsonOrNull(it.unsignedEventJson)?.kind == SealEvent.KIND }

    suspend fun check(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        proposer: HexKey,
        events: List<Event>,
        proposalTags: Array<Array<String>>,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray?,
        now: Long = Clock.System.now().epochSeconds,
    ): Verdict {
        if (!ExperimentalFeatures.groupDirectMessages) return Verdict.Declined(FEATURE_OFF, "the feature is off")
        val roomId = localChatRoom.chatRoom.id
        // A batch of seals with nothing to read them by is no message any device could check.
        val sealed = GroupDmManifest.sealedIn(proposalTags) ?: return Verdict.Declined(CANNOT_READ, "there is no manifest")
        val secret = nostrPrivateKey ?: return Verdict.Waiting

        // 1. The manifest, addressed to this device's host key by a current admin.
        val context = GroupEcdhManager.contextFor(database, localChatRoom, userPublicKey, secret)
            ?: return Verdict.Declined(CANNOT_READ, "this device's member is not a current admin holding a share")
        val proposerHost = context.admins[proposer]?.second
            ?: return Verdict.Declined(CANNOT_READ, "the proposer is not a current admin")
        val manifest = AdminSeal.open(sealed, ChillDkgRitualManager.deriveHostSecretKey(secret), context.myHostKey, proposerHost)
            ?.let(GroupDmManifest::decode)
            ?: return Verdict.Declined(CANNOT_READ, "the manifest has no slot this device can open")

        // 2. Seals by the room, with nothing a recipient's app would not expect.
        if (events.isEmpty() || events.size != manifest.seals.size) return Verdict.Declined(REASON, "the manifest names ${manifest.seals.size} recipients for ${events.size} seals")
        events.forEach { seal ->
            if (seal.kind != SealEvent.KIND || !seal.pubKey.equals(roomId, ignoreCase = true) || seal.tags.isNotEmpty()) {
                return Verdict.Declined(REASON, "an item is not a bare kind 13 by the group")
            }
        }

        // 3. Each recipient's evidence, as D5 asks of anyone a device multiplies by.
        val recipients = manifest.seals.map { it.recipient.lowercase() }
        if (recipients.toSet().size != recipients.size) return Verdict.Declined(REASON, "a recipient repeats")
        manifest.seals.forEach { seal ->
            Evidence.check(EcdhPurpose.RECIPIENT, seal.recipient, seal.evidence, roomId)?.let {
                return Verdict.Declined(REASON, "a recipient's evidence was refused: $it")
            }
        }

        // 4. The conversation keys, or a round for each missing one, and wait.
        val keys = recipients.map { database.groupDmDao().getConversationKey(roomId, it) }
        if (keys.any { it == null }) {
            manifest.seals.filterIndexed { i, _ -> keys[i] == null }.forEach { seal ->
                GroupEcdhManager.ensureRound(database, context, seal.recipient, EcdhPurpose.RECIPIENT, evidence = seal.evidence)
            }
            GroupEcdhManager.flush(database, context)
            return Verdict.Waiting
        }

        // 5. One message, the same to everyone, to exactly the recipients named.
        val rumors = events.mapIndexed { i, seal ->
            GroupDmSeal.open(seal, keys[i]!!.conversationKey.hexToByteArray())
                ?: return Verdict.Declined(REASON, "a seal does not open with its recipient's key")
        }
        val rumor = rumors.first()
        if (rumors.any { it.id != rumor.id || it.toJson() != rumor.toJson() }) return Verdict.Declined(REASON, "the seals do not hold the same message")
        if (rumor.kind != 14 || !rumor.pubKey.equals(roomId, ignoreCase = true) || rumor.content.isBlank()) {
            return Verdict.Declined(REASON, "the message is not a kind 14 by the group with words in it")
        }
        val tagged = rumor.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1].lowercase() }
        if (tagged.toSet() != recipients.toSet() || tagged.size != recipients.size) {
            return Verdict.Declined(REASON, "the message names other recipients than the manifest")
        }

        // 6. Back-dated as NIP-59 does, and not from the future.
        events.forEach { seal ->
            if (seal.createdAt > now + SKEW || seal.createdAt < now - TWO_DAYS - SKEW) {
                return Verdict.Declined(REASON, "a seal's time is outside NIP-59's two days")
            }
        }
        return Verdict.Readable(rumor, manifest)
    }

    /** The reason a decline of [items] says, on a device without its key to hand. */
    suspend fun reasonFor(database: DedunDatabase, chatRoomId: HexKey, userPublicKey: HexKey): String {
        if (!ExperimentalFeatures.groupDirectMessages) return FEATURE_OFF
        val admins = RoomAdmins.of(database, chatRoomId)?.admins.orEmpty()
        return if (userPublicKey in admins) REASON else CANNOT_READ
    }
}
