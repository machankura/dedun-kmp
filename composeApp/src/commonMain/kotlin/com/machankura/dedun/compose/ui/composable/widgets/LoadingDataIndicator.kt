package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

/**
 * The app's one loading state, used at 41 call sites.
 *
 * It draws M3's `LoadingIndicator` rather than a `CircularProgressIndicator`. That is the
 * expressive component for exactly this -- an indeterminate wait with no progress to
 * report -- and it is what `MaterialExpressiveTheme` expects to be paired with.
 *
 * The colour default moved from `secondary` to the component's own
 * `LoadingIndicatorDefaults.indicatorColor`. `secondary` is the brand gold, which read as
 * a warning rather than as a wait, and the previous version also hardcoded an 80dp width
 * where the component has a size of its own.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingDataIndicator(
    modifier: Modifier = Modifier.fillMaxWidth(),
    color: Color = LoadingIndicatorDefaults.indicatorColor,
    fillScreen: Boolean = true,
    text: String? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (fillScreen) {
            Spacer(modifier = Modifier.weight(1f))
        }

        LoadingIndicator(color = color)

        text?.let {

            Spacer(
                modifier = Modifier.height(MaterialTheme.spacing.space400)
            )

            Text(
                text = text,
                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space125),
                textAlign = TextAlign.Center
            )
        }
        if (fillScreen) {
            Spacer(modifier = Modifier.weight(3f))
        }

    }
}


@ConformancePreviews
@Composable
private fun TransferHistoryScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LoadingDataIndicator(
                text = "Starting up"
            )
        }
    }
}