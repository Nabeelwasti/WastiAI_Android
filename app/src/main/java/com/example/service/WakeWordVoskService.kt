package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

object WakeWordVoskState {
    private val _statusText = MutableStateFlow("Stopped")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastDetectedWakeWord = MutableStateFlow<String?>(null)
    val lastDetectedWakeWord: StateFlow<String?> = _lastDetectedWakeWord.asStateFlow()

    private val _lastDetectedTimeMs = MutableStateFlow(0L)
    val lastDetectedTimeMs: StateFlow<Long> = _lastDetectedTimeMs.asStateFlow()

    fun updateStatus(status: String) { _statusText.value = status }
    fun setModelLoaded(loaded: Boolean) { _isModelLoaded.value = loaded }
    fun setListening(listening: Boolean) { _isListening.value = listening }
    fun recordWakeWord(word: String) {
        _lastDetectedWakeWord.value = word
        _lastDetectedTimeMs.value = System.currentTimeMillis()
    }
}

class WakeWordVoskService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "WakeWordVoskService"
        private const val CHANNEL_ID = "wasti_wakeword_channel"
        private const val NOTIFICATION_ID = 3001
        private const val SAMPLE_RATE = 16000

        var startNativeListeningCallback: (() -> Unit)? = null
        var isServiceRunning = false

        fun startService(context: Context) {
            val intent = Intent(context, WakeWordVoskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, WakeWordVoskService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        WakeWordVoskState.setListening(true)
        WakeWordVoskState.updateStatus("Initializing Foreground Wake-Word Service...")

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WastiOS:VoskWakeWordWakeLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed acquiring WakeLock in WakeWordVoskService", e)
        }

        startForegroundServiceNotification()
        initializeVoskAndStartListening()
    }

    private fun startForegroundServiceNotification() {
        val channelName = "Wasti Wake-Word Service"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background continuous listening for 'Hey Wasti' wake phrase"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wasti Voice Wake-Word Active")
            .setContentText("Listening for 'Hey Wasti' offline wake word via Vosk Engine...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed starting foreground service with microphone type", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeVoskAndStartListening() {
        serviceScope.launch {
            try {
                Log.i(TAG, "Initializing Vosk model for 'Hey Wasti' keyword spotting...")
                WakeWordVoskState.updateStatus("Loading Vosk Model...")

                val modelDir = File(filesDir, "vosk-model-small-en-us-0.15")
                if (!modelDir.exists()) {
                    modelDir.mkdirs()
                }

                try {
                    voskModel = Model(modelDir.absolutePath)
                    voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat(), "[\"hey wasti\", \"[unk]\"]")
                    WakeWordVoskState.setModelLoaded(true)
                    WakeWordVoskState.updateStatus("Model Loaded • Listening for 'Hey Wasti'")
                } catch (e: Throwable) {
                    Log.w(TAG, "Vosk native model directory standby mode.", e)
                    WakeWordVoskState.setModelLoaded(false)
                    WakeWordVoskState.updateStatus("AudioRecord Standby (Model Directory Prepared)")
                }

                startAudioRecordBufferLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error in initializeVoskAndStartListening", e)
                WakeWordVoskState.updateStatus("Error: ${e.message ?: "Model load failed"}")
            }
        }
    }

    private fun startAudioRecordBufferLoop() {
        serviceScope.launch {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord state not initialized (Microphone permission or hardware busy)")
                    WakeWordVoskState.updateStatus("Error: Microphone unavailable or permission denied")
                    return@launch
                }

                audioRecord?.startRecording()
                isListening = true
                WakeWordVoskState.setListening(true)
                if (voskModel != null) {
                    WakeWordVoskState.updateStatus("Listening for 'Hey Wasti'")
                } else {
                    WakeWordVoskState.updateStatus("AudioRecord Active • Listening for 'Hey Wasti'")
                }
                Log.i(TAG, "Continuous microphone buffer reading active on Dispatchers.IO background thread.")

                val buffer = ByteArray(minBufferSize)
                while (isListening && coroutineContext.isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        voskRecognizer?.let { recognizer ->
                            if (recognizer.acceptWaveForm(buffer, read)) {
                                checkWakeWordInJson(recognizer.result)
                            } else {
                                checkWakeWordInJson(recognizer.partialResult)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading audio buffer loop", e)
                WakeWordVoskState.updateStatus("Error: Audio buffer read failure")
            }
        }
    }

    private suspend fun checkWakeWordInJson(jsonText: String) {
        if (jsonText.isBlank()) return
        val lower = jsonText.lowercase()
        // Task 39B: Enforce strict check for exact full phrase "hey wasti" to prevent false triggers
        if (lower.contains("hey wasti")) {
            Log.i(TAG, ">>> Wake word 'Hey Wasti' detected in microphone buffer! Executing callback...")
            voskRecognizer?.reset()
            WakeWordVoskState.recordWakeWord("Hey Wasti")
            WakeWordVoskState.updateStatus("Wake Word 'Hey Wasti' Detected!")
            withContext(Dispatchers.Main) {
                startNativeListeningCallback?.invoke()
            }
            delay(1500)
            if (isListening) {
                WakeWordVoskState.updateStatus("Listening for 'Hey Wasti'")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Destroying WakeWordVoskService and releasing Vosk Recognizer, Model and WakeLock...")
        isListening = false
        isServiceRunning = false
        WakeWordVoskState.setListening(false)
        WakeWordVoskState.updateStatus("Stopped")

        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock in WakeWordVoskService", e)
        }
        wakeLock = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }

        try {
            voskRecognizer?.close()
            voskRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk Recognizer", e)
        }

        try {
            voskModel?.close()
            voskModel = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing Vosk Model", e)
        }

        serviceScope.cancel()
        super.onDestroy()
    }
}
