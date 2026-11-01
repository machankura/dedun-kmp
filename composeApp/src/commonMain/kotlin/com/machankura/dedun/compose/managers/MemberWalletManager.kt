package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.types.ChatRoomType
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.MemberWallet
import com.machankura.dedun.compose.database.model.MemberWalletSpend
import com.machankura.dedun.compose.wallet.GroupSpend
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.secp256k1.Hex
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.GroupKeyNoticeEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.dedun.compose.nostr.wallet.MemberWalletRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.machankura.dedun.compose.wallet.GroupWalletRollout
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A member's guarded wallet, from the asking to the guarding:
 * `docs/member-guarded-wallets.md`.
 *
 * The member's device makes a random key, works out every ancestor leaf, stores
 * the key and only then asks the group. A signer puts the request to the group,
 * the group's quorum signs a certificate saying it guards that key for that
 * person, and the certificate is what every device opens the wallet on.
 */
object MemberWalletManager {
    private const val TAG = "MemberWalletManager"

    private val logger = Logger.withTag(TAG)

    /**
     * The key [chatRoomId] signs with and the certificate it was born with --
     * what a member's grant is worked out from -- or null when this device knows
     * neither.
     *
     * This device's own verified key state first. Behind it, the newest answer to
     * a key notice in the room that checks out: a member welcomed after the
     * founding holds no key state, and asks (`askForGroupKey`). Never filed as
     * key state -- see [GroupKeyNoticeEvent].
     */
    suspend fun groupKeyFor(database: DedunDatabase, chatRoomId: String): GroupKeyNoticeEvent.GroupKey? {
        GroupKeyStateManager.keyStateFor(database, chatRoomId)?.takeIf { it.verifies() }?.let { state ->
            return GroupKeyNoticeEvent.GroupKey(
                chatRoomId = state.chatRoomId,
                thresholdPublicKey = state.thresholdPublicKey,
                certificate = state.birthCertificateJson?.let { Event.fromJsonOrNull(it) },
            )
        }

        return noticesIn(database, chatRoomId)
            .sortedByDescending { it.createdAt }
            .firstNotNullOfOrNull { GroupKeyNoticeEvent.read(it, chatRoomId) }
    }

    /**
     * Whether [chatRoomId] guards its members' wallets, and at what delay: the
     * newest policy its own room signed, open or closed, among the ones this
     * device holds -- `docs/member-wallet-delay.md`. Null when it holds none,
     * which means the group has not opened (D8), or that this device has not
     * yet been told.
     *
     * The one reader, used by the member's device when it builds a request and
     * by every signer when it judges one.
     */
    suspend fun policyFor(database: DedunDatabase, chatRoomId: String): MemberWalletPolicyEvent.Policy? =
        MemberWalletPolicyEvent.newestAmong(
            database.groupSignedEventDao()
                .getByChatRoomIdAndKind(chatRoomId, MemberWalletPolicyEvent.KIND)
                .map { it.toEvent() } +
                // A late member's only way to it: carried in a key answer, and
                // checked again here as the room's own signature.
                noticesIn(database, chatRoomId).mapNotNull { GroupKeyNoticeEvent.read(it, chatRoomId)?.policy?.event },
            chatRoomId,
        )

    /**
     * Asks [chatRoomId] for its key, as [userPublicKey]. Returns false, asking
     * nothing, when this device already knows it or has asked and not been
     * answered: a second question would get the same answer.
     */
    suspend fun askForGroupKey(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey): Boolean {
        if (groupKeyFor(database, chatRoomId) != null) return false

        val notices = noticesIn(database, chatRoomId)
        val answered = notices.mapNotNull { GroupKeyNoticeEvent.answers(it) }.toSet()
        if (notices.any { GroupKeyNoticeEvent.isAsking(it) && it.pubKey == userPublicKey && it.id !in answered }) return false

        queue(database, chatRoomId, userPublicKey, GroupKeyNoticeEvent.askingTags(chatRoomId), content = "")
        logger.i("Asked $chatRoomId for its key")
        return true
    }

    /**
     * Answers [request], an asking notice that arrived in [chatRoomId], as
     * [userPublicKey] -- when this device holds the room's verified key state and
     * nobody has answered it yet. Returns whether it answered.
     *
     * Any member holding the state may answer, and none is elected to: a
     * duplicate answer costs bandwidth and nothing else, since every answer is
     * checked on its own and they all say the same thing. The chronicle's
     * request is answered the same way.
     */
    suspend fun answerKeyRequest(database: DedunDatabase, chatRoomId: String, request: Event, userPublicKey: HexKey): Boolean {
        if (!GroupKeyNoticeEvent.isAsking(request) || request.pubKey == userPublicKey) return false
        val state = GroupKeyStateManager.keyStateFor(database, chatRoomId)?.takeIf { it.verifies() } ?: return false
        val path = state.pathIndices() ?: return false
        if (noticesIn(database, chatRoomId).any { GroupKeyNoticeEvent.answers(it) == request.id }) return false

        val groupKey = GroupKeyNoticeEvent.GroupKey(
            chatRoomId = state.chatRoomId,
            thresholdPublicKey = state.thresholdPublicKey,
            certificate = state.birthCertificateJson?.let { Event.fromJsonOrNull(it) },
            // The other thing a late member cannot read for themselves.
            // docs/member-wallet-delay.md, Phase 3.
            policy = policyFor(database, chatRoomId),
        )
        queue(database, chatRoomId, userPublicKey, GroupKeyNoticeEvent.answeringTags(request.id, groupKey, path), content = state.thresholdPublicKey)
        logger.i("Answered ${request.pubKey.take(8)}'s request for $chatRoomId's key")
        return true
    }

