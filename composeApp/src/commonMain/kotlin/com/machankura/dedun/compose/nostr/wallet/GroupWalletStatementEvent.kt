package com.machankura.dedun.compose.nostr.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.nostr.subgroup.tags.GuardianTag
import com.machankura.dedun.compose.nostr.subgroup.tags.SubgroupBirthCertificateTag
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock

/**
 * The group saying it keeps coins on chain, at one account on one network.
 *
 * Every room with a key *could* receive coins without this -- the addresses are a
 * function of the threshold key -- and that is the wrong default for money. A
 * member should not be able to show an address the group never agreed to look
 * after, and somebody paying one deserves to know the group said so. So a wallet
 * exists when a quorum has signed one of these, and not before.
 *
 * ```
 *  member --[ 30320 proposal over a 30331 ]-> the room
 *  ...a signing session...
 *  everyone holds the signed 30331             "the group keeps a wallet at this account"
 * ```
 *
 * ### Trusted no further than it can be checked
 *
 * Content is the account's xpub; the tags carry the network, the account number
 * and the two descriptors. A reader accepts a statement only when the room signed
 * it *and* [GroupWalletDerivation.account] rederives every one of those values
 * from the room's threshold key -- the same two checks `GroupKeyStateEvent` asks
 * of a key state. The second is the load-bearing one. This is a record that the
 * group agreed, not a source of keys: nothing reads an address out of it, and a
 * statement naming any xpub but the rederived one is not the group's wallet,
 * whoever signed it.
 *
 * ### A guarded statement
 *
 * A subgroup whose birth certificate grants it guardian keys may open its wallet
 * *guarded*: every address carries a leaf per ancestor, and the statement is the
 * child's quorum agreeing to that. It carries the guardians as [GuardianTag]s
 * and the certificate itself, whole, so that a member who holds no key state --
 * one welcomed in after the founding -- can still check every address, and so
 * that an ancestor it is forwarded to can check where its own key came from.
 * See `docs/group-wallet-guardians.md`.
 *
 * ### Never on a relay
 *
 * The xpub is every address and the whole balance, to whoever has it. Like the
 * signing kinds this only ever lives inside an encryption a relay cannot open,
 * and nothing in the app sends a group-signed event to a relay.
 *
 * ### Why 30331
 *
 * Next to the signing family in the app's private inner-event space. 30327-30330
 * were taken by the chronicle, its request, the subgroup certificate and the
 * decline (the last also shared with the direct-message envelope), so this is the
 * first free number past them, and the transaction proposal that is opened
 * against it sits beside it at 30332.
 */
object GroupWalletStatementEvent {
    val KIND: Kind = 30331

    private const val D_TAG = "d"
    private const val CHAIN_TAG = "chain"
    private const val ACCOUNT_TAG = "account"
    private const val DESCRIPTOR_TAG = "descriptor"
    private const val RECEIVE_MARKER = "receive"
    private const val CHANGE_MARKER = "change"

    /**
     * What a statement says, read but not yet believed. [verifiedAgainst] is what
     * turns it into a wallet.
     */
    data class Reading(
        val chain: Chain,
        val account: Long,
        val xpub: String,
        val receiveDescriptor: String,
        val changeDescriptor: String,
        /** The guardians it names, nearest first; empty for a plain statement. */
        val guardians: List<GuardianDerivation.Guardian> = emptyList(),
        /** The birth certificate a guarded statement carries, unverified. */
        val certificate: Event? = null,
    ) {
        /**
         * The account this statement describes, if [thresholdPublicKey] rederives
         * every value it carries; null otherwise.
         *
         * For a guarded one, also: the certificate certifies the room the key
         * makes, it names that key, and its guardian keys are exactly the ones
         * the statement names -- the guardians come from the parent's signature
         * and the child's agreement together, or the statement opens nothing.
         */
        fun verifiedAgainst(thresholdPublicKey: HexKey): GroupWalletDerivation.Account? {
            if (guardians.isNotEmpty() && !guardiansCheckOut(thresholdPublicKey)) return null

            val account = runCatching { GroupWalletDerivation.account(thresholdPublicKey, chain, account, guardians) }
                .getOrNull()
                ?: return null

            return account.takeIf {
                it.xpub == xpub &&
                    it.receiveDescriptor == receiveDescriptor &&
                    it.changeDescriptor == changeDescriptor
            }
        }

        private fun guardiansCheckOut(thresholdPublicKey: HexKey): Boolean = runCatching {
            val certificate = certificate ?: return false
            val chatRoomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
            val parentChatRoomId = SubgroupBirthCertificateEvent.parseParentChatRoomId(certificate.tags) ?: return false

            SubgroupBirthCertificateEvent.certifies(certificate, chatRoomId, parentChatRoomId) &&
                SubgroupBirthCertificateEvent.parseThresholdPublicKey(certificate.tags).equals(thresholdPublicKey, ignoreCase = true) &&
                SubgroupBirthCertificateEvent.parseGuardians(certificate.tags) == guardians
        }.getOrDefault(false)
    }

