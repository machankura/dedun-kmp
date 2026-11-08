package com.machankura.dedun.compose.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.managers.computePreferencePath
import fr.acinq.phoenix.utils.PlatformContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * What this device remembers about one group wallet, for the member using it.
 *
 * The store the app did not have. Phases 3 and 10 of `docs/group-wavelength-redesign.md` each needed a
 * per-group-wallet fact kept across navigation, and each shipped without one: the balance's hide toggle
 * forgot itself the moment a member left the screen, and the commitment screen's acknowledgement was
 * not recorded at all. `UserPrefs` and `InternalPrefs` are Phoenix's and scoped to a Phoenix wallet,
 * which is the wrong grain -- a member has one Phoenix wallet and may be in six groups.
 *
 * **A `DataStore`, not a table.** Everything here is a local per-device preference: nothing is signed,
 * nothing goes on the wire, nothing another member can read, and nothing a query has to join against.
 * A Room table would have meant a schema migration in `composeApp/schemas` for two booleans. And no new
 * platform code either: `computePreferencePath` is Phoenix's own public `expect`, with an `actual` for
 * each of the three targets, so this file is the whole of it.
 *
 * Keys are the wallet key `GroupArkCoSigner.walletKey` already builds -- room, chain and wallet number
 * -- so a group with two wallets, or the same group on two networks, keeps them apart. A member's own
 * key is in the acknowledgement's key too, because two members sharing a device must not acknowledge
 * for each other.
 */
class GroupWalletPrefs(private val data: DataStore<Preferences>) {

    /**
     * A read that cannot fail into a crash.
     *
     * The same shape as `GlobalPrefs.safeData`: a corrupt or unreadable preferences file is a wallet
     * that forgets a toggle, not a wallet that will not open. There is nothing in here worth failing
     * a screen over.
     */
    private val safe: Flow<Preferences> = data.data.catch { emit(emptyPreferences()) }

    /** Whether the member has hidden this wallet's balance. False where they never have. */
    fun balanceHidden(wallet: String): Flow<Boolean> =
        safe.map { it[hiddenKey(wallet)] ?: false }

    suspend fun setBalanceHidden(wallet: String, hidden: Boolean) {
        data.edit { it[hiddenKey(wallet)] = hidden }
    }

    /**
     * Whether [member] has said they understand what this wallet commits the group to.
     *
     * False where there is no record, which is the whole point: a member who joined after the wallet
     * was opened has never been shown the six sentences, and this is how the screen finds out. It is
     * **never** read by anything that signs (B10's limit): a device that refused to co-sign because
     * its owner had not tapped a screen would lose the group money on a deadline.
     */
    fun acknowledged(wallet: String, member: HexKey): Flow<Boolean> =
        safe.map { it[acknowledgedKey(wallet, member)] ?: false }

    suspend fun acknowledge(wallet: String, member: HexKey) {
        data.edit { it[acknowledgedKey(wallet, member)] = true }
    }

    private fun hiddenKey(wallet: String) = booleanPreferencesKey("balance_hidden:$wallet")

    /** The member's key as well as the wallet's: two members on one device acknowledge for themselves. */
    private fun acknowledgedKey(wallet: String, member: HexKey) =
        booleanPreferencesKey("acknowledged:$wallet:${member.lowercase()}")

    companion object {
        /** Beside Phoenix's own preference files, which is what `computePreferencePath` decides. */
        private const val FILE = "dedun-group-wallets.preferences_pb"

        /** One store for every group wallet on the device: the keys keep them apart, not the files. */
        fun of(context: PlatformContext): GroupWalletPrefs =
            GroupWalletPrefs(PreferenceDataStoreFactory.createWithPath { computePreferencePath(context, FILE) })
    }
}
