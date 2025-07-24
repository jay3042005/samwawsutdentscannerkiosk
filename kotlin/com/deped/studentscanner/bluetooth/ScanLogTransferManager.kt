package com.deped.studentscanner.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.studentscanner.data.LocalStorageManager
import java.io.File
import java.io.IOException
import java.util.UUID

class ScanLogTransferManager(
    private val context: Context,
    private val localStorageManager: LocalStorageManager
) {
    companion object {
        private const val TAG = "ScanLogTransferManager"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SERVICE_NAME = "StudentScannerTransfer"
    }
    
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var isTransferring = false
    
    /**
     * Starts the Bluetooth SPP server to handle incoming transfer requests
     * @param onLoggerConnected Callback when a Logger device connects (for QR code display)
     * @param onTransferComplete Callback when transfer completes
     */
    suspend fun startTransferServer(
        onLoggerConnected: (String) -> Unit,
        onQrCodeRequested: (String) -> Unit = { },
        onTransferComplete: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔵 Starting Bluetooth SPP server for scan log transfer...")
        Log.d(TAG, "🔵 Service name: $SERVICE_NAME, UUID: $SPP_UUID")
        
        if (bluetoothAdapter == null) {
            Log.e(TAG, "❌ Bluetooth not available")
            onTransferComplete(false, "Bluetooth not available")
            return@withContext
        }
        
        Log.d(TAG, "🔵 Bluetooth adapter available: ${bluetoothAdapter.name}")
        Log.d(TAG, "🔵 Bluetooth adapter address: ${bluetoothAdapter.address}")
        
        isTransferring = true
        
        try {
            // Create server socket once and keep it open for multiple connections
            Log.d(TAG, "📡 Creating persistent Bluetooth SPP server socket...")
            serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            Log.d(TAG, "✅ Persistent Bluetooth SPP server socket created successfully")
            // Add delay to ensure server is ready before accepting connections
            Thread.sleep(2000)
            
            while (isTransferring) {
                Log.d(TAG, "[SPP] Waiting for SPP connection...");
                val socket: BluetoothSocket? = serverSocket?.accept()
                Log.d(TAG, "[SPP] SPP connection accepted: ${socket?.remoteDevice?.address}");
                if (socket != null && isTransferring) {
                    val loggerAddress = socket.remoteDevice?.address ?: "Unknown"
                    Log.d(TAG, "[SPP] Logger receiver connected: $loggerAddress")

                    // Notify that Logger has connected (trigger QR code display)
                    Log.d(TAG, "🎯 Calling onLoggerConnected callback...")
                    onLoggerConnected(loggerAddress)
                    Log.d(TAG, "✅ onLoggerConnected callback completed")

                    Log.d(TAG, "🔄 Starting transfer request handler...")
                    try {
                        handleTransferRequest(socket, onLoggerConnected, onQrCodeRequested, onTransferComplete)
                        Log.d(TAG, "✅ Transfer completed, ready for next connection")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in transfer handler: ${e.message}")
                        e.printStackTrace()
                    }
                    
                    // Close only the client socket, keep server socket open
                    try {
                        socket.close()
                        Log.d(TAG, "✅ Client socket closed, server remains open")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing client socket: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    Log.e(TAG, "❌ Connection failed or cancelled")
                    if (!isTransferring) {
                        break // Exit loop if transfer was stopped
                    }
                }
                // Continue loop immediately to accept next connection
                Log.d(TAG, "🔄 Ready for next Logger connection...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ SPP server error: ${e.message}")
            e.printStackTrace()
            onTransferComplete(false, "SPP server error: ${e.message}")
        } finally {
            isTransferring = false
            stopTransferServer()
        }
    }
    
    /**
     * Handles the actual transfer request from a LOgger receiver
     */
    private suspend fun handleTransferRequest(
        socket: BluetoothSocket,
        onLoggerConnected: (String) -> Unit,
        onQrCodeRequested: (String) -> Unit,
        onTransferComplete: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var transferAttempted = false
        try {
            val inputStream = socket.inputStream
            val outputStream = socket.outputStream
            Log.d(TAG, "[SPP] Reading command from socket...")
            socket.inputStream.available() // Test connection
            val buffer = ByteArray(1024)
            val bytesRead = inputStream.read(buffer)
            Log.d(TAG, "[SPP] Read $bytesRead bytes from socket")
            
            if (bytesRead <= 0) {
                Log.d(TAG, "⏳ No data received yet, Logger may still be connecting...")
                // Don't report as failure - Logger might still be connecting
                return@withContext
            }
            
            val fullMessage = String(buffer, 0, bytesRead)
            
            // Extract only the first line as the command (before first newline)
            val firstMessage = fullMessage.lines().first().trim()
            
            Log.d(TAG, "🔍 DEBUG: Received data - Length: $bytesRead, Raw bytes: ${buffer.take(bytesRead).toByteArray().contentToString()}")
            Log.d(TAG, "🔍 DEBUG: Full message: '$fullMessage'")
            Log.d(TAG, "🔍 DEBUG: Extracted command: '$firstMessage' (length: ${firstMessage.length})")
            Log.d(TAG, "🔍 DEBUG: Command bytes as hex: ${buffer.take(bytesRead).joinToString("") { "%02x".format(it) }}")

            transferAttempted = true
            
            when (firstMessage) {
                "REQUEST_QR" -> {
                    // 1. Display QR code for Logger to scan (trigger callback/UI update for kiosk mode)
                    Log.d(TAG, "🔐 Logger requested QR code, triggering display...")
                    onQrCodeRequested(socket.remoteDevice?.address ?: "Unknown")
                    // 2. Wait for Logger to send the verification token
                    val tokenBytes = ByteArray(1024)
                    val tokenRead = inputStream.read(tokenBytes)
                    val receivedToken = String(tokenBytes, 0, tokenRead).trim()
                    Log.d(TAG, "[SPP] Received token: $receivedToken")
                    // 3. Validate the token (implement your validation logic)
                    val isValid = validateToken(receivedToken) // You must implement this function
                    Log.d(TAG, "[SPP] Token valid: $isValid")
                    if (isValid) {
                        // 4. Send scan logs
                        sendScanLogs(outputStream) // You must implement this function
                        onTransferComplete(true, "QR handshake transfer complete")
                    } else {
                        outputStream.write("ERROR:INVALID_TOKEN\n".toByteArray())
                        onTransferComplete(false, "Invalid QR token")
                    }
                }
                "REQUEST_DATA" -> {
                    // File transfer mode: extract scanned token from the already-received message
                    Log.d(TAG, "📁 Logger requested file transfer, extracting token...")
                    
                    // Extract the token from the second line of the received message
                    val messageLines = fullMessage.lines()
                    val receivedToken = if (messageLines.size > 1) {
                        messageLines[1].trim()
                    } else {
                        // If token not in same message, read it separately
                        val tokenBytes = ByteArray(1024)
                        val tokenRead = inputStream.read(tokenBytes)
                        String(tokenBytes, 0, tokenRead).trim()
                    }
                    Log.d(TAG, "🔐 Received token for file transfer: $receivedToken")
                    
                    // Validate the token
                    val isValid = validateToken(receivedToken)
                    Log.d(TAG, "[SPP] Token valid: $isValid")
                    if (isValid) {
                        Log.d(TAG, "✅ Token validated, sending scan logs...")
                        sendScanLogs(outputStream)
                        onTransferComplete(true, "File transfer complete")
                    } else {
                        Log.e(TAG, "❌ Invalid token for file transfer")
                        outputStream.write("ERROR:INVALID_TOKEN\n".toByteArray())
                        onTransferComplete(false, "Invalid token for file transfer")
                    }
                }
                else -> {
                    Log.e(TAG, "[SPP] Unknown command: $firstMessage")
                    outputStream.write("ERROR:UNKNOWN_COMMAND\n".toByteArray())
                    onTransferComplete(false, "Unknown command: $firstMessage")
                }
            }
        } catch (e: IOException) {
            // Only report as failure if we actually attempted a transfer
            if (transferAttempted) {
                Log.e(TAG, "❌ Transfer failed after attempt: ${e.message}")
                e.printStackTrace()
                onTransferComplete(false, "Transfer error: ${e.message}")
            } else {
                Log.d(TAG, "⏳ Connection closed before transfer attempt - Logger may still be connecting")
                // Don't call onTransferComplete for premature disconnections
            }
        } catch (e: Exception) {
            // Only report as failure if we actually attempted a transfer
            if (transferAttempted) {
                Log.e(TAG, "❌ Transfer failed: ${e.message}")
                e.printStackTrace()
                onTransferComplete(false, "Transfer error: ${e.message}")
            } else {
                Log.d(TAG, "⏳ Connection issue before transfer - Logger may still be connecting")
                // Don't call onTransferComplete for premature issues
            }
        } finally {
            // Add a small delay before closing socket to ensure QR code has time to be scanned
            try {
                Thread.sleep(500) // 500ms delay
            } catch (e: InterruptedException) {
                // Ignore interruption
            }
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Fetches recent scan logs from LocalStorageManager
     */
    private suspend fun fetchRecentScanLogs(): List<ScanLogData> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching recent scan logs for transfer...")
            val scanLogs = localStorageManager.getScanLogs().takeLast(50)
            Log.d(TAG, "Retrieved ${scanLogs.size} scan logs for transfer")
            scanLogs.map { scanLog ->
                ScanLogData(
                    id = scanLog.id,
                    studentId = scanLog.student_id,
                    studentName = scanLog.student_name,
                    entryExitStatus = scanLog.entry_exit_status,
                    timestamp = scanLog.timestamp,
                    grade = scanLog.grade_level.toString(),
                    section = scanLog.section
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recent scan logs: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Validates the transfer token received from the LOgger receiver
     */
    private fun validateToken(token: String): Boolean {
        return validateTransferToken(token)
    }

    private suspend fun sendScanLogs(outputStream: java.io.OutputStream) {
        // Fetch recent scan logs from LocalStorageManager
        val scanLogs = fetchRecentScanLogs()
        if (scanLogs.isNotEmpty()) {
            // Convert to JSON
            val jsonData = gson.toJson(scanLogs)
            // Create ZIP file containing the JSON
            val zipFile = File(context.filesDir, "scan_logs_to_transfer.zip")
            try {
                java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zipOut ->
                    val entry = java.util.zip.ZipEntry("scan_logs.json")
                    zipOut.putNextEntry(entry)
                    zipOut.write(jsonData.toByteArray())
                    zipOut.closeEntry()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Android 14+] ❌ Error creating ZIP file: ${e.message}")
                outputStream.write("ERROR:Failed to create ZIP file\n".toByteArray())
                outputStream.flush()
                return
            }
            // Read ZIP file contents and send
            if (!zipFile.exists()) {
                Log.e(TAG, "[Android 14+] ❌ ZIP file does not exist after creation!")
                outputStream.write("ERROR:ZIP file missing\n".toByteArray())
                outputStream.flush()
                return
            }
            try {
                val fileBytes = zipFile.readBytes()
                outputStream.write("SUCCESS:${fileBytes.size}\n".toByteArray())
                outputStream.flush()
                outputStream.write(fileBytes)
                outputStream.flush()
                Log.d(TAG, "Scan logs ZIP sent successfully (${fileBytes.size} bytes, ${scanLogs.size} records)")
            } catch (e: Exception) {
                Log.e(TAG, "[Android 14+] ❌ Error reading/sending ZIP file: ${e.message}")
                outputStream.write("ERROR:Failed to send ZIP file\n".toByteArray())
                outputStream.flush()
            }
        } else {
            outputStream.write("ERROR:No scan logs available\n".toByteArray())
            outputStream.flush()
            Log.d(TAG, "No scan logs available to send")
        }
    }
    
    /**
     * Helper function to validate UUID format
     */
    private fun isValidUUID(token: String): Boolean {
        return try {
            val uuidRegex = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".toRegex()
            uuidRegex.matches(token)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validates the transfer token received from the LOgger receiver
     */
    private fun validateTransferToken(token: String): Boolean {
        return try {
            Log.d(TAG, "🔍 Validating transfer token: $token")
            
            // Special case for simple mode (background service)
            if (token == "SIMPLE_MODE_TOKEN") {
                Log.d(TAG, "✅ Simple mode token accepted for background service")
                return true
            }
            
            // Check if token is a valid UUID format (new format)
            if (isValidUUID(token)) {
                Log.d(TAG, "🔍 UUID validation - Token: $token, Valid: true")
                Log.d(TAG, "✅ Valid UUID token format detected")
                return true
            }
            
            // Fall back to legacy token validation
            Log.d(TAG, "🔍 Attempting legacy token validation...")
            
            // Legacy token format: "DEVICE_ID:TIMESTAMP:HASH" (e.g., "B4:05:A1:BE:F4:81:1752728236766:-735b4153")
            // Handle the actual format which may have more colons in device ID
            // Split by last two colons to get deviceId, timestamp, and hash
            val lastColonIndex = token.lastIndexOf(':')
            val secondLastColonIndex = token.lastIndexOf(':', lastColonIndex - 1)
            
            if (lastColonIndex == -1 || secondLastColonIndex == -1) {
                Log.e(TAG, "❌ Invalid token format: not enough colons")
                return false
            }
            
            val deviceId = token.substring(0, secondLastColonIndex)
            val timestamp = token.substring(secondLastColonIndex + 1, lastColonIndex).toLong()
            val hash = token.substring(lastColonIndex + 1)
            
            Log.d(TAG, "🔍 Legacy token parts - DeviceID: $deviceId, Timestamp: $timestamp, Hash: $hash")
            
            // Basic validation checks
            if (deviceId.isBlank() || hash.isBlank()) {
                Log.e(TAG, "❌ Empty deviceId or hash")
                return false
            }
            
            // Check if timestamp is reasonable (not too old, not in the future)
            val currentTime = System.currentTimeMillis()
            val maxAgeMs = 5 * 60 * 1000 // 5 minutes
            val timeDifference = currentTime - timestamp
            
            Log.d(TAG, "🔍 Time validation - Current: $currentTime, Token: $timestamp, Difference: ${timeDifference}ms, MaxAge: ${maxAgeMs}ms")
            
            if (timeDifference > maxAgeMs) {
                Log.e(TAG, "❌ Token too old: ${timeDifference}ms > ${maxAgeMs}ms")
                return false
            }
            
            if (timestamp > currentTime + 60000) {
                Log.e(TAG, "❌ Token timestamp in the future")
                return false
            }
            
            // Verify hash (simple validation)
            val expectedHash = "$deviceId:$timestamp".hashCode().toString(16)
            val hashValid = hash == expectedHash
            
            Log.d(TAG, "🔍 Hash validation - Expected: $expectedHash, Received: $hash, Valid: $hashValid")
            
            if (hashValid) {
                Log.d(TAG, "✅ Legacy transfer token validation successful")
            } else {
                Log.e(TAG, "❌ Hash validation failed")
            }
            
            hashValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error validating transfer token: ${e.message}")
            false
        }
    }
    
    /**
     * Stops the transfer server
     */
    fun stopTransferServer() {
        isTransferring = false
        try {
            serverSocket?.close()
            Log.d(TAG, "Transfer server stopped")
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping transfer server: ${e.message}")
        }
    }
    
    /**
     * Checks if the transfer server is currently running
     */
    fun isTransferring(): Boolean = isTransferring
    
    /**
     * Data class for scan log transfer
     */
    data class ScanLogData(
        val id: String,
        val studentId: String,
        val studentName: String,
        val entryExitStatus: String,
        val timestamp: String,
        val grade: String,
        val section: String
    )
}