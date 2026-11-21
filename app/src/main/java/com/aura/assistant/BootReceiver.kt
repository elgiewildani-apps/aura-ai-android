package com.aura.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && AuraConfig.is247Active(context)) {
            val svcIntent = Intent(context, AuraForegroundService::class.java)
            ContextCompat.startForegroundService(context, svcIntent)
        }
    }
}
