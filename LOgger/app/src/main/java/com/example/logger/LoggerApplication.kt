package com.arjay.logger

import android.app.Application

class LoggerApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // WorkManager is auto-initialized in newer versions
        // No manual initialization needed
    }
} 