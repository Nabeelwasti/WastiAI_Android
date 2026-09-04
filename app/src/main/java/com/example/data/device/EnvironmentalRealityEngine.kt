package com.example.data.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EnvironmentalSensorSnapshot(
    val ambientTempCelsius: Float = 22.5f,
    val relativeHumidityPercent: Float = 45.0f,
    val lightLevelLux: Float = 350.0f,
    val dominantAudioFrequencyHz: Float = 440.0f,
    val motionMagnitude: Float = 0.02f,
    val timestampMs: Long = System.currentTimeMillis()
)

object EnvironmentalRealityEngine {

    private val _currentEnvironment = MutableStateFlow(EnvironmentalSensorSnapshot())
    val currentEnvironment: StateFlow<EnvironmentalSensorSnapshot> = _currentEnvironment.asStateFlow()

    fun updateTelemetry(
        temp: Float? = null,
        humidity: Float? = null,
        lux: Float? = null,
        freq: Float? = null,
        motion: Float? = null
    ) {
        val current = _currentEnvironment.value
        _currentEnvironment.value = current.copy(
            ambientTempCelsius = temp ?: current.ambientTempCelsius,
            relativeHumidityPercent = humidity ?: current.relativeHumidityPercent,
            lightLevelLux = lux ?: current.lightLevelLux,
            dominantAudioFrequencyHz = freq ?: current.dominantAudioFrequencyHz,
            motionMagnitude = motion ?: current.motionMagnitude,
            timestampMs = System.currentTimeMillis()
        )
    }
}
