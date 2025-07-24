package com.deped.studentscanner.utils

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ServerManager(private val context: Context) {
    private var isServerStarting = false
    private var lastServerStartAttempt = 0L
    private val serverStartCooldown = 300L // 30s cooldown between start attempts
    
    fun resetServerStartingFlag() {
        isServerStarting = false
        println("[SERVER] 🔄 Server starting flag reset")
    }
    
    fun checkAndStartServerIfNeededWithFeedback() {
        if (isServerStarting) {
            println("[SERVER] ⏳ Server is already starting, skipping...")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastServerStartAttempt < serverStartCooldown) {
            println("[SERVER] ⏳ Server start cooldown active, skipping...")
            return
        }
        
        isServerStarting = true
        lastServerStartAttempt = currentTime
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("[SERVER] 🚀 Starting server...")
                startAutoStartServer()
            } catch (e: Exception) {
                println("[SERVER] ❌ Error starting server: ${e.message}")
                isServerStarting = false
            }
        }
    }
    
    private fun startAutoStartServer() {
        try {
            val intent = Intent(context, com.deped.studentscanner.AutoStartService::class.java)
            intent.action = "START_SERVER"
            context.startService(intent)
            println("[SERVER] ✅ AutoStartService started")
        } catch (e: Exception) {
            println("[SERVER] ❌ Error starting AutoStartService: ${e.message}")
            isServerStarting = false
        }
    }
    
    fun createAutoStartScript() {
        try {
            val scriptContent = """
                #!/system/bin/sh
                # Auto-start script for Student Scanner
                
                # Wait for system to be ready
                sleep 10
                
                # Start the app
                am start -n com.deped.studentscanner/.MainActivity
                
                # Start the server service
                am startservice -n com.deped.studentscanner/.AutoStartService
            """
            
            val scriptFile = File(Environment.getExternalStorageDirectory(), "auto_start.sh")
            scriptFile.writeText(scriptContent)
            scriptFile.setExecutable(true)
            
            println("[SERVER] ✅ Auto-start script created: ${scriptFile.absolutePath}")
        } catch (e: Exception) {
            println("[SERVER] ❌ Error creating auto-start script: ${e.message}")
        }
    }
    
    fun showServerStatus(status: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
        }
    }
    
    fun showServerError(error: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, "❌ $error", Toast.LENGTH_LONG).show()
            
            // Show detailed error dialog
            val builder = android.app.AlertDialog.Builder(context)
            builder.setTitle("🚨 Server Startup Failed")
            builder.setMessage("""
                📋 Error Details:
                $error
                
                🔧 Troubleshooting:
                1. Check if Termux is installed
                2. Verify PocketBase file exists
                3. Storage permissions are granted
                4. Try restarting the app
            """)
            builder.setPositiveButton("OK") { dialog, _->
                dialog.dismiss()
            }
            builder.show()
        }
    }
} 