package com.example.data.agent.runtime

import com.example.data.core.BciSignalProcessor
import com.example.data.device.EnvironmentalRealityEngine
import com.example.data.device.IoTPhysicalWorldAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CocoonFocusMode {
    NORMAL_STANDBY,
    DEEP_WORK_SHIELD,      // High Beta wave concentration / Deep Flow
    CALM_RECOVERY_COCOON,  // High Alpha / Meditation
    ENVIRONMENT_ALERT      // High noise or temperature anomaly
}

data class FocusCocoonState(
    val activeMode: CocoonFocusMode = CocoonFocusMode.NORMAL_STANDBY,
    val isDoNotDisturbActive: Boolean = false,
    val ambientLightTargetLux: Float = 250.0f,
    val activeBackgroundAcoustic: String = "OFF", // "BROWN_NOISE", "BINAURAL_ALPHA_10HZ", "OFF"
    val smartLightsDimmed: Boolean = false,
    val currentFlowIndex: Int = 72,
    val reason: String = "System initialized and monitoring biological + environmental signals"
)

object FocusCocoonEngine {

    private val _cocoonState = MutableStateFlow(FocusCocoonState())
    val cocoonState: StateFlow<FocusCocoonState> = _cocoonState.asStateFlow()

    fun evaluateSensorsAndAdapt() {
        val bci = BciSignalProcessor.bciState.value
        val env = EnvironmentalRealityEngine.currentEnvironment.value

        val attention = bci.attentionScorePercent
        val meditation = bci.meditationScorePercent

        when {
            // Case 1: High Cognitive Attention (Beta Wave Dominance) -> Trigger Deep Work Shield
            attention >= 75 -> {
                _cocoonState.value = FocusCocoonState(
                    activeMode = CocoonFocusMode.DEEP_WORK_SHIELD,
                    isDoNotDisturbActive = true,
                    ambientLightTargetLux = 300.0f,
                    activeBackgroundAcoustic = "BROWN_NOISE",
                    smartLightsDimmed = true,
                    currentFlowIndex = attention,
                    reason = "High cognitive focus detected (Attention: $attention%). Notification shielding and acoustic barrier enabled."
                )
                // Dispatch command to IoT Home Assistant if available
                IoTPhysicalWorldAdapter.dispatchCommand("home_hub", "{\"action\": \"set_scene\", \"scene\": \"deep_focus\"}")
            }

            // Case 2: High Meditation (Alpha Wave Dominance) -> Calm Recovery
            meditation >= 75 -> {
                _cocoonState.value = FocusCocoonState(
                    activeMode = CocoonFocusMode.CALM_RECOVERY_COCOON,
                    isDoNotDisturbActive = false,
                    ambientLightTargetLux = 150.0f,
                    activeBackgroundAcoustic = "BINAURAL_ALPHA_10HZ",
                    smartLightsDimmed = true,
                    currentFlowIndex = meditation,
                    reason = "Deep relaxation / Alpha burst detected ($meditation%). Ambient lighting softened."
                )
            }

            // Case 3: High Ambient Noise -> Environmental acoustic protection
            env.lightLevelLux > 800.0f || env.motionMagnitude > 0.4f -> {
                _cocoonState.value = FocusCocoonState(
                    activeMode = CocoonFocusMode.ENVIRONMENT_ALERT,
                    isDoNotDisturbActive = false,
                    ambientLightTargetLux = 400.0f,
                    activeBackgroundAcoustic = "OFF",
                    smartLightsDimmed = false,
                    currentFlowIndex = 50,
                    reason = "Environmental noise/motion anomaly detected."
                )
            }

            else -> {
                _cocoonState.value = FocusCocoonState(
                    activeMode = CocoonFocusMode.NORMAL_STANDBY,
                    isDoNotDisturbActive = false,
                    ambientLightTargetLux = 350.0f,
                    activeBackgroundAcoustic = "OFF",
                    smartLightsDimmed = false,
                    currentFlowIndex = 60,
                    reason = "Baseline ambient conditions normal."
                )
            }
        }
    }
}
