package com.example.data.agent.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.UUID

/**
 * Cost and Resource Planner for Wasti AI OS.
 * Evaluates device thermals, battery level, network speed, and token cost
 * to select the optimal execution route (On-Device Local, Sandboxed WASM, Paired Mesh Node, or Cloud).
 */

enum class ExecutionDestination {
    LOCAL_ANDROID_NATIVE,
    LOCAL_WASM_SANDBOX,
    PAIRED_MESH_NODE,
    CLOUD_API_PROVIDER
}

data class ResourceAssessment(
    val batteryPct: Float,
    val isCharging: Boolean,
    val isLowPowerMode: Boolean,
    val recommendedDestination: ExecutionDestination,
    val rationale: String
)

class CostResourcePlanner(
    private val context: Context? = null
) {

    /**
     * Determines the optimal execution target based on runtime constraints.
     */
    fun evaluateResourcePlan(
        taskComplexity: RiskLevel,
        estimatedPayloadBytes: Long,
        requiresToolchain: Boolean
    ): ResourceAssessment {
        var batteryPct = 1.0f
        var isCharging = false

        if (context != null) {
            try {
                val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, ifilter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    batteryPct = level / scale.toFloat()
                }
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            } catch (_: Exception) {}
        }

        val isLowPower = batteryPct < 0.20f && !isCharging

        val (destination, rationale) = when {
            requiresToolchain -> {
                ExecutionDestination.PAIRED_MESH_NODE to "Heavy compile/build toolchain required; routing to paired desktop/server mesh node."
            }
            isLowPower && taskComplexity == RiskLevel.HIGH -> {
                ExecutionDestination.CLOUD_API_PROVIDER to "Low battery ($batteryPct); offloading complex inference to cloud to conserve device power."
            }
            taskComplexity == RiskLevel.LOW || estimatedPayloadBytes < 50_000 -> {
                ExecutionDestination.LOCAL_WASM_SANDBOX to "Low complexity task suitable for instant local zero-overhead WASM sandbox execution."
            }
            else -> {
                ExecutionDestination.LOCAL_ANDROID_NATIVE to "Standard on-device execution selected."
            }
        }

        return ResourceAssessment(
            batteryPct = batteryPct,
            isCharging = isCharging,
            isLowPowerMode = isLowPower,
            recommendedDestination = destination,
            rationale = rationale
        )
    }
}
