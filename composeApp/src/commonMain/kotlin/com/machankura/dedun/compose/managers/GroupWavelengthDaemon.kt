package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.machankura.dedun.compose.wallet.wavelength.WavelengthWalletRollout
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkAuth
import com.machankura.dedun.compose.wallet.wavelength.group.GroupArkManifest
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import engineering.lightning.wavelength.HostedWalletClient
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.ReceiveRequest
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.SendRequest
import engineering.lightning.wavelength.WatchOnlyWalletRequest
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import engineering.lightning.wavelength.ExitRequest
import engineering.lightning.wavelength.ListRequest
import engineering.lightning.wavelength.model.EntryStatus
import engineering.lightning.wavelength.model.ExitSummaryResult
import engineering.lightning.wavelength.model.GetExitPlanResult
import engineering.lightning.wavelength.model.ListView
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Running a group's Wavelength wallet on the elected device -- `docs/group-wavelength-wallet.md`, Phase 5: a watch-only daemon with
 * no fund key in it, whose every signature goes to [GroupArkTransport].
 *
 * A daemon that opened with anything but the manifest the group signed is stopped at once. Nothing here deletes a wallet
 * directory, which rebuilds from the chain and the indexer anyway, at five group signatures per key index scanned (D14).
 */
class GroupWavelengthDaemon(private val client: HostedWalletClient, private val dataRoot: String, private val fileSystem: FileSystem = FileSystem.SYSTEM) {
    private val logger = Logger.withTag("GroupWavelengthDaemon")

    sealed class Outcome {
        data class Running(val transport: GroupArkTransport, val config: RuntimeConfig) : Outcome()

        /** Why not, for the wallet's screen to say. Nothing was started, or what was has been stopped. */
        data class Refused(val reason: Refusal) : Outcome()
    }

    enum class Refusal {
        CHAIN_REFUSED,
        NOT_OPERATOR_OR_BEHIND,
        NOBODY_RUNS_IT,
        NO_SEED,
        KEYS_DISAGREE,
        /** The group's devices didn't publish what a signature needs in time. */
        SIGNERS_NOT_READY,
        OPENED_AS_ANOTHER,
    }

    private fun networkOf(chain: Chain): Network? = when (chain) {
        Chain.Mainnet -> Network.MAINNET
        Chain.Signet -> Network.SIGNET
        Chain.Regtest -> Network.REGTEST
        else -> null
    }

    /**
     * Opens the wallet this device was elected to run. [recoveryWindow] is how many keys of each family to scan, or null when
     * this directory has already been opened: the narrowest the last snapshot allows, since each key costs the group signatures.
     */
    suspend fun open(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        wallet: GroupWavelengthWalletManager.Wallet,
        recoveryWindow: Long?,
        lastSyncedAt: Instant?,
        now: Instant,
        /** What has to happen before the daemon is started, on its transport: round one has to be there before anything can ask for it. */
        prepare: suspend (GroupArkTransport) -> Boolean = { true },
    ): Outcome {
        val statement = wallet.statement
        val roomId = localChatRoom.chatRoom.id
        if (!GroupWavelengthRollout.allows(statement.chain)) return Outcome.Refused(Refusal.CHAIN_REFUSED)
        val network = networkOf(statement.chain) ?: return Outcome.Refused(Refusal.CHAIN_REFUSED)
        if (!GroupWavelengthOperatorManager.mayRunDaemon(database, roomId, statement.chain, userPublicKey, lastSyncedAt, now, statement.wallet)) {
            return Outcome.Refused(Refusal.NOT_OPERATOR_OR_BEHIND)
        }
        val term = GroupWavelengthOperatorManager.current(database, roomId, statement.chain, statement.wallet)?.term
            ?: return Outcome.Refused(Refusal.NOBODY_RUNS_IT)
        val seed = database.groupWavelengthSeedDao().get(roomId, statement.identity)
            ?: return Outcome.Refused(Refusal.NO_SEED)

        val auth = GroupArkAuth.of(ByteVector32(seed.seed), statement.chain)
        val manifest = GroupArkManifest.of(PublicKey.fromHex(statement.icebergKey), auth, statement.chain, statement.wallet)
        if (manifest.hash != statement.manifestHash) return Outcome.Refused(Refusal.KEYS_DISAGREE)

        val dataDir = dataDirOf(statement)
        // Regtest has no public hosts to name: whoever runs a local operator names them.
        val base = if (network == Network.REGTEST) RuntimeConfig(network = network, dataDir = dataDir) else WavelengthWalletRollout.configFor(network, dataDir)
        val config = base.copy(hostedSignerRequestTimeoutSeconds = REQUEST_TIMEOUT.inWholeSeconds)
        val transport = GroupArkTransport(database, localChatRoom, userPublicKey, wallet, term, auth, timeout = TRANSPORT_TIMEOUT)
        if (!prepare(transport)) return Outcome.Refused(Refusal.SIGNERS_NOT_READY)

        val opened = client.startHostedWatchOnlyWallet(
            config,
            WatchOnlyWalletRequest(
                walletPassword = passwordOf(seed.seed),
                manifest = manifest.canonical,
                authKeys = auth.authKeysJson(),
                expectedIdentityPubKey = statement.identity,
                recoverState = recoveryWindow != null,
                recoveryWindow = recoveryWindow,
            ),
            transport,
        )
        if (opened.manifestHash != statement.manifestHash || opened.identityPubKey != statement.identity) {
            logger.e("The daemon opened as ${opened.identityPubKey.take(16)} with manifest ${opened.manifestHash.take(16)}, which isn't what the group signed")
            client.stop()
            return Outcome.Refused(Refusal.OPENED_AS_ANOTHER)
        }
        // Only now: a scan that failed partway leaves the directory behind, and has found nothing.
        runCatching { markOpened(statement) }.onFailure { logger.w(it) { "Couldn't note that this wallet opened, so it'll scan again next time" } }
        return Outcome.Running(transport, config)
    }

