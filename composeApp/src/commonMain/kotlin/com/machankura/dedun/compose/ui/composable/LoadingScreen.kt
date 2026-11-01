package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.dedun.compose.ui.theme.readableContent
import com.machankura.dedun.compose.ui.theme.ConformancePreviews

@Composable
fun LoadingScreen(
    text: String? = null
) {
    Scaffold(snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) }) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding)
            .readableContent()
        ) {
            com.machankura.dedun.compose.ui.composable.widgets.LoadingDataIndicator(
                text = text
            )
        }
    }
}

@ConformancePreviews
@Composable
private fun LoadingScreenPreview() {
    com.machankura.dedun.compose.ui.theme.BennuTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            LoadingScreen(
                text = "Starting up"

            )
        }
    }
}