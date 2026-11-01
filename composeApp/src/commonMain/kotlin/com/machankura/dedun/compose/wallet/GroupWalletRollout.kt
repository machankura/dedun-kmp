package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi

/**
 * The gates the group wallet ships behind -- `docs/group-chain-wallet.md`, Phase
 * 10 -- in one place, each with the condition that lifts it.
 *
 * Entry points only. A device with a gate closed still accepts, shows and signs
 * a spend another member proposes, through every check `GroupSpend` makes: a
 * gate decides what this device offers, never what a quorum may decide.
 */
object GroupWalletRollout {
    /**
     * Whether the group's screen offers a wallet at all. Lifted when Phases 1-9
     * were in and the JVM suite passed, which they are and it did.
     */
    const val ENABLED: Boolean = true

    /**
     * The most one spend proposed from this device may send on mainnet -- and,
     * since a member can send an amount with change on every network, the
     * bound on what a builder bug in that change could put at risk. Lifts
     * when a release has run real spends end to end with no session that failed
     * to aggregate and no transaction that failed to confirm. Test networks have
     * no cap: nothing on them is worth one.
     */
    val MAINNET_CAP: Satoshi = Satoshi(100_000)

    fun capFor(chain: Chain): Satoshi? = MAINNET_CAP.takeIf { chain.isMainnet() }

    /**
     * Whether a subgroup is offered a guarded wallet on mainnet --
     * `docs/group-wallet-guardians.md`. Test networks were offered one from the
     * start. On: lifted by decision, ahead of the signet run its own condition
     * named -- a guardian spend made by a real three-level hierarchy. What it
     * still leaves open is the mixed-version risk: a member on a build that
     * predates guarded statements sees no wallet, and can open a plain one whose
     * addresses the rest do not watch. Every guardian spend on mainnet is held to
     * [MAINNET_CAP] like any other.
     */
    const val GUARDIANS_ON_MAINNET: Boolean = true

    fun offersGuardiansOn(chain: Chain): Boolean = GUARDIANS_ON_MAINNET || !chain.isMainnet()

    /**
     * Whether a member is offered a guarded wallet of their own on mainnet --
     * `docs/member-guarded-wallets.md`, D8. Test networks were offered one from
     * the start. On: lifted by decision, ahead of the signet run its own
     * condition named -- a three-level hierarchy watching one member's wallet,
     * and its root making a guardian spend from it -- as [GUARDIANS_ON_MAINNET]
     * was. What it still leaves open is the mixed-version risk: a signer on a
     * build that predates member wallets is shown a certificate it cannot check.
     *
     * The entrances only. A device with it shut still accepts, shows and signs
     * a certificate somebody else asked for.
     */
    const val MEMBER_WALLETS_ON_MAINNET: Boolean = true

    fun offersMemberWalletsOn(chain: Chain): Boolean = MEMBER_WALLETS_ON_MAINNET || !chain.isMainnet()

    /**
     * Whether a device tells the groups above about a guarded wallet on its own
     * -- as the wallet arrives, and as a group's chat or wallet screen opens --
     * rather than only when somebody taps *Share*.
     * `docs/guarded-wallet-forwarding.md`, D8. A behaviour that sends messages
     * without a person, so it has a switch for the release it first ships in.
     * The button does not read it.
     */
    const val FORWARDS_GUARDED_WALLETS: Boolean = true

    /**
     * The most a member's own spend from their guarded wallet may send on
     * [chain]: [MAINNET_CAP] on mainnet, as every other spend is. The gate
     * lifted before the run that was to lift the cap with it, and the member's
     * spend is signed by a path no group spend goes through, so the cap stays
     * until that run has been made.
     */
    fun memberCapFor(chain: Chain): Satoshi? = capFor(chain)
}