    // ---- The member's side -----------------------------------------------

    /** Why a member cannot ask a group to guard a wallet for them here, said as a reason the screen can name. */
    enum class Refusal {
        /** A read-only profile: it cannot sign the request. */
        CANNOT_SIGN,

        /** The room is not a Marmot group, so there is no quorum to sign and no room to carry it. */
        NOT_A_GROUP,

        /** This device does not know what the group signs with -- [askForGroupKey] is the way on. */
        KEY_UNKNOWN,

        /** The group's certificate carries guardian keys this build cannot read. */
        GUARDIANS_UNREADABLE,

        /** Member wallets are not offered on this network yet: `GroupWalletRollout.offersMemberWalletsOn`. */
        NOT_ON_THIS_NETWORK,

        /**
         * The group has not said it guards members' wallets: no policy, as far as
         * this device knows. `docs/member-wallet-delay.md`, D8.
         */
        NOT_OFFERED,

        /** The group's newest policy closed it to new members' wallets. D9. */
        CLOSED,
    }

    /**
     * The groups [ownerPublicKey] is in that could guard a wallet for them: Marmot
     * rooms, whether or not their key is known yet -- but not a direct chat.
     *
     * A direct chat is a Marmot room too, of exactly two people, and it never has
     * a key: a key ceremony needs at least `ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE`
     * participants. Listed, it would ask for a key nobody can ever answer with.
     * So a room of fewer than three members is left out unless this device knows
     * its key -- a real group can shrink to two after its ceremony, and still sign
     * if the two make its quorum. The same "the two of us" the direct-message
     * lookup uses (`ChatRepository.findDirectMessageRoom`).
     */
    suspend fun groupsOf(database: DedunDatabase, ownerPublicKey: HexKey): List<LocalChatRoom> =
        database.chatRoomDao().getChatRoomListByUserPublicKey(ownerPublicKey).filter { couldGuard(database, it) }

    /** Whether [room] is a group that could guard a member's wallet: [groupsOf]'s one rule, for a room on its own page too. */
    suspend fun couldGuard(database: DedunDatabase, room: LocalChatRoom): Boolean {
        if (room.chatRoom.mlsGroupState == null) return false
        val members = room.localParticipants.map { it.participant.participantPublicKey.lowercase() }.toSet()
        return members.size >= ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE || groupKeyFor(database, room.chatRoom.id) != null
    }

    /** Why [chatRoomId] cannot guard a wallet for this profile on [chain], or null when it can. */
    suspend fun refusal(database: DedunDatabase, chatRoomId: String, canSign: Boolean, chain: Chain): Refusal? {
        if (!canSign) return Refusal.CANNOT_SIGN
        if (!GroupWalletRollout.offersMemberWalletsOn(chain)) return Refusal.NOT_ON_THIS_NETWORK
        val room = database.chatRoomDao().findChatRoomById(chatRoomId)
        if (room?.chatRoom?.mlsGroupState == null) return Refusal.NOT_A_GROUP
        val groupKey = groupKeyFor(database, chatRoomId) ?: return Refusal.KEY_UNKNOWN
        if (groupKey.certificate != null && SubgroupBirthCertificateEvent.parseGuardians(groupKey.certificate.tags) == null) {
            return Refusal.GUARDIANS_UNREADABLE
        }
        // Only a group that has opened guards members' wallets, at the delay it
        // chose -- and a late member learns it with the key, so no key and no
        // policy is KEY_UNKNOWN above, with its button to ask.
        return when (policyFor(database, chatRoomId)) {
            null -> Refusal.NOT_OFFERED
            is MemberWalletPolicyEvent.Policy.Closed -> Refusal.CLOSED
            is MemberWalletPolicyEvent.Policy.Open -> null
        }
    }

    /**
     * How many groups a wallet guarded by [chatRoomId] would be guarded by: the
     * group, and every group above it whose key it passes down. Null when this
     * device does not know the group's key.
     */
    suspend fun reachOf(database: DedunDatabase, chatRoomId: String): Int? =
        groupKeyFor(database, chatRoomId)?.let { it.guardians.size + 1 }

    /**
     * How long a coin in a wallet [chatRoomId] guards would wait for each of its
     * guardians, nearest first: the group's own leaf at the delay its open policy
     * sets, and every group above it at the delay it chose -- the same list the
     * grant in [request] carries. Null when this device does not know the
     * group's key, or the group is not open. `docs/member-wallet-delay.md`.
     */
    suspend fun delaysOf(database: DedunDatabase, chatRoomId: String): List<Long>? {
        val key = groupKeyFor(database, chatRoomId) ?: return null
        val policy = policyFor(database, chatRoomId) as? MemberWalletPolicyEvent.Policy.Open ?: return null
        return listOf(policy.delay) + key.guardians.map { it.delay }
    }

