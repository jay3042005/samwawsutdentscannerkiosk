package org.example.project.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class USBScannerPlatform {
    
    actual fun startListening(onScan: (String) -> Unit) {
        // On Android, USB scanner support would require USB Host API
        // For now, do nothing (not supported)
    }
    
    actual fun stopListening() {
        // No-op for Android
    }
}

@Composable
actual fun rememberUSBScannerPlatform(): USBScannerPlatform {
    return remember { USBScannerPlatform() }
} 