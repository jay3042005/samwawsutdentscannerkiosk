package com.arjay.logger.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.UUID
import android.os.Handler
import android.os.Looper
import com.example.logger.bluetooth.BleLogDownloadClient

class BleManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BleManager"
        private const val NOTIFICATION_CHANNEL_ID = "BleManagerDebug"
        private const val NOTIFICATION_ID = 2001
        // FIXED: Use the same UUID as Student Scanner app
        private val SERVICE_UUID = UUID.fromString("87654321-4321-4321-4321-123456789abc")
        // Add characteristic UUID for GATT service (if needed for future use)
        private val CHARACTERISTIC_UUID = UUID.fromString("87654321-4321-4321-4321-123456789abc")
        private val READY_SIGNAL = "READY_TO_RECEIVE"
        private const val APP_IDENTIFIER = "LoggerKioskReceiver"
        private const val STUDENT_SCANNER_IDENTIFIER = "StudentScannerHost"
    }
    
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var bleLogDownloadClient: BleLogDownloadClient? = null
    private var scanHandler: Handler? = null
    private var isScanningForLog = false
    
    private var onDeviceFound: ((BluetoothDevice) -> Unit)? = null
    private var onReadySignalReceived: (() -> Unit)? = null
    
    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BLE Manager Debug",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Debug notifications for BLE Manager"
                setShowBadge(false)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun showDebugNotification(title: String, message: String) {
        createNotificationChannel() // Ensure channel exists before showing notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // Show toast on main thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, "$title: $message", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun startAdvertising(onReadySignalReceived: () -> Unit) {
        this.onReadySignalReceived = onReadySignalReceived
        
        createNotificationChannel()
        showDebugNotification("BLE", "Starting BLE advertising")
        
        if (!isBluetoothSupported() || !isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth not supported or not enabled")
            showDebugNotification("BLE", "Bluetooth not supported or not enabled")
            return
        }
        
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            // .addManufacturerData(0x1234, APP_IDENTIFIER.toByteArray()) // REMOVE for Android 7 compatibility
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_ADVERTISE permission not granted")
                showDebugNotification("BLE", "BLUETOOTH_ADVERTISE permission not granted")
                return
            }
        } else {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH or BLUETOOTH_ADMIN permission not granted")
                showDebugNotification("BLE", "BLUETOOTH or BLUETOOTH_ADMIN permission not granted")
                return
            }
        }
        bluetoothLeAdvertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
    }
    
    fun stopAdvertising() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                }
            } else {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for stopAdvertising: ${e.message}")
        }
    }
    
    fun startScanning(onDeviceFound: (android.bluetooth.BluetoothDevice) -> Unit) {
        Log.d(TAG, "Starting BLE scanning with service UUID filter: $SERVICE_UUID")
        
        if (!isBluetoothSupported() || !isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth not supported or not enabled")
            return
        }
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_SCAN permission not granted")
            return
        }
        
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        
        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        
        // Store the callback for device discovery
        this.onDeviceFound = onDeviceFound
    }
    
    fun stopScanning() {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                Log.d(TAG, "BLE scanning stopped")
                showDebugNotification("BLE", "Scanning stopped")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for stopScanning: ${e.message}")
        }
    }
    
    fun connectToDevice(device: BluetoothDevice, onConnected: () -> Unit) {
        // Note: Student Scanner app only advertises BLE, it doesn't implement GATT services
        // So we don't need to connect to GATT services. The BLE scanning is sufficient
        // to detect the Student Scanner app and initiate Bluetooth SPP transfer.
        Log.d(TAG, "Student Scanner detected via BLE, ready for Bluetooth SPP transfer")
        onConnected()
    }
    
    fun disconnect() {
        gatt?.disconnect()
        gatt = null
    }

    fun scanAndDownloadScanLog(
        onProgress: (String) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isScanningForLog) {
            onError("Already scanning for StudentScanner BLE device.")
            return
        }
        isScanningForLog = true
        onProgress("Scanning for StudentScanner BLE device...")
        val bluetoothAdapter = bluetoothAdapter ?: run {
            onError("Bluetooth not supported on this device.")
            isScanningForLog = false
            return
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onError("BLE scanner not available.")
            isScanningForLog = false
            return
        }
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleLogDownloadClient.SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanHandler = Handler(Looper.getMainLooper())
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    onProgress("Found StudentScanner device: ${device.address}, connecting...")
                    scanner.stopScan(this)
                    isScanningForLog = false
                    bleLogDownloadClient = BleLogDownloadClient(context)
                    bleLogDownloadClient?.downloadScanLog(device) { logJson ->
                        if (logJson != null) {
                            onSuccess(logJson)
                        } else {
                            onError("Failed to download scan log from device.")
                        }
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                onError("BLE scan failed: $errorCode")
                isScanningForLog = false
            }
        }
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        scanHandler?.postDelayed({
            if (isScanningForLog) {
                scanner.stopScan(scanCallback)
                isScanningForLog = false
                onError("Timeout: No StudentScanner BLE device found.")
            }
        }, 10000) // 10 seconds timeout
    }

    fun cancelLogDownload() {
        bleLogDownloadClient?.close()
        bleLogDownloadClient = null
        isScanningForLog = false
        scanHandler?.removeCallbacksAndMessages(null)
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE advertising started successfully")
            showDebugNotification("BLE", "Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed to start: $errorCode")
            showDebugNotification("BLE", "Advertising failed: error $errorCode")
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val device = scanResult.device
                val deviceName = device.name ?: "Unknown"
                val deviceAddress = device.address
                
                Log.d(TAG, "Found BLE device: $deviceName ($deviceAddress)")
                
                // Check if this is a Student Scanner app by looking for our service UUID
                val scanRecord = scanResult.scanRecord
                val serviceUuids = scanRecord?.serviceUuids
                
                Log.d(TAG, "Scan record service UUIDs: ${serviceUuids?.map { it.uuid }}")
                Log.d(TAG, "Device bond state: ${device.bondState}")
                Log.d(TAG, "Device type: ${device.type}")
                
                if (serviceUuids?.any { it.uuid == SERVICE_UUID } == true) {
                    Log.d(TAG, "✅ Found Student Scanner app: $deviceName ($deviceAddress)")
                    
                    // Check if this is our own device (Logger app shouldn't connect to itself)
                    val localAddress = bluetoothAdapter?.address
                    if (deviceAddress == localAddress) {
                        Log.d(TAG, "❌ Ignoring own device: $deviceName ($deviceAddress) - This is the Logger app itself")
                        return@let
                    }
                    
                    // Additional check: Make sure this is actually a Student Scanner device
                    // Student Scanner devices should have a specific name pattern or be the correct device
                    if (deviceName.contains("Student") || deviceName.contains("Scanner") || deviceName.contains("usap64")) {
                        Log.d(TAG, "✅ Confirmed Student Scanner device: $deviceName ($deviceAddress)")
                        showDebugNotification("BLE", "✅ Student Scanner found: $deviceName")
                        onDeviceFound?.invoke(device)
                    } else {
                        Log.d(TAG, "⚠️ Device has correct UUID but wrong name: $deviceName ($deviceAddress)")
                        // Still invoke the callback but log a warning
                        showDebugNotification("BLE", "⚠️ Potential Student Scanner: $deviceName")
                        onDeviceFound?.invoke(device)
                    }
                } else {
                    Log.d(TAG, "❌ Ignoring non-Student Scanner device: $deviceName (UUIDs: ${serviceUuids?.map { it.uuid }})")
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            showDebugNotification("BLE", "Scan failed: error $errorCode")
        }
    }
} 