    /** Whether this device has finished opening this wallet before: a wallet's directory rebuilds from the chain, at a cost in group signatures (D14). */
    fun hasOpened(statement: GroupWavelengthWalletStatementEvent.Statement): Boolean = fileSystem.exists(openedMarkerOf(statement))

    private fun markOpened(statement: GroupWavelengthWalletStatementEvent.Statement) {
        val marker = openedMarkerOf(statement)
        marker.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(marker) {}
    }

    /** Beside the daemon's directory and not in it, which is the daemon's own. */
    private fun openedMarkerOf(statement: GroupWavelengthWalletStatementEvent.Statement) = "${dataDirOf(statement)}.opened".toPath()

    fun isRunning(): Boolean = client.isRunning()

    /**
     * What the running daemon says it holds. Local reads, as far as anyone has measured: an idle wallet asked for no fund-key signature
     * in the census, and nothing has counted what these cost on a wallet with coins, so whoever calls this should do so rarely.
     */
    suspend fun snapshotOf(walletKey: String, term: Long, now: Instant): GroupWavelengthSnapshot {
        val balance = client.balance()
        val coins = client.list(ListRequest(view = ListView.VTXOS)).vtxos?.vtxos.orEmpty()
        val entries = client.list(ListRequest(limit = GroupWavelengthSnapshot.MAX_ACTIVITY.toLong())).activity?.entries.orEmpty()
        return GroupWavelengthSnapshot(
            wallet = walletKey,
            term = term,
            sentAt = now.epochSeconds,
            tip = client.getInfo().blockHeight.toLong(),
            confirmedSat = balance.confirmedSat,
            pendingInSat = balance.pendingInSat,
            pendingOutSat = balance.pendingOutSat,
            creditSat = balance.creditAvailableSat + balance.creditReservedSat,
            coins = coins.take(GroupWavelengthSnapshot.MAX_COINS).map { GroupWavelengthSnapshot.Coin(it.outpoint, it.amountSat, it.status, it.batchExpiry.toLong()) },
            activity = entries.take(GroupWavelengthSnapshot.MAX_ACTIVITY).map {
                GroupWavelengthSnapshot.Line(it.id, it.kind?.wire.orEmpty(), it.status?.wire.orEmpty(), it.amountSat, it.feeSat, it.createdAt?.epochSeconds ?: 0, it.failureReason, it.progress?.paymentHash.orEmpty())
            },
        )
    }

    /** A new address for the group's boarding key, from the running daemon, which every device checks before believing. */
    suspend fun newBoardingAddress(): String = client.deposit().address

