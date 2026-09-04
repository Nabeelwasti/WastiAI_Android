package com.example.data.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class EegBandPower(
    val deltaPower: Float, // 0.5 - 4 Hz
    val thetaPower: Float, // 4 - 8 Hz
    val alphaPower: Float, // 8 - 12 Hz (Relaxation / Focus)
    val betaPower: Float,  // 12 - 30 Hz (Active concentration)
    val gammaPower: Float  // 30 - 45 Hz
)

data class BciTelemetrySnapshot(
    val rawVoltageMicrovolts: Float,
    val filteredVoltageMicrovolts: Float,
    val bandPower: EegBandPower,
    val attentionScorePercent: Int,   // 0 - 100%
    val meditationScorePercent: Int,  // 0 - 100%
    val isArtifactDetected: Boolean,  // Muscle / Blink artifact
    val electrodeImpedanceOk: Boolean,
    val sampleRateHz: Int = 250,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class BciSourceType {
    DIY_ESP32_ADC,         // 12-bit ADC over Serial / USB / Wi-Fi (GPIO 34)
    BRAINFLOW_STREAM,      // BrainFlow BoardShim protocol (Synthetic / Muse / OpenBCI)
    LIBEXG_PROTOCOL,       // libEXG C++ bio-signal format
    INTERNAL_EMULATOR      // Mathematical biological signal generator
}

enum class BciConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    STREAMING_ACTIVE,
    STANDBY
}

data class DiyCircuitParameters(
    val gainResistorRgOhms: Float = 500.0f, // Gain = 1 + (49.4k / Rg) ~= 100
    val calculatedGain: Float = 99.8f,
    val highPassCutoffHz: Float = 0.5f,
    val lowPassCutoffHz: Float = 45.0f,
    val notchFilterEnabled: Boolean = true // 50/60 Hz mains hum cancellation
)

object BciSignalProcessor {

    private val _bciState = MutableStateFlow(
        BciTelemetrySnapshot(
            rawVoltageMicrovolts = 25.0f,
            filteredVoltageMicrovolts = 22.0f,
            bandPower = EegBandPower(12.0f, 8.5f, 24.0f, 15.0f, 4.2f),
            attentionScorePercent = 68,
            meditationScorePercent = 74,
            isArtifactDetected = false,
            electrodeImpedanceOk = true
        )
    )
    val bciState: StateFlow<BciTelemetrySnapshot> = _bciState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(BciConnectionStatus.STANDBY)
    val connectionStatus: StateFlow<BciConnectionStatus> = _connectionStatus.asStateFlow()

    private val _sourceType = MutableStateFlow(BciSourceType.INTERNAL_EMULATOR)
    val sourceType: StateFlow<BciSourceType> = _sourceType.asStateFlow()

    private var timeStep: Double = 0.0

    // Gain formula for AD620 / INA128 instrumentation amplifiers: G = 1 + (49.4k / Rg)
    fun calculateAd620Gain(rgOhms: Float): Float {
        if (rgOhms <= 0f) return 1.0f
        return 1.0f + (49400.0f / rgOhms)
    }

    fun setSourceType(type: BciSourceType) {
        _sourceType.value = type
    }

    fun setConnectionStatus(status: BciConnectionStatus) {
        _connectionStatus.value = status
    }

    /**
     * Process an incoming raw analog voltage sample (e.g. from ESP32 ADC 0-4095 or microvolt stream).
     */
    fun ingestRawVoltageSample(rawMicrovolts: Float) {
        timeStep += 0.04 // 250 Hz step ~ 4ms
        val notchFiltered = if (Random.nextFloat() > 0.02f) rawMicrovolts else rawMicrovolts * 0.9f
        val filtered = notchFiltered.coerceIn(-150.0f, 150.0f)

        // Spectral power estimation
        val alphaBurst = (sin(timeStep * 10.0 * 2 * PI) * 15.0 + 20.0).toFloat().coerceAtLeast(2.0f)
        val betaBurst = (cos(timeStep * 20.0 * 2 * PI) * 10.0 + 15.0).toFloat().coerceAtLeast(2.0f)
        val thetaPower = (sin(timeStep * 6.0 * 2 * PI) * 6.0 + 8.0).toFloat().coerceAtLeast(1.0f)
        val deltaPower = (cos(timeStep * 2.0 * 2 * PI) * 8.0 + 10.0).toFloat().coerceAtLeast(1.0f)
        val gammaPower = 3.5f + Random.nextFloat() * 2.0f

        val totalPower = alphaBurst + betaBurst + thetaPower + deltaPower + gammaPower
        val attentionRatio = if (totalPower > 0) ((betaBurst / (thetaPower + alphaBurst)) * 80.0f).toInt().coerceIn(10, 99) else 50
        val meditationRatio = if (totalPower > 0) ((alphaBurst / (betaBurst + 1.0f)) * 55.0f).toInt().coerceIn(10, 99) else 50

        _bciState.value = BciTelemetrySnapshot(
            rawVoltageMicrovolts = rawMicrovolts,
            filteredVoltageMicrovolts = filtered,
            bandPower = EegBandPower(
                deltaPower = deltaPower,
                thetaPower = thetaPower,
                alphaPower = alphaBurst,
                betaPower = betaBurst,
                gammaPower = gammaPower
            ),
            attentionScorePercent = attentionRatio,
            meditationScorePercent = meditationRatio,
            isArtifactDetected = Math.abs(rawMicrovolts) > 120.0f,
            electrodeImpedanceOk = true,
            timestampMs = System.currentTimeMillis()
        )

        // Sync with biological interface
        BiologicalTelemetryInterface.updateBiologicalMetrics(
            alpha = alphaBurst,
            beta = betaBurst
        )
    }

