package org.example.project.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class AndroidFilePicker(
    private val onFileSelected: (String) -> Unit
) : FilePicker {
    
    override fun pickCsvFile() {
        // On Android, file picking requires Activity context and permissions
        // For now, provide a placeholder that could be extended with proper Android file picker
        onFileSelected("")
    }
    
    override fun pickFile(fileExtensions: List<String>, title: String) {
        // On Android, file picking requires Activity context and permissions
        // For now, provide a placeholder that could be extended with proper Android file picker
        onFileSelected("")
    }
}

class AndroidFolderPicker(
    private val onFolderSelected: (String) -> Unit
) : FolderPicker {
    
    override fun pickFolder() {
        // On Android, folder picking requires Activity context and permissions
        // For now, provide a placeholder that could be extended with proper Android folder picker
        onFolderSelected("")
    }
}

@Composable
actual fun rememberFilePicker(onFileSelected: (String) -> Unit): FilePicker {
    return remember { AndroidFilePicker(onFileSelected) }
}

@Composable
actual fun rememberFolderPicker(onFolderSelected: (String) -> Unit): FolderPicker {
    return remember { AndroidFolderPicker(onFolderSelected) }
} 