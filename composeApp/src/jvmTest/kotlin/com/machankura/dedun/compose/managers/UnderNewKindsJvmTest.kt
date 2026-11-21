package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.MovedKinds
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEventJvmTest
import com.machankura.dedun.compose.nostr.wallet.DepositStatementsJvmTest
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEventJvmTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/*
 * The wallet suites again, with every moved kind written under its new number:
 * docs/giza-into-dedun.md, Phase K, K1's "a copy of each, run with the new numbers on the way in".
 *
 * The suites themselves run as K1 does -- every device writes the legacy number -- and these
 * subclasses run them as K2 will, so a K1 device is shown to read everything a K2 device sends.
 * [GroupArkCoSigningAcrossReleasesJvmTest] runs the two side by side in one group.
 */

private fun writeNew() { MovedKinds.writesNew = true }
private fun writeLegacy() { MovedKinds.writesNew = false }

class GroupArkCoSigningUnderNewKindsJvmTest : GroupArkCoSigningJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

class MemberTransfersUnderNewKindsJvmTest : MemberTransfersJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

class DepositAttributionUnderNewKindsJvmTest : DepositAttributionJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

class MemberBalancesUnderNewKindsJvmTest : MemberBalancesJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

class GroupWavelengthPayoutsUnderNewKindsJvmTest : GroupWavelengthPayoutsJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

class GroupWavelengthWalletUnderNewKindsJvmTest : GroupWavelengthWalletJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

class MemberTransferEventUnderNewKindsJvmTest : MemberTransferEventJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

class CoinAssignmentEventUnderNewKindsJvmTest : CoinAssignmentEventJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

class DepositStatementsUnderNewKindsJvmTest : DepositStatementsJvmTest() {
    @BeforeTest fun newNumbers() = writeNew()
    @AfterTest fun legacyNumbers() = writeLegacy()
}

/**
 * One group, two releases: devices 1 and 3 write the new numbers and devices 0 and 2 the legacy
 * ones, every time each acts on a message. The whole co-signing suite has to pass so, which is the
 * K1 promise -- a device that reads both hears a device that has already moved.
 */
class GroupArkCoSigningAcrossReleasesJvmTest : GroupArkCoSigningJvmTest() {
    @BeforeTest
    fun mixReleases() {
        val inner = fixture.dispatch
        fixture.dispatch = { to, event ->
            val was = MovedKinds.writesNew
            MovedKinds.writesNew = to.index % 2 == 1
            try {
                inner(to, event)
            } finally {
                MovedKinds.writesNew = was
            }
        }
    }

    @AfterTest fun legacyNumbers() = writeLegacy()
}
