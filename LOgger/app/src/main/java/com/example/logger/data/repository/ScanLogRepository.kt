package com.arjay.logger.data.repository

import com.arjay.logger.data.api.PocketBaseApi
import com.arjay.logger.data.model.ScanLog
import com.arjay.logger.data.model.ScanLogRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class ScanLogRepository(
    private val api: PocketBaseApi,
    private val context: Context,
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
) {
    
    suspend fun fetchAndSaveScanLogs(): Result<File> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ScanLogRepository", "Starting to fetch scan logs from PocketBase...")
            
            // Fetch scan logs from PocketBase
            val response = api.getScanLogs()
            android.util.Log.d("ScanLogRepository", "Received response with ${response.items.size} items")
            
            // Transform to minimized format
            val minimizedLogs = response.items.map { raw ->
                ScanLog(
                    id = raw.id,
                    studentId = raw.studentId,
                    studentName = raw.studentName,
                    entryExitStatus = raw.entryExitStatus,
                    timestamp = raw.timestamp,
                    grade = raw.gradeLevel?.toString() ?: "Unknown",
                    section = raw.section ?: "Unknown"
                )
            }
            android.util.Log.d("ScanLogRepository", "Transformed ${minimizedLogs.size} logs")
            
            // Save to JSON file
            val jsonFile = saveToJsonFile(minimizedLogs)
            android.util.Log.d("ScanLogRepository", "Saved logs to: ${jsonFile.absolutePath}")
            
            // Optionally create gzipped version
            val gzipFile = createGzipFile(jsonFile)
            android.util.Log.d("ScanLogRepository", "Created gzip file: ${gzipFile.absolutePath}")
            
            Result.success(jsonFile)
        } catch (e: Exception) {
            android.util.Log.e("ScanLogRepository", "Error fetching scan logs: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }
    
    private fun saveToJsonFile(logs: List<ScanLog>): File {
        val file = File(getAppFilesDir(), "scan_logs.json")
        file.parentFile?.mkdirs()
        
        val jsonString = gson.toJson(logs)
        file.writeText(jsonString)
        
        return file
    }
    
    private fun createGzipFile(jsonFile: File): File {
        val gzipFile = File(getAppFilesDir(), "scan_logs.gz")
        
        jsonFile.inputStream().use { input ->
            GZIPOutputStream(FileOutputStream(gzipFile)).use { gzip ->
                input.copyTo(gzip)
            }
        }
        
        return gzipFile
    }
    
    private fun getAppFilesDir(): File {
        // Use the application's files directory
        return context.filesDir
    }
    
    fun getLogFile(): File {
        return File(getAppFilesDir(), "scan_logs.json")
    }
    
    fun getGzipFile(): File {
        return File(getAppFilesDir(), "scan_logs.gz")
    }
} 