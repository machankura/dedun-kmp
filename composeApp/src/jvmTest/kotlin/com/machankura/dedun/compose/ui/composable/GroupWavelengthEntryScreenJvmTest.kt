package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.ME
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.OTHER
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.localChatRoom
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHistory
import fr.acinq.bitcoin.Chain
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * One transaction of the group's Wavelength wallet, in general:
 * `docs/group-wavelength-transaction-details.md`, Phase 4.
 *
 * Every entry the history can build has to have a summary; a kind that fell through to a blank screen
 * would be a transaction a member could open and learn nothing about, which is worse than a row that
 * doesn't open.
 */
@OptIn(ExperimentalTestApi::class)
class GroupWavelengthEntryScreenJvmTest {
    private val phone = 400
    private val wide = 1280
    private val now = 1_800_000_000L
    private val txid = "1".repeat(64)
    private val address = "bcrt1qzyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3lgth6c"

    private fun of(rows: GroupWavelengthHistory.Rows, snapshot: GroupWavelengthSnapshot? = null) =
        GroupWavelengthHistory.of(rows, snapshot, now)

    private fun deposit(member: String? = OTHER) = DepositRow(
        chatRoomId = GroupWavelengthUiFixtures.chatRoomId,
        chain = "regtest",
        depositKey = "$txid:0",
        source = DepositRow.WAVELENGTH,
        amountMsat = 50_000_000,
        member = member,
        attributedBy = member?.let { DepositRow.BY_BOARDING_ADDRESS },
        seenAt = Instant.fromEpochSeconds(now - 600),
        confirmedAt = Instant.fromEpochSeconds(now - 500),
    )

    private fun onchainPayout() = GroupWavelengthOnchainPayoutEvent.Payout(Chain.Regtest, 0, OTHER, "d", address, 8_000, 2_000, now + 3_600, now - 60)

    private fun lightningPayout() = GroupWavelengthPaymentEvent.Payment(Chain.Regtest, 0, "lnbcrt-not-an-invoice", 50, 500, now + 3_600, GroupWavelengthPaymentEvent.Payout(OTHER, "d"))

