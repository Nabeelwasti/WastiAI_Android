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

/** Boot recovery entry point. Durable recovery uses WorkManager; receiver work uses goAsync(). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || context == null) return

        val appContext = context.applicationContext
        Log.i("BootReceiver", "Device boot completed — scheduling Wasti OS recovery.")
        WastiServiceLocator.init(appContext)

        val scheduled = ProactiveReconciliationWorker.schedulePeriodicReconciliation(appContext)
        if (!scheduled) Log.w("BootReceiver", "WorkManager reconciliation was not scheduled; recovery remains deferred.")

        // Robolectric's host BroadcastReceiver may not provide a real PendingResult.
        // Production Android does; null-safe cleanup keeps the lifecycle contract truthful in both.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val engine = WastiProactiveAutonomousEngine.getInstance(appContext)
                val recoveredCount = engine.recoverOnBootOrProcessStart()
                com.example.data.device.AccessibilityRecoveryCoordinator.resumeFromCheckpoint(appContext)
                Log.i("BootReceiver", "Boot recovery finished: $recoveredCount tasks reconciled.")
            } catch (e: Exception) {
                Log.e("BootReceiver", "Boot task recovery failed: ${e.message}", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
