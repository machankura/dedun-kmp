package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.runtime.Composable
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.about_n_days
import bennu.composeapp.generated.resources.about_n_hours
import bennu.composeapp.generated.resources.about_n_minutes
import bennu.composeapp.generated.resources.about_n_months
import bennu.composeapp.generated.resources.about_n_years
import bennu.composeapp.generated.resources.blocks_and_about
import bennu.composeapp.generated.resources.n_blocks
import com.machankura.dedun.compose.text.BlockDuration
import com.machankura.dedun.compose.text.groupedBlocks
import fr.acinq.bitcoin.Chain
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A guardian's delay as a member reads it: *144 blocks, about 1 day*.
 * `docs/guardian-delay.md`, D5.
 *
 * Blocks only on regtest, whose blocks come when somebody mines one, so a time
 * there would be wrong. [chain] is null where a delay is for no network in
 * particular -- a birth certificate is not -- and then the estimate is
 * mainnet's, as every network but regtest shares its target.
 */
@Composable
fun delayText(blocks: Long, chain: Chain? = null): String {
    val count = pluralStringResource(Res.plurals.n_blocks, blocks.toInt(), blocks.groupedBlocks())
    if (chain == Chain.Regtest) return count
    return stringResource(Res.string.blocks_and_about, count, approximateDuration(blocks))
}

/** *about 1 day*: [BlockDuration.approximate] in words. */
@Composable
fun approximateDuration(blocks: Long): String {
    val approximate = BlockDuration.approximate(blocks)
    val plural = when (approximate.unit) {
        BlockDuration.Unit.MINUTES -> Res.plurals.about_n_minutes
        BlockDuration.Unit.HOURS -> Res.plurals.about_n_hours
        BlockDuration.Unit.DAYS -> Res.plurals.about_n_days
        BlockDuration.Unit.MONTHS -> Res.plurals.about_n_months
        BlockDuration.Unit.YEARS -> Res.plurals.about_n_years
    }
    return pluralStringResource(plural, approximate.count, approximate.count)
}
