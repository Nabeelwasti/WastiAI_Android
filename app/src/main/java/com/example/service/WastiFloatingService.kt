package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.conversation.RoomIdentity
import com.example.data.conversation.UniversalConversationFabric
import com.example.data.core.CommandSubmissionResult
import com.example.data.core.WastiCore
import com.example.data.core.WastiOSRuntime
import com.example.data.db.SystemLogEntity
import com.example.data.db.WastiDatabase
import com.example.data.device.WastiDeviceController
import com.example.data.voice.provider.AndroidSpeechToTextProvider
import com.example.data.voice.provider.STTResult
import com.example.data.voice.provider.STTState
import com.example.util.WastiSpeechSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Task 39C: Text & Voice Floating Bubble UI (System Alert Window Service)
 * Overlay floating action bubble accessible over any application.
 * When tapped, expands to reveal Voice (Microphone Button), Text (EditText + Send Button),
 * Scrollable AI Response Card with Copy/Speak/Clear actions, and Screen Context Suggestions.
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
    private var collapsedView: LinearLayout? = null
    private var expandedView: LinearLayout? = null

    private var micIconView: ImageView? = null
    private var statusTextView: TextView? = null
    private var expandedStatusTextView: TextView? = null
    private var commandEditText: EditText? = null
    private var responseTextLabel: TextView? = null
    private var speakerToggleBtn: TextView? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private val sttProvider = AndroidSpeechToTextProvider()

    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var isTtsEnabled = true
    private var lastDisplayedResponse: String? = null

    private var isListeningState = false
    private var isExpanded = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WastiOS:FloatingServiceWakeLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire PARTIAL_WAKE_LOCK in WastiFloatingService", e)
        }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildForegroundNotification(),
                0
            )
        } else {
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
        }

        // [P0-37] Overlay Permission Verification
        if (!com.example.assistant.PermissionManager.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW (overlay) permission not granted. Stopping WastiFloatingService.")
            stopSelf()
            return
        }

        // [P0-37] Emergency Stop Integration
        com.example.data.agent.runtime.WastiEmergencyStopController.registerScope(serviceScope)
        serviceScope.launch {
            com.example.data.agent.runtime.WastiEmergencyStopController.stopStateFlow.collectLatest { snap ->
                if (snap.isStopped) {
                    Log.w(TAG, "Emergency stop active in WastiFloatingService: hiding overlay")
                    withContext(Dispatchers.Main) {
                        floatingContainer?.visibility = View.GONE
                    }
                }
            }
        }

        initTextToSpeech()
        setupFloatingView()
        observeSTTState()
        observeToolProgress()
        observeRuntimeContext()
        observeFabricEvents()

        logSystemEvent("INFO", "Wasti Floating Action Service initialized and overlay attached.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        sttProvider.destroy()
        stopSpeech()
        try {
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TextToSpeech", e)
        }
        textToSpeech = null
        serviceScope.cancel()

        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        wakeLock = null

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

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    textToSpeech?.language = Locale.US
                } else {
                    Log.w(TAG, "TTS init returned status code: $status")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize TextToSpeech in WastiFloatingService", e)
        }
    }

    private fun speakText(text: String) {
        if (!isTtsEnabled || !isTtsReady || textToSpeech == null) return
        val sanitized = WastiSpeechSanitizer.sanitizeForSpeech(text)
        if (sanitized.isBlank()) return
        try {
            val params = Bundle()
            val utteranceId = "wasti_bubble_${System.currentTimeMillis()}"
            textToSpeech?.speak(sanitized.take(600), TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text in WastiFloatingService", e)
        }
    }

    private fun stopSpeech() {
        try {
            textToSpeech?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TextToSpeech playback", e)
        }
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
            .setContentText("Tap overlay bubble anytime to command Wasti OS (Voice or Text)")
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

        // Root FrameLayout container
        floatingContainer = FrameLayout(this)

        // -------------------------------------------------------------
        // 1. Collapsed Bubble View (Compact Pill Shape)
        // -------------------------------------------------------------
        collapsedView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(14), dpToPx(8))
            background = createCardBackground(
                fillColor = Color.parseColor("#1E1E2E"),
                strokeColor = Color.parseColor("#6366F1"),
                strokeWidthPx = dpToPx(2),
                radiusPx = dpToPx(28)
            )
        }

        micIconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply {
                marginEnd = dpToPx(8)
            }
        }

        statusTextView = TextView(this).apply {
            text = "Wasti AI"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        collapsedView?.addView(micIconView)
        collapsedView?.addView(statusTextView)

        // -------------------------------------------------------------
        // 2. Expanded Control Panel View (Voice + Text Command Interface)
        // -------------------------------------------------------------
        expandedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            visibility = View.GONE
            background = createCardBackground(
                fillColor = Color.parseColor("#181825"),
                strokeColor = Color.parseColor("#818CF8"),
                strokeWidthPx = dpToPx(2),
                radiusPx = dpToPx(18)
            )
        }

        // Header Bar inside Expanded View
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
        }

        val voiceMicBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor("#818CF8"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)).apply {
                marginEnd = dpToPx(8)
            }
            setOnClickListener {
                toggleVoiceListening()
            }
        }

        expandedStatusTextView = TextView(this).apply {
            text = "Wasti AI Assistant"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        speakerToggleBtn = TextView(this).apply {
            text = if (isTtsEnabled) "🔊" else "🔇"
            setTextColor(Color.parseColor("#A6ADC8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(4)
            }
            setOnClickListener {
                isTtsEnabled = !isTtsEnabled
                text = if (isTtsEnabled) "🔊" else "🔇"
                if (!isTtsEnabled) {
                    stopSpeech()
                }
                Toast.makeText(
                    applicationContext,
                    if (isTtsEnabled) "Speech response enabled" else "Speech response muted",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setOnClickListener {
                collapseOverlay()
            }
        }

        headerRow.addView(voiceMicBtn)
        headerRow.addView(expandedStatusTextView)
        headerRow.addView(speakerToggleBtn)
        headerRow.addView(closeBtn)

        // -------------------------------------------------------------
        // Response Display Box (Scrollable Output + Action Buttons)
        // -------------------------------------------------------------
        val responseCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            background = createCardBackground(
                fillColor = Color.parseColor("#11111B"),
                strokeColor = Color.parseColor("#313244"),
                strokeWidthPx = dpToPx(1),
                radiusPx = dpToPx(10)
            )
        }

        responseTextLabel = TextView(this).apply {
            text = "Ready. Tap the mic or type a command to run."
            setTextColor(Color.parseColor("#CDD6F4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextIsSelectable(true)
            setLineSpacing(dpToPx(2).toFloat(), 1.1f)
        }

        val responseActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        }

        val copyActionBtn = TextView(this).apply {
            text = "📋 Copy"
            setTextColor(Color.parseColor("#A6ADC8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            background = createCardBackground(
                fillColor = Color.parseColor("#1E1E2E"),
                strokeColor = Color.parseColor("#45475A"),
                strokeWidthPx = dpToPx(1),
                radiusPx = dpToPx(6)
            )
            setOnClickListener {
                val content = responseTextLabel?.text?.toString() ?: ""
                if (content.isNotBlank()) {
                    copyToClipboard(content)
                }
            }
        }

        val replayActionBtn = TextView(this).apply {
            text = "🔊 Speak"
            setTextColor(Color.parseColor("#A6ADC8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(6)
            }
            background = createCardBackground(
                fillColor = Color.parseColor("#1E1E2E"),
                strokeColor = Color.parseColor("#45475A"),
                strokeWidthPx = dpToPx(1),
                radiusPx = dpToPx(6)
            )
            setOnClickListener {
                val content = responseTextLabel?.text?.toString() ?: ""
                if (content.isNotBlank()) {
                    speakText(content)
                }
            }
        }

        val clearActionBtn = TextView(this).apply {
            text = "🧹 Clear"
            setTextColor(Color.parseColor("#A6ADC8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(6)
            }
            background = createCardBackground(
                fillColor = Color.parseColor("#1E1E2E"),
                strokeColor = Color.parseColor("#45475A"),
                strokeWidthPx = dpToPx(1),
                radiusPx = dpToPx(6)
            )
            setOnClickListener {
                responseTextLabel?.text = "Ready. Tap the mic or type a command to run."
                lastDisplayedResponse = null
                stopSpeech()
                expandedStatusTextView?.text = "Wasti AI Assistant"
            }
        }

        responseActionsRow.addView(copyActionBtn)
        responseActionsRow.addView(replayActionBtn)
        responseActionsRow.addView(clearActionBtn)

        responseCard.addView(responseTextLabel)
        responseCard.addView(responseActionsRow)

        val responseScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(140)
            ).apply {
                bottomMargin = dpToPx(8)
            }
            isFillViewport = true
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        responseScrollView.addView(responseCard)

        // Input Row (EditText + Send Button)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        commandEditText = EditText(this).apply {
            hint = "Ask or command Wasti AI..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            background = createCardBackground(
                fillColor = Color.parseColor("#313244"),
                strokeColor = Color.parseColor("#45475A"),
                strokeWidthPx = dpToPx(1),
                radiusPx = dpToPx(10)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpToPx(8)
            }
        }

        val sendButton = TextView(this).apply {
            text = "Send"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            background = createCardBackground(
                fillColor = Color.parseColor("#6366F1"),
                strokeColor = Color.TRANSPARENT,
                strokeWidthPx = 0,
                radiusPx = dpToPx(10)
            )
            setOnClickListener {
                val typedText = commandEditText?.text?.toString() ?: ""
                sendTypedCommand(typedText)
            }
        }

        inputRow.addView(commandEditText)
        inputRow.addView(sendButton)

        // Quick Action Chips Row
        val actionChipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }

        val suggestionBtn = TextView(this).apply {
            text = "✨ Screen Suggestions"
            setTextColor(Color.parseColor("#818CF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            background = createCardBackground(
                fillColor = Color.parseColor("#313244"),
                strokeColor = Color.parseColor("#818CF8"),
                strokeWidthPx = dpToPx(1),
                radiusPx = dpToPx(8)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpToPx(6)
            }
            setOnClickListener {
                expandedStatusTextView?.text = "Scraping screen suggestions..."
                serviceScope.launch {
                    val suggestions = WastiSuggestionOverlay.analyzeScreenAndGenerateSuggestions(applicationContext)
                    val text = if (suggestions.isNotEmpty()) suggestions.first() else "No screen text detected"
                    commandEditText?.setText(text)
                    expandedStatusTextView?.text = "Suggestion ready"
                }
            }
        }

        val statusQuickBtn = TextView(this).apply {
            text = "📊 System Status"
            setTextColor(Color.parseColor("#A6E3A1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            background = createCardBackground(
                fillColor = Color.parseColor("#313244"),
                strokeColor = Color.parseColor("#A6E3A1"),
                strokeWidthPx = dpToPx(1),
                radiusPx = dpToPx(8)
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                sendTypedCommand("Query system telemetry, active nodes, and AI brain status")
            }
        }

        actionChipsRow.addView(suggestionBtn)
        actionChipsRow.addView(statusQuickBtn)

        expandedView?.addView(headerRow)
        expandedView?.addView(responseScrollView)
        expandedView?.addView(inputRow)
        expandedView?.addView(actionChipsRow)

        floatingContainer?.addView(collapsedView)
        floatingContainer?.addView(expandedView)

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20)
            y = dpToPx(200)
        }

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
                        if (!isExpanded) {
                            expandOverlay()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun expandOverlay() {
        val dpToPx = { dp: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
        }

        isExpanded = true
        collapsedView?.visibility = View.GONE
        expandedView?.visibility = View.VISIBLE

        // Remove FLAG_NOT_FOCUSABLE so the soft keyboard and EditText can obtain touch focus
        windowParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        windowParams.width = dpToPx(320)

        try {
            windowManager.updateViewLayout(floatingContainer, windowParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error expanding floating overlay", e)
        }
    }

    private fun collapseOverlay() {
        isExpanded = false
        expandedView?.visibility = View.GONE
        collapsedView?.visibility = View.VISIBLE

        // Hide soft keyboard if active
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        commandEditText?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }

        // Restore FLAG_NOT_FOCUSABLE so touch passes through outside the bubble
        windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        windowParams.width = WindowManager.LayoutParams.WRAP_CONTENT

        try {
            windowManager.updateViewLayout(floatingContainer, windowParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error collapsing floating overlay", e)
        }
    }

    private fun observeToolProgress() {
        serviceScope.launch {
            WastiCore.toolProgressState.collectLatest { progress ->
                if (progress.stage != com.example.data.core.ProgressStage.IDLE) {
                    val label = "[${progress.stage.name}] ${progress.statusMessage}"
                    statusTextView?.text = label
                    expandedStatusTextView?.text = label
                }
            }
        }
    }

    private fun observeRuntimeContext() {
        serviceScope.launch {
            try {
                val runtime = WastiOSRuntime.getInstance(applicationContext)
                runtime.activeContext.collectLatest { ctx ->
                    if (ctx.isBusy) {
                        statusTextView?.text = "🧠 Thinking..."
                        expandedStatusTextView?.text = ctx.progressMessage.take(45)
                    } else if (!ctx.lastResultSummary.isNullOrBlank()) {
                        val summary = ctx.lastResultSummary ?: ""
                        if (summary != lastDisplayedResponse && summary.isNotBlank()) {
                            handleExecutionCompleted(summary)
                        }
                    } else if (!ctx.lastError.isNullOrBlank()) {
                        val err = ctx.lastError ?: ""
                        if (err != lastDisplayedResponse && err.isNotBlank()) {
                            lastDisplayedResponse = err
                            responseTextLabel?.text = "❌ $err"
                            expandedStatusTextView?.text = "Execution Error"
                            statusTextView?.text = "❌ Error"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing runtime context in WastiFloatingService", e)
            }
        }
    }

    private fun observeFabricEvents() {
        serviceScope.launch {
            try {
                val fabric = UniversalConversationFabric.getInstance(applicationContext)
                fabric.fabricEvents.collectLatest { event ->
                    if (event.executionPhase == "TASK_COMPLETED" || event.severity == "SUCCESS") {
                        val cleanSummary = event.message.removePrefix("Task completed: ").trim()
                        if (cleanSummary.isNotBlank() && cleanSummary != lastDisplayedResponse) {
                            handleExecutionCompleted(cleanSummary)
                        }
                    } else if (event.executionPhase == "TASK_FAILED" || event.severity == "ERROR") {
                        val err = event.message
                        if (err.isNotBlank() && err != lastDisplayedResponse) {
                            lastDisplayedResponse = err
                            withContext(Dispatchers.Main) {
                                responseTextLabel?.text = "❌ $err"
                                expandedStatusTextView?.text = "Task Failed"
                                statusTextView?.text = "❌ Error"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error observing fabric events in WastiFloatingService", e)
            }
        }
    }

    private fun handleExecutionCompleted(summary: String) {
        serviceScope.launch(Dispatchers.Main) {
            if (summary.isNotBlank() && summary != lastDisplayedResponse) {
                lastDisplayedResponse = summary
                responseTextLabel?.text = summary
                expandedStatusTextView?.text = "✅ Complete"
                statusTextView?.text = "✅ AI Ready"
                speakText(summary)
            }
        }
    }

    private fun copyToClipboard(content: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Wasti AI Response", content)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(this, "Copied response to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard", e)
        }
    }

    private fun toggleVoiceListening() {
        if (isListeningState) {
            sttProvider.stopListening()
            updateBubbleUi(isListening = false, labelText = "Wasti AI Assistant")
        } else {
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

    private fun sendTypedCommand(text: String) {
        val command = text.trim()
        if (command.isBlank()) return

        commandEditText?.setText("")
        expandedStatusTextView?.text = "🧠 Reasoning..."
        responseTextLabel?.text = "⏳ Processing command:\n\"$command\"..."
        statusTextView?.text = "🧠 Thinking..."

        logSystemEvent("INFO", "Floating Typed Command Sent: '$command'")

        serviceScope.launch(Dispatchers.IO) {
            executeCommand(command)
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
            expandedStatusTextView?.text = "🧠 Reasoning..."
            responseTextLabel?.text = "⏳ Processing voice command:\n\"$transcript\"..."
            statusTextView?.text = "🧠 Thinking..."

            logSystemEvent("INFO", "Floating Voice Command Received: '$transcript'")

            serviceScope.launch(Dispatchers.IO) {
                executeCommand(transcript)
            }
        } else {
            val errorMsg = result.errorMsg ?: "No speech recognized"
            updateBubbleUi(isListening = false, labelText = "Retry Voice")
            logSystemEvent("WARN", "Floating Speech Recognizer: $errorMsg")

            expandedView?.postDelayed({
                updateBubbleUi(isListening = false, labelText = "Wasti AI Assistant")
            }, 2500)
        }
    }

    private suspend fun executeCommand(command: String) {
        try {
            val result = UniversalConversationFabric.getInstance(applicationContext).submitTask(
                prompt = command,
                originRoom = RoomIdentity.FLOATING_BUBBLE.roomId,
                executionMode = com.example.data.agent.runtime.ExecutionMode.AUTONOMOUS,
                targetAgentId = "ceo_agent"
            )
            logSystemEvent("INFO", "Floating Command Dispatched via UniversalConversationFabric: $command -> $result")

            when (result) {
                is CommandSubmissionResult.ImmediateSuccess -> {
                    handleExecutionCompleted(result.output)
                }
                is CommandSubmissionResult.Rejected -> {
                    val errorMsg = "Command rejected: ${result.reason}"
                    withContext(Dispatchers.Main) {
                        responseTextLabel?.text = "❌ $errorMsg"
                        expandedStatusTextView?.text = "Rejected"
                        statusTextView?.text = "❌ Rejected"
                        speakText(errorMsg)
                    }
                }
                is CommandSubmissionResult.Accepted -> {
                    // Task successfully accepted into execution loop; updates handled by observers
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing floating command via UniversalConversationFabric", e)
            logSystemEvent("ERROR", "Floating Command Execution Failure: ${e.message}")
            withContext(Dispatchers.Main) {
                val errorMsg = e.message ?: "Unknown error"
                responseTextLabel?.text = "❌ Execution Error: $errorMsg"
                expandedStatusTextView?.text = "Error"
                statusTextView?.text = "❌ Error"
                speakText("Error: $errorMsg")
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
                        expandedView?.postDelayed({ updateBubbleUi(isListening = false, labelText = "Wasti AI Assistant") }, 2000)
                    }
                    STTState.IDLE -> {}
                }
            }
        }
    }

    private fun updateBubbleUi(isListening: Boolean, labelText: String) {
        statusTextView?.text = labelText
        expandedStatusTextView?.text = labelText

        val dpToPx = { dp: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
        }

        val fillColor = if (isListening) Color.parseColor("#065F46") else Color.parseColor("#1E1E2E")
        val strokeColor = if (isListening) Color.parseColor("#10B981") else Color.parseColor("#6366F1")

        collapsedView?.background = createCardBackground(
            fillColor = fillColor,
            strokeColor = strokeColor,
            strokeWidthPx = dpToPx(2),
            radiusPx = dpToPx(28)
        )
    }

    private fun createCardBackground(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
        radiusPx: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
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
                        details = "Floating Action Bubble System Overlay Event",
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert system log from WastiFloatingService", e)
            }
        }
    }
}
