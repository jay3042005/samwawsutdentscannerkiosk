package org.example.project.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

@Composable
fun QRCodeDisplayDialog(
    qrCodeBitmap: Bitmap,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan this QR Code") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = qrCodeBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(240.dp)
                )
            }
        },
        confirmButton = {}, // Remove the close button
    )
} 