package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LoadingAsyncImage(
    model: Any?,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    blurPainter: BitmapPainter? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var isLoading by remember { mutableStateOf(true) }

    Box(modifier = modifier) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = contentScale,
            placeholder = blurPainter,
            onLoading = { isLoading = true },
            onSuccess = { isLoading = false },
            onError = { isLoading = false },
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = onClick ?: {},
                            onLongClick = onLongClick
                        )
                    } else Modifier
                )
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    // m3-color-exempt: over a blurhash placeholder the backdrop is an
                    // arbitrary blurred image, so no role is reliably legible on it.
                    // White is the conventional choice and holds on all but a white
                    // photograph. Without a placeholder the surface is known and the
                    // role is used.
                    color = if (blurPainter != null) Color.White else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
