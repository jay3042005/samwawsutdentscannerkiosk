package org.example.project.platform

import org.example.project.network.UpdateInfo

class AndroidUpdateManager : UpdateManager {
    
    override suspend fun checkAndPromptForUpdate(): UpdateInfo? {
        // On Android, updates would typically be handled through Google Play Store
        // or a custom update mechanism. For now, return null (no updates available)
        return null
    }
    
    override suspend fun downloadAndInstallUpdate(updateInfo: UpdateInfo, onProgress: (Float) -> Unit): Result<Boolean> {
        // On Android, automatic updates require special permissions or Play Store integration
        // For now, return failure (update not supported)
        return Result.failure(Exception("Updates not supported on Android"))
    }
    
    override fun openDownloadPage(url: String) {
        // On Android, this would open a browser to download the APK
        // For now, do nothing
    }
}

actual fun createUpdateManager(): UpdateManager {
    return AndroidUpdateManager()
} 