    /**
     * Steps 1 and 2: makes a random key, works out every ancestor's leaf and
     * both descriptors, stores the key, and only then asks [chatRoomId] to
     * guard the wallet, as the member [memberPrivateKey] holds.
     *
     * The row is written before the request is queued, so a crash between the
     * two leaves a key and no request rather than a request and no key. The
     * request is checked the way every signer will check it before it goes, so
     * a quorum is never shown one this device would refuse. A member who holds a
     * share of the group's key puts it to the group in the same breath; anybody
     * else waits for a signer to.
     *
     * @throws IllegalStateException with the [Refusal] when it cannot be asked
     */
    suspend fun request(
        database: DedunDatabase,
        chatRoomId: String,
        memberPrivateKey: PrivateKey,
        chain: Chain,
        label: String?,
        createdAt: Instant = Clock.System.now(),
    ): MemberWallet {
        refusal(database, chatRoomId, canSign = true, chain)?.let { throw IllegalStateException("Cannot ask: $it") }
        val groupKey = groupKeyFor(database, chatRoomId) ?: throw IllegalStateException("Cannot ask: ${Refusal.KEY_UNKNOWN}")
        val memberPublicKey = memberPrivateKey.xOnlyPublicKey().value.toHex()

        // The group's own leaf at the delay its newest policy sets, which the
        // request carries so every signer judges it by what the group signed.
        // refusal() has already refused a group that is not open.
        val policy = policyFor(database, chatRoomId) as? MemberWalletPolicyEvent.Policy.Open
            ?: throw IllegalStateException("Cannot ask: ${Refusal.NOT_OFFERED}")

        val walletPrivateKey = MemberWalletDerivation.generate()
        val walletId = MemberWalletDerivation.walletId(walletPrivateKey.publicKey())
        val grant = GuardianDerivation.grant(groupKey.thresholdPublicKey, chatRoomId.lowercase(), groupKey.guardians, walletId, policy.delay)
        val account = MemberWalletDerivation.account(walletPrivateKey.publicKey(), chain, grant)

        val request = MemberWalletRequestEvent.build(
            memberPrivateKey = memberPrivateKey,
            groupChatRoomId = chatRoomId,
            walletPrivateKey = walletPrivateKey,
            account = account,
            label = label,
            createdAt = createdAt.epochSeconds,
            policy = policy.event,
        )
        checkNotNull(MemberWalletRequestEvent.verified(request)) { "The request this device built does not check out" }

        val wallet = MemberWallet(
            walletId = walletId,
            ownerPublicKey = memberPublicKey,
            guardingChatRoomId = chatRoomId,
            chain = GroupWalletStatementEvent.chainName(chain),
            walletPrivateKey = walletPrivateKey.value.toHex(),
            label = MemberWalletRequestEvent.read(request)?.label,
            requestJson = request.toJson(),
            // The request's own second: the row and the event it carries agree on when.
            createdAt = Instant.fromEpochSeconds(request.createdAt),
        )
        database.memberWalletDao().upsert(wallet)
        send(database, chatRoomId, request)
        logger.i("Asked $chatRoomId to guard wallet ${walletId.take(8)}")

        sponsorIfSigner(database, chatRoomId, memberPublicKey, request)
        return wallet
    }

    /**
     * Asks again -- for a request no signer put to the group, or one the group
     * did not sign. With the request [wallet] was asked with, unchanged, when
     * the group would still accept it; otherwise with a new one under the
     * group's newest policy and a new key, as the member [memberPrivateKey]
     * holds, and the old key discarded: no address of it was ever offered.
     * `docs/member-wallet-delay.md`, Phase 5.
     *
     * Returns the wallet now asked for -- [wallet] itself, or its replacement.
     *
     * @throws IllegalStateException when a new request is needed and this group
     * cannot be asked now, with the [Refusal]
     */
    suspend fun askAgain(database: DedunDatabase, wallet: MemberWallet, memberPrivateKey: PrivateKey): MemberWallet {
        val request = Event.fromJsonOrNull(wallet.requestJson) ?: throw IllegalStateException("The stored request does not read")
        if (judgePolicy(database, wallet.guardingChatRoomId, request) is PolicyJudgement.At) {
            send(database, wallet.guardingChatRoomId, request)
            sponsorIfSigner(database, wallet.guardingChatRoomId, wallet.ownerPublicKey, request)
            return wallet
        }

        val chain = MemberWalletRequestEvent.read(request)?.chain ?: throw IllegalStateException("The stored request does not read")
        val replacement = request(database, wallet.guardingChatRoomId, memberPrivateKey, chain, wallet.label)
        discard(database, wallet.walletId)
        logger.i("Asked ${wallet.guardingChatRoomId} again for a new wallet in place of ${wallet.walletId.take(8)}")
        return replacement
    }

