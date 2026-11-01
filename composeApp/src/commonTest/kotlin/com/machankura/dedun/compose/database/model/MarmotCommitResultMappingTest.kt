package com.machankura.dedun.compose.database.model

import com.vitorpamplona.quartz.marmot.mls.messages.CommitResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

/**
 * Where a commit's bytes land when the row that records it is written.
 *
 * `MarmotCommitResult` carries quartz's `CommitResult` payload fields verbatim --
 * `commitBytes`, `welcomeBytes`, `groupInfoBytes`, `framedCommitBytes`,
 * `preCommitExporterSecret`, the same names and all of them `ByteArray`. A value
 * taken from the wrong field of the right object therefore typechecks, and
 * `framedCommitBytes = commitResult.preCommitExporterSecret` reached the database
 * that way and sat there unnoticed: the column documented to hold a broadcastable
 * `MlsMessage(PublicMessage(FramedContent(commit)))` envelope held 32 bytes of the
 * group's pre-commit exporter secret instead.
 *
 * Nothing caught it because nothing read the column. The bytes that reached the
 * relay come off the in-memory `CommitResult`, so the wire stayed correct while the
 * record of it did not, and the row is written precisely so that the
 * acknowledgement path in `DatabaseNostrRepository` can pick work back up later. A
 * rebroadcast reading `framedCommitBytes` would have published noise the group
 * decrypts, fails to parse, and drops -- silent, which is this subsystem's
 * characteristic failure.
 *
 * So the routing is pinned here. Every payload gets a distinct, self-identifying
 * value: a field that ends up in the wrong column names both halves of the mistake
 * when it fails, rather than comparing equal by accident.
 */
class MarmotCommitResultMappingTest {
    private val commitBytes = "raw-commit".encodeToByteArray()
    private val framedCommitBytes = "framed-commit-envelope".encodeToByteArray()
    private val welcomeBytes = "welcome".encodeToByteArray()
    private val groupInfoBytes = "group-info".encodeToByteArray()

    /** Stands in for `MLS-Exporter("marmot", "group-event", 32)` at the pre-commit epoch. */
    private val preCommitExporterSecret = ByteArray(32) { 0x5E }

    private val commitEventId = "a".repeat(64)
    private val chatRoomId = "b".repeat(64)
    private val userPublicKey = "c".repeat(64)
    private val peerKeyPackageEventId = "d".repeat(64)
    private val createdAt = Instant.fromEpochSeconds(1_700_000_000)

    private fun commitResult(
        framedCommitBytes: ByteArray = this.framedCommitBytes,
        preCommitExporterSecret: ByteArray = this.preCommitExporterSecret,
    ) = CommitResult(
        commitBytes = commitBytes,
        welcomeBytes = welcomeBytes,
        groupInfoBytes = groupInfoBytes,
        framedCommitBytes = framedCommitBytes,
        preCommitExporterSecret = preCommitExporterSecret,
    )

    private fun map(commitResult: CommitResult) = MarmotCommitResult.from(
        commitEventId = commitEventId,
        commitResult = commitResult,
        chatRoomId = chatRoomId,
        userPublicKey = userPublicKey,
        peerKeyPackageEventId = peerKeyPackageEventId,
        isOneMemberInitialGroupCreation = false,
        createdAt = createdAt,
    )

    @Test
    fun `every payload field lands in its own column`() {
        val row = map(commitResult())

        assertContentEquals(commitBytes, row.commitBytes, "commitBytes")
        assertContentEquals(welcomeBytes, row.welcomeBytes, "welcomeBytes")
        assertContentEquals(groupInfoBytes, row.groupInfoBytes, "groupInfoBytes")
        assertContentEquals(framedCommitBytes, row.framedCommitBytes, "framedCommitBytes")
        assertContentEquals(
            preCommitExporterSecret,
            row.preCommitExporterSecret,
            "preCommitExporterSecret"
        )
    }

    @Test
    fun `the framed commit column never holds the exporter secret`() {
        // The regression. Stated as the invariant rather than as an equality check,
        // so it keeps holding for a CommitResult this test did not anticipate.
        val row = map(commitResult())

        assertFalse(
            row.framedCommitBytes.contentEquals(row.preCommitExporterSecret),
            "the group's exporter secret was stored as the framed commit"
        )
    }

    @Test
    fun `a CommitResult that never framed its commit still stores a commit`() {
        // quartz defaults framedCommitBytes to commitBytes, and the entity repeats that
        // default. Whichever of the two a row ends up with, it must be a commit -- the
        // fallback must not quietly become the secret either.
        val unframed = CommitResult(
            commitBytes = commitBytes,
            welcomeBytes = welcomeBytes,
            groupInfoBytes = groupInfoBytes,
            preCommitExporterSecret = preCommitExporterSecret,
        )

        val row = map(unframed)

        assertContentEquals(commitBytes, row.framedCommitBytes)
        assertFalse(
            row.framedCommitBytes.contentEquals(row.preCommitExporterSecret),
            "the group's exporter secret was stored as the framed commit"
        )
    }

    @Test
    fun `the bookkeeping the acknowledgement path reads is carried through`() {
        // DatabaseNostrRepository finds this row by the commit event's id and delivers the
        // welcome using chatRoomId, userPublicKey and peerKeyPackageEventId. All four are
        // supplied by the caller rather than the CommitResult, so they are checked here to
        // keep the argument order of `from` honest -- every one of them is a 64-char hex
        // string, and swapping two would otherwise typecheck as silently as the bug did.
        val row = map(commitResult())

        assertEquals(commitEventId, row.id)
        assertEquals(chatRoomId, row.chatRoomId)
        assertEquals(userPublicKey, row.userPublicKey)
        assertEquals(peerKeyPackageEventId, row.peerKeyPackageEventId)
        assertEquals(createdAt, row.createdAt)
        assertFalse(row.isOneMemberInitialGroupCreation)
    }
}
