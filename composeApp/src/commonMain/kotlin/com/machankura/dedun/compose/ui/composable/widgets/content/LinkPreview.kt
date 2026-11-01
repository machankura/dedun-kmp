package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.ktor.http.Url
import com.machankura.dedun.compose.ui.theme.spacing
import com.machankura.dedun.compose.ui.composable.widgets.Decorative

@Composable
internal fun LinkPreview(url: String) {
    var ogData by remember(url) { mutableStateOf(Constants.ogCache.get(url)) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(url) {
        if (ogData == null) {
            ogData =
                Helpers.fetchOgData(
                    url
                )
        }
    }

    val data = ogData
    if (data != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.space50)
                .minimumInteractiveComponentSize()
                .clickable { uriHandler.openUri(url) }
        ) {
            Column {
                data.image?.let { imageUrl ->
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = Decorative,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    )
                }
                Column(modifier = Modifier.padding(MaterialTheme.spacing.space150)) {
                    data.siteName?.let { site ->
                        Text(
                            text = site.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } ?: run {
                        // Fall back to domain
                        val host = try {
                            Url(url).host.removePrefix("www.").uppercase() ?: ""
                        } catch (_: Exception) { "" }
                        if (host.isNotEmpty()) {
                            Text(
                                text = host,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    data.title?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.space25)
                        )
                    }
                    data.description?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.space50)
                        )
                    }
                }
            }
        }
    } else {
        // Show clickable link text while loading / if OG fetch fails
        Text(
            text = url,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(vertical = MaterialTheme.spacing.space25)
                .minimumInteractiveComponentSize()
                .clickable { uriHandler.openUri(url) }
        )
    }
}
