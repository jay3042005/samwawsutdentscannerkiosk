package com.deped.studentscanner.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

object QRCodeGenerator {
    
    /**
     * Generates a QR code bitmap from the given text
     * @param text The text to encode in the QR code
     * @param width The width of the QR code bitmap
     * @param height The height of the QR code bitmap
     * @return Bitmap containing the QR code, or null if generation fails
     */
    fun generateQRCode(text: String, width: Int = 512, height: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, 1)
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
            }
            
            val bitMatrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, width, height, hints)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            
            bitmap
        } catch (e: WriterException) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Generates a verification token for QR code authentication
     * @param deviceId The device identifier
     * @param timestamp Current timestamp
     * @return A verification token string
     */
    fun generateVerificationToken(deviceId: String, timestamp: Long): String {
        // Simple token format: DEVICE_ID:TIMESTAMP:HASH
        val data = "$deviceId:$timestamp"
        val hash = data.hashCode().toString(16)
        val token = "$data:$hash"
        
        println("[QR_GENERATOR] 🔐 Generated token: $token")
        println("[QR_GENERATOR] 🔍 Token details - DeviceID: $deviceId, Timestamp: $timestamp, Hash: $hash")
        
        return token
    }
    
    /**
     * Validates a verification token
     * @param token The token to validate
     * @param maxAgeMs Maximum age of the token in milliseconds (default: 5 minutes)
     * @return True if the token is valid, false otherwise
     */
    fun validateVerificationToken(token: String, maxAgeMs: Long = 300000): Boolean {
        return try {
            val parts = token.split(":")
            if (parts.size != 3) return false
            
            val deviceId = parts[0]
            val timestamp = parts[1].toLong()
            val hash = parts[2]
            
            // Check if token is not too old
            val currentTime = System.currentTimeMillis()
            if (currentTime - timestamp > maxAgeMs) return false
            
            // Verify hash
            val expectedHash = "$deviceId:$timestamp".hashCode().toString(16)
            hash == expectedHash
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Extracts device ID from a verification token
     * @param token The verification token
     * @return Device ID if valid, null otherwise
     */
    fun extractDeviceId(token: String): String? {
        return try {
            val parts = token.split(":")
            if (parts.size >= 1) parts[0] else null
        } catch (e: Exception) {
            null
        }
    }
}