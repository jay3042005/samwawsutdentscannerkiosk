package org.example.project.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager

@Composable
actual fun AndroidQRScanner(
    onQRScanned: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    var isScanning by remember { mutableStateOf(true) }
    var lastScanTime by remember { mutableStateOf(0L) }
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Color.Black
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isScanning) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val executor = ContextCompat.getMainExecutor(ctx)
                        
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            
                            val barcodeScanner = BarcodeScanning.getClient()
                            
                            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                val currentTime = System.currentTimeMillis()
                                
                                // Prevent duplicate scans within 2 seconds
                                if (currentTime - lastScanTime > 2000) {
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val image = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees
                                        )
                                        
                                        barcodeScanner.process(image)
                                            .addOnSuccessListener { barcodes ->
                                                try {
                                                    println("[QR SCAN] Processing ${barcodes.size} barcodes detected")
                                                    for (barcode in barcodes) {
                                                        // Handle all barcode types, not just TEXT and URL
                                                        val qrData = barcode.displayValue 
                                                            ?: barcode.rawValue?.toString() 
                                                            ?: ""
                                                        
                                                        // Log for debugging
                                                        println("[QR SCAN] Barcode detected - Type: ${barcode.valueType}, DisplayValue: '${barcode.displayValue}', RawValue: '${barcode.rawValue}', Final: '$qrData'")
                                                        
                                                        // Only process non-empty QR codes
                                                        if (qrData.isNotBlank() && qrData.trim().isNotEmpty()) {
                                                            lastScanTime = currentTime
                                                            val cleanedData = qrData.trim()
                                                            println("[QR SCAN] Processing QR data: '$cleanedData'")
                                                            onQRScanned(cleanedData)
                                                        } else {
                                                            println("[QR SCAN] Empty QR code detected, ignoring")
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    println("[QR SCAN] Error processing barcodes: ${e.message}")
                                                }
                                            }
                                            .addOnFailureListener { exception ->
                                                println("[QR SCAN] Barcode scanning failed: ${exception.message}")
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                } else {
                                    imageProxy.close()
                                }
                            }
                            
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (exc: Exception) {
                                // Handle camera binding errors
                            }
                        }, executor)
                        
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Scanning overlay
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Scanning...",
                            modifier = Modifier.padding(8.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                    }
                }
            } else {
                // Camera permission or error state
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "📷",
                        fontSize = 48.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Camera Not Available",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
} 

@Composable
fun RequestBluetoothPermissions(
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit = {}
) {
    val context = LocalContext.current
    val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var permissionsRequested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.all { it.value }) {
            onPermissionsGranted()
        } else {
            onPermissionsDenied()
        }
    }
    LaunchedEffect(Unit) {
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted && !permissionsRequested) {
            permissionsRequested = true
            launcher.launch(permissions.toTypedArray())
        } else if (allGranted) {
            onPermissionsGranted()
        }
    }
}

@Composable
fun AndroidQRScannerWithPermissions(
    onQRScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var permissionsGranted by remember { mutableStateOf(false) }
    var permissionsDenied by remember { mutableStateOf(false) }
    RequestBluetoothPermissions(
        onPermissionsGranted = { permissionsGranted = true },
        onPermissionsDenied = { permissionsDenied = true }
    )
    when {
        permissionsGranted -> AndroidQRScanner(onQRScanned = onQRScanned, modifier = modifier)
        permissionsDenied -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text("Bluetooth permissions are required to scan.")
        }
        else -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text("Requesting Bluetooth permissions...")
        }
    }
} 