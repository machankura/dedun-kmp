package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import fr.acinq.phoenix.managers.NodeParamsManager

/**
 * The gates the group Lightning wallet ships behind -- `docs/group-lightning-wallet.md`, Phase 15 --
 * in one place, each with the condition that lifts it.
 *
 * As with [GroupWalletRollout], these decide what this device offers, never what it accepts: a device
 * with a gate closed still verifies and co-signs for a wallet another member opened -- except the
 * channel cap, which every co-signer holds to, since it is the one gate that bounds what can be lost.
 */
object GroupLightningRollout {
    /**
     * Whether the group's screen offers a Lightning wallet, and whether this device runs the group nodes
     * it is elected to. On: the phases are in, the JVM suite passes and the regtest end-to-end run passes.
     */
    const val ENABLED: Boolean = true

    /**
     * The network a Lightning wallet is opened on: the node's own, mainnet, where the group's onchain
     * wallet already is. Lifted to mainnet by decision, ahead of what the plan named for it -- a review
     * of the Iceberg scheme and the DKG outside the project, and a month of a testnet3 group with no
     * refused label and no lost channel. The Iceberg library itself says not to put money behind it.
     * What bounds the loss meanwhile is [MAINNET_CHANNEL_CAP], the statement's in-flight cap, and
     * every co-signer's checks.
     */
    val NETWORK: Chain = NodeParamsManager.chain

    /**
     * The most one channel may hold on mainnet. Every co-signer refuses to sign the first commitment of a
     * bigger channel, so it never opens, and the shared input of a splice to a bigger one, so it never grows
     * past it: the one splice they sign is the liquidity purchase the group authorised. Lifts
     * on the same condition as [GroupWalletRollout.MAINNET_CAP]: a release that has run real channels,
     * payments and closes with no refused label and no lost channel.
     */
    val MAINNET_CHANNEL_CAP: Satoshi = Satoshi(1_000_000)

    /**
     * Whether a member may ask the group to buy inbound liquidity, and the operator's node buys it
     * (`docs/group-lightning-liquidity.md`). Co-signers check a purchase whether or not it is on.
     *
     * On, on mainnet, by decision, ahead of the plan's Phase 0 mainnet check: ACINQ's LSP has not yet
     * been seen to accept a splice whose funding key from us does not change. If it refuses, the splice
     * aborts before anything is signed into it, and the purchase is reported as failed. What bounds a
     * purchase meanwhile is [MAINNET_CHANNEL_CAP], the fee cap the group signs, and every co-signer's checks.
     */
    const val LIQUIDITY_ENABLED: Boolean = true

    /** The most a channel on [chain] may hold, or null where there is no cap. */
    fun channelCapFor(chain: Chain): Satoshi? = MAINNET_CHANNEL_CAP.takeIf { chain.isMainnet() }

    /**
     * Whether a Lightning wallet may be opened on [chain] at all: [NETWORK], and regtest for tests and
     * development.
     */
    fun allows(chain: Chain): Boolean = chain == NETWORK || chain == Chain.Regtest
}
