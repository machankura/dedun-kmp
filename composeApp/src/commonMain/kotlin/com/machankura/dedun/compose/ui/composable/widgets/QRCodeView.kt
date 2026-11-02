package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.machankura.dedun.compose.ui.theme.LocalExtendedColors
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrLogoPadding
import io.github.alexzhirkevich.qrose.options.QrLogoShape
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import org.jetbrains.compose.resources.painterResource
import dedun.composeapp.generated.resources.Res
import dedun.composeapp.generated.resources.compose_multiplatform

class QRCodeBackgroundPainter(
    private val painter: Painter,
    // No default. It used to be the `BluePill` literal, which meant a colour chosen
    // outside the theme for a surface that is almost never seen: at the default padding
    // of 0.dp the logo painter covers the rect it fills. Making the one call site pass
    // it keeps that visible rather than buried in a constructor default.
    private val backgroundColor: Color,
    private val padding: Dp = 0.dp,
): Painter() {
    override val intrinsicSize: Size = painter.intrinsicSize

    override fun DrawScope.onDraw() {
        val paddingPx = padding.toPx() // Convert Dp to Px

        // Calculate the size excluding the padding
        val paddedSize = Size(
            width = size.width - paddingPx * 2,
            height = size.height - paddingPx * 2
        )

        // Draw the white background
        drawRect(
            color = backgroundColor,
            size = size
        )

        // Draw the original painter with the padding
        with(painter) {
            // Translate to apply padding
            translate(left = paddingPx, top = paddingPx) {
                draw(size = paddedSize)
            }
        }
    }

}

@Composable
fun QRCodeView(
    data: String,
) {
    // m3-color-exempt: a QR code is read by a camera, not a person. Scanners need
    // maximum luminance contrast between the modules and their background, so these
    // are black and white rather than onSurface and surface -- which in a dynamic
    // colour scheme could be two mid tones and unscannable.
    val qrCodeColor = if (isSystemInDarkTheme()) {
        Color.White
    } else {
        Color.Black
    }

    val logoPainter = QRCodeBackgroundPainter(
        painter = painterResource(
            Res.drawable.compose_multiplatform
        ),
        backgroundColor = Color.Black,
    )

    val painter = rememberQrCodePainter(
        data = data,
    ) {
        logo {
            painter = logoPainter
            padding = QrLogoPadding.Natural(.1f)
            shape = QrLogoShape.Default
            size = 0.3f
        }
        colors {
            dark = QrBrush.solid(
                color = qrCodeColor,
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Image(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            painter = painter,
            contentDescription = data,
        )
    }
}