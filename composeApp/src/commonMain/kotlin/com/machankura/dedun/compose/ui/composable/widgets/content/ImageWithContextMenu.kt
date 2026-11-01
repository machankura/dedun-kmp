package com.machankura.dedun.compose.ui.composable.widgets.content

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.copy_url
import dedun.composeapp.generated.resources.download
import dedun.composeapp.generated.resources.tap_to_load

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImageWithContextMenu(meta: MediaMeta, onFullScreen: () -> Unit) {
    val url = meta.url
    val autoLoad = true
    var loaded by remember { mutableStateOf(autoLoad) }
    var showMenu by remember { mutableStateOf(false) }
//    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    if (!loaded) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .minimumInteractiveComponentSize()
                .clickable { loaded = true },
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = "Load image",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(MaterialTheme.spacing.space100))
                Text(
                    stringResource(Res.string.tap_to_load),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val ratio = remember(meta.dimension) { parseAspectRatio(meta.dimension) }

    Box {
        LoadingAsyncImage(
            model = url,
            contentDescription = "Image",
            contentScale = ContentScale.FillWidth,
            onClick = onFullScreen,
            onLongClick = { showMenu = true },
            modifier = Modifier
                .fillMaxWidth()
                .let { if (ratio != null) it.aspectRatio(ratio) else it }
                .clip(RoundedCornerShape(12.dp)),
        )
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.copy_url)) },
                onClick = {
                    clipboardManager.setText(AnnotatedString(url))
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.download)) },
                onClick = {
                    showMenu = false
//                    scope.launch { MediaDownloader.downloadMedia(context, url) }
                }
            )
        }
    }
}

internal fun parseAspectRatio(dim: String?): Float? {
    if (dim == null) return null
    val parts = dim.split('x')
    if (parts.size != 2) return null
    val w = parts[0].toFloatOrNull() ?: return null
    val h = parts[1].toFloatOrNull() ?: return null
    return if (h > 0) w / h else null
}
