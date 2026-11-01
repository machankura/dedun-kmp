package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import com.machankura.dedun.compose.ui.theme.spacing

@Composable
fun FullScreenImageViewer(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(0.5f, 5f)
            offset = if (scale > 1f) offset + panChange else Offset.Zero
        }

        val clipboardManager = LocalClipboardManager.current
        rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                .minimumInteractiveComponentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (scale <= 1f) onDismiss() }
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Full screen image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = transformableState)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(MaterialTheme.spacing.containerPadding)
            ) {
                val buttonColors = IconButtonDefaults.iconButtonColors(
                    // m3-color-exempt: this button floats over an arbitrary
                    // photograph, so no scheme role is safe behind it. A translucent
                    // scrim with white on it is M3's own full-screen media treatment
                    // and the only pairing that holds over both a white sky and a
                    // black one.
                    containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                    contentColor = Color.White
                )

//                IconButton(
//                    onClick = { scope.launch { TODO: MediaDownloader.downloadMedia(context, imageUrl) } },
//                    colors = buttonColors,
//                    modifier = Modifier.size(40.dp)
//                ) {
//                    Icon(
//                        Icons.Default.Download,
//                        contentDescription = "Download"
//                    )
//                }
                Spacer(Modifier.width(MaterialTheme.spacing.space100))
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(imageUrl)) },
                    colors = buttonColors,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL")
                }
                Spacer(Modifier.width(MaterialTheme.spacing.space100))
                IconButton(
                    onClick = onDismiss,
                    colors = buttonColors,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        }
    }
}
