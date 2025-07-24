package com.arjay.logger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arjay.logger.R
import com.arjay.logger.bluetooth.BleManager
import com.arjay.logger.bluetooth.BluetoothClient
import com.arjay.logger.bluetooth.BluetoothServer
import com.arjay.logger.data.repository.ScanLogRepository
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

class LoggerService : Service() {
    
    companion object {
        private const val TAG = "LoggerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "LoggerServiceChannel"
        private const val MAX_RETRY_ATTEMPTS = 3
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bleManager: BleManager
    private lateinit var scanLogRepository: ScanLogRepository
    private lateinit var bluetoothAdapter: android.bluetooth.BluetoothAdapter
    
    private var isRunning = false
    private var retryCount = 0
    private var isHost = true // Default to host
    private var bluetoothSppServer: com.arjay.logger.bluetooth.BluetoothServer? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        // Initialize components
        bleManager = BleManager(this)
        bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        
        // Only initialize PocketBase API if we're the host
        val retrofit = Retrofit.Builder()
            .baseUrl("http://localhost:8090/") // Host phone uses localhost
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        val api = retrofit.create(com.arjay.logger.data.api.PocketBaseApi::class.java)
        scanLogRepository = ScanLogRepository(api, this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        // Always act as receiver
        isHost = false
        if (!isRunning) {
            isRunning = true
            startForeground(NOTIFICATION_ID, createNotification("Logger Service Running (Receiver)"))
            Log.d(TAG, "Starting workflow as Receiver...")
            startWorkflow()
        } else {
            Log.d(TAG, "Service already running, ignoring start command")
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        isRunning = false
        serviceScope.cancel()
        
        // Cleanup
        bleManager.stopAdvertising()
        bleManager.stopScanning()
        bleManager.disconnect()
        bluetoothSppServer?.let {
            Log.d(TAG, "Service destroyed: Closing Bluetooth SPP server instance")
            try {
                it.stopServer()
            } catch (e: Exception) {
                Log.e(TAG, "Service destroyed: Error closing SPP server: ${e.message}")
            }
        }
    }
    
    private fun startWorkflow() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Starting workflow as Receiver")
                updateNotification("Starting as Receiver...")
                startReceiverWorkflow()
            } catch (e: Exception) {
                Log.e(TAG, "Workflow error: ${e.message}")
                e.printStackTrace()
                updateNotification("Workflow error: ${e.message}")
                retryWorkflow()
            }
        }
    }
    
    private suspend fun startReceiverWorkflow() {
        Log.d(TAG, "Starting receiver workflow")
        updateNotification("Receiver: Scanning for host BLE advertisement...")
        
        // Step 1: Start BLE scanning for host advertisement
        startBleScanning()
    }
    
    private fun startBleScanning() {
        Log.d(TAG, "Receiver: Starting BLE scanning for host devices...")
        updateNotification("Receiver: Scanning for host BLE advertisement...")
        
        var devicesFound = 0
        var validDevicesFound = 0
        
        bleManager.startScanning { device ->
            devicesFound++
            Log.d(TAG, "Receiver: Device #$devicesFound found via BLE: ${device.address} (${device.name ?: "Unknown"})")
            
            // Verify this is actually our Logger app host device
            if (isValidHostDevice(device)) {
                validDevicesFound++
                Log.d(TAG, "Receiver: Valid host device #$validDevicesFound found, connecting via Bluetooth SPP...")
                updateNotification("Receiver: Host found, connecting via Bluetooth SPP...")
                
                // Stop BLE scanning once valid host is found
                bleManager.stopScanning()
                
                // Connect to host via Bluetooth SPP and receive file
                connectToHostAndReceiveFile(device.address)
            } else {
                Log.d(TAG, "Receiver: Ignoring non-host device: ${device.address} (${device.name ?: "Unknown"})")
            }
        }
        
        // Add a timeout to check if no valid hosts are found
        serviceScope.launch {
            delay(30000) // 30 seconds timeout
            if (validDevicesFound == 0) {
                Log.w(TAG, "Receiver: No valid host devices found after 30 seconds. Make sure a host device is running the Logger app in Host mode.")
                updateNotification("Receiver: No valid hosts found - check if host is running")
            }
        }
    }
    
    private fun isValidHostDevice(device: android.bluetooth.BluetoothDevice): Boolean {
        // For now, accept any device that advertises our service UUID
        // The BLE scanning filter should already ensure this is our app
        val deviceName = device.name ?: ""
        Log.d(TAG, "Receiver: Device validation - ${device.address} (${deviceName}): Accepting (filtered by service UUID)")
        return true // Since we're filtering by service UUID, all found devices should be valid
    }
    
    private fun connectToHostAndReceiveFile(hostAddress: String) {
        Log.d(TAG, "Receiver: Initiating Bluetooth SPP connection to host $hostAddress...")
        updateNotification("Receiver: Connecting to host...")
        
        // Use actual BluetoothClient for connection
        val client = BluetoothClient(bluetoothAdapter, this)
        val outputFile = File(filesDir, "scan_logs_received.json")
        val extractedFile = File(filesDir, "scan_logs_extracted.json")
        
        Log.d(TAG, "Receiver: Connecting to host $hostAddress via Bluetooth SPP...")
        updateNotification("Receiver: Connecting to host...")
        
        // For background service, we'll use a simple connection without QR verification
        // since the service doesn't have QR scanning capabilities
        client.connectAndReceiveSimpleWithAddress(hostAddress, outputFile) { success, errorType ->
            if (success) {
                Log.d(TAG, "Receiver: File received successfully from host $hostAddress")
                updateNotification("Receiver: File received successfully")
                // Use extractedFile for further processing if needed
                // Restart workflow after successful reception
                serviceScope.launch {
                    delay(5000)
                    Log.d(TAG, "Receiver: Restarting workflow for next transfer cycle...")
                    startWorkflow()
                }
            } else {
                if (errorType == "NO_LOGS") {
                    Log.e(TAG, "Receiver: No scan logs available on Student Scanner")
                    updateNotification("No scan logs available on Student Scanner")
                    // Do NOT retry, just stop the workflow
                    stopSelf()
                } else {
                    Log.e(TAG, "Receiver: Failed to receive file from host $hostAddress (errorType=$errorType)")
                    updateNotification("Receiver: File transfer failed")
                    // Retry after delay
                    serviceScope.launch {
                        delay(5000)
                        Log.d(TAG, "Receiver: Retrying connection to host...")
                        startWorkflow()
                    }
                }
            }
        }
    }
    
    private fun retryWorkflow() {
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCount++
            Log.d(TAG, "Retrying workflow (attempt $retryCount/$MAX_RETRY_ATTEMPTS) for Receiver")
            updateNotification("Retrying... (attempt $retryCount)")
            
            serviceScope.launch {
                delay(10000) // 10 seconds delay before retry
                Log.d(TAG, "Retry delay completed, restarting workflow...")
                startWorkflow()
            }
        } else {
            Log.e(TAG, "Max retry attempts reached for Receiver")
            updateNotification("Max retry attempts reached")
            retryCount = 0 // Reset for next cycle
        }
    }
    
    private fun createNotification(message: String): Notification {
        createNotificationChannel()
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Logger Service")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Using a system icon for now
            .setPriority(NotificationManager.IMPORTANCE_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(message))
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Logger Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for Logger Service notifications"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
} 