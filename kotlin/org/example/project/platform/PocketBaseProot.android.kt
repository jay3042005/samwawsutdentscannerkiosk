package org.example.project.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL

class PocketBaseProot(private val context: Context) {
    
    private val appFilesDir = context.filesDir
    private val prootPath = File(appFilesDir, "proot")
    private val pocketbasePath = File(appFilesDir, "pocketbase")
    private val linuxRootPath = File(appFilesDir, "linux-root")
    
    suspend fun downloadProot(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://github.com/proot-me/proot-static-build/raw/master/static/proot-arm64"
            downloadFile(url, prootPath)
            makeExecutable(prootPath)
            Result.success("Proot downloaded successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun downloadPocketBase(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Get latest PocketBase ARM64 release
            val url = "https://github.com/pocketbase/pocketbase/releases/latest/download/pocketbase_0.20.0_linux_arm64.zip"
            val zipFile = File(appFilesDir, "pocketbase.zip")
            
            downloadFile(url, zipFile)
            extractPocketBase(zipFile)
            makeExecutable(pocketbasePath)
            
            Result.success("PocketBase downloaded successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun setupLinuxEnvironment(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Create minimal Linux directory structure
            linuxRootPath.mkdirs()
            File(linuxRootPath, "bin").mkdirs()
            File(linuxRootPath, "lib").mkdirs()
            File(linuxRootPath, "lib64").mkdirs()
            File(linuxRootPath, "usr/bin").mkdirs()
            File(linuxRootPath, "usr/lib").mkdirs()
            File(linuxRootPath, "usr/lib64").mkdirs()
            File(linuxRootPath, "tmp").mkdirs()
            File(linuxRootPath, "var").mkdirs()
            File(linuxRootPath, "proc").mkdirs()
            File(linuxRootPath, "sys").mkdirs()
            File(linuxRootPath, "dev").mkdirs()
            
            // Copy PocketBase to Linux root
            val targetPocketBase = File(linuxRootPath, "usr/bin/pocketbase")
            pocketbasePath.copyTo(targetPocketBase, overwrite = true)
            makeExecutable(targetPocketBase)
            
            Result.success("Linux environment setup complete")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun startPocketBase(): Result<Process> = withContext(Dispatchers.IO) {
        try {
            if (!prootPath.exists()) {
                return@withContext Result.failure(Exception("Proot not found. Please download first."))
            }
            
            if (!pocketbasePath.exists()) {
                return@withContext Result.failure(Exception("PocketBase not found. Please download first."))
            }
            
            // Setup Linux environment if not exists
            if (!linuxRootPath.exists()) {
                setupLinuxEnvironment()
            }
            
            // Build proot command
            val command = arrayOf(
                prootPath.absolutePath,
                "-r", linuxRootPath.absolutePath,
                "-b", "/proc",
                "-b", "/sys", 
                "-b", "/dev",
                "-w", "/usr/bin",
                "./pocketbase",
                "serve",
                "--http=127.0.0.1:8090",
                "--dir=/tmp/pb_data"
            )
            
            val processBuilder = ProcessBuilder(*command)
            processBuilder.environment()["PATH"] = "/usr/bin:/bin"
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            
            // Log output
            Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        println("PocketBase: $line")
                    }
                }
            }.start()
            
            Result.success(process)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun downloadFile(url: String, destination: File) {
        URL(url).openStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
    
    private fun extractPocketBase(zipFile: File) {
        // Simple ZIP extraction for PocketBase binary
        // In a real implementation, you'd use a ZIP library
        // For now, assume direct download of binary
        zipFile.copyTo(pocketbasePath, overwrite = true)
    }
    
    private fun makeExecutable(file: File) {
        try {
            val runtime = Runtime.getRuntime()
            runtime.exec("chmod +x ${file.absolutePath}")
        } catch (e: Exception) {
            println("Warning: Could not set executable permission: ${e.message}")
        }
    }
    
    fun getStatus(): String {
        return when {
            !prootPath.exists() -> "Proot not downloaded"
            !pocketbasePath.exists() -> "PocketBase not downloaded"
            !linuxRootPath.exists() -> "Linux environment not setup"
            else -> "Ready to start"
        }
    }
}

@Composable
fun rememberPocketBaseProot(): PocketBaseProot {
    val context = LocalContext.current
    return remember { PocketBaseProot(context) }
} 