    /**
     * The `d` tag: one statement per network and account, the newest winning, so a
     * group that signs a second one for the same account replaces rather than adds.
     */
    fun identifier(chain: Chain, account: Long): String = "${chainName(chain)}:$account"

    /**
     * The statement opening [account]. A guarded account needs the [certificate]
     * its guardians were granted in, which the statement carries whole.
     */
    fun template(
        account: GroupWalletDerivation.Account,
        certificate: Event? = null,
        createdAt: Long = Clock.System.now().epochSeconds,
    ): EventTemplate<*> {
        require(!account.isGuarded || certificate != null) { "a guarded wallet's statement carries the certificate that granted its guardians" }

        val guarded = if (account.isGuarded) {
            account.guardians.map(GuardianTag::assemble) + listOf(SubgroupBirthCertificateTag.assemble(certificate!!))
        } else {
            emptyList()
        }

        return EventTemplate<Nothing>(
            createdAt = createdAt,
            kind = KIND,
            tags = arrayOf(
                arrayOf(D_TAG, identifier(account.chain, account.account)),
                arrayOf(CHAIN_TAG, chainName(account.chain)),
                arrayOf(ACCOUNT_TAG, account.account.toString()),
                arrayOf(DESCRIPTOR_TAG, account.receiveDescriptor, RECEIVE_MARKER),
                arrayOf(DESCRIPTOR_TAG, account.changeDescriptor, CHANGE_MARKER),
            ) + guarded,
            content = account.xpub,
        )
    }

    /** The statement's fields, or null when any is missing or unreadable. Nothing is verified here. */
    fun read(tags: Array<Array<String>>, content: String): Reading? {
        fun value(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        fun descriptor(marker: String): String? =
            tags.firstOrNull { it.size >= 3 && it[0] == DESCRIPTOR_TAG && it[2] == marker }?.get(1)

        val chain = value(CHAIN_TAG)?.let(::chainNamed) ?: return null
        val account = value(ACCOUNT_TAG)?.toLongOrNull() ?: return null
        val receive = descriptor(RECEIVE_MARKER) ?: return null
        val change = descriptor(CHANGE_MARKER) ?: return null
        if (content.isBlank()) return null
        // A `d` tag that disagrees with the fields would let two statements for one
        // account both stand; reading it back here keeps "newest per account" one rule.
        if (value(D_TAG) != identifier(chain, account)) return null

        // Guardians that do not all read are not a shorter list of guardians.
        val guardians = GuardianTag.parseAll(tags) ?: return null
        val certificate = tags.firstNotNullOfOrNull(SubgroupBirthCertificateTag::parse)?.certificate

        return Reading(chain, account, content, receive, change, guardians, certificate)
    }

    /**
     * Lower-case names, written into a tag. Spelled out rather than taken from
     * [Chain.name], whose capitalisation is the library's to change.
     */
    fun chainName(chain: Chain): String = when (chain) {
        Chain.Mainnet -> "mainnet"
        Chain.Testnet3 -> "testnet3"
        Chain.Testnet4 -> "testnet4"
        Chain.Signet -> "signet"
        Chain.Regtest -> "regtest"
    }

    fun chainNamed(name: String): Chain? = when (name) {
        "mainnet" -> Chain.Mainnet
        "testnet3" -> Chain.Testnet3
        "testnet4" -> Chain.Testnet4
        "signet" -> Chain.Signet
        "regtest" -> Chain.Regtest
        else -> null
    }
}
