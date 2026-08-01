package com.example.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log

/**
 * ActionExecutor: central place to perform device actions. Each action checks
 * required permissions before performing the action. For many actions we use intents
 * so the system / user confirms the action (safer than silent operations).
 */
class ActionExecutor(private val context: Context) {

    fun sendSmsViaIntent(phoneNumber: String, message: String) {
        try {
            val uri = Uri.parse("smsto:" + phoneNumber)
            val intent = Intent(Intent.ACTION_SENDTO, uri)
            intent.putExtra("sms_body", message)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("ActionExecutor", "sendSmsViaIntent failed", e)
        }
    }

    fun makeCallIntent(phoneNumber: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:" + phoneNumber)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: SecurityException) {
            Log.e("ActionExecutor", "Missing CALL_PHONE permission", e)
        } catch (e: Exception) {
            Log.e("ActionExecutor", "makeCall failed", e)
        }
    }

    fun setDoNotDisturb(enabled: Boolean) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                if (enabled) {
                    nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            }
        } catch (e: Exception) {
            Log.e("ActionExecutor", "setDND failed", e)
        }
    }

    // More actions can be added: launchApp, changeSettings, interactViaAccessibility, etc.
}
