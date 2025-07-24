package com.example.logger.bluetooth

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import android.os.Build

class BleLogDownloadClient(private val context: Context) {
    companion object {
        private const val TAG = "BleLogDownloadClient"
        val SERVICE_UUID: UUID = UUID.fromString("87654321-4321-4321-4321-123456789abc")
        val CHAR_LOG_UUID: UUID = UUID.fromString("87654321-4321-4321-4321-123456789abd")
        private const val CHUNK_SIZE = 512 // bytes
        private const val MAX_RETRIES = 3
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var logBuffer = mutableListOf<Byte>()
    private var logLength = 0
    private var currentOffset = 0
    private var retries = 0
    private var onLogDownloaded: ((String?) -> Unit)? = null
    private var logChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())

    fun downloadScanLog(device: BluetoothDevice, onComplete: (String?) -> Unit) {
        logBuffer.clear()
        logLength = 0
        currentOffset = 0
        retries = 0
        onLogDownloaded = onComplete
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d(TAG, "Connecting to GATT server on device: ${device.address}")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "GATT connection state changed: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server, discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server")
                handler.post { onLogDownloaded?.invoke(null) }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "Services discovered: status=$status")
            val service = gatt.getService(SERVICE_UUID)
            if (service != null) {
                logChar = service.getCharacteristic(CHAR_LOG_UUID)
                if (logChar != null) {
                    Log.d(TAG, "Found log characteristic, starting read at offset 0")
                    readNextChunk()
                } else {
                    Log.e(TAG, "Log characteristic not found")
                    handler.post { onLogDownloaded?.invoke(null) }
                }
            } else {
                Log.e(TAG, "Log service not found")
                handler.post { onLogDownloaded?.invoke(null) }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == CHAR_LOG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val chunk = characteristic.value
                    Log.d(TAG, "Read chunk: offset=$currentOffset, size=${chunk.size}")
                    if (chunk.isNotEmpty()) {
                        logBuffer.addAll(chunk.toList())
                        currentOffset += chunk.size
                        if (chunk.size == CHUNK_SIZE) {
                            // More data to read
                            readNextChunk()
                        } else {
                            // Last chunk
                            val logJson = logBuffer.toByteArray().toString(Charsets.UTF_8)
                            Log.d(TAG, "Full log downloaded, size=${logBuffer.size}")
                            handler.post { onLogDownloaded?.invoke(logJson) }
                            gatt.disconnect()
                        }
                    } else {
                        // No more data
                        val logJson = logBuffer.toByteArray().toString(Charsets.UTF_8)
                        Log.d(TAG, "Full log downloaded (empty chunk), size=${logBuffer.size}")
                        handler.post { onLogDownloaded?.invoke(logJson) }
                        gatt.disconnect()
                    }
                } else {
                    Log.e(TAG, "Characteristic read failed: $status")
                    if (retries < MAX_RETRIES) {
                        retries++
                        Log.d(TAG, "Retrying read, attempt $retries")
                        readNextChunk()
                    } else {
                        handler.post { onLogDownloaded?.invoke(null) }
                        gatt.disconnect()
                    }
                }
            }
        }
    }

    private fun readNextChunk() {
        logChar?.let { char ->
            // On Android 13+ (API 33), you can use the offset/length API for chunked reads
            // On older versions, only the full value is returned (up to MTU size)
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    val method = bluetoothGatt?.javaClass?.getMethod("readCharacteristic", BluetoothGattCharacteristic::class.java, Int::class.java, Int::class.java)
                    if (method != null) {
                        method.invoke(bluetoothGatt, char, currentOffset, CHUNK_SIZE)
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "readCharacteristic with offset/length not available: ${e.message}")
                }
            }
            // Fallback: just read the characteristic (will return up to MTU size)
            char.value = null // Clear previous value
            bluetoothGatt?.readCharacteristic(char)
        }
    }

    fun close() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
} 