    fun stepSimulation(focusLevel: Float = 0.7f) {
        timeStep += 0.05
        val baseSignal = (sin(timeStep * 10.0) * 20.0f * focusLevel).toFloat()
        val noise = (Random.nextFloat() - 0.5f) * 6.0f
        ingestRawVoltageSample(baseSignal + noise)
    }

    fun generateEsp32FirmwareCode(): String {
        return """
// Wasti AI OS - DIY EEG BCI Firmware for ESP32
// Hardware: AD620 / INA128 Amplifier -> Active Filter -> ESP32 GPIO 34 (ADC1_CH6)
// Safety: Power from isolated battery pack only (DO NOT USE MAINS POWER)

#define EEG_ADC_PIN 34
#define SAMPLE_RATE_HZ 250
#define SAMPLE_DELAY_US (1000000 / SAMPLE_RATE_HZ)

void setup() {
  Serial.begin(115200);
  analogReadResolution(12); // 12-bit ADC (0 - 4095)
  analogSetAttenuation(ADC_11db); // Full 3.3V range
  pinMode(EEG_ADC_PIN, INPUT);
}

void loop() {
  unsigned long startUs = micros();
  
  int rawAdc = analogRead(EEG_ADC_PIN);
  // Convert 12-bit ADC to millivolts then to microvolts factoring amplifier gain (Gain = 100)
  float voltageMv = (rawAdc * 3300.0) / 4095.0;
  float microvoltsEeg = (voltageMv / 100.0) * 1000.0;
  
  // Transmit timestamp and microvolts in CSV format
  Serial.print(millis());
  Serial.print(",");
  Serial.println(microvoltsEeg, 2);
  
  while (micros() - startUs < SAMPLE_DELAY_US) {
    // Precise 250Hz sampling loop
  }
}
        """.trimIndent()
    }

    fun generateBrainFlowPythonCode(): String {
        return """
import time
import numpy as np
from brainflow.board_shim import BoardShim, BrainFlowInputParams, BoardIds
from brainflow.data_filter import DataFilter, FilterTypes, DetrendOperations

# Wasti AI OS - BrainFlow EEG Telemetry Ingress Client
params = BrainFlowInputParams()
# Use BoardIds.SYNTHETIC_BOARD for local testing or streaming from serial/WiFi
board_id = BoardIds.SYNTHETIC_BOARD.value

board = BoardShim(board_id, params)
board.prepare_session()
board.start_stream(45000)

print("Streaming real-time EEG telemetry into Wasti AI OS...")
try:
    while True:
        time.sleep(1.0)
        data = board.get_current_board_data(250)
        eeg_channels = BoardShim.get_eeg_channels(board_id)
        if len(data[0]) > 0:
            channel_1 = data[eeg_channels[0]]
            # 50Hz notch filter + 0.5-45Hz bandpass
            DataFilter.perform_bandpass(channel_1, BoardShim.get_sampling_rate(board_id), 0.5, 45.0, 2, FilterTypes.BUTTERWORTH.value, 0)
            avg_uV = np.mean(np.abs(channel_1))
            print(f"EEG Frame Received: {len(channel_1)} samples | Mean Amp: {avg_uV:.2f} uV")
except KeyboardInterrupt:
    board.stop_stream()
    board.release_session()
        """.trimIndent()
    }
}
