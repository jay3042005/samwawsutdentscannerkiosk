package com.arjay.logger.utils

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.arjay.logger.utils.CustomQRScannerActivity

class QRCodeScanner(private val activity: Activity) {
    
    companion object {
        private const val TAG = "QRCodeScanner"
        private const val QR_SCAN_REQUEST_CODE = 1001
    }
    
    fun startQRCodeScan() {
        Log.d(TAG, "Starting custom QR code scan")
        val intent = Intent(activity, CustomQRScannerActivity::class.java)
        activity.startActivityForResult(intent, QR_SCAN_REQUEST_CODE)
    }
    
    fun handleScanResult(requestCode: Int, resultCode: Int, data: Intent?): String? {
        if (requestCode == QR_SCAN_REQUEST_CODE) {
            return if (resultCode == Activity.RESULT_OK && data != null) {
                val result = data.getStringExtra("SCAN_RESULT")
                Log.d(TAG, "QR Code scanned successfully: $result")
                result
            } else {
                Log.d(TAG, "QR Code scan cancelled or failed")
                null
            }
        }
        return null
    }
    
    fun validateStudentScannerToken(token: String): Boolean {
        // Updated validation to handle both legacy and new UUID-based tokens from Student Scanner
        return try {
            Log.d(TAG, "🔍 Validating QR token: $token")
            
            // Check if token is UUID format (new format from Student Scanner)
            if (isValidUUID(token)) {
                Log.d(TAG, "✅ Valid UUID token format detected")
                return true
            }
            
            // Handle legacy format: "DEVICE_ID:TIMESTAMP:HASH"
            // Split by last two colons to get deviceId, timestamp, and hash
            val lastColonIndex = token.lastIndexOf(':')
            val secondLastColonIndex = token.lastIndexOf(':', lastColonIndex - 1)
            
            if (lastColonIndex == -1 || secondLastColonIndex == -1) {
                Log.e(TAG, "❌ Invalid token format: not UUID and not enough colons for legacy format")
                return false
            }
            
            val deviceId = token.substring(0, secondLastColonIndex)
            val timestamp = token.substring(secondLastColonIndex + 1, lastColonIndex).toLong()
            val hash = token.substring(lastColonIndex + 1)
            
            Log.d(TAG, "🔍 Legacy token parts - DeviceID: $deviceId, Timestamp: $timestamp, Hash: $hash")
            
            // Basic validation checks
            if (deviceId.isBlank() || hash.isBlank()) {
                Log.e(TAG, "❌ Empty deviceId or hash")
                return false
            }
            
            // Check if timestamp is reasonable (not too old, not in the future)
            val currentTime = System.currentTimeMillis()
            val maxAgeMs = 5 * 60 * 1000 // 5 minutes
            val timeDifference = currentTime - timestamp
            
            Log.d(TAG, "🔍 Time validation - Current: $currentTime, Token: $timestamp, Difference: ${timeDifference}ms, MaxAge: ${maxAgeMs}ms")
            
            if (timeDifference > maxAgeMs) {
                Log.e(TAG, "❌ Token too old: ${timeDifference}ms > ${maxAgeMs}ms")
                return false
            }
            
            if (timestamp > currentTime + 60000) {
                Log.e(TAG, "❌ Token timestamp in the future")
                return false
            }
            
            // Verify hash (simple validation)
            val expectedHash = "$deviceId:$timestamp".hashCode().toString(16)
            val hashValid = hash == expectedHash
            
            Log.d(TAG, "🔍 Hash validation - Expected: $expectedHash, Received: $hash, Valid: $hashValid")
            
            if (hashValid) {
                Log.d(TAG, "✅ Legacy QR token validation successful")
            } else {
                Log.e(TAG, "❌ Hash validation failed")
            }
            
            hashValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error validating token: ${e.message}")
            false
        }
    }
    
    private fun isValidUUID(token: String): Boolean {
        return try {
            // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
            val uuidRegex = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".toRegex()
            val isValid = uuidRegex.matches(token)
            Log.d(TAG, "🔍 UUID validation - Token: $token, Valid: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error validating UUID: ${e.message}")
            false
        }
    }
}