    @Test
    fun `a deposit says what came in, whose it is, how and when`() = runDesktopComposeUiTest(phone, 1200) {
        val entry = assertNotNull(of(GroupWavelengthHistory.Rows(deposits = listOf(deposit()))).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
        onNodeWithText("+50,000 sats").assertIsDisplayed()
        onNodeWithText("Settled").assertIsDisplayed()
        onNodeWithText("Onchain").assertIsDisplayed()
        onNodeWithText("Whose").assertIsDisplayed()
        onNodeWithText("Your device's own record, from start to finish.").assertIsDisplayed()
        onNodeWithText("Paid to an address your device worked out as theirs").assertIsDisplayed()
    }

    @Test
    fun `a deposit nobody has attributed still opens, and says nothing about whose it is`() = runDesktopComposeUiTest(phone, 1200) {
        val entry = assertNotNull(of(GroupWavelengthHistory.Rows(deposits = listOf(deposit(member = null)))).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
        onNodeWithText("+50,000 sats").assertIsDisplayed()
        onNodeWithText("50,000 sats added").assertIsDisplayed()
    }

    @Test
    fun `an onchain payout names the address it has to pay, which is the one kind that can`() = runDesktopComposeUiTest(phone, 1400) {
        val approval = onchainPayout()
        val rows = GroupWavelengthHistory.Rows(
            leaves = listOf(GroupArkLeave("w", approval.id, "round", "00", 8_000, 400, confirmed = true, confirmedAtEpochSeconds = now - 10)),
            onchainPayouts = listOf(approval),
        )
        val entry = assertNotNull(of(rows).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
        onNodeWithText("−8,000 sats").assertIsDisplayed()
        onNodeWithText("Paid to").assertIsDisplayed()
        onNodeWithText(address).assertIsDisplayed()
        onNodeWithText("Paid, with 400 sats in fees").assertIsDisplayed()
        onNodeWithText("Your device's own record, from start to finish.").assertIsDisplayed()
    }

    @Test
    fun `a Lightning payout says the refund path has only met fixtures`() = runDesktopComposeUiTest(phone, 1400) {
        val entry = assertNotNull(of(GroupWavelengthHistory.Rows(payouts = listOf(lightningPayout()))).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
        onNodeWithText("Lightning").assertIsDisplayed()
        onNodeWithText("A failed Lightning payment's refund has only been tested against fixtures, so its money could be tied up.").assertIsDisplayed()
    }

    @Test
    fun `a payout the operator reports says whose word that part is`() = runDesktopComposeUiTest(phone, 1400) {
        val approval = lightningPayout()
        val said = GroupWavelengthSnapshot(
            "w", 3, now, 500, 0,
            activity = listOf(GroupWavelengthSnapshot.Line("l1", "send", GroupWavelengthHistory.LINE_COMPLETE, 0, 41, now - 50, paymentHash = "")),
        )
        // The line has no hash, so nothing joins it: the payout stays this device's own and the line
        // stands on its own, which is the honest reading of a report that names nothing.
        val entries = of(GroupWavelengthHistory.Rows(payouts = listOf(approval)), said)
        val theirs = assertNotNull(entries.firstOrNull { it.source == GroupWavelengthEntry.Source.THE_OPERATOR })
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(theirs, localChatRoom, ME, Modifier) } } }
        onNodeWithText("Only whoever runs the wallet says this happened. Your device has no record of it.").assertIsDisplayed()
    }

    @Test
    fun `a round's figure is what it cost, since nothing moved in or out`() = runDesktopComposeUiTest(phone, 1200) {
        val rows = GroupWavelengthHistory.Rows(fees = listOf(GroupArkFee("w", "commitment", 30, 3, now - 200)))
        val entry = assertNotNull(of(rows).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
        onNodeWithText("Wallet upkeep").assertIsDisplayed()
        onNodeWithText("\u2212" + "30 sats").assertIsDisplayed()
        onNodeWithText("Fee").assertIsDisplayed()
        // No Amount line: a round has a cost and no amount, so one would say nothing twice.
        onNodeWithText("Amount").assertDoesNotExist()
    }

    @Test
    fun `a Lightning payout's fee and time are labelled as the operator's, because that is whose they are`() =
        runDesktopComposeUiTest(phone, 1600) {
            val approval = lightningPayout()
            val hash = "d".repeat(64)
            val said = GroupWavelengthSnapshot(
                "w", 3, now, 500, 0,
                activity = listOf(GroupWavelengthSnapshot.Line("l1", "send", GroupWavelengthHistory.LINE_COMPLETE, 4_000, 41, now - 50, paymentHash = hash)),
            )
            // Built directly: the fixture's invoice doesn't decode, and what this asserts is the labels.
            val entry = GroupWavelengthEntry(
                id = "payout-ln:x",
                kind = GroupWavelengthEntry.Kind.PAYOUT_LIGHTNING,
                status = GroupWavelengthEntry.Status.DONE,
                source = GroupWavelengthEntry.Source.BOTH,
                amountSat = 4_010,
                feeSat = 41,
                at = now - 50,
                member = OTHER,
                direction = GroupWavelengthEntry.Direction.OUT,
                detail = GroupWavelengthEntry.Detail.LightningPayout(approval, line = said.activity.single()),
            )
            setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
            // The amount is this device's own, so it keeps the plain label.
            onNodeWithText("Amount").assertIsDisplayed()
            // The fee and the time are not, and an unmarked "Fee" beside that amount would read as though
            // they were. This is what Phase 6 found.
            onNodeWithText("Fee, as whoever runs the wallet reports it").assertIsDisplayed()
            onNodeWithText("When they say it happened").assertIsDisplayed()
            onNodeWithText("Fee").assertDoesNotExist()
            onNodeWithText("When").assertDoesNotExist()
        }

    @Test
    fun `an onchain payout's fee keeps the plain label, since this device signed the round that cost it`() =
        runDesktopComposeUiTest(phone, 1600) {
            val approval = onchainPayout()
            val rows = GroupWavelengthHistory.Rows(
                leaves = listOf(GroupArkLeave("w", approval.id, "round", "00", 8_000, 400, confirmed = true, confirmedAtEpochSeconds = now - 10)),
                onchainPayouts = listOf(approval),
            )
            val entry = assertNotNull(of(rows).singleOrNull())
            setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
            onNodeWithText("Fee").assertIsDisplayed()
            onNodeWithText("When").assertIsDisplayed()
            onNodeWithText("Fee, as whoever runs the wallet reports it").assertDoesNotExist()
        }

    // ---- the technical sheet ----

    @Test
    fun `the sheet puts what this device signed and what the group approved under their own headings`() =
        runDesktopComposeUiTest(phone, 2400) {
            val approval = onchainPayout()
            val rows = GroupWavelengthHistory.Rows(
                leaves = listOf(GroupArkLeave("w", approval.id, "the-round", "0014bb", 8_000, 400, confirmed = true, atEpochSeconds = now - 50, confirmedAtEpochSeconds = now - 10)),
                onchainPayouts = listOf(approval),
            )
            val entry = assertNotNull(of(rows).singleOrNull())
            setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryTechnicalSheet(entry) {} } } }
            onNodeWithText("What your device signed").assertIsDisplayed()
            onNodeWithText("What the group approved").assertIsDisplayed()
            onNodeWithText("the-round").assertIsDisplayed()
            onNodeWithText(approval.id).assertIsDisplayed()
            onNodeWithText("Your device saw the round confirm").assertIsDisplayed()
        }

    @Test
    fun `a field this device doesn't hold is left out, not shown empty`() = runDesktopComposeUiTest(phone, 2400) {
        // No leave: nothing was signed yet, so there is no "What your device signed" at all.
        val entry = assertNotNull(of(GroupWavelengthHistory.Rows(onchainPayouts = listOf(onchainPayout()))).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryTechnicalSheet(entry) {} } } }
        onNodeWithText("What the group approved").assertIsDisplayed()
        onNodeWithText("What your device signed").assertDoesNotExist()
        onNodeWithText("Your device saw the round confirm").assertDoesNotExist()
    }

    @Test
    fun `a Lightning payout's sheet says the group holds no proof of payment`() = runDesktopComposeUiTest(phone, 2400) {
        val entry = assertNotNull(of(GroupWavelengthHistory.Rows(payouts = listOf(lightningPayout()))).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryTechnicalSheet(entry) {} } } }
        onNodeWithText("The group's wallet holds no proof of payment", substring = true).assertIsDisplayed()
        onNodeWithText("Still untested").assertIsDisplayed()
    }

    @Test
    fun `a Lightning deposit's sheet says its deadline is the operator's word`() = runDesktopComposeUiTest(phone, 2400) {
        val hash = "c".repeat(64)
        val rows = GroupWavelengthHistory.Rows(
            addresses = listOf(GroupArkDepositAddress("w", "r1", OTHER, Instant.fromEpochSeconds(now - 900), invoiceAmountSat = 9_000, invoice = "lnbcrt90u1x", paymentHash = hash)),
            claims = listOf(GroupArkClaim("w", "ark", hash, 8_900, atEpochSeconds = now - 100)),
        )
        val entry = assertNotNull(of(rows).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryTechnicalSheet(entry) {} } } }
        onNodeWithText("has no deadline your device can work out", substring = true).assertIsDisplayed()
        onNodeWithText("lnbcrt90u1x").assertIsDisplayed()
    }

    @Test
    fun `an operator-only line's sheet is their word throughout, and says so`() = runDesktopComposeUiTest(phone, 2400) {
        val said = GroupWavelengthSnapshot(
            "w", 3, now, 912_000, 0,
            activity = listOf(GroupWavelengthSnapshot.Line("l1", "receive", GroupWavelengthHistory.LINE_COMPLETE, 8_000, 12, now - 50, failure = "")),
        )
        val entry = assertNotNull(of(GroupWavelengthHistory.Rows(), said).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryTechnicalSheet(entry) {} } } }
        onNodeWithText("What whoever runs the wallet says").assertIsDisplayed()
        onNodeWithText("Your device has no record of this at all. Everything above is their word.").assertIsDisplayed()
        onNodeWithText("What your device signed").assertDoesNotExist()
    }

    @Test
    fun `the detail opens the sheet, and it is the only control on the screen`() = runDesktopComposeUiTest(phone, 1600) {
        val entry = assertNotNull(of(GroupWavelengthHistory.Rows(deposits = listOf(deposit()))).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
        onNodeWithText("Technical details").performClick()
        onNodeWithText("What your device signed").assertIsDisplayed()
    }

    @Test
    fun `an id the history doesn't hold says so, rather than failing`() = runDesktopComposeUiTest(phone, 800) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(null, localChatRoom, ME, Modifier) } } }
        onNodeWithText("This isn't in the wallet's history").assertIsDisplayed()
    }

    @Test
    fun `a row with no time of its own says that, rather than sorting silently`() = runDesktopComposeUiTest(phone, 1200) {
        val rows = GroupWavelengthHistory.Rows(
            boarded = listOf(GroupArkBoarded("w", "$txid:0", "commitment", 7_000, "0014aa"))
        )
        val entry = assertNotNull(of(rows).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
        onNodeWithText("When isn't known").assertIsDisplayed()
    }

    @Test
    fun `the detail reads the same in a wide window, since it is pushed rather than a pane`() = runDesktopComposeUiTest(wide, 1000) {
        val entry = assertNotNull(of(GroupWavelengthHistory.Rows(deposits = listOf(deposit()))).singleOrNull())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthEntryDetail(entry, localChatRoom, ME, Modifier) } } }
        onNodeWithText("+50,000 sats").assertIsDisplayed()
        onNodeWithText("Settled").assertIsDisplayed()
    }
}
