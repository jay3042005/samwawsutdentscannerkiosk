package com.arjay.logger.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object FileUtils {
    fun getLogsDirectory(context: Context): File {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "logs")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    fun getLogFiles(context: Context): Array<File> {
        val logDir = getLogsDirectory(context)
        return logDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json")
        } ?: emptyArray()
    }

    fun readLogFile(file: File): String {
        return file.readText()
    }

    fun writeLogToFile(context: Context, fileName: String, content: String) {
        val logFile = File(getLogsDirectory(context), fileName)
        FileWriter(logFile, false).use { writer ->
            writer.write(content)
        }
    }

    fun getFormattedDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
    }

    fun getExportFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        return "scan_logs_${timestamp}.csv"
    }
}
