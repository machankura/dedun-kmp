package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.PublicShare
import fr.acinq.bitcoin.crypto.iceberg.Share
import fr.acinq.bitcoin.utils.Either

/**
 * A distributed key generation for Iceberg, which ships only a trusted dealer.
 *
 * An Iceberg share is replicated secret sharing, not a Shamir point: every
 * `(t-1)`-subset `T` of the participants has its own 32-byte seed, held by every
 * participant *outside* `T`, and a participant's share is the seeds of the
 * subsets it is outside of, in the lexicographic rank order `rss.h` fixes. So a
 * DKG only has to agree each seed among its holders without any member of `T`
 * learning it:
 *
 * ```
 * round 1  each holder j of each T:  c[j,T] = random,  publish h[j,T] = H(commit, sid, T, j, c[j,T])
 * round 2  send c[j,T] to every other holder of T, encrypted to them
 * round 3  each k checks every c[j,T] it was sent against h[j,T], then
 *            s[T]  = H(seed, sid, T, c[j,T] for each holder j, ascending)
 *            share = 1 | n | t | k | s[T] for each T not containing k, in rank order
 *          and publishes its public share and a signature over the transcript
 * done     Iceberg.groupPublicKey over all n public shares
 * ```
 *
 * Why it is safe, in the three sentences it takes:
 *
 * - **Every seed is random.** `T` has `n - t + 1 >= t` holders and at most
 *   `t - 1` members are corrupt, so some holder's contribution is honest.
 * - **Every seed is secret from `T`.** Its contributions only go to its holders,
 *   so a coalition of `t - 1` lacks exactly the seed named after itself, which
 *   is the scheme's whole margin (`rss.h`).
 * - **Nobody chooses last.** Contributions are committed before any is revealed,
 *   so a contributor cannot grind the group key.
 *
 * A contributor who reveals something other than what it committed to is named
 * ([Fault.BadReveal]). One who commits to two different values in front of two
 * different members is not caught here; the transcript signature is what catches
 * that, because the two members then publish different transcript hashes.
 *
 * ### Repair, which RSS gives away
 *
 * Every seed a participant holds is also held by `n - t` others. A participant
 * that has lost its share can be sent its seeds again ([repairSeeds]); the share
 * it rebuilds ([repair]) must reproduce the public share it published, which is
 * the check on what it was sent. That replaces ChillDKG's recovery data.
 *
 * ### What this is not
 *
 * Reviewed. `iceberg.md` says the scheme must not protect anything of value, and
 * this protocol is newer than the scheme. It lives in the app rather than in the
 * bitcoin-kmp fork beside [Iceberg] only because it needs nothing from inside
 * the library; see the build table in `docs/group-lightning-wallet.md`.
 *
 * Every function here is pure over inputs the ritual has stored, as
 * [fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG]'s are, so a ceremony resumes from
 * its persisted messages after the app is killed.
 */
object IcebergDkg {
    /** `SECP256K1_ICEBERG_MAX_PARTICIPANTS`, which sizes the library's ABI. */
    const val MAX_PARTICIPANTS = 10

    private const val TAG_SESSION = "bennu/iceberg-dkg/session/v1"
    private const val TAG_CONTRIBUTION = "bennu/iceberg-dkg/contribution/v1"
    private const val TAG_COMMIT = "bennu/iceberg-dkg/commit/v1"
    private const val TAG_SEED = "bennu/iceberg-dkg/seed/v1"
    private const val TAG_TRANSCRIPT = "bennu/iceberg-dkg/transcript/v1"

    /** The share format version `secp256k1_iceberg_share_parse` accepts. */
    private const val SHARE_VERSION: Byte = 1

    /**
     * Whether a group of [n] with threshold [t] can be *expressed* by Iceberg:
     * a round-one quorum of `2t-1` has to exist.
     */
    fun isExpressible(n: Int, t: Int): Boolean = n in 1..MAX_PARTICIPANTS && t >= 1 && t <= (n + 1) / 2

