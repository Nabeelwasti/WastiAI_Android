package com.example.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.di.WastiServiceLocator
import com.example.data.proactive.WastiProactiveAutonomousEngine
import com.example.data.worker.ProactiveReconciliationWorker
import com.example.service.WastiForegroundExecutionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Stage 16: Canonical Boot Receiver for Wasti AI OS.
 * Restarts foreground daemon, triggers durable proactive task recovery,
 * and schedules persistent reconciliation workers upon device boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device boot completed — Initializing Wasti OS persistent recovery.")
            if (context == null) return

            WastiServiceLocator.init(context)
            val scheduled = ProactiveReconciliationWorker.schedulePeriodicReconciliation(context)
            if (!scheduled) {
                Log.w("BootReceiver", "WorkManager reconciliation worker deferred; foreground daemon will handle active recovery.")
            }
            WastiForegroundExecutionService.startDaemon(context)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val engine = WastiProactiveAutonomousEngine.getInstance(context)
                    val recoveredCount = engine.recoverOnBootOrProcessStart()
                    Log.i("BootReceiver", "Boot recovery finished: $recoveredCount tasks reconciled.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error during boot task recovery: ${e.message}", e)
                }
            }
        }
    }
}
