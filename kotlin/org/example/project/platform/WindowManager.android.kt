package org.example.project.platform

class AndroidWindowManager : WindowManager {
    
    override fun toggleFullscreen() {
        // On Android, fullscreen is typically handled by Activity
        // This would require Activity context to implement properly
    }
    
    override fun setFullscreen(enabled: Boolean) {
        // On Android, fullscreen is typically handled by Activity
        // This would require Activity context to implement properly
    }
    
    override fun setupAutoStartup(enabled: Boolean) {
        // Android doesn't support traditional auto-startup
        // Apps can request to be exempt from battery optimization instead
    }
    
    override fun isFullscreenSupported(): Boolean {
        // Android supports fullscreen mode
        return true
    }
    
    override fun isAutoStartupSupported(): Boolean {
        // Android doesn't support traditional auto-startup like desktop platforms
        return false
    }
}

actual fun getWindowManager(): WindowManager {
    return AndroidWindowManager()
} 