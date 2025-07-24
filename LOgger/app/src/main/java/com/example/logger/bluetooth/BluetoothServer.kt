package com.arjay.logger.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID

class BluetoothServer(private val bluetoothAdapter: BluetoothAdapter) {
    companion object {
        private const val TAG = "BluetoothServer"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var isStopped = false

    fun startServer(file: File, onComplete: (Boolean) -> Unit) {
        isStopped = false
        Thread {
            try {
                Log.d(TAG, "Starting Bluetooth SPP server...")
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("LoggerKiosk", SPP_UUID)
                Log.d(TAG, "Bluetooth SPP server started, waiting for connection...")
                val socket: BluetoothSocket? = serverSocket?.accept()
                if (isStopped) {
                    Log.d(TAG, "Bluetooth SPP server stopped before connection accepted.")
                    serverSocket?.close()
                    onComplete(false)
                    return@Thread
                }
                Log.d(TAG, "Bluetooth SPP client connected from ${socket?.remoteDevice?.address}")
                val outputStream = socket?.outputStream
                Log.d(TAG, "Sending file: ${file.name} (${file.length()} bytes)")
                file.inputStream().use { input ->
                    outputStream?.let { input.copyTo(it) }
                }
                outputStream?.flush()
                Log.d(TAG, "File data sent, waiting for client to receive...")
                Thread.sleep(2000) // Wait 2 seconds for client to receive all data
                Log.d(TAG, "Closing connections...")
                outputStream?.close()
                socket?.close()
                serverSocket?.close()
                Log.d(TAG, "File sent successfully via Bluetooth SPP")
                onComplete(true)
            } catch (e: IOException) {
                Log.e(TAG, "Bluetooth SPP server error: ${e.message}")
                e.printStackTrace()
                onComplete(false)
            }
        }.start()
    }

    fun stopServer() {
        isStopped = true
        try {
            serverSocket?.close()
            Log.d(TAG, "Bluetooth SPP server socket closed by stopServer()")
        } catch (e: IOException) {
            Log.e(TAG, "Error closing Bluetooth SPP server socket: ${e.message}")
        }
    }
} 