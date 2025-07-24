package com.deped.studentscanner.utils

import android.content.Context
import android.telephony.SmsManager
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.*
import org.example.project.data.SchoolSettings
import java.text.SimpleDateFormat
import java.util.*

class SmsManager(private val context: Context) {
    private lateinit var smsManager: android.telephony.SmsManager
    private var lastSMSData: String = "" // Track last SMS to prevent duplicates
    
    init {
        smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(android.telephony.SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            android.telephony.SmsManager.getDefault()
        }
    }
    
    fun processStudentScanWithSMS(studentId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("[SMS] 📱 Processing SMS for student: $studentId")
                // Get school settings from PocketBase (mocked for now)
                val schoolSettings = getSchoolSettingsFromPocketBase()
                if (schoolSettings == null) {
                    println("[SMS] ❌ Could not get school settings")
                    return@launch
                }
                // Determine action based on time
                val action = determineStudentAction(schoolSettings)
                println("[SMS] 🕐 Action determined: $action")
                // Send SMS notification
                sendSMSNotification(studentId, action, schoolSettings)
            } catch (e: Exception) {
                println("[SMS] ❌ Error processing SMS: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    private fun getSchoolSettingsFromPocketBase(): SchoolSettings? {
        return try {
            // This would typically fetch from PocketBase API
            // For now, return a default settings object
            SchoolSettings(
                school_entry_start_time = "07:00",
                school_entry_end_time = "08:00",
                school_exit_start_time = "15:00",
                school_exit_end_time = "17:00"
            )
        } catch (e: Exception) {
            println("[SMS] ❌ Error getting school settings: ${e.message}")
            null
        }
    }
    
    private fun determineStudentAction(schoolSettings: SchoolSettings): String {
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val currentMinutes = parseTimeToMinutes(currentTime)
        val entryStart = parseTimeToMinutes(schoolSettings.school_entry_start_time)
        val entryEnd = parseTimeToMinutes(schoolSettings.school_entry_end_time)
        val exitStart = parseTimeToMinutes(schoolSettings.school_exit_start_time)
        val exitEnd = parseTimeToMinutes(schoolSettings.school_exit_end_time)
        return when {
            isTimeWithinRange(currentMinutes, entryStart, entryEnd) -> "entered"
            isTimeWithinRange(currentMinutes, exitStart, exitEnd) -> "exited"
            else -> "visited"
        }
    }
    
    private fun isTimeWithinRange(current: Int, start: Int, end: Int): Boolean {
        return current in start..end
    }
    
    private fun parseTimeToMinutes(time: String): Int {
        val parts = time.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }
    
    private fun sendSMSNotification(studentId: String, action: String, schoolSettings: SchoolSettings) {
        try {
            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val message = "Your child Student $studentId has $action school at $currentTime."
            // For demo purposes, we'll just log the message
            // In a real implementation, you would send via Twilio or local SMS
            println("[SMS] 📤 Message: $message")
            // Update last SMS data to prevent duplicates
            lastSMSData = "$studentId-$action-$currentTime"
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(context, "📱 SMS sent: $message", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            println("[SMS] ❌ Error sending SMS: ${e.message}")
            e.printStackTrace()
        }
    }
} 