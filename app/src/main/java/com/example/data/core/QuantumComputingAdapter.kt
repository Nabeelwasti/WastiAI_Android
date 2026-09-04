package com.example.data.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

data class QuantumQubitState(
    val amplitudeZero: Double,
    val amplitudeOne: Double,
    val probabilityZero: Double,
    val probabilityOne: Double
)

object QuantumComputingAdapter {

    fun simulateSingleQubitHadamard(): QuantumQubitState {
        // |0> state transformed by Hadamard gate H|0> = (|0> + |1>) / sqrt(2)
        val amp = 1.0 / sqrt(2.0)
        return QuantumQubitState(
            amplitudeZero = amp,
            amplitudeOne = amp,
            probabilityZero = amp * amp,
            probabilityOne = amp * amp
        )
    }
}

data class BiologicalSignalFrame(
    val heartRateBpm: Int = 72,
    val heartRateVariabilityMs: Float = 48.0f,
    val alphaWavePower: Float = 14.2f,
    val betaWavePower: Float = 8.5f,
    val stressIndex: Float = 0.25f,
    val timestampMs: Long = System.currentTimeMillis()
)

object BiologicalTelemetryInterface {

    private val _telemetryStream = MutableStateFlow(BiologicalSignalFrame())
    val telemetryStream: StateFlow<BiologicalSignalFrame> = _telemetryStream.asStateFlow()

    fun updateBiologicalMetrics(
        bpm: Int? = null,
        hrv: Float? = null,
        alpha: Float? = null,
        beta: Float? = null
    ) {
        val current = _telemetryStream.value
        _telemetryStream.value = current.copy(
            heartRateBpm = bpm ?: current.heartRateBpm,
            heartRateVariabilityMs = hrv ?: current.heartRateVariabilityMs,
            alphaWavePower = alpha ?: current.alphaWavePower,
            betaWavePower = beta ?: current.betaWavePower,
            timestampMs = System.currentTimeMillis()
        )
    }
}
