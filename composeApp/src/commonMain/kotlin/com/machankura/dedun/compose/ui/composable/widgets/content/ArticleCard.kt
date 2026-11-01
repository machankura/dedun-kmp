package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.article
import bennu.composeapp.generated.resources.loading_article
import bennu.composeapp.generated.resources.untitled_article

@Composable
private fun ArticleCard(
    localNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent,
    dTag: String,
    author: String,
    relayHints: List<String>,
    onArticleClick: ((Int, String, String) -> Unit)?,
    onProfileClick: ((String) -> Unit)?
) {
    val profile = localNostrEvent.profile
    val event = localNostrEvent.nostrEvent
    // Sync event...
//    LaunchedEffect(author, dTag) {
//        if (eventRepo.findAddressableEvent(30023, author, dTag) == null) {
//            eventRepo.requestAddressableEvent(30023, author, dTag, relayHints)
//        }
//    }

    val title = remember(event) { event.tags?.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1) }
    val summary = remember(event) { event.tags?.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1) }
    remember(event) { event.tags?.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1) }
    val publishedAt = remember(event) { event.tags?.firstOrNull { it.size >= 2 && it[0] == "published_at" }?.get(1)?.toLongOrNull() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.spacing.space75)
            .then(
                if (onArticleClick != null) Modifier.minimumInteractiveComponentSize()
                                                .clickable { onArticleClick(30023, author, dTag) }
                else Modifier
            )
    ) {
        if (event == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(MaterialTheme.spacing.space175)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.width(14.dp).height(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(MaterialTheme.spacing.space100))
                Text(
                    text = stringResource(Res.string.loading_article),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column {
//               TODO if (image != null) {
//                    val meta = remember(event.id) { parseImetaTags(event.tags)[image] ?: MediaMeta(url = image) }
//                    val ratio = remember(meta.dimension) { parseAspectRatio(meta.dimension) }
//                    LoadingAsyncImage(
//                        model = image,
//                        contentDescription = title,
//                        contentScale = ContentScale.Crop,
//                        modifier = Modifier
//                            .fillMaxWidth()
//                            .let { if (ratio != null) it.aspectRatio(ratio) else it.heightIn(max = 180.dp) }
//                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
//                    )
//                }
                Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.space175, vertical = MaterialTheme.spacing.space125)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(end = MaterialTheme.spacing.compactPadding)
                        ) {
                            Text(
                                text = stringResource(Res.string.article),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.space75, vertical = MaterialTheme.spacing.space25)
                            )
                        }
                        Text(
                            text = title ?: stringResource(Res.string.untitled_article),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.space50)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = MaterialTheme.spacing.space75)
                    ) {
                        com.machankura.dedun.compose.ui.composable.widgets.profile.ProfileAvatar(
                            profile = profile,
                            publicKey = localNostrEvent.nostrEvent.pubKey,
                            size = 20.dp
                        )
                        Spacer(Modifier.width(MaterialTheme.spacing.space75))
                        val displayName = profile?.humanReadableNameOrPubkey()
                            ?: "${author.take(8)}...${author.takeLast(4)}"
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onProfileClick != null) {
                                Modifier.minimumInteractiveComponentSize()
                                    .clickable { onProfileClick(author) }
                            } else Modifier
                        )
                        if (publishedAt != null) {
                            Text(
                                text = " \u00b7 ${
                                    Helpers.formatQuotedTimestamp(
                                        publishedAt
                                    )
                                }",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
