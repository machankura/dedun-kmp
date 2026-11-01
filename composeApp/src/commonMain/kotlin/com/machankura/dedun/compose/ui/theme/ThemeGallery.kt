// m3-string-exempt: the words in this file are sample text for looking at colour
// pairings, not UI text. Nobody navigates here and nothing here is translated;
// putting these eight strings in the catalogue would add eight entries that no
// screen shows and that a translator would have to be told to ignore.
package com.machankura.dedun.compose.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.machankura.dedun.compose.ui.composable.widgets.Decorative

/**
 * All six schemes, side by side, over the components that carry text.
 *
 * The one place the medium and high contrast schemes can be looked at. They are reachable
 * in the app only through a platform setting -- and on android 12 and up, only with
 * dynamic colour switched off -- so without this they are values in `Color.kt` that
 * nobody has seen.
 *
 * Over components rather than over screens, and that is the point of it. Contrast is a
 * property of the scheme: `ColorSchemeContrastTest` measures every pair in all six, so
 * what a render adds is not proof but judgement -- whether `secondaryContainer` still
 * reads as a container in high contrast, whether the outline is doing its job. Six renders
 * of this answer that for the whole app; six renders of each of 51 screens would answer it
 * 51 times.
 */
@Preview(name = "Six schemes", group = "schemes", widthDp = 1200, heightDp = 900)
@Composable
private fun ThemeGalleryPreview() {
    // One render holding all six, not six renders. The contrast step is a `DedunTheme`
    // parameter and no preview annotation can set it, so the combinations have to be laid
    // out in the body -- and laying them out side by side is what makes them comparable,
    // which is the only reason to look at them at all.
    Column {
        listOf(false, true).forEach { dark ->
            Row(modifier = Modifier.fillMaxWidth()) {
                ThemeContrast.entries.forEach { contrast ->
                    Column(modifier = Modifier.weight(1f)) {
                        ThemeGallery(darkTheme = dark, contrast = contrast)
                    }
                }
            }
        }
    }
}

/**
 * One scheme's worth of components. Public so a screen's own preview can borrow it.
 *
 * `dynamicColor = false` on purpose: with it left on, an android 12+ preview renders the
 * wallpaper palette and every column here comes out identical. That is correct app
 * behaviour and useless as a gallery of the six schemes.
 */
@Composable
fun ThemeGallery(darkTheme: Boolean, contrast: ThemeContrast) {
    DedunTheme(darkTheme = darkTheme, contrast = contrast, dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.compactPadding),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
            ) {
                Text(
                    text = "${if (darkTheme) "Dark" else "Light"} · ${contrast.name}",
                    style = MaterialTheme.typography.labelSmall,
                )

                // Body copy on the plain surface: the pairing every screen uses most, and
                // the one that has to hold 4.5:1.
                Text("Body on surface", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Secondary text",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // A card with a container role and a list item inside it -- the exact
                // arrangement that rendered a headline at 1.00:1 before phase 3, because
                // ListItem does not read LocalContentColor.
                Card {
                    ListItem(
                        headlineContent = { Text("Awaiting your signature") },
                        supportingContent = { Text("Two events") },
                        leadingContent = { Icon(Icons.Default.Star, contentDescription = Decorative) },
                    )
                }

                // The three button emphases, which is where the extended brand colours and
                // the error role are seen.
                Button(onClick = {}) { Text("Primary") }
                FilledTonalButton(onClick = {}) { Text("Tonal") }
                TextButton(onClick = {}) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
