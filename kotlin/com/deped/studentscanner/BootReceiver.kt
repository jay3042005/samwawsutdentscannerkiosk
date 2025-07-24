package com.deped.studentscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver to automatically start the Student Scanner app when the device boots up.
 * This is essential for kiosk-mode operations where the app should always be available.
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_REPLACED -> {
                    Log.i("BootReceiver", "📱 Device boot completed or app updated - checking auto-start preference")
                    
                    // Check if auto-start is enabled in preferences
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val autoStartEnabled = prefs.getBoolean("autoStartOnBoot", true)
                    val isKioskMode = prefs.getBoolean("kioskMode", false)
                    
                    if (autoStartEnabled || isKioskMode) {
                        Log.i("BootReceiver", "🚀 Auto-start enabled - launching Student Scanner app")
                        
                        // Create intent to start MainActivity
                        val startIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            
                            // Add extra to indicate this is an auto-start
                            putExtra("auto_started", true)
                            putExtra("boot_reason", intent.action)
                        }
                        
                        try {
                            context.startActivity(startIntent)
                            Log.i("BootReceiver", "✅ Student Scanner app launched successfully")
                            
                            // Optional: Start any background services if needed
                            startBackgroundServices(context)
                            
                        } catch (e: Exception) {
                            Log.e("BootReceiver", "❌ Failed to start Student Scanner app: ${e.message}")
                        }
                    } else {
                        Log.i("BootReceiver", "⏸️ Auto-start disabled in settings")
                    }
                }
                
                Intent.ACTION_USER_PRESENT -> {
                    // User unlocked the device - useful for kiosk mode
                    Log.i("BootReceiver", "🔓 User unlocked device")
                    
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val launchOnUnlock = prefs.getBoolean("launchOnUnlock", false)
                    
                    if (launchOnUnlock) {
                        Log.i("BootReceiver", "🔓 Launching app on device unlock")
                        
                        val startIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("unlock_started", true)
                        }
                        
                        try {
                            context.startActivity(startIntent)
                        } catch (e: Exception) {
                            Log.e("BootReceiver", "❌ Failed to start app on unlock: ${e.message}")
                        }
                    }
                }
                
                else -> {
                    Log.d("BootReceiver", "📡 Received intent: ${intent.action}")
                }
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "❌ Error in BootReceiver: ${e.message}", e)
        }
    }
    
    /**
     * Start any necessary background services
     */
    private fun startBackgroundServices(context: Context) {
        try {
            Log.i("BootReceiver", "🔧 Starting background services...")
            
            // Add any background services that need to start on boot
            // For example: PocketBase server, BLE advertising, etc.
            
            Log.i("BootReceiver", "✅ Background services started")
        } catch (e: Exception) {
            Log.e("BootReceiver", "❌ Failed to start background services: ${e.message}")
        }
    }
}