    /**
     * Forgets [walletId]'s key. Refused once a group has certified the wallet:
     * from then on its addresses may have been paid. Before, none was ever
     * offered, so no coin can be behind the key.
     */
    suspend fun discard(database: DedunDatabase, walletId: String) {
        val wallet = database.memberWalletDao().getById(walletId) ?: return
        check(certificatesOf(database, wallet.guardingChatRoomId).none { it.walletId == walletId }) {
            "A group guards this wallet: its key is not discarded"
        }
        database.memberWalletDao().delete(walletId)
    }

    /** The request into [chatRoomId], whole, as an inner event's content: see `MemberWalletRequestEvent`. */
    private suspend fun send(database: DedunDatabase, chatRoomId: String, request: Event) {
        queue(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = request.pubKey,
            tags = arrayOf(arrayOf("h", chatRoomId.lowercase())),
            content = request.toJson(),
            kind = MemberWalletRequestEvent.KIND,
        )
    }

    private suspend fun sponsorIfSigner(database: DedunDatabase, chatRoomId: String, memberPublicKey: HexKey, request: Event) {
        if (!FrostSigningManager.canSign(database, chatRoomId)) return
        val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return
        runCatching { putToGroup(database, room, memberPublicKey, request) }
            .onFailure { logger.w("Asked $chatRoomId, and could not put it to the group: ${it.message}") }
    }

    /** Where a member's wallet has got to, as one fact the profile shows. */
    enum class WalletState {
        /** A key and a request here; nobody has put it to the group yet. */
        ASKED,

        /** A session is running over its certificate. */
        SIGNING,

        /** The session failed or was declined, and the group has not certified it. */
        NOT_SIGNED,

        /** Certified, and the key is on this device. */
        OPEN,

        /** Certified, and the key is on another device of this profile's. */
        WATCH_ONLY,
    }

    /** One of a member's guarded wallets, as their profile lists it. */
    data class MemberWalletRow(
        val walletId: String,
        val groupChatRoomId: String,
        /** The guarding group's room on this device, or null when this device is not in it. */
        val groupRoom: LocalChatRoom?,
        val label: String?,
        val chain: Chain,
        /** The account the wallet opens: rederived from the request, guardians and all. */
        val account: GroupWalletDerivation.Account,
        val state: WalletState,
        /** Whether the member is still a member of the guarding group, as far as this device can see. */
        val isMember: Boolean,
        /** The stored key's row, for a wallet whose key is here. Never handed to a screen. */
        internal val stored: MemberWallet?,
        /**
         * Why the group would refuse this wallet's request as it stands, for one
         * not yet guarded -- [judgePolicy] -- or null when it would not. Asking
         * again then makes a new request. `docs/member-wallet-delay.md`.
         */
        val refusedBecause: Why? = null,
    ) {
        val isCertified: Boolean get() = state == WalletState.OPEN || state == WalletState.WATCH_ONLY
    }

    /**
     * Every guarded wallet of [ownerPublicKey]'s this device knows about, newest
     * first: the ones whose key is here, and the ones a group this device is in
     * has certified for this profile -- which includes wallets made on another
     * of its devices.
     */
    suspend fun walletsOf(database: DedunDatabase, ownerPublicKey: HexKey): List<MemberWalletRow> {
        val stored = database.memberWalletDao().getByOwner(ownerPublicKey)
        val certified = database.groupSignedEventDao().getByKind(MemberWalletCertificateEvent.KIND)
            .filter { MemberWalletCertificateEvent.memberOf(it.toEvent()).equals(ownerPublicKey, ignoreCase = true) }
            .mapNotNull { MemberWalletCertificateEvent.verify(it.toEvent(), it.chatRoomId) }
            .distinctBy { it.walletId }
            .associateBy { it.walletId }

        val fromKeys = stored.mapNotNull { wallet ->
            val request = Event.fromJsonOrNull(wallet.requestJson) ?: return@mapNotNull null
            val verified = MemberWalletRequestEvent.verified(request) ?: return@mapNotNull null
            val state = when {
                certified.containsKey(wallet.walletId) -> WalletState.OPEN
                openSessionFor(database, wallet.guardingChatRoomId, request) != null -> WalletState.SIGNING
                wasNotSigned(database, wallet.guardingChatRoomId, request) -> WalletState.NOT_SIGNED
                else -> WalletState.ASKED
            }
            row(database, wallet.walletId, wallet.guardingChatRoomId, wallet.label, verified.account, state, ownerPublicKey, wallet)
                .let { row ->
                    if (row.isCertified) row
                    else row.copy(refusedBecause = (judgePolicy(database, wallet.guardingChatRoomId, request) as? PolicyJudgement.Refused)?.why)
                }
        }
        val elsewhere = certified.values
            .filter { cert -> stored.none { it.walletId == cert.walletId } }
            .map { row(database, it.walletId, it.groupChatRoomId, it.label, it.account, WalletState.WATCH_ONLY, ownerPublicKey, null) }

        return (fromKeys + elsewhere).sortedByDescending { it.stored?.createdAt?.epochSeconds ?: 0L }
    }

    private suspend fun row(
        database: DedunDatabase,
        walletId: String,
        groupChatRoomId: String,
        label: String?,
        account: GroupWalletDerivation.Account,
        state: WalletState,
        ownerPublicKey: HexKey,
        stored: MemberWallet?,
    ): MemberWalletRow {
        val room = database.chatRoomDao().findChatRoomById(groupChatRoomId)
        val isMember = room == null || database.participantDao().findParticipantsByChatRoomId(groupChatRoomId)
            .any { it.participantPublicKey.equals(ownerPublicKey, ignoreCase = true) }
        return MemberWalletRow(walletId, groupChatRoomId, room, label, account.chain, account, state, isMember, stored)
    }

