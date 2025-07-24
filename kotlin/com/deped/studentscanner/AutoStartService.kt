package com.deped.studentscanner

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

class AutoStartService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var startupJob: Job? = null
    
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        android.util.Log.d("AutoStartService", "🎯 onStartCommand called with action: $action")
        
        when (action) {
            "AUTO_START" -> {
                android.util.Log.d("AutoStartService", "🤖 AUTO_START action received")
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val autoStart = prefs.getBoolean("autoStart", false)
                android.util.Log.d("AutoStartService", "🤖 Auto start setting: $autoStart")
                if (autoStart) {
                    startAutomatedSequence()
                } else {
                    android.util.Log.d("AutoStartService", "⏸️ Auto start disabled, not starting")
                }
            }
            "MANUAL_START" -> {
                android.util.Log.d("AutoStartService", "👆 MANUAL_START action received")
                startAutomatedSequence()
            }
            else -> {
                android.util.Log.d("AutoStartService", "🔄 Legacy/default action, checking auto start setting")
                // Legacy support - check auto start setting
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val autoStart = prefs.getBoolean("autoStart", false)
                android.util.Log.d("AutoStartService", "🔄 Auto start setting: $autoStart")
                if (autoStart) {
                    startAutomatedSequence()
                } else {
                    android.util.Log.d("AutoStartService", "⏸️ Auto start disabled, not starting")
                }
            }
        }
        
        android.util.Log.d("AutoStartService", "🏁 onStartCommand returning START_NOT_STICKY")
        return START_NOT_STICKY
    }

    private fun startAutomatedSequence() {
        // Cancel any existing startup job
        startupJob?.cancel()
        
        android.util.Log.d("AutoStartService", "🚀 Starting automated sequence...")
        
        startupJob = scope.launch {
            try {
                android.util.Log.d("AutoStartService", "🔄 Executing startup sequence...")
                executeStartupSequence()
            } catch (e: Exception) {
                android.util.Log.e("AutoStartService", "❌ Startup sequence failed: ${e.message}")
                sendErrorBroadcast("Startup failed: ${e.message}")
                stopSelf()
            }
        }
    }

    private suspend fun executeStartupSequence() {
        android.util.Log.d("AutoStartService", "📋 Starting executeStartupSequence...")
        
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val pocketbasePath = prefs.getString("pocketbasePath", "") ?: ""
        val selectedDistro = prefs.getString("selectedDistro", "debian") ?: "debian"
        
        android.util.Log.d("AutoStartService", "📋 PocketBase path from prefs: $pocketbasePath")
        android.util.Log.d("AutoStartService", "📋 Selected distro: $selectedDistro")
        
        // Step 1: Check cooldown to prevent multiple rapid attempts (reduced to 10 seconds)
        val currentTime = System.currentTimeMillis()
        val lastAttempt = prefs.getLong("lastServerStartAttempt", 0)
        val timeSinceLastAttempt = currentTime - lastAttempt
        
        if (timeSinceLastAttempt < 10000) { // Reduced to 10 seconds cooldown
            android.util.Log.d("AutoStartService", "⏳ Recent startup attempt detected (${timeSinceLastAttempt}ms ago), skipping...")
            sendStatusBroadcast("Recent startup attempt detected - please wait ${(10000 - timeSinceLastAttempt) / 1000}s")
            delay(1000) // Reduced delay
            returnToApp()
            stopSelf()
            return
        }
        
        // Record this startup attempt
        prefs.edit().putLong("lastServerStartAttempt", currentTime).apply()
        android.util.Log.d("AutoStartService", "🚀 Starting server (script will handle if already running)")
        
        // Step 2: Validate PocketBase path
        android.util.Log.d("AutoStartService", "📋 Step 2: Validating PocketBase path...")
        if (pocketbasePath.isEmpty()) {
            android.util.Log.e("AutoStartService", "❌ PocketBase path not configured")
            sendErrorBroadcast("PocketBase path not configured")
            stopSelf()
            return
        }
        android.util.Log.d("AutoStartService", "✅ PocketBase path validated")
        
        // Step 3: Prepare shared storage
        android.util.Log.d("AutoStartService", "📋 Step 3: Preparing shared storage...")
        sendStatusBroadcast("Preparing server files...")
        if (!prepareSharedStorage(pocketbasePath)) {
            android.util.Log.e("AutoStartService", "❌ Failed to prepare shared storage")
            sendErrorBroadcast("Failed to prepare server files")
            stopSelf()
            return
        }
        android.util.Log.d("AutoStartService", "✅ Shared storage prepared successfully")
        
        // Step 4: Execute PocketBase startup via RUN_COMMAND Intent (no UI)
        android.util.Log.d("AutoStartService", "📋 Step 4: Starting PocketBase server via background command...")
        sendStatusBroadcast("Executing server startup (background)...")
        
        if (!executeServerStartupViaRunCommand()) {
            android.util.Log.e("AutoStartService", "❌ Failed to execute server startup command")
            sendErrorBroadcast("Failed to start PocketBase server")
            stopSelf()
            return
        }
        android.util.Log.d("AutoStartService", "✅ PocketBase server startup command executed")
        
        sendStatusBroadcast("✅ Server startup script executed successfully!")
        
        // Wait briefly then return to app
        android.util.Log.d("AutoStartService", "⏳ Waiting 5 seconds then returning to app...")
        delay(5000)
        
        android.util.Log.d("AutoStartService", "📱 Returning to Student Scanner app...")
        sendStatusBroadcast("🚀 Server script executed! Returning to app...")
        returnToApp()
        
        // Service job is complete - the script takes over
        android.util.Log.d("AutoStartService", "🏁 AutoStartService job complete, stopping service")
        stopSelf()
    }

    private suspend fun prepareSharedStorage(pocketbasePath: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("AutoStartService", "📁 Preparing shared storage for: $pocketbasePath")
                
                // Handle both file paths and content URIs
                val sourceFile = if (pocketbasePath.startsWith("content://")) {
                    // If it's a content URI, we need to find the actual file
                    // Check if file was already copied to app directory
                    val appSpecificPath = "/storage/emulated/0/Android/data/com.deped.studentscanner/files/StudentScanner/pocketbase"
                    val appFile = File(appSpecificPath)
                    if (appFile.exists()) {
                        android.util.Log.d("AutoStartService", "📁 Found PocketBase in app directory: $appSpecificPath")
                        appFile
                    } else {
                        android.util.Log.e("AutoStartService", "❌ Could not find PocketBase file for content URI")
                        return@withContext false
                    }
                } else {
                    File(pocketbasePath)
                }
                
                if (!sourceFile.exists() || !sourceFile.canRead()) {
                    android.util.Log.e("AutoStartService", "❌ Source file not accessible: ${sourceFile.absolutePath}")
                    return@withContext false
                }
                
                val sharedServerDir = File("/storage/emulated/0/StudentScanner-Server")
                val targetFile = File(sharedServerDir, "pocketbase") // Always use "pocketbase" as filename
                
                android.util.Log.d("AutoStartService", "📁 Source: ${sourceFile.absolutePath}")
                android.util.Log.d("AutoStartService", "📁 Target: ${targetFile.absolutePath}")
                
                // Create directory if it doesn't exist
                if (!sharedServerDir.exists()) {
                    sharedServerDir.mkdirs()
                    android.util.Log.d("AutoStartService", "📁 Created shared directory: ${sharedServerDir.absolutePath}")
                }
                
                // Copy PocketBase binary
                sourceFile.copyTo(targetFile, overwrite = true)
                targetFile.setExecutable(true)
                android.util.Log.d("AutoStartService", "📁 Copied file: ${sourceFile.length()} bytes")
                
                // Create data directory
                val dataDir = File(sharedServerDir, "pb_data")
                if (!dataDir.exists()) {
                    dataDir.mkdirs()
                    android.util.Log.d("AutoStartService", "📁 Created data directory: ${dataDir.absolutePath}")
                }
                
                // Verify copy was successful
                val success = targetFile.exists() && targetFile.canRead() && targetFile.length() > 0
                android.util.Log.d("AutoStartService", "✅ Copy verification: $success (${targetFile.length()} bytes)")
                
                success
                
            } catch (e: Exception) {
                android.util.Log.e("AutoStartService", "❌ Error preparing shared storage: ${e.message}")
                false
            }
        }
    }

    private fun executeServerStartupViaRunCommand(): Boolean {
        return try {
            android.util.Log.d("AutoStartService", "🚀 Executing PocketBase startup via Termux...")
            
            val sharedServerDir = "/storage/emulated/0/StudentScanner-Server"
            
            // Create the startup command with better error handling
            val startupCommand = """
echo "🚀 Student Scanner - PocketBase Server Startup" && \
echo "===============================================" && \
echo "📱 Starting server for Student Scanner app..." && \
echo "" && \
echo "🔄 Stopping any existing PocketBase processes..." && \
pkill -f pocketbase 2>/dev/null || true && \
sleep 2 && \
echo "" && \
echo "📂 Setting up directories..." && \
mkdir -p /data/data/com.termux/files/home/pocketbase && \
cd /data/data/com.termux/files/home/pocketbase && \
echo "📁 Working directory: $(pwd)" && \
echo "" && \
if [ -f "$sharedServerDir/pocketbase" ]; then \
echo "📦 Copying PocketBase binary..." && \
cp "$sharedServerDir/pocketbase" ./pocketbase && \
chmod +x ./pocketbase && \
echo "✅ Binary copied and made executable" && \
echo "📊 Binary size: $(ls -lh pocketbase | awk '{print $5}')" && \
echo "" && \
if [ -x "./pocketbase" ]; then \
    echo "🗄️ Setting up data directory..." && \
    mkdir -p pb_data && \
    echo "✅ Data directory ready" && \
    echo "" && \
    echo "🚀 Starting PocketBase server..." && \
    echo "🌐 Server will be available at: http://0.0.0.0:8090" && \
    echo "📱 Return to Student Scanner app when ready" && \
    echo "" && \
    nohup ./pocketbase serve --http=0.0.0.0:8090 --dir=./pb_data > server.log 2>&1 & \
    SERVER_PID=${'$'}! && \
    echo "📊 Server PID: ${'$'}SERVER_PID" && \
    echo "⏳ Waiting for server to start..." && \
    sleep 3 && \
    if kill -0 ${'$'}SERVER_PID 2>/dev/null; then \
        echo "✅ PocketBase server is running!" && \
        echo "🌐 Access the server at: http://0.0.0.0:8090" && \
        echo "📱 You can now return to Student Scanner app" && \
        echo "" && \
        echo "💡 To stop the server later, run: pkill -f pocketbase" && \
        echo "💡 To view server logs: cat server.log" && \
        echo "" && \
        echo "🎯 Server startup completed successfully!" && \
        am start -n com.deped.studentscanner/.MainActivity 2>/dev/null || echo "Note: Return to Student Scanner manually"; \
    else \
        echo "❌ PocketBase process failed to start" && \
        echo "📋 Checking server log..." && \
        [ -f "server.log" ] && cat server.log || echo "No server log found"; \
    fi; \
else \
    echo "❌ ERROR: PocketBase binary is not executable" && \
    echo "💡 Try: chmod +x ./pocketbase"; \
fi; \
else \
echo "❌ ERROR: PocketBase binary not found!" && \
echo "📁 Expected location: $sharedServerDir/pocketbase" && \
echo "📁 Available files in shared directory:" && \
ls -la "$sharedServerDir/" 2>/dev/null || echo "Cannot access shared directory" && \
echo "" && \
echo "💡 Please ensure PocketBase binary is configured in Student Scanner settings"; \
fi
            """.trimIndent()
            
            // Check Android version and use appropriate method
            val androidVersion = android.os.Build.VERSION.SDK_INT
            android.util.Log.d("AutoStartService", "📋 Android API Level: $androidVersion")
            
            if (androidVersion >= 35) { // Android 15 (API 35) and above
                android.util.Log.d("AutoStartService", "📱 Android 15+ detected - using manual execution method")
                return executeManualTermuxStartup(startupCommand)
            } else {
                android.util.Log.d("AutoStartService", "📱 Android 14 and below - using RUN_COMMAND method")
                return executeAutomaticTermuxStartup(startupCommand)
            }
            
        } catch (e: Exception) {
            android.util.Log.e("AutoStartService", "❌ Failed to execute server startup: ${e.message}")
            false
        }
    }
    
    private fun executeAutomaticTermuxStartup(startupCommand: String): Boolean {
        return try {
            // Android 14 and below - use RUN_COMMAND (works reliably)
            val termuxIntent = Intent().apply {
                setClassName("com.termux", "com.termux.app.TermuxActivity")
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            android.util.Log.d("AutoStartService", "📱 Opening Termux (Android 14 and below)...")
            startActivity(termuxIntent)
            
            // Wait a moment for Termux to open, then send the command
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val runCommandIntent = Intent().apply {
                        setClassName("com.termux", "com.termux.app.RunCommandService")
                        action = "com.termux.RUN_COMMAND"
                        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", startupCommand))
                        putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                        putExtra("com.termux.RUN_COMMAND_BACKGROUND", false) // Show output
                        putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "1") // Switch to new session
                    }
                    
                    android.util.Log.d("AutoStartService", "📋 Sending RUN_COMMAND to Termux...")
                    startService(runCommandIntent)
                    
                } catch (e: Exception) {
                    android.util.Log.e("AutoStartService", "❌ Failed to send RUN_COMMAND: ${e.message}")
                    // Fallback to background execution
                    executeBackgroundCommand(startupCommand)
                }
            }, 2000) // Wait 2 seconds for Termux to open
            
            android.util.Log.d("AutoStartService", "✅ Automatic Termux startup initiated")
            true
            
        } catch (e: Exception) {
            android.util.Log.e("AutoStartService", "❌ Failed automatic Termux startup: ${e.message}")
            false
        }
    }
    
    private fun executeManualTermuxStartup(startupCommand: String): Boolean {
        return try {
            // Android 15+ - create script file and open Termux for manual execution
            val scriptPath = createStartupScript(startupCommand)
            
            // Open Termux
            val termuxIntent = Intent().apply {
                setClassName("com.termux", "com.termux.app.TermuxActivity")
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            android.util.Log.d("AutoStartService", "📱 Opening Termux for manual execution (Android 15+)...")
            startActivity(termuxIntent)
            
            // Show user instructions after a delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                showManualExecutionInstructions(scriptPath)
            }, 3000)
            
            android.util.Log.d("AutoStartService", "✅ Manual Termux startup prepared")
            true
            
        } catch (e: Exception) {
            android.util.Log.e("AutoStartService", "❌ Failed manual Termux startup: ${e.message}")
            false
        }
    }
    
    private fun createStartupScript(command: String): String {
        return try {
            val scriptDir = File("/storage/emulated/0/StudentScanner-Server")
            if (!scriptDir.exists()) {
                scriptDir.mkdirs()
            }
            
            val scriptFile = File(scriptDir, "start_server.sh")
            scriptFile.writeText("#!/bin/bash\n$command")
            scriptFile.setExecutable(true)
            
            android.util.Log.d("AutoStartService", "📝 Created startup script: ${scriptFile.absolutePath}")
            scriptFile.absolutePath
            
        } catch (e: Exception) {
            android.util.Log.e("AutoStartService", "❌ Failed to create startup script: ${e.message}")
            "/storage/emulated/0/StudentScanner-Server/start_server.sh"
        }
    }
    
    private fun showManualExecutionInstructions(scriptPath: String) {
        try {
            // Create a notification with instructions
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // Create notification channel for Android 8.0+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "server_instructions",
                    "Server Instructions",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            val notification = android.app.Notification.Builder(this, "server_instructions")
                .setContentTitle("🚀 Manual Server Startup Required")
                .setContentText("Android 15: Please run the startup command manually in Termux")
                .setStyle(android.app.Notification.BigTextStyle()
                    .bigText("📱 Android 15 Detected\n\n" +
                            "Due to Android 15 restrictions, please run this command manually in Termux:\n\n" +
                            "bash $scriptPath\n\n" +
                            "Or copy and paste the startup commands from the script file."))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(1001, notification)
            android.util.Log.d("AutoStartService", "📱 Manual execution instructions shown")
            
        } catch (e: Exception) {
            android.util.Log.e("AutoStartService", "❌ Failed to show instructions: ${e.message}")
        }
    }
    
    private fun executeBackgroundCommand(command: String): Boolean {
        return try {
            android.util.Log.d("AutoStartService", "🔄 Falling back to background execution...")
            
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            
            startService(intent)
            android.util.Log.d("AutoStartService", "✅ Background command executed")
            true
            
        } catch (e: Exception) {
            android.util.Log.e("AutoStartService", "❌ Background execution failed: ${e.message}")
            false
        }
    }



    // OLD FUNCTIONS REMOVED - REPLACED BY RUN_COMMAND APPROACH
    // The following functions were replaced by executeServerStartupViaRunCommand()
    // for better Android 14 compatibility and cleaner execution without UI interruption
    
    // Removed complex functions:
    // - startPocketBaseServerAndroid14Compatible()
    // - createAndroid14CompatibleScript() 
    // - executeWithAndroid14Fallbacks()
    // - tryRUNCommandWithAndroid14Flags()
    // - tryBasicRUNCommand()
    // - tryFileBasedExecution()
    // - tryManualExecutionFallback()
    
    // All replaced by the simple executeServerStartupViaRunCommand() function above

    private fun returnToApp() {
        try {
            val intent = Intent()
            intent.setClassName("com.deped.studentscanner", "com.deped.studentscanner.MainActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: send broadcast
            sendBroadcast(Intent("com.example.project.RETURN_TO_APP"))
        }
    }

    private fun sendStatusBroadcast(message: String) {
        sendBroadcast(Intent("com.example.project.SERVER_STATUS").apply {
            putExtra("status", message)
        })
    }

    private fun sendErrorBroadcast(error: String) {
        sendBroadcast(Intent("com.example.project.AUTO_START_FAILED").apply {
            putExtra("error", error)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        startupJob?.cancel()
        scope.cancel()
    }

    companion object {
        fun startAutomatic(context: Context) {
            val intent = Intent(context, AutoStartService::class.java)
            intent.action = "AUTO_START"
            context.startService(intent)
        }
        
        fun startManual(context: Context) {
            val intent = Intent(context, AutoStartService::class.java)
            intent.action = "MANUAL_START"
            context.startService(intent)
        }
    }
} 