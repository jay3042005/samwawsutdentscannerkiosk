package org.example.project.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
actual fun StudentPhotoCard(
    studentId: String,
    modifier: Modifier,
    imageLocation: String
) {
    var imageLoaded by remember(studentId, imageLocation) { mutableStateOf(false) }
    val context = LocalContext.current
    
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (imageLoaded) Color.White else Color(0xFF1E3A8A).copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            // Try to load image from various possible locations
            val imagePaths = listOf(
                // Android internal storage paths
                "${context.filesDir}/$imageLocation/${studentId}.jpg",
                "${context.filesDir}/$imageLocation/${studentId}.png",
                "${context.getExternalFilesDir(null)}/$imageLocation/${studentId}.jpg", 
                "${context.getExternalFilesDir(null)}/$imageLocation/${studentId}.png",
                // Asset-based paths (if images are bundled with app)
                "file:///android_asset/$imageLocation/${studentId}.jpg",
                "file:///android_asset/$imageLocation/${studentId}.png"
            )
            
            var currentImagePath by remember(studentId, imageLocation) { mutableStateOf(imagePaths.firstOrNull()) }
            
            if (currentImagePath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(currentImagePath)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Student Photo",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                    onSuccess = { imageLoaded = true },
                    onError = { 
                        // Try next path if current one fails
                        val currentIndex = imagePaths.indexOf(currentImagePath)
                        if (currentIndex < imagePaths.size - 1) {
                            currentImagePath = imagePaths[currentIndex + 1]
                        } else {
                            imageLoaded = false
                        }
                    }
                )
            }
            
            // Fallback placeholder if no image loaded
            if (!imageLoaded) {
                Text(
                    text = "👤",
                    fontSize = 80.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
} 