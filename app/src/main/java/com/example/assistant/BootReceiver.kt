package com.example.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver: used to restart foreground service or re-schedule periodic work after reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Boot completed - restarting assistant or scheduling sync")
            context?.let {
                val svc = Intent(it, AssistantForegroundService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    it.startForegroundService(svc)
                } else {
                    it.startService(svc)
                }
            }
        }
    }
}
