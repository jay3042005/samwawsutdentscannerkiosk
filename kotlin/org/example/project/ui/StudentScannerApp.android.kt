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

import com.studentscanner.data.LocalStorageManager
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.deped.studentscanner.R
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun StudentScannerApp(
    scannedStudent: StudentResponse?,
    scanError: String?,
    onQRCodeDismiss: () -> Unit = {},
    onStudentDismissed: () -> Unit = {}
) {
    val context = LocalContext.current
    val localStorageManager = remember { LocalStorageManager(context) }
    var showSchoolSettings by remember { mutableStateOf(false) }
    var showQRCodeDisplay by remember { mutableStateOf(false) }
    var qrCodeBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Load school settings for display
    var schoolSettings by remember { mutableStateOf(SchoolSettings()) }
    LaunchedEffect(Unit) {
        val loaded = localStorageManager.loadSchoolTime()
        if (loaded != null) {
            schoolSettings = loaded
        }
    }

    // Show school settings dialog if triggered
    if (showSchoolSettings) {
        AndroidSchoolSettingsScreen(
            onDismiss = { showSchoolSettings = false }
        )
    } else if (showQRCodeDisplay && qrCodeBitmap != null) {
        QRCodeDisplayDialog(
            qrCodeBitmap = qrCodeBitmap!!,
            onDismiss = onQRCodeDismiss
        )
    } else {
        // Main Student Scanner App         Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // DepEd Logo
                Image(
                    painter = painterResource(id = R.drawable.deped_logo),
                    contentDescription = "DepEd Logo",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Original content of StudentScannerApp
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Department of Education",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Student Scanner Kiosk",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Display scanned student info or error
                    if (scannedStudent != null) {
                        StudentInfoCard(scannedStudent) {
                            onStudentDismissed()
                        }
                    } else if (scanError != null) {
                        ErrorCard(scanError)
                    } else {
                        Text(
                            text = "Scan a student ID to begin",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Buttons for settings and manual input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { showSchoolSettings = true },
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        ) {
                            Text("Settings")
                        }
                        Button(
                            onClick = { /* Trigger manual input dialog in MainActivity */ },
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        ) {
                            Text("Manual Input")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    StudentScannerApp(
        scannedStudent = null,
        scanError = null
    )
}