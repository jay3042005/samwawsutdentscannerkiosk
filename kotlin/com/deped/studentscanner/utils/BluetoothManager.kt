package com.deped.studentscanner.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.*
import com.deped.studentscanner.bluetooth.StudentScannerBleManager
import com.deped.studentscanner.bluetooth.ScanLogTransferManager
import com.deped.studentscanner.utils.QRCodeGenerator
import com.studentscanner.data.LocalStorageManager

class BluetoothManager(private val context: Context, private val localStorageManager: LocalStorageManager) {
    private lateinit var bleManager: StudentScannerBleManager
    private lateinit var scanLogTransferManager: ScanLogTransferManager
    private var isQRCodeDisplayed = false
    private var currentQRToken: String? = null
    private var qrCodeDisplayCallback: ((String) -> Unit)? = null
    private var hideQRCodeCallback: (() -> Unit)? = null
    
    fun initializeBluetooth() {
        try {
            bleManager = StudentScannerBleManager(context)
            scanLogTransferManager = ScanLogTransferManager(context, localStorageManager)
            println("[BLE] ✅Bluetooth managers initialized")
        } catch (e: Exception) {
            println("[BLE] ❌ Error initializing Bluetooth: ${e.message}")
        }
    }
    
    fun startBLEAdvertising() {
        try {
            println("[BLE] 🔵 Starting BLE advertising...")
            
            if (::bleManager.isInitialized) {
                bleManager.startAdvertising {
                    println("[BLE] ✅ BLE advertising started successfully")
                    
                    // Start SPP server immediately when BLE advertising starts
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            println("[BLE] 📡 Starting SPP server for Logger connections...")
                            scanLogTransferManager.startTransferServer(
                                onLoggerConnected = { loggerAddress ->
                                    CoroutineScope(Dispatchers.Main).launch {
                                        try {
                                            println("[BLE] 🎯 Logger connected via SPP: $loggerAddress")
                                            // Generate QR code token for authentication
                                            currentQRToken = QRCodeGenerator.generateVerificationToken(loggerAddress, System.currentTimeMillis())
                                            println("[BLE] 🔐 Generated QR token: $currentQRToken")
                                            // Display QR code for verification
                                            displayQRCodeForVerification(currentQRToken!!)
                                            // Set up timeout to hide QR code after 30 seconds
                                            CoroutineScope(Dispatchers.Main).launch {
                                                delay(10000)
                                                if (isQRCodeDisplayed) {
                                                    hideQRCodeDisplay()
                                                    println("[BLE] ⏰ QR code display timeout")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            println("[BLE] ❌ Error handling Logger connection: ${e.message}")
                                            e.printStackTrace()
                                        }
                                    }
                                },
                                onTransferComplete = { success, message ->
                                    CoroutineScope(Dispatchers.Main).launch {
                                        try {
                                            println("[BLE] Transfer result: $success - $message")
                                            if (success) {
                                                Toast.makeText(
                                                    context,
                                                    "✅ Scan logs transferred successfully!\n$message",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "❌ Scan log transfer failed: $message",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        } catch (e: Exception) {
                                            println("[BLE] ❌ Error during scan log transfer: ${e.message}")
                                            e.printStackTrace()
                                        }
                                    }
                                }
                            )
                            println("[BLE] ✅ SPP server started successfully")
                        } catch (e: Exception) {
                            println("[BLE] ❌ Error starting SPP server: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        Toast.makeText(
                            context,
                            "🔵 BLE advertising started\n📡 Logger devices can now detect this app",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                println("[BLE] ❌ BLE manager not initialized")
            }
            
        } catch (e: Exception) {
            println("[BLE] ❌ Error starting BLE advertising: ${e.message}")
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(
                    context,
                    "❌ BLEadvertising failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    fun stopBLEAdvertising() {
        try {
            println("[BLE] 🔴 Stopping BLE advertising...")
            
            if (::bleManager.isInitialized) {
                bleManager.stopAdvertising()
                
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(
                        context,
                        "🔴 BLE advertising stopped",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            
        } catch (e: Exception) {
            println("[BLE] ❌ Error stopping BLE advertising: ${e.message}")
        }
    }
    
    fun displayQRCodeForVerification(token: String) {
        if (token.isBlank()) {
            println("[BLE] ❌ Cannot display QR code - token is blank or null")
            return
        }
        
        try {
            isQRCodeDisplayed = true
            currentQRToken = token
            println("[BLE] 🔐 Displaying QR code for verification: $token")
            
            qrCodeDisplayCallback?.invoke(token)
            
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(
                    context,
                    "🔐 QR Code displayed\n📱 Scan with Logger app to verify",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            println("[BLE] ❌ Error displaying QR code: ${e.message}")
            e.printStackTrace()
        }
    }
    
    fun hideQRCodeDisplay() {
        try {
            isQRCodeDisplayed = false
            currentQRToken = null
            println("[BLE] 🔒 Hiding QR code display")
            
            hideQRCodeCallback?.invoke()
            
        } catch (e: Exception) {
            println("[BLE] ❌ Error hiding QR code: ${e.message}")
            e.printStackTrace()
        }
    }
    
    fun setQRCodeCallbacks(
        displayCallback: (String) -> Unit,
        hideCallback: () -> Unit
    ) {
        println("[BLE] �� Setting up QR code callbacks")
        qrCodeDisplayCallback = displayCallback
        hideQRCodeCallback = hideCallback
        println("[BLE] 🔧 QR code callbacks set successfully")
    }
    
    fun cleanupBluetoothResources() {
        try {
            if (::bleManager.isInitialized) {
                bleManager.stopAdvertising()
            }
            
            if (::scanLogTransferManager.isInitialized) {
                scanLogTransferManager.stopTransferServer()
            }
            
            println("[BLE] 🧹 Bluetooth resources cleaned up")
            
        } catch (e: Exception) {
            println("[BLE] ❌ Error cleaning up Bluetooth resources: ${e.message}")
        }
    }
} 