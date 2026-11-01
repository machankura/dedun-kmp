package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.appComponents.GroupProfileV1
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519
import com.vitorpamplona.quartz.marmot.mls.components.AppDataDictionary
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.messages.MlsKeyPackage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.RandomInstance
import com.machankura.dedun.compose.nostr.MarmotProfile

/**
 * The one place a Marmot group is minted, on whichever profile new rooms are on.
 *
 * Three sites make rooms -- a direct message, the "convenient" room, and the derived
 * room `MarmotGroupCreation` builds around a threshold key -- and each used to hand
 * `MlsGroup.create` a `0xF2EE` extension of its own. The current profile is not one
 * more extension: epoch 0 carries `required_capabilities` naming extension `0x0006`
 * and proposal `0x0008`, a GroupContext dictionary with the required-component list,
 * the admin policy, the routing and the profile, and the creator's leaf with its
 * account identity proof -- and no `0xF2EE` at all, because RFC 9420 requires every
 * member to support every GroupContext extension and an MDK leaf does not advertise
 * it. So the profile is decided here, once, from [MarmotProfile.DEFAULT_FOR_NEW_ROOMS]
 * unless a caller says otherwise, and the three sites only say what the room is.
 *
 * **The routing id.** A legacy room's `nostr_group_id` is its room id; that is how
 * every room was keyed and how every other client keys the rooms it makes. A
 * current-profile room's is minted here at random, whatever its room id, because the
 * routing component says it must never be derived from a public key and the derived
 * room id is exactly that. The caller records the pair in `MarmotRouting`; the room
 * id stays the identity everything else is keyed on. See docs/marmot-v2.md, Phase 4.
 */
object MarmotGroupFactory {
    class Created(
        val group: MlsGroup,
        /** The `h` tag the room's kind:445 traffic carries. Equal to the room id on the legacy profile. */
        val routingId: HexKey,
        val profile: MarmotProfile,
    )

    /**
     * @param creator the room's creator. Its private key is needed on the current
     *   profile, where the creator's leaf carries a proof only the account key can
     *   sign; a legacy room needs only the public half.
     * @param roomId what `ChatRoom.id` will be: derived for a room with a threshold key
     *   behind it, random otherwise.
     * @param adminPublicKeys the founding admins. The creator is one whether or not it
     *   is listed.
     */
    suspend fun create(
        creator: KeyPair,
        roomId: HexKey,
        name: String?,
        description: String?,
        adminPublicKeys: Collection<HexKey>,
        relays: List<String>,
        profile: MarmotProfile = MarmotProfile.DEFAULT_FOR_NEW_ROOMS,
    ): Created {
        val creatorHex = creator.pubKey.toHexKey()
        val admins = (listOf(creatorHex) + adminPublicKeys.filterNot { it == creatorHex }).distinct()

        return when (profile) {
            MarmotProfile.LEGACY -> {
                val metadata = MarmotGroupData(
                    nostrGroupId = roomId,
                    name = name.orEmpty(),
                    description = description.orEmpty(),
                    adminPubkeys = admins,
                    relays = relays,
                )
                val group = MlsGroup.create(
                    identity = creatorHex.hexToByteArray(),
                    signingKey = Ed25519.generateKeyPair().privateKey,
                    initialExtensions = listOf(metadata.toExtension()),
                )
                Created(group = group, routingId = roomId, profile = profile)
            }

            MarmotProfile.CURRENT -> {
                requireNotNull(creator.privKey) {
                    "A current-profile room needs an identity that can sign: its leaf carries an account identity proof"
                }
                val routingId = RandomInstance.bytes(32)
                // The routing id is an address; the room id is what every member keys
                // the room on and what a joiner has to be told. It rides in the
                // description, as the derivation path does -- see MarmotRoomIdentity.
                val describedRoom = MarmotRoomIdentity.describe(description, roomId)
                val group = CurrentProfileGroupFactory.createGroup(
                    signer = NostrSignerInternal(creator),
                    nostrGroupId = routingId,
                    relays = relays,
                    // Byte-capped by the component (256 and 4096); a name is a `#name`
                    // and a description is a sentence, a path and a room id, so the
                    // caps only ever bind on input nobody meant, and cutting the
                    // front of the description beats refusing the room over it. The
                    // room-id line is last and is what must survive the cut.
                    profile = GroupProfileV1(
                        name = name.orEmpty().truncatedToBytes(GroupProfileV1.NAME_MAX_BYTES),
                        description = describedRoom.truncatedToBytesKeepingTail(GroupProfileV1.DESCRIPTION_MAX_BYTES),
                    ),
                    additionalAdmins = admins.filterNot { it == creatorHex }.map { it.hexToByteArray() },
                )
                Created(group = group, routingId = routingId.toHexKey(), profile = profile)
            }
        }
    }

    /**
     * Whether [keyPackage]'s leaf can be added to [group]: it advertises what the
     * group's `required_capabilities` names.
     *
     * A current-profile group requires the `app_data_dictionary` extension and the
     * `app_data_update` proposal; a legacy group the `0xF2EE` extension and the
     * `self_remove` proposal. A current-profile leaf advertises all four, so it fits
     * either; a legacy leaf fits only a legacy group. The library checks the same
     * thing at `addMember` and refuses with the missing numbers; this is the same
     * answer a step earlier, so the refusal can name the profile instead.
     */
    fun leafFits(group: MlsGroup, keyPackage: MlsKeyPackage): Boolean {
        val capabilities = keyPackage.leafNode.capabilities
        return if (group.currentGroupState().isCurrentProfile) {
            capabilities.extensions.contains(AppDataDictionary.EXTENSION_TYPE) &&
                capabilities.proposals.contains(APP_DATA_UPDATE_PROPOSAL_TYPE)
        } else {
            capabilities.extensions.contains(MarmotGroupData.EXTENSION_ID_INT) &&
                capabilities.proposals.contains(SELF_REMOVE_PROPOSAL_TYPE)
        }
    }

    /** MLS proposal type `app_data_update` (draft-ietf-mls-extensions). */
    private const val APP_DATA_UPDATE_PROPOSAL_TYPE = 0x0008

    /** MLS proposal type `self_remove` (draft-ietf-mls-extensions). */
    private const val SELF_REMOVE_PROPOSAL_TYPE = 0x000A

    /** [this] cut to at most [maxBytes] of UTF-8, on a character boundary. */
    private fun String.truncatedToBytes(maxBytes: Int): String {
        if (encodeToByteArray().size <= maxBytes) return this
        var end = length
        while (end > 0 && substring(0, end).encodeToByteArray().size > maxBytes) end--
        return substring(0, end)
    }

    /** [this] cut from the front to at most [maxBytes] of UTF-8, keeping its last line whole. */
    private fun String.truncatedToBytesKeepingTail(maxBytes: Int): String {
        if (encodeToByteArray().size <= maxBytes) return this
        val tail = substringAfterLast('\n')
        val head = substringBeforeLast('\n', missingDelimiterValue = "")
        val room = maxBytes - tail.encodeToByteArray().size - 1
        return if (room <= 0) tail else head.truncatedToBytes(room) + "\n" + tail
    }
}
