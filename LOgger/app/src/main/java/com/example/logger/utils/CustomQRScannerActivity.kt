package com.arjay.logger.utils

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.arjay.logger.R
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

class CustomQRScannerActivity : AppCompatActivity(), SurfaceHolder.Callback, android.hardware.Camera.PreviewCallback {
    companion object {
        private const val TAG = "CustomQRScannerActivity"
        private const val CAMERA_PERMISSION_REQUEST = 100
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var scanBoxOverlay: View
    private lateinit var closeButton: ImageView
    private lateinit var instructionText: TextView
    private var camera: android.hardware.Camera? = null
    private var isScanning = false
    private var isProcessingFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_qr_scanner)

        // Force portrait mode
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        initializeViews()
        checkCameraPermission()
    }

    private fun initializeViews() {
        surfaceView = findViewById(R.id.surfaceView)
        scanBoxOverlay = findViewById(R.id.scanBoxOverlay)
        closeButton = findViewById(R.id.closeButton)
        instructionText = findViewById(R.id.instructionText)

        surfaceView.holder.addCallback(this)

        closeButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        } else {
            Log.d(TAG, "Camera permission granted, waiting for surface")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission granted after request, waiting for surface")
            } else {
                Log.e(TAG, "Camera permission denied")
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun startCamera() {
        try {
            Log.d(TAG, "Starting camera...")

            // Check if camera permission is granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Camera permission not granted")
                return
            }

            // Release any existing camera
            camera?.release()

            // Open camera
            camera = android.hardware.Camera.open()
            Log.d(TAG, "Camera opened successfully")

            // Configure camera parameters
            val parameters = camera?.parameters
            parameters?.let { params ->
                // Set focus mode to continuous if available
                val supportedFocusModes = params.supportedFocusModes
                when {
                    supportedFocusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) -> {
                        params.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                    }
                    supportedFocusModes.contains(android.hardware.Camera.Parameters.FOCUS_MODE_AUTO) -> {
                        params.focusMode = android.hardware.Camera.Parameters.FOCUS_MODE_AUTO
                    }
                }

                // Set preview size
                val supportedSizes = params.supportedPreviewSizes
                val optimalSize = getOptimalPreviewSize(supportedSizes, surfaceView.width, surfaceView.height)
                optimalSize?.let {
                    params.setPreviewSize(it.width, it.height)
                    Log.d(TAG, "Preview size set to: ${it.width}x${it.height}")
                }

                camera?.parameters = params
            }

            // Set display orientation for portrait mode
            camera?.setDisplayOrientation(90)

            // Set preview display
            camera?.setPreviewDisplay(surfaceView.holder)
            Log.d(TAG, "Preview display set")

            // Set preview callback for QR scanning
            camera?.setPreviewCallback(this)

            // Start preview
            camera?.startPreview()
            Log.d(TAG, "Camera preview started")

            isScanning = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting camera: ${e.message}", e)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun getOptimalPreviewSize(sizes: List<android.hardware.Camera.Size>, w: Int, h: Int): android.hardware.Camera.Size? {
        val targetRatio = h.toDouble() / w
        var optimalSize: android.hardware.Camera.Size? = null
        var minDiff = Double.MAX_VALUE

        for (size in sizes) {
            val ratio = size.width.toDouble() / size.height
            if (Math.abs(ratio - targetRatio) > 0.2) continue
            if (Math.abs(size.height - h) < minDiff) {
                optimalSize = size
                minDiff = Math.abs(size.height - h).toDouble()
            }
        }

        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE
            for (size in sizes) {
                if (Math.abs(size.height - h) < minDiff) {
                    optimalSize = size
                    minDiff = Math.abs(size.height - h).toDouble()
                }
            }
        }

        return optimalSize
    }

    override fun onPreviewFrame(data: ByteArray?, camera: android.hardware.Camera?) {
        if (!isScanning || isProcessingFrame || data == null) return
        isProcessingFrame = true
        try {
            val parameters = camera?.parameters
            val width = parameters?.previewSize?.width ?: return
            val height = parameters.previewSize.height

            // If in portrait, swap width/height
            val rotatedData: ByteArray
            val previewWidth: Int
            val previewHeight: Int
            if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                rotatedData = rotateYUV420Degree90(data, width, height)
                previewWidth = height
                previewHeight = width
            } else {
                rotatedData = data
                previewWidth = width
                previewHeight = height
            }

            val source = PlanarYUVLuminanceSource(
                rotatedData,
                previewWidth,
                previewHeight,
                0,
                0,
                previewWidth,
                previewHeight,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = QRCodeReader()
            val result: Result? = try {
                reader.decode(bitmap)
            } catch (e: Exception) {
                null
            }
            if (result != null) {
                isScanning = false
                runOnUiThread {
                    handleQRResult(result.text)
                }
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            isProcessingFrame = false
        }
    }

    private fun handleQRResult(result: String) {
        Log.d(TAG, "QR Code scanned: $result")
        isScanning = false
        camera?.setPreviewCallback(null)
        val intent = android.content.Intent()
        intent.putExtra("SCAN_RESULT", result)
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        // Surface is created, now we can start the camera if permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
        // Surface changed, restart camera preview if needed
        if (camera != null) {
            try {
                camera?.stopPreview()
                camera?.setPreviewDisplay(holder)
                camera?.startPreview()
                Log.d(TAG, "Camera preview restarted after surface change")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting camera preview: ${e.message}")
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        // Surface destroyed, stop camera
        isScanning = false
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
            camera = null
            Log.d(TAG, "Camera released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing camera: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        isScanning = false
        camera?.setPreviewCallback(null)
        camera?.stopPreview()
        camera?.release()
        camera = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isScanning = false
        camera?.setPreviewCallback(null)
        camera?.release()
        camera = null
    }

    // Helper to rotate YUV data for portrait mode
    private fun rotateYUV420Degree90(data: ByteArray, imageWidth: Int, imageHeight: Int): ByteArray {
        val yuv = ByteArray(data.size)
        var i = 0
        for (x in 0 until imageWidth) {
            for (y in imageHeight - 1 downTo 0) {
                yuv[i++] = data[y * imageWidth + x]
            }
        }
        val size = imageWidth * imageHeight
        i = size * 3 / 2 - 1
        var x = imageWidth - 1
        while (x > 0) {
            for (y in 0 until imageHeight / 2) {
                yuv[i--] = data[size + y * imageWidth + x]
                yuv[i--] = data[size + y * imageWidth + (x - 1)]
            }
            x -= 2
        }
        return yuv
    }
} 