    /**
     * Whether it can be *deployed*: expressible, a real threshold, and big
     * enough for the group to agree what it signs while `t - 1` members
     * misbehave, `n >= 3t - 2` (`secp256k1_iceberg.h`, constraint 3). 2-of-4 is
     * the smallest group this admits, and 4-of-10 the largest threshold.
     */
    fun isDeployable(n: Int, t: Int): Boolean = isExpressible(n, t) && t >= 2 && n >= 3 * t - 2

    /** Every threshold a group of [n] could deploy, smallest first. */
    fun deployableThresholds(n: Int): List<Int> = (2..n).filter { isDeployable(n, it) }

    /**
     * A ceremony's parameters, the same on every device.
     *
     * [hostKeys] are sorted bytewise and participant `k` is `hostKeys[k - 1]`,
     * for the reason the ChillDKG ritual sorts its own: an order every device
     * reaches without being told. [sessionId] binds all of it, so a device
     * that ordered the participants differently computes another session and
     * gets no key rather than a weaker one.
     */
    class Params private constructor(
        val n: Int,
        val t: Int,
        val hostKeys: List<PublicKey>,
        val sessionId: ByteVector32,
    ) {
        /** The `(t-1)`-subsets, as bitmasks over participants 1..n, in rank order. */
        val subsets: List<Int> = subsetsInRankOrder(n, t - 1)

        fun holders(subset: Int): List<Int> = (1..n).filter { subset and (1 shl it) == 0 }

        fun isHolder(participant: Int, subset: Int): Boolean = subset and (1 shl participant) == 0

        /** The ranks whose seeds [participant] holds, ascending: its share's layout. */
        fun heldRanks(participant: Int): List<Int> = subsets.indices.filter { isHolder(participant, subsets[it]) }

        fun indexOf(hostKey: PublicKey): Int? = hostKeys.indexOf(hostKey).takeIf { it >= 0 }?.plus(1)

        companion object {
            /**
             * @param ceremonyId the proposal's own id, so two ceremonies over the
             *   same people and threshold are two sessions.
             */
            fun of(t: Int, hostKeys: Collection<PublicKey>, ceremonyId: ByteVector32): Params {
                val sorted = hostKeys.distinct().sortedWith { a, b -> compareBytes(a.value.toByteArray(), b.value.toByteArray()) }
                require(sorted.size == hostKeys.size) { "a participant appears twice" }
                require(isDeployable(sorted.size, t)) { "$t-of-${sorted.size} is not a deployable Iceberg group" }
                val sid = Crypto.taggedHash(
                    byteArrayOf(sorted.size.toByte(), t.toByte()) + sorted.flatMap { it.value.toByteArray().asList() } + ceremonyId.toByteArray(),
                    TAG_SESSION,
                )
                return Params(sorted.size, t, sorted, sid)
            }
        }
    }

    /** What went wrong, as a value: a faulty participant is an outcome of a DKG, not a bug in it. */
    sealed class Fault(val participant: Int?, message: String) : Exception(message) {
        class MissingCommitment(participant: Int) : Fault(participant, "participant $participant sent no commitment")
        class MissingReveal(participant: Int) : Fault(participant, "participant $participant revealed nothing to us")
        class BadReveal(participant: Int) : Fault(participant, "participant $participant revealed a value it had not committed to")
        class MalformedMessage(participant: Int, reason: String) : Fault(participant, "participant $participant sent a malformed message: $reason")
        class InconsistentShares(reason: String) : Fault(null, "the public shares do not lie on one polynomial: $reason")
        class RepairMismatch(reason: String) : Fault(null, "the repaired share does not reproduce the published public share: $reason")
    }

    /** One participant's round-1 message: a commitment per subset it holds, by rank. */
    data class Commitments(val participant: Int, val byRank: Map<Int, ByteVector32>)

    /**
     * [participant]'s contribution to the seed at [rank], derived from the
     * 32 random bytes it stored when it joined. Storing the root rather than the
     * contributions is what lets a ritual stay a function of its stored inputs.
     * The root is as secret as the share it becomes; drop it once the share is
     * assembled.
     */
    fun contribution(params: Params, participant: Int, root: ByteVector32, rank: Int): ByteVector32 {
        require(params.isHolder(participant, params.subsets[rank])) { "participant $participant does not hold the seed at rank $rank" }
        return Crypto.taggedHash(root.toByteArray() + params.sessionId.toByteArray() + Pack.writeInt32BE(rank) + byteArrayOf(participant.toByte()), TAG_CONTRIBUTION)
    }

