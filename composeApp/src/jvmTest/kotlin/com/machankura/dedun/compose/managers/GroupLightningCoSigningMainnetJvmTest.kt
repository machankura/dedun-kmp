package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.Chain

/**
 * Every co-signing test again, on mainnet: the network the Lightning wallet is opened on, and the one
 * where every co-signer holds a channel to the rollout's cap.
 */
class GroupLightningCoSigningMainnetJvmTest : GroupLightningCoSigningCases(Chain.Mainnet)
