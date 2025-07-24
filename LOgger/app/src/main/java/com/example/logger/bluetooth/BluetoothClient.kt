package com.arjay.logger.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.IOException
import java.util.UUID

class BluetoothClient(private val bluetoothAdapter: BluetoothAdapter, private val context: Context) {
    companion object {
        private const val TAG = "BluetoothClient"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    fun connectAndReceive(hostAddress: String, outputFile: File, verificationToken: String, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                Log.d(TAG, "Connecting to host: $hostAddress")
                val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(hostAddress)
                Log.d(TAG, "Found device: ${device.name} (${device.address})")
                
                // Check if device is paired, if not, attempt to pair
                if (!device.bondState.equals(android.bluetooth.BluetoothDevice.BOND_BONDED)) {
                    Log.d(TAG, "Device not paired, attempting to pair...")
                    
                    // Check permissions for pairing
                    if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        device.createBond()
                        
                        // Wait for pairing to complete (max 10 seconds)
                        var attempts = 0
                        while (device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED && attempts < 100) {
                            Thread.sleep(100) // 100ms delay
                            attempts++
                        }
                        
                        if (device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
                            Log.e(TAG, "Failed to pair with device after 10 seconds")
                            onComplete(false)
                            return@Thread
                        } else {
                            Log.d(TAG, "Successfully paired with device")
                        }
                    } else {
                        Log.e(TAG, "Missing BLUETOOTH_CONNECT permission for pairing")
                        onComplete(false)
                        return@Thread
                    }
                } else {
                    Log.d(TAG, "Device already paired")
                }
                
                val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                Log.d(TAG, "Created RFCOMM socket, attempting connection...")
                Log.d(TAG, "Connecting to device: ${device.name} (${device.address})")
                Log.d(TAG, "Using SPP UUID: $SPP_UUID")
                
                bluetoothAdapter.cancelDiscovery()
                Log.d(TAG, "Bluetooth discovery cancelled, attempting socket connection...")
                Log.d(TAG, "[SPP] Preparing to connect to device: ${device.name} (${device.address})")
                Thread.sleep(2000) // Wait 2 seconds before connecting
                Log.d(TAG, "[SPP] Attempting SPP socket.connect()...")
                socket.connect()
                Log.d(TAG, "[SPP] SPP socket.connect() successful")
                Log.d(TAG, "Connected to host via Bluetooth SPP")
                
                val outputStream = socket.outputStream
                val inputStream = socket.inputStream
                
                // Step 1: Send QR request message
                Log.d(TAG, "Sending REQUEST_QR to host...")
                outputStream.write("REQUEST_QR\n".toByteArray())
                outputStream.flush()
                
                // Step 2: Wait a moment for QR code to be displayed
                Log.d(TAG, "Waiting for QR code to be displayed...")
                Thread.sleep(2000) // Wait 2 seconds for QR code to appear
                
                // Step 3: Read response from host (should be waiting for verification token)
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead).trim()
                    Log.d(TAG, "Received response from host: '$response'")
                }
                
                // Step 4: Send the actual verification token that was scanned
                Log.d(TAG, "Sending verification token: $verificationToken")
                outputStream.write("$verificationToken\n".toByteArray())
                outputStream.flush()
                
                // Step 5: Wait a moment for the host to process the token
                Log.d(TAG, "Waiting for host to process verification token...")
                Thread.sleep(1000) // Wait 1 second for processing
                
                // Step 6: Read the file data with Android 14+ compatibility
                Log.d(TAG, "Receiving file to: ${outputFile.name}")
                
                try {
                    // First, read the response header to get file size
                    val headerBuffer = ByteArray(1024)
                    val headerBytesRead = inputStream.read(headerBuffer)
                    val headerResponse = String(headerBuffer, 0, headerBytesRead).trim()
                    Log.d(TAG, "[Android 14+] Received header: '$headerResponse'")
                    
                    if (headerResponse.startsWith("SUCCESS:")) {
                        val fileSize = headerResponse.substringAfter("SUCCESS:").toLongOrNull() ?: 0L
                        Log.d(TAG, "[Android 14+] Expected file size: $fileSize bytes")
                        
                        outputFile.outputStream().use { fileOut ->
                            var totalBytesRead = 0L
                            val readBuffer = ByteArray(8192) // Larger buffer for Android 14+
                            
                            while (totalBytesRead < fileSize) {
                                val bytesToRead = minOf(readBuffer.size.toLong(), fileSize - totalBytesRead).toInt()
                                val bytesRead = inputStream.read(readBuffer, 0, bytesToRead)
                                if (bytesRead == -1) {
                                    Log.w(TAG, "[Android 14+] Unexpected end of stream at $totalBytesRead/$fileSize bytes")
                                    break
                                }
                                
                                fileOut.write(readBuffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                
                                if (totalBytesRead % 10240 == 0L) { // Log every 10KB
                                    Log.d(TAG, "[Android 14+] Progress: ${totalBytesRead}/$fileSize bytes (${(totalBytesRead * 100 / fileSize)}%)")
                                }
                            }
                            
                            Log.d(TAG, "[Android 14+] Total bytes received: $totalBytesRead/$fileSize")
                            
                            if (totalBytesRead != fileSize) {
                                Log.e(TAG, "[Android 14+] File size mismatch: expected $fileSize, got $totalBytesRead")
                                onComplete(false)
                                return@Thread
                            }
                        }
                    } else if (headerResponse.startsWith("ERROR:")) {
                        Log.e(TAG, "[Android 14+] Server error: $headerResponse")
                        onComplete(false)
                        return@Thread
                    } else {
                        Log.e(TAG, "[Android 14+] Invalid response header: $headerResponse")
                        onComplete(false)
                        return@Thread
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[Android 14+] Error reading file data: ${e.message}")
                    onComplete(false)
                    return@Thread
                }
                
                inputStream.close()
                socket.close()
                Log.d(TAG, "File received successfully via Bluetooth SPP")
                onComplete(true)
            } catch (e: IOException) {
                Log.e(TAG, "Bluetooth SPP client error: ${e.message}")
                e.printStackTrace()
                onComplete(false)
            }
        }.start()
    }

    fun connectAndReceiveSimple(hostAddress: String, outputFile: File, onComplete: (Boolean, String?) -> Unit) {
        Thread {
            try {
                Log.d(TAG, "Connecting to host: $hostAddress (simple mode)")
                val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(hostAddress)
                Log.d(TAG, "Found device: ${device.name} (${device.address})")
                
                // Check if device is paired, if not, attempt to pair
                if (!device.bondState.equals(android.bluetooth.BluetoothDevice.BOND_BONDED)) {
                    Log.d(TAG, "Device not paired, attempting to pair...")
                    
                    // Check permissions for pairing
                    if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        device.createBond()
                        
                        // Wait for pairing to complete (max 10 seconds)
                        var attempts = 0
                        while (device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED && attempts < 100) {
                            Thread.sleep(100) // 100ms delay
                            attempts++
                        }
                        
                        if (device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
                            Log.e(TAG, "Failed to pair with device after 10 seconds")
                            onComplete(false, "PAIRING_FAILED")
                            return@Thread
                        } else {
                            Log.d(TAG, "Successfully paired with device")
                        }
                    } else {
                        Log.e(TAG, "Missing BLUETOOTH_CONNECT permission for pairing")
                        onComplete(false, "MISSING_PERMISSION")
                        return@Thread
                    }
                } else {
                    Log.d(TAG, "Device already paired")
                }
                
                val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                Log.d(TAG, "Created RFCOMM socket, attempting connection...")
                
                bluetoothAdapter.cancelDiscovery()
                socket.connect()
                Log.d(TAG, "Connected to host via Bluetooth SPP")
                
                val outputStream = socket.outputStream
                val inputStream = socket.inputStream
                
                // Simple mode: Just send a direct request for data
                Log.d(TAG, "Sending direct data request to host...")
                outputStream.write("REQUEST_DATA\n".toByteArray())
                outputStream.flush()
                
                // Wait a moment for the host to process the request
                Log.d(TAG, "Waiting for host to process data request...")
                Thread.sleep(1000) // Wait 1 second for processing
                
                // Read the status line and any extra data in the buffer
                val statusBuffer = ByteArray(4096)
                val statusBytes = inputStream.read(statusBuffer)
                val statusString = String(statusBuffer, 0, statusBytes)
                val newlineIndex = statusString.indexOf('\n')
                if (newlineIndex == -1) {
                    Log.e(TAG, "[DEBUG] Malformed response: no newline in status line")
                    onComplete(false, "MALFORMED")
                    return@Thread
                }
                val statusLine = statusString.substring(0, newlineIndex).trim()
                Log.d(TAG, "Received status line from host: '$statusLine'")

                if (statusLine.startsWith("SUCCESS:")) {
                    val expectedSize = statusLine.removePrefix("SUCCESS:").trim().toInt()
                    var totalRead = 0
                    // Save received ZIP file
                    outputFile.outputStream().use { fileOut ->
                        // Write any data after the status line (could be part of the file)
                        val initialDataStart = newlineIndex + 1
                        val initialDataLength = statusBytes - initialDataStart
                        if (initialDataLength > 0) {
                            fileOut.write(statusBuffer, initialDataStart, initialDataLength)
                            totalRead += initialDataLength
                            Log.d(TAG, "[DEBUG] Wrote $initialDataLength bytes from initial buffer after status line")
                        }
                        // Now read the rest
                        val buffer = ByteArray(4096)
                        while (totalRead < expectedSize) {
                            val read = inputStream.read(buffer, 0, minOf(buffer.size, expectedSize - totalRead))
                            Log.d(TAG, "[DEBUG] Read $read bytes, total so far: $totalRead/$expectedSize")
                            if (read == -1) break
                            fileOut.write(buffer, 0, read)
                            totalRead += read
                        }
                    }
                    Log.d(TAG, "[DEBUG] Scan logs ZIP saved to file: ${outputFile.absolutePath}, total bytes written: $totalRead")
                    // Extract scan_logs.json from ZIP
                    val extractedJsonFile = File(outputFile.parentFile, "scan_logs_extracted.json")
                    var extractionSuccess = false
                    try {
                        java.util.zip.ZipInputStream(outputFile.inputStream()).use { zipIn ->
                            var entry = zipIn.nextEntry
                            while (entry != null) {
                                if (entry.name == "scan_logs.json") {
                                    extractedJsonFile.outputStream().use { out ->
                                        zipIn.copyTo(out)
                                    }
                                    extractionSuccess = true
                                    break
                                }
                                entry = zipIn.nextEntry
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[DEBUG] Failed to extract scan_logs.json from ZIP: ${e.message}")
                    }
                    if (extractionSuccess) {
                        Log.d(TAG, "[DEBUG] scan_logs.json extracted to: ${extractedJsonFile.absolutePath}")
                        onComplete(true, null)
                    } else {
                        Log.e(TAG, "[DEBUG] scan_logs.json not found in ZIP")
                        onComplete(false, "EXTRACTION_FAILED")
                    }
                } else if (statusLine.startsWith("ERROR:No scan logs available")) {
                    Log.e(TAG, "[DEBUG] Host returned error: $statusLine")
                    onComplete(false, "NO_LOGS")
                    return@Thread
                } else {
                    Log.e(TAG, "[DEBUG] Host returned error or unexpected response: $statusLine")
                    onComplete(false, "GENERIC")
                    return@Thread
                }
                
                inputStream.close()
                socket.close()
                Log.d(TAG, "File received successfully via Bluetooth SPP (simple mode)")
                // Only call onComplete(true, null) on actual success above
            } catch (e: IOException) {
                Log.e(TAG, "Bluetooth SPP client error (simple mode): ${e.message}")
                e.printStackTrace()
                onComplete(false, "IOEXCEPTION")
            }
        }.start()
    }
    
    // New method for file transfer after QR scan - skips REQUEST_QR phase
    fun connectAndReceiveWithToken(device: BluetoothDevice, scannedToken: String, onComplete: (Boolean) -> Unit) {
        Thread {
            try {
                Log.d(TAG, "🔗 File transfer connection to: ${device.address}")
                Log.d(TAG, "📁 Using scanned token: $scannedToken")
                
                // Check if device is paired
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.d(TAG, "Device not paired, attempting to pair...")
                    
                    if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        device.createBond()
                        
                        var attempts = 0
                        while (device.bondState != BluetoothDevice.BOND_BONDED && attempts < 100) {
                            Thread.sleep(100)
                            attempts++
                        }
                        
                        if (device.bondState != BluetoothDevice.BOND_BONDED) {
                            Log.e(TAG, "❌ Failed to pair with device after 10 seconds")
                            onComplete(false)
                            return@Thread
                        }
                    } else {
                        Log.e(TAG, "❌ Missing BLUETOOTH_CONNECT permission")
                        onComplete(false)
                        return@Thread
                    }
                } else {
                    Log.d(TAG, "✅ Device already paired")
                }
                
                val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                Log.d(TAG, "🔗 Created RFCOMM socket for file transfer")
                
                bluetoothAdapter.cancelDiscovery()
                socket.connect()
                Log.d(TAG, "✅ Connected to Student app for file transfer")
                
                val outputStream = socket.outputStream
                val inputStream = socket.inputStream
                
                // Send REQUEST_DATA command (not REQUEST_QR since QR already scanned)
                Log.d(TAG, "📤 Sending REQUEST_DATA with scanned token...")
                outputStream.write("REQUEST_DATA\n".toByteArray())
                outputStream.flush()
                
                // Immediately send the scanned token for validation
                Log.d(TAG, "🔐 Sending scanned token: $scannedToken")
                outputStream.write("$scannedToken\n".toByteArray())
                outputStream.flush()
                
                // Read validation response
                val validationBuffer = ByteArray(1024)
                val validationBytesRead = inputStream.read(validationBuffer)
                
                // Find the end of the first line (validation response)
                var headerEndIndex = -1
                for (i in 0 until validationBytesRead) {
                    if (validationBuffer[i] == '\n'.code.toByte()) {
                        headerEndIndex = i
                        break
                    }
                }
                
                val validationResponse = if (headerEndIndex != -1) {
                    String(validationBuffer, 0, headerEndIndex).trim()
                } else {
                    String(validationBuffer, 0, validationBytesRead).trim()
                }
                
                Log.d(TAG, "🔍 Validation response: '$validationResponse'")
                Log.d(TAG, "🔍 Header end at byte: $headerEndIndex, Total bytes: $validationBytesRead")
                
                if (validationResponse.startsWith("ERROR:")) {
                    Log.e(TAG, "❌ Token validation failed: $validationResponse")
                    socket.close()
                    onComplete(false)
                    return@Thread
                }
                
                // Read file data
                Log.d(TAG, "📁 Starting file transfer...")
                val outputFile = File(context.filesDir, "received_scan_logs.zip")
                
                if (validationResponse.startsWith("SUCCESS:")) {
                    val fileSizeStr = validationResponse.substringAfter("SUCCESS:").trim()
                    val fileSize = fileSizeStr.toLongOrNull() ?: 0L
                    Log.d(TAG, "📊 Parsed file size string: '$fileSizeStr' -> $fileSize bytes")
                    
                    outputFile.outputStream().use { fileOut ->
                        var totalBytesRead = 0L
                        val readBuffer = ByteArray(8192)
                        
                        // Write any binary data after the validation response header
                        if (headerEndIndex != -1 && headerEndIndex + 1 < validationBytesRead) {
                            val initialDataStart = headerEndIndex + 1
                            val initialDataLength = validationBytesRead - initialDataStart
                            fileOut.write(validationBuffer, initialDataStart, initialDataLength)
                            totalBytesRead += initialDataLength
                            Log.d(TAG, "📦 Wrote $initialDataLength bytes from initial buffer (binary ZIP data)")
                        }
                        
                        while (totalBytesRead < fileSize) {
                            val bytesRead = inputStream.read(readBuffer)
                            if (bytesRead == -1) {
                                Log.e(TAG, "❌ Unexpected end of stream")
                                break
                            }
                            
                            fileOut.write(readBuffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            
                            val progress = (totalBytesRead * 100 / fileSize).toInt()
                            if (totalBytesRead % 8192 == 0L) {
                                Log.d(TAG, "📊 Transfer progress: $progress% ($totalBytesRead/$fileSize bytes)")
                            }
                        }
                        
                        fileOut.flush()
                    }
                    
                    Log.d(TAG, "✅ File transfer completed: ${outputFile.absolutePath}")
                    Log.d(TAG, "📊 Final file size: ${outputFile.length()} bytes")
                    
                    // Extract the ZIP file to get the JSON scan logs
                    try {
                        Log.d(TAG, "📦 Extracting ZIP file...")
                        java.util.zip.ZipInputStream(outputFile.inputStream()).use { zipIn ->
                            var entry = zipIn.nextEntry
                            while (entry != null) {
                                if (entry.name == "scan_logs.json") {
                                    Log.d(TAG, "📄 Found scan_logs.json in ZIP, extracting...")
                                    val extractedFile = File(context.filesDir, "scan_logs_extracted.json")
                                    extractedFile.outputStream().use { fileOut ->
                                        zipIn.copyTo(fileOut)
                                    }
                                    Log.d(TAG, "✅ Extracted scan logs to: ${extractedFile.absolutePath}")
                                    Log.d(TAG, "📊 Extracted file size: ${extractedFile.length()} bytes")
                                    break
                                }
                                entry = zipIn.nextEntry
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error extracting ZIP file: ${e.message}")
                        e.printStackTrace()
                    }
                    
                    socket.close()
                    onComplete(true)
                } else {
                    Log.e(TAG, "❌ Unexpected response format: $validationResponse")
                    socket.close()
                    onComplete(false)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ File transfer error: ${e.message}")
                e.printStackTrace()
                onComplete(false)
            }
        }.start()
    }
    
    /**
     * Simple connection method for background service without QR verification
     * Uses device address as a string
     */
    fun connectAndReceiveSimpleWithAddress(deviceAddress: String, outputFile: File, onComplete: (Boolean, String?) -> Unit) {
        Thread {
            try {
                Log.d(TAG, "🔗 Connecting to device: $deviceAddress")
                
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                
                socket.connect()
                Log.d(TAG, "✅ Connected to device: $deviceAddress")
                
                val inputStream = socket.inputStream
                val outputStream = socket.outputStream
                
                // Send REQUEST_QR to trigger QR display
                outputStream.write("REQUEST_QR\n".toByteArray())
                outputStream.flush()
                Log.d(TAG, "📤 Sent REQUEST_QR command")
                
                // For simple connection, we'll skip QR verification
                // and directly request data
                Thread.sleep(2000) // Give time for QR display
                
                outputStream.write("REQUEST_DATA\n".toByteArray())
                outputStream.flush()
                outputStream.write("simple_token\n".toByteArray())
                outputStream.flush()
                Log.d(TAG, "📤 Sent REQUEST_DATA with simple token")
                
                // Read response
                val buffer = ByteArray(1024)
                val bytesRead = inputStream.read(buffer)
                val response = String(buffer, 0, bytesRead).trim()
                
                if (response.startsWith("ERROR:")) {
                    Log.e(TAG, "❌ Error response: $response")
                    socket.close()
                    
                    if (response.contains("NO_LOGS")) {
                        onComplete(false, "NO_LOGS")
                    } else {
                        onComplete(false, "ERROR")
                    }
                    return@Thread
                }
                
                if (response.startsWith("SUCCESS:")) {
                    val fileSize = response.substringAfter("SUCCESS:").trim().toLongOrNull() ?: 0L
                    Log.d(TAG, "📦 Receiving file of size: $fileSize bytes")
                    
                    // Receive file data
                    outputFile.outputStream().use { fileOut ->
                        val fileBuffer = ByteArray(8192)
                        var totalBytesRead = 0L
                        
                        while (totalBytesRead < fileSize) {
                            val bytesToRead = minOf(fileBuffer.size.toLong(), fileSize - totalBytesRead).toInt()
                            val bytesReceived = inputStream.read(fileBuffer, 0, bytesToRead)
                            
                            if (bytesReceived == -1) break
                            
                            fileOut.write(fileBuffer, 0, bytesReceived)
                            totalBytesRead += bytesReceived
                        }
                    }
                    
                    Log.d(TAG, "✅ File received successfully: ${outputFile.absolutePath}")
                    socket.close()
                    onComplete(true, null)
                } else {
                    Log.e(TAG, "❌ Unexpected response: $response")
                    socket.close()
                    onComplete(false, "UNEXPECTED_RESPONSE")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Simple connection error: ${e.message}")
                e.printStackTrace()
                onComplete(false, "CONNECTION_ERROR")
            }
        }.start()
    }
    
    /**
     * Send test commands to Student Scanner app for comprehensive testing
     */
    fun sendTestCommand(deviceAddress: String, testCommand: String, onResult: (Boolean, String) -> Unit) {
        Thread {
            try {
                Log.d(TAG, "🧪 Connecting to Student Scanner for test: $deviceAddress")
                Log.d(TAG, "🧪 Test command: $testCommand")
                
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                
                // Cancel discovery and connect
                bluetoothAdapter.cancelDiscovery()
                socket.connect()
                Log.d(TAG, "✅ Connected to Student Scanner for testing")
                
                val inputStream = socket.inputStream
                val outputStream = socket.outputStream
                
                // Send the test command
                Log.d(TAG, "📤 Sending test command: $testCommand")
                outputStream.write("$testCommand\n".toByteArray())
                outputStream.flush()
                
                // Wait for response
                Log.d(TAG, "⏳ Waiting for test result...")
                val buffer = ByteArray(4096) // Larger buffer for test results
                val bytesRead = inputStream.read(buffer)
                
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead).trim()
                    Log.d(TAG, "📥 Test result received: $response")
                    
                    // Parse the response
                    if (response.startsWith("RESULT:")) {
                        val resultParts = response.substringAfter("RESULT:").split(":", limit = 2)
                        val status = resultParts.getOrNull(0) ?: "UNKNOWN"
                        val message = resultParts.getOrNull(1) ?: "No details"
                        
                        val success = status == "SUCCESS"
                        Log.d(TAG, "🧪 Test result - Status: $status, Success: $success")
                        
                        socket.close()
                        onResult(success, message)
                    } else {
                        Log.e(TAG, "❌ Invalid test result format: $response")
                        socket.close()
                        onResult(false, "Invalid response format: $response")
                    }
                } else {
                    Log.e(TAG, "❌ No response received from Student Scanner")
                    socket.close()
                    onResult(false, "No response received")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Test command error: ${e.message}")
                e.printStackTrace()
                onResult(false, "Connection error: ${e.message}")
            }
        }.start()
    }
    
    /**
     * Data class for test results
     */
    data class TestResult(
        val success: Boolean,
        val message: String,
        val details: Map<String, String> = emptyMap()
    )
    
    /**
     * Send test command with detailed result parsing
     */
    fun sendTestCommandDetailed(deviceAddress: String, testCommand: String, onResult: (TestResult) -> Unit) {
        Thread {
            try {
                Log.d(TAG, "🧪 Detailed test connection to: $deviceAddress")
                Log.d(TAG, "🧪 Command: $testCommand")
                
                val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                
                bluetoothAdapter.cancelDiscovery()
                socket.connect()
                Log.d(TAG, "✅ Connected for detailed testing")
                
                val inputStream = socket.inputStream
                val outputStream = socket.outputStream
                
                // Send test command
                outputStream.write("$testCommand\n".toByteArray())
                outputStream.flush()
                Log.d(TAG, "📤 Test command sent")
                
                // Read response with timeout
                val buffer = ByteArray(8192) // Even larger buffer for detailed results
                val bytesRead = inputStream.read(buffer)
                
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead).trim()
                    Log.d(TAG, "📥 Detailed response: $response")
                    
                    // Parse detailed response
                    if (response.startsWith("RESULT:")) {
                        val resultContent = response.substringAfter("RESULT:")
                        val parts = resultContent.split("|")
                        
                        val status = parts.getOrNull(0)?.split(":")?.getOrNull(0) ?: "UNKNOWN"
                        val message = parts.getOrNull(0)?.split(":", limit = 2)?.getOrNull(1) ?: "No message"
                        
                        // Parse additional details
                        val details = mutableMapOf<String, String>()
                        for (i in 1 until parts.size) {
                            val part = parts[i]
                            val colonIndex = part.indexOf(":")
                            if (colonIndex != -1) {
                                val key = part.substring(0, colonIndex).trim()
                                val value = part.substring(colonIndex + 1).trim()
                                details[key] = value
                            }
                        }
                        
                        val success = status == "SUCCESS"
                        val testResult = TestResult(success, message, details)
                        
                        Log.d(TAG, "🧪 Parsed test result - Success: $success, Details: ${details.size}")
                        
                        socket.close()
                        onResult(testResult)
                    } else {
                        Log.e(TAG, "❌ Invalid detailed response format")
                        socket.close()
                        onResult(TestResult(false, "Invalid response format", mapOf("raw_response" to response)))
                    }
                } else {
                    Log.e(TAG, "❌ No detailed response received")
                    socket.close()
                    onResult(TestResult(false, "No response received"))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Detailed test error: ${e.message}")
                e.printStackTrace()
                onResult(TestResult(false, "Connection error: ${e.message}"))
            }
        }.start()
    }
}
