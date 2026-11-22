package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupDmOutbound
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.RelayUrlInput
import com.machankura.dedun.compose.nostr.Relays

/**
 * Where the group receives direct messages: a kind 10050 the group signs (D8 of
 * docs/group-threshold-ecdh.md).
 *
 * A sender's app sends NIP-17 to the recipient's 10050 relays, and many send nothing when
 * there are none, so signing one is the group's decision to receive. Until it has, no device
 * subscribes for its mail. The signed list is a [GroupSignedEvent] row on every signer's
 * device, read back by [relaysOf]; the proposer's device owes it to relays ([onSigned]).
 */
object GroupDmRelays {
    const val KIND = 10050
    private const val RELAY_TAG = "relay"

    /** The relays a 10050's tags list, normalised, or null when any is not a public `wss://` relay or none is. */
    fun relaysIn(tags: Array<Array<String>>): List<String>? {
        val raw = tags.filter { it.size >= 2 && it[0] == RELAY_TAG }.map { it[1] }
        if (raw.isEmpty()) return null
        return raw.map { RelayUrlInput.relayUrlOrNull(it)?.url ?: return null }.distinct()
    }

    fun tagsFor(relays: List<String>): Array<Array<String>> = relays.map { arrayOf(RELAY_TAG, it) }.toTypedArray()

    /** What the setup screen starts from. */
    val defaultRelays: List<String> get() = Relays.DefaultDMRelayList.map { it.url }

    /**
     * Asks the group to sign [relays] as where it receives messages.
     *
     * @throws IllegalArgumentException when a relay is not one [RelayUrlInput] accepts: the
     * signers would decline it ([GroupDmRelayRefusal]), so it is not proposed.
     */
    suspend fun propose(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, relays: List<String>): FrostSigningSession {
        val normalised = relaysIn(tagsFor(relays)) ?: throw IllegalArgumentException("Every relay has to be a public wss:// address")
        return FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            kind = KIND,
            tags = tagsFor(normalised),
            content = "",
        )
    }

    /** The newest 10050 the group signed in [chatRoomId], if it has signed one. */
    suspend fun signedListOf(database: DedunDatabase, chatRoomId: HexKey): GroupSignedEvent? =
        database.groupSignedEventDao().getByChatRoomIdAndKind(chatRoomId, KIND)
            .filter { it.publicKey.equals(chatRoomId, ignoreCase = true) }
            .maxByOrNull { it.createdAt }

    /** Where the group receives messages, or null while it has not opened them. */
    suspend fun relaysOf(database: DedunDatabase, chatRoomId: HexKey): List<String>? =
        signedListOf(database, chatRoomId)?.let { relaysIn(it.tags) }

    /** The relays a signed 10050 is sent to: the publish set, and the relays it names. */
    fun publishTargets(listed: List<String>): List<String> =
        (Relays.eventPublishRelaySet.map { it.url } + listed).distinct()

    /**
     * A 10050 the group just signed: owed to relays by the device that proposed it, through
     * [GroupDmPublisher], with backoff. Any member can send it again from the broadcast screen.
     */
    suspend fun onSigned(database: DedunDatabase, session: FrostSigningSession, signedEvents: List<Event>) {
        if (!session.isCoordinator()) return
        signedEvents.filter { it.kind == KIND && it.pubKey.equals(session.chatRoomId, ignoreCase = true) }.forEach { signed ->
            val listed = relaysIn(signed.tags) ?: return@forEach
            database.groupDmDao().insertOutbound(
                GroupDmOutbound(
                    signingSessionId = session.id,
                    sealId = signed.id,
                    chatRoomId = session.chatRoomId,
                    recipient = session.chatRoomId,
                    relays = publishTargets(listed).joinToString(","),
                    wrapJson = signed.toJson(),
                )
            )
        }
    }
}

/**
 * A 10050 the group is asked to sign whose relays are not all public `wss://` addresses
 * (D8 of docs/group-threshold-ecdh.md). Declined out loud, in the four places every refusal
 * sits, for the reason a wallet's is: a device signs only what it can check, and a relay
 * list senders cannot reach is the group's mail going nowhere.
 */
object GroupDmRelayRefusal {
    const val REASON = "Dedun doesn't sign a list of message relays that are not public wss:// addresses"

    fun of(event: Event): String? =
        if (event.kind == GroupDmRelays.KIND && GroupDmRelays.relaysIn(event.tags) == null) {
            "lists a message relay that is not a public wss:// address"
        } else {
            null
        }

    fun of(events: List<Event>): String? = events.firstNotNullOfOrNull(::of)
}
