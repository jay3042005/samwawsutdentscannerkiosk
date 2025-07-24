package com.deped.studentscanner.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionManager(private val activity: Activity) {
    
    companion object {
        const val PERMISSION_REQUEST_CODE = 1001
        const val SMS_PERMISSION_REQUEST_CODE = 1002
        const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1003
        const val BLUETOOTH_ADVERTISE_PERMISSION_REQUEST_CODE = 1004
        const val BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE = 1005
        const val BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE = 16    }
    
    fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        // Check for required permissions
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.SEND_SMS)
        }
        
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }
        
        // Bluetooth permissions for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
    
    fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Bluetooth permissions not required for Android < 12
        }
    }
    
    fun ensureDefaultSmsApp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val defaultSmsApp = Telephony.Sms.getDefaultSmsPackage(activity)
            val currentPackage = activity.packageName
            
            if (defaultSmsApp != currentPackage) {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, currentPackage)
                activity.startActivity(intent)
            }
        }
    }
    
    fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", activity.packageName, null)
        intent.data = uri
        activity.startActivity(intent)
    }
    
    fun showPermissionDeniedDialog() {
        val builder = android.app.AlertDialog.Builder(activity)
        builder.setTitle("⚠️ Permissions Required")
        builder.setMessage("This app needs the following permissions to function properly:\n\n" +
                "📁 Storage: To access PocketBase files\n" +
                "📱 SMS: To send notifications to parents\n" +
                "🔵 Bluetooth: To communicate with Logger devices\n\n" +
                "Please grant these permissions in the next screen.")
        builder.setPositiveButton("Open Settings") { _, _ ->
            openSettings()
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
    }
} 