package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.managers.LineageExample.heldBy
import com.machankura.dedun.compose.managers.LineageExample.m
import com.machankura.dedun.compose.managers.LineageExample.n
import com.machankura.dedun.compose.managers.LineageExample.p
import com.machankura.dedun.compose.managers.LineageExample.r
import com.machankura.dedun.compose.managers.LineageExample.rent
import com.machankura.dedun.compose.managers.LineageExample.rentGuardians
import com.machankura.dedun.compose.managers.LineageExample.savings
import com.machankura.dedun.compose.managers.LineageExample.savingsGuardians
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which group directly guards a member's wallet, and which branch of an
 * ancestor's tree it sits under -- read off the guardians and the line of
 * descent a verified certificate already gives. `docs/member-wallet-guarding-group.md`, Phase 1.
 */
class GuardedWalletLineageTest {
    @Test
    fun `the root sees Savings guarded by M, two generations down through P`() {
        val wallet = heldBy(r, savings, savingsGuardians)

        assertEquals(m, wallet.directGuardianChatRoomId)
        assertEquals(p, wallet.branchChatRoomId)
        assertEquals(2, wallet.position)
        assertFalse(wallet.isGuardedHere)
    }

    @Test
    fun `the root sees Rent guarded by its own child N, which is also the branch`() {
        val wallet = heldBy(r, rent, rentGuardians)

        assertEquals(n, wallet.directGuardianChatRoomId)
        assertEquals(n, wallet.branchChatRoomId)
        assertEquals(1, wallet.position)
    }

    @Test
    fun `the middle group sees the same guardian, with M as its branch`() {
        val wallet = heldBy(p, savings, savingsGuardians)

        assertEquals(m, wallet.directGuardianChatRoomId)
        assertEquals(m, wallet.branchChatRoomId)
    }

    @Test
    fun `the group that signed the certificate guards it here, and has no branch`() {
        val wallet = heldBy(m, savings, savingsGuardians)

        assertEquals(m, wallet.directGuardianChatRoomId)
        assertTrue(wallet.isGuardedHere)
        assertNull(wallet.branchChatRoomId)
    }
}
