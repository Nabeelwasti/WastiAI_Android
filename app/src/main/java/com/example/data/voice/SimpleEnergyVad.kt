package com.example.data.voice

import kotlin.math.sqrt

/**
 * Energy-based RMS Voice Activity Detector with state machine for robust speech boundary detection.
 */
class SimpleEnergyVad(
    override val speechThresholdRms: Double = 0.015,
    override val minSpeechDurationMs: Long = 150L,
    override val maxSilenceDurationMs: Long = 800L
) : VoiceActivityDetector {

    private var inSpeech = false
    private var speechStartTimestamp = 0L
    private var lastSpeechTimestamp = 0L

    companion object {
        fun calculateRms(pcmData: ByteArray): Double {
            if (pcmData.isEmpty()) return 0.0
            var sum = 0.0
            val samples = pcmData.size / 2
            if (samples == 0) return 0.0

            for (i in 0 until samples) {
                val byteLow = pcmData[i * 2].toInt() and 0xFF
                val byteHigh = pcmData[i * 2 + 1].toInt()
                val sample = (byteHigh shl 8) or byteLow
                val normalized = sample / 32768.0
                sum += normalized * normalized
            }
            return sqrt(sum / samples)
        }
    }

    @Synchronized
    override fun processChunk(chunk: AudioChunk): VadState {
        val energy = if (chunk.energyRms > 0.0) chunk.energyRms else calculateRms(chunk.data)
        val isCurrentSpeech = energy >= speechThresholdRms
        val now = chunk.timestamp

        return if (!inSpeech) {
            if (isCurrentSpeech) {
                if (speechStartTimestamp == 0L) {
                    speechStartTimestamp = now
                }
                if (now - speechStartTimestamp >= minSpeechDurationMs) {
                    inSpeech = true
                    lastSpeechTimestamp = now
                    VadState.SPEECH_START
                } else {
                    VadState.SILENCE
                }
            } else {
                speechStartTimestamp = 0L
                VadState.SILENCE
            }
        } else {
            // Currently in speech
            if (isCurrentSpeech) {
                lastSpeechTimestamp = now
                VadState.SPEECH_ACTIVE
            } else {
                if (now - lastSpeechTimestamp >= maxSilenceDurationMs) {
                    inSpeech = false
                    speechStartTimestamp = 0L
                    VadState.SPEECH_END
                } else {
                    VadState.SPEECH_ACTIVE
                }
            }
        }
    }

    @Synchronized
    override fun reset() {
        inSpeech = false
        speechStartTimestamp = 0L
        lastSpeechTimestamp = 0L
    }
}
