package com.machankura.dedun.compose.wallet.wavelength.group

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GroupArkArriving
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.wallet.GroupChainClients
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.blockchain.electrum.ElectrumMiniWallet
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.WalletState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Sees for itself what has been paid to a group Wavelength wallet's boarding addresses, before any round takes it in:
 * `docs/group-wavelength-unconfirmed-deposits.md`, Phase 2.
 *
 * Needs nothing from whoever runs the wallet. Every device in the room reads the operator's answer to a deposit request and writes
 * [GroupArkDepositAddress.script] only after recomputing it from the group's own key, so an address here has already been checked;
 * the amount and the block come off that address's own unspents on this device's connection to the chain. That is the wallet's
 * standing rule -- this device's own record first -- and the boarding rail is the one incoming rail where it can be kept unaided.
 *
 * Writes [GroupArkArriving] and nothing else. **Never a `DepositRow`**: a row in the deposit ledger is a payable balance the moment
 * it exists (`GroupWavelengthPayouts.balancesIn` reads it with no filter on confirmation), so a transaction that can still be
 * replaced must not reach it. `GroupWavelengthDeposits.roundHappened` stays the only thing that files a Wavelength deposit.
 *
 * A coin leaving the chain's unspents means two different things, and they are told apart rather than guessed at (A10). An
 * Electrum call that fails answers with an empty list, so nothing here forgets a row on an empty answer alone (A15).
 */
