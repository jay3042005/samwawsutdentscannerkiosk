package com.arjay.logger

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.arjay.logger.service.LoggerService

class KioskActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "KioskActivity"
    }
    
    private lateinit var statusTextView: TextView
    private lateinit var logTextView: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kiosk)
        
        // Set up kiosk mode
        setupKioskMode()
        
        // Initialize views
        statusTextView = findViewById(R.id.kioskStatusTextView)
        logTextView = findViewById(R.id.kioskLogTextView)
        
        // Start the service automatically
        startLoggerService()
        
        // Update status
        updateStatus("Kiosk mode active")

        // Disable back navigation (button and gesture)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing
            }
        })
    }
    
    private fun setupKioskMode() {
        // Use modern window insets controller
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Prevent going to sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
    
    private fun startLoggerService() {
        val serviceIntent = Intent(this, LoggerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
    
    private fun updateStatus(message: String) {
        statusTextView.text = message
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Don't stop the service on activity destroy for kiosk mode
    }
} 