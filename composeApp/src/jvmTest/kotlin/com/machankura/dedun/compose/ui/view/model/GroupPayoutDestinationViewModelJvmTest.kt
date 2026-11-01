package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.network.LightningAddressResolver
import com.machankura.dedun.compose.network.LightningAddressResolver.Resolution
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.Sendable
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.ui.view.model.GroupPayoutDestinationViewModel.LightningProblem
import com.machankura.dedun.compose.ui.view.model.GroupPayoutDestinationViewModel.OnchainProblem
import com.machankura.dedun.compose.ui.view.state.GroupPayoutDestinationUIState
import com.machankura.dedun.compose.wallet.UnusedAddressCheck.Usage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.phoenix.data.lnurl.LnurlPay
import fr.acinq.secp256k1.Hex
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Setting where a group pays this profile -- `docs/group-payout-destination.md`, Phase 4. The
 * repository is a fake: what is under test is what the screen fills in, what it refuses and
 * under which field, and what it sends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupPayoutDestinationViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chain = Chain.Regtest
    private val chatRoomId = "9a17".repeat(16)
    private val me = PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0'))))
    private val fresh = Bitcoin.computeBIP86Address(PrivateKey(ByteVector32(Hex.decode("f1".padStart(64, '0')))).publicKey(), chain.chainHash)

    private val intent = LnurlPay.Intent(
        initialUrl = Url("https://example.com/.well-known/lnurlp/alice"),
        callback = Url("https://example.com/callback"),
        minSendable = MilliSatoshi(1_000),
        maxSendable = MilliSatoshi(100_000_000),
        metadata = LnurlPay.Intent.Metadata(raw = "[]", plainText = "", longDesc = null, imagePng = null, imageJpg = null, identifier = null, email = null, unknown = null),
        maxCommentLength = null,
    )

    private class Fake(
        var standing: PayoutDestinationRepository.Standing?,
        var resolution: Resolution,
        var usage: Usage = Usage.Unused,
    ) : PayoutDestinationRepository {
        data class Set(val lightning: LightningAddress, val sendable: Sendable?, val onchain: String?)
        val sets = mutableListOf<Set>()
        var removals = 0
        var resolved = 0
        var checked = 0

        override suspend fun standing(chatRoomId: String, chain: Chain) = standing
        override suspend fun destinationsIn(chatRoomId: String, chain: Chain): Map<HexKey, GroupPayoutDestination> = emptyMap()
        override suspend fun resolve(address: LightningAddress): Resolution = resolution.also { resolved++ }
        override suspend fun usageOf(chatRoomId: String, chain: Chain, script: ByteVector): Usage = usage.also { checked++ }
        override suspend fun invoiceFor(destination: GroupPayoutDestination, amount: MilliSatoshi, lightningChain: Chain) =
            LightningAddressResolver.PayoutInvoice.Unreachable
        override suspend fun set(chatRoomId: String, chain: Chain, lightningAddress: LightningAddress, sendable: Sendable?, onchainAddress: String?) {
            sets += Set(lightningAddress, sendable, onchainAddress)
        }
        override suspend fun remove(chatRoomId: String, chain: Chain) {
            removals++
        }
    }

    private fun standing(current: GroupPayoutDestination? = null, lud16: String? = null, canSet: Boolean = true, isGroup: Boolean = true) =
        PayoutDestinationRepository.Standing(isGroup = isGroup, canSet = canSet, current = current, profileLightningAddress = lud16)

    private fun destination(onchain: String? = null) = GroupPayoutDestinationEvent.verified(
        GroupPayoutDestinationEvent.build(me, chatRoomId, chain, LightningAddress("alice", "example.com"), onchainAddress = onchain)
    ) as GroupPayoutDestination

    private fun loaded(fake: Fake) = GroupPayoutDestinationViewModel(chatRoomId, chain, GroupPayoutDestinationUIState.Loading, fake).apply {
        initiate()
        runBlocking { withTimeout(5_000) { while (uiState !is GroupPayoutDestinationUIState.Loaded) delay(10) } }
    }

    private fun GroupPayoutDestinationViewModel.awaitIdle() = runBlocking {
        delay(20)
        withTimeout(5_000) { while (isWorking) delay(10) }
    }

    private fun GroupPayoutDestinationViewModel.type(lightning: String, onchain: String = "") {
        lightningField.edit { replace(0, length, lightning) }
        onchainField.edit { replace(0, length, onchain) }
    }

    @Test
    fun `the first time, the Lightning field starts from the profile's own address`() {
        val viewModel = loaded(Fake(standing(lud16 = "Alice@Example.com"), Resolution.Resolved(intent)))
        assertEquals("alice@example.com", viewModel.lightningField.text.toString())
        assertTrue(viewModel.lightningFromProfile)
    }

    @Test
    fun `a profile address that is not one is not filled in`() {
        val viewModel = loaded(Fake(standing(lud16 = "lnurl1dp68gurn8ghj7"), Resolution.Resolved(intent)))
        assertEquals("", viewModel.lightningField.text.toString())
        assertFalse(viewModel.lightningFromProfile)
    }

    @Test
    fun `an existing destination fills both fields, not the profile's`() {
        val viewModel = loaded(Fake(standing(current = destination(onchain = fresh), lud16 = "other@example.com"), Resolution.Resolved(intent)))
        assertEquals("alice@example.com", viewModel.lightningField.text.toString())
        assertEquals(fresh, viewModel.onchainField.text.toString())
        assertFalse(viewModel.lightningFromProfile)
        assertTrue(viewModel.canRemove())
    }

    @Test
    fun `saving checks both and sends one destination, with the range the address gave`() {
        val fake = Fake(standing(), Resolution.Resolved(intent))
        val viewModel = loaded(fake)
        viewModel.type("alice@example.com", fresh)
        var notChecked: Boolean? = null
        viewModel.save(onSaved = { notChecked = it }, onFailure = {})
        viewModel.awaitIdle()

        assertEquals(listOf(Fake.Set(LightningAddress("alice", "example.com"), Sendable(MilliSatoshi(1_000), MilliSatoshi(100_000_000)), fresh)), fake.sets)
        assertEquals(1, fake.resolved)
        assertEquals(1, fake.checked)
        assertEquals(false, notChecked)
    }

    @Test
    fun `no onchain address is no onchain check`() {
        val fake = Fake(standing(), Resolution.Resolved(intent))
        val viewModel = loaded(fake)
        viewModel.type("alice@example.com")
        viewModel.save(onSaved = {}, onFailure = {})
        viewModel.awaitIdle()
        assertEquals(0, fake.checked)
        assertNull(fake.sets.single().onchain)
    }

    @Test
    fun `Tor on saves without a range, and says it was not checked`() {
        val fake = Fake(standing(), Resolution.NotCheckedTor)
        val viewModel = loaded(fake)
        viewModel.type("alice@example.com")
        var notChecked: Boolean? = null
        viewModel.save(onSaved = { notChecked = it }, onFailure = {})
        viewModel.awaitIdle()
        assertEquals(true, notChecked)
        assertNull(fake.sets.single().sendable)
    }

    @Test
    fun `what is not a Lightning address is refused before anything is asked`() {
        listOf(
            "" to LightningProblem.Empty,
            "alice" to LightningProblem.NotAnAddress,
            "lnurl1dp68gurn8ghj7" to LightningProblem.Lnurl,
            "lno1pg257enxv4ezqcneype82um50ynhxgrwdajx283qfwdpl28qqmc78ymlvhmxcsywdk5wrjnj36jryg488qwlrnzyjczs" to LightningProblem.Offer,
            "lnbc2500u1pvjluezpp5qqqsyqcyq5rqwzq" to LightningProblem.Invoice,
            "₿alice@example.com" to LightningProblem.Bip353,
            "alice@pay2wqdkbyb6j4evl6fbhoy7n5l5yoofkjg7i6fmhkhtpt2vqfp3ad.onion" to LightningProblem.OnionDomain,
        ).forEach { (input, problem) ->
            val fake = Fake(standing(), Resolution.Resolved(intent))
            val viewModel = loaded(fake)
            viewModel.type(input)
            viewModel.save(onSaved = {}, onFailure = {})
            assertEquals(problem, viewModel.lightningProblem, input)
            assertEquals(0, fake.resolved, input)
            assertTrue(fake.sets.isEmpty(), input)
        }
    }

    @Test
    fun `what the address's server says is refused under the Lightning field`() {
        listOf(
            Resolution.Unreachable to LightningProblem.Unreachable,
            Resolution.NotFound to LightningProblem.NotFound,
            Resolution.Refused("no such user") to LightningProblem.Refused("no such user"),
            Resolution.NotAPayRequest to LightningProblem.NotAPayRequest,
            Resolution.Malformed("an http callback") to LightningProblem.Malformed,
        ).forEach { (resolution, problem) ->
            val fake = Fake(standing(), resolution)
            val viewModel = loaded(fake)
            viewModel.type("alice@example.com")
            viewModel.save(onSaved = {}, onFailure = {})
            viewModel.awaitIdle()
            assertEquals(problem, viewModel.lightningProblem)
            assertTrue(fake.sets.isEmpty())
        }
    }

    @Test
    fun `an onchain address that is used, unknown or the group's is refused under its own field`() {
        listOf(
            Usage.Used to OnchainProblem.Used,
            Usage.Unknown to OnchainProblem.Unknown,
            Usage.TheGroups to OnchainProblem.TheGroups,
        ).forEach { (usage, problem) ->
            val fake = Fake(standing(), Resolution.Resolved(intent), usage)
            val viewModel = loaded(fake)
            viewModel.type("alice@example.com", fresh)
            viewModel.save(onSaved = {}, onFailure = {})
            viewModel.awaitIdle()
            assertEquals(problem, viewModel.onchainProblem)
            assertNull(viewModel.lightningProblem)
            assertTrue(fake.sets.isEmpty())
        }
    }

    @Test
    fun `another network's address, or none at all, is refused before anything is asked`() {
        val mainnet = Bitcoin.computeBIP86Address(PrivateKey(ByteVector32(Hex.decode("f1".padStart(64, '0')))).publicKey(), Chain.Mainnet.chainHash)
        listOf(mainnet to OnchainProblem.OtherNetwork, "bcrt1qnope" to OnchainProblem.NotAnAddress).forEach { (input, problem) ->
            val fake = Fake(standing(), Resolution.Resolved(intent))
            val viewModel = loaded(fake)
            viewModel.type("alice@example.com", input)
            viewModel.save(onSaved = {}, onFailure = {})
            assertEquals(problem, viewModel.onchainProblem)
            assertEquals(0, fake.resolved)
        }
    }

    @Test
    fun `removing sends a removal`() {
        val fake = Fake(standing(current = destination()), Resolution.Resolved(intent))
        val viewModel = loaded(fake)
        var removed = false
        viewModel.remove(onRemoved = { removed = true }, onFailure = {})
        viewModel.awaitIdle()
        assertEquals(1, fake.removals)
        assertTrue(removed)
        assertEquals("", viewModel.lightningField.text.toString())
    }

    @Test
    fun `a profile that cannot sign, or a room that is not a group, can save nothing`() {
        assertFalse(loaded(Fake(standing(canSet = false), Resolution.Resolved(intent))).canSave())
        assertFalse(loaded(Fake(standing(isGroup = false), Resolution.Resolved(intent))).canSave())
        assertFalse(loaded(Fake(standing(current = destination(), canSet = false), Resolution.Resolved(intent))).canRemove())
    }

    @Test
    fun `a room that cannot be found is an error`() {
        val viewModel = GroupPayoutDestinationViewModel(chatRoomId, chain, GroupPayoutDestinationUIState.Loading, Fake(null, Resolution.Unreachable))
        viewModel.initiate()
        runBlocking { withTimeout(5_000) { while (viewModel.uiState !is GroupPayoutDestinationUIState.Error) delay(10) } }
    }
}
