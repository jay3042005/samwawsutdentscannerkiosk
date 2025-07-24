package com.arjay.logger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arjay.logger.bluetooth.BleManager
import com.arjay.logger.bluetooth.BluetoothClient
import com.arjay.logger.utils.QRCodeScanner
import com.arjay.logger.data.model.ScanLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import com.arjay.logger.ScanLogsActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.PropertyValuesHolder

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val NOTIFICATION_CHANNEL_ID = "LoggerDebug"
        private const val NOTIFICATION_ID = 1001
    }
    
    // New UI elements for redesigned interface
    private lateinit var startServiceButton: FrameLayout
    private lateinit var viewReceivedLogsButton: LinearLayout
    private lateinit var outerRing: FrameLayout
    private lateinit var scanButtonText: TextView
    private lateinit var statusText: TextView
    private lateinit var historyIcon: ImageView
    private lateinit var infoIcon: ImageView
    private var isActivityActive = true
    
    // QR Code scanner for authentication
    private lateinit var qrCodeScanner: QRCodeScanner
    private var currentStudentScannerDevice: BluetoothDevice? = null
    private lateinit var bleManager: BleManager
    private var isReceiverFlowActive = false
    
    // Scanning overlay views
    private lateinit var scanningOverlay: View
    private lateinit var scanningContent: LinearLayout
    private lateinit var scanningStatusText: TextView
    private lateinit var scanningSubStatusText: TextView
    private val scanningHandler = Handler(Looper.getMainLooper())
    
    // Camera views
    private lateinit var cameraContainer: FrameLayout
    private lateinit var cameraPreview: PreviewView
    private lateinit var scanningLine: View
    private lateinit var cancelScanButton: Button
    
    // Camera functionality
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService
    private var isQRScanningActive = false
    
    // Animation objects
    private var ringAnimator: ObjectAnimator? = null

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.CAMERA,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.POST_NOTIFICATIONS
    )
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        val deniedPermissions = permissions.filter { !it.value }.keys.toList()
        
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            showDebugNotification("Permissions", "All permissions granted")
            updateButtonState(true)
            statusText.text = "Ready to receive scan logs"
        } else {
            Log.e(TAG, "Some permissions were denied: ${deniedPermissions.joinToString(", ")}")
            showDebugNotification("Permissions", "Denied: ${deniedPermissions.joinToString(", ")}")
            showPermissionHelp(deniedPermissions)
            updateButtonState(false)
            statusText.text = "Permissions required"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        createNotificationChannel()
        initializeViews()
        setupClickListeners()
        
        // Initialize QR code scanner
        qrCodeScanner = QRCodeScanner(this)
        
        // Load logs
        loadLogs()
        
        // Check permissions and update UI
        checkAndUpdateButtonState()

        bleManager = BleManager(this)
        // Add a button and a TextView to your layout (activity_main.xml) with IDs: downloadButton, progressTextView
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        val progressTextView = findViewById<TextView>(R.id.progressTextView)
        downloadButton.setOnClickListener {
            bleManager.scanAndDownloadScanLog(
                onProgress = { message ->
                    runOnUiThread { progressTextView.text = message }
                },
                onSuccess = { logJson ->
                    runOnUiThread {
                        progressTextView.text = "Download complete!"
                        // Optionally show the log in a dialog or save it
                        // showLogDialog(logJson)
                    }
                },
                onError = { error ->
                    runOnUiThread { progressTextView.text = "Error: $error" }
                }
            )
        }
    }
    
    private fun initializeViews() {
        // Initialize new UI elements
        startServiceButton = findViewById(R.id.startServiceButton)
        viewReceivedLogsButton = findViewById(R.id.viewReceivedLogsButton)
        outerRing = findViewById(R.id.outerRing)
        scanButtonText = findViewById(R.id.scanButtonText)
        statusText = findViewById(R.id.statusText)
        historyIcon = findViewById(R.id.historyIcon)
        infoIcon = findViewById(R.id.infoIcon)
        
        // Initialize scanning overlay views
        scanningOverlay = findViewById(R.id.scanningOverlay)
        scanningContent = findViewById(R.id.scanningContent)
        scanningStatusText = findViewById(R.id.scanningStatusText)
        scanningSubStatusText = findViewById(R.id.scanningSubStatusText)
        
        // Initialize camera views
        cameraContainer = findViewById(R.id.cameraContainer)
        cameraPreview = findViewById(R.id.cameraPreview)
        scanningLine = findViewById(R.id.scanningLine)
        cancelScanButton = findViewById(R.id.cancelScanButton)
        
        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Set initial state
        updateButtonState(checkAllPermissions())
        statusText.text = if (checkAllPermissions()) "Ready to receive scan logs" else "Permissions required"
    }
    
    private fun setupClickListeners() {
        // Central scan button click
        startServiceButton.setOnClickListener {
            if (!checkAllPermissions()) {
                // Toast removed for missing permissions
                requestMissingPermissions()
                return@setOnClickListener
            }
            
            // Start the beautiful scanning animation
            startScanningWithAnimation()
        }
        
        // History icon click
        historyIcon.setOnClickListener {
            showReceivedLogsDialog()
        }
        
        // Info icon click
        infoIcon.setOnClickListener {
            Toast.makeText(this, "Logger Receiver v1.0\nReceives scan logs from Student Scanner apps", Toast.LENGTH_LONG).show()
        }
        
        // Info icon long press for testing interface
        infoIcon.setOnLongClickListener {
            showTestingInterface()
            true
        }
        
        // View logs button (bottom action)
        viewReceivedLogsButton.setOnClickListener {
            showReceivedLogsDialog()
        }
        
        // Cancel scan button
        cancelScanButton.setOnClickListener {
            cancelQRScanning()
        }
    }
    
    private fun startScanningWithAnimation() {
        Log.d(TAG, "🎯 Starting scanning with beautiful animation...")
        
        // Update status
        statusText.text = "Scanning for Student App..."
        
        // Start the outer ring animation
        startRingAnimation()
        
        // Show scanning overlay after a brief delay
        Handler(Looper.getMainLooper()).postDelayed({
            showScanningAnimation()
            startReceiverFlow()
            updateButtonStates(isRunning = true)
            showDebugNotification("Logger", "Receiver started, scanning for devices")
        }, 800)
    }
    
    private fun startRingAnimation() {
        // Create a beautiful rotating animation for the outer ring
        ringAnimator = ObjectAnimator.ofFloat(outerRing, "rotation", 0f, 360f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
        
        // Add a pulsing effect to the central button
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1f, 1.1f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1f, 1.1f, 1f)
        val pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(startServiceButton, scaleX, scaleY).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }
    
    private fun stopRingAnimation() {
        ringAnimator?.cancel()
        ringAnimator = null
        
        // Reset transformations
        outerRing.rotation = 0f
        startServiceButton.scaleX = 1f
        startServiceButton.scaleY = 1f
    }
    
    private fun updateButtonState(enabled: Boolean) {
        startServiceButton.isEnabled = enabled
        startServiceButton.isClickable = enabled
        startServiceButton.alpha = if (enabled) 1.0f else 0.6f
        scanButtonText.text = if (enabled) "SCAN LOG" else "PERMISSIONS\nREQUIRED"
    }
    
    private fun updateButtonStates(isRunning: Boolean) {
        runOnUiThread {
            if (isRunning) {
                // When running, disable the button temporarily
                startServiceButton.isEnabled = false
                startServiceButton.isClickable = false
                scanButtonText.text = "SCANNING..."
                statusText.text = "Scanning for Student App..."
            } else {
                // When not running, enable the button
                stopRingAnimation()
                startServiceButton.isEnabled = true
                startServiceButton.isClickable = true
                scanButtonText.text = "SCAN LOG"
                statusText.text = "Ready to receive scan logs"
                startServiceButton.alpha = 1.0f
            }
        }
    }
    
    // Rest of the methods remain the same as the original MainActivity
    // (I'll include the essential ones for functionality)
    
    private fun startReceiverFlow() {
        showDebugNotification("BLE", "Scanning for Student Scanner apps")
        if (!checkRuntimePermissions()) {
            showDebugNotification("BLE", "Missing runtime permissions for receiver")
            return
        }
        bleManager.startScanning { device ->
            if (!isReceiverFlowActive) {
                Log.d(TAG, "Receiver flow not active, ignoring BLE scan result")
                return@startScanning
            }
            currentStudentScannerDevice = device
            
            runOnUiThread {
                onStudentScannerFound(device.name ?: "Unknown Device")
            }
            
            showDebugNotification("BLE", "Student Scanner found: ${device.name ?: device.address}")
            bleManager.stopScanning()
            
            Log.d(TAG, "🔗 Connecting to Student Scanner via Bluetooth SPP...")
            connectToStudentScannerAndReceiveLogs(device)
        }
    }
    
    private fun connectToStudentScannerAndReceiveLogs(device: BluetoothDevice) {
        Log.d(TAG, "🔗 Starting connection to Student Scanner: ${device.name ?: device.address}")
        
        runOnUiThread {
            onConnectingToStudentScanner()
        }
        
        currentStudentScannerDevice = device
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ Bluetooth adapter not available")
            runOnUiThread {
                updateScanningStatus("Error", "Bluetooth not available")
                scanningHandler.postDelayed({
                    hideScanningAnimation()
                    updateButtonStates(isRunning = false)
                }, 3000)
            }
            return
        }
        
        Thread {
            try {
                Log.d(TAG, "🔗 Connecting via Bluetooth SPP...")
                val socket = device.createRfcommSocketToServiceRecord(
                    java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
                
                bluetoothAdapter.cancelDiscovery()
                socket.connect()
                Log.d(TAG, "✅ Connected to Student Scanner via SPP")
                
                runOnUiThread {
                    onConnectedToStudentScanner()
                }
                
                val outputStream = socket.outputStream
                val inputStream = socket.inputStream
                
                outputStream.write("REQUEST_QR\n".toByteArray())
                outputStream.flush()
                Log.d(TAG, "📷 Sent REQUEST_QR, waiting for QR_READY acknowledgment...")
                
                // Wait for QR_READY acknowledgment from Student Scanner
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead).trim()
                    Log.d(TAG, "📤 Received response from Student Scanner: $response")
                    
                    if (response == "QR_READY") {
                        Log.d(TAG, "✅ QR code is ready on Student Scanner, opening camera...")
                        socket.close()
                        
                        runOnUiThread {
                            showDebugNotification("Connection", "QR dialog triggered, opening camera")
                            onOpeningCamera()
                        }
                    } else {
                        Log.e(TAG, "❌ Unexpected response from Student Scanner: $response")
                        socket.close()
                        runOnUiThread {
                            updateScanningStatus("Connection Error", "Unexpected response: $response")
                            scanningHandler.postDelayed({
                                hideScanningAnimation()
                                updateButtonStates(isRunning = false)
                            }, 3000)
                        }
                    }
                } else {
                    Log.e(TAG, "❌ No response from Student Scanner after REQUEST_QR")
                    socket.close()
                    runOnUiThread {
                        updateScanningStatus("Connection Error", "No response from Student Scanner")
                        scanningHandler.postDelayed({
                            hideScanningAnimation()
                            updateButtonStates(isRunning = false)
                        }, 3000)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to connect to Student Scanner: ${e.message}")
                runOnUiThread {
                    showDebugNotification("Error", "Connection failed: ${e.message}")
            // Toast removed for connection failure
            updateScanningStatus("Connection Failed", "${e.message}")
            scanningHandler.postDelayed({
                hideScanningAnimation()
                updateButtonStates(isRunning = false)
            }, 3000)
                }
            }
        }.start()
    }
    
    // Include essential methods for camera and QR scanning
    private fun showCameraPreview() {
        Log.d(TAG, "📷 Showing camera preview...")
        
        scanningContent.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                scanningContent.visibility = View.GONE
                cameraContainer.visibility = View.VISIBLE
                cameraContainer.alpha = 0f
                cameraContainer.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .withEndAction {
                        startCamera()
                    }
                    .start()
            }
            .start()
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                startScanningLineAnimation()
                isQRScanningActive = true
                Log.d(TAG, "✅ Camera started successfully")
            } catch (exc: Exception) {
                Log.e(TAG, "❌ Camera startup failed: ${exc.message}")
                showCameraError("Failed to start camera: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(cameraPreview.surfaceProvider)
        
        imageAnalyzer = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor, QRCodeAnalyzer { qrCode ->
                    if (isQRScanningActive) {
                        runOnUiThread {
                            onQRCodeDetected(qrCode)
                        }
                    }
                })
            }
        
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
        } catch (exc: Exception) {
            Log.e(TAG, "❌ Camera binding failed: ${exc.message}")
            showCameraError("Camera binding failed: ${exc.message}")
        }
    }
    
    private fun startScanningLineAnimation() {
        val animator = ObjectAnimator.ofFloat(scanningLine, "translationY", 0f, 260f)
        animator.duration = 2000
        animator.repeatCount = ValueAnimator.INFINITE
        animator.repeatMode = ValueAnimator.REVERSE
        animator.start()
    }
    
    private fun onQRCodeDetected(qrCode: String) {
        if (!isQRScanningActive) return
        
        Log.d(TAG, "📱 QR Code detected: $qrCode")
        isQRScanningActive = false
        
        stopCamera()
        hideCameraPreview()
        handleQRCodeScanned(qrCode)
    }
    
    // Include other essential methods...
    private fun checkAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestMissingPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    private fun checkAndUpdateButtonState() {
        if (checkAllPermissions()) {
            Log.d(TAG, "All permissions granted - button ready")
            updateButtonState(true)
            statusText.text = "Ready to receive scan logs"
        } else {
            Log.d(TAG, "Missing permissions - requesting...")
            updateButtonState(false)
            statusText.text = "Permissions required"
            requestMissingPermissions()
        }
    }
    
    // Essential utility methods
    private fun showDebugNotification(title: String, message: String) {
        if (!isActivityActive) return
        runOnUiThread {
            if (isActivityActive) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                
                val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setAutoCancel(true)
                    .build()
                
                notificationManager.notify(NOTIFICATION_ID, notification)
                // Toast removed for debug notification
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Logger Debug",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Debug notifications for Logger app"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun showReceivedLogsDialog() {
        try {
            val intent = Intent(this, ScanLogsActivity::class.java)
            startActivity(intent)
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        } catch (e: Exception) {
            e.printStackTrace()
            // Toast removed for error opening scan logs
        }
    }
    
    private fun loadLogs(received: Boolean = false) {
        try {
            val logFile = if (received) {
                val extractedFile = File(filesDir, "scan_logs_extracted.json")
                if (extractedFile.exists()) extractedFile else File(filesDir, "scan_logs_received.json")
            } else {
                File(filesDir, "scan_logs.json")
            }
            if (logFile.exists()) {
                val jsonContent = logFile.readText()
                val gson = Gson()
                val type = object : TypeToken<List<ScanLog>>() {}.type
                val logs: List<ScanLog> = gson.fromJson(jsonContent, type)
                
                Log.d(TAG, "📋 Loaded ${logs.size} scan logs successfully")
            } else {
                Log.d(TAG, "No log file found at: ${logFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading logs: ${e.message}")
        }
    }
    
    private fun loadLogsAndGetCount(received: Boolean = false): Int {
        return try {
            val logFile = if (received) {
                val extractedFile = File(filesDir, "scan_logs_extracted.json")
                if (extractedFile.exists()) extractedFile else File(filesDir, "scan_logs_received.json")
            } else {
                File(filesDir, "scan_logs.json")
            }
            if (logFile.exists()) {
                val jsonContent = logFile.readText()
                val gson = Gson()
                val type = object : TypeToken<List<ScanLog>>() {}.type
                val logs: List<ScanLog> = gson.fromJson(jsonContent, type)
                
                Log.d(TAG, "📋 Loaded ${logs.size} scan logs successfully")
                logs.size
            } else {
                Log.d(TAG, "No log file found at: ${logFile.absolutePath}")
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading logs: ${e.message}")
            0
        }
    }
    
    private fun showSuccessAnimation(logCount: Int) {
        // Update status with count information
        statusText.text = "✅ Received $logCount scan logs successfully!"
        
        // Create a success animation with scale and fade effects
        val successAnimator = ObjectAnimator.ofPropertyValuesHolder(
            startServiceButton,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f, 1f)
        ).apply {
            duration = 600
            start()
        }
        
        // Update button text to show success
        scanButtonText.text = "SUCCESS!"
        
        // Reset to normal state after animation
        Handler(Looper.getMainLooper()).postDelayed({
            scanButtonText.text = "SCAN LOG"
            statusText.text = "Ready for next scan"
        }, 2000)
        
        Log.d(TAG, "✅ Success animation shown with $logCount logs received")
    }
    
    // Placeholder methods for animation callbacks
    private fun showScanningAnimation() {
        scanningOverlay.visibility = View.VISIBLE
        scanningOverlay.alpha = 0f
        scanningOverlay.animate()
            .alpha(1f)
            .setDuration(500)
            .start()
        
        updateScanningStatus("Scanning for Student App...", "Detecting BLE devices...")
    }
    
    private fun onStudentScannerFound(deviceName: String) {
        updateScanningStatus("Student App Found!", "Device: $deviceName")
    }
    
    private fun onConnectingToStudentScanner() {
        updateScanningStatus("Connecting...", "Establishing Bluetooth connection...")
    }
    
    private fun onConnectedToStudentScanner() {
        updateScanningStatus("Connected!", "Requesting QR code display...")
    }
    
    private fun onOpeningCamera() {
        updateScanningStatus("Opening Camera...", "Please scan the QR code")
        scanningHandler.postDelayed({
            showCameraPreview()
        }, 2000)
    }
    
    private fun updateScanningStatus(status: String, subStatus: String) {
        scanningStatusText.animate()
            .alpha(0.7f)
            .setDuration(150)
            .withEndAction {
                scanningStatusText.text = status
                scanningSubStatusText.text = subStatus
                scanningStatusText.animate()
                    .alpha(1.0f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }
    
    private fun hideScanningAnimation() {
        scanningOverlay.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                scanningOverlay.visibility = View.GONE
            }
            .start()
    }
    
    private fun cancelQRScanning() {
        Log.d(TAG, "❌ QR scanning cancelled by user")
        isQRScanningActive = false
        
        stopCamera()
        hideCameraPreview()
        stopReceiverFlow()
        updateButtonStates(isRunning = false)
        hideScanningAnimation()
        
        // Toast removed for QR scan cancelled
    }
    
    private fun hideCameraPreview() {
        cameraContainer.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                cameraContainer.visibility = View.GONE
                scanningContent.visibility = View.VISIBLE
                scanningContent.alpha = 0f
                scanningContent.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            .start()
    }
    
    private fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            camera = null
            imageAnalyzer = null
            Log.d(TAG, "✅ Camera stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping camera: ${e.message}")
        }
    }
    
    private fun showCameraError(message: String) {
        runOnUiThread {
            updateScanningStatus("Camera Error", message)
            scanningHandler.postDelayed({
                hideScanningAnimation()
                updateButtonStates(isRunning = false)
                stopReceiverFlow()
            }, 3000)
        }
    }
    
    private fun stopReceiverFlow() {
        Log.d(TAG, "🛑 Stopping receiver flow...")
        isReceiverFlowActive = false
        
        bleManager.let { manager ->
            Log.d(TAG, "🛑 Stopping BLE scanning...")
            try {
                manager.stopScanning()
                Log.d(TAG, "✅ BLE scanning stopped successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error stopping BLE scan: ${e.message}")
            }
        }
        
        currentStudentScannerDevice = null
        
        runOnUiThread {
        // Toast removed for logger receiver stopped
    }
        
        Log.d(TAG, "✅ Receiver flow stopped completely")
    }
    
    private fun handleQRCodeScanned(token: String) {
        if (!isReceiverFlowActive) {
            Log.d(TAG, "Ignoring handleQRCodeScanned because receiver flow is not active")
            updateButtonStates(isRunning = false)
            hideScanningAnimation()
            return
        }
        Log.d(TAG, "QR code scanned: $token")
        showDebugNotification("QR Scan", "QR code scanned, validating...")
        
        if (qrCodeScanner.validateStudentScannerToken(token)) {
            showDebugNotification("QR Scan", "Valid token, connecting...")
            
            currentStudentScannerDevice?.let { device ->
                connectToStudentScannerAndReceiveLogsWithToken(device, token)
            } ?: run {
                showDebugNotification("Error", "No Student Scanner device found")
                stopReceiverFlow()
                updateButtonStates(isRunning = false)
                hideScanningAnimation()
                // Toast removed for no device found
            }
        } else {
            showDebugNotification("QR Scan", "Invalid QR code")
            stopReceiverFlow()
            updateButtonStates(isRunning = false)
            hideScanningAnimation()
            // Toast removed for invalid QR code
        }
    }
    
    private fun connectToStudentScannerAndReceiveLogsWithToken(device: BluetoothDevice, scannedToken: String) {
        Log.d(TAG, "🔗 Starting file transfer connection with scanned token: $scannedToken")
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ Bluetooth adapter not available for file transfer")
            runOnUiThread {
                updateButtonStates(isRunning = false)
                hideScanningAnimation()
            }
            return
        }
        
        Thread {
            try {
                Log.d(TAG, "🔗 Creating BluetoothClient for file transfer...")
                val bluetoothClient = BluetoothClient(bluetoothAdapter, this@MainActivity)
                
                Log.d(TAG, "📁 Starting file transfer with token: $scannedToken")
                
                bluetoothClient.connectAndReceiveWithToken(device, scannedToken) { success ->
                    runOnUiThread {
                        hideScanningAnimation()
                        
                        if (success) {
                            showDebugNotification("Success", "Scan logs received!")
                            loadLogs(true)
                            
                            Log.d(TAG, "🛑 Auto-stopping logger after successful log reception")
                            stopReceiverFlow()
                            updateButtonStates(isRunning = false)
                            // Toast removed for scan logs received
                            // Show success animation with count
                            val logCount = loadLogsAndGetCount(true)
                            showSuccessAnimation(logCount)
                        } else {
                            showDebugNotification("Error", "File transfer failed")
                            stopReceiverFlow()
                            updateButtonStates(isRunning = false)
                            // Toast removed for transfer failed
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error during file transfer: ${e.message}")
                runOnUiThread {
                    hideScanningAnimation()
                    showDebugNotification("Error", "Transfer failed: ${e.message}")
                    stopReceiverFlow()
                    updateButtonStates(isRunning = false)
                    // Toast removed for error message
                }
            }
        }.start()
    }
    
    private fun checkRuntimePermissions(): Boolean {
        val bluetoothPermissions = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
        
        val locationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        val allPermissions = bluetoothPermissions + locationPermissions
        
        for (permission in allPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Runtime permission denied: $permission")
                return false
            }
        }
        
        return true
    }
    
    private fun showPermissionHelp(deniedPermissions: List<String>) {
        val helpMessage = buildString {
            appendLine("Required permissions were denied:")
            appendLine()
            deniedPermissions.forEach { permission ->
                when {
                    permission.contains("BLUETOOTH") -> appendLine("• Bluetooth: Required for device communication")
                    permission.contains("LOCATION") -> appendLine("• Location: Required for BLE scanning")
                    permission.contains("WIFI") -> appendLine("• WiFi: Required for WiFi Direct")
                    permission.contains("CAMERA") -> appendLine("• Camera: Required for QR code scanning")
                    permission.contains("STORAGE") -> appendLine("• Storage: Required for file operations")
                    permission.contains("FOREGROUND") -> appendLine("• Foreground Service: Required for background operation")
                }
            }
            appendLine()
            appendLine("Please go to Settings > Apps > Logger > Permissions and grant all permissions.")
        }
        
        Toast.makeText(this, "Missing permissions. Please grant all permissions in Settings.", Toast.LENGTH_LONG).show()
        Log.d(TAG, helpMessage)
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }

    override fun onDestroy() {
        super.onDestroy()
        isActivityActive = false
        cameraExecutor.shutdown()
        stopRingAnimation()
    }
    
    // ========================================
    // COMPREHENSIVE TESTING SYSTEM
    // ========================================
    
    /**
     * Show testing interface for Student Scanner app
     */
    private fun showTestingInterface() {
        Log.d(TAG, "🧪 Showing testing interface...")
        
        // Always show testing options directly - use same animation flow as scan log
        val testOptions = arrayOf(
            "🧪 Test Student Scanning",
            "🌐 Test Network/PocketBase", 
            "📋 Test Log Management",
            "👤 Test Student Data",
            "🎨 Test UI/UX States",
            "📊 Get App Status",
            "🔄 Run All Tests"
        )
        
        Log.d(TAG, "🧪 Test options array created with ${testOptions.size} items")
        testOptions.forEachIndexed { index, option ->
            Log.d(TAG, "🧪 Option $index: $option")
        }
        
        try {
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🧪 Student Scanner Testing")
                .setItems(testOptions) { _, which ->
                    Log.d(TAG, "🧪 Test option selected: $which (${testOptions[which]})")
                    when (which) {
                        0 -> startTestWithAnimation("SCAN_TESTS")
                        1 -> startTestWithAnimation("NETWORK_TESTS")
                        2 -> startTestWithAnimation("LOG_TESTS")
                        3 -> startTestWithAnimation("DATA_TESTS")
                        4 -> startTestWithAnimation("UI_TESTS")
                        5 -> startTestWithAnimation("STATUS_TEST")
                        6 -> startTestWithAnimation("ALL_TESTS")
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    Log.d(TAG, "🧪 Testing dialog cancelled")
                    dialog.dismiss()
                }
                .setCancelable(true)
                .create()
            
            Log.d(TAG, "🧪 Dialog created, about to show...")
            dialog.show()
            Log.d(TAG, "✅ Testing dialog shown successfully with ${testOptions.size} options")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing testing dialog: ${e.message}")
            e.printStackTrace()
            showToast("❌ Error opening testing interface: ${e.message}")
        }
    }
    
    /**
     * Start test with same beautiful animation as scan log function
     */
    private fun startTestWithAnimation(testCategory: String) {
        Log.d(TAG, "🧪 Starting test with animation: $testCategory")
        
        // Update status for testing
        statusText.text = "Preparing test..."
        
        // Start the same beautiful animation as scan log
        startRingAnimation()
        
        // Show scanning overlay after a brief delay (same as scan log)
        Handler(Looper.getMainLooper()).postDelayed({
            showScanningAnimation()
            startTestingFlow(testCategory)
            updateButtonStates(isRunning = true)
            showDebugNotification("Testing", "Starting $testCategory test suite")
        }, 800)
    }
    
    /**
     * Start testing flow with BLE scanning (same as scan log function)
     */
    private fun startTestingFlow(testCategory: String) {
        showDebugNotification("BLE", "Scanning for Student Scanner apps for testing")
        if (!checkRuntimePermissions()) {
            showDebugNotification("BLE", "Missing runtime permissions for testing")
            return
        }
        
        bleManager.startScanning { device ->
            if (!isReceiverFlowActive) {
                Log.d(TAG, "Testing flow not active, ignoring BLE scan result")
                return@startScanning
            }
            
            currentStudentScannerDevice = device
            
            runOnUiThread {
                onStudentScannerFoundForTesting(device.name ?: "Unknown Device")
            }
            
            showDebugNotification("BLE", "Student Scanner found for testing: ${device.name ?: device.address}")
            bleManager.stopScanning()
            
            Log.d(TAG, "🔗 Connecting to Student Scanner for testing via Bluetooth SPP...")
            connectToStudentScannerForTesting(device, testCategory)
        }
    }
    
    /**
     * Connect to Student Scanner for testing (uses TEST commands, not REQUEST_QR)
     */
    private fun connectToStudentScannerForTesting(device: BluetoothDevice, testCategory: String) {
        Log.d(TAG, "🔗 Starting testing connection to Student Scanner: ${device.name ?: device.address}")
        
        runOnUiThread {
            onConnectingToStudentScannerForTesting()
        }
        
        currentStudentScannerDevice = device
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ Bluetooth adapter not available for testing")
            runOnUiThread {
                updateScanningStatus("Error", "Bluetooth not available")
                scanningHandler.postDelayed({
                    hideScanningAnimation()
                    updateButtonStates(isRunning = false)
                }, 3000)
            }
            return
        }
        
        Thread {
            try {
                Log.d(TAG, "🔗 Connecting via Bluetooth SPP for testing...")
                val socket = device.createRfcommSocketToServiceRecord(
                    java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
                
                bluetoothAdapter.cancelDiscovery()
                socket.connect()
                Log.d(TAG, "✅ Connected to Student Scanner via SPP for testing")
                
                runOnUiThread {
                    onConnectedToStudentScannerForTesting()
                }
                
                // Use TEST_STATUS command instead of REQUEST_QR to avoid QR popup
                val outputStream = socket.outputStream
                val inputStream = socket.inputStream
                
                outputStream.write("TEST_STATUS\n".toByteArray())
                outputStream.flush()
                Log.d(TAG, "🧪 Sent TEST_STATUS to verify testing connection (no QR popup)...")
                
                // Wait for response
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                
                socket.close()
                
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead).trim()
                    Log.d(TAG, "✅ Testing connection verified: $response")
                    
                    runOnUiThread {
                        onTestingConnectionVerified()
                        // Now show the specific test options for the selected category
                        Handler(Looper.getMainLooper()).postDelayed({
                            hideScanningAnimation()
                            updateButtonStates(isRunning = false)
                            showSpecificTestOptions(testCategory)
                        }, 2000)
                    }
                } else {
                    throw Exception("No response from Student Scanner test server")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to connect to Student Scanner for testing: ${e.message}")
                runOnUiThread {
                    showDebugNotification("Error", "Testing connection failed: ${e.message}")
                    updateScanningStatus("Connection Failed", "${e.message}")
                    scanningHandler.postDelayed({
                        hideScanningAnimation()
                        updateButtonStates(isRunning = false)
                    }, 3000)
                }
            }
        }.start()
    }
    
    /**
     * Show specific test options after successful connection
     */
    private fun showSpecificTestOptions(testCategory: String) {
        when (testCategory) {
            "SCAN_TESTS" -> showScanTestOptions()
            "NETWORK_TESTS" -> showNetworkTestOptions()
            "LOG_TESTS" -> showLogTestOptions()
            "DATA_TESTS" -> showDataTestOptions()
            "UI_TESTS" -> showUITestOptions()
            "STATUS_TEST" -> runStatusTest()
            "ALL_TESTS" -> runAllTests()
        }
    }
    
    // Animation callback methods for testing
    private fun onStudentScannerFoundForTesting(deviceName: String) {
        updateScanningStatus("Student App Found!", "Device: $deviceName (Testing Mode)")
    }
    
    private fun onConnectingToStudentScannerForTesting() {
        updateScanningStatus("Connecting for Testing...", "Establishing test connection...")
    }
    
    private fun onConnectedToStudentScannerForTesting() {
        updateScanningStatus("Connected!", "Verifying test capabilities...")
    }
    
    private fun onTestingConnectionVerified() {
        updateScanningStatus("Test Ready!", "Connection verified, opening test menu...")
    }
    
    /**
     * Show student scanning test options
     */
    private fun showScanTestOptions() {
        val scanTests = arrayOf(
            "Simulate Student Scan",
            "Test Duplicate Detection",
            "Test Invalid Student ID"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🧪 Student Scanning Tests")
            .setItems(scanTests) { _, which ->
                when (which) {
                    0 -> runScanSimulationTest()
                    1 -> runDuplicateDetectionTest()
                    2 -> runInvalidStudentTest()
                }
            }
            .show()
    }
    
    /**
     * Show network test options
     */
    private fun showNetworkTestOptions() {
        val networkTests = arrayOf(
            "Test PocketBase Connection",
            "Simulate Network Down",
            "Test Server Response Time"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🌐 Network/PocketBase Tests")
            .setItems(networkTests) { _, which ->
                when (which) {
                    0 -> runNetworkStatusTest()
                    1 -> runNetworkDownTest()
                    2 -> runServerResponseTest()
                }
            }
            .show()
    }
    
    /**
     * Show log management test options
     */
    private fun showLogTestOptions() {
        val logTests = arrayOf(
            "Get Recent Logs (10)",
            "Get Recent Logs (50)",
            "Count Total Logs",
            "Test Log Retrieval Speed"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📋 Log Management Tests")
            .setItems(logTests) { _, which ->
                when (which) {
                    0 -> runGetRecentLogsTest(10)
                    1 -> runGetRecentLogsTest(50)
                    2 -> runLogCountTest()
                    3 -> runLogSpeedTest()
                }
            }
            .show()
    }
    
    /**
     * Show student data test options
     */
    private fun showDataTestOptions() {
        val dataTests = arrayOf(
            "Add Test Student",
            "Check Student Exists",
            "Test Invalid Student Data",
            "Test Student Lookup Speed"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("👤 Student Data Tests")
            .setItems(dataTests) { _, which ->
                when (which) {
                    0 -> runAddTestStudentTest()
                    1 -> runCheckStudentTest()
                    2 -> runInvalidDataTest()
                    3 -> runStudentLookupSpeedTest()
                }
            }
            .show()
    }
    
    /**
     * Show UI/UX test options
     */
    private fun showUITestOptions() {
        val uiTests = arrayOf(
            "Simulate Busy State",
            "Simulate Error State",
            "Test Loading Indicators",
            "Test Error Handling"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🎨 UI/UX State Tests")
            .setItems(uiTests) { _, which ->
                when (which) {
                    0 -> runUIBusyTest()
                    1 -> runUIErrorTest()
                    2 -> runLoadingTest()
                    3 -> runErrorHandlingTest()
                }
            }
            .show()
    }
    
    // ========================================
    // INDIVIDUAL TEST IMPLEMENTATIONS
    // ========================================
    
    private fun runScanSimulationTest() {
        Log.d(TAG, "🧪 Starting scan simulation test with animation...")
        
        // Use same animation as main scan log function
        statusText.text = "Testing Student Scanning..."
        startRingAnimation()
        
        // Show scanning overlay with test-specific messaging
        Handler(Looper.getMainLooper()).postDelayed({
            showScanningAnimation()
            updateScanningStatus("Testing Student Scanning...", "Connecting to Student Scanner...")
            
            val testCommand = "TEST_SCAN:SIMULATE:TEST123:Test Student"
            sendTestCommandWithAnimation(testCommand, "Scan Simulation Test") { result ->
                runOnUiThread {
                    hideScanningAnimation()
                    stopRingAnimation()
                    updateButtonStates(isRunning = false)
                    showTestResult("Scan Simulation Test", result)
                }
            }
        }, 800)
    }
    
    private fun runDuplicateDetectionTest() {
        showTestProgress("Testing duplicate detection...")
        
        val testCommand = "TEST_SCAN:DUPLICATE:TEST123"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Duplicate Detection Test", result)
            }
        }
    }
    
    private fun runInvalidStudentTest() {
        showTestProgress("Testing invalid student ID...")
        
        val testCommand = "TEST_SCAN:SIMULATE:INVALID999:Invalid Student"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Invalid Student Test", result)
            }
        }
    }
    
    private fun runNetworkStatusTest() {
        showTestProgress("Testing PocketBase connection...")
        
        val testCommand = "TEST_NETWORK:STATUS"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Network Status Test", result)
            }
        }
    }
    
    private fun runNetworkDownTest() {
        showTestProgress("Simulating network down...")
        
        val testCommand = "TEST_NETWORK:SIMULATE_DOWN"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Network Down Test", result)
            }
        }
    }
    
    private fun runServerResponseTest() {
        showTestProgress("Testing server response time...")
        
        val startTime = System.currentTimeMillis()
        val testCommand = "TEST_NETWORK:STATUS"
        sendTestCommand(testCommand) { result ->
            val responseTime = System.currentTimeMillis() - startTime
            runOnUiThread {
                hideTestProgress()
                val enhancedResult = result.copy(
                    details = result.details + ("Response Time" to "${responseTime}ms")
                )
                showTestResult("Server Response Test", enhancedResult)
            }
        }
    }
    
    private fun runGetRecentLogsTest(limit: Int) {
        showTestProgress("Getting recent logs (limit: $limit)...")
        
        val testCommand = "TEST_LOGS:GET_RECENT:$limit"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Recent Logs Test ($limit)", result)
            }
        }
    }
    
    private fun runLogCountTest() {
        showTestProgress("Counting total logs...")
        
        val testCommand = "TEST_LOGS:COUNT"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Log Count Test", result)
            }
        }
    }
    
    private fun runLogSpeedTest() {
        showTestProgress("Testing log retrieval speed...")
        
        val startTime = System.currentTimeMillis()
        val testCommand = "TEST_LOGS:GET_RECENT:10"
        sendTestCommand(testCommand) { result ->
            val retrievalTime = System.currentTimeMillis() - startTime
            runOnUiThread {
                hideTestProgress()
                val enhancedResult = result.copy(
                    details = result.details + ("Retrieval Time" to "${retrievalTime}ms")
                )
                showTestResult("Log Speed Test", enhancedResult)
            }
        }
    }
    
    private fun runAddTestStudentTest() {
        showTestProgress("Adding test student...")
        
        val timestamp = System.currentTimeMillis()
        val testCommand = "TEST_DATA:ADD_TEST_STUDENT:TEST$timestamp:Test Student $timestamp"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Add Test Student", result)
            }
        }
    }
    
    private fun runCheckStudentTest() {
        showTestProgress("Checking student existence...")
        
        val testCommand = "TEST_DATA:CHECK_STUDENT:TEST123"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Check Student Test", result)
            }
        }
    }
    
    private fun runInvalidDataTest() {
        showTestProgress("Testing invalid student data...")
        
        val testCommand = "TEST_DATA:CHECK_STUDENT:INVALID999"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Invalid Data Test", result)
            }
        }
    }
    
    private fun runStudentLookupSpeedTest() {
        showTestProgress("Testing student lookup speed...")
        
        val startTime = System.currentTimeMillis()
        val testCommand = "TEST_DATA:CHECK_STUDENT:TEST123"
        sendTestCommand(testCommand) { result ->
            val lookupTime = System.currentTimeMillis() - startTime
            runOnUiThread {
                hideTestProgress()
                val enhancedResult = result.copy(
                    details = result.details + ("Lookup Time" to "${lookupTime}ms")
                )
                showTestResult("Student Lookup Speed Test", enhancedResult)
            }
        }
    }
    
    private fun runUIBusyTest() {
        showTestProgress("Testing UI busy state...")
        
        val testCommand = "TEST_UI:SIMULATE_BUSY"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("UI Busy State Test", result)
            }
        }
    }
    
    private fun runUIErrorTest() {
        showTestProgress("Testing UI error state...")
        
        val testCommand = "TEST_UI:SIMULATE_ERROR:NETWORK"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("UI Error State Test", result)
            }
        }
    }
    
    private fun runLoadingTest() {
        showTestProgress("Testing loading indicators...")
        
        val testCommand = "TEST_UI:SIMULATE_BUSY"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Loading Indicators Test", result)
            }
        }
    }
    
    private fun runErrorHandlingTest() {
        showTestProgress("Testing error handling...")
        
        val testCommand = "TEST_UI:SIMULATE_ERROR:GENERAL"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("Error Handling Test", result)
            }
        }
    }
    
    private fun runStatusTest() {
        showTestProgress("Getting comprehensive app status...")
        
        val testCommand = "TEST_STATUS"
        sendTestCommand(testCommand) { result ->
            runOnUiThread {
                hideTestProgress()
                showTestResult("App Status Test", result)
            }
        }
    }
    
    private fun runAllTests() {
        showTestProgress("Running comprehensive test suite...")
        
        val testCommands = listOf(
            "TEST_STATUS" to "App Status",
            "TEST_NETWORK:STATUS" to "Network Status",
            "TEST_LOGS:COUNT" to "Log Count",
            "TEST_SCAN:SIMULATE:TEST123:Test Student" to "Scan Simulation",
            "TEST_DATA:CHECK_STUDENT:TEST123" to "Student Check"
        )
        
        runTestSequence(testCommands, 0, mutableListOf())
    }
    
    private fun runTestSequence(
        tests: List<Pair<String, String>>, 
        currentIndex: Int, 
        results: MutableList<Pair<String, BluetoothClient.TestResult>>
    ) {
        if (currentIndex >= tests.size) {
            // All tests completed
            runOnUiThread {
                hideTestProgress()
                showAllTestResults(results)
            }
            return
        }
        
        val (command, testName) = tests[currentIndex]
        runOnUiThread {
            statusText.text = "Running: $testName (${currentIndex + 1}/${tests.size})"
        }
        
        sendTestCommand(command) { result ->
            results.add(testName to result)
            // Continue with next test
            runTestSequence(tests, currentIndex + 1, results)
        }
    }
    
    // ========================================
    // TEST COMMAND SENDING
    // ========================================
    
    /**
     * Send test command with animation (used for tests that show full scanning animation)
     */
    private fun sendTestCommandWithAnimation(command: String, testName: String, onResult: (BluetoothClient.TestResult) -> Unit) {
        // Update scanning status to show test progress
        updateScanningStatus("Running $testName...", "Sending command to Student Scanner...")
        
        // Use the regular sendTestCommand but with animation updates
        sendTestCommand(command) { result ->
            // Update animation before calling result
            runOnUiThread {
                if (result.success) {
                    updateScanningStatus("Test Complete!", "✅ $testName successful")
                } else {
                    updateScanningStatus("Test Failed!", "❌ $testName failed")
                }
                
                // Wait a moment to show the result, then call the callback
                Handler(Looper.getMainLooper()).postDelayed({
                    onResult(result)
                }, 1500)
            }
        }
    }
    
    private fun sendTestCommand(command: String, onResult: (BluetoothClient.TestResult) -> Unit) {
        // Use same connection method as scan log transfer
        currentStudentScannerDevice?.let { device ->
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            
            if (bluetoothAdapter != null) {
                val bluetoothClient = BluetoothClient(bluetoothAdapter, this)
                
                // Use same SPP connection approach as connectAndReceiveSimpleWithAddress
                Log.d(TAG, "🧪 Sending test command via Bluetooth SPP: $command")
                bluetoothClient.sendTestCommandDetailed(device.address, command, onResult)
            } else {
                onResult(BluetoothClient.TestResult(false, "Bluetooth adapter not available"))
            }
        } ?: run {
            // If no device connected, try to find one using same method as LoggerService
            Log.d(TAG, "🧪 No device connected, scanning for Student Scanner...")
            showTestProgress("Scanning for Student Scanner...")
            
            if (!checkRuntimePermissions()) {
                onResult(BluetoothClient.TestResult(false, "Missing Bluetooth permissions"))
                return
            }
            
            bleManager.startScanning { device ->
                if (!isValidStudentScannerDevice(device)) {
                    return@startScanning
                }
                
                bleManager.stopScanning()
                
                Log.d(TAG, "🧪 Found Student Scanner for test command: ${device.address}")
                currentStudentScannerDevice = device
                
                // Now send the test command
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val bluetoothAdapter = bluetoothManager.adapter
                
                if (bluetoothAdapter != null) {
                    val bluetoothClient = BluetoothClient(bluetoothAdapter, this)
                    bluetoothClient.sendTestCommandDetailed(device.address, command, onResult)
                } else {
                    onResult(BluetoothClient.TestResult(false, "Bluetooth adapter not available"))
                }
            }
            
            // Timeout for device scanning
            Handler(Looper.getMainLooper()).postDelayed({
                bleManager.stopScanning()
                runOnUiThread { hideTestProgress() }
                onResult(BluetoothClient.TestResult(false, "No Student Scanner device found"))
            }, 15000)
        }
    }
    
    // ========================================
    // TEST UI HELPERS
    // ========================================
    
    private fun showTestProgress(message: String) {
        runOnUiThread {
            statusText.text = message
            startServiceButton.isEnabled = false
            scanButtonText.text = "Testing..."
        }
    }
    
    private fun hideTestProgress() {
        runOnUiThread {
            statusText.text = "Ready to scan"
            startServiceButton.isEnabled = true
            scanButtonText.text = "SCAN LOG"
        }
    }
    
    private fun showTestResult(testName: String, result: BluetoothClient.TestResult) {
        val statusIcon = if (result.success) "✅" else "❌"
        val title = "$statusIcon $testName"
        
        val message = buildString {
            append("Status: ${if (result.success) "SUCCESS" else "FAILED"}\n")
            append("Message: ${result.message}\n")
            
            if (result.details.isNotEmpty()) {
                append("\nDetails:\n")
                result.details.forEach { (key, value) ->
                    append("• $key: $value\n")
                }
            }
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard("$title\n\n$message")
            }
            .show()
    }
    
    private fun showAllTestResults(results: List<Pair<String, BluetoothClient.TestResult>>) {
        val successCount = results.count { it.second.success }
        val totalCount = results.size
        val title = "🧪 Test Suite Results ($successCount/$totalCount passed)"
        
        val message = buildString {
            results.forEach { (testName, result) ->
                val icon = if (result.success) "✅" else "❌"
                append("$icon $testName\n")
                if (!result.success) {
                    append("   Error: ${result.message}\n")
                }
            }
            
            append("\nSummary:\n")
            append("• Passed: $successCount\n")
            append("• Failed: ${totalCount - successCount}\n")
            append("• Success Rate: ${(successCount * 100 / totalCount)}%")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy Results") { _, _ ->
                copyToClipboard("$title\n\n$message")
            }
            .show()
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Test Results", text)
        clipboard.setPrimaryClip(clip)
        showToast("📋 Results copied to clipboard")
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    // ========================================
    // TESTING CONNECTION HELPERS
    // ========================================
    
    /**
     * Start testing connection - uses same BLE scanning method as LoggerService
     */
    private fun startTestingConnection() {
        showTestProgress("Scanning for Student Scanner devices...")
        
        if (!checkRuntimePermissions()) {
            showToast("❌ Missing Bluetooth permissions")
            hideTestProgress()
            return
        }
        
        // Use same BLE scanning approach as LoggerService
        bleManager.startScanning { device ->
            if (!isValidStudentScannerDevice(device)) {
                return@startScanning
            }
            
            Log.d(TAG, "🧪 Valid Student Scanner device found for testing: ${device.address} (${device.name})")
            
            runOnUiThread {
                updateTestProgress("Found Student Scanner: ${device.name ?: device.address}")
            }
            
            // Test the connection immediately to verify it's actually running the SPP server
            testDeviceConnection(device) { isConnectable ->
                runOnUiThread {
                    if (isConnectable) {
                        hideTestProgress()
                        bleManager.stopScanning()
                        
                        // Set the device for testing
                        currentStudentScannerDevice = device
                        showToast("✅ Connected to ${device.name ?: "Unknown Device"} for testing")
                        
                        // Show testing interface now that we're connected
                        showTestingInterface()
                    } else {
                        updateTestProgress("Device found but SPP server not ready. Continuing scan...")
                        Log.d(TAG, "🧪 Device ${device.address} found but SPP server not responding")
                    }
                }
            }
        }
        
        // Add timeout like LoggerService
        Handler(Looper.getMainLooper()).postDelayed({
            bleManager.stopScanning()
            runOnUiThread {
                hideTestProgress()
                showToast("❌ No Student Scanner devices found. Make sure:\n• Student Scanner app is running\n• BLE Host mode is enabled\n• Both devices have Bluetooth permissions")
            }
        }, 30000)
    }
    
    /**
     * Test if a device has an active SPP server for testing
     */
    private fun testDeviceConnection(device: BluetoothDevice, onResult: (Boolean) -> Unit) {
        Thread {
            try {
                Log.d(TAG, "🧪 Testing SPP connection to: ${device.address}")
                
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val bluetoothAdapter = bluetoothManager.adapter
                
                if (bluetoothAdapter == null) {
                    onResult(false)
                    return@Thread
                }
                
                val socket = device.createRfcommSocketToServiceRecord(
                    java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
                
                bluetoothAdapter.cancelDiscovery()
                
                // Set a short timeout for testing
                socket.connect()
                
                // Send a simple test command to verify the server is responding
                val outputStream = socket.outputStream
                val inputStream = socket.inputStream
                
                outputStream.write("TEST_STATUS\n".toByteArray())
                outputStream.flush()
                
                // Wait for response with timeout
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                
                socket.close()
                
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead).trim()
                    Log.d(TAG, "🧪 SPP test response: $response")
                    
                    // Check if we got a valid test response
                    val isValid = response.startsWith("RESULT:")
                    Log.d(TAG, "🧪 SPP server test result: $isValid")
                    onResult(isValid)
                } else {
                    Log.d(TAG, "🧪 No response from SPP server")
                    onResult(false)
                }
                
            } catch (e: Exception) {
                Log.d(TAG, "🧪 SPP connection test failed: ${e.message}")
                onResult(false)
            }
        }.start()
    }
    
    /**
     * Update test progress message
     */
    private fun updateTestProgress(message: String) {
        statusText.text = message
        Log.d(TAG, "🧪 Test progress: $message")
    }
    
    /**
     * Same validation logic as LoggerService
     */
    private fun isValidStudentScannerDevice(device: BluetoothDevice): Boolean {
        // Same validation logic as LoggerService - accept devices filtered by service UUID
        val deviceName = device.name ?: ""
        Log.d(TAG, "Device validation - ${device.address} (${deviceName}): Accepting (filtered by service UUID)")
        return true // Since BLE scanning filters by service UUID, all found devices should be valid
    }
    
    /**
     * Show manual device selection for testing
     */
    private fun showManualDeviceSelection() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            showToast("❌ Bluetooth not available")
            return
        }
        
        try {
            val pairedDevices = bluetoothAdapter.bondedDevices
            if (pairedDevices.isEmpty()) {
                showToast("❌ No paired devices found. Please pair with Student Scanner first.")
                return
            }
            
            val deviceNames = pairedDevices.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
            val devices = pairedDevices.toList()
            
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📱 Select Student Scanner Device")
                .setItems(deviceNames) { _, which ->
                    val selectedDevice = devices[which]
                    currentStudentScannerDevice = selectedDevice
                    showToast("✅ Selected ${selectedDevice.name ?: "Unknown Device"} for testing")
                    
                    // Show testing interface
                    showTestingInterface()
                }
                .setNegativeButton("Cancel", null)
                .show()
                
        } catch (e: SecurityException) {
            showToast("❌ Missing Bluetooth permissions")
        }
    }
    
    /**
     * Disconnect current device
     */
    private fun disconnectCurrentDevice() {
        currentStudentScannerDevice = null
        showToast("🔌 Disconnected from Student Scanner")
        
        // Stop any active scanning
        bleManager.stopScanning()
        isReceiverFlowActive = false
    }
    
    // QR Code Analyzer class
    private inner class QRCodeAnalyzer(private val onQRCodeDetected: (String) -> Unit) : ImageAnalysis.Analyzer {
        private val scanner = BarcodeScanning.getClient()
        
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            when (barcode.valueType) {
                                Barcode.TYPE_TEXT, Barcode.TYPE_URL -> {
                                    barcode.rawValue?.let { qrCode ->
                                        onQRCodeDetected(qrCode)
                                        return@addOnSuccessListener
                                    }
                                }
                            }
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "❌ QR scanning failed: ${exception.message}")
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }
}
