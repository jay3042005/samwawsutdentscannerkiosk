package com.deped.studentscanner

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import org.example.project.App
import org.example.project.ui.StudentScannerApp
import org.example.project.data.SchoolSettings
import org.example.project.ui.SchoolSettingsDialog
import org.example.project.ui.QRCodeDisplayDialog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

// BLE and Bluetooth imports


// PocketBase extension functions for server-based duplicate detection
// REMOVE: import com.deped.studentscanner.getScanLogs
// REMOVE: import com.deped.studentscanner.createScanLog

// Compose imports
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.CoroutineExceptionHandler

import com.studentscanner.data.LocalStorageManager
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.project.data.StudentResponse
import android.graphics.Bitmap
import android.widget.Toast

data class StudentSMSData(
    val fullName: String,
    val phoneNumber: String?
)

class MainActivity : ComponentActivity() {
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, exception ->
        println("[CRASH] Coroutine exception caught: ${exception.message}")
        exception.printStackTrace()
    })
    private var qrScanBuffer = StringBuilder()
    private var lastKeyTime = 0L
    private val scanTimeout = 200L // 200ms timeout between characters
    private var onQRScanned: ((String) -> Unit)? = null
    private var onVolumeUpPressed: (() -> Unit)? = null
    private var duplicateNotificationCallback: ((String) -> Unit)? = null

    // SMS functionality
    private lateinit var smsManager: SmsManager
    private var lastSMSData: String = "" // Track last SMS to prevent duplicates

    private var isServerStarting = false
    private var lastServerStartAttempt = 0L
    private val serverStartCooldown = 30000L // 30 seconds cooldown between start attempts

    private lateinit var resetFlagReceiver: BroadcastReceiver

    // BLE and Bluetooth functionality for LOgger integration


    private lateinit var localStorageManager: LocalStorageManager

    // Server status broadcast receiver
    private val serverStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.project.SERVER_STATUS" -> {
                    val status = intent.getStringExtra("status") ?: ""
                    showServerStatus(status)
                }
                "com.example.project.AUTO_START_FAILED" -> {
                    val error = intent.getStringExtra("error") ?: "Unknown error"
                    showServerError(error)
                    isServerStarting = false
                }
                "com.example.project.RETURN_TO_APP" -> {
                    // App should be brought to front - handled by the service
                }
            }
        }
    }

    private var csvImportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                if (uri != null) {
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        val csvContent = inputStream?.bufferedReader().use { it?.readText() } ?: ""
                        val success = localStorageManager.importStudentsFromCSV(csvContent)
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this,
                                if (success) "✅ Students imported from CSV!" else "❌ Failed to import students.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            this,
                            "❌ Error importing CSV: \\${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

    private val scannedStudentFlow = MutableStateFlow<StudentResponse?>(null)
    fun getScannedStudentFlow() = scannedStudentFlow.asStateFlow()

    private val scanErrorFlow = MutableStateFlow<String?>(null)
    fun getScanErrorFlow() = scanErrorFlow.asStateFlow()

    private val qrTokenFlow = MutableStateFlow<String?>(null)



    // Deduplication cache for relayed logs
    private val recentlyRelayedLogIds = LinkedHashSet<Int>()
    private fun isRecentlyRelayed(logId: Int): Boolean = recentlyRelayedLogIds.contains(logId)
    private fun markRelayed(logId: Int) {
        recentlyRelayedLogIds.add(logId)
        if (recentlyRelayedLogIds.size > 100) {
            recentlyRelayedLogIds.remove(recentlyRelayedLogIds.first())
        }
    }

    private var meshRelayJob: Job? = null



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localStorageManager = LocalStorageManager(this)

        // Check if this is an auto-start from boot or app update
        val autoStarted = intent.getBooleanExtra("auto_started", false)
        val bootReason = intent.getStringExtra("boot_reason")
        val unlockStarted = intent.getBooleanExtra("unlock_started", false)
        
        if (autoStarted) {
            println("[AUTO_START] 🚀 App auto-started due to: $bootReason")
        }
        
        if (unlockStarted) {
            println("[AUTO_START] 🔓 App started on device unlock")
        }



        // Register broadcast receiver for server status updates
        val filter = IntentFilter().apply {
            addAction("com.example.project.SERVER_STATUS")
            addAction("com.example.project.AUTO_START_FAILED")
            addAction("com.example.project.RETURN_TO_APP")
        }

        // TEMPORARY FIX: Disable broadcast receiver to fix Android 13+ crash
        // The AutoStartService will still work, but we won't get real-time status updates
        // TODO: Re-enable with proper Android 13+ flags once the issue is resolved
        /*
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(serverStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(serverStatusReceiver, filter)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to register broadcast receiver: ${e.message}")
        }
        */

        // Initialize coroutine scope
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // Initialize SMS manager
        smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        // Reset server starting flag on app startup to prevent stuck states
        isServerStarting = false
        println("[SERVER] 🔄 App started - reset server starting flag")

        // Register broadcast receiver for emergency flag reset
        resetFlagReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.deped.studentscanner.RESET_FLAG" -> {
                        resetServerStartingFlag()
                    }
                    "com.deped.studentscanner.TRIGGER_SMS" -> {
                        val studentId = intent.getStringExtra("studentId") ?: ""
                        val action = intent.getStringExtra("action") ?: "entry"
                        println("[SMS] 📱 Received SMS trigger broadcast: $studentId, $action")
                        if (studentId.isNotEmpty()) {
                            println("[SMS] 📱 Processing SMS for student: $studentId")
                            processStudentScanWithSMS(studentId)
                        } else {
                            println("[SMS] ❌ Empty student ID in broadcast")
                        }
                    }
                }
            }
        }

        try {
            val resetFilter = IntentFilter().apply {
                addAction("com.deped.studentscanner.RESET_FLAG")
                addAction("com.deped.studentscanner.TRIGGER_SMS")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(resetFlagReceiver, resetFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(resetFlagReceiver, resetFilter)
            }
            println("[SERVER] ✅ Emergency reset and SMS trigger receivers registered")
        } catch (e: Exception) {
            println("[SERVER] ⚠️ Could not register receivers: ${e.message}")
        }

        // Always check permissions at startup to ensure proper app functionality
        // This ensures permissions are requested even if they were denied previously
        

        // Check auto-start preference and trigger if enabled
        



        // Request permissions at runtime
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)

    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {

                println("[PERMISSION] Required permissions not granted. BLE/SPP will not start.")
            }
        }
    }





    private fun showServerStatus(status: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, status, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showServerError(error: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, "❌ $error", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Simple function to continue with permission checking after storage permission
    private fun continuePermissionCheck() {
        checkAndRequestPermissions()
    }

    

    

    // Add callback for path updates
    private var onPocketbasePathUpdate: ((String) -> Unit)? = null

    @Composable
    private fun AndroidSchoolSettingsScreen(onDismiss: () -> Unit) {
        val context = this
        val localStorageManager = remember { LocalStorageManager(context) }
        var schoolSettings by remember { mutableStateOf(SchoolSettings()) }


        val scope = rememberCoroutineScope()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        // Load settings on screen open
        LaunchedEffect(Unit) {
            scope.launch {
                val loaded = localStorageManager.loadSchoolTime()
                if (loaded != null) {
                    schoolSettings = loaded
                } else {
                    // Create default and save
                    val defaultSettings = SchoolSettings(
                        school_entry_start_time = "07:00",
                        school_entry_end_time = "08:00",
                        school_exit_start_time = "15:00",
                        school_exit_end_time = "17:00"
                    )
                    val saved = localStorageManager.saveSchoolTime(defaultSettings)
                    if (saved) schoolSettings = defaultSettings
                }
            }


        ComprehensiveSettingsDialog(
            schoolSettings = schoolSettings,

            onSchoolSettingsUpdate = { newSettings: SchoolSettings ->
                scope.launch {
                    val saved = localStorageManager.saveSchoolTime(newSettings)
                    if (saved) {
                        schoolSettings = newSettings
                        android.widget.Toast.makeText(
                            context,
                            "School time saved successfully!",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Failed to save school time.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onOpenFilePicker = {
                openPocketBaseFilePicker()
            },

            onDismiss = onDismiss
        )
    }

    @Composable
    private fun ComprehensiveSettingsDialog(
        schoolSettings: SchoolSettings,

        onSchoolSettingsUpdate: (SchoolSettings) -> Unit,
        onOpenFilePicker: () -> Unit,

        onDismiss: () -> Unit
    ) {
        var entryStartTime by remember { mutableStateOf("") }
        var entryEndTime by remember { mutableStateOf("") }
        var exitStartTime by remember { mutableStateOf("") }
        var exitEndTime by remember { mutableStateOf("") }

        // Update time fields when schoolSettings changes (convert from 24-hour to 12-hour format)
        LaunchedEffect(schoolSettings) {
            entryStartTime = convertTo12HourFormat(schoolSettings.school_entry_start_time)
            entryEndTime = convertTo12HourFormat(schoolSettings.school_entry_end_time)
            exitStartTime = convertTo12HourFormat(schoolSettings.school_exit_start_time)
            exitEndTime = convertTo12HourFormat(schoolSettings.school_exit_end_time)
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF5F5F5)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E3A8A)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // DepEd Logo
                        Image(
                            painter = painterResource(id = R.drawable.deped_logo),
                            contentDescription = "DepEd Logo",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "⚙️ Comprehensive Settings",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Student Scanner Configuration",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // School Time Settings Section
                SettingsSection(
                    title = "🕐 School Hours",
                    subtitle = "Configure entry and exit time windows"
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Entry Times
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF10B981).copy(alpha = 0.1f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "\uD83D\uDCDA School Entry Hours",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF059669)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Current: ${convertTo12HourFormat(schoolSettings.school_entry_start_time)}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF6B7280)
                                        )
                                        TimeInputField(
                                            label = "Start Time",
                                            value = entryStartTime,
                                            onValueChange = { entryStartTime = it },
                                            placeholder = "7:00 AM",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Current: ${convertTo12HourFormat(schoolSettings.school_entry_end_time)}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF6B7280)
                                        )
                                        TimeInputField(
                                            label = "End Time",
                                            value = entryEndTime,
                                            onValueChange = { entryEndTime = it },
                                            placeholder = "8:00 AM",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        // Exit Times
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFEF4444).copy(alpha = 0.1f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "\uD83D\uDEAA School Exit Hours",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFDC2626)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Current: ${convertTo12HourFormat(schoolSettings.school_exit_start_time)}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF6B7280)
                                        )
                                        TimeInputField(
                                            label = "Start Time",
                                            value = exitStartTime,
                                            onValueChange = { exitStartTime = it },
                                            placeholder = "3:00 PM",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Current: ${convertTo12HourFormat(schoolSettings.school_exit_end_time)}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF6B7280)
                                        )
                                        TimeInputField(
                                            label = "End Time",
                                            value = exitEndTime,
                                            onValueChange = { exitEndTime = it },
                                            placeholder = "5:00 PM",
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // App Behavior Settings Section
                SettingsSection(
                    title = "🔧 App Behavior",
                    subtitle = "Configure app startup and display options"
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                     }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // SMS Notifications Settings Section
                SettingsSection(
                    title = "📱 SMS Notifications",
                    subtitle = "Configure SMS alerts for student entry/exit"
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

                        var smsEnabled by remember { mutableStateOf(prefs.getBoolean("smsEnabled", false)) }
                        var blockDuplicates by remember { mutableStateOf(prefs.getBoolean("blockDuplicates", true)) }
                        var smsEntryTemplate by remember { mutableStateOf(prefs.getString("smsEntryTemplate", "\$name has entered school at \$time") ?: "\$name has entered school at \$time") }
                        var smsExitTemplate by remember { mutableStateOf(prefs.getString("smsExitTemplate", "\$name has left school at \$time") ?: "\$name has left school at \$time") }

                        SettingsSwitch(
                            label = "Enable SMS Notifications",
                            description = "Send SMS alerts when students scan their IDs",
                            checked = smsEnabled,
                            onCheckedChange = { value ->
                                smsEnabled = value
                                prefs.edit().putBoolean("smsEnabled", value).apply()
                            }
                        )

                        SettingsSwitch(
                            label = "Block Duplicate Scans",
                            description = "Prevent duplicate SMS within 5 minutes for same student",
                            checked = blockDuplicates,
                            onCheckedChange = { value ->
                                blockDuplicates = value
                                prefs.edit().putBoolean("blockDuplicates", value).apply()
                            }
                        )

                        OutlinedTextField(
                            value = smsEntryTemplate,
                            onValueChange = { value ->
                                smsEntryTemplate = value
                                prefs.edit().putString("smsEntryTemplate", value).apply()
                            },
                            label = { Text("Entry Message Template") },
                            placeholder = { Text("\$name has entered school at \$time") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = smsExitTemplate,
                            onValueChange = { value ->
                                smsExitTemplate = value
                                prefs.edit().putString("smsExitTemplate", value).apply()
                            },
                            label = { Text("Exit Message Template") },
                            placeholder = { Text("\$name has left school at \$time") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "Template Variables: \$name, \$time, \$last_entry, \$student_id, \$action",
                            fontSize = 12.sp,
                            color = Color(0xFF6B7280),
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Student Photos Settings Section
                SettingsSection(
                    title = "📸 Student Photos",
                    subtitle = "Configure photo folder location for student images"
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

                        var photoFolderPath by remember { mutableStateOf(prefs.getString("photoFolderPath", "") ?: "") }
                        var photoFolderName by remember { mutableStateOf(prefs.getString("photoFolderName", "") ?: "") }

                        // Photo folder path display
                        Column {
                            Text(
                                text = "Student Photo Folder:",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = if (photoFolderPath.isNotEmpty()) {
                                        if (photoFolderName.isNotEmpty()) {
                                            "📁 $photoFolderName\n📍 $photoFolderPath"
                                        } else {
                                            photoFolderPath
                                        }
                                    } else {
                                        ""
                                    },
                                    onValueChange = { /* Read-only */ },
                                    placeholder = { Text("No folder selected") },
                                    readOnly = true,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2
                                )
                                Button(
                                    onClick = { openPhotoFolderPicker() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF8B5CF6)
                                    )
                                ) {
                                    Text("Browse")
                                }
                            }

                            // Photo folder status indicator
                            if (photoFolderPath.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "✅ Photo folder configured - photos will be loaded by student ID",
                                    fontSize = 12.sp,
                                    color = Color(0xFF059669),
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }

                        // Photo format information
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF8B5CF6).copy(alpha = 0.1f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = "📋 Photo Requirements",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF7C3AED)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "• Photos must be named exactly as student ID (e.g., 12345.jpg)\n" +
                                            "• Supported formats: JPG, PNG\n" +
                                            "• Recommended size: 300x400 pixels\n" +
                                            "• Photos will be automatically cropped to fit",
                                    fontSize = 12.sp,
                                    color = Color(0xFF374151)
                                )
                            }
                        }

                        // Test photo loading
                        if (photoFolderPath.isNotEmpty()) {
                            Button(
                                onClick = { testPhotoLoading() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF10B981)
                                )
                            ) {
                                Text("🧪 Test Photo Loading")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            // Convert 12-hour format back to 24-hour format for storage
                            val updatedSettings = schoolSettings.copy(
                                school_entry_start_time = convertTo24HourFormat(entryStartTime),
                                school_entry_end_time = convertTo24HourFormat(entryEndTime),
                                school_exit_start_time = convertTo24HourFormat(exitStartTime),
                                school_exit_end_time = convertTo24HourFormat(exitEndTime)
                            )
                            onSchoolSettingsUpdate(updatedSettings)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF059669)
                        )
                    ) {
                        Text("Save All Settings")
                    }
                }

                // Add Import CSV button at the end of the dialog
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "text/csv"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        csvImportLauncher.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("📥 Import Students from CSV")
                }
            }
        }
    }

    @Composable
    private fun SettingsSection(
        title: String,
        subtitle: String,
        content: @Composable () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937)
                    )
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        color = Color(0xFF6B7280)
                    )
                }
                content()
            }
        }
    }

    @Composable
    private fun SettingsSwitch(
        label: String,
        description: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280)
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }

    @Composable
    private fun TimeInputField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        modifier: Modifier = Modifier
    ) {
        // Extract time and AM/PM from value
        val match = Regex("""^([0-9]{0,2}:?[0-9]{0,2})\s*(AM|PM)?$""", RegexOption.IGNORE_CASE).find(value.trim())
        var timeValue by remember { mutableStateOf(match?.groupValues?.getOrNull(1) ?: "") }
        var amPm by remember { mutableStateOf(match?.groupValues?.getOrNull(2)?.uppercase() ?: "AM") }

        Row(modifier = modifier) {
            OutlinedTextField(
                value = timeValue,
                onValueChange = {
                    // Allow empty, partial, or valid time input
                    if (
                        it.isEmpty() ||
                        it.matches(Regex("^[0-9]{0,2}$")) ||
                        it.matches(Regex("^[0-9]{0,2}:$")) ||
                        it.matches(Regex("^[0-9]{0,2}:[0-9]{0,2}$"))
                    ) {
                        timeValue = it
                        onValueChange(if (it.isNotEmpty()) "$it $amPm" else "")
                    }
                },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    amPm = if (amPm == "AM") "PM" else "AM"
                    if (timeValue.isNotEmpty()) onValueChange("$timeValue $amPm")
                },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .align(Alignment.CenterVertically)
            ) {
                Text(amPm)
            }
        }
        Text(
            text = "Format: 7:00 AM, 3:30 PM",
            fontSize = 10.sp,
            color = Color(0xFF6B7280)
        )
    }

    
    

    private fun runCommand(cmd: Array<String>): String {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun showManualInputDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Manual Student ID Test")
        builder.setMessage("Enter a student ID to test the lookup system:")

        val input = android.widget.EditText(this)
        input.hint = "Enter Student ID (e.g., test, STU123456)"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        builder.setPositiveButton("Test") { dialog, _ ->
            val studentId = input.text.toString().trim()
            println("[DEBUG] Manual input triggers scan for: '$studentId'")
            // ...
            processStudentScanWithSMS(studentId)
            onQRScanned?.invoke(studentId)
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }

    override fun onResume() {
        super.onResume()

        // Check auto-start on resume with reduced cooldown
        if (!isServerStarting && System.currentTimeMillis() - lastServerStartAttempt > 5000) {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("autoStart", true) // Default to true


        }
    }



    // Function for auto-start server using background RUN_COMMAND


    // Function to create auto-start script


    // Function for manual server startup  


    // Function to manually trigger auto-start for testing


    // Function to check auto-start configuration

    private suspend fun startManualServerProcess() {
        android.widget.Toast.makeText(this, "🚀 Starting PocketBase server manually...", android.widget.Toast.LENGTH_LONG).show()

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val pocketbasePath = prefs.getString("pocketbasePath", "") ?: ""
        val backgroundExecution = prefs.getBoolean("backgroundExecution", false)

        // Step 1: Validate configuration
        if (pocketbasePath.isEmpty()) {
            android.widget.Toast.makeText(this, "❌ Please configure PocketBase binary path in settings first", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // Step 3: Prepare shared storage
        if (!prepareManualServerFiles(pocketbasePath)) {
            android.widget.Toast.makeText(this, "❌ Failed to prepare server files", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // Step 4: Choose execution method based on settings
        if (backgroundExecution) {
            executeManualServerViaRunCommand()
        } else {
            executeManualServerViaTermux()
        }
    }

    private suspend fun prepareManualServerFiles(pocketbasePath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                println("[MANUAL] 📁 Preparing manual server files...")

                // Handle both file paths and content URIs
                val sourceFile = if (pocketbasePath.startsWith("content://")) {
                    val appSpecificPath = "/storage/emulated/0/Android/data/com.deped.studentscanner/files/StudentScanner/pocketbase"
                    val appFile = File(appSpecificPath)
                    if (appFile.exists()) {
                        appFile
                    } else {
                        println("[MANUAL] ❌ Could not find PocketBase file")
                        return@withContext false
                    }
                } else {
                    File(pocketbasePath)
                }

                if (!sourceFile.exists() || !sourceFile.canRead()) {
                    println("[MANUAL] ❌ Source file not accessible: ${sourceFile.absolutePath}")
                    return@withContext false
                }

                val sharedServerDir = File("/storage/emulated/0/StudentScanner-Server")
                val targetFile = File(sharedServerDir, "pocketbase")

                // Create directory if needed
                if (!sharedServerDir.exists()) {
                    sharedServerDir.mkdirs()
                }

                // Copy binary
                sourceFile.copyTo(targetFile, overwrite = true)
                targetFile.setExecutable(true)

                // Create data directory
                val dataDir = File(sharedServerDir, "pb_data")
                if (!dataDir.exists()) {
                    dataDir.mkdirs()
                }

                // Create manual startup script
                createManualStartupScript(sharedServerDir.absolutePath)

                println("[MANUAL] ✅ Manual server files prepared successfully")
                true

            } catch (e: Exception) {
                println("[MANUAL] ❌ Error preparing files: ${e.message}")
                false
            }
        }
    }

    private fun createManualStartupScript(sharedServerDir: String) {
        val isAndroid14Plus = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val androidVersion = "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"

        val scriptContent = """#!/data/data/com.termux/files/usr/bin/bash
echo "🚀 DIRECT TERMUX POCKETBASE SERVER STARTUP"
echo "==========================================="
echo "📱 Android Version: $androidVersion"
echo "🚀 Manual startup initiated from Student Scanner app"
echo ""

# Stop any existing processes
echo "🛑 Stopping any existing PocketBase processes..."
pkill -f pocketbase 2>/dev/null || true
sleep 2

# Check and install requirements
echo "📦 Checking requirements..."
if ! command -v curl >/dev/null 2>&1; then
    echo "Installing curl..."
    pkg update -y && pkg install -y curl
fi

echo "✅ Environment ready!"
echo ""

# Start PocketBase server directly in Termux (no virtualization)
echo "🚀 Starting PocketBase server directly in Termux..."
cd /data/data/com.termux/files/home

if [ -f "$sharedServerDir/pocketbase" ]; then
    echo "📁 Copying PocketBase binary..."
    cp "$sharedServerDir/pocketbase" ./pocketbase
    chmod +x ./pocketbase
    
    if [ -x "./pocketbase" ]; then
        echo "✅ PocketBase binary ready"
        echo "📊 Binary size: $(ls -lh pocketbase | awk '{print $5}')"
        echo "🗄️ Setting up data directory..."
    mkdir -p pb_data
    
    echo "🌐 Starting server on http://localhost:8090..."
        nohup ./pocketbase serve --http=0.0.0.0:8090 --dir=./pb_data > server.log 2>&1 &
    SERVER_PID=${'$'}!
    
    echo "⏳ Waiting for server to start..."
    sleep 5
    
        # Direct Termux server connectivity testing (no PRoot limitations)
        echo "🔍 Testing server connectivity (Direct Termux)..."
    
    CURL_TEST="000"
    ATTEMPTS=0
    MAX_ATTEMPTS=3
    
        # Method 1: Direct curl test (should work in Termux)
    while [ "${'$'}CURL_TEST" = "000" ] && [ ${'$'}ATTEMPTS -lt ${'$'}MAX_ATTEMPTS ]; do
            ATTEMPTS=${'$'}(((ATTEMPTS + 1))
        echo "   Connectivity test attempt ${'$'}ATTEMPTS/${'$'}MAX_ATTEMPTS..."
        
        # Test with timeouts
        CURL_TEST=${'$'}(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 8 http://127.0.0.1:8090/api/health 2>/dev/null || echo "000")
        
        if [ "${'$'}CURL_TEST" != "000" ]; then
            echo "   ✅ HTTP Success: ${'$'}CURL_TEST"
            break
        fi
        
        CURL_TEST=${'$'}(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 8 http://localhost:8090/api/health 2>/dev/null || echo "000")
        
        if [ "${'$'}CURL_TEST" != "000" ]; then
            echo "   ✅ HTTP Success: ${'$'}CURL_TEST (localhost)"
            break
        fi
        
        [ ${'$'}ATTEMPTS -lt ${'$'}MAX_ATTEMPTS ] && sleep 2
    done
    
        # Method 2: Process verification
    echo "   Verifying server process..."
    PROCESS_RUNNING=false
    if ps aux | grep -v grep | grep -q "pocketbase.*serve"; then
        echo "   ✅ PocketBase process confirmed running"
        PROCESS_RUNNING=true
    else
        echo "   ⚠️ PocketBase process not detected in process list"
    fi
    
    # Method 3: Data directory check
    echo "   Checking data directory..."
    DATA_DIR_OK=false
    if [ -d "pb_data" ]; then
        echo "   ✅ PocketBase data directory exists"
        DATA_DIR_OK=true
    else
        echo "   ⚠️ PocketBase data directory not found"
    fi
    
    # Overall assessment
    SUCCESS_COUNT=0
        [ "${'$'}CURL_TEST" != "000" ] && SUCCESS_COUNT=${'$'}(((SUCCESS_COUNT + 1))
        [ "${'$'}PROCESS_RUNNING" = "true" ] && SUCCESS_COUNT=${'$'}(((SUCCESS_COUNT + 1))
        [ "${'$'}DATA_DIR_OK" = "true" ] && SUCCESS_COUNT=${'$'}(((SUCCESS_COUNT + 1))
    
    echo ""
    if [ ${'$'}SUCCESS_COUNT -ge 2 ]; then
            echo "✅ SUCCESS! PocketBase server is running"
        echo "🌐 Server URL: http://localhost:8090"
        echo "📊 Admin Panel: http://localhost:8090/_/"
        echo ""
            echo "💡 Direct Termux execution provides better performance and reliability"
            echo "   compared to virtualized environments."
        echo ""
        echo "📱 Server is ready! You can now return to Student Scanner app."
    else
        echo "❌ Server startup verification failed"
        echo "📊 Success indicators: ${'$'}SUCCESS_COUNT/3"
        echo "   HTTP Response: ${'$'}CURL_TEST"
        echo "   Process Running: ${'$'}PROCESS_RUNNING"
        echo "   Data Directory: ${'$'}DATA_DIR_OK"
        echo ""
        echo "🔧 Troubleshooting:"
        echo "   1. Check error messages above"
        echo "   2. Try: ./pocketbase --help"
        echo "   3. Verify binary permissions: ls -la pocketbase"
        echo "   4. Manual test: curl http://localhost:8090/api/health"
    fi
    
    echo "🔄 Server running in background (PID: ${'$'}SERVER_PID)"
    echo "💡 Keep this terminal open to monitor server"
    echo ""
    echo "📱 To return to Student Scanner app:"
    echo "   1. Press Home button"
    echo "   2. Open Student Scanner from recent apps"
    echo ""
    echo "🛑 To stop server: pkill -f pocketbase"
    echo ""
    
    # Monitor server
    echo "📊 Server monitoring started..."
    while true; do
        sleep 30
        if kill -0 ${'$'}SERVER_PID 2>/dev/null; then
            echo "${'$'}(date +%H:%M:%S) - Server running (PID: ${'$'}SERVER_PID)"
        else
            echo "${'$'}(date +%H:%M:%S) - ⚠️ Server process ended"
            break
        fi
        
        if curl -s http://localhost:8090/api/health >/dev/null 2>&1; then
            echo "${'$'}(date +%H:%M:%S) - API responding ✅"
        else
            echo "${'$'}(date +%H:%M:%S) - API not responding ❌"
        fi
    done
    else
        echo "❌ ERROR: PocketBase binary not executable"
        echo "🔧 Try: chmod +x ./pocketbase"
    fi
else
    echo "❌ ERROR: PocketBase binary not found"
    echo "📁 Expected location: $sharedServerDir/pocketbase"
    echo "📋 Available files:"
    ls -la "$sharedServerDir/" 2>/dev/null || echo "Cannot access shared directory"
    echo ""
    echo "💡 Please check PocketBase binary configuration in app settings"
    echo "Press Enter to continue..."
    read
fi

echo ""
echo "✅ Manual startup script completed!"
echo "📱 Return to Student Scanner app when ready"
            """.trimIndent()

        val scriptFile = File(sharedServerDir, "manual_start_pb.sh")
        scriptFile.writeText(scriptContent)
        scriptFile.setExecutable(true)

        println("[MANUAL] 📝 Manual startup script created: ${scriptFile.absolutePath}")
    }

    private fun executeManualServerViaRunCommand() {
        try {
            println("[MANUAL] 🚀 Starting server via RUN_COMMAND (background execution enabled)...")
            android.widget.Toast.makeText(this, "🚀 Starting server via RUN_COMMAND...", android.widget.Toast.LENGTH_SHORT).show()

            val scriptPath = "/storage/emulated/0/StudentScanner-Server/manual_start_pb.sh"

            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptPath))
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false) // Show output
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", 0)

            startService(intent)

            android.widget.Toast.makeText(this, "✅ Manual server command sent to Termux!\n📱 Check Termux for progress", android.widget.Toast.LENGTH_LONG).show()

            android.widget.Toast.makeText(this@MainActivity, "✅ Server command sent to Termux!\n📱 Check Termux for progress", android.widget.Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            println("[MANUAL] ❌ RUN_COMMAND failed: ${e.message}")
            // Fallback to Termux method
            executeManualServerViaTermux()
        }
    }

    private fun executeManualServerViaTermux() {
        try {
            println("[MANUAL] 📱 Opening Termux for manual server startup...")
            android.widget.Toast.makeText(this, "📱 Opening Termux for manual server startup...", android.widget.Toast.LENGTH_LONG).show()

            val scriptPath = "/storage/emulated/0/StudentScanner-Server/manual_start_pb.sh"

            // Show instructions dialog
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("🚀 Manual Server Startup")
            builder.setMessage("""
📱 TERMUX MANUAL STARTUP INSTRUCTIONS

Termux will now open. To start the PocketBase server:

1️⃣ In Termux terminal, run:
   bash $scriptPath

2️⃣ Wait for "Server is ready!" message

3️⃣ Return to Student Scanner app

💡 The script will:
• Install requirements automatically
• Start PocketBase server
• Show server status and monitoring

🔄 Keep Termux open in background for server to stay running
            """.trimIndent())

            builder.setPositiveButton("📱 Open Termux") { dialog, _ ->
                dialog.dismiss()

                try {
                    val intent = Intent()
                    intent.setClassName("com.termux", "com.termux.app.TermuxActivity")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)

                    android.widget.Toast.makeText(this, "Run: bash $scriptPath", android.widget.Toast.LENGTH_LONG).show()

                } catch (e: Exception) {
                    android.widget.Toast.makeText(this, "❌ Could not open Termux: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }

            builder.setNegativeButton("❌ Cancel") { dialog, _ ->
                dialog.dismiss()
            }

            builder.show()

        } catch (e: Exception) {
            println("[MANUAL] ❌ Termux method failed: ${e.message}")
            android.widget.Toast.makeText(this, "❌ Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Emergency function to reset server starting flag
    fun resetServerStartingFlag() {
        println("[SERVER] 🔄 === EMERGENCY RESET ===")
        println("[SERVER] 🔄 Old values - isServerStarting: $isServerStarting, lastServerStartAttempt: $lastServerStartAttempt")
        isServerStarting = false
        lastServerStartAttempt = 0L
        println("[SERVER] ✅ New values - isServerStarting: $isServerStarting, lastServerStartAttempt: $lastServerStartAttempt")
        android.widget.Toast.makeText(this, "✅ Server flag forcefully reset - you can try starting again", android.widget.Toast.LENGTH_SHORT).show()
    }

    // Platform-specific function to trigger SMS notifications from Compose
    fun triggerSMSNotification(studentId: String, action: String) {
        println("[SMS] 📱 Triggering SMS notification from Compose: $studentId, $action")
        processStudentScanWithSMS(studentId)
    }

    // Test SMS functionality
    fun testSMSFunctionality() {
        scope.launch {
            try {
                println("[SMS] 🧪 Testing SMS functionality...")

                // Check SMS permissions
                val smsPermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.SEND_SMS)
                val phonePermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE)

                println("[SMS] 📱 SMS Permission: ${if (smsPermission == PackageManager.PERMISSION_GRANTED) "✅ Granted" else "❌ Denied"}")
                println("[SMS] 📱 Phone Permission: ${if (phonePermission == PackageManager.PERMISSION_GRANTED) "✅ Granted" else "❌ Denied"}")

                // Check if app is default SMS app
                val isDefaultSmsApp = Telephony.Sms.getDefaultSmsPackage(this@MainActivity) == packageName
                println("[SMS] 📱 Default SMS App: ${if (isDefaultSmsApp) "✅ Yes" else "❌ No"}")

                // Check SMS settings
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val smsEnabled = prefs.getBoolean("smsEnabled", false)

                println("[SMS] 📱 SMS Enabled: ${if (smsEnabled) "✅ Yes" else "❌ No"}")

                if (smsEnabled) {
                    // Test SMS sending with emergency contact
                    processStudentScanWithSMS("TEST123")
                    android.widget.Toast.makeText(this@MainActivity, "🧪 SMS test triggered - check logs", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(this@MainActivity, "❌ SMS not enabled - enable in settings", android.widget.Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                println("[SMS] ❌ SMS test failed: ${e.message}")
                android.widget.Toast.makeText(this@MainActivity, "❌ SMS test failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Simple SMS test function
    fun testSimpleSMS() {
        try {
            println("[SMS] 🧪 Testing simple SMS...")

            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val smsEnabled = prefs.getBoolean("smsEnabled", false)

            if (!smsEnabled) {
                println("[SMS] ❌ SMS not enabled")
                android.widget.Toast.makeText(this, "❌ SMS not enabled", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            // Test SMS with emergency contact lookup
            processStudentScanWithSMS("TEST123")
            android.widget.Toast.makeText(this, "🧪 SMS test with emergency contact triggered", android.widget.Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            println("[SMS] ❌ Simple SMS test failed: ${e.message}")
            android.widget.Toast.makeText(this, "❌ SMS failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings() {
        try {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "❌ Could not open settings", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPocketBaseFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_TITLE, "Select PocketBase ARM64 Binary")
        intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
        startActivityForResult(intent, 1001)
    }

    private fun openPhotoFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.putExtra(Intent.EXTRA_TITLE, "Select Student Photos Folder")
        startActivityForResult(intent, 1004)
    }

    private fun testPhotoLoading() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val photoFolderPath = prefs.getString("photoFolderPath", "") ?: ""

        if (photoFolderPath.isEmpty()) {
            android.widget.Toast.makeText(this, "❌ No photo folder configured", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val photoFolder = File(photoFolderPath)
        if (!photoFolder.exists() || !photoFolder.isDirectory) {
            android.widget.Toast.makeText(this, "❌ Photo folder not accessible", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Count photos in folder
        val photoFiles = photoFolder.listFiles { file ->
            file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".png"))
        }

        val photoCount = photoFiles?.size ?: 0

        android.widget.Toast.makeText(
            this,
            "📸 Found $photoCount photos in folder\n📁 $photoFolderPath",
            android.widget.Toast.LENGTH_LONG
        ).show()

        // Show sample photos
        if (photoCount > 0) {
            val samplePhotos = photoFiles?.take(3)?.joinToString(", ") { it.name } ?: ""
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("📸 Photo Folder Test Results")
            builder.setMessage("""
                ✅ Photo folder is accessible
                
                📊 Statistics:
                • Total photos: $photoCount
                • Folder: $photoFolderPath
                
                📋 Sample photos:
                $samplePhotos
                
                💡 Photos will be loaded by student ID when scanning
            """.trimIndent())
            builder.setPositiveButton("✅ OK") { dialog, _ -> dialog.dismiss() }
            builder.show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            1001 -> { // File picker result
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        handlePocketBaseFileSelection(uri)
                    }
                }
            }
            1002 -> { // Storage permission result (legacy)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        android.widget.Toast.makeText(this, "✅ Storage permission granted!", android.widget.Toast.LENGTH_SHORT).show()
                        
                    } else {
                        android.widget.Toast.makeText(this, "❌ Storage permission required for app functionality", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            103 -> { // Storage permission result (new flow)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        println("[STORAGE] ✅ Storage permission granted, continuing with other permissions...")
                        android.widget.Toast.makeText(this, "✅ Storage access granted!", android.widget.Toast.LENGTH_SHORT).show()
                        // Continue with other permission requests
                        continueWithOtherPermissions()
                    } else {
                        println("[STORAGE] ❌ Storage permission denied, continuing with limited functionality...")
                        android.widget.Toast.makeText(this, "⚠️ Storage access denied - some features may be limited", android.widget.Toast.LENGTH_LONG).show()
                        // Continue with other permissions even if storage is denied
                        continueWithOtherPermissions()
                    }
                }
            }
            1003 -> { // Draw over apps permission result
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        android.widget.Toast.makeText(this, "✅ Display permission granted!", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(this, "⚠️ Auto-start may require manual confirmation", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                continueAppInitialization()
            }
            1004 -> { // Photo folder picker result
                if (resultCode == RESULT_OK) {
                    data?.data?.let { uri ->
                        handlePhotoFolderSelection(uri)
                    }
                }
            }
        }
    }

    private fun handlePocketBaseFileSelection(uri: Uri) {
        scope.launch {
            try {
                android.widget.Toast.makeText(this@MainActivity, "📂 Processing PocketBase binary...", android.widget.Toast.LENGTH_SHORT).show()

                // Get file info
                val fileName = getFileNameFromUri(uri) ?: "pocketbase"
                val fileSize = getFileSizeFromUri(uri)

                println("[FILE] 📁 Selected file: $fileName")
                println("[FILE] 📊 File size: ${fileSize}B")

                // Validate file (basic checks)
                if (fileSize < 1000000) { // Less than 1MB is probably not PocketBase
                    android.widget.Toast.makeText(this@MainActivity, "⚠️ Warning: File seems too small for PocketBase binary", android.widget.Toast.LENGTH_LONG).show()
                }

                // Use app's external files directory which we have guaranteed access to
                val appExternalDir = getExternalFilesDir("StudentScanner")
                if (appExternalDir == null) {
                    android.widget.Toast.makeText(this@MainActivity, "❌ Cannot access external storage", android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Ensure directory exists
                if (!appExternalDir.exists()) {
                    appExternalDir.mkdirs()
                }

                println("[FILE] 📁 Using app external directory: ${appExternalDir.absolutePath}")

                // Copy file to the app's external directory
                copyFileToLocation(uri, fileName, appExternalDir)

            } catch (e: Exception) {
                println("[FILE] ❌ Error handling file selection: ${e.message}")
                e.printStackTrace()
                android.widget.Toast.makeText(this@MainActivity, "❌ Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handlePhotoFolderSelection(uri: Uri) {
        try {
            println("[PHOTO] 📁 Processing photo folder selection...")

            // Get the folder path from URI
            val folderPath = getRealPathFromUri(uri)
            if (folderPath == null) {
                android.widget.Toast.makeText(this, "❌ Could not access selected folder", android.widget.Toast.LENGTH_LONG).show()
                return
            }

            val folder = File(folderPath)
            if (!folder.exists() || !folder.isDirectory) {
                android.widget.Toast.makeText(this, "❌ Selected path is not a valid folder", android.widget.Toast.LENGTH_LONG).show()
                return
            }

            // Check if folder contains photos
            val photoFiles = folder.listFiles { file: File ->
                file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".png"))
            }

            val photoCount = photoFiles?.size ?: 0

            // Save folder path to preferences
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("photoFolderPath", folderPath)
                .putString("photoFolderName", folder.name)
                .apply()

            println("[PHOTO] ✅ Photo folder configured: $folderPath")
            println("[PHOTO] 📊 Found $photoCount photos in folder")

            // Show success message
            val message = if (photoCount > 0) {
                "✅ Photo folder configured!\n📁 $folderPath\n📸 Found $photoCount photos"
            } else {
                "✅ Photo folder configured!\n📁 $folderPath\n⚠️ No photos found - add photos named by student ID"
            }

            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

            // Show instructions if no photos found
            if (photoCount == 0) {
                val builder = android.app.AlertDialog.Builder(this)
                builder.setTitle("📸 Photo Setup Instructions")
                builder.setMessage("""
                    📁 Folder: $folderPath
                    
                    📋 To add student photos:
                    1. Copy student photos to this folder
                    2. Name photos exactly as student ID (e.g., 12345.jpg)
                    3. Supported formats: JPG, PNG
                    4. Photos will be automatically loaded when scanning
                    
                    💡 Example:
                    • Student ID: 12345 → Photo: 12345.jpg
                    • Student ID: STU001 → Photo: STU001.png
                """.trimIndent())
                builder.setPositiveButton("✅ Got It") { dialog, _ -> dialog.dismiss() }
                builder.show()
            }

        } catch (e: Exception) {
            println("[PHOTO] ❌ Error handling photo folder selection: ${e.message}")
            e.printStackTrace()
            android.widget.Toast.makeText(this, "❌ Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun copyFileToLocation(uri: Uri, fileName: String, targetDir: File) {
        try {
            // Create the target file in the provided directory
            val targetFile = File(targetDir, fileName)
            println("[FILE] 📄 Target file: ${targetFile.absolutePath}")

            // Copy file with detailed error handling
            val success = copyUriToFileWithLogging(uri, targetFile)

            if (success) {
                // Make executable
                targetFile.setExecutable(true)
                println("[FILE] ✅ File made executable")

                // Verify file exists and is accessible
                if (targetFile.exists() && targetFile.canRead()) {
                    // Save the path
                    val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    prefs.edit().putString("pocketbasePath", targetFile.absolutePath).apply()
                    prefs.edit().putString("pocketbaseOriginalName", fileName).apply()
                    prefs.edit().putLong("pocketbaseFileSize", targetFile.length()).apply()

                    // Success feedback - show user-friendly path
                    val sizeStr = formatFileSize(targetFile.length())
                    val displayPath = "StudentScanner/$fileName"
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "✅ PocketBase binary ready!\n📁 File: $fileName\n📊 Size: $sizeStr\n🗂️ Location: $displayPath",
                        android.widget.Toast.LENGTH_LONG
                    ).show()

                    println("[FILE] ✅ PocketBase binary copied to: ${targetFile.absolutePath}")

                    // Trigger UI refresh
                    refreshSettingsUI()

                } else {
                    android.widget.Toast.makeText(this@MainActivity, "❌ Cannot access copied file", android.widget.Toast.LENGTH_LONG).show()
                }
            } else {
                android.widget.Toast.makeText(this@MainActivity, "❌ Failed to copy PocketBase binary", android.widget.Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            println("[FILE] ❌ Error copying to location: ${e.message}")
            e.printStackTrace()
            android.widget.Toast.makeText(this@MainActivity, "❌ Copy error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun getRealPathFromUri(uri: Uri): String? {
        return try {
            when {
                // Handle DocumentProvider URIs
                uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" -> {
                    val docId = uri.lastPathSegment
                    if (docId != null) {
                        val split = docId.split(":")
                        if (split.size >= 2) {
                            val type = split[0]
                            val path = split[1]
                            when (type) {
                                "primary" -> "/storage/emulated/0/$path"
                                else -> "/storage/$type/$path" // For SD cards, USB, etc.
                            }
                        } else null
                    } else null
                }
                // Handle file:// URIs
                uri.scheme == "file" -> uri.path
                // Fallback to URI path
                else -> uri.path
            }
        } catch (e: Exception) {
            println("[FILE] ❌ Error getting real path: ${e.message}")
            // Fallback: try to extract from URI string
            val uriString = uri.toString()
            when {
                uriString.contains("/storage/emulated/0/") -> {
                    val index = uriString.indexOf("/storage/emulated/0/")
                    uriString.substring(index)
                }
                uriString.contains("/sdcard/") -> {
                    val index = uriString.indexOf("/sdcard/")
                    "/storage/emulated/0" + uriString.substring(index + 7)
                }
                else -> null
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            println("[FILE] ❌ Error getting file name: ${e.message}")
            null
        }
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            println("[FILE] ❌ Error getting file size: ${e.message}")
            0L
        }
    }

    private fun copyUriToFileWithLogging(uri: Uri, targetFile: File): Boolean {
        return try {
            println("[FILE] 📋 Starting file copy...")
            println("[FILE] 📋 Source URI: $uri")
            println("[FILE] 📋 Target: ${targetFile.absolutePath}")

            // Ensure parent directory exists
            val parentDir = targetFile.parentFile
            if (parentDir != null && !parentDir.exists()) {
                println("[FILE] 📂 Creating parent directory: ${parentDir.absolutePath}")
                val created = parentDir.mkdirs()
                println("[FILE] 📂 Parent directory creation: $created")
            }

            var bytesCopied = 0L
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                    }
                    output.flush()
                }
            }

            println("[FILE] 📊 Bytes copied: $bytesCopied")
            println("[FILE] 📄 Target file exists: ${targetFile.exists()}")
            println("[FILE] 📄 Target file size: ${targetFile.length()}")

            val success = targetFile.exists() && targetFile.length() > 0
            println("[FILE] ✅ Copy success: $success")
            success

        } catch (e: Exception) {
            println("[FILE] ❌ Error copying file: ${e.message}")
            println("[FILE] ❌ Target path: ${targetFile.absolutePath}")
            println("[FILE] ❌ Parent dir exists: ${targetFile.parentFile?.exists()}")
            println("[FILE] ❌ Parent dir writable: ${targetFile.parentFile?.canWrite()}")
            e.printStackTrace()
            false
        }
    }

    private fun formatFileSize(sizeBytes: Long): String {
        return when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${sizeBytes / (1024 * 1024)}MB"
            else -> "${sizeBytes / (1024 * 1024 * 1024)}GB"
        }
    }

    private fun copyPocketBaseToSharedStorage(pocketbasePath: String): Boolean {
        return try {
            val sourceFile = File(pocketbasePath)
            if (!sourceFile.exists() || !sourceFile.canRead()) {
                println("[MainActivity] ❌ Source file not accessible: $pocketbasePath")
                return false
            }

            val pbFilename = sourceFile.name
            // Use a dedicated shared server folder that both app and Termux can access
            val sharedServerDir = File("/storage/emulated/0/StudentScanner-Server")
            val targetFile = File(sharedServerDir, pbFilename)

            // Ensure target directory exists
            if (!sharedServerDir.exists()) {
                val created = sharedServerDir.mkdirs()
                println("[MainActivity] 📂 Created shared server directory: $created")
            }

            // Copy file using Android file operations to shared storage
            sourceFile.copyTo(targetFile, overwrite = true)

            // Check if copy was successful
            if (targetFile.exists() && targetFile.length() > 0) {
                // Make the file executable
                val executableSet = targetFile.setExecutable(true)
                println("[MainActivity] ✅ File copied to shared server directory: ${targetFile.absolutePath}")
                println("[MainActivity] 🔧 Executable permission set: $executableSet")

                // Also create pb_data directory in shared location
                val dataDir = File(sharedServerDir, "pb_data")
                if (!dataDir.exists()) {
                    dataDir.mkdirs()
                    println("[MainActivity] 📂 Created shared data directory: ${dataDir.absolutePath}")
                }

                // Verify the copy was successful
                val verifySuccess = targetFile.exists() && targetFile.canRead() && targetFile.length() > 0
                println("[MainActivity] ✅ Copy verification: $verifySuccess (size: ${targetFile.length()} bytes)")
                verifySuccess
            } else {
                println("[MainActivity] ❌ File copy failed")
                false
            }
        } catch (e: SecurityException) {
            println("[MainActivity] ❌ Security exception during file copy: ${e.message}")
            false
        } catch (e: Exception) {
            println("[MainActivity] ❌ Error copying file: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun refreshSettingsUI() {
        // Update the pocketbasePath state to trigger UI refresh
        scope.launch {
            delay(500) // Small delay to ensure file operations complete

            // Read the updated path from preferences
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val updatedPath = prefs.getString("pocketbasePath", "") ?: ""
            val fileName = prefs.getString("pocketbaseOriginalName", "") ?: ""
            val fileSize = prefs.getLong("pocketbaseFileSize", 0L)

            // Trigger the path update callback if it's available
            onPocketbasePathUpdate?.invoke(updatedPath)

            android.widget.Toast.makeText(
                this@MainActivity,
                "🔄 UI updated - displaying: $fileName (${formatFileSize(fileSize)})",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Task-based stop server
    private fun stopPocketBaseServerWithTask() {
        val stopCommand = "pkill -f pocketbase"
        startPocketBaseServerWithTask(stopCommand)
    }

    // Update stopPocketBaseServer to use the task-based approach
    private fun stopPocketBaseServer() {
        stopPocketBaseServerWithTask()
    }

    private fun startPocketBaseServerWithTask(command: String) {
        try {
            val task = TaskManager.createTask(command)
            val intent = Intent()
            intent.setClassName("com.termux", "com.termux.app.RunCommandService")
            intent.action = "com.termux.RUN_COMMAND"
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", 0)

            // PendingIntent for result
            val resultIntent = Intent(this, PluginResultsService::class.java)
            resultIntent.putExtra("task_id", task.id)
            val pendingIntent = android.app.PendingIntent.getService(
                this, task.id, resultIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
            )
            intent.putExtra("com.termux.RUN_COMMAND_RESULT_PENDING_INTENT", pendingIntent)

            startService(intent)
            println("[SERVER] ✅ Termux command sent successfully")
        } catch (e: SecurityException) {
            println("[SERVER] ❌ Termux permission denied: ${e.message}")
            // Show user-friendly error message
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "❌ Termux permission denied\n\nPlease ensure:\n• Termux is installed\n• External apps are enabled\n• RUN_COMMAND permission granted",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            println("[SERVER] ❌ Error starting Termux command: ${e.message}")
            // Show user-friendly error message
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "❌ Failed to start server\n\nError: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    

    // Fix onNewIntent signature for ComponentActivity
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            "START_SERVER_TASK" -> {
                // No longer needed for local storage
            }
            "STOP_SERVER_TASK" -> {
                // No longer needed for local storage
            }
        }
    }

    // Simple permission checking using standard Android popups
    private fun checkAndRequestPermissions() {
        println("[PERMISSIONS] 🔍 Checking app permissions...")
        val requiredPermissions = mutableListOf<String>()

        // SMS permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.SEND_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        // Location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Notification permissions for Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Check for Android 11+ (API 30+) special storage permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                println("[PERMISSIONS] 💾 Requesting storage permission for Android 11+")
                requestStoragePermission()
                return // Wait for user to grant storage permission first
            }
        }

        // Request missing permissions directly using standard Android popups
        if (requiredPermissions.isNotEmpty()) {
            println("[PERMISSIONS] 📱 Requesting \\${requiredPermissions.size} permissions...")
            ActivityCompat.requestPermissions(this, requiredPermissions.toTypedArray(), 101)
        } else {
            println("[PERMISSIONS] ✅ All permissions already granted")
            // Configure kiosk behavior if enabled
            configureKioskBehavior()

            org.example.project.ui.setCurrentContext(this)
            setContent {
                val scannedStudent by getScannedStudentFlow().collectAsState()
                val scanError by getScanErrorFlow().collectAsState()
                val qrToken by qrTokenFlow.collectAsState()
                StudentScannerApp(
                    scannedStudent = scannedStudent,
                    scanError = scanError
                )
                if (qrToken != null) {
                    QRCodeDialog(token = qrToken!!, onDismiss = { qrTokenFlow.value = null })
                }
            }
        }




    // Volume key handlers
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle volume keys as before
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            onVolumeUpPressed?.invoke()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            showManualInputDialog()
            return true
        }

        // Handle physical keyboard scanning
        if (event != null && event.action == KeyEvent.ACTION_DOWN && event.unicodeChar != 0) {
            val now = System.currentTimeMillis()
            if (now - lastKeyTime > scanTimeout) {
                qrScanBuffer.clear()
            }
            lastKeyTime = now
            val c = event.unicodeChar.toChar()
            if (c == '\n' || c == '\r' || c == '\t') {
                val scannedId = qrScanBuffer.toString().trim()
                println("[DEBUG] Keyboard scan buffer: '${qrScanBuffer}', scannedId: '$scannedId'")
                if (scannedId.isNotEmpty()) {
                    println("[KEYBOARD SCAN] Student ID scanned: $scannedId")
                    // Directly process the scan (replace onQRScanned if needed)
                    processStudentScanWithSMS(scannedId)
                }
                qrScanBuffer.clear()
                return true
            } else if (!c.isISOControl()) {
                qrScanBuffer.append(c)
                println("[DEBUG] Appending char '$c' to buffer: ${qrScanBuffer}")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    // Server-based SMS processing with duplicate protection and school time validation
    fun processStudentScanWithSMS(studentId: String) {
        println("[SMS] 📱 Starting server-based SMS processing for student: $studentId")
        scope.launch {
            try {
                val schoolSettings = localStorageManager.loadSchoolTime() ?: org.example.project.data.SchoolSettings()
                val action = determineStudentAction(schoolSettings)
                if (action == null) {
                    val msg = "⏰ Outside school hours - scan ignored"
                    println("[SMS] $msg")
                    scanErrorFlow.value = msg
                    return@launch
                }
                if (localStorageManager.isDuplicateScan(studentId, action, schoolSettings)) {
                    val msg = "🚫 Duplicate scan blocked: Student already processed $action in this window"
                    println("[DUPLICATE] $msg")
                    scanErrorFlow.value = msg
                    return@launch
                }
                val studentInfo = getStudentInfoLocal(studentId)
                if (studentInfo == null) {
                    val msg = "❌ Student not found: $studentId"
                    println("[SMS] $msg")
                    scanErrorFlow.value = msg
                    return@launch
                }
                val now = System.currentTimeMillis()
                val scanLog = org.example.project.data.ScanLog(
                    student_id = studentId,
                    student_name = studentInfo["name"] as? String ?: "",
                    section = studentInfo["section"] as? String ?: "",
                    grade_level = (studentInfo["grade_level"]?.toString()?.toIntOrNull() ?: 0),
                    entry_exit_status = action,
                    timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now)),
                    scan_timestamp = now
                )
                localStorageManager.saveScanLog(scanLog)
                val studentResponse = org.example.project.data.StudentResponse(
                    id_no = studentId,
                    full_name = studentInfo["name"] as? String ?: "",
                    school_year = "",
                    grade_level = (studentInfo["grade_level"]?.toString() ?: ""),
                    section = studentInfo["section"] as? String ?: "",
                    address = "",
                    contact_no = studentInfo["parent_phone"] as? String ?: "",
                    image_url = "",
                    entry_time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                )
                println("[DEBUG] Sending student to UI: $studentResponse")
                scannedStudentFlow.value = studentResponse
                scanErrorFlow.value = null
                org.example.project.ui.setCurrentContext(this@MainActivity)
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val smsEnabled = prefs.getBoolean("smsEnabled", false)
                val smsEntryTemplate = prefs.getString("smsEntryTemplate", "\$name has entered school at \$time") ?: "\$name has entered school at \$time"
                val smsExitTemplate = prefs.getString("smsExitTemplate", "\$name has left school at \$time") ?: "\$name has left school at \$time"
                val smsSimIndex = prefs.getInt("smsSimIndex", 0)
                val template = if (action == "entry") smsEntryTemplate else smsExitTemplate
                val currentTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                val message = template
                    .replace("\$name", studentInfo["name"] as? String ?: "")
                    .replace("\$time", currentTime)
                    .replace("\$action", action)
                if (smsEnabled) {
                    println("[SMS] 📲 Sending SMS to ${studentInfo["parent_phone"]}: $message")
                    val success = sendSMS(studentInfo["parent_phone"] as? String ?: "", message, smsSimIndex)
                    if (!success) {
                        val msg = "❌ Failed to send SMS"
                        println("[SMS] $msg")
                        scanErrorFlow.value = msg
                    }
                }
            } catch (e: Exception) {
                val msg = "❌ Scan processing error: ${e.message}"
                println("[SMS] $msg")
                e.printStackTrace()
                scanErrorFlow.value = msg
            }
        }
    }

    // Determine student action (entry/exit) based on school time windows
    private fun determineStudentAction(schoolSettings: org.example.project.data.SchoolSettings): String? {
        val now = java.util.Calendar.getInstance()
        val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val entryStart = parseTimeToMinutes(schoolSettings.school_entry_start_time)
        val entryEnd = parseTimeToMinutes(schoolSettings.school_entry_end_time)
        val exitStart = parseTimeToMinutes(schoolSettings.school_exit_start_time)
        val exitEnd = parseTimeToMinutes(schoolSettings.school_exit_end_time)
        return when {
            nowMinutes in entryStart..entryEnd -> "entry"
            nowMinutes in exitStart..exitEnd -> "exit"
            else -> null
        }
    }

    private fun parseTimeToMinutes(time: String): Int {
        val parts = time.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    // Server-based duplicate detection with school time validation
    private suspend fun isDuplicateScan(studentId: String, action: String, blockMinutes: Int): Boolean {
        try {
            println("[DUPLICATE] 🔍 Server-based duplicate check for student: $studentId, action: $action")
            // Load school settings
            val schoolSettings = localStorageManager.loadSchoolTime() ?: org.example.project.data.SchoolSettings()
            // First validate if we're within school hours
            val currentAction = determineStudentAction(schoolSettings)
            if (currentAction == null) {
                println("[DUPLICATE] ⏰ Outside school hours - no duplicate check needed")
                return false
            }
            
            println("[DUPLICATE] 🎯 Within school hours - checking for any recent scans (current action would be: $currentAction, requested: $action)")
            
            val currentTime = System.currentTimeMillis()
            val blockTimeMs = blockMinutes * 60 * 1000L
            
            println("[DUPLICATE] 📡 Querying scan_log API for recent scans within $blockMinutes minutes")
            
            // Get recent scans from PocketBase scan_log API
            val recentScans = getRecentScans(studentId, blockTimeMs)
            
            // Check for duplicates - any scan within the time window should be considered a duplicate
            val duplicateFound = recentScans.any { scan ->
                val scanAction = scan["action"] as? String
                val scanTime = scan["created"] as? String
                val scanTimestamp = scan["timestamp"] as? Long
                
                if (scanTime != null || scanTimestamp != null) {
                    val scanTimeMs = if (scanTimestamp != null) {
                        scanTimestamp
                    } else {
                        parseISOTime(scanTime!!)
                    }
                    
                    val timeDifference = currentTime - scanTimeMs
                    val isDuplicate = timeDifference < blockTimeMs
                    
                    if (isDuplicate) {
                        val minutesAgo = timeDifference / 60000
                        println("[DUPLICATE] 🚫 DUPLICATE FOUND: Recent scan '$scanAction' performed $minutesAgo minutes ago (blocking '$action')")
                    }
                    
                    isDuplicate
                } else {
                    false
                }
            }
            
            if (!duplicateFound) {
                println("[DUPLICATE] ✅ No duplicates found - scan allowed")
            }
            
            return duplicateFound
            
        } catch (e: Exception) {
            println("[DUPLICATE] ❌ Error in server-based duplicate check: ${e.message}")
            e.printStackTrace()
            return false // Allow scan if check fails to avoid blocking valid scans
        }
    }

    // Record student scan in PocketBase
    private suspend fun recordStudentScan(studentId: String, action: String) {
        try {
            val scanData = mapOf(
                "student_id" to studentId,
                "action" to action,
                "timestamp" to System.currentTimeMillis(),
                "created" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).format(Date())
            )
            
            // Save to PocketBase (implement based on your PocketBase setup)
            saveScanToPocketBase(scanData)
            println("[RECORD] ✅ Scan recorded: $studentId - $action")
        } catch (e: Exception) {
            println("[RECORD] ❌ Error recording scan: ${e.message}")
        }
    }

    // Server-based duplicate detection with school time validation for student data view
    suspend fun shouldBlockStudentScan(studentId: String): Boolean {
        return try {
            println("[DUPLICATE] 🔍 Server-based duplicate check for student data view: $studentId")
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val blockDuplicates = prefs.getBoolean("blockDuplicates", true)
            if (!blockDuplicates) {
                println("[DUPLICATE] ⏸️ Duplicate blocking disabled in settings - allowing scan")
                return false
            }
            // Load school settings
            val schoolSettings = localStorageManager.loadSchoolTime() ?: org.example.project.data.SchoolSettings()
            // First check if we're within school hours
            val action = determineStudentAction(schoolSettings)
            if (action == null) {
                println("[DUPLICATE] ⏰ Outside school hours - allowing scan (no duplicate check needed)")
                return false
            }
            
            println("[DUPLICATE] 🏠 Within school hours - action determined as: $action")
            
            val duplicateBlockMinutes = prefs.getInt("duplicateBlockMinutes", 30)
            
            // Perform server-based duplicate check using scan_log API
            val isDuplicate = isDuplicateScan(studentId, action, duplicateBlockMinutes)
            
            if (isDuplicate) {
                println("[DUPLICATE] 🚫 BLOCKING STUDENT DATA VIEW: Server detected duplicate $action scan for $studentId within $duplicateBlockMinutes minutes")
                
                // Show blocking notification to user
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "🚫 Duplicate scan blocked: Student already processed within $duplicateBlockMinutes minutes",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                
                // Call duplicate notification callback if set
                duplicateNotificationCallback?.invoke("Server-based duplicate scan blocked for student $studentId")
                
                return true
            } else {
                println("[DUPLICATE] ✅ Server confirms no duplicate - allowing student data view")
                return false
            }
            
        } catch (e: Exception) {
            println("[DUPLICATE] ❌ Error in server-based duplicate check: ${e.message}")
            e.printStackTrace()
            return false // Allow scan if check fails to avoid blocking valid scans
        }
    }
    
    // Helper functions for PocketBase operations
    private suspend fun getLastScanToday(studentId: String): String? {
        return try {
            println("[SCAN_HISTORY] 🔍 Getting last scan today for student: $studentId")
            
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val scanLogs = localStorageManager.getScanLogsByDate(today)
                .filter { it.student_id == studentId }
                .sortedByDescending { it.timestamp }
            
            if (scanLogs.isNotEmpty()) {
                val lastScan = scanLogs.first()
                val action = lastScan.entry_exit_status
                println("[SCAN_HISTORY] 📝 Last scan today: $action")
                return action
            }
            
            println("[SCAN_HISTORY] ✅ No scans found today for student $studentId")
            return null
        } catch (e: Exception) {
            println("[SCAN_HISTORY] ❌ Error getting last scan: ${e.message}")
            return null
        }
    }

    private suspend fun getRecentScans(studentId: String, timeRangeMs: Long): List<Map<String, Any>> {
        return try {
            println("[DUPLICATE] 🔍 Checking recent scans for student: $studentId within ${timeRangeMs}ms")
            
            val currentTime = System.currentTimeMillis()
            val cutoffTime = currentTime - timeRangeMs
            val fromDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).format(Date(cutoffTime))
            val toDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).format(Date(currentTime))
            
            // Query local storage for recent scan logs
            val scanLogs = localStorageManager.getScanLogsByStudent(studentId)
                .filter { scanLog ->
                    try {
                        val scanTimestamp = scanLog.scan_timestamp
                        scanTimestamp >= cutoffTime
                    } catch (e: Exception) {
                        false
                    }
                }
            
            println("[DUPLICATE] 📊 Found ${scanLogs.size} recent scans for student $studentId")
            
            scanLogs.forEach { scanLog ->
                val action = scanLog.entry_exit_status
                val timeStr = try {
                    val scanTimestamp = scanLog.scan_timestamp
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(scanTimestamp))
                } catch (e: Exception) {
                    scanLog.timestamp
                }
                println("[DUPLICATE] 📝 Recent scan: $action at $timeStr")
            }
            
            // Convert to Map format for compatibility
            val scans = scanLogs.map { scanLog ->
                mapOf(
                    "student_id" to scanLog.student_id,
                    "action" to scanLog.entry_exit_status,
                    "created" to scanLog.timestamp,
                    "timestamp" to scanLog.scan_timestamp
                )
            }
            
            return scans
        } catch (e: Exception) {
            println("[DUPLICATE] ❌ Error querying recent scans: ${e.message}")
            e.printStackTrace()
            return emptyList() // Return empty list on error to allow scan
        }
    }
    
    private suspend fun saveScanToPocketBase(scanData: Map<String, Any>) {
        return try {
            println("[RECORD] 💾 Saving scan to PocketBase: ${scanData["student_id"]} - ${scanData["action"]}")
            
            // Get student info to create proper scan log
            val studentId = scanData["student_id"] as String
            val action = scanData["action"] as String
            val timestamp = scanData["created"] as String
            
            // Get student details for the scan log
            val student = localStorageManager.getStudentById(studentId)
            if (student != null) {
                // Create ScanLog object using the correct data structure
                val scanLog = org.example.project.data.ScanLog(
                    student_id = studentId,
                    student_name = "${student.firstName} ${student.lastName}",
                    section = student.section,
                    grade_level = student.grade.toIntOrNull() ?: 0,
                    entry_exit_status = action,
                    timestamp = timestamp,
                    scan_timestamp = System.currentTimeMillis()
                )
                
                val result = localStorageManager.saveScanLog(scanLog)
                
                if (result) {
                    println("[RECORD] ✅ Scan log saved successfully")
                    println("[RECORD] 📄 Student: ${scanLog.student_name} - ${scanLog.entry_exit_status}")
                } else {
                    println("[RECORD] ❌ Failed to save scan log")
                }
            } else {
                println("[RECORD] ❌ Student data not found for ID: $studentId")
            }
        } catch (e: Exception) {
            println("[RECORD] ❌ Error saving scan to PocketBase: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun parseISOTime(isoTime: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).parse(isoTime)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Request storage permission for Android 11+
    private fun requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                println("[STORAGE] 💾 Requesting All Files Access permission...")
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, 103)
            }
        } catch (e: Exception) {
            println("[STORAGE] ❌ Error requesting storage permission: ${e.message}")
            // Fallback: continue with other permissions
            continueWithOtherPermissions()
        }
    }

    // Continue with other permission requests after storage permission
    private fun continueWithOtherPermissions() {
        println("[PERMISSIONS] 🔄 Continuing with other permission requests...")
        // Re-call checkAndRequestPermissions but skip storage check
        val requiredPermissions = mutableListOf<String>()

        // SMS permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.SEND_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }



        // Location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Notification permissions for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Request all remaining permissions
        if (requiredPermissions.isNotEmpty()) {
            println("[PERMISSIONS] 📱 Requesting ${requiredPermissions.size} permissions...")
            ActivityCompat.requestPermissions(this, requiredPermissions.toTypedArray(), 101)
        } else {
            println("[PERMISSIONS] ✅ All permissions already granted")
            // Configure kiosk behavior if enabled
            configureKioskBehavior()

            org.example.project.ui.setCurrentContext(this)
            setContent {
                val scannedStudent by getScannedStudentFlow().collectAsState()
                val scanError by getScanErrorFlow().collectAsState()
                val qrToken by qrTokenFlow.collectAsState()
                StudentScannerApp(
                    scannedStudent = scannedStudent,
                    scanError = scanError
                )
                if (qrToken != null) {
                    QRCodeDialog(token = qrToken!!, onDismiss = { qrTokenFlow.value = null })
                }
            }
        }



    // Server-based student info retrieval from PocketBase
    private suspend fun getStudentInfoLocal(studentId: String): Map<String, Any>? {
        val student = localStorageManager.getStudentByIdNo(studentId)
        return if (student != null) {
            mapOf(
                "id" to student.id,
                "name" to "${student.firstName} ${student.lastName}",
                "grade_level" to (student.grade ?: 0),
                "section" to (student.section ?: ""),
                "parent_phone" to (student.guardianPhone ?: "")
            )
        } else null
    }







    // Helper function to convert time string to minutes since midnight
    private fun convertTimeToMinutes(timeString: String): Int {
        return try {
            val parts = timeString.split(":")
            val hours = parts[0].toInt()
            val minutes = parts[1].toInt()
            hours * 60 + minutes
        } catch (e: Exception) {
            println("[TIME_CONVERT] ❌ Error converting time '$timeString': ${e.message}")
            0
        }
    }



    private suspend fun sendSMS(phoneNumber: String, message: String, simIndex: Int): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
                val subscriptionInfoList = subscriptionManager.activeSubscriptionInfoList
                if (subscriptionInfoList != null && subscriptionInfoList.size > simIndex) {
                    val subscriptionId = subscriptionInfoList[simIndex].subscriptionId
                    android.telephony.SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
                } else {
                    android.telephony.SmsManager.getDefault()
                }
            } else {
                android.telephony.SmsManager.getDefault()
            }

            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            println("[SMS] ✅ SMS sent successfully to $phoneNumber")
            true
        } catch (e: Exception) {
            println("[SMS] ❌ Failed to send SMS: ${e.message}")
            false
        }
    }





    private ssage}")
        }
    }







    // Time format conversion functions
    private fun convertTo12HourFormat(time24: String): String {
        return try {
            val inputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val outputFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val date = inputFormat.parse(time24)
            outputFormat.format(date ?: Date())
        } catch (e: Exception) {
            println("[TIME] ❌ Error converting to 12-hour format: ${e.message}")
            time24 // Return original if conversion fails
        }
    }

    private fun convertTo24HourFormat(time12: String): String {
        return try {
            val inputFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val outputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val date = inputFormat.parse(time12)
            outputFormat.format(date ?: Date())
        } catch (e: Exception) {
            println("[TIME] ❌ Error converting to 24-hour format: ${e.message}")
            time12 // Return original if conversion fails
        }
    }

    // Diagnostic and initialization functions
    private fun runComprehensiveDiagnostic() {
        try {
            println("[DIAGNOSTIC] 🔍 Running comprehensive diagnostic...")
            // Run comprehensive system diagnostic
            // This is a placeholder - implement based on your diagnostic logic
        } catch (e: Exception) {
            println("[DIAGNOSTIC] ❌ Error running diagnostic: ${e.message}")
        }
    }

    private fun continueAppInitialization() {
        try {
            println("[INIT] 🚀 Continuing app initialization...")
            // Configure kiosk behavior if enabled
            configureKioskBehavior()

            org.example.project.ui.setCurrentContext(this)
            setContent {
                val scannedStudent by getScannedStudentFlow().collectAsState()
                val scanError by getScanErrorFlow().collectAsState()
                val qrToken by qrTokenFlow.collectAsState()
                StudentScannerApp(
                    scannedStudent = scannedStudent,
                    scanError = scanError
                )
                if (qrToken != null) {
                    QRCodeDialog(token = qrToken!!, onDismiss = { qrTokenFlow.value = null })
                }
            } catch (e: Exception) {
            println("[INIT] ❌ Error continuing initialization: ${e.message}")
        }
    }

    // Auto-start functionality helper functions
    private fun enableAutoStartOnBoot(enable: Boolean) {
        try {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("autoStartOnBoot", enable).apply()
            
            println("[AUTO_START] 🔄 Auto-start on boot ${if (enable) "enabled" else "disabled"}")
            
            // Show user feedback
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (enable) "✅ Auto-start on boot enabled" else "❌ Auto-start on boot disabled",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            println("[AUTO_START] ❌ Error setting auto-start preference: ${e.message}")
        }
    }
    
    private fun enableKioskMode(enable: Boolean) {
        try {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("kioskMode", enable).apply()
            
            println("[KIOSK] 🔄 Kiosk mode ${if (enable) "enabled" else "disabled"}")
            
            // Show user feedback
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (enable) "✅ Kiosk mode enabled - app will auto-start" else "❌ Kiosk mode disabled",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            println("[KIOSK] ❌ Error setting kiosk mode: ${e.message}")
        }
    }
    
    private fun enableLaunchOnUnlock(enable: Boolean) {
        try {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("launchOnUnlock", enable).apply()
            
            println("[AUTO_START] 🔓 Launch on unlock ${if (enable) "enabled" else "disabled"}")
            
            // Show user feedback
            runOnUiThread {
                android.widget.Toast.makeText(
                    this,
                    if (enable) "✅ Launch on unlock enabled" else "❌ Launch on unlock disabled",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            println("[AUTO_START] ❌ Error setting launch on unlock: ${e.message}")
        }
    }
    
    private fun getAutoStartSettings(): Map<String, Boolean> {
        return try {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            mapOf(
                "autoStartOnBoot" to prefs.getBoolean("autoStartOnBoot", true),
                "kioskMode" to prefs.getBoolean("kioskMode", false),
                "launchOnUnlock" to prefs.getBoolean("launchOnUnlock", false)
            )
        } catch (e: Exception) {
            println("[AUTO_START] ❌ Error getting auto-start settings: ${e.message}")
            mapOf(
                "autoStartOnBoot" to true,
                "kioskMode" to false,
                "launchOnUnlock" to false
            )
        }
    }
    
    // Check if the app should behave as a kiosk (always on top, auto-restart, etc.)
    private fun configureKioskBehavior() {
        try {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val kioskMode = prefs.getBoolean("kioskMode", false)
            
            if (kioskMode) {
                println("[KIOSK] 📱 Configuring kiosk mode behavior")
                
                // Keep screen on
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                
                // Hide navigation bar and status bar for immersive experience
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
                
                println("[KIOSK] ✅ Kiosk mode configured")
            }
        } catch (e: Exception) {
            println("[KIOSK] ❌ Error configuring kiosk behavior: ${e.message}")
        }
    }
    
    // Handle back button in kiosk mode
    override fun onBackPressed() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val kioskMode = prefs.getBoolean("kioskMode", false)
        
        if (kioskMode) {
            println("[KIOSK] 🚫 Back button disabled in kiosk mode")
            // Don't allow back button in kiosk mode
            return
        } else {
            super.onBackPressed()
        }
    }

    @Composable
    private fun StudentScannerAppWithKeyboardQR(scannedStudent: StudentResponse?) {
        var qrResult by remember { mutableStateOf("") }
        var showSchoolSettings by remember { mutableStateOf(false) }
        var showDuplicateNotification by remember { mutableStateOf(false) }
        var duplicateMessage by remember { mutableStateOf("") }
        var qrCodeBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var showQRCode by remember { mutableStateOf(false) }

        // Set up QR scan callback
        LaunchedEffect(Unit) {
            onQRScanned = { scannedData ->
                qrResult = scannedData

                // Check if this is a BLE verification QR code
                if (isQRCodeDisplayed && currentQRToken != null) {
                    println("[BLE] 🔍 QR code scanned during BLE verification: $scannedData")
                    println("[BLE] 🔍 Current QR token: $currentQRToken")

                    // Verify the scanned token matches the current QR token
                    if (scannedData == currentQRToken) {
                        println("[BLE] ✅ QR token matches! Starting verification...")
                        // Extract device ID from the token (format: DEVICE_ID:TIMESTAMP:HASH)
                        val deviceId = scannedData.split(":")[0]
                        verifyQRTokenAndTransfer(scannedData, deviceId)
                    } else {
                        println("[BLE] ❌ QR token mismatch!")
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "❌ Invalid QR code\n🔐 Please scan the correct QR code",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    println("[BLE] 📱 Regular QR scan (not BLE verification): $scannedData")
                }
            }
            // Set up volume up callback to show school settings
            onVolumeUpPressed = {
                showSchoolSettings = true
            }

            // Set up QR code display callbacks for BLE integration
            setQRCodeCallbacks(
                displayCallback = { token ->
                    println("[BLE] 🎯 QR display callback triggered with token: $token")
                    // Generate QR code bitmap and show it
                    qrCodeBitmap = QRCodeGenerator.generateQRCode(token)
                    println("[BLE] 🎯 QR bitmap generated: ${qrCodeBitmap != null}")
                    showQRCode = true
                    println("[BLE] 🎯 showQRCode set to true")
                },
                hideCallback = {
                    println("[BLE] 🎯 QR hide callback triggered")
                    // Hide QR code - just update UI state, don't call hideQRCodeDisplay() to avoid recursion
                    showQRCode = false
                    qrCodeBitmap = null
                }
            )
        }

        // Reset QR result after processing
        LaunchedEffect(qrResult) {
            if (qrResult.isNotEmpty()) {
                delay(100) // Small delay to ensure processing
                qrResult = ""
            }
        }

        // Show either the main app or settings
        if (showSchoolSettings) {
            // Show the school settings dialog
            AndroidSchoolSettingsScreen(
                onDismiss = { showSchoolSettings = false }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                StudentScannerApp(
                    scannedStudent = scannedStudent,
                    onQRCodeDismiss = {
                        showQRCode = false
                        qrCodeBitmap = null
                        // Don't call hideQRCodeDisplay() to avoid recursion
                    },
                    onStudentDismissed = { clearScannedStudent() }
                )

                // Show QR code dialog when needed
                if (showQRCode && qrCodeBitmap != null) {
                    QRCodeDisplayDialog(
                        qrCodeBitmap = qrCodeBitmap!!,
                        onDismiss = {
                            showQRCode = false
                            qrCodeBitmap = null
                            // Don't call hideQRCodeDisplay() to avoid recursion
                        }
                    )
                }

                // Removed Custom duplicate notification
            }
        }
    }



    // Show student info dialog if found
    private fun showStudentInfoDialog(studentInfo: Map<String, Any>) {
        val name = studentInfo["name"] as? String ?: "Unknown"
        val grade = studentInfo["grade_level"]?.toString() ?: "-"
        val section = studentInfo["section"] as? String ?: "-"
        val parentPhone = studentInfo["parent_phone"] as? String ?: "-"

        val message = """
            Name: $name
            Grade: $grade
            Section: $section
            Parent Phone: $parentPhone
        """.trimIndent()

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Student Info")
        builder.setMessage(message)
        builder.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    fun clearScannedStudent() {
        scannedStudentFlow.value = null
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(resetFlagReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // End of MainActivity class
}

@Composable
fun QRCodeDialog(token: String, onDismiss: () -> Unit) {
    val qrBitmap = remember(token) {
        QRCodeGenerator.generateQRCode(token, 400, 400)
    }
    if (qrBitmap != null) {
        QRCodeDisplayDialog(qrCodeBitmap = qrBitmap, onDismiss = onDismiss)
    } else {
        // Fallback if QR generation fails
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("QR Code Error") },
            text = { Text("Failed to generate QR code.") },
            confirmButton = {}
        )
    }
}


