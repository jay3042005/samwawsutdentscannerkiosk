package com.deped.studentscanner.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Build
import android.util.Log
import com.studentscanner.data.LocalStorageManager
import java.nio.charset.Charset
import java.util.UUID

class StudentScannerBleGattServer(
    private val context: Context,
    private val localStorageManager: LocalStorageManager
) {
    companion object {
        private const val TAG = "StudentScannerBleGattServer"
        val SERVICE_UUID: UUID = UUID.fromString("87654321-4321-4321-4321-123456789abc")
        val CHAR_LOG_UUID: UUID = UUID.fromString("87654321-4321-4321-4321-123456789abd")
        private const val CHUNK_SIZE = 512 // bytes
    }

    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var logChunks: List<ByteArray> = emptyList()

    fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val logCharacteristic = BluetoothGattCharacteristic(
            CHAR_LOG_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(logCharacteristic)
        gattServer?.addService(service)
        Log.d(TAG, "BLE GATT server started with service $SERVICE_UUID and characteristic $CHAR_LOG_UUID")
    }

    fun stopGattServer() {
        gattServer?.close()
        gattServer = null
        Log.d(TAG, "BLE GATT server stopped")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d(TAG, "GATT connection state changed: $device, status=$status, newState=$newState")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid == CHAR_LOG_UUID) {
                Log.d(TAG, "BLE GATT read request for scan log, offset=$offset")
                val logJson = getScanLogJson()
                val logBytes = logJson.toByteArray(Charset.forName("UTF-8"))
                val end = (offset + CHUNK_SIZE).coerceAtMost(logBytes.size)
                val chunk = if (offset < logBytes.size) logBytes.copyOfRange(offset, end) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, chunk)
                Log.d(TAG, "Sent chunk: offset=$offset, size=${chunk.size}, total=${logBytes.size}")
            } else {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }
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
} 