    fun commitment(params: Params, participant: Int, rank: Int, contribution: ByteVector32): ByteVector32 =
        Crypto.taggedHash(params.sessionId.toByteArray() + Pack.writeInt32BE(rank) + byteArrayOf(participant.toByte()) + contribution.toByteArray(), TAG_COMMIT)

    /** Round 1: commit to every contribution, to be published to everyone. */
    fun round1(params: Params, participant: Int, root: ByteVector32): Commitments =
        Commitments(participant, params.heldRanks(participant).associateWith { commitment(params, participant, it, contribution(params, participant, root, it)) })

    /**
     * Round 2: what [participant] reveals to [recipient], by rank -- its
     * contributions to the seeds they both hold, and nothing else. The caller
     * encrypts it to the recipient; it must never travel in the clear.
     */
    fun round2(params: Params, participant: Int, root: ByteVector32, recipient: Int): Map<Int, ByteVector32> {
        require(participant != recipient) { "a participant does not reveal to itself" }
        return params.heldRanks(participant)
            .filter { params.isHolder(recipient, params.subsets[it]) }
            .associateWith { contribution(params, participant, root, it) }
    }

    /**
     * Round 3: check every reveal against its commitment and assemble this
     * participant's share.
     *
     * @param commitments every participant's round-1 message, own included.
     * @param reveals what every other participant revealed to this one, by sender.
     */
    fun assemble(
        params: Params,
        participant: Int,
        root: ByteVector32,
        commitments: Map<Int, Commitments>,
        reveals: Map<Int, Map<Int, ByteVector32>>,
    ): Either<Fault, Share> {
        for (j in 1..params.n) {
            val c = commitments[j] ?: return Either.Left(Fault.MissingCommitment(j))
            if (c.byRank.keys != params.heldRanks(j).toSet()) return Either.Left(Fault.MalformedMessage(j, "commitments for the wrong subsets"))
        }
        val seeds = params.heldRanks(participant).map { rank ->
            val contributions = params.holders(params.subsets[rank]).map { j ->
                val value = if (j == participant) {
                    contribution(params, participant, root, rank)
                } else {
                    reveals[j]?.get(rank) ?: return Either.Left(Fault.MissingReveal(j))
                }
                if (commitment(params, j, rank, value) != commitments.getValue(j).byRank.getValue(rank)) return Either.Left(Fault.BadReveal(j))
                value
            }
            seed(params, rank, contributions)
        }
        return Either.Right(share(params.n, params.t, participant, seeds))
    }

    /** The seed at [rank], from its holders' contributions in ascending participant order. */
    fun seed(params: Params, rank: Int, contributions: List<ByteVector32>): ByteVector32 =
        Crypto.taggedHash(params.sessionId.toByteArray() + Pack.writeInt32BE(rank) + contributions.flatMap { it.toByteArray().asList() }, TAG_SEED)

    /**
     * A share in `secp256k1_iceberg_share_serialize`'s encoding: version, n, t,
     * the participant's index, then its seeds in rank order. Which seed is which
     * follows from n, t and the index, so the order of [seeds] is the whole of
     * the layout and has to be [Params.heldRanks]'s.
     */
    fun share(n: Int, t: Int, participant: Int, seeds: List<ByteVector32>): Share {
        require(isExpressible(n, t) && participant in 1..n)
        require(seeds.size == binomial(n - 1, t - 1)) { "a share holds C(n-1, t-1) seeds" }
        return Share(byteArrayOf(SHARE_VERSION, n.toByte(), t.toByte(), participant.toByte()) + seeds.flatMap { it.toByteArray().asList() })
    }

