package com.machankura.dedun.compose.ui

import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.managers.GroupWavelengthOperatorManager
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlin.time.Instant

/** A room with a Wavelength wallet, as the screens are handed one: none of it derives or verifies. */
internal object GroupWavelengthUiFixtures {
    const val ME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    val OTHER: HexKey = "b".repeat(64)
    val chatRoomId = "d4c3b2a1".repeat(8)
    val localChatRoom = LocalChatRoom(chatRoom = ChatRoom(id = chatRoomId, userPublicKey = ME, subject = "Treasury", description = null, mlsGroupState = "state"))

    val key = IcebergKeySession(
        id = "ab".repeat(32), chatRoomId = chatRoomId, coordinatorPublicKey = ME, userPublicKey = ME, threshold = 2, participantCount = 4,
        participantPublicKeys = IcebergKeySession.formatParticipants(listOf(ME, OTHER, "c".repeat(64), "d".repeat(64))),
        onchainDkgSessionId = "x", purpose = IcebergKeySession.PURPOSE_WAVELENGTH, stage = IcebergRitualStage.COMPLETE, share = "00", groupPublicKey = "02" + "11".repeat(32),
    )

    fun wallet(chain: Chain = Chain.Signet, number: Long = 0) = GroupWavelengthWalletManager.Wallet(
        signedEvent = GroupSignedEvent(id = "e".repeat(64), chatRoomId = chatRoomId, publicKey = chatRoomId, kind = GroupWavelengthWalletStatementEvent.KIND, tags = emptyArray(), content = "", signature = "f".repeat(128), createdAt = Instant.fromEpochSeconds(1)),
        statement = GroupWavelengthWalletStatementEvent.Statement(
            chain = chain, wallet = number, onchainAccount = 0, icebergKey = key.groupPublicKey!!, ceremonyId = key.id, threshold = 2, participants = key.participants(),
            identity = "02" + "33".repeat(32), manifestHash = "44".repeat(32), arkOperatorKey = "02" + "55".repeat(32), policy = GroupWavelengthWalletStatementEvent.Policy.DEFAULT,
        ),
    )

    fun operator(publicKey: HexKey?, term: Long = 3) = GroupWavelengthOperatorManager.Operator(publicKey, term, "9".repeat(64))
}
