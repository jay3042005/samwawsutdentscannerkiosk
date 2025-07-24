package org.example.project.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

@Composable
actual fun StudentPhotoLoader(
    studentId: String,
    modifier: Modifier,
    photoFolderPath: String
) {
    val context = LocalContext.current
    var photoLoaded by remember { mutableStateOf(false) }
    var photoError by remember { mutableStateOf(false) }
    
    // Get photo folder path from settings (prefer settings over passed parameter)
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val configuredPhotoPath = prefs.getString("photoFolderPath", photoFolderPath) ?: photoFolderPath
    
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (photoLoaded) Color.White else Color(0xFF1E3A8A).copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (configuredPhotoPath.isNotEmpty()) {
                // Try to load photo from configured folder
                val photoFile = File(configuredPhotoPath, "$studentId.jpg")
                val photoFilePng = File(configuredPhotoPath, "$studentId.png")
                
                val imageFile = if (photoFile.exists()) photoFile else if (photoFilePng.exists()) photoFilePng else null
                
                if (imageFile != null && imageFile.exists()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageFile)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Student Photo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                        onSuccess = { photoLoaded = true },
                        onError = { 
                            photoLoaded = false
                            photoError = true
                        }
                    )
                } else {
                    // Show placeholder with student initials
                    StudentPhotoPlaceholder(studentId)
                }
            } else {
                // No photo folder configured, show placeholder
                StudentPhotoPlaceholder(studentId)
            }
        }
    }
}

@Composable
private fun StudentPhotoPlaceholder(studentId: String) {
    // Generate initials from student ID or use default
    val initials = when {
        studentId.length >= 2 -> studentId.take(2).uppercase()
        studentId.isNotEmpty() -> studentId.uppercase()
        else -> "ST"
    }
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = initials,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E3A8A),
            textAlign = TextAlign.Center
        )
    }
}

// Utility function to check if photo exists
fun checkStudentPhotoExists(context: Context, studentId: String): Boolean {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val photoFolderPath = prefs.getString("photoFolderPath", "") ?: ""
    
    if (photoFolderPath.isEmpty()) return false
    
    val photoFile = File(photoFolderPath, "$studentId.jpg")
    val photoFilePng = File(photoFolderPath, "$studentId.png")
    
    return photoFile.exists() || photoFilePng.exists()
}

// Utility function to get photo file
fun getStudentPhotoFile(context: Context, studentId: String): File? {
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val photoFolderPath = prefs.getString("photoFolderPath", "") ?: ""
    
    if (photoFolderPath.isEmpty()) return null
    
    val photoFile = File(photoFolderPath, "$studentId.jpg")
    val photoFilePng = File(photoFolderPath, "$studentId.png")
    
    return when {
        photoFile.exists() -> photoFile
        photoFilePng.exists() -> photoFilePng
        else -> null
    }
} 