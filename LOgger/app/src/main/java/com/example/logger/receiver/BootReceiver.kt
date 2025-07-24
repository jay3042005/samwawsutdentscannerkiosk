package com.arjay.logger.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.arjay.logger.service.LoggerService

class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Boot completed, checking if auto-start is enabled")
                
                context?.let {
                    // Check if auto-start is enabled (default: false)
                    val prefs = it.getSharedPreferences("LoggerPrefs", Context.MODE_PRIVATE)
                    val autoStartEnabled = prefs.getBoolean("auto_start_enabled", false)
                    
                    if (autoStartEnabled) {
                        Log.d(TAG, "Auto-start enabled, starting LoggerService")
                        val serviceIntent = Intent(it, LoggerService::class.java)
                        
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            it.startForegroundService(serviceIntent)
                        } else {
                            it.startService(serviceIntent)
                        }
                    } else {
                        Log.d(TAG, "Auto-start disabled, not starting LoggerService")
                    }
                }
            }
        }
    }
} 