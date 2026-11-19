package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent

/**
 * Whether a key-backed room's admin policy names every one of its founding
 * members. docs/robust-group-admins.md, Phase 3.
 *
 * The founding members are the participants of the ceremony that made the room's
 * key (D1). Every one of their devices built that set from the proposal it agreed
 * to, so each can check the room it was welcomed into against its own record
 * rather than trusting the device that happened to create it -- a bug, an older
 * build, or a Dedun peer could all have listed fewer. A subgroup has a second
 * record of the same set: the birth certificate its parent's quorum signed, which
 * names its admins.
 *
 * Nothing is refused on a mismatch. The room's address is the key, and there is
 * no second room to go to; a warning that names who is missing is what can be
 * acted on, by any admin, with [missingFrom]'s answer.
 */
object FoundingAdmins {

    /** Enough of a key to tell two members apart when no profile is stored. */
    private const val SHORTENED_PUBLIC_KEY_LENGTH = 12

    sealed interface Check {
        /** No key behind the room -- a convenient group, run by its creator. */
        data object NotKeyBacked : Check

        /**
         * A key-backed room this device holds no ceremony for: a member added
         * after the founding, or a reinstall. It is not one of the founders, and
         * has nothing of its own to check the room against.
         */
        data object CannotCheck : Check

        data object Matches : Check

        /** Founding members the room's admin policy does not name. */
        data class Missing(val publicKeys: Set<HexKey>) : Check
    }

    suspend fun check(database: DedunDatabase, chatRoomId: String): Check {
        val state = database.groupKeyStateDao().getByChatRoomId(chatRoomId) ?: return Check.NotKeyBacked
        val group = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.toMlsGroup()
            ?: return Check.CannotCheck
        val ceremony = database.dkgSessionDao().getSessionById(state.dkgSessionId)

        val founders = foundersOf(database, ceremony).orEmpty() + certifiedAdmins(state.birthCertificateJson)
        if (founders.isEmpty()) return Check.CannotCheck

        val missing = founders - group.currentAdminIdentities()
        return if (missing.isEmpty()) Check.Matches else Check.Missing(missing)
    }

    /**
     * Checks the room this device was just welcomed into, and says so in its
     * transcript when founding members are missing from its admins. Once, at the
     * Welcome: the detail screen repeats the check on every visit, and a line per
     * visit would be the same news told again.
     *
     * A whole sentence naming who is missing, like the membership lines it sits
     * among, so nothing prefixes a sender's name to it.
     */
    suspend fun announceIfMissing(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey): Check {
        val check = check(database, chatRoomId)
        if (check !is Check.Missing) return check

        val names = check.publicKeys.map { publicKey ->
            database.profileDao().getProfileByPublicKey(publicKey)?.humanReadableNameOrPubkey()
                ?: publicKey.take(SHORTENED_PUBLIC_KEY_LENGTH)
        }.sorted()

        database.chatMessageDao().upsert(
            ChatMessage(
                content = "This room was created without ${names.joinToString(", ")} as " +
                    (if (names.size == 1) "an admin" else "admins") +
                    ", though they took part in the key ceremony. An admin can add them back from the room's details.",
                messageType = ChatMessage.TYPE_FOUNDING_ADMINS_MISSING,
                chatRoomId = chatRoomId,
                senderPublicKey = userPublicKey,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
            )
        )
        return check
    }

    /**
     * The ceremony that made [chatRoomId]'s key, as this device holds it, and the
     * room it ran in -- or null where this device holds no key state for the room,
     * no record of that ceremony, or no longer the room it ran in.
     */
    suspend fun ceremonyOf(database: DedunDatabase, chatRoomId: String): Pair<DkgSession, LocalChatRoom>? {
        val state = database.groupKeyStateDao().getByChatRoomId(chatRoomId) ?: return null
        val ceremony = database.dkgSessionDao().getSessionById(state.dkgSessionId) ?: return null
        val ceremonyRoom = database.chatRoomDao().findChatRoomById(ceremony.chatRoomId) ?: return null
        return ceremony to ceremonyRoom
    }

    /** The ceremony's participants, or null when this device holds no record of them. */
    private suspend fun foundersOf(database: DedunDatabase, ceremony: DkgSession?): Set<HexKey>? {
        ceremony ?: return null
        ceremony.participantPublicKeySet()?.let { return it }
        val ceremonyRoom = database.chatRoomDao().findChatRoomById(ceremony.chatRoomId) ?: return null
        return ChillDkgRitualManager.participantsOf(ceremony, ceremonyRoom)
    }

    /**
     * The admins a subgroup's parent certified it with, or nothing for a group
     * with no parent. Only read off a state that `GroupKeyStateManager.stateFrom`
     * kept, which it does only for a certificate that checked out.
     */
    private fun certifiedAdmins(birthCertificateJson: String?): Set<HexKey> =
        birthCertificateJson
            ?.let { Event.fromJsonOrNull(it) }
            ?.let { SubgroupBirthCertificateEvent.parseAdminPublicKeys(it.tags).toSet() }
            .orEmpty()
}
