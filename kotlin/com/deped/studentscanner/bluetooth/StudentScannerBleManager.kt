package com.deped.studentscanner.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID

class StudentScannerBleManager(private val context: Context) {
    
    companion object {
        private const val TAG = "StudentScannerBleManager"
        // Different service UUID to distinguish from LOgger app
        private val STUDENT_SCANNER_SERVICE_UUID = UUID.fromString("87654321-4321-4321-4321-123456789abc")
        private const val STUDENT_SCANNER_IDENTIFIER = "StudentScannerHost"
    }
    
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false
    
    private var onLoggerDetected: (() -> Unit)? = null
    
    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    fun startAdvertising(onLoggerDetected: () -> Unit) {
        this.onLoggerDetected = onLoggerDetected
        
        Log.d(TAG, "Starting BLE advertising for Student Scanner")
        
        if (!isBluetoothSupported() || !isBluetoothEnabled()) {
            Log.e(TAG, "Bluetooth not supported or not enabled")
            return
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_ADVERTISE permission not granted")
                return
            }
        } else {
            // For Android 7-11, check legacy permissions
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH or BLUETOOTH_ADMIN permission not granted")
                return
            }
        }

        // Android 6+ (API 23+): check location permission and services
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Location permission not granted")
                return
            }
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val isLocationEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            if (!isLocationEnabled) {
                Log.e(TAG, "Location services are not enabled")
                return
            }
        }

        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BLE advertising not supported on this device (bluetoothLeAdvertiser is null)")
            return
        }
        
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false) // We don't need connections, just advertising
            .setTimeout(0) // Advertise indefinitely
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(STUDENT_SCANNER_SERVICE_UUID))
            // .addManufacturerData(0x1234, byteArrayOf('S'.toByte(), 'S'.toByte())) // REMOVE to avoid ADVERTISE_FAILED_DATA_TOO_LARGE
            .build()
        
        bluetoothLeAdvertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
    }
    
    fun stopAdvertising() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                    isAdvertising = false
                    Log.d(TAG, "BLE advertising stopped")
                }
            } else {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                    isAdvertising = false
                    Log.d(TAG, "BLE advertising stopped")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for stopAdvertising: ${e.message}")
        }
    }
    
    fun isAdvertising(): Boolean = isAdvertising
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "Student Scanner BLE advertising started successfully")
            // Simulate logger detection for testing - in real scenario this would be triggered by actual BLE scan
            onLoggerDetected?.invoke()
        }
        
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Student Scanner BLE advertising failed to start: $errorCode")
        }
    }
}