package com.machankura.dedun.compose.nostr.dkg

import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianDelayTag
import com.machankura.dedun.compose.nostr.dkg.tags.DkgSessionIdTag
import com.machankura.dedun.compose.nostr.dkg.tags.DkgThresholdTag
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip14Subject.SubjectTag
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.machankura.dedun.compose.nostr.subgroup.tags.SubgroupParentTag
import com.machankura.dedun.compose.nostr.subgroup.tags.WalletPolicyTag

/**
 * The nostr kinds a ChillDKG ritual is carried on.
 *
 * These are **rumor** kinds: they only ever travel encrypted -- inside a NIP-17
 * gift wrap for a group's own ceremony, and as a Marmot inner event in the
 * parent's room for a subgroup's -- so no relay sees them in the clear and the
 * replaceable semantics normally implied by the 3xxxx range never apply. The
 * transport, sealing and broadcast pipeline is the one chat messages ride.
 *
 * 30310-30312 used to be nip30303 document kinds too, and a round 1 read as a
 * document submission once reached a parent's chat as raw hex. `ChatMessage`
 * recognises these before any content kind for that reason, and the document
 * kinds are gone. See docs/ceremony-creation.md.
 *
 * Who talks to whom, in order:
 *
 * ```
 *  coordinator --[ 30310 proposal      ]-> everyone   "let's make a t-of-n key"
 *  participant --[ 30311 host key      ]-> everyone   host pubkey for the participant set
 *  participant --[ 30312 round 1       ]-> everyone   pmsg1
 *  coordinator --[ 30313 coord round 1 ]-> everyone   cmsg1
 *  participant --[ 30314 round 2       ]-> everyone   CertEq signature
 *  coordinator --[ 30315 certificate   ]-> everyone   cmsg2, the success certificate
 *  anyone      --[ 30316 failure       ]-> everyone   abort + blame
 * ```
 *
 * Everything is addressed to the whole group rather than point-to-point even
 * where the protocol only needs the coordinator to read it. NIP-17 wraps per
 * recipient anyway, the coordinator is untrusted by construction, and letting
 * every member watch the ritual is what makes the progress UI possible without
 * a second channel.
 */
object DkgRitualEvents {
    /** Coordinator opens a ritual. Threshold in [DkgThresholdTag]; membership is the wrap's p-tags. */
    val PROPOSAL: Kind = 30310

    /** A participant's long-term ChillDKG host public key (33-byte compressed, hex). */
    val HOST_KEY: Kind = 30311

    /** A participant's `pmsg1` from `ChillDKG.participantStep1`, hex encoded. */
    val ROUND_1: Kind = 30312

    /** The coordinator's `cmsg1` from `ChillDKG.coordinatorStep1`, hex encoded. */
    val COORDINATOR_ROUND_1: Kind = 30313

    /** A participant's 64-byte CertEq signature from `ChillDKG.participantStep2`, hex encoded. */
    val ROUND_2: Kind = 30314

    /** The coordinator's `cmsg2` — the success certificate — hex encoded. */
    val CERTIFICATE: Kind = 30315

    /** Ritual abandoned. Content is the reason, for showing to the group. */
    val FAILURE: Kind = 30316

    /** Every kind above, for filtering inbound payloads in one check. */
    val ALL: Set<Kind> = setOf(
        PROPOSAL,
        HOST_KEY,
        ROUND_1,
        COORDINATOR_ROUND_1,
        ROUND_2,
        CERTIFICATE,
        FAILURE
    )

    fun isDkgRitualKind(kind: Kind): Boolean = kind in ALL

    /**
     * Tags for a ritual message. The session id is on every kind so a late
     * message from an abandoned attempt can be dropped rather than mixed in.
     */
    fun assembleTags(
        sessionId: String,
        threshold: Int? = null,
        parentChatRoomId: String? = null,
        subject: String? = null,
        walletPolicy: SubgroupWalletPolicy? = null,
        guardianDelay: Long? = null
    ): Array<Array<String>> = buildList {
        add(DkgSessionIdTag.assemble(sessionId))
        threshold?.let { add(DkgThresholdTag.assemble(it)) }
        parentChatRoomId?.let { add(SubgroupParentTag.assemble(it)) }
        subject?.ifBlank { null }?.let { add(SubjectTag.assemble(it)) }
        walletPolicy?.let { add(WalletPolicyTag.assemble(it)) }
        guardianDelay?.let { add(GuardianDelayTag.assemble(it)) }
    }.toTypedArray()

    fun parseSessionId(tags: Array<Array<String>>): String? =
        tags.firstNotNullOfOrNull(DkgSessionIdTag::parse)?.sessionId

    fun parseThreshold(tags: Array<Array<String>>): Int? =
        tags.firstNotNullOfOrNull(DkgThresholdTag::parse)?.threshold

    /**
     * The group this ceremony claims to be making a subgroup of, or null for an
     * ordinary one.
     *
     * **This authenticates nothing.** Anybody can put any room id here, and
     * nothing about the parent is checked when it is read: `acceptProposal` files
     * it on the session and the ritual screen shows it, and that is the whole of
     * what it is for. The claim that decides anything is the birth certificate,
     * two steps later, which the parent's own quorum signs and which any device
     * can check against the parent's room id alone.
     *
     * The reason to carry it at all is that a member selected for a subgroup
     * ceremony would otherwise see a shared key ceremony open in a room they did
     * not ask for, with nothing saying what it is for. Being told is worth having
     * even when the telling is not evidence.
     */
    fun parseParentChatRoomId(tags: Array<Array<String>>): String? =
        tags.firstNotNullOfOrNull(SubgroupParentTag::parse)?.parentChatRoomId

    /**
     * What the room a ceremony runs in should be called.
     *
     * `NostrDao.getOrCreateNip17ChatRoom` already reads this off an arriving
     * payload -- it has always built the receiving side's room with
     * `subject = parseSubject()` -- and nothing on the ritual path ever wrote
     * one. So a proposal was the first thing anybody heard of the room, and it
     * arrived nameless: the member who created it saw the name they typed and
     * every other member saw an untitled chat with a key ceremony running in it.
     *
     * Read through the same `SubjectTag` NIP-14 uses, because it is the same tag
     * and the same field it lands in.
     */
    fun parseSubject(tags: Array<Array<String>>): String? =
        tags.firstNotNullOfOrNull(SubjectTag::parse)

    /**
     * The wallet policy the coordinator chose for the subgroup this ceremony
     * makes -- `docs/subgroup-wallet-policy.md`.
     *
     * Authenticates nothing, as [parseParentChatRoomId] does not: it is what the
     * admins are shown before they join, and what the certificate is later
     * proposed under. Absent for an ordinary ceremony and for one from an older
     * build; unreadable is told apart from absent, because a proposal whose
     * policy cannot be read is not one to join on the assumption it has none.
     */
    fun parseWalletPolicy(tags: Array<Array<String>>): WalletPolicyTag.Result =
        WalletPolicyTag.parseOne(tags)

    /**
     * The delay the coordinator chose for the parent's own leaf, beside a
     * guarded policy -- `docs/guardian-delay.md`. Authenticates nothing, as the
     * policy does not: it is what the admins are shown, and what the certificate
     * is proposed under.
     */
    fun parseGuardianDelay(tags: Array<Array<String>>): GuardianDelayTag.Result =
        GuardianDelayTag.parseOne(tags)
}
