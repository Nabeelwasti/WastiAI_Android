package com.example.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.di.WastiServiceLocator
import com.example.data.proactive.WastiProactiveAutonomousEngine
import com.example.data.worker.ProactiveReconciliationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot recovery entry point for Wasti AI OS.
 *
 * Boot receivers must not blindly start a foreground service. Android imposes background-start
 * and foreground-service-type restrictions, especially on recent releases. Durable work is
 * scheduled through WorkManager; reconciliation runs under goAsync() so the receiver lifecycle
 * remains valid until the recovery coroutine finishes.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || context == null) return

        val appContext = context.applicationContext
        Log.i("BootReceiver", "Device boot completed — scheduling Wasti OS recovery.")
        WastiServiceLocator.init(appContext)

        val scheduled = ProactiveReconciliationWorker.schedulePeriodicReconciliation(appContext)
        if (!scheduled) {
            Log.w("BootReceiver", "WorkManager reconciliation was not scheduled; recovery remains deferred.")
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val engine = WastiProactiveAutonomousEngine.getInstance(appContext)
                val recoveredCount = engine.recoverOnBootOrProcessStart()
                Log.i("BootReceiver", "Boot recovery finished: $recoveredCount tasks reconciled.")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Boot task recovery failed: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
