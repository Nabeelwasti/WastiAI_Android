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
import android.os.PowerManager
import android.provider.Settings
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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.core.WastiCore
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
 * Task 39C: Text & Voice Floating Bubble UI (System Alert Window Service)
 * Overlay floating action bubble accessible over any application.
 * When tapped, expands to reveal both Voice (Microphone Button) and Text (EditText + Send Button)
 * input capabilities to execute commands via WastiCore and WastiDeviceController in the background.
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

    private lateinit var windowParams: WindowManager.LayoutParams
    private val sttProvider = AndroidSpeechToTextProvider()

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
        observeToolProgress()

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
        headerRow.addView(closeBtn)

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
            hint = "Type command..."
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

        val suggestionBtn = TextView(this).apply {
            text = "✨ AI Screen Suggestions"
            setTextColor(Color.parseColor("#818CF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = createCardBackground(
                fillColor = Color.parseColor("#313244"),
                strokeColor = Color.parseColor("#818CF8"),
                strokeWidthPx = dpToPx(1),
                radiusPx = dpToPx(8)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
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

        expandedView?.addView(headerRow)
        expandedView?.addView(inputRow)
        expandedView?.addView(suggestionBtn)

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
        windowParams.width = dpToPx(290)

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
        expandedStatusTextView?.text = "Sending command..."
        Toast.makeText(this, "Wasti Command Sent: \"$command\"", Toast.LENGTH_SHORT).show()

        logSystemEvent("INFO", "Floating Typed Command Sent: '$command'")

        serviceScope.launch(Dispatchers.IO) {
            executeCommand(command)
        }

        expandedView?.postDelayed({
            expandedStatusTextView?.text = "Wasti AI Assistant"
            collapseOverlay()
        }, 2000)
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
            Toast.makeText(this, "Wasti Voice Command: \"$transcript\"", Toast.LENGTH_SHORT).show()

            logSystemEvent("INFO", "Floating Voice Command Received: '$transcript'")

            serviceScope.launch(Dispatchers.IO) {
                executeCommand(transcript)
            }

            expandedView?.postDelayed({
                updateBubbleUi(isListening = false, labelText = "Wasti AI Assistant")
                collapseOverlay()
            }, 3000)
        } else {
            val errorMsg = result.errorMsg ?: "No speech recognized"
            updateBubbleUi(isListening = false, labelText = "Retry Voice")
            logSystemEvent("WARN", "Floating Speech Recognizer: $errorMsg")

            expandedView?.postDelayed({
                updateBubbleUi(isListening = false, labelText = "Wasti AI Assistant")
            }, 2000)
        }
    }

    private suspend fun executeCommand(command: String) {
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
                try {
                    val (response, _) = WastiCore.executeOrchestratedRequest(
                        userPrompt = command,
                        systemInstruction = "You are Wasti OS floating assistant. Provide concise actionable mobile execution response.",
                        activeAgentId = "ceo_agent"
                    )
                    logSystemEvent("INFO", "Floating Command WastiCore Execution: ${response.take(100)}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing floating command via WastiCore", e)
                    logSystemEvent("ERROR", "Floating Command Execution Failure: ${e.message}")
                }
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
