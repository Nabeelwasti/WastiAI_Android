package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import com.example.data.device.WastiDeviceController
import com.example.data.voice.provider.AndroidSpeechToTextProvider
import com.example.data.voice.provider.STTResult
import com.example.data.voice.provider.STTState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Task 27A: Wasti Floating Action Bubble (System Alert Window Service)
 * Provides an overlay floating bubble accessible over any application (Chrome, WhatsApp, etc.).
 * Draggable via WindowManager & TYPE_APPLICATION_OVERLAY.
 * Tapping triggers background SpeechRecognizer (AndroidSpeechToTextProvider) to execute voice commands.
 */
class WastiFloatingService : Service() {

    companion object {
        private const val TAG = "WastiFloatingService"
        private const val NOTIFICATION_CHANNEL_ID = "wasti_floating_bubble_channel"
        private const val NOTIFICATION_ID = 2005

        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Toast.makeText(
                        context,
                        "Please grant System Alert Window (Overlay) permission for Wasti Floating Bubble",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching overlay permission settings", e)
                }
                return
            }

            val intent = Intent(context, WastiFloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WastiFloatingService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager

    private var floatingContainer: FrameLayout? = null
    private var bubbleCard: LinearLayout? = null
    private var micIconView: ImageView? = null
    private var statusTextView: TextView? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private val sttProvider = AndroidSpeechToTextProvider()

    private var isListeningState = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, buildForegroundNotification(), serviceType)
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }

        setupFloatingView()
        observeSTTState()

        logSystemEvent("INFO", "Wasti Floating Action Service initialized and overlay attached.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        sttProvider.destroy()
        serviceScope.cancel()

        floatingContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating window view", e)
            }
        }
        floatingContainer = null

        logSystemEvent("WARN", "Wasti Floating Action Service stopped.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Wasti OS Floating Bubble Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Wasti Floating Action Overlay active over other apps"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Wasti Floating AI Bubble Active")
            .setContentText("Tap overlay bubble anytime to command Wasti OS")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupFloatingView() {
        val dpToPx = { dp: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
        }

        // 1. Root FrameLayout container
        floatingContainer = FrameLayout(this)

        // 2. Bubble Card (pill shape: Icon + Status Text)
        bubbleCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(14), dpToPx(8))

            // Default Background Drawable: Deep Indigo/Navy Slate Pill
            background = createPillBackground(
                fillColor = Color.parseColor("#1E1E2E"),
                strokeColor = Color.parseColor("#6366F1"),
                strokeWidthPx = dpToPx(2),
                radiusPx = dpToPx(28)
            )
        }

        // 3. Mic Icon
        micIconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply {
                marginEnd = dpToPx(8)
            }
        }

        // 4. Status Text
        statusTextView = TextView(this).apply {
            text = "Wasti AI"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        bubbleCard?.addView(micIconView)
        bubbleCard?.addView(statusTextView)
        floatingContainer?.addView(bubbleCard)

        // Window Layout Parameters
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20)
            y = dpToPx(200)
        }

        // Drag and Tap Gesture Handling
        setupDragAndTapListener()

        try {
            windowManager.addView(floatingContainer, windowParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach floating overlay view to WindowManager", e)
        }
    }

    private fun setupDragAndTapListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        floatingContainer?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        windowParams.x = initialX + dx
                        windowParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(floatingContainer, windowParams)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating overlay layout params during drag", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && Math.abs(dx) < 15 && Math.abs(dy) < 15) {
                        onFloatingBubbleTapped()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onFloatingBubbleTapped() {
        if (isListeningState) {
            // Stop listening
            sttProvider.stopListening()
            updateBubbleUi(isListening = false, labelText = "Wasti AI")
        } else {
            // Start speech recognition
            if (!sttProvider.isHardwareAvailable(this)) {
                Toast.makeText(this, "Speech recognizer unavailable on this device", Toast.LENGTH_SHORT).show()
                return
            }

            updateBubbleUi(isListening = true, labelText = "Listening...")

            sttProvider.startListening(
                context = this,
                onBeginningOfSpeech = {
                    updateBubbleUi(isListening = true, labelText = "Speaking...")
                },
                onResult = { result ->
                    handleSpeechResult(result)
                }
            )
        }
    }

    private fun handleSpeechResult(result: STTResult) {
        if (!result.isFinal) {
            if (result.transcript.isNotBlank()) {
                updateBubbleUi(isListening = true, labelText = result.transcript)
            }
            return
        }

        val transcript = result.transcript.trim()
        if (transcript.isNotBlank()) {
            updateBubbleUi(isListening = false, labelText = "Command: '$transcript'")
            Toast.makeText(this, "Wasti Command Received: \"$transcript\"", Toast.LENGTH_SHORT).show()

            // 1. Log voice command to SystemLogEntity database
            logSystemEvent("INFO", "Floating Voice Command Received: '$transcript'")

            // 2. Dispatch command to WastiDeviceController or intent engine
            serviceScope.launch(Dispatchers.IO) {
                executeVoiceCommand(transcript)
            }

            // Reset bubble label back after 3.5 seconds
            bubbleCard?.postDelayed({
                updateBubbleUi(isListening = false, labelText = "Wasti AI")
            }, 3500)
        } else {
            val errorMsg = result.errorMsg ?: "No speech recognized"
            updateBubbleUi(isListening = false, labelText = "Retry Tap")
            logSystemEvent("WARN", "Floating Speech Recognizer: $errorMsg")

            bubbleCard?.postDelayed({
                updateBubbleUi(isListening = false, labelText = "Wasti AI")
            }, 2500)
        }
    }

    private suspend fun executeVoiceCommand(command: String) {
        val lower = command.lowercase()
        when {
            lower.startsWith("open ") || lower.startsWith("launch ") -> {
                val appTarget = command.substringAfter(" ").trim()
                val result = WastiDeviceController.openApp(applicationContext, appTarget)
                logSystemEvent("INFO", "Floating Command Execution [OPEN_APP]: ${result.userFeedback}")
            }
            lower.contains("click ") || lower.contains("tap ") -> {
                val targetText = command.replace("click", "").replace("tap", "").trim()
                val result = WastiDeviceController.simulateTap(applicationContext, targetText)
                logSystemEvent("INFO", "Floating Command Execution [TAP]: ${result.userFeedback}")
            }
            else -> {
                logSystemEvent("INFO", "Floating Voice Command Processed: '$command'")
            }
        }
    }

    private fun observeSTTState() {
        serviceScope.launch {
            sttProvider.currentState.collectLatest { state ->
                isListeningState = state == STTState.LISTENING || state == STTState.PROCESSING
                when (state) {
                    STTState.LISTENING -> updateBubbleUi(isListening = true, labelText = "Listening...")
                    STTState.PROCESSING -> updateBubbleUi(isListening = true, labelText = "Processing...")
                    STTState.ERROR -> {
                        updateBubbleUi(isListening = false, labelText = "Error")
                        bubbleCard?.postDelayed({ updateBubbleUi(isListening = false, labelText = "Wasti AI") }, 2000)
                    }
                    STTState.IDLE -> {
                        // Handled in onResult
                    }
                }
            }
        }
    }

    private fun updateBubbleUi(isListening: Boolean, labelText: String) {
        val dpToPx = { dp: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
        }

        statusTextView?.text = labelText

        val fillColor = if (isListening) Color.parseColor("#065F46") else Color.parseColor("#1E1E2E")
        val strokeColor = if (isListening) Color.parseColor("#10B981") else Color.parseColor("#6366F1")

        bubbleCard?.background = createPillBackground(
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidthPx = dpToPx(2),
            radiusPx = dpToPx(28)
        )
    }

    private fun createPillBackground(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
        radiusPx: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            setStroke(strokeWidthPx, strokeColor)
        }
    }

    private fun logSystemEvent(level: String, message: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = WastiDatabase.getDatabase(applicationContext)
                db.systemLogDao().insertLog(
                    SystemLogEntity(
                        level = level,
                        source = "WastiFloatingService",
                        message = message,
                        details = "Floating Action Bubble System Alert Window Event",
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert system log from WastiFloatingService", e)
            }
        }
    }
}
