package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.text.inLocalCurrency
import fr.acinq.bitcoin.Satoshi
import fr.acinq.phoenix.data.ExchangeRate
import fr.acinq.phoenix.data.PreferredFiatCurrencies
import fr.acinq.phoenix.managers.global.CurrencyManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The one rate a member's screens read: their own primary currency against bitcoin, or null.
 *
 * A composition local rather than a field on each screen's state, because a rate is not a fact about
 * the wallet -- it is a fact about this device and this member, the same on every screen. Putting it
 * on `GroupWavelengthWalletUIState.Opened` would have reached the balance card and missed the two
 * places that need it most: the commitment screen's cap, which is drawn from `NotOpened` before any
 * wallet exists, and the payouts screen, which has a view model of its own.
 *
 * Null is the honest default and the one a test gets for free: no provider, no rate, no second line,
 * and every existing screen assertion still passes.
 */
val LocalExchangeRate: ProvidableCompositionLocal<ExchangeRate.BitcoinPriceRate?> =
    compositionLocalOf { null }

/** Provides [rate] to everything inside [content]. */
@Composable
fun ProvideExchangeRate(rate: ExchangeRate.BitcoinPriceRate?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExchangeRate provides rate) { content() }
}

/**
 * The member's primary currency against bitcoin, as Phoenix holds it, or null.
 *
 * Three things have to line up, and any of them missing means null rather than a guess:
 *
 * - **A rate at all.** `ratesFlow` is fed from the app database by a refresh job that
 *   `BusinessManager.startNewBusiness` starts. A member signed in with a bare nostr key starts no
 *   Phoenix business, so they have no rates and see no local currency. That is correct: this app
 *   would otherwise be quoting a price it never fetched.
 * - **A currency to convert into.** `UserPrefs.getFiatCurrencies` defaults its `primary` to USD, so
 *   there is no such thing as a member who has not chosen -- which is half of the plan's question 1
 *   answered by the build rather than by a decision.
 * - **A path from bitcoin to that currency.** [CurrencyManager.calculateOriginalFiat] is what does
 *   this, and it is used here rather than a lookup in `ratesFlow` because most currencies arrive as a
 *   price against the US dollar, not against bitcoin. A plain `first { it.fiatCurrency == currency }`
 *   returns a `UsdPriceRate`, which is not a bitcoin price, and reading its `price` as one would be
 *   out by the dollar rate. Phoenix's own `primaryFiatRate` ambient has that bug; this does not use it.
 */
@Composable
fun rememberPrimaryFiatRate(
    currencyManager: CurrencyManager,
    activeIdentity: StateFlow<Identity?>,
): ExchangeRate.BitcoinPriceRate? {
    val identity by activeIdentity.collectAsStateWithLifecycle()
    val prefs = identity?.userPrefs
    val currencies: Flow<PreferredFiatCurrencies?> =
        remember(prefs) { prefs?.getFiatCurrencies ?: flowOf(null) }
    val preferred by currencies.collectAsStateWithLifecycle(initialValue = null)
    val rates by currencyManager.ratesFlow.collectAsStateWithLifecycle()
    // Keyed on the rates as well as the preference: calculateOriginalFiat reads ratesFlow.value
    // itself, so without the rates in the key the figure would never follow a refresh.
    return remember(preferred, rates) {
        preferred?.primary?.let { currencyManager.calculateOriginalFiat(it) }
    }
}

/**
 * What a sats figure is worth, under it -- or nothing, where there is no rate.
 *
 * One widget rather than the `?.let { Text(...) }` at each of the six call sites, so the line has one
 * style and one condition everywhere it appears. [onSurfaceVariant] and a smaller style are the point:
 * **the sats figure above it is the figure,** and this is only what it is worth today (B1). Never put
 * this where there is no sats figure above it.
 */
@Composable
fun LocalAmount(
    sats: Satoshi,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val text = sats.inLocalCurrency(LocalExchangeRate.current) ?: return
    Text(text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
