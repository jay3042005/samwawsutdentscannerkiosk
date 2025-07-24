package com.arjay.logger.util

import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream

object FileTransferUtil {
    
    private const val TAG = "FileTransferUtil"
    private const val CHUNK_SIZE = 512 // BLE characteristic size limit
    
    /**
     * Converts a file to Base64 encoded chunks for BLE transfer
     */
    fun fileToBase64Chunks(file: File): List<String> {
        return try {
            val chunks = mutableListOf<String>()
            val fileInputStream = FileInputStream(file)
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                val chunk = if (bytesRead < CHUNK_SIZE) {
                    buffer.copyOf(bytesRead)
                } else {
                    buffer
                }
                
                val base64Chunk = Base64.encodeToString(chunk, Base64.DEFAULT)
                chunks.add(base64Chunk)
            }
            
            fileInputStream.close()
            Log.d(TAG, "File converted to ${chunks.size} Base64 chunks")
            chunks
            
        } catch (e: Exception) {
            Log.e(TAG, "Error converting file to Base64 chunks: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Reconstructs a file from Base64 encoded chunks
     */
    fun base64ChunksToFile(chunks: List<String>, outputFile: File): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            val fileOutputStream = outputFile.outputStream()
            
            chunks.forEach { base64Chunk ->
                val decodedBytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                fileOutputStream.write(decodedBytes)
            }
            
            fileOutputStream.close()
            Log.d(TAG, "File reconstructed from ${chunks.size} Base64 chunks")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reconstructing file from Base64 chunks: ${e.message}")
            false
        }
    }
    
    /**
     * Gets file size in human readable format
     */
    fun getFileSizeString(file: File): String {
        val bytes = file.length()
        val kilobytes = bytes / 1024.0
        val megabytes = kilobytes / 1024.0
        
        return when {
            megabytes >= 1 -> String.format("%.2f MB", megabytes)
            kilobytes >= 1 -> String.format("%.2f KB", kilobytes)
            else -> "$bytes bytes"
        }
    }
    
    /**
     * Calculates transfer time estimate based on file size
     */
    fun estimateTransferTime(fileSize: Long, transferSpeed: Long = 1000000): String {
        val seconds = fileSize / transferSpeed.toDouble()
        return when {
            seconds < 60 -> String.format("%.1f seconds", seconds)
            seconds < 3600 -> String.format("%.1f minutes", seconds / 60)
            else -> String.format("%.1f hours", seconds / 3600)
        }
    }
} 