    /**
     * The hash every participant publishes beside its public share: the parameters and every
     * commitment. Two members who were shown different commitments publish different hashes, and the
     * ritual refuses to finish on hashes that do not all agree.
     *
     * It cannot cover the public shares, which are published in the same message. Those are held
     * together by [groupPublicKey], which refuses a set that does not lie on one polynomial, and by
     * the wallet statement the group signs over the key they make (Phase 6), which a member whose
     * device computed a different key refuses to sign.
     */
    fun transcriptHash(params: Params, commitments: Map<Int, Commitments>): ByteVector32 {
        require(commitments.size == params.n)
        val bytes = buildList {
            addAll(params.sessionId.toByteArray().asList())
            for (j in 1..params.n) {
                val c = commitments.getValue(j)
                for (rank in params.heldRanks(j)) addAll(c.byRank.getValue(rank).toByteArray().asList())
            }
        }
        return Crypto.taggedHash(bytes.toByteArray(), TAG_TRANSCRIPT)
    }

    /**
     * The group key, from every participant's public share in participant
     * order. Using all `n` rather than a quorum is the point: `groupPublicKey`
     * checks the shares lie on one polynomial of degree `t-1`, and every share
     * past `t` is one more constraint a wrong one has to satisfy.
     */
    fun groupPublicKey(params: Params, publicShares: List<PublicShare>): Either<Fault, PublicKey> {
        if (publicShares.size != params.n) return Either.Left(Fault.InconsistentShares("expected ${params.n} public shares, got ${publicShares.size}"))
        return when (val key = Iceberg.groupPublicKey(publicShares, params.n, params.t)) {
            is Either.Right -> Either.Right(key.value)
            is Either.Left -> Either.Left(Fault.InconsistentShares(key.value.message ?: "rejected"))
        }
    }

    /**
     * What [helper] sends [participant] to repair its share: the seeds they
     * both hold. Encrypted to the participant, like a round-2 reveal.
     */
    fun repairSeeds(params: Params, helper: Int, helperShare: Share, participant: Int): Map<Int, ByteVector32> {
        require(helper != participant)
        val bytes = helperShare.toByteArray()
        require(bytes[3].toInt() == helper) { "that is not the helper's share" }
        return params.heldRanks(helper).withIndex()
            .filter { (_, rank) -> params.isHolder(participant, params.subsets[rank]) }
            .associate { (i, rank) -> rank to ByteVector32(bytes.copyOfRange(4 + 32 * i, 36 + 32 * i)) }
    }

    /**
     * Rebuild [participant]'s share from what helpers sent ([repairSeeds]).
     * Where several helpers sent one seed they must agree, and the result must
     * reproduce [publishedPublicShare] -- a wrong seed from anybody fails here
     * rather than at a signature.
     */
    fun repair(params: Params, participant: Int, received: Map<Int, Map<Int, ByteVector32>>, publishedPublicShare: PublicShare): Either<Fault, Share> {
        val seeds = params.heldRanks(participant).map { rank ->
            val offered = received.values.mapNotNull { it[rank] }.toSet()
            when (offered.size) {
                0 -> return Either.Left(Fault.RepairMismatch("nobody sent the seed at rank $rank"))
                1 -> offered.single()
                else -> return Either.Left(Fault.RepairMismatch("helpers disagree on the seed at rank $rank"))
            }
        }
        val share = share(params.n, params.t, participant, seeds)
        if (Iceberg.publicShare(share, null) != publishedPublicShare) return Either.Left(Fault.RepairMismatch("public share differs"))
        return Either.Right(share)
    }

    /** All [size]-subsets of 1..[n] as bitmasks (bit j for participant j), in `rss.h`'s lexicographic rank order. */
    fun subsetsInRankOrder(n: Int, size: Int): List<Int> {
        val out = mutableListOf<Int>()
        fun walk(next: Int, remaining: Int, mask: Int) {
            if (remaining == 0) { out += mask; return }
            for (j in next..(n - remaining + 1)) walk(j + 1, remaining - 1, mask or (1 shl j))
        }
        walk(1, size, 0)
        return out
    }

    fun binomial(n: Int, k: Int): Int {
        if (k < 0 || k > n) return 0
        var r = 1L
        for (i in 0 until k) r = r * (n - i) / (i + 1)
        return r.toInt()
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size - b.size
    }
}
