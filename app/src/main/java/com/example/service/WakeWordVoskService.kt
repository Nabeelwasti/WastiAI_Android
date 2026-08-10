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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

class WakeWordVoskService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false

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

                val modelDir = File(filesDir, "vosk-model-small-en-us-0.15")
                if (!modelDir.exists()) {
                    modelDir.mkdirs()
                }

                try {
                    voskModel = Model(modelDir.absolutePath)
                    voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat(), "[\"hey wasti\", \"wasti\", \"hey\", \"[unk]\"]")
                } catch (e: Throwable) {
                    Log.w(TAG, "Vosk native model not pre-loaded. Service running in AudioRecord listening standby.", e)
                }

                startAudioRecordBufferLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Error in initializeVoskAndStartListening", e)
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
                    return@launch
                }

                audioRecord?.startRecording()
                isListening = true
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
            }
        }
    }

    private suspend fun checkWakeWordInJson(jsonText: String) {
        if (jsonText.isBlank()) return
        val lower = jsonText.lowercase()
        if (lower.contains("hey wasti") || lower.contains("wasti")) {
            Log.i(TAG, ">>> Wake word 'Hey Wasti' detected in microphone buffer! Executing callback...")
            voskRecognizer?.reset()
            withContext(Dispatchers.Main) {
                startNativeListeningCallback?.invoke()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Destroying WakeWordVoskService and releasing Vosk Recognizer and Model...")
        isListening = false
        isServiceRunning = false

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
