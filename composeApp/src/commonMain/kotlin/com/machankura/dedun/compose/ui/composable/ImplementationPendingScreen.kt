package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.theme.spacing
import dedun.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import dedun.composeapp.generated.resources.functionality_coming_soon_2
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

/**
 * @param onNavigateBack `null` when this screen is the root of the stack, which it is
 *   when the navigation machine lands on an error and clears everything under it. Pushed
 *   from a "learn more" or an "edit profile", there is a screen to return to and this is
 *   how. Only the host knows which, so it decides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImplementationPendingScreen(
    text: String,
    onNavigateBack: (() -> Unit)?,
) {

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                // The name of the thing that is not built yet, which is what was tapped.
                title = { Text(text) },
                navigationIcon = {
                    if (onNavigateBack != null) NavigateBackButton(onNavigateBack)
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
        ) {
            Column(
                modifier = Modifier.padding(MaterialTheme.spacing.space250).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(Res.string.functionality_coming_soon_2, text),

                )
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun ImplementationPendingScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            ImplementationPendingScreen(
                text = "Sign in",
                onNavigateBack = {},
            )
        }
    }
}