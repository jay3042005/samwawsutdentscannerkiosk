package com.deped.studentscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.util.Log

class SMSReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Minimal implementation for default SMS app compliance
        if (intent == null) return
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            "android.provider.Telephony.SMS_DELIVER" -> {
                // Optionally log or ignore
                Log.d("SMSReceiver", "SMS received (ignored for kiosk mode)")
            }
            "android.provider.Telephony.WAP_PUSH_RECEIVED",
            "android.provider.Telephony.WAP_PUSH_DELIVER" -> {
                Log.d("SMSReceiver", "MMS/WAP Push received (ignored for kiosk mode)")
            }
        }
    }
} 