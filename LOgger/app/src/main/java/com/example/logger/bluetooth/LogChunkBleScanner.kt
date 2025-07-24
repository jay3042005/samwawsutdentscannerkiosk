package com.example.logger.bluetooth

import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*

class LogChunkBleScanner(private val context: Context) {
    companion object {
        private const val TAG = "LogChunkBleScanner"
        private const val MANUFACTURER_ID = 0x1234
        private const val TIMEOUT_MS = 10000L
    }

    private val bluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
    private val scanner = bluetoothAdapter?.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var chunkMap = mutableMapOf<Int, MutableMap<Int, ByteArray>>() // logId -> seq -> data
    private var totalChunksMap = mutableMapOf<Int, Int>() // logId -> totalChunks
    private var onLogReceived: ((String) -> Unit)? = null
    private var scanCallback: ScanCallback? = null

    fun startScan(onLogReceived: (String) -> Unit, onProgress: (String) -> Unit, onError: (String) -> Unit) {
        if (isScanning) {
            onError("Already scanning for log chunks.")
            return
        }
        isScanning = true
        this.onLogReceived = onLogReceived
        chunkMap.clear()
        totalChunksMap.clear()
        val scanFilter = ScanFilter.Builder().setManufacturerData(MANUFACTURER_ID, byteArrayOf()).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val data = result?.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
                if (data.size < 4) return
                val logId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val seq = data[2].toInt() and 0xFF
                val total = data[3].toInt() and 0xFF
                val chunk = data.copyOfRange(4, data.size)
                val logChunks = chunkMap.getOrPut(logId) { mutableMapOf() }
                if (!logChunks.containsKey(seq)) {
                    logChunks[seq] = chunk
                    totalChunksMap[logId] = total
                    onProgress("Received chunk $seq/$total for log $logId")
                }
                // Check if all chunks received
                if (logChunks.size == total && total > 0) {
                    val logBytes = (0 until total).flatMap { logChunks[it]!!.toList() }.toByteArray()
                    val logJson = logBytes.toString(Charsets.UTF_8)
                    onLogReceived(logJson)
                    stopScan()
                }
            }
            override fun onScanFailed(errorCode: Int) {
                onError("BLE scan failed: $errorCode")
                stopScan()
            }
        }
        scanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        handler.postDelayed({
            if (isScanning) {
                onError("Timeout: Did not receive complete log.")
                stopScan()
            }
        }, TIMEOUT_MS)
        onProgress("Scanning for log chunks...")
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        scanCallback?.let { scanner?.stopScan(it) }
        handler.removeCallbacksAndMessages(null)
        scanCallback = null
        chunkMap.clear()
        totalChunksMap.clear()
        Log.d(TAG, "Stopped BLE log chunk scanning")
    }
} 