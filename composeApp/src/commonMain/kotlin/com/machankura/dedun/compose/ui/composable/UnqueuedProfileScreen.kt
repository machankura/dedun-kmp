package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.all_broadcasts_are_queued_so_that_we_can
import bennu.composeapp.generated.resources.everything_is_cryptographical_sound_just_3
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UnqueuedProfileScreen() {
    Scaffold(snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) }) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding)
            .readableContent()
        ) {

            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    MaterialTheme.spacing.space125
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
            ) {

                Spacer(
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(Res.string.everything_is_cryptographical_sound_just_3),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(Res.string.all_broadcasts_are_queued_so_that_we_can),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )

                Spacer(
                    modifier = Modifier.weight(1f)
                )
                com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator(
                    fillScreen = false
                )

                Spacer(
                    modifier = Modifier.weight(8f)
                )

                // TODO: Give user ability to start over...

            }
        }
    }
}

@ConformancePreviews
@Composable
private fun UnannouncedProfileScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            UnqueuedProfileScreen()
        }
    }
}