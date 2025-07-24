package org.example.project.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

@Composable
actual fun QRCodeImage(
    bitmap: Bitmap,
    modifier: Modifier
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR Code for Authentication",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}