    /** An invoice for [amountSat], made by the running daemon. It takes the group's signatures, so it gets longer than a personal wallet's. */
    suspend fun newInvoice(amountSat: Long): String =
        client.receive(ReceiveRequest(amountSat, timeoutSeconds = INVOICE_TIMEOUT.inWholeSeconds)).invoice

    /** Pays an invoice the group approved. Every signature this needs is still the group's to give, and credit stays closed (D10). */
    suspend fun pay(invoice: String, maxFeeSat: Long) {
        client.send(SendRequest.Invoice(invoice, maxFeeSat = maxFeeSat, maxCreditSat = 0, maxCreditTopupSat = 0))
    }

    /**
     * Pays a member's approved onchain payout: a leave of exactly [amountSat] to [address] in the next round, with the fee taken from the
     * change, which every device checks against the approval (`docs/group-wavelength-payouts.md`, W5). [note] is the approval's id, so the
     * daemon's own activity says which approvals it already has in hand.
     */
    suspend fun sendOnchain(address: String, amountSat: Long, maxFeeSat: Long, note: String) {
        client.send(SendRequest.Onchain(address, amountSat = amountSat, sweepAll = false, note = note, maxFeeSat = maxFeeSat))
    }

    /** The notes of the daemon's activity that hasn't failed: the approvals it is paying or has paid, as [sendOnchain] wrote them. */
    suspend fun notedActivity(): Set<String> =
        client.list(ListRequest(limit = GroupWavelengthSnapshot.MAX_ACTIVITY.toLong())).activity?.entries.orEmpty()
            .filter { it.status != EntryStatus.FAILED && it.note.isNotEmpty() }
            .map { it.note }
            .toSet()

    /** The coins the running daemon can still move. */
    suspend fun liveCoins(): List<String> =
        client.list(ListRequest(view = ListView.VTXOS)).vtxos?.vtxos.orEmpty().filter { it.status == LIVE }.map { it.outpoint }

    /** Leaves in a round to [destination]. A device signs only if that's the group's own onchain wallet. */
    suspend fun leave(outpoint: String, destination: String) {
        client.exit(ExitRequest.Cooperative(outpoint, destination))
    }

    suspend fun exitPlan(outpoints: List<String>): GetExitPlanResult = client.getExitPlan(outpoints)

    suspend fun exitSummary(): ExitSummaryResult = client.exitSummary()

    /** Leaves without the Ark operator's help, which has never been run on any network. */
    suspend fun forceExit(outpoint: String) {
        client.exit(ExitRequest.ForceUnroll(outpoint))
    }

    /** How many exits the running daemon has under way, which nobody can rebuild from the chain (D14). */
    suspend fun exitsUnderWay(): Int = client.exitSummary().exits.size

    /** Stops whatever daemon this client holds, which is safe to ask twice. */
    suspend fun stop() = client.stop()

    private fun dataDirOf(statement: GroupWavelengthWalletStatementEvent.Statement): String =
        "$dataRoot/group/${GroupWalletStatementEvent.chainName(statement.chain)}/${statement.identity.take(32)}/${statement.wallet}"

    companion object {
        /**
         * How long the daemon waits for any one signature. A round gives its forfeits 60 seconds from the commitment arriving, as the
         * real signet operator's did, so this is inside that: a signer still being waited on past it holds up the daemon releasing the coin.
         */
        val REQUEST_TIMEOUT = 55.seconds

        /** Shorter, so that silence from the group reaches the daemon as a refusal rather than as a lost connection. */
        val TRANSPORT_TIMEOUT = 50.seconds

        /** Two group signatures on signet, each up to [TRANSPORT_TIMEOUT]. */
        val INVOICE_TIMEOUT = 120.seconds

        private const val PASSWORD_TAG = "dedun/group-wavelength/wallet-password/v1"

        /** The daemon's word for a coin in no other state. */
        private const val LIVE = "live"

        /** A watch-only wallet has no secret to derive this from but the seed, and any participant can reopen it. */
        internal fun passwordOf(seedHex: HexKey): ByteArray = Crypto.taggedHash(fr.acinq.secp256k1.Hex.decode(seedHex), PASSWORD_TAG).toByteArray()
    }
}
