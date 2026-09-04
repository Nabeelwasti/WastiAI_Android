package com.example.data.agent.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build

data class DiscoveredSystemCapability(
    val id: String,
    val category: String,
    val name: String,
    val isAvailableOnDevice: Boolean,
    val detail: String
)

object WastiOSApiDiscovery {

    fun scanDeviceEnvironment(context: Context?): List<DiscoveredSystemCapability> {
        val capabilities = mutableListOf<DiscoveredSystemCapability>()

        // 1. Android OS & Runtime Platform
        capabilities.add(
            DiscoveredSystemCapability(
                id = "android_os",
                category = "PLATFORM",
                name = "Android OS Runtime",
                isAvailableOnDevice = true,
                detail = "Android SDK ${Build.VERSION.SDK_INT}, Model: ${Build.MODEL}, Brand: ${Build.BRAND}"
            )
        )

        // 2. Hardware Sensors
        if (context != null) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensors = sensorManager?.getSensorList(Sensor.TYPE_ALL) ?: emptyList()
            sensors.forEach { sensor ->
                capabilities.add(
                    DiscoveredSystemCapability(
                        id = "sensor_${sensor.type}",
                        category = "SENSOR",
                        name = sensor.name,
                        isAvailableOnDevice = true,
                        detail = "Vendor: ${sensor.vendor}, Type: ${sensor.stringType}"
                    )
                )
            }
        }

        // 3. WRE Sandbox & Filesystem Environment
        capabilities.add(
            DiscoveredSystemCapability(
                id = "wre_sandbox",
                category = "EXECUTION",
                name = "Wasti Runtime Environment (WRE)",
                isAvailableOnDevice = true,
                detail = "Native sandbox directory active with pipeline & scripting support"
            )
        )

        return capabilities
    }
}

object CapabilityTrustManager {

    private val trustScores = mutableMapOf<String, Float>()

    fun recordVerificationOutcome(capabilityId: String, isSuccess: Boolean) {
        val current = trustScores[capabilityId] ?: 0.90f
        val newScore = if (isSuccess) {
            (current + 0.02f).coerceAtMost(1.0f)
        } else {
            (current - 0.10f).coerceAtLeast(0.0f)
        }
        trustScores[capabilityId] = newScore
    }

    fun getTrustScore(capabilityId: String): Float {
        return trustScores[capabilityId] ?: 0.90f
    }
}
