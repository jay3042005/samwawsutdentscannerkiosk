package com.deped.studentscanner.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.studentscanner.data.LocalStorageManager
import java.util.*

class LogChunkBleAdvertiser(
    private val context: Context,
    private val localStorageManager: LocalStorageManager
) {
    companion object {
        private const val TAG = "LogChunkBleAdvertiser"
        private const val MANUFACTURER_ID = 0x1234
        private const val CHUNK_SIZE = 16
        private const val INTERVAL_MS = 120L
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var advertiser: BluetoothLeAdvertiser? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isAdvertising = false
    private var logId: Int = 0
    private var logChunks: List<ByteArray> = emptyList()
    private var totalChunks: Int = 0
    private var seq: Int = 0

    fun start() {
        if (isAdvertising) return
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return
        }
        val logJson = getScanLogJson()
        val logBytes = logJson.toByteArray(Charsets.UTF_8)
        logId = (System.currentTimeMillis() % 65536).toInt()
        totalChunks = (logBytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        logChunks = (0 until totalChunks).map { i ->
            val start = i * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, logBytes.size)
            logBytes.copyOfRange(start, end)
        }
        seq = 0
        isAdvertising = true
        advertiseNextChunk()
        Log.d(TAG, "Started BLE log chunk advertising: logId=$logId, totalChunks=$totalChunks")
    }

    fun stop() {
        isAdvertising = false
        advertiser?.stopAdvertising(advertiseCallback)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped BLE log chunk advertising")
    }

    fun startWithLog(logJson: String, customLogId: Int? = null) {
        if (isAdvertising) return
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return
        }
        val logBytes = logJson.toByteArray(Charsets.UTF_8)
        logId = customLogId ?: (System.currentTimeMillis() % 65536).toInt()
        totalChunks = (logBytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        logChunks = (0 until totalChunks).map { i ->
            val start = i * CHUNK_SIZE
            val end = minOf(start + CHUNK_SIZE, logBytes.size)
            logBytes.copyOfRange(start, end)
        }
        seq = 0
        isAdvertising = true
        advertiseNextChunk()
        Log.d(TAG, "Started BLE log chunk advertising (relay): logId=$logId, totalChunks=$totalChunks")
    }

    private fun advertiseNextChunk() {
        if (!isAdvertising || advertiser == null) return
        val payload = buildPayload(seq)
        val advertiseData = AdvertiseData.Builder()
            .addManufacturerData(MANUFACTURER_ID, payload)
            .build()
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        advertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        handler.postDelayed({
            advertiser?.stopAdvertising(advertiseCallback)
            seq = (seq + 1) % totalChunks
            advertiseNextChunk()
        }, INTERVAL_MS)
    }

    private fun buildPayload(seq: Int): ByteArray {
        val chunk = logChunks[seq]
        val payload = ByteArray(4 + chunk.size)
        payload[0] = (logId shr 8).toByte()
        payload[1] = (logId and 0xFF).toByte()
        payload[2] = seq.toByte()
        payload[3] = totalChunks.toByte()
        System.arraycopy(chunk, 0, payload, 4, chunk.size)
        return payload
    }

    private fun getScanLogJson(): String {
        return try {
            val logs = localStorageManager.getScanLogs()
            com.google.gson.Gson().toJson(logs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get scan logs: ${e.message}")
            "[]"
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE log chunk advertising started (seq=$seq)")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE log chunk advertising failed: $errorCode")
        }
    }
} 