    /** [ownerPublicKey]'s wallet [walletId], or null when this device knows of none. */
    suspend fun walletOf(database: DedunDatabase, ownerPublicKey: HexKey, walletId: String): MemberWalletRow? =
        walletsOf(database, ownerPublicKey).firstOrNull { it.walletId == walletId }

    /**
     * [walletId]'s descriptors with its private key in them, or null when the key
     * is not on this device. See `MemberWalletDerivation.privateDescriptors`.
     */
    suspend fun exportKey(database: DedunDatabase, ownerPublicKey: HexKey, walletId: String): Pair<String, String>? {
        val row = walletOf(database, ownerPublicKey, walletId) ?: return null
        val key = row.stored?.walletPrivateKey?.let { PrivateKey.fromHex(it) } ?: return null
        return MemberWalletDerivation.privateDescriptors(key, row.account)
    }

    /**
     * Signs [psbt] as [walletId]'s own key and files the spend, returning the
     * finished transaction for the caller to broadcast.
     *
     * Checked the way a group member checks a group spend -- `GroupSpend.check`
     * against the account the wallet rederives to, never against the PSBT's own
     * claims -- because the builder is shared and so are its mistakes; and then
     * `correctlySpends`, the last word on whether the network will take it.
     *
     * @throws IllegalStateException when the key is not here, the wallet is not
     * certified, the spend fails a check or is over the cap
     */
    suspend fun signSpend(database: DedunDatabase, ownerPublicKey: HexKey, walletId: String, psbt: Psbt, at: Instant = Clock.System.now()): Transaction {
        val row = walletOf(database, ownerPublicKey, walletId) ?: throw IllegalStateException("No such wallet")
        check(row.state == WalletState.OPEN) { "Only a certified wallet whose key is here can spend" }
        val key = PrivateKey.fromHex(checkNotNull(row.stored).walletPrivateKey)

        val checked = when (val result = GroupSpend.check(psbt, row.account, GroupSpend.Spender.Owner)) {
            is GroupSpend.Result.Ok -> result.checked
            is GroupSpend.Result.Refused -> throw IllegalStateException("This spend ${result.reason}")
        }
        GroupWalletRollout.memberCapFor(row.chain)?.let { cap ->
            check(checked.amountSent <= cap) { "This spend sends more than the ${cap.sat} sats one spend may send on mainnet" }
        }

        val signed = checked.witnessed(checked.inputs.map { MemberWalletDerivation.sign(key, row.chain, it) })
        signed.correctlySpends(checked.inputs.associate { it.outPoint to it.txOut }, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

        database.memberWalletDao().upsertSpend(
            MemberWalletSpend(
                txid = signed.txid.toString(),
                walletId = walletId,
                psbt = GroupSpend.encode(psbt),
                signedTransaction = Hex.encode(Transaction.write(signed)),
                createdAt = at,
            )
        )
        logger.i("Signed ${signed.txid} from wallet ${walletId.take(8)}")
        return signed
    }

    /**
     * Files what the chain said of [txid]: handed to Electrum at [broadcastAt],
     * and -- with [confirmations] non-null -- seen, and confirmed once it is at
     * least one deep.
     */
    suspend fun recordChain(database: DedunDatabase, txid: String, confirmations: Int?, broadcastAt: Instant? = null, at: Instant = Clock.System.now()) {
        val spend = database.memberWalletDao().getSpend(txid) ?: return
        val updated = spend.copy(
            broadcastAt = broadcastAt ?: spend.broadcastAt,
            seenAt = spend.seenAt ?: at.takeIf { confirmations != null },
            confirmedAt = spend.confirmedAt ?: at.takeIf { (confirmations ?: 0) >= 1 },
        )
        if (updated != spend) database.memberWalletDao().upsertSpend(updated)
    }

    // ---- The group's side ------------------------------------------------

    /**
     * Every certificate [chatRoomId] holds that verifies as its own: the member
     * wallets this group guards, newest first. One per wallet -- the `d` tag makes
     * two certificates for one wallet replacements, and they say the same thing.
     */
    suspend fun certificatesOf(database: DedunDatabase, chatRoomId: String): List<MemberWalletCertificateEvent.Verified> =
        database.groupSignedEventDao()
            .getByChatRoomIdAndKind(chatRoomId, MemberWalletCertificateEvent.KIND)
            .mapNotNull { MemberWalletCertificateEvent.verify(it.toEvent(), chatRoomId) }
            .sortedByDescending { it.certificate.createdAt }
            .distinctBy { it.walletId }

    /**
     * Every request a member has put into [chatRoomId] that checks out and asks
     * this room, newest first, one per wallet.
     */
    suspend fun requestsIn(database: DedunDatabase, chatRoomId: String): List<MemberWalletRequestEvent.Verified> =
        database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(MemberWalletRequestEvent.KIND))
            .mapNotNull { MemberWalletRequestEvent.carried(it.asEvent()) }
            .mapNotNull { MemberWalletRequestEvent.verified(it) }
            .filter { it.reading.groupChatRoomId.equals(chatRoomId, ignoreCase = true) }
            .sortedByDescending { it.request.createdAt }
            .distinctBy { it.reading.walletId }

