package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.managers.Contacts
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.managers.MemberWalletManager
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.nostr.wallet.GroupWallet
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import fr.acinq.bitcoin.Chain
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.managers.SubgroupManager
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.managers.DepositLedger
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination

sealed interface ChatRoomDetailUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * What the group has said this room signs with, or null while it has
         * said nothing.
         *
         * Sits above everything else on the screen because it is the fact the
         * rest of the room's signed work stands on: which key the room signs as
         * decides whether anything it has signed will be believed.
         */
        val groupKeyState: GroupKeyState? = null,
        /**
         * The event the group signed to say it, as the group signed it.
         *
         * Held beside [groupKeyState] rather than derived from it, because a
         * state is this device's reading and this is the statement -- with the
         * signature on it, which is the part worth copying somewhere it can be
         * checked. Null on a device that holds the reading and not the event.
         */
        val signedGroupKeyStateEvent: GroupSignedEvent? = null,
        /**
         * The groups this one has certified as its children.
         *
         * Off the parent's own signed certificates, so it lists a subgroup this
         * device holds no room for -- which is the normal position of a member
         * who is not in it, and of everybody between the certificate being
         * signed and the room being created.
         */
        val subgroups: List<SubgroupManager.Subgroup> = emptyList(),
        /**
         * The group this room is a subgroup of, or null when it is nobody's
         * child.
         *
         * Verified: it comes off the room's key state, which only carries a
         * parent whose certificate was checked. A member welcomed in after the
         * founding holds no state and so sees nothing here, even in a room that
         * really is a subgroup.
         */
        val parentChatRoomId: HexKey? = null,
        /**
         * What [parentChatRoomId] decided about this room's wallet at its birth,
         * or null for a room with no certificate. Shown on the parent row,
         * because it is a fact about where the room came from.
         */
        val walletPolicy: WalletPolicyReading? = null,
        /**
         * The delay [parentChatRoomId] gave its own leaf in this room's wallet,
         * or null for a room whose certificate grants no keys.
         */
        val guardianDelay: Long? = null,
        /**
         * The wallet this group has opened on the app's network, or null for one
         * it has not. The row says only whether it is open; the coins are read on
         * `GroupWalletScreen`.
         */
        val groupWallet: GroupWallet? = null,
        /**
         * The networks in [GroupWavelengthRollout.NETWORKS] the group has an
         * onchain wallet on, which is where a Wavelength wallet's money leaves
         * to. A row for a network absent here is disabled and says so, as the
         * Lightning row does: the same question `GroupWavelengthWalletViewModel`
         * asks before its screen's first state.
         *
         * Chain-keyed rather than one flag, because there is a row per network
         * and each needs its own answer. [groupWallet] cannot stand in for it:
         * that is the wallet on the app's own network, which is every network
         * Wavelength offers today and need not stay so.
         */
        val wavelengthOnchainChains: Set<Chain> = emptySet(),
        /** Who holds a share of the group's Lightning wallet key, so removing one of them can say what it leaves. */
        val lightningParticipants: Set<HexKey> = emptySet(),
        val wavelengthParticipants: Set<HexKey> = emptySet(),
        /**
         * Whether this device could open a subgroup here at all.
         *
         * Two conditions and both are about capability rather than permission.
         * The room has to be a Marmot one, since a NIP-17 room has no admins to
         * be a parent's. And this device has to hold a share of the group's key,
         * because certifying a child is a signature by the parent and
         * `FrostSigningManager.proposeSigningBatch` throws for a device that
         * cannot make one -- throwing at a button is not a UI.
         *
         * The rest of the refusals are the picker's, where they can name who is
         * missing and what to do about it.
         */
        val canAddSubgroup: Boolean = false,
        /**
         * The kind:0 the group signed about itself, or null while this device holds
         * none. docs/group-nostr-profile-and-posts.md.
         */
        val groupNostrProfile: GroupNostrProfile? = null,
        /**
         * Whether this device could put a profile or a post to the group's quorum:
         * a Marmot room it has not left, not disbanded, where it holds a share of
         * the key (D4 of docs/group-nostr-profile-and-posts.md). Capability, not
         * permission -- any member with a share may propose, and the quorum is what
         * decides. The identity's own capability, an npub that cannot sign at all,
         * is the screen's to add: it is a composition local, not room state.
         */
        val canProposeAsGroup: Boolean = false,
        /**
         * Whether this device administers a current-profile room: it may remove a
         * member and disband the group, both commits through the publish gate. A
         * legacy room has neither action, and a member who is not an admin has
         * neither either. See docs/marmot-v2.md, Phase 7.
         */
        val canGovern: Boolean = false,
        /**
         * The group's lifecycle says it is over. Absorbing: nothing is sent into it
         * and nobody rejoins, so every write entrance is hidden and the room says so.
         */
        val isDisbanded: Boolean = false,
        /**
         * The member wallets this group guards, and the requests for one still
         * waiting for a signer -- `docs/member-guarded-wallets.md`. Its own
         * section, never mixed into [subgroups]: a row here is a person.
         */
        val memberWallets: List<MemberWalletManager.GroupMemberWallet> = emptyList(),
        /** Whether this profile could ask this group to guard a wallet for it. */
        val canAskForMemberWallet: Boolean = false,
        /** Whether this device holds a share, and so can put a member's request to the group. */
        val canSponsor: Boolean = false,
        /**
         * Whether this group guards its members' wallets, and at what delay --
         * its newest member-wallet policy, or null for one it never signed.
         * `docs/member-wallet-delay.md`.
         */
        val memberWalletPolicy: MemberWalletPolicyEvent.Policy? = null,
        /** Whether to show that at all: a Marmot group, still going. */
        val showsMemberWalletPolicy: Boolean = false,
        /**
         * Where this group pays the active profile, and whether it can say: null, or not a group,
         * for no *Payouts* section at all. `docs/group-payout-destination.md`.
         */
        val payouts: PayoutDestinationRepository.Standing? = null,
        /** Where this group pays each member who has said, by public key; read only when [payouts] is shown. */
        val payoutDestinations: Map<HexKey, GroupPayoutDestination> = emptyMap(),
        /**
         * Members with a destination whose deposits the group has not opened, for a device that
         * holds a share to open: `docs/member-deposits.md`, Phase 5.
         */
        val openableDepositors: Set<HexKey> = emptySet(),
        /** What each member has put in, the unattributed and the total: `docs/member-deposits.md`, Phase 8. */
        val tallies: DepositLedger.Tallies? = null,
        /** The people outside the group it has agreed to count, for naming their tallies: `docs/non-member-deposits.md`. */
        val contacts: Map<HexKey, Contacts.Contact> = emptyMap(),
        /**
         * The room's key as the Shares section shows it: the generation, who
         * holds what, and what this device is asked or may start. Null when the
         * room has no key (docs/frost-share-refresh.md, Phase 11).
         */
        val shares: SharesSummary? = null,
        /**
         * An offer to this device's account to become an admin of this keyed room,
         * by public key of the offer and of who made it; null when there is none.
         * docs/member-admin-promotion.md, Phase 8.
         */
        val promotionOffer: Pair<HexKey, HexKey>? = null,
        /**
         * Founding members this room's founding invite never reached -- only ever
         * non-empty on the device that made the room, while it is still at epoch 0.
         * See `MarmotGroupCreation.uninvitedFounders`; docs/robust-group-admins.md,
         * Phase 4.
         */
        val uninvitedFounders: Set<HexKey> = emptySet(),
        /**
         * What to call the founders [uninvitedFounders] names. They are not in this
         * room, by definition, so the room's own rows cannot be asked.
         */
        val founderNames: Map<HexKey, String> = emptyMap(),
        /**
         * The admin room this group's ceremony made, when this is the group's
         * NIP-17 room and this device holds it. A NIP-17 room has no admins of its
         * own; this is where its members are admins. docs/robust-group-admins.md,
         * Phase 5.
         */
        val adminRoomId: String? = null,
        /**
         * Who administers the room, read off the group's state rather than the
         * participants' `adminAt` flags. Labels each member row **Admin**.
         * docs/member-admin-promotion.md, D1 and Phase 11.
         */
        val adminPublicKeys: Set<HexKey> = emptySet(),
        /**
         * Who holds a live share of the room's key, for the **Holds a share**
         * label. Shown apart from [adminPublicKeys] on purpose: when the two
         * disagree, an enrollment or a rotation is still owed. Empty while share
         * rotation is behind its flag.
         */
        val shareHolders: Set<HexKey> = emptySet(),
        /**
         * What each member row's menu offers, by public key, open or not, with the
         * reasons an item is not; absent means no menu. Every other member has an
         * entry whatever the viewer's role, and this device's own row has one only
         * when stepping down is open. docs/member-row-actions.md, D1 and D2.
         */
        val memberActions: Map<HexKey, List<MemberActionOption>> = emptyMap(),
        /** How **Leave group** goes when this device is an admin; null when it is not. */
        val adminLeave: AdminLeave? = null,
        /** Where a keyed room stands on needing the group's signature; null for any other room. */
        val governance: GovernanceStatus? = null,
        /** The group's inbox, for its row; null where this device has nothing of it to show. */
        val inbox: InboxRowSummary? = null,
        /** What the group's direct messages show here, or nothing. docs/group-threshold-ecdh.md. */
        val groupDmRow: GroupDmRow? = null,
    ): ChatRoomDetailUIState

    data class Error(
        val message: String
    ): ChatRoomDetailUIState
    data object Loading: ChatRoomDetailUIState
}

