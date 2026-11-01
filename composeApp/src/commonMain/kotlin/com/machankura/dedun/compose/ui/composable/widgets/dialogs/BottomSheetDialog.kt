/*
 * Copyright 2024 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.machankura.dedun.compose.ui.composable.widgets.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.theme.spacing

/** Provides a Material3 [ModalBottomSheet] with some presets. Content is contained in a [Column] with [internalPadding]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    scrimAlpha: Float = 0.2f,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults. DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    contentHeight: Dp = Dp.Unspecified,
    internalPadding: PaddingValues = PaddingValues(top = MaterialTheme.spacing.space0, start = MaterialTheme.spacing.space250, end = MaterialTheme.spacing.space250, bottom = MaterialTheme.spacing.space800),
    isContentScrollable: Boolean = true,
    dismissOnScrimClick: Boolean = true,
    dismissOnBack: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded, confirmValueChange = { dismissOnScrimClick })
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = {
            // executed when user click outside the sheet, and after sheet has been hidden thru state.
            onDismiss()
        },
        sheetMaxWidth = 500.dp,
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = dragHandle,
        contentWindowInsets = contentWindowInsets,
        scrimColor = MaterialTheme.colorScheme.onBackground.copy(alpha = scrimAlpha),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissOnBack)
    ) {
        Column(
            horizontalAlignment = horizontalAlignment,
            modifier = Modifier
                .fillMaxWidth()
                .height(contentHeight)
                .then(if (isContentScrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(internalPadding)
        ) {
            content()
        }
    }
}