    /**
     * The event id a certificate for [request] signed in [chatRoomId] will have.
     * The room's id is the author, and the request fixes everything else -- which
     * is what lets two sponsors find each other's session.
     */
    fun certificateIdFor(chatRoomId: String, request: Event): HexKey {
        val template = MemberWalletCertificateEvent.template(request)
        return EventHasher.hashId(chatRoomId.lowercase(), template.createdAt, template.kind, template.tags, template.content)
    }

    /** The session open in [chatRoomId] over [request]'s certificate, if one is still running. */
    suspend fun openSessionFor(database: DedunDatabase, chatRoomId: String, request: Event): FrostSigningSession? {
        val certificateId = certificateIdFor(chatRoomId, request)
        return database.frostSigningSessionDao().getSessionsForChatRoom(chatRoomId)
            .filter { it.stage != FrostSigningStage.FAILED && it.stage != FrostSigningStage.COMPLETE }
            .firstOrNull { session -> database.frostSigningSessionDao().getItems(session.id).any { it.eventId == certificateId } }
    }

    /** Whether the latest session over [request]'s certificate in [chatRoomId] failed -- declined, or abandoned. */
    suspend fun wasNotSigned(database: DedunDatabase, chatRoomId: String, request: Event): Boolean {
        val certificateId = certificateIdFor(chatRoomId, request)
        val sessions = database.frostSigningSessionDao().getSessionsForChatRoom(chatRoomId)
            .filter { session -> database.frostSigningSessionDao().getItems(session.id).any { it.eventId == certificateId } }
        return sessions.isNotEmpty() && sessions.all { it.stage == FrostSigningStage.FAILED }
    }

    /**
     * The requests in [chatRoomId] still waiting for somebody to put them to the
     * group: no certificate for the wallet, and no session running over one.
     */
    suspend fun awaitingSponsor(database: DedunDatabase, chatRoomId: String): List<MemberWalletRequestEvent.Verified> {
        val certified = certificatesOf(database, chatRoomId).map { it.walletId }.toSet()
        return requestsIn(database, chatRoomId)
            .filter { it.reading.walletId !in certified }
            .filter { openSessionFor(database, chatRoomId, it.request) == null }
    }

    /**
     * Why [chatRoomId], signing as [thresholdPublicKey], should not sign a
     * proposed member wallet [certificate] -- or null when it may.
     *
     * The check every signer makes before agreeing to guard a member's wallet,
     * refused before anything is shown, for the reason a mismatched id is:
     *
     *  - it is not exactly the certificate the request it carries asks for, or
     *    the request does not check out, or it asked another group;
     *  - the person asking is not a member of this room;
     *  - its guardians are not the ones this group grants: `SubgroupManager.grantFor`
     *    with the wallet's id in a child room's place -- the same list a guarded
     *    subgroup of this group would get. A coordinator who slipped a key in,
     *    dropped an ancestor or reordered the leaves is refused here.
     *
     * The group's own wallet policy decides only which keys the grant carries.
     * It never refuses a member's wallet: an unguarded group guards its members
     * with its own key alone. `docs/member-guarded-wallets.md`, Phase 7.
     *
     * The group's *member-wallet* policy decides whether it guards one at all,
     * and the delay on its own leaf: [judgePolicy], `docs/member-wallet-delay.md`.
     */
    suspend fun refuseCertificate(
        database: DedunDatabase,
        chatRoomId: String,
        thresholdPublicKey: HexKey,
        certificate: Event,
    ): String? {
        val verified = MemberWalletCertificateEvent.verify(certificate, chatRoomId, signed = false)
            ?: return "is not a certificate this group could have been asked for"

        val member = database.participantDao().findParticipantsByChatRoomId(chatRoomId)
            .any { it.participantPublicKey.equals(verified.memberPublicKey, ignoreCase = true) }
        if (!member) return "was asked by somebody who is not a member"

        val delay = when (val judged = judgePolicy(database, chatRoomId, verified.request)) {
            is PolicyJudgement.Refused -> return judged.reason
            is PolicyJudgement.At -> judged.delay
        }

        val expected = runCatching { SubgroupManager.grantFor(database, chatRoomId, thresholdPublicKey, verified.walletId, delay) }.getOrNull()
            ?: return "grants keys this device cannot check"
        return if (verified.account.guardians == expected) null else "grants guardian keys this group does not"
    }

    /** What a signer makes of the policy a request was built under: the delay to judge it at, or why not. */
    sealed interface PolicyJudgement {
        data class At(val delay: Long) : PolicyJudgement
        data class Refused(val reason: String, val why: Why) : PolicyJudgement
    }

    /** Why a request's policy will not do, said as a reason the member's screen can name. */
    enum class Why {
        /** Built before policies, or under none: the group never said it guards members' wallets. */
        NO_POLICY,

        /** It carries a policy, but not an open one the group signed. */
        NOT_THE_GROUPS,

        /** The group has a newer delay; the request was built under an older one. */
        DELAY_CHANGED,

        /** The group has since stopped guarding members' wallets. */
        CLOSED,
    }

    /**
     * The delay [chatRoomId]'s own leaf must have in the wallet [request] asks
     * for, or why the request cannot be signed -- `docs/member-wallet-delay.md`,
     * D6. `held` is this device's newest policy, `carried` the one the request
     * was built under:
     *
     *  - carried, an open policy the group signed, and nothing held newer: at
     *    its delay. Newer than what this device holds is fine -- the group
     *    signed it, and this device missed it;
     *  - carried, but a newer open one held: stale, *asked under a delay the
     *    group has since changed*;
     *  - carried, but a newer closed one held: stale, *the group has stopped*;
     *  - carried and not an open policy the group signed: refused;
     *  - none carried: refused. A request from before policies never asked for
     *    the delay the group chose, or of a group that chose to guard members.
     *
     * Read from the request, not the certificate, so the member's screen and a
     * sponsor's device can ask it before there is a certificate to check.
     */
    suspend fun judgePolicy(database: DedunDatabase, chatRoomId: String, request: Event): PolicyJudgement {
        val reading = MemberWalletRequestEvent.read(request)
            ?: return PolicyJudgement.Refused("is not a request this group could have been asked", Why.NOT_THE_GROUPS)
        val carried = reading.policy
            ?: return PolicyJudgement.Refused("does not say the group guards members' wallets", Why.NO_POLICY)
        val open = MemberWalletPolicyEvent.verify(carried, chatRoomId) as? MemberWalletPolicyEvent.Policy.Open
            ?: return PolicyJudgement.Refused("carries a policy this group did not sign open", Why.NOT_THE_GROUPS)

        val held = policyFor(database, chatRoomId)
        if (held != null && MemberWalletPolicyEvent.ORDER.compare(held, open) > 0) {
            return when (held) {
                is MemberWalletPolicyEvent.Policy.Open ->
                    PolicyJudgement.Refused("was asked under a delay the group has since changed", Why.DELAY_CHANGED)
                is MemberWalletPolicyEvent.Policy.Closed ->
                    PolicyJudgement.Refused("was asked of a group that has since stopped guarding members' wallets", Why.CLOSED)
            }
        }
        return PolicyJudgement.At(open.delay)
    }

    /**
     * Puts [request] to [groupRoom]'s quorum, as [userPublicKey]: a signing
     * session over the certificate the request determines, which this device's
     * proposing counts as approving. Returns the session already running over it
     * instead, when another signer got there first.
     *
     * @throws IllegalStateException when the wallet is already certified, or this
     * device would refuse the certificate itself -- a quorum should never be asked
     * for what the proposer's own device knows is wrong.
     */
    suspend fun putToGroup(database: DedunDatabase, groupRoom: LocalChatRoom, userPublicKey: HexKey, request: Event): FrostSigningSession {
        val chatRoomId = groupRoom.chatRoom.id
        val reading = MemberWalletRequestEvent.verified(request)?.reading
            ?: throw IllegalStateException("This request does not check out")
        check(certificatesOf(database, chatRoomId).none { it.walletId == reading.walletId }) { "This group already guards that wallet" }
        openSessionFor(database, chatRoomId, request)?.let { return it }

        val thresholdPublicKey = GroupWalletManager.thresholdKeyFor(database, chatRoomId)
            ?: throw IllegalStateException("This group has no shared key to sign with")
        val template = MemberWalletCertificateEvent.template(request)
        val unsigned = Event(certificateIdFor(chatRoomId, request), chatRoomId.lowercase(), template.createdAt, template.kind, template.tags, template.content, "")
        refuseCertificate(database, chatRoomId, thresholdPublicKey, unsigned)?.let { reason ->
            throw IllegalStateException("This group would refuse it: it $reason")
        }

        logger.i("Putting ${reading.memberPublicKey.take(8)}'s wallet ${reading.walletId.take(8)} to $chatRoomId")
        return FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = groupRoom,
            userPublicKey = userPublicKey,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            createdAt = template.createdAt,
        )
    }

    // ---- Shown from the group, and from the person -----------------------

    /** A member's wallet as the guarding group's page lists it: certified, or asked for and not yet put to the group. */
    data class GroupMemberWallet(
        val walletId: String,
        val ownerPublicKey: HexKey,
        /** The member's profile name on this device, or null. */
        val ownerName: String?,
        val label: String?,
        val request: Event,
        val isCertified: Boolean,
    )

    /**
     * The member wallets [chatRoomId] guards, and the requests still waiting for
     * a signer to put them to it -- the group's page's *Members' guarded
     * wallets*, which is never mixed into its subgroups: a row there is a
     * person, not a room.
     */
    suspend fun memberWalletsOf(database: DedunDatabase, chatRoomId: String): List<GroupMemberWallet> {
        suspend fun nameOf(publicKey: HexKey) = database.profileDao().getProfileByPublicKey(publicKey)?.humanReadableNameOrPubkey()
        val certified = certificatesOf(database, chatRoomId).map {
            GroupMemberWallet(it.walletId, it.memberPublicKey, nameOf(it.memberPublicKey), it.label, it.request, isCertified = true)
        }
        val waiting = awaitingSponsor(database, chatRoomId).map {
            GroupMemberWallet(it.reading.walletId, it.reading.memberPublicKey, nameOf(it.reading.memberPublicKey), it.reading.label, it.request, isCertified = false)
        }
        return certified + waiting
    }

    /**
     * Every guarded wallet of [ownerPublicKey]'s that a group [viewerPublicKey]
     * is in on this device guards, as that group holds it -- the person's page's *Wallets your
     * groups guard*. One entry per guarding group: a wallet guarded by a group
     * and its parent, both here, is two ways in, each spending through its own
     * leaf.
     */
    suspend fun guardedByGroupsHere(database: DedunDatabase, viewerPublicKey: HexKey, ownerPublicKey: HexKey): List<GuardedHere> =
        database.chatRoomDao().getChatRoomListByUserPublicKey(viewerPublicKey)
            .filter { it.chatRoom.mlsGroupState != null }
            .flatMap { room ->
                val wallets = runCatching { GuardianManager.guardedWalletsOf(database, room.chatRoom.id) }.getOrDefault(emptyList())
                    .filter { (it.subject as? GuardianManager.GuardedSubject.Member)?.ownerPublicKey.equals(ownerPublicKey, ignoreCase = true) }
                if (wallets.isEmpty()) return@flatMap emptyList()
                val canSpend = FrostSigningManager.canSign(database, room.chatRoom.id)
                wallets.map { GuardedHere(it, room.chatRoom.subject?.takeIf { name -> name.isNotBlank() }, canSpend) }
            }

    /** A member's wallet as one of the guarding groups on this device holds it. */
    data class GuardedHere(
        val wallet: GuardianManager.GuardedWallet,
        /**
         * The holding group's room name here -- the group with a leaf in it, which
         * is not always the one that directly guards it: that is
         * `GuardedSubject.Member.guardianName`.
         */
        val holderName: String?,
        /** Whether this device holds a share of that group's key, and so can propose a spend through its leaf. */
        val canSpend: Boolean,
    )

    /**
     * One of a member's wallets on their page, with every group here that holds
     * a leaf in it -- `docs/member-wallet-guarding-group.md`, D7. The page used to
     * draw a row per holder, so a wallet held by a group and its parent appeared
     * twice, each row calling its holder the guardian.
     */
    data class GuardedHereWallet(
        val walletId: String,
        val label: String?,
        /** The group that directly guards it, as any holder here can name it, or null. */
        val guardianName: String?,
        /** Where no holder can name it: a group it is known to sit below, or null. */
        val below: String?,
        /** The groups here holding a leaf in it, nearest first. */
        val holders: List<GuardedHere>,
    )

    /** [rows] -- one per holding group, as [guardedByGroupsHere] gives them -- as one entry per wallet. */
    fun byWallet(rows: List<GuardedHere>): List<GuardedHereWallet> =
        rows.groupBy { it.wallet.descendantChatRoomId.lowercase() }.map { (_, held) ->
            val holders = held.sortedBy { it.wallet.position }
            val members = holders.mapNotNull { it.wallet.subject as? GuardianManager.GuardedSubject.Member }
            GuardedHereWallet(
                walletId = holders.first().wallet.descendantChatRoomId,
                label = members.firstNotNullOfOrNull { it.label },
                guardianName = members.firstNotNullOfOrNull { it.guardianName },
                // The nearest holder's own child it sits under, or the holder
                // itself: either is a group it is truly below.
                below = holders.firstNotNullOfOrNull { holder ->
                    (holder.wallet.subject as? GuardianManager.GuardedSubject.Member)?.branchName ?: holder.holderName?.takeUnless { holder.wallet.isGuardedHere }
                },
                holders = holders,
            )
        }

    private suspend fun noticesIn(database: DedunDatabase, chatRoomId: String): List<Event> =
        database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(GroupKeyNoticeEvent.KIND))
            .map { it.asEvent() }

    /**
     * A stored inner event as the event it was, unsigned: an inner event's
     * authority is the MLS frame it arrived in, not a signature, so `sig` is
     * empty here as it is everywhere inner events are read back.
     */
    internal fun MarmotInnerEvent.asEvent(): Event =
        Event(id = id, pubKey = publicKey, createdAt = createdAt.epochSeconds, kind = kind, tags = tags, content = content, sig = "")

    /**
     * Queues one inner event in [chatRoomId] for the outbound pipeline, the way
     * every inner event is: a row, which `encryptAndSendMarmotInnerEvent` sends.
     */
    internal suspend fun queue(
        database: DedunDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        tags: Array<Array<String>>,
        content: String,
        kind: Int = GroupKeyNoticeEvent.KIND,
        createdAt: Instant = Clock.System.now(),
    ): HexKey {
        val id = EventHasher.hashId(userPublicKey, createdAt.epochSeconds, kind, tags, content)
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id,
                publicKey = userPublicKey,
                kind = kind,
                createdAt = createdAt,
                tags = tags,
                content = content,
                chatRoomId = chatRoomId,
            )
        )
        return id
    }
}