class GroupArkBoardingWatch(
    private val database: DedunDatabase,
    private val clients: GroupChainClients,
    private val scope: CoroutineScope,
) {
    private val logger = Logger.withTag("GroupArkBoardingWatch")
    private var job: Job? = null
    private val watching = HashMap<String, Watching>()

    private class Watching(val mini: ElectrumMiniWallet, val jobs: Job)

    /** Starts watching every wallet the group has been given a boarding address for, and any answered later. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            database.groupArkDao().observeWalletsWithDepositAddresses().collect { keys ->
                keys.filterNot { it in watching }.forEach { key ->
                    try {
                        watch(key)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.e("Could not watch the boarding addresses of $key", e)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        watching.values.forEach {
            it.jobs.cancel()
            it.mini.stop()
        }
        watching.clear()
    }

    private suspend fun watch(key: String) {
        val roomId = key.split(":").getOrNull(1) ?: return
        val (chain, _) = GroupArkCoSigner.parseWalletKey(roomId, key) ?: return
        val client = clients.clientFor(chain)
        // One mini-wallet per wallet, each address added singly: these are not an HD branch with a gap limit, they are the
        // addresses the group was handed, and the other 512 of the window are ones nobody can pay.
        val mini = ElectrumMiniWallet(chain.chainHash, client, scope, logger)
        val jobs = scope.launch {
            launch {
                // Addresses as they are answered, so one given out while the app is open is watched without a restart.
                // `addAddress` ignores a script hash it already holds, so repeating the whole list is free.
                database.groupArkDao().observeDepositAddresses(key).collect { rows ->
                    rows.forEach { row -> if (row.script != null) row.address?.let(mini::addAddress) }
                }
            }
            launch {
                mini.walletStateFlow.collectLatest { state ->
                    try {
                        reconcile(key, roomId, client, state)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.e("Could not read what has arrived at $key's boarding addresses", e)
                    }
                }
            }
        }
        watching[key] = Watching(mini, jobs)
    }

    /**
     * What [state] says is at this wallet's boarding addresses now, against what this device already held.
     *
     * An address the state has not heard of yet is left alone entirely: before the first subscription answer every address looks
     * unpaid, and a row dropped then would take the receive screen's QR code back up while the money was still on its way.
     */
    private suspend fun reconcile(key: String, roomId: String, client: IElectrumClient, state: WalletState) {
        val dao = database.groupArkDao()
        val requests = dao.depositAddresses(key).filter { it.script != null }
        val now = Clock.System.now().epochSeconds
        val held = dao.arriving(key).associateBy { it.outpoint }
        val borneOut = HashSet<String>()

        state.addresses.forEach { (address, addressState) ->
            val request = requests.firstOrNull { it.address == address } ?: return@forEach
            val script = request.script?.lowercase() ?: return@forEach
            addressState.utxos.forEach { utxo ->
                val outpoint = outpointOf(utxo)
                borneOut += outpoint
                val existing = held[outpoint]
                val row = GroupArkArriving(
                    wallet = key,
                    outpoint = outpoint,
                    amountSat = utxo.amount.sat,
                    script = script,
                    // Whose the address is, as `roundHappened` resolves it: the request whose script this coin paid.
                    member = requests.firstOrNull { it.script.equals(script, ignoreCase = true) }?.requester,
                    // The time of first sight is never restamped: it is the only time a transaction in the mempool has.
                    firstSeenAtEpochSeconds = existing?.firstSeenAtEpochSeconds ?: now,
                    blockHeight = utxo.blockHeight.takeIf { it > 0 },
                    spentUnseenAtEpochSeconds = existing?.spentUnseenAtEpochSeconds,
                )
                if (row != existing) dao.upsertArriving(row)
            }
        }

        val boarded = dao.boarded(key).map { it.outpoint.lowercase() }.toSet()
        held.values.filter { it.outpoint !in borneOut && it.spentUnseenAtEpochSeconds == null }.forEach { row ->
            val address = requests.firstOrNull { it.script.equals(row.script, ignoreCase = true) }?.address
            val addressState = address?.let { state.addresses[it] } ?: return@forEach
            when {
                // The subscription says this address has no history at all, which is a status and not a failed call.
                !addressState.alreadyUsed -> dao.forgetArriving(key, row.outpoint)
                // A round this device signed has it, or it is already a deposit: the record moves on and this row is done.
                row.outpoint.lowercase() in boarded || database.depositDao().get(roomId, row.outpoint) != null ->
                    dao.forgetArriving(key, row.outpoint)
                else -> handOverOrForget(client, key, row, now)
            }
        }
    }

    /**
     * A coin that has left the unspents of an address that has been paid, with no round of this device's behind it.
     *
     * The transaction still in that address's history means it happened and its output has been spent -- by a round this device
     * never signed, so it will never file a deposit for it, and that is worth saying rather than hiding (A12). Gone from the
     * history means it was replaced or reorged out, and there is nothing to show. An **empty** history contradicts the
     * subscription that said this address has been paid, so it is a call that failed, and nothing is changed on it.
     */
    private suspend fun handOverOrForget(client: IElectrumClient, key: String, row: GroupArkArriving, now: Long) {
        val txid = row.outpoint.substringBefore(':')
        val history = client.getScriptHashHistory(scriptHashOf(ByteVector(row.script)))
        when {
            history.isEmpty() -> logger.i { "No history for a boarding address that has been paid: leaving ${row.outpoint} as it is" }
            history.any { it.txid.toString().equals(txid, ignoreCase = true) } -> {
                logger.i { "Boarding coin ${row.outpoint} was taken into a round this device didn't sign" }
                dao().markArrivingSpentUnseen(key, row.outpoint, now)
            }
            else -> {
                logger.i { "Boarding coin ${row.outpoint} is no longer on the chain: forgetting it" }
                dao().forgetArriving(key, row.outpoint)
            }
        }
    }

    private fun dao() = database.groupArkDao()

    private fun outpointOf(utxo: WalletState.Utxo): String =
        DepositAttributionEvent.DepositKey.Onchain(utxo.txId.toString().lowercase(), utxo.outputIndex.toLong()).text

    private fun scriptHashOf(script: ByteVector) = Crypto.sha256(script).byteVector32().reversed()

    companion object {
        /**
         * How deep a boarding coin has to be before the operator can take it into a round, as [GroupWavelengthTerms] reads each
         * network's operator: six blocks on mainnet. What a screen counts towards, and nothing this watch acts on.
         */
        fun confirmationsNeeded(chain: Chain): Int = GroupWavelengthTerms.of(chain)?.boardingConfirmations ?: 1

        /** How deep [row] is against [tip], as `Standing` counts it: 0 in the mempool, and 1 in the block it is in. */
        fun depthOf(row: GroupArkArriving, tip: Long?): Int {
            val height = row.blockHeight ?: return 0
            if (tip == null) return 1
            return (tip - height + 1).coerceAtLeast(1).toInt()
        }
    }
}
