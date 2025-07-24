package org.example.project.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.deped.studentscanner.bluetooth.StudentScannerBleManager
import com.deped.studentscanner.bluetooth.ScanLogTransferManager
import com.studentscanner.data.LocalStorageManager
import android.graphics.Bitmap
import kotlinx.serialization.encodeToString
import org.example.project.data.ScanLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Android-specific implementation for SMS notifications
actual fun triggerSMSNotification(studentId: String, action: String) {
    // This will be called from the Compose UI
    println("[SMS] 📱 Android SMS notification triggered: $studentId, $action")
    
    // Use broadcast method only (no direct call to prevent duplicates)
    try {
        val context = getCurrentContext()
        if (context != null) {
            val intent = Intent("com.deped.studentscanner.TRIGGER_SMS")
            intent.putExtra("studentId", studentId)
            intent.putExtra("action", action)
            context.sendBroadcast(intent)
            println("[SMS] ✅ SMS broadcast sent")
        } else {
            println("[SMS] ❌ Could not get context for SMS notification")
        }
    } catch (e: Exception) {
        println("[SMS] ❌ Error triggering SMS: ${e.message}")
    }
}

// Temporary context getter - in a real app, use proper dependency injection
private var currentContext: Context? = null

fun setCurrentContext(context: Context) {
    currentContext = context
}

private fun getCurrentContext(): Context? = currentContext 

// Android-specific BLE host implementation
@Composable
actual fun PlatformBLEHostSection(
    bleHostEnabled: Boolean,
    onBleHostToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val bleManager = remember { StudentScannerBleManager(context) }
    val localStorageManager = remember { LocalStorageManager(context) }
    val scanLogTransferManager = remember { ScanLogTransferManager(context, localStorageManager) }

    // State for QR code dialog
    var showQrDialog by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var sessionToken by remember { mutableStateOf("") }
    var qrDialogError by remember { mutableStateOf<String?>(null) }

    // On first composition, read persisted BLE Host state from SharedPreferences
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val savedBleHostEnabled = prefs.getBoolean("bleHostEnabled", false)
        if (savedBleHostEnabled != bleHostEnabled) {
            onBleHostToggle(savedBleHostEnabled)
        }
    }
    
    // Persist BLE Host toggle to SharedPreferences when changed
    LaunchedEffect(bleHostEnabled) {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("bleHostEnabled", bleHostEnabled).apply()
    }

    // Start/stop BLE advertising based on toggle
    LaunchedEffect(bleHostEnabled) {
        if (bleHostEnabled) {
            bleManager.startAdvertising {
                // Receiver detected!
                coroutineScope.launch {
                    // Start Bluetooth SPP server - QR code will be shown when Logger connects
                    scanLogTransferManager.startTransferServer(
                        onLoggerConnected = { loggerAddress ->
                            coroutineScope.launch {
                                try {
                                    println("[BLE HOST] 🎯 Logger connected: $loggerAddress")
                                    // Generate session token and QR code
                                    sessionToken = java.util.UUID.randomUUID().toString()
                                    val generatedBitmap = com.deped.studentscanner.utils.QRCodeGenerator.generateQRCode(sessionToken)
                                    if (generatedBitmap != null) {
                                        qrBitmap = generatedBitmap
                                        showQrDialog = true
                                        println("[BLE HOST] 🔐 Generated QR token: $sessionToken")
                                    } else {
                                        qrDialogError = "Failed to generate QR code bitmap."
                                        println("[BLE HOST] ❌ QR code bitmap generation failed!")
                                    }
                                } catch (e: Exception) {
                                    println("[BLE HOST] ❌ Error handling Logger connection: ${e.message}")
                                    e.printStackTrace()
                                }
                            }
                        },
                        onTransferComplete = { success, message ->
                            if (success) {
                                println("[BLE HOST] Bluetooth SPP transfer completed: $message")
                            } else {
                                println("[BLE HOST] Bluetooth SPP transfer failed: $message")
                            }
                        }
                    )
                }
            }
        } else {
            bleManager.stopAdvertising()
            showQrDialog = false
        }
    }

    // After QR code is scanned and verified by receiver, start Bluetooth SPP transfer
    fun onQrVerifiedByReceiver() {
        coroutineScope.launch {
            scanLogTransferManager.startTransferServer(
                onLoggerConnected = { loggerAddress ->
                    println("[BLE HOST] 🎯 Logger connected: $loggerAddress")
                },
                onQrCodeRequested = { loggerAddress ->
                    // Show QR code only when Logger requests it (perfect for kiosk mode)
                    coroutineScope.launch {
                        try {
                            println("[BLE HOST] 🔐 Logger requested QR code, showing dialog: $loggerAddress")
                            sessionToken = java.util.UUID.randomUUID().toString()
                            qrBitmap = com.deped.studentscanner.utils.QRCodeGenerator.generateQRCode(sessionToken)
                            showQrDialog = true
                            println("[BLE HOST] 🔐 Generated QR token for kiosk: $sessionToken")
                        } catch (e: Exception) {
                            println("[BLE HOST] ❌ Error generating QR code: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                },
                onTransferComplete = { success, message ->
                    if (success) {
                        println("[BLE HOST] Bluetooth SPP transfer completed: $message")
                    } else {
                        println("[BLE HOST] Bluetooth SPP transfer failed: $message")
                    }
                }
            )
        }
    }

    // Auto-hide QR dialog after 10 seconds
    LaunchedEffect(showQrDialog) {
        if (showQrDialog) {
            println("[BLE HOST] ⏰ QR dialog shown, will auto-hide in 10 seconds")
            kotlinx.coroutines.delay(10000) // 10 seconds
            if (showQrDialog) { // Check if still showing
                showQrDialog = false
                qrBitmap = null
                println("[BLE HOST] ⏰ QR dialog auto-hidden after 10 seconds")
            }
        }
    }

    // Show QR code dialog when needed
    if (showQrDialog && qrBitmap != null) {
        QRCodeDisplayDialog(
            qrCodeBitmap = qrBitmap!!,
            onDismiss = {
                showQrDialog = false
                qrBitmap = null
                println("[BLE HOST] 🔐 QR dialog manually dismissed")
            }
        )
    }
    // Show error notification if dialog fails
    if (qrDialogError != null) {
        // Fallback: show a toast or log error
        LaunchedEffect(qrDialogError) {
            val ctx = currentContext
            if (ctx != null) {
                android.widget.Toast.makeText(ctx, qrDialogError, android.widget.Toast.LENGTH_LONG).show()
            }
            println("[BLE HOST] ❌ QR dialog error: $qrDialogError")
            qrDialogError